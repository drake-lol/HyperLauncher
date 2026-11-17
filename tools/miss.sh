#!/bin/bash
# usage: miss.sh "<name>" "<setup cmds>"  -> runs setup, flicks home, reports whether the close aimed at an icon
adb shell "input keyevent KEYCODE_HOME; sleep 2; $2; logcat -c; sh /data/local/tmp/fl2.sh; sleep 2.5"
m=$(adb logcat -d -v time | grep -E "HyperLauncher.*closing" | sed 's/.*closing/closing/' | cut -c1-200 | head -1)
echo "$1: ${m:-nothing logged}"
