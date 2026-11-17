import subprocess,glob,os,sys,numpy as np
from PIL import Image
# usage: sheet.py name [start|first|last|span] [crop x y]  -> /tmp/w/name_sheet.png, 14 frames
name=sys.argv[1]; mode=sys.argv[2] if len(sys.argv)>2 else 'span'
crop=(int(sys.argv[4]),int(sys.argv[5])) if len(sys.argv)>5 and sys.argv[3]=='crop' else None
d=f'/tmp/w/{name}_f'; os.makedirs(d,exist_ok=True)
for f in glob.glob(d+'/*.png'): os.remove(f)
vf='fps=60,'+(f'crop=420:420:{crop[0]-210}:{crop[1]-210}' if crop else 'scale=305:-1')
subprocess.run(['ffmpeg','-v','error','-y','-i',f'/tmp/w/{name}.mp4','-vf',vf,'-vsync','0',d+'/%04d.png'])
fs=sorted(glob.glob(d+'/*.png'))
fr=[np.asarray(Image.open(f).convert('L')).astype(float)[20:] for f in fs]
dd=[0]+[float(np.abs(fr[i]-fr[i-1]).mean()) for i in range(1,len(fr))]
act=[i for i,v in enumerate(dd) if v>(0.15 if crop else 1.0) and i>20] or [30]
a,b=act[0],act[-1]
if mode=='first': idx=list(range(max(a-1,0),a+13))
elif mode=='last': idx=list(range(b-11,b+3))
elif mode.isdigit(): idx=list(range(int(mode),int(mode)+14))
else: idx=[a+round(k*(b+3-a)/13) for k in range(14)]
idx=[min(max(i,0),len(fs)-1) for i in idx]
im0=Image.open(fs[0]); w,h=(250,250) if crop else im0.size
row=Image.new('RGB',(w*7,h*2))
for k,i in enumerate(idx): row.paste(Image.open(fs[i]).resize((w,h)),((k%7)*w,(k//7)*h))
row.save(f'/tmp/w/{name}_sheet.png'); print(name,len(fs),'moving',a,b,'shown',idx[0],idx[-1])
