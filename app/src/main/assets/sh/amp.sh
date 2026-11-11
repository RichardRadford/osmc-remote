#!/bin/sh
# Reads or writes Kodi's default volume amplification (dB) in guisettings.xml.
#   sh amp.sh get
#   sh amp.sh set 12.000000      (Kodi must be stopped first, or it overwrites the file on exit)
f="$HOME/.kodi/userdata/guisettings.xml"
case "$1" in
  get)
    sed -n 's:.*<volumeamplification>\([^<]*\)</volumeamplification>.*:\1:p' "$f" 2>/dev/null | head -n 1
    ;;
  set)
    v="$2"
    [ -f "$f" ] || { echo "Kodi's settings file wasn't found at $f"; exit 1; }
    cp "$f" "$f.osmcremote.bak"
    if grep -q '<volumeamplification>' "$f"; then
      sed -i "s:<volumeamplification>[^<]*</volumeamplification>:<volumeamplification>$v</volumeamplification>:" "$f"
    elif grep -q '<defaultvideosettings>' "$f"; then
      sed -i "s:<defaultvideosettings>:<defaultvideosettings>\n        <volumeamplification>$v</volumeamplification>:" "$f"
    else
      echo "Kodi hasn't saved default video settings yet. In Kodi, play any video, open the audio settings and choose 'Set as default for all media' once, then try again."
      exit 3
    fi
    grep -q "<volumeamplification>$v</volumeamplification>" "$f" || { echo "Couldn't update $f"; exit 1; }
    ;;
  *)
    echo "usage: amp.sh get|set VALUE"; exit 1
    ;;
esac
