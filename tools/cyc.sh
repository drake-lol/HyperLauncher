#!/bin/bash
# usage: cyc.sh <tag> <x> <y> <n> <in-app wait> <home wait> [seconds]  -> rapid open/back cycles, 84-frame sheet + log
T=$1; D=$(dirname "$0")
adb shell "input keyevent KEYCODE_HOME; sleep 2"; adb logcat -c
$D/hf.sh $T "sh /data/local/tmp/cyc.sh $2 $3 $4 $5 $6 \"$8\"" ${7:-8} >/dev/null 2>&1; adb logcat -d -v time > /tmp/w/$T.log
python3 -I - $T <<'PY'
import subprocess,glob,os,sys
from PIL import Image
t=sys.argv[1]; d=f'/tmp/w/{t}_f'; os.makedirs(d,exist_ok=True)
for f in glob.glob(d+'/*.png'): os.remove(f)
subprocess.run(['ffmpeg','-v','error','-y','-i',f'/tmp/w/{t}.mp4','-vf','fps=30,scale=150:-1','-vsync','0',d+'/%04d.png'])
fs=sorted(glob.glob(d+'/*.png'))[25:]
step=max(1,len(fs)//96); idx=list(range(0,len(fs),step))[:96]
W,H=Image.open(fs[0]).size
row=Image.new('RGB',(W*16,H*6))
for k,i in enumerate(idx): row.paste(Image.open(fs[i]),((k%16)*W,(k//16)*H))
row.save(f'/tmp/w/{t}_sheet.png'); print(t,len(fs),'frames, every',step)
PY
grep -E "HyperLauncher *\(|HyperLauncher:|setWindowAnimType" /tmp/w/$T.log | grep -v MIUIInput | sed -E 's/^[0-9-]+ ([0-9:.]+).*(HyperLauncher[^:]*: |type=)/\1 /' | cut -c1-110
