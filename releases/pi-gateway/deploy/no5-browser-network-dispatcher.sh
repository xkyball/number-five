#!/bin/sh
set -eu
case "${2:-}" in
    up|down|dhcp4-change|dhcp6-change|reapply|connectivity-change|dns-change) ;;
    *) exit 0 ;;
esac
test "$(cat /etc/hostname)" = lysmarine || exit 0
case "$(cat /proc/device-tree/model)" in
    *'Raspberry Pi 5'*) ;;
    *) exit 0 ;;
esac
# Read current state in the service, rather than trusting stale event addresses.
/usr/bin/systemctl start --no-block no5-browser-network.service
