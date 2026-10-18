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
 'servant':(18,(121,301,33,8)), 'exp':(28,(119,301,35,9)),
 'new_bright':(18,(109,175,54,17)), 'new_dark':(28,(243,176,52,16)),
 'selected':(20,(478,183,9,9)),
 'star':(18,(173,282,13,14)), 'star_exp':(28,(173,283,13,14)),
 'dot_on':(15,(822,691,14,14)), 'dot_off':(15,(791,691,14,14)),
 'rarity1':(27,(128,278,60,14)), 'rarity2':(18,(128,566,60,14)),
 'rarity3':(18,(528,282,60,14)), 'rarity4':(18,(128,282,60,14)),
 'rarity_exp4':(28,(128,566,60,14)), 'rarity_exp5':(28,(128,282,60,14)),
 'new_orange':(27,(109,172,54,17)),
 'selected_lower':(20,(78,329,9,9)),
 'servant_selected':(20,(520,301,33,8)),
}
# (full_servant was replaced by the word template in cut_fp_templates.py)
# Background variants in the full five-position strip: fail closed on unrepresented artwork.
for row, col, rarity in [(0,1,4),(0,5,3),(0,6,3)]+[(1,c,3) for c in range(7)]+[(2,3,2),(2,6,2)]:
    x=round((156+267*col)/2)+50
    recipes[f'rarity{rarity}_r{row}c{col}']=(18,(x,282+142*row,60,14))
recipes['rarity_exp4_dark']=(27,(795,560,60,14))
recipes['rarity_exp5_dark']=(27,(261,276,60,14))
recipes.update({
 'new_selected':(20,(109,175,54,17)), 'new_selected_right':(20,(509,175,54,17)),
 'new_orange_right':(27,(509,171,54,17)),
 'servant_selected_bronze':(20,(121,585,33,8)),
 'exp_dark':(27,(519,579,35,9)),
 'dot_off_light':(25,(415,691,14,14)),
 'dot_off_a':(15,(478,691,14,14)), 'dot_off_b':(15,(603,691,14,14)),
 'dot_off_c':(15,(728,691,14,14)), 'dot_off_d':(15,(384,691,14,14)),
})
out=root/'build/fp-template-review'; out.mkdir(parents=True,exist_ok=True)
for name,(n,(x,y,w,h)) in recipes.items():
    # The same 1440p region is recorded in the manifest; per-cell crops are relative to the card.
    region=(max(0,x-6)*2,max(0,y-6)*2,(w+12)*2,(h+12)*2)
    target=root/f'app/src/main/assets/Kr/fp_sell_{name}.png'
    result=subprocess.run([sys.executable,str(root/'.claude/skills/extract-template-asset/crop_template.py'),str(root/f'docs/reference-images/Kr/fp-sell/{n}.png'),'--region',','.join(map(str,region)),'--template',f'6,6,{w},{h}','--out',str(target),'--out-dir',str(out/name)],capture_output=True,text=True)
    if result.returncode: raise RuntimeError(result.stderr)
(root/'docs/reference-images/Kr/fp-sell/templates.json').write_text(json.dumps(recipes,indent=2)+'\n')
print('extracted',len(recipes),'templates')

