import subprocess,glob,os,sys,numpy as np
from PIL import Image
# usage: glitch.py name  -> flags single-frame flashes and sudden jumps in /tmp/w/name.mp4
name=sys.argv[1]
d=f'/tmp/w/{name}_gl'; os.makedirs(d,exist_ok=True)
for f in glob.glob(d+'/*.png'): os.remove(f)
subprocess.run(['ffmpeg','-v','error','-y','-i',f'/tmp/w/{name}.mp4','-vf','fps=60,scale=244:-1','-vsync','0',d+'/%04d.png'])
fs=sorted(glob.glob(d+'/*.png'))
fr=[np.asarray(Image.open(f).convert('L')).astype(float)[14:] for f in fs]
n=len(fr)
def diff(a,b): return float(np.abs(fr[a]-fr[b]).mean())
d1=[0]+[diff(i,i-1) for i in range(1,n)]
flags=[]
for i in range(2,n-2):
    around=diff(i-1,i+1)
    if d1[i]>2.5 and d1[i+1]>2.5 and around<0.45*min(d1[i],d1[i+1]): flags.append((i,'flash',round(d1[i],1),round(around,1)))
    if d1[i]>14 and d1[i-1]<0.3 and d1[i+1]<0.3: flags.append((i,'cut',round(d1[i],1),0))
active=[i for i,v in enumerate(d1) if v>0.3]
span=(active[0],active[-1]) if active else (0,0)
print(f"{name}: {n} frames, moving {span[0]}-{span[1]} ({(span[1]-span[0])*1000//60} ms), flags: {flags if flags else 'none'}")
if flags:
    idx=sorted(set(j for i,_,_,_ in flags[:3] for j in range(i-2,i+3) if 0<=j<n))[:15]
    row=Image.new('RGB',(244*len(idx),531))
    for k,i in enumerate(idx): row.paste(Image.open(fs[i]),(k*244,0))
    row.save(f'/tmp/w/{name}_gl.png')
