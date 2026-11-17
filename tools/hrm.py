import glob,sys,numpy as np
from PIL import Image
# usage: hrm.py name -> size of the last jump before the screen comes to rest, vs. the creep just before it
t=sys.argv[1]
fs=sorted(glob.glob(f'/tmp/w/{t}_f/*.png'))
fr=[np.asarray(Image.open(f).convert('L')).astype(float)[14:] for f in fs]
d=[0]+[float(np.abs(fr[i]-fr[i-1]).mean()) for i in range(1,len(fr))]
big=max(i for i,v in enumerate(d) if v>5)
tail=[round(v,1) for v in d[big+1:big+60]]
print(t,'after the main movement:',tail)
