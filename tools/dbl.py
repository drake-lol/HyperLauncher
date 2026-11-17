import glob,subprocess,numpy as np,os,sys
from PIL import Image
# usage: dbl.py name x0 y0 x1 y1 -> brightness of a see-through element in every frame of the last 70 frames with movement
t=sys.argv[1]; x0,y0,x1,y1=[int(v) for v in sys.argv[2:6]]
d=f'/tmp/w/{t}_full'; os.makedirs(d,exist_ok=True)
for f in glob.glob(d+'/*.png'): os.remove(f)
subprocess.run(['ffmpeg','-v','error','-y','-i',f'/tmp/w/{t}.mp4','-vf',f"fps=60,crop={x1-x0}:{y1-y0}:{x0}:{y0}",'-vsync','0',d+'/%04d.png'])
fs=sorted(glob.glob(d+'/*.png'))
v=[round(float(np.asarray(Image.open(f).convert('L')).mean()),1) for f in fs]
rest=v[-1]
odd=[i for i,x in enumerate(v) if abs(x-rest)>3]
last=[i for i in odd if i>len(v)-200]
print(t,'rest',rest,'| frames off rest near the end:',[(i,v[i]) for i in last][-30:])
