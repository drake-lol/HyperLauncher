#!/bin/bash
# usage: land.sh <tag> <x> <y> ["<close cmd>"]  -> opens the icon, turns the screen to landscape, records the close; /tmp/w/<tag>_sheet.png
T=$1; C=${4:-input swipe 1328 1215 1328 450 130}; D=$(dirname "$0")
timeout 40 adb shell "input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; wm user-rotation lock 0; input keyevent KEYCODE_HOME; sleep 2; input tap $2 $3; sleep 3; wm user-rotation lock 1; sleep 2.5" || exit 1
adb logcat -c; $D/hf.sh $T "$C" 4 >/dev/null 2>&1; adb logcat -d -v time > /tmp/w/$T.log
adb shell "wm user-rotation lock 0; sleep 1"
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
idx=[min(a+round(k*(b+3-a)/15),len(fs)-1) for k in range(16)]
ims=[Image.open(fs[i]) for i in idx]; w=max(i.size[0] for i in ims); h=300
row=Image.new('RGB',(w*4,h*4),(40,40,40))
for k,im in enumerate(ims): row.paste(im,((k%4)*w,(k//4)*h))
row.save(f'/tmp/w/{t}_sheet.png'); print(t,len(fs),'moving',a,b,[i.size for i in ims[:1]])
PY
grep -E "HyperLauncher.*closing|setWindowAnimType" /tmp/w/$T.log | cut -c7-150 | head -6
