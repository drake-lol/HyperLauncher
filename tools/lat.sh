#!/bin/bash
# usage: lat.sh <x> <y> [n]  -> ms from finger-up on an icon to the first frame that changes (recorded with touch dots off)
X=$1; Y=$2; N=${3:-5}; D=$(dirname "$0"); out=""
for k in $(seq 1 $N); do
  adb shell "input keyevent KEYCODE_HOME; sleep 3"; adb logcat -c
  $D/hf.sh lat "input swipe $X $Y $((X+1)) $((Y+1)) 60" 2 >/dev/null 2>&1
  adb logcat -d -v time > /tmp/w/lat.log
  # time between the launcher's ACTION_UP and MiuiHome's OPEN_FROM_HOME
  up=$(grep -E "action=ACTION_UP" /tmp/w/lat.log | head -1 | awk '{print $2}')
  op=$(grep -E "setWindowAnimType  type=OPEN_FROM_HOME" /tmp/w/lat.log | head -1 | awk '{print $2}')
  ms=$(python3 -c "
import sys
def t(s): h,m,x=s.split(':'); return (int(h)*3600+int(m)*60+float(x))*1000
print(round(t('$op')-t('$up')) if '$up' and '$op' else 'x')")
  out="$out $ms"
done
echo "finger-up to open animation start (ms):$out"
