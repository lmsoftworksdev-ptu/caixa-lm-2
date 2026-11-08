[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Serial,
    [switch] $Aplicar,
    [switch] $Reiniciar
)

$ErrorActionPreference = 'Stop'
$manifest = Get-Content -Raw (Join-Path $PSScriptRoot 'manifest.json') | ConvertFrom-Json
$payload = Join-Path $PSScriptRoot 'arquivos'
$bootBlock = '/dev/block/bootdevice/by-name/boot'
$staging = '/data/local/tmp/caixa-rtl'

function Invoke-Adb {
    $result = & adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "Falha no ADB: $($args[0])" }
    $result
}

function Assert-Hash($actual, $expected, $label) {
    if ($actual -ne $expected) { throw "SHA-256 divergente: $label" }
}

foreach ($file in $manifest.arquivos) {
    $path = Join-Path $payload $file.nome
    Assert-Hash (Get-FileHash -LiteralPath $path).Hash $file.sha256 $file.nome
}

$rom = (Invoke-Adb shell getprop ro.cm.version).Trim()
$kernel = (Invoke-Adb shell uname -r).Trim()
$device = (Invoke-Adb shell getprop ro.product.device).Trim()
if ($rom -ne $manifest.rom -or $kernel -ne $manifest.kernel -or $device -ne $manifest.aparelho) {
    throw "ROM, kernel ou modelo diferente do pacote: $rom / $kernel / $device"
}
if (!$Aplicar) {
    Write-Output 'Arquivos e versão da ROM conferidos. Use -Aplicar para instalar.'
    return
}

Invoke-Adb root
Invoke-Adb wait-for-device
if ((Invoke-Adb shell id -u).Trim() -ne '0') { throw 'ADB root não está disponível.' }
$installed = ((Invoke-Adb shell "sha256sum $bootBlock") -split '\s+')[0]
if ($installed -notin $manifest.bootsAceitos) {
    $stockPrefix = ((Invoke-Adb shell "dd if=$bootBlock bs=2048 count=$($manifest.bootOriginalPaginas) 2>/dev/null | sha256sum") -split '\s+')[0]
    if ($stockPrefix -ne $manifest.bootOriginalSha256) {
        throw "Boot atual desconhecido ($installed). O pacote não foi aplicado."
    }
}
Invoke-Adb shell "mkdir -p $staging && chmod 700 $staging"
foreach ($file in $manifest.arquivos) {
    $remote = "$staging/$($file.nome)"
    Invoke-Adb push (Join-Path $payload $file.nome) $remote
    Assert-Hash (((Invoke-Adb shell "sha256sum $remote") -split '\s+')[0]) $file.sha256 $file.nome
}
Invoke-Adb shell "/system/xbin/gunzip -c $staging/caixa-boot.img.gz > $staging/boot.img"
Assert-Hash (((Invoke-Adb shell "sha256sum $staging/boot.img") -split '\s+')[0]) $manifest.bootSha256 'boot.img'
Invoke-Adb remount
$systemCommands = @'
set -e
d=/data/local/tmp/caixa-rtl
mkdir -p /system/vendor/bin /system/vendor/lib /system/vendor/overlay /system/etc/init
cp "$d/rtl_usb_eject" /system/vendor/bin/rtl_usb_eject
cp "$d/rtl_bt_h4" /system/vendor/bin/rtl_bt_h4
chmod 755 /system/vendor/bin/rtl_usb_eject /system/vendor/bin/rtl_bt_h4
chown 0:0 /system/vendor/bin/rtl_usb_eject /system/vendor/bin/rtl_bt_h4
chcon u:object_r:shell_exec:s0 /system/vendor/bin/rtl_usb_eject /system/vendor/bin/rtl_bt_h4
cp "$d/libbt-rtl-usb.so" /system/vendor/lib/libbt-rtl-usb.so
cp "$d/caixa-rtl-bt-power.rc" /system/etc/init/caixa-rtl-bt-power.rc
cp "$d/CaixaBluetooth.apk" /system/vendor/overlay/CaixaBluetooth.apk
for f in /system/vendor/lib/libbt-rtl-usb.so /system/etc/init/caixa-rtl-bt-power.rc /system/vendor/overlay/CaixaBluetooth.apk; do
    chown 0:0 "$f"
    chmod 644 "$f"
    restorecon "$f"
done
sed '/^persist\.service\.bt\.a2dp\.sink=/d; /^adb\.network\.port=/d' /system/build.prop > "$d/build.prop"
printf '\npersist.service.bt.a2dp.sink=true\nadb.network.port=5555\n' >> "$d/build.prop"
cat "$d/build.prop" > /system/build.prop
setprop persist.service.bt.a2dp.sink true
pm enable com.android.bluetooth/com.android.bluetooth.avrcp.AvrcpControllerService
pm enable com.android.bluetooth/com.android.bluetooth.a2dpsink.A2dpSinkService
pm enable com.android.bluetooth/com.android.bluetooth.a2dpsink.mbs.A2dpMediaBrowserService
pm disable com.android.bluetooth/com.android.bluetooth.a2dp.A2dpService
sync
'@
Invoke-Adb shell ($systemCommands -replace "`r", '')
Invoke-Adb shell "if [ ! -f $staging/boot-anterior.img ]; then dd if=$bootBlock of=$staging/boot-anterior.img bs=1048576 && sync; fi"
Invoke-Adb shell "dd if=$staging/boot.img of=$bootBlock bs=1048576 && sync"
Assert-Hash (((Invoke-Adb shell "sha256sum $bootBlock") -split '\s+')[0]) $manifest.bootSha256 'partição boot'
Write-Output "Instalado. Backup do boot anterior no aparelho: $staging/boot-anterior.img"
if ($Reiniciar) { Invoke-Adb reboot }
else { Write-Output 'Reinicie a caixa com o dongle conectado para ativar a configuração.' }
