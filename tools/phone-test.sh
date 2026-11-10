#!/bin/bash
# The phone test: the release APK (the exact file Tyler installs) on a real Android emulator.
# Fresh install and launch (the start guide path), relaunch, a walk through every tab, then long
# random touch runs. Any crash or freeze fails the build before it is published.
APK="$1"
PKG=com.tideclock.messmachine
OUT=phone-out
mkdir -p $OUT
fail=0
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }
alive() { adb shell pidof $PKG >/dev/null 2>&1; }
note() { echo "PHONE: $*"; echo "$*" >> $OUT/steps.txt; }
# is Mess Machine the app in front? (a running process alone is not enough: it can sit behind the home screen)
infront() {
  if adb shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp" | grep -q $PKG; then note "$1: in front"
  else note "$1: NOT IN FRONT"; fail=1; adb shell am start -n $PKG/.MainActivity; sleep 4; fi
}
# Android's own "isn't responding" boxes (build 45's run: the emulator's launcher froze and its box sat
# over the app, so the screen map held only that box). Turned off up front; any that still show are closed.
clear_dialogs() {
  for i in 1 2 3; do
    f=$(adb shell dumpsys window | grep mCurrentFocus)
    case "$f" in
      *"Not Responding"*|*"Application Error"*|*"isn't responding"*)
        note "system box over the app, closed: $(echo $f | tr -d '\r')"
        adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1
        adb shell input keyevent KEYCODE_BACK; sleep 2 ;;
      *) return ;;
    esac
  done
}
# the centre of the element with this test tag, as "x y" (empty when it is not on screen; the screen map
# is saved as ui-<tag>.xml so a miss can be read afterwards)
centre() {
  clear_dialogs
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml > $OUT/ui-now.xml
  grep -q "resource-id=\"$1\"" $OUT/ui-now.xml || cp $OUT/ui-now.xml "$OUT/ui-$1.xml"
  cat $OUT/ui-now.xml | python3 -c "
import sys,re
m=re.search(r'resource-id=\"$1\"[^>]*?bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"',sys.stdin.read())
print('%d %d'%((int(m[1])+int(m[3]))//2,(int(m[2])+int(m[4]))//2) if m else '')"
}

adb install -r -g "$APK" || { note "install failed"; exit 1; }
# let the freshly booted emulator settle, and keep Android's error boxes off the screen
adb shell settings put global hide_error_dialogs 1
adb shell settings put secure anr_show_background 0
sleep 20
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1
adb logcat -c
size=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); W=${size%x*}; H=${size#*x}
note "screen ${W}x${H}"

# 1. first launch of a new build: the start guide opens (build 41 crashed right here)
adb shell am start -W -n $PKG/.MainActivity
sleep 10; shot 01-first-launch
alive && note "first launch: running" || { note "first launch: NOT RUNNING"; fail=1; }

# 2. close the guide and relaunch from cold
clear_dialogs
xy=$(centre done)
if [ -n "$xy" ]; then adb shell input tap $xy; else adb shell input keyevent KEYCODE_BACK; fi
sleep 2; shot 02-guide-closed
infront "guide closed"
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n $PKG/.MainActivity
sleep 8; shot 03-relaunch
alive && note "relaunch: running" || { note "relaunch: NOT RUNNING"; fail=1; }
infront "relaunch"

# 3. every tab, found by its tag (Back is never pressed here: with no sheet open it leaves the app,
#    and build 44's run tapped the home screen for this whole section because of that)
# a tap on a page can open a sheet over the tabs (build 46's run: the Desk page opened Sounds); Done closes it
close_sheets() {
  for i in 1 2 3; do
    d=$(centre done); [ -z "$d" ] && return
    adb shell input tap $d; sleep 1
  done
}
for t in Pads Console Steps Roll Song Sounds; do
  close_sheets
  xy=$(centre "tab-$t")
  [ -z "$xy" ] && { note "tab $t: NOT FOUND"; fail=1; shot "04-tab-$t"; continue; }
  adb shell input tap $xy; sleep 2
  adb shell input tap $(( W / 2 )) $(( H / 3 )); sleep 1
  adb shell input swipe $(( W / 2 )) $(( H / 2 )) $(( W / 2 )) $(( H / 4 )) 300; sleep 1
  shot "04-tab-$t"
  alive || { note "tab $t: NOT RUNNING"; fail=1; adb shell am start -n $PKG/.MainActivity; sleep 5; }
  infront "tab $t"
done

# a long press on a pad opens its menu on lift; then a circle on a pad opens the sphere
close_sheets
adb shell input tap $(centre tab-Pads); sleep 2
xy=$(centre pad-6)
if [ -z "$xy" ]; then note "pad 6: NOT FOUND"; fail=1; else
  set -- $xy
  adb shell input swipe $1 $2 $1 $2 900; sleep 1; shot 05-long-press
  [ -n "$(centre padmenu)" ] && note "pad menu: opened" || { note "pad menu: DID NOT OPEN"; fail=1; }
  adb shell input keyevent KEYCODE_BACK; sleep 1
  infront "after pad menu"
  cx=$1; cy=$2; r=$(( W / 30 ))
  adb shell input motionevent DOWN $(( cx + r )) $cy
  for a in 20 40 60 80 100 120 140 160 180 200 220 240 260 280 300 320 340 360 380 400; do
    px=$(python3 -c "import math;print(int($cx+$r*math.cos(math.radians($a))))"); py=$(python3 -c "import math;print(int($cy+$r*math.sin(math.radians($a))))")
    adb shell input motionevent MOVE $px $py
  done
  shot 06-circle
  adb shell input motionevent UP $cx $cy; sleep 1
fi
alive && note "gestures: running" || { note "gestures: NOT RUNNING"; fail=1; adb shell am start -n $PKG/.MainActivity; sleep 5; }
infront "gestures"

# 3b. sound and smoothness: play the demo mess for 30 seconds, then read the audio log and the frame stats
#     (reported, not failed on: the emulator has no real audio device or graphics chip)
close_sheets
adb shell input tap $(centre tab-Pads); sleep 1
adb shell dumpsys gfxinfo $PKG reset >/dev/null 2>&1
pl=$(centre play)
if [ -n "$pl" ]; then
  adb shell input tap $pl
  sleep 30
  adb shell input tap $pl; sleep 1
  a=$(adb logcat -d -s MessAudio:I | tail -1 | sed 's/.*MessAudio: //')
  note "sound while playing: ${a:-no audio log}"
  j=$(adb shell dumpsys gfxinfo $PKG | grep -E "Total frames rendered|Janky frames" | tr -s ' ' | tr '\n' ';' | tr -d '\r')
  note "frames while playing: $j"
else note "play button: NOT FOUND"; fail=1; fi

# 4. two long random runs (different seeds), with a cold restart between them
for seed in 11 2026; do
  adb shell monkey -p $PKG -s $seed --throttle 35 --pct-syskeys 0 --pct-appswitch 0 --pct-anyevent 0 --pct-nav 0 --pct-majornav 1 \
    --pct-touch 55 --pct-motion 35 --pct-trackball 0 -v 5000 > $OUT/monkey-$seed.txt 2>&1
  tail -3 $OUT/monkey-$seed.txt
  shot "07-after-monkey-$seed"
  grep -E "// CRASH|// NOT RESPONDING|Monkey aborted" $OUT/monkey-$seed.txt && { note "monkey $seed: CRASH or freeze"; fail=1; } || note "monkey $seed: clean"
  adb shell am force-stop $PKG; sleep 1; adb shell am start -W -n $PKG/.MainActivity; sleep 5
done
alive && note "after everything: running" || { note "after everything: NOT RUNNING"; fail=1; }
shot 08-end

adb logcat -d > $OUT/logcat.txt
grep -n "Process: $PKG" -B3 -A45 $OUT/logcat.txt > $OUT/crashes.txt
grep -n "ANR in $PKG" -A10 $OUT/logcat.txt >> $OUT/crashes.txt
if [ -s $OUT/crashes.txt ]; then note "crashes found"; cat $OUT/crashes.txt | head -120; fail=1; fi
cat $OUT/steps.txt
exit $fail
