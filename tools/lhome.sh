#!/bin/bash
# usage: lhome.sh <tag> <rot 1|3> <landscape icon x> <y> "<close cmd>" [sec]  -> launcher in landscape, open the icon, record the close.
# The system drops a rotation lock whenever an upright-only window shows, which the sensor would not: it is put back all through.
T=$1; R=$2; D=$(dirname "$0")
adb shell "wm user-rotation lock 0; input keyevent KEYCODE_HOME; sleep 2"

adb shell "nohup sh -c 'i=0; while [ \$i -lt 130 ]; do cmd window user-rotation lock $R; i=\$((i+1)); sleep 0.1; done' >/dev/null 2>&1 &"
adb shell "sleep 4; input tap $3 $4; sleep 3.5"
$D/lrec.sh $T "$5" ${6:-4}
adb shell "sleep 1; screencap -d 4630946949513469331 -p /sdcard/e.png; pkill -f 'user-rotation lock' ; sleep 0.5; wm user-rotation lock 0"; adb pull /sdcard/e.png /tmp/w/${T}_end.png >/dev/null
