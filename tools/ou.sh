#!/bin/bash
# usage: ou.sh <tag> <x> <y> <delay after tap> -> tap icon, swipe up after delay; sheet of 28 frames + timeline
T=$1; D=$(dirname "$0")
adb shell "input keyevent KEYCODE_HOME; sleep 2.5"; adb logcat -c
$D/hf.sh $T "input tap $2 $3; sleep $4; input swipe 610 2650 610 1400 130" 4 >/dev/null 2>&1; adb logcat -d -v time > /tmp/w/$T.log
python3 -I - $T <<'PY'
import subprocess,glob,os,sys,numpy as np
from PIL import Image
t=sys.argv[1]; d=f'/tmp/w/{t}_f'; os.makedirs(d,exist_ok=True)
for f in glob.glob(d+'/*.png'): os.remove(f)
subprocess.run(['ffmpeg','-v','error','-y','-i',f'/tmp/w/{t}.mp4','-vf','fps=60,scale=200:-1','-vsync','0',d+'/%04d.png'])
fs=sorted(glob.glob(d+'/*.png'))
fr=[np.asarray(Image.open(f).convert('L')).astype(float)[14:] for f in fs]
dd=[0]+[float(np.abs(fr[i]-fr[i-1]).mean()) for i in range(1,len(fr))]
act=[i for i,v in enumerate(dd) if v>0.5 and i>20] or [30]
a,b=act[0],act[-1]
# longest still stretch inside the movement
run=best=0; at=0
for i in range(a,b+1):
    if dd[i]<0.15:
        run+=1
        if run>best: best=run; at=i
    else: run=0
print(t,'moving',a,b,f'({(b-a)*1000//60} ms)','longest freeze',best*1000//60,'ms ending at frame',at)
idx=[a+round(k*(b+3-a)/27) for k in range(28)]
W,H=Image.open(fs[0]).size
row=Image.new('RGB',(W*14,H*2))
for k,i in enumerate(idx): row.paste(Image.open(fs[min(i,len(fs)-1)]),((k%14)*W,(k//14)*H))
row.save(f'/tmp/w/{t}_sheet.png')
PY
grep -E "HyperLauncher *\(|HyperLauncher:|setWindowAnimType|GestureStubHome', \{ action=ACTION_(DOWN|UP)" /tmp/w/$T.log | grep -v "moveCount:[1-9]" | sed -E 's/^[0-9-]+ ([0-9:.]+).*(HyperLauncher[^:]*: |type=|action=)/\1 /' | cut -c1-90
