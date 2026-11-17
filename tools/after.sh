#!/bin/bash
# usage: after.sh <tag> <icon x> <icon y> "<gesture>" [delay]  -> screenshot 1.5s after a gesture made just after closing an app
D=${5:-0.3}
adb shell "input keyevent KEYCODE_HOME; sleep 2; input tap $2 $3; sleep 3.5; sh /data/local/tmp/fl2.sh; sleep $D; $4; sleep 1.5; screencap -d 4630946949513469331 -p /sdcard/f.png"; adb pull /sdcard/f.png /tmp/w/af_$1.png >/dev/null; adb shell "input keyevent KEYCODE_BACK; sleep 0.5; input keyevent KEYCODE_HOME"
