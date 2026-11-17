import glob,sys,numpy as np
from PIL import Image
# usage: blank.py name x0 y0 x1 y1  -> frames (of name_f, 305 px wide) where the bright home content in the box is gone
t=sys.argv[1]; box=tuple(int(v) for v in sys.argv[2:6])
fs=sorted(glob.glob(f'/tmp/w/{t}_f/*.png'))
v=[float((np.asarray(Image.open(f).convert('L').crop(box)).astype(float)>215).mean()) for f in fs]
base=max(v[5:15]) or 1
r=[round(x/base,2) for x in v]
first=next((i for i,x in enumerate(r) if x<0.97 and i>20),len(r))
print(t,'home content from first change:',r[first-1:first+16])
