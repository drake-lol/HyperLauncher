#!/bin/bash
# usage: ICONS="x,y ..." rnd.sh <n> [seed]  -> random opens and closes; reports closes that did not aim at an icon
N=$1; RANDOM=${2:-7}; miss=0
set -- $ICONS; n=$#
closes=("sh /data/local/tmp/fl2.sh" "input swipe 610 2650 610 1700 90" "input swipe 400 2650 380 1200 220" "input swipe 800 2650 900 1500 160" "input swipe 610 2650 610 1900 300" "sh /data/local/tmp/back.sh" "input swipe 610 2650 300 1800 180" "input swipe 610 2655 610 2100 60" "input swipe 200 2650 200 1500 140")
for i in $(seq 1 $N); do
  xy=${@:$((RANDOM%n+1)):1}; x=${xy%,*}; y=${xy#*,}
  w=$(awk -v r=$RANDOM 'BEGIN{print 0.5+r%25/10}'); c=${closes[$((RANDOM%${#closes[@]}))]}; d=$(awk -v r=$RANDOM 'BEGIN{print 0.25+r%20/10}')
  adb shell "input tap $x $y; sleep $w; logcat -c; $c; sleep 1.6"
  L=$(adb logcat -d -v time | grep -E "HyperLauncher.*closing|setWindowAnimType")
  top=$(adb shell dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity | sed -E 's/.*u0 ([^ /]+).*/\1/')
  if echo "$L" | grep -q "aiming at its icon"; then :; else miss=$((miss+1)); echo "  #$i icon=$xy wait=$w close='$c' top=$top :: $(echo "$L" | sed -E 's/.*(HyperLauncher[^:]*: |type=)//' | cut -c1-110 | tr '\n' ';' | cut -c1-330)"; fi
  case "$top" in *lawnchair*|*flowerfree*) ;; *) adb shell "input keyevent KEYCODE_HOME";; esac
  adb shell "sleep $d"
done
echo "closes not aimed at an icon: $miss of $N"
