#!/usr/bin/env bash
# Drives the promo-video takes on a phone over adb. Everything shown is made up and posted by the
# demo apps in poster/; the debug build's demo mode keeps real notifications out of the panel.
#
#   ./scenes.sh setup     install-time setup: demo mode on, stable Chronotif's listener paused
#   ./scenes.sh earlier   post the Earlier notifications, then check them (run ~40 min before takes)
#   ./scenes.sh player    start the pretend music player
#   ./scenes.sh fresh     Maya's messages and a reminder arrive (New), screen stays on
#   ./scenes.sh lock [NAME SECS]   screen off, they arrive, screen wakes: the lock-screen view
#                                  (records NAME from the wake when given)
#   ./scenes.sh arrive    a new message from the hiking group while the panel is open
#   ./scenes.sh panel     open the debug panel from its edge handle
#   ./scenes.sh rec NAME SECONDS   record the screen into raw/takes/NAME.mp4 (runs in background:
#                                  start it, then drive the take)
#   ./scenes.sh tap X Y   tap, in screen pixels
#   ./scenes.sh restore   demo mode off, stable listener back, demo notifications cleared
set -euo pipefail

DEBUG=app.chronotif.debug
post() { local app=$1; shift; adb shell am broadcast -f 32 -n "app.chronotif.demo.$app/app.chronotif.demo.Post" "$@" >/dev/null; }
q() { printf "'%s'" "${1//\'/\'\\\'\'}"; }   # quote for the device shell
msg() { post chat --es do msg --es conv "$1" --es from "$(q "$2")" --es text "$(q "$3")" "${@:4}"; }
note() { post "$1" --es do note --ei id "$2" --es title "$(q "$3")" --es text "$(q "$4")" "${@:5}"; }

# The demo apps use a conversation id's Java hashCode as its notification id.
java_hash() { python3 -c 'import sys
h = 0
for c in sys.argv[1]: h = (31 * h + ord(c)) & 0xFFFFFFFF
print(h - (1 << 32) if h >= 1 << 31 else h)' "$1"; }

handle() {
  adb shell dumpsys window windows | awk -v pkg="package=$DEBUG " '
    /Window #.*Chronotif handle/ { h = 1; next }
    h && index($0, pkg) { mine = 1 }
    h && mine && /frame=\[/ { match($0, /frame=\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]/); print substr($0, RSTART + 6, RLENGTH - 6); exit }
    /Window #/ { h = 0; mine = 0 }'
}

open_panel() {
  local f; f=$(handle)
  [[ -n $f ]] || { echo "debug edge handle not found" >&2; exit 1; }
  local x1 y1 x2 y2; IFS="[],"; read -r _ x1 y1 _ x2 y2 <<<"$f"; unset IFS
  local y=$(((y1 + y2) / 2))
  if ((x1 > 640)); then adb shell input swipe $((x2 - 10)) $y 500 $y 250; else adb shell input swipe $((x1 + 10)) $y 780 $y 250; fi
}

case ${1:-} in
  setup)
    adb shell am broadcast -n $DEBUG/app.chronotif.DemoModeReceiver --ez on true | tail -1
    adb shell cmd notification disallow_listener app.chronotif/app.chronotif.ListenerService
    for a in chat parcel calendar bank music steps; do
      adb shell pm grant app.chronotif.demo.$a android.permission.POST_NOTIFICATIONS
      post $a --es do reset
    done
    ;;
  earlier)
    # Oldest first. The group chat is backdated through its message times; other cards can't be,
    # so they're posted for real and simply age until the takes.
    msg crew "Leo Park" "Trailhead at 8, bring layers" --es title "$(q "Hiking crew")" --ez group true --ei ago 52
    msg crew "Emma Wilson" "I’ll drive, room for three" --ei ago 49
    note steps 1 "4,812 steps" "Goal 8,000 · 60% there" --ez ongoing true --ei progress 60
    note calendar 1 "Standup moved to 10:30" "Today · Room 2"
    sleep 60
    note parcel 1 "Order confirmed" "Trail runners, size 42 · arriving Thursday"
    sleep 150
    note parcel 2 "Packed and on its way" "Tracking number PX-2048-1187"
    sleep 150
    note parcel 3 "Out for delivery" "Arriving today between 2 and 4 PM"
    sleep 120
    note bank 1 "You paid Corner Café" "€4.20 · Main account"
    sleep 20
    open_panel; sleep 3; adb shell input keyevent KEYCODE_BACK
    echo "Earlier is set and checked."
    ;;
  player)
    adb shell am start-foreground-service -n app.chronotif.demo.music/app.chronotif.demo.Player \
      --es title "$(q "Slow Light")" --es artist "$(q "The Quiet Hours")" --ei at 40 >/dev/null
    ;;
  fresh|lock)
    # Each take starts Maya's chat afresh under a new id; the last take's chat and reminder go.
    state="$(dirname "$0")/raw/maya.conv"
    mkdir -p "$(dirname "$state")"
    if [[ -f $state ]]; then post chat --es do cancel --ei id "$(java_hash "$(cat "$state")")"; fi
    post calendar --es do cancel --ei id 2
    conv="maya$(date +%s)"; echo "$conv" >"$state"
    "$0" player
    [[ $1 == lock ]] && { adb shell input keyevent KEYCODE_SLEEP; sleep 2; }
    msg "$conv" "Maya Chen" "Found a table by the window"
    sleep 1
    msg "$conv" "Maya Chen" "Ordered you a flat white"
    sleep 1
    msg "$conv" "Maya Chen" "Look at this view" --es photo lake
    note calendar 2 "Design review in 15 min" "11:00 · Room 4" --es channel reminders
    if [[ $1 == lock ]]; then
      sleep 1
      adb shell input keyevent KEYCODE_WAKEUP
      # screenrecord can't start while the display is off, so a take starts here.
      if [[ -n ${2:-} ]]; then "$0" rec "$2" "${3:-14}" & fi
    fi
    ;;
  arrive)
    msg crew "Sam Ortiz" "Forecast says clear skies, we’re on" --es photo morning
    ;;
  panel) open_panel ;;
  tap) adb shell input tap "$2" "$3" ;;
  rec)
    mkdir -p "$(dirname "$0")/raw/takes"
    # Right after a wake the display may not be ready yet; retry until screenrecord starts.
    for _ in $(seq 20); do
      out=$(adb shell screenrecord --time-limit "$3" --bit-rate 24000000 "/sdcard/Movies/take.mp4" 2>&1) || true
      [[ $out == *INVALID_LAYER_STACK* ]] || break
      sleep 0.15
    done
    adb pull /sdcard/Movies/take.mp4 "$(dirname "$0")/raw/takes/$2.mp4" >/dev/null
    adb shell rm /sdcard/Movies/take.mp4
    echo "raw/takes/$2.mp4"
    ;;
  restore)
    adb shell am broadcast -n $DEBUG/app.chronotif.DemoModeReceiver --ez on false | tail -1
    adb shell am stopservice -n app.chronotif.demo.music/app.chronotif.demo.Player >/dev/null || true
    for a in chat parcel calendar bank music steps; do post $a --es do reset; done
    adb shell cmd notification allow_listener app.chronotif/app.chronotif.ListenerService
    ;;
  *) sed -n '2,15p' "$0"; exit 1 ;;
esac
