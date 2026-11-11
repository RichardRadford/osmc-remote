#!/bin/sh
# Prints KEY=VALUE lines describing the device. Run as the normal user.
model=$(tr -d '\000' < /proc/device-tree/model 2>/dev/null)
[ -z "$model" ] && model=$(awk -F': ' '/^Hardware/{print $2; exit}' /proc/cpuinfo 2>/dev/null)
echo "MODEL=$model"
echo "HOST=$(hostname)"
echo "OS=$(. /etc/os-release 2>/dev/null; echo "$PRETTY_NAME")"
echo "KODI=$(dpkg-query -W -f='${db:Status-Abbrev}${Package} ${Version}\n' '*-mediacenter-osmc' 2>/dev/null | awk '/^ii/{print $3; exit}')"
echo "KERNEL=$(uname -r)"
echo "UPTIME=$(cut -d' ' -f1 /proc/uptime)"
echo "LOAD=$(cut -d' ' -f1-3 /proc/loadavg)"
echo "CPUS=$(nproc 2>/dev/null || echo 1)"
echo "TEMP=$(cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null)"
echo "MEM=$(free -b | awk '/^Mem:/{print $2" "$3" "$7}')"
echo "DISK=$(df -B1 / | awk 'NR==2{print $2" "$3}')"
echo "IP=$(hostname -I 2>/dev/null)"
echo "KODI_STATE=$(systemctl is-active mediacenter 2>/dev/null)"
echo "CPUFREQ=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq 2>/dev/null)"
if command -v vcgencmd >/dev/null 2>&1; then
  echo "THROTTLED=$(vcgencmd get_throttled 2>/dev/null | cut -d= -f2)"
fi
