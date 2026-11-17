#!/bin/bash
# usage: lrec.sh <tag> "<recorded cmds>" [seconds]  -> 16-frame sheet at any screen rotation, plus where it ended
T=$1; D=$(dirname "$0"); adb logcat -c; $D/hf.sh $T "$2" ${3:-3} >/dev/null 2>&1; adb logcat -d -v time > /tmp/w/$T.log
python3 -I - $T <<'PY'
import subprocess,glob,os,sys,numpy as np
from PIL import Image
t=sys.argv[1]; d=f'/tmp/w/{t}_f'; os.makedirs(d,exist_ok=True)
for f in glob.glob(d+'/*.png'): os.remove(f)
subprocess.run(['ffmpeg','-v','error','-y','-i',f'/tmp/w/{t}.mp4','-vf','fps=60,scale=-1:300','-vsync','0',d+'/%04d.png'])
fs=sorted(glob.glob(d+'/*.png'))
fr=[np.asarray(Image.open(f).convert('L').resize((160,160))).astype(float) for f in fs]
dd=[0]+[float(np.abs(fr[i]-fr[i-1]).mean()) for i in range(1,len(fr))]
act=[i for i,v in enumerate(dd) if v>1.0 and i>20] or [30]
a,b=act[0],act[-1]
idx=[min(max(a-1,0)+round(k*(b+4-a)/15),len(fs)-1) for k in range(16)]
ims=[Image.open(fs[i]) for i in idx]; w=max(i.size[0] for i in ims)
row=Image.new('RGB',(w*4,1200),(40,40,40))
for k,im in enumerate(ims): row.paste(im,((k%4)*w,(k//4)*300))
row.save(f'/tmp/w/{t}_sheet.png'); print(t,len(fs),'moving',a,b)
PY
echo "  -> $(adb shell dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity | sed -E 's/.*u0 ([^ /]+).*/\1/') $(adb shell "dumpsys window displays | sed -n '/Display: mDisplayId=0/,\$p' | grep -m1 -o 'ROTATION_[0-9]*'" 2>/dev/null) | $(grep -E 'HyperLauncher *\(|HyperLauncher:|setWindowAnimType' /tmp/w/$T.log | grep -v MIUIInput | sed -E 's/.*(HyperLauncher[^:]*: |type=)//' | cut -c1-90 | tr '\n' ';' | cut -c1-420)"
