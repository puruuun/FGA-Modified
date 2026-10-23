"""Normalize and register owner 0019 append fixtures (18 resampled wide + one window)."""
from pathlib import Path
import cv2,json,math
R=Path(__file__).resolve().parents[2]
cases=[]
for src in sorted((R/'build/orchestration/inputs/0019-append').glob('*.webp')):
 im=cv2.imread(str(src));h,w=im.shape[:2]
 if 'window' in src.name:
  im=im[48:1147,0:1950];width=1280;server='Kr';level=7;note='BlueStacks window; chrome removed using owner game box'
 else:
  # Product FgoGameAreaManager: >21:9 crops to 3360:1440 before comparisons.
  cropw=math.floor(h*3360/1440+0.5);left=math.floor((w-cropw)/2+0.5)
  im=im[:,left:left+cropw];width=1680;server='Kr' if src.name.startswith('kr') else 'Jp'
  level=int(src.stem.split('-')[2]);note='Resampled 2000x844 upload; not native; centered 21:9 game area'
 out=R/f'docs/reference-images/{server}/skill-level/0019/{src.stem}.png'
 out.parent.mkdir(parents=True,exist_ok=True)
 cv2.imwrite(str(out),cv2.resize(im,(width,720),interpolation=cv2.INTER_AREA))
 cases.append(dict(name='0019-'+src.stem,suite='skillLevel',server=server,file=out.relative_to(R).as_posix(),
   view='levels',slotMode='Append',note=note,expected=dict(current=level,next=level+1)))
p=R/'docs/reference-images/cases/skillLevel.json';data=json.loads(p.read_text(encoding='utf-8'))
data['cases']=[c for c in data['cases'] if not c['name'].startswith('0019-')]+cases
p.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
(R/'build/orchestration/stable162-19-cases.json').write_text(json.dumps(dict(schema=1,suite='skillLevel',cases=cases)),encoding='utf-8')
print(len(cases),'frames normalized')
