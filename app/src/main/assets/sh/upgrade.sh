#!/bin/sh
# Runs detached as root (setsid + nohup) so it survives the app disconnecting.
# Equivalent to OSMC's documented: sudo apt update && sudo apt full-upgrade
export DEBIAN_FRONTEND=noninteractive
echo "== Refreshing package lists =="
apt-get update
echo
echo "== Installing updates =="
apt-get -y \
  -o Dpkg::Options::=--force-confdef \
  -o Dpkg::Options::=--force-confold \
  dist-upgrade
rc=$?
apt-get -y autoclean >/dev/null 2>&1
if [ -f /var/run/reboot-required ] || [ -f /run/reboot-required ]; then
  echo "__OSMCREMOTE_REBOOT__"
fi
echo "__OSMCREMOTE_DONE__ $rc"
