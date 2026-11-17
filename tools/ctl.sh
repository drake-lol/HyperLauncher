#!/bin/bash
# usage: ctl.sh <prefix> <x> <y> <ox> <oy> [delays...]  -> tap another icon <delay> s after flicking home
P=$1; X=$2; Y=$3; OX=$4; OY=$5; shift 5; D=$(dirname "$0")
for d in "$@"; do T=${P}_$d
  adb shell "input keyevent KEYCODE_HOME; sleep 2; input tap $X $Y; sleep 3.5"; adb logcat -c
  $D/hf.sh $T "sh /data/local/tmp/fl2.sh; sleep $d; input tap $OX $OY" 4 >/dev/null 2>&1
  python3 -I $D/glitch.py $T; adb logcat -d -v time > /tmp/w/$T.log
  echo "   open-anim=$(grep -c OPEN_FROM_HOME /tmp/w/$T.log) $(grep 'HyperLauncher.*: ' /tmp/w/$T.log | grep -i 'icon launch\|own animation\|plainly' | sed 's/.*HyperLauncher[^:]*: //' | cut -c1-120 | tr '\n' ';')"
done
