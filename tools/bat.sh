#!/bin/bash
# usage: bat.sh <home pkg> <icon x> <icon y> <second icon x> <second icon y>   21 steps; prints only the ones that end in the wrong place
H=$1; X=$2; Y=$3; OX=$4; OY=$5; bad=0
top() { adb shell "dumpsys activity activities" 2>/dev/null | grep -m1 topResumedActivity | sed -E 's/.*u0 ([^ /]+).*/\1/'; }
A=""; B=""
say() { t=$(top); case "$2" in home) want=$H;; a) want=$A;; b) want=$B;; rec) want=com.miui.home;; esac; if [ "$t" != "$want" ]; then bad=$((bad+1)); echo "  FAIL $1 -> $t (wanted $want)"; fi; }
fl="sh /data/local/tmp/fl2.sh"
adb shell "input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; input keyevent KEYCODE_HOME; sleep 3; am force-stop com.miui.home; sleep 8; input tap $X $Y; sleep 4"; A=$(top); echo "app A=$A"
adb shell "$fl; sleep 3"; say "2 flick home" home
adb shell "input tap $X $Y; sleep 3"; say "3 tap icon (warm)" a
adb shell "$fl; sleep 0.9; input tap $OX $OY; sleep 3"; B=$(top); echo "app B=$B"; [ "$B" = "$H" ] && { bad=$((bad+1)); echo "  FAIL 4 flick home, tap other icon fast"; }
adb shell "input keyevent KEYCODE_HOME; sleep 2; input tap $X $Y; sleep 3; sh /data/local/tmp/hold2.sh; sleep 2"; say "5 hold for recents" rec
adb shell "input tap 1000 1300; sleep 2.5"; say "6 tap a recents card" a
adb shell "sh /data/local/tmp/hold2.sh; sleep 2; $fl; sleep 3"; say "7 recents then flick home" home
adb shell "input tap $X $Y; sleep 3; sh /data/local/tmp/hold2.sh; sleep 2; input keyevent KEYCODE_BACK; sleep 2.5"; say "8 recents then back" a
adb shell "sh /data/local/tmp/hold2.sh; sleep 2; input keyevent KEYCODE_HOME; sleep 2.5"; say "9 recents then HOME key" home
adb shell "input tap $X $Y; sleep 3; input keyevent KEYCODE_HOME; sleep 2.5"; say "10 HOME key from app" home
adb shell "input tap $X $Y; sleep 3; input keyevent KEYCODE_BACK; sleep 0.6; input keyevent KEYCODE_BACK; sleep 0.6; input keyevent KEYCODE_BACK; sleep 2.5"; say "11 back out of app" home
adb shell "$fl; sleep 3"; say "12 flick on home" home
adb shell "sh /data/local/tmp/slowdrag.sh; sleep 2"; say "13 hold on home (recents)" rec
adb shell "input keyevent KEYCODE_HOME; sleep 2.5"; say "14 HOME key from recents" home
adb shell "input tap $OX $OY; sleep 3; input keyevent KEYCODE_HOME; sleep 2; input tap $X $Y; sleep 3; sh /data/local/tmp/qs.sh; sleep 2.5"; say "15 quick switch" b
adb shell "$fl; sleep 3"; say "16 flick home after switch" home
adb shell "input tap $X $Y; sleep 3; input keyevent KEYCODE_SLEEP; sleep 2; input keyevent KEYCODE_WAKEUP; sleep 1; wm dismiss-keyguard; sleep 2; $fl; sleep 3"; say "17 screen off/on then flick home" home
adb shell "input tap $X $Y; sleep 3; am force-stop com.miui.home; sleep 6; $fl; sleep 3"; say "18 MiuiHome killed, flick home" home
adb shell "input tap $X $Y; sleep 3"; say "19 tap icon after MiuiHome restart" a
adb shell "am force-stop $H; sleep 2; $fl; sleep 4"; say "20 launcher killed, flick home" home
adb shell "input tap $X $Y; sleep 3"; say "21 tap icon after launcher restart" a
adb shell "input keyevent KEYCODE_HOME; sleep 2"
echo "battery: $((20-bad)) of 20 steps right"; adb logcat -d | grep -E "FATAL EXCEPTION|HyperLauncher.*(failed|refused)" | grep -v adbd | head -4 | cut -c1-200
