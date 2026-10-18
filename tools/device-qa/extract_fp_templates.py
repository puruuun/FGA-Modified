#!/usr/bin/env python3
"""Re-cut the KR FP templates with the repository's extract-template-asset skill.

Input references are 1280x720 game images. Owner captures were actually 1988x1140,
not 1971x1131 as listed in 0007: crop (0,48)-(1936,1137), then resize with Lanczos.
Run with a Python containing Pillow. Review build/fp-template-review/*/*verify.png.
The manifest records source screen and 720p UI box; each extraction passes the
corresponding 1440p search region to crop_template.py. No device access is used.
"""
from pathlib import Path
import subprocess,sys,json
root=Path(__file__).resolve().parents[2]
# Verified game rectangle in all owner window captures: title 48px, toolbar 52px, bottom 3px.
recipes={
 'sell':(17,(319,461,56,31)),
 'heading':(18,(1030,13,216,50)), 'tab':(18,(68,103,64,25)),
 'confirm':(20,(1125,651,55,33)), 'convert':(21,(807,609,67,36)),
 'convert_heading':(21,(581,32,123,36)), 'done':(22,(491,223,293,32)),
 'close':(22,(610,608,66,36)), 'shop':(23,(1009,12,243,45)),
 'menu':(23,(1145,638,88,29)), 'summon_menu':(24,(615,628,59,29)),
}
out=root/'build/fp-template-review'; out.mkdir(parents=True,exist_ok=True)
for name,(n,(x,y,w,h)) in recipes.items():
    # The same 1440p region is recorded in the manifest; per-cell crops are relative to the card.
    region=(max(0,x-6)*2,max(0,y-6)*2,(w+12)*2,(h+12)*2)
    target=root/f'app/src/main/assets/Kr/fp_sell_{name}.png'
    result=subprocess.run([sys.executable,str(root/'.claude/skills/extract-template-asset/crop_template.py'),str(root/f'docs/reference-images/Kr/fp-sell/{n}.png'),'--region',','.join(map(str,region)),'--template',f'6,6,{w},{h}','--out',str(target),'--out-dir',str(out/name)],capture_output=True,text=True)
    if result.returncode: raise RuntimeError(result.stderr)
(root/'docs/reference-images/Kr/fp-sell/templates.json').write_text(json.dumps(recipes,indent=2)+'\n')
print('extracted',len(recipes),'templates')

