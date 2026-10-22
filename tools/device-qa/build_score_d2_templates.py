"""Generate JP maximum-score templates using only support-score-d2 training frames.

Development aid. The authoritative check is tools/device-qa/run.py.
Requires OpenCV and NumPy. Run with --generate; does not open the audit holdout.
9 is rendered by rotating a measured 6; both target and near-miss pairs combine measured glyphs.
The resource keeps resolution-specific variants; the 720p reader compares all of them.
"""
from pathlib import Path
import cv2,numpy as np,json
r=Path(__file__).resolve().parents[2]
root=r/'docs/reference-images/support-score-d2'
def filled(gray):
 a=cv2.resize(gray,None,fx=4,fy=4,interpolation=cv2.INTER_LINEAR)
 mean=cv2.blur(a.astype(np.float32),(21,21))
 return np.where((a>110)&(a>mean+5),255,0).astype(np.uint8)
def segments(gray):
 ink=filled(gray)
 n,lab,stats,cent=cv2.connectedComponentsWithStats(ink)
 # Filled title fragments sit above the baseline; keep number-sized ink only.
 boxes=[(x,y,w,h) for x,y,w,h,area in stats[1:] if h>=20 and y>=ink.shape[0]*.2 and y+h<=ink.shape[0]*.92 and h<=ink.shape[0]*.7]
 if not boxes:return ink,[]
 bottoms=sorted(y+h for x,y,w,h in boxes if h>=ink.shape[0]/3)
 if not bottoms:return ink,[]
 bottom=bottoms[len(bottoms)//2]
 boxes=[b for b in boxes if -12<=b[1]+b[3]-bottom<=8]
 # Segment by occupied columns, retaining vertical bounds of each run.
 clean=np.zeros_like(ink)
 for x,y,w,h in boxes:clean[y:y+h,x:x+w]=ink[y:y+h,x:x+w]
 runs=[]; start=None
 for x in range(clean.shape[1]+1):
  on=x<clean.shape[1] and np.any(clean[:,x])
  if on and start is None:start=x
  if not on and start is not None:
   ys=np.where(clean[:,start:x])[0]; runs.append((start,int(ys.min()),x-start,int(ys.max()-ys.min()+1)));start=None
 return clean,runs
def fields(gray):
 ink,boxes=segments(gray)
 # A thin serif stroke may be disconnected. Merge only a short fragment, never two tall glyphs.
 merged=[]
 for b in boxes:
  if merged and b[0]-(merged[-1][0]+merged[-1][2])<=max(b[3],merged[-1][3])*.25 and min(b[3],merged[-1][3])<max(b[3],merged[-1][3])*.8 and b[0]+b[2]-merged[-1][0]<=max(b[3],merged[-1][3])*.85:
   a=merged.pop();x=min(a[0],b[0]);y=min(a[1],b[1]);merged.append((x,y,max(a[0]+a[2],b[0]+b[2])-x,max(a[1]+a[3],b[1]+b[3])-y))
  else:merged.append(b)
 def slash(b):
  x,y,w,h=b
  if h<w*2:return False
  ys,xs=np.where(ink[y:y+h,x:x+w]);top=xs[ys<h/3];bot=xs[ys>=h*2/3]
  return len(top)>0 and len(bot)>0 and top.mean()-bot.mean()>w*.4
 slashes=[i for i,b in enumerate(merged) if slash(b)]
 if len(slashes)>1:return None,None,None
 si=slashes[0] if slashes else None
 def digits(bs,ref):
  bs=list(bs)
  while bs and bs[0][3]<ref*.8:bs.pop(0)
  if not bs or any(b[3]<ref*.7 for b in bs):return None
  return [ink[y:y+h,x:x+w] for x,y,w,h in bs]
 if si is None:
  ref=max((b[3] for b in merged),default=0);return digits(merged,ref),None,False
 ref=merged[si][3]
 return digits(merged[:si],ref),digits(merged[si+1:],ref),True
def norm(g):return cv2.resize(g,(16,32),interpolation=cv2.INTER_AREA).astype(np.float64).flatten()/255
def sim(a,b):
 a=a-a.mean();b=b-b.mean()
 return float(np.dot(a,b)/max(1e-12,np.linalg.norm(a)*np.linalg.norm(b))) # ink correlation; background agreement is not evidence
def train():
 labels=json.loads((root/'labels.json').read_text());samples=[];pools={}
 for f in labels['frames']:
  res=Path(f['file']).parent.name
  for i,row in enumerate(f['rows']):
   name=f'd2-{res}-{Path(f["file"]).stem}-row-{i+1}'
   raw=cv2.imread(str(root/'scores'/f'{name}.png'),0)
   for sigma in [0,.4,.7]:
    a=raw if sigma==0 else cv2.GaussianBlur(raw,(0,0),sigma)
    c,g,compound=fields(a)
    for field,gs,value in [('class',c,row['class']),('grand',g,row['grand'])]:
     if gs is not None and value is not None and len(gs)==len(str(value)):
      ns=[norm(x) for x in gs]
      samples.append(dict(name=name,frame=f['file'],res=res,field=field,value=value,digits=ns,sigma=sigma))
      for digit,n in zip(str(value),ns):pools.setdefault((res,digit),[]).append(n)
 return samples,pools
def templates(samples,pools):
 result=[]
 for s in samples:
  if len(s['digits'])==2:result.append((s['res'],s['field'],s['value'],s['frame'],np.concatenate(s['digits'])))
 # Render missing pairs using measured glyphs, including 9 made by rotating the measured 6.
 for res in sorted(set(s['res'] for s in samples)):
  pool={d:pools.get((res,d),[n for (rs,dg),ns in pools.items() if dg==d for n in ns]) for d in '012345678'}
  pool={d:[ns[i] for i in sorted(set(np.linspace(0,len(ns)-1,min(4,len(ns))).astype(int)))] for d,ns in pool.items()}
  pool['9']=[np.rot90(n.reshape(32,16),2).flatten() for n in pool['6']]
  for field,values in [('class',[62,52,63,68,82,57,51]),('grand',[16,10,11,12,13,15,18,19])]:
   for v in values:
    a,b=str(v)
    # All measured glyph variants; deduplicate identical rasters below.
    for ga in pool[a]:
     for gb in pool[b]:result.append((res,field,v,'rendered',np.concatenate([ga,gb])))
 return result
def generate():
 samples,pools=train();ts=templates(samples,pools)
 # Byte-quantized template blocks, generated only from the 12 training frames.
 unique={}
 for res,field,v,frame,t in ts:
  data=np.rint(t*255).astype(np.uint8).tobytes();unique[(res,field,v,data)]=(res,field,v,frame,data)
 ts=list(unique.values());print('templates',len(ts))
 # Pack all variants of a numeric value in a compact resource string for the pure JVM reader.
 import gzip
 target=r/'scripts/src/main/resources/support-score-d2.tsv.gz';target.parent.mkdir(exist_ok=True)
 target.write_bytes(gzip.compress(('\n'.join('\t'.join([res,field,str(v),data.hex()]) for res,field,v,frame,data in ts)+'\n').encode(),mtime=0))
 # Leave-one-frame-out calibration rebuilds every measured/rendered candidate without that frame.
 # Positive and negative pairs both combine the same glyph pool, avoiding a margin biased by pairing.
 by_frame={}
 for excluded in set(s['frame'] for s in samples):
  subset=[s for s in samples if s['frame']!=excluded];subset_pools={}
  for sample in subset:
   for digit,n in zip(str(sample['value']),sample['digits']):subset_pools.setdefault((sample['res'],digit),[]).append(n)
  by_frame[excluded]=templates(subset,subset_pools)
 stats=[]
 for s in samples:
  if len(s['digits'])!=2:continue
  target=62 if s['field']=='class' else 16;q=np.concatenate(s['digits'])
  pos=max((sim(q,t) for res,f,v,frame,t in by_frame[s['frame']] if f==s['field'] and v==target),default=0)
  neg=max((sim(q,t) for res,f,v,frame,t in by_frame[s['frame']] if f==s['field'] and v!=target),default=0)
  stats.append(dict(name=s['name'],field=s['field'],label=s['value'],sigma=s['sigma'],positive=pos,negative=neg,margin=pos-neg))
 (root/'calibration.json').write_text(json.dumps(stats,indent=2))
 print(json.dumps(stats,indent=2))
if __name__=='__main__':
 import sys
 if '--generate' in sys.argv:generate();raise SystemExit()
 fs=sorted((root/'scores').glob('*.png')); out=np.zeros((len(fs)*130,500),np.uint8)
 for i,f in enumerate(fs):
  gray=cv2.imread(str(f),0);ink,boxes=segments(gray);print(f.stem,boxes)
  out[i*130:i*130+120,:240]=ink
  cv2.putText(out,f.stem[3:],(245,i*130+20),cv2.FONT_HERSHEY_SIMPLEX,.28,255,1)
 cv2.imwrite(str(r/'build/orchestration/d2-ink.png'),out)
