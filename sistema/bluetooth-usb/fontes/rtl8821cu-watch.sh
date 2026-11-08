#!/system/bin/sh
PATH=/sbin:/system/sbin:/system/bin:/system/xbin
bluetooth_only=0
[ "$1" != "--bluetooth-only" ] || bluetooth_only=1

last_status=
next_eject=0
tick=0

while :; do
    status=absent
    for device in /sys/bus/usb/devices/*; do
        [ -r "$device/idVendor" ] || continue
        IFS= read -r vendor < "$device/idVendor" 2>/dev/null || continue
        [ "$vendor" = 0bda ] || continue
        IFS= read -r product < "$device/idProduct" 2>/dev/null || continue

        case "$product" in
            1a2b)
                status=disk
                [ "$tick" -ge "$next_eject" ] || break
                for scsi in "$device":*/host*/target*/*/scsi_generic/sg*; do
                    [ -d "$scsi" ] || continue
                    node="/dev/${scsi##*/}"
                    [ -c "$node" ] || continue
                    /system/vendor/bin/rtl_usb_eject "$node"
                    next_eject=$((tick + 5))
                    break
                done
                break
                ;;
            c820)
                next_eject=0
                if [ "$bluetooth_only" -eq 1 ]; then
                    status=usb-ready
                elif [ -e /sys/class/net/wlan1 ]; then
                    status=wlan1
                else
                    status=loading
                    if [ "$last_status" != loading ]; then
                        setprop sys.rtl8821cu.load 0
                        setprop sys.rtl8821cu.load 1
                    fi
                fi
                break
                ;;
        esac
    done
    [ "$status" != absent ] || next_eject=0
    if [ "$status" != "$last_status" ]; then
        setprop sys.rtl8821cu.status "$status"
        last_status=$status
    fi
    tick=$((tick + 1))
    sleep 2
done
