#!/bin/bash
# usage: hr.sh <tag> "<way back home>"  -> home, drag up to recents, back home; per-frame visibility of home content + sheet around oddities
T=$1; D=$(dirname "$0")
adb shell "input keyevent KEYCODE_HOME; sleep 2.5"; adb logcat -c
$D/hf.sh $T "sh /data/local/tmp/slowdrag.sh; sleep 1.2; $2" 7 >/dev/null 2>&1; adb logcat -d -v time > /tmp/w/$T.log
python3 -I - $T <<'PY'
import subprocess,glob,os,sys,numpy as np
from PIL import Image
t=sys.argv[1]; d=f'/tmp/w/{t}_f'; os.makedirs(d,exist_ok=True)
for f in glob.glob(d+'/*.png'): os.remove(f)
subprocess.run(['ffmpeg','-v','error','-y','-i',f'/tmp/w/{t}.mp4','-vf','fps=60,scale=244:-1','-vsync','0',d+'/%04d.png'])
fs=sorted(glob.glob(d+'/*.png'))
fr=[np.asarray(Image.open(f).convert('L')).astype(float)[14:] for f in fs]
n=len(fr)
d1=[0]+[float(np.abs(fr[i]-fr[i-1]).mean()) for i in range(1,n)]
flags=[]
for i in range(2,n-2):
    around=float(np.abs(fr[i-1]-fr[i+1]).mean())
    # a frame unlike both neighbours while they agree with each other: lower bar than glitch.py
    if d1[i]>0.8 and d1[i+1]>0.8 and around<0.5*min(d1[i],d1[i+1]): flags.append((i,round(d1[i],1),round(d1[i+1],1),round(around,1)))
print(t,n,'frames; one-frame oddities:',flags if flags else 'none')
if flags:
    i=flags[0][0]; idx=list(range(i-3,i+4))
    row=Image.new('RGB',(244*7,531))
    for k,j in enumerate(idx): row.paste(Image.open(fs[j]),(k*244,0))
    row.save(f'/tmp/w/{t}_odd.png')
PY
