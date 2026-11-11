#!/bin/sh
# F <unit> <unit-file-state>   and   U <unit> <active> <sub>
systemctl list-unit-files --type=service --no-legend --no-pager 2>/dev/null \
  | awk '{print "F "$1" "$2}'
systemctl list-units --type=service --all --no-legend --no-pager --plain 2>/dev/null \
  | awk '{ if ($1 == "●" || $1 == "*") { $1 = ""; $0 = $0 } print "U "$1" "$3" "$4 }'
# C <unit>   for services created by this app
grep -l 'Created by OSMC Remote' /etc/systemd/system/*.service 2>/dev/null \
  | while read -r f; do echo "C $(basename "$f")"; done
