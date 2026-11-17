#!/bin/bash
# usage: bclose.sh <tag> "<setup: get into an app>" ["<close cmd>"]  -> records the close; reports how much of the home screen is visible mid-animation
T=$1; D=$(dirname "$0")
timeout 60 adb shell "input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; input keyevent KEYCODE_HOME; sleep 2; $2" || exit 1
adb logcat -c; $D/hf.sh $T "${3:-sh /data/local/tmp/sb.sh}" 4 >/dev/null 2>&1; adb logcat -d -v time > /tmp/w/$T.log
python3 -I - $T <<'PY'
import subprocess,glob,os,sys,numpy as np
from PIL import Image
t=sys.argv[1]; d=f'/tmp/w/{t}_f'; os.makedirs(d,exist_ok=True)
for f in glob.glob(d+'/*.png'): os.remove(f)
subprocess.run(['ffmpeg','-v','error','-y','-i',f'/tmp/w/{t}.mp4','-vf','fps=60,scale=305:-1','-vsync','0',d+'/%04d.png'])
fs=sorted(glob.glob(d+'/*.png'))
fr=[np.asarray(Image.open(f).convert('L')).astype(float)[20:] for f in fs]
dd=[0]+[float(np.abs(fr[i]-fr[i-1]).mean()) for i in range(1,len(fr))]
act=[i for i,v in enumerate(dd) if v>2 and i>20] or [30]
a,b=act[0],act[-1]
# clock text (top area) of the resting home: is it there while things move?
def clock(i): return float((np.asarray(Image.open(fs[i]).convert('L').crop((60,90,240,140))).astype(float)>215).mean())
base=clock(len(fs)-1) or 1
print(t,'moving',a,b,'| clock visible (1=fully) from first movement, every 3 frames:',[round(clock(i)/base,1) for i in range(a,min(a+75,len(fs)),3)])
idx=[a+round(k*(max(b,a+40)+3-a)/13) for k in range(14)]
row=Image.new('RGB',(305*7,664*2))
for k,i in enumerate(idx): row.paste(Image.open(fs[min(i,len(fs)-1)]),((k%7)*305,(k//7)*664))
row.save(f'/tmp/w/{t}_all.png')
PY
grep -E "HyperLauncher *\(|HyperLauncher:|setWindowAnimType" /tmp/w/$T.log | grep -v MIUIInput | sed -E 's/.*(HyperLauncher[^:]*: |type=)//' | cut -c1-90 | tr '\n' ';' | cut -c1-300; echo
