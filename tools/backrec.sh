#!/bin/bash
# usage: backrec.sh <tag> <x> <y>  -> records backing out of an app; says how long the home screen keeps moving after the close
T=$1; D=$(dirname "$0")
adb shell "input keyevent KEYCODE_HOME; sleep 2; input tap $2 $3; sleep 3"; adb logcat -c
$D/hf.sh $T "${4:-sh /data/local/tmp/back.sh}" 4 >/dev/null 2>&1; adb logcat -d -v time > /tmp/w/$T.log
python3 -I - $T <<'PY'
import subprocess,glob,os,sys,numpy as np
from PIL import Image
t=sys.argv[1]; d=f'/tmp/w/{t}_f'; os.makedirs(d,exist_ok=True)
for f in glob.glob(d+'/*.png'): os.remove(f)
subprocess.run(['ffmpeg','-v','error','-y','-i',f'/tmp/w/{t}.mp4','-vf','fps=60,scale=305:-1','-vsync','0',d+'/%04d.png'])
fs=sorted(glob.glob(d+'/*.png'))
fr=[np.asarray(Image.open(f).convert('L')).astype(float)[20:] for f in fs]
rest=fr[-1]
dist=[float(np.abs(f-rest).mean()) for f in fr]
last_big=max(i for i,v in enumerate(dist) if v>12)
moving=[i for i,v in enumerate(dist) if v>0.6]
print(t,'frames',len(fs),'close ends ~',last_big,'home at rest from',moving[-1]+1,'=> settles for',(moving[-1]+1-last_big)*1000//60,'ms')
print('  distance from rest after the close:',[round(v,1) for v in dist[last_big:last_big+60:3]])
idx=[min(last_big-2+4*k,len(fs)-1) for k in range(14)]
row=Image.new('RGB',(305*7,664*2))
for k,i in enumerate(idx): row.paste(Image.open(fs[i]),((k%7)*305,(k//7)*664))
row.save(f'/tmp/w/{t}_sheet.png')
PY
grep -E "HyperLauncher.*closing|setWindowAnimType|START u0.*(HomeScreen|Lawnchair)" /tmp/w/$T.log | cut -c7-130 | tail -5
