#!/bin/bash
# usage: rec.sh <tag> "<setup cmds>" "<recorded cmds>" [seconds] [sheet args...]  -> /tmp/w/<tag>_sheet.png
T=$1; D=$(dirname "$0")
timeout 40 adb shell "input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; input keyevent KEYCODE_HOME; sleep 2.5; $2" || exit 1
adb logcat -c; $D/hf.sh $T "$3" ${4:-3} >/dev/null 2>&1; adb logcat -d -v time > /tmp/w/$T.log
shift 4; python3 -I $D/sheet.py $T "$@"
