"""Prepare a btusb probe for the RTL8821CU from the Moto G3 kernel source."""

import re
import sys
from pathlib import Path

source, destination = map(Path, sys.argv[1:])
code = source.read_text()
table = "static struct usb_device_id btusb_table[] = {"
if code.count(table) != 1:
    raise SystemExit("unexpected btusb match table")
code = code.replace(
    table,
    table + "\n\t{ USB_DEVICE_AND_INTERFACE_INFO(0x0bda, 0xc820, 0xe0, 0x01, 0x01) },",
    1,
)
code, count = re.subn(
    r"\tif \(id->driver_info & BTUSB_ATH3012\) \{.*?\n\t\}\n",
    "\tif (id->driver_info & BTUSB_ATH3012)\n\t\treturn -ENODEV;\n",
    code,
    count=1,
    flags=re.S,
)
if count != 1 or code.count("int i, version, err;") != 1:
    raise SystemExit("unexpected btusb probe implementation")
code = code.replace("int i, version, err;", "int i, err;", 1)
destination.write_text(code)
