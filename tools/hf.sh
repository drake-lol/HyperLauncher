#!/bin/bash
# usage: hf.sh name "<cmds>" seconds  -> /tmp/w/name.mp4 (high bitrate)
N=$1; CMDS=$2; T=${3:-4}
adb shell "rm -f /sdcard/$N.mp4; (screenrecord --display-id 4630946949513469331 --time-limit $T --bit-rate 20000000 /sdcard/$N.mp4 >/dev/null 2>&1 &); sleep 1; $CMDS; sleep $T"
sleep 0.5; adb pull /sdcard/$N.mp4 /tmp/w/$N.mp4 >/dev/null
