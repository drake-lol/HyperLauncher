#!/bin/bash
# usage: ICONS="x,y x,y ..." stress.sh   taps icons in turn with a finger-like press, going home different ways
ok=0; n=0; i=0
ways=("sh /data/local/tmp/fl2.sh" "input keyevent KEYCODE_HOME" "sh /data/local/tmp/fl2.sh" "input keyevent KEYCODE_BACK; sleep 0.4; input keyevent KEYCODE_BACK; sleep 0.4; input keyevent KEYCODE_HOME" "sh /data/local/tmp/fl2.sh")
waits=(0.9 1.5 0.7 2.5 1.1 0.8 3.0 1.3)
for round in 1 2 3; do
  for xy in $ICONS; do
    x=${xy%,*}; y=${xy#*,}
    adb shell "logcat -c; input swipe $x $y $((x+3)) $((y+2)) $((60 + (i*37)%90)); sleep 2.6"
    n=$((n+1)); L=$(adb shell "logcat -d")
    if echo "$L" | grep -q "setWindowAnimType  type=OPEN_FROM_HOME"; then ok=$((ok+1)); else echo "  from centre at $xy (#$i): $(echo "$L" | grep -E 'HyperLauncher.*(own animation|icon launch)' | sed -E 's/.*(own animation|icon launch)/\1/' | head -2 | tr '\n' ';') top=$(adb shell dumpsys activity activities | grep -m1 topResumedActivity | sed -E 's/.*u0 ([^ /]+).*/\1/')"; echo "$L" > /tmp/w/stress_miss_$i.log; fi
    adb shell "${ways[$((i%5))]}; sleep ${waits[$((i%8))]}"
    i=$((i+1))
  done
done
echo "animated from the icon: $ok of $n"
