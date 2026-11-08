import gzip, hashlib, os, struct, sys
from pathlib import Path

root = Path(__file__).resolve().parent
boot_path = root / "current-boot.img"
module_path = root / "8821cu-active.ko"
script_path = root / "rtl8821cu-watch.sh"
bluetooth_usb = "--bluetooth-usb" in sys.argv[1:]
policy_path = root / ("sepolicy-rtl-btboot.bin" if bluetooth_usb else "sepolicy-rtl.bin")
out_path = root / ("caixa-boot-rtl-btusb.img" if bluetooth_usb else "caixa-boot-rtl-late.img")
boot = boot_path.read_bytes()
assert boot[:8] == b"ANDROID!"
page = struct.unpack_from("<I", boot, 36)[0]
ks = struct.unpack_from("<I", boot, 8)[0]
rs = struct.unpack_from("<I", boot, 16)[0]
ss = struct.unpack_from("<I", boot, 24)[0]
dt_size = struct.unpack_from("<I", boot, 40)[0]
ko = page
ro = ((ko + ks + page - 1) // page) * page
so = ((ro + rs + page - 1) // page) * page
do = ((so + ss + page - 1) // page) * page
kernel = boot[ko:ko+ks]
ramdisk_gz = boot[ro:ro+rs]
second = boot[so:so+ss]
dtb = boot[do:do+dt_size]
ramdisk = gzip.decompress(ramdisk_gz)

def parse_cpio(data):
    entries = []
    off = 0
    while off + 110 <= len(data):
        hdr = data[off:off+110]
        if hdr[:6] not in (b"070701", b"070702"):
            raise ValueError(f"bad cpio magic at {off}: {hdr[:6]!r}")
        fields = [int(hdr[6+i*8:14+i*8], 16) for i in range(13)]
        off += 110
        namesz = fields[11]
        name = data[off:off+namesz-1].decode("utf-8", "surrogateescape")
        off += namesz
        off = (off + 3) & ~3
        size = fields[6]
        payload = data[off:off+size]
        off += size
        off = (off + 3) & ~3
        entries.append([fields, name, payload, hdr[:6]])
        if name == "TRAILER!!!":
            break
    if not entries or entries[-1][1] != "TRAILER!!!":
        raise ValueError("cpio trailer not found")
    return entries

entries = parse_cpio(ramdisk)
files = {e[1]: e for e in entries}
if files["sepolicy"][2] != (root / "sepolicy-original.bin").read_bytes():
    raise ValueError("the patched SELinux policy must come from this boot image")
files["sepolicy"][2] = policy_path.read_bytes()
qcom = files.get("init.qcom.rc")
if qcom is None:
    raise ValueError("init.qcom.rc missing")
text = qcom[2].decode("utf-8")
marker = "# msm specific files that need to be created on /data\non post-fs-data"
watch_args = " --bluetooth-only" if bluetooth_usb else ""
action = (
    "# Caixa RTL8821CU module insertion uses init's insmod builtin.\n"
    "on property:sys.rtl8821cu.load=1\n"
    "    insmod /sbin/8821cu.ko\n\n"
    f"service rtlwatch /system/bin/sh /sbin/rtl8821cu-watch.sh{watch_args}\n"
    "    class late_start\n"
    "    user root\n"
    "    group root\n"
    "    seclabel u:r:qti_init_shell:s0\n"
    "    disabled\n\n"
)
if bluetooth_usb:
    action += (
        "service rtlbt /system/vendor/bin/rtl_bt_h4\n"
        "    class core\n"
        "    user root\n"
        "    group root\n"
        "    seclabel u:r:qti_init_shell:s0\n"
        "    disabled\n\n"
        "on post-fs-data\n"
        "    mount none /system/vendor/lib/libbt-rtl-usb.so /system/vendor/lib/libbt-vendor.so bind\n"
        "    setprop qcom.bluetooth.soc rome\n"
        "    setprop bluetooth.hciattach false\n"
        "    insmod /sbin/btusb.ko\n"
        "    start rtlbt\n"
        "    start rtlwatch\n\n"
    )
else:
    action += "on property:sys.boot_completed=1\n    start rtlwatch\n\n"
if "rtl8821cu-watch.sh" not in text:
    if marker not in text:
        raise ValueError("post-fs-data insertion point not found")
    text = text.replace(marker, action + marker, 1)
qcom[2] = text.encode("utf-8")
# Add immutable payload files into the boot ramdisk.
ino = max(e[0][0] for e in entries) + 1
payloads = [("sbin/8821cu.ko", module_path), ("sbin/rtl8821cu-watch.sh", script_path)]
if bluetooth_usb:
    payloads.append(("sbin/btusb.ko", root / "btusb.ko"))
for name, path in payloads:
    if name in files:
        raise ValueError(f"duplicate ramdisk path: {name}")
    payload = path.read_bytes()
    fields = [ino, 0o100755, 0, 0, 1, 0, len(payload), 0, 0, 0, 0, len(name.encode())+1, 0]
    entries.insert(-1, [fields, name, payload, b"070701"])
    ino += 1

def build_cpio(entries):
    out = bytearray()
    for fields, name, payload, magic in entries:
        fields = list(fields)
        fields[6] = len(payload)
        fields[11] = len(name.encode("utf-8", "surrogateescape")) + 1
        out.extend(magic)
        out.extend("".join(f"{x:08x}" for x in fields).encode("ascii"))
        out.extend(name.encode("utf-8", "surrogateescape") + b"\0")
        out.extend(b"\0" * ((-len(out)) & 3))
        out.extend(payload)
        out.extend(b"\0" * ((-len(out)) & 3))
    out.extend(b"\0" * ((-len(out)) % 512))
    return bytes(out)

new_ramdisk = gzip.compress(build_cpio(entries), compresslevel=9, mtime=0)
header = bytearray(boot[:page])
struct.pack_into("<I", header, 16, len(new_ramdisk))
digest = hashlib.sha1()
for component in (kernel, new_ramdisk, second, dtb):
    digest.update(component)
    digest.update(struct.pack("<I", len(component)))
header[576:608] = digest.digest() + b"\0" * 12
image = bytearray(header)
image.extend(kernel)
image.extend(b"\0" * ((-len(image)) % page))
image.extend(new_ramdisk)
image.extend(b"\0" * ((-len(image)) % page))
image.extend(second)
image.extend(b"\0" * ((-len(image)) % page))
image.extend(dtb)
image.extend(b"\0" * ((-len(image)) % page))
if len(image) > len(boot):
    raise ValueError(f"repacked image exceeds boot partition: {len(image)} > {len(boot)}")
image.extend(b"\0" * (len(boot) - len(image)))
out_path.write_bytes(image)
# Validate structure, component hashes, and the changed ramdisk.
assert image[:8] == b"ANDROID!"
assert image[ko:ko+ks] == kernel
new_rs = struct.unpack_from("<I", image, 16)[0]
new_ro = ((ko + ks + page - 1) // page) * page
new_ramdisk_check = gzip.decompress(image[new_ro:new_ro+new_rs])
check = {e[1]: e for e in parse_cpio(new_ramdisk_check)}
assert "sbin/8821cu.ko" in check and "sbin/rtl8821cu-watch.sh" in check
assert check["sepolicy"][2] == policy_path.read_bytes()
q = check["init.qcom.rc"][2].decode()
assert "setprop wifi.interface wlan0" in q
if bluetooth_usb:
    assert "sbin/btusb.ko" in check and "insmod /sbin/btusb.ko" in q
    assert "setprop qcom.bluetooth.soc rome" in q
    assert "start rtlbt\n    start rtlwatch" in q
    assert "/sbin/rtl8821cu-watch.sh --bluetooth-only" in q
else:
    assert "on property:sys.boot_completed=1\n    start rtlwatch" in q
assert "on property:sys.rtl8821cu.load=1" in q
assert "insmod /sbin/8821cu.ko" in q
assert q.count("-iwlan0 -Dnl80211 -c/data/misc/wifi/wpa_supplicant.conf") == 2
script = check["sbin/rtl8821cu-watch.sh"][2].decode()
assert "/system/vendor/bin/rtl_usb_eject" in script
print("Original boot bytes:", len(boot), "ramdisk:", rs, "dtb:", dt_size)
print("New boot bytes:", len(image), "ramdisk:", new_rs, "uncompressed ramdisk:", len(build_cpio(entries)))
print("sha256 original kernel:", hashlib.sha256(kernel).hexdigest())
print("sha256 preserved dtb:", hashlib.sha256(dtb).hexdigest())
print("output:", out_path)
