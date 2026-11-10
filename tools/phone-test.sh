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
# the centre of the element with this test tag, as "x y" (empty when it is not on screen)
centre() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml | python3 -c "
import sys,re
m=re.search(r'resource-id=\"$1\"[^>]*?bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"',sys.stdin.read())
print('%d %d'%((int(m[1])+int(m[3]))//2,(int(m[2])+int(m[4]))//2) if m else '')"
}

adb install -r -g "$APK" || { note "install failed"; exit 1; }
adb logcat -c
size=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); W=${size%x*}; H=${size#*x}
note "screen ${W}x${H}"

# 1. first launch of a new build: the start guide opens (build 41 crashed right here)
adb shell am start -W -n $PKG/.MainActivity
sleep 10; shot 01-first-launch
alive && note "first launch: running" || { note "first launch: NOT RUNNING"; fail=1; }

# 2. close the guide and relaunch from cold
adb shell input keyevent KEYCODE_BACK; sleep 2; shot 02-guide-closed
infront "guide closed"
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n $PKG/.MainActivity
sleep 8; shot 03-relaunch
alive && note "relaunch: running" || { note "relaunch: NOT RUNNING"; fail=1; }
infront "relaunch"

# 3. every tab, found by its tag (Back is never pressed here: with no sheet open it leaves the app,
#    and build 44's run tapped the home screen for this whole section because of that)
for t in Pads Console Steps Roll Song Sounds; do
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
