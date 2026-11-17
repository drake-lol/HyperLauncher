#!/bin/bash
# usage: scan.sh <tag> <home pkg> <icon x> <icon y> <other x> <other y>
T=$1; H=$2; X=$3; Y=$4; OX=$5; OY=$6; D=$(dirname "$0")
r() { $D/hf.sh ${T}_$1 "$2" $3 >/dev/null 2>&1; python3 -I $D/glitch.py ${T}_$1; }
adb shell "input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; input keyevent KEYCODE_HOME; sleep 3; input tap $X $Y; sleep 3.5; sh /data/local/tmp/fl2.sh; sleep 3"
r open "input swipe $X $Y $((X+2)) $((Y+1)) 80" 3
r home "sh /data/local/tmp/fl2.sh" 4
adb shell "input tap $X $Y; sleep 3.5"
r back "input keyevent KEYCODE_BACK; sleep 0.5; input keyevent KEYCODE_BACK" 4
adb shell "input keyevent KEYCODE_HOME; sleep 2; input tap $X $Y; sleep 3.5"
r homekey "input keyevent KEYCODE_HOME" 3
r flickhome "sh /data/local/tmp/fl2.sh" 3
r draghold "sh /data/local/tmp/slowdrag.sh" 6
r recents_home "sh /data/local/tmp/fl2.sh" 4
adb shell "input tap $X $Y; sleep 3.5"
r apphold "sh /data/local/tmp/hold2.sh" 5
r card "input tap 610 1300" 4
r quick "sh /data/local/tmp/qs.sh" 3
adb shell "input keyevent KEYCODE_HOME; sleep 2; input tap $X $Y; sleep 3.5"
r close_tap "sh /data/local/tmp/fl2.sh; sleep 0.5; input tap $OX $OY" 4
adb shell "input keyevent KEYCODE_HOME; sleep 2; input tap $X $Y; sleep 3.5"
r close_drawer "sh /data/local/tmp/fl2.sh; sleep 0.5; input swipe 610 1700 610 700 160" 4
adb shell "input keyevent KEYCODE_BACK; sleep 1; input keyevent KEYCODE_HOME; sleep 2"
r two "input tap $X $Y; sleep 0.8; sh /data/local/tmp/fl2.sh; sleep 0.8; input tap $OX $OY; sleep 0.8; sh /data/local/tmp/fl2.sh" 7
adb shell "input keyevent KEYCODE_HOME; sleep 2"
