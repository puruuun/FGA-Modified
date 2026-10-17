"""Development aid. The authoritative check is `tools/device-qa/run.py`.

Check production CE crops against recorded JP support art and other CE assets.

Use --assets-dir with downloaded JP EquipFaces PNGs named as in the reference manifest
and the existing support-template-correlations.csv. Does not send input to the game.
"""
import argparse
import csv
import json
import re
from pathlib import Path
import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[1]

def load(path):
    image = cv2.imdecode(np.frombuffer(Path(path).read_bytes(), np.uint8), cv2.IMREAD_COLOR)
    assert image is not None, path
    return image

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assets-dir',type=Path,required=True)
    parser.add_argument('--report',type=Path,required=True)
    args=parser.parse_args()
    source=(ROOT/'app/src/main/java/io/github/fate_grand_automata/util/portraits/ServantPortraitImporter.kt').read_text(encoding='utf-8')
    body=re.search(r'object SupportCECrop \{(.*?)\n\}',source,re.S)[1]
    c={k:int(v) for k,v in re.findall(r'const val (\w+) = (\d+)',body)}
    prefs=(ROOT/'prefs/src/main/java/io/github/fate_grand_automata/prefs/core/PrefsCore.kt').read_text(encoding='utf-8')
    threshold=int(re.search(r'maker.int\("min_similarity", (\d+)\)',prefs)[1])/100
    rows=[row for row in csv.DictReader((ROOT/'wiki/scripts/research/support-template-correlations.csv').open(encoding='utf-8')) if row['kind']=='ce']
    assets={row['asset']:load(args.assets_dir/row['asset']) for row in rows}
    scaled={};crops={}
    for name,im in assets.items():
        assert im.shape[:2]==(c['SOURCE_HEIGHT'],c['SOURCE_WIDTH']),name
        resized=cv2.resize(im,(c['RESIZED_WIDTH'],c['RESIZED_HEIGHT']),interpolation=cv2.INTER_LINEAR)
        gray=cv2.cvtColor(resized,cv2.COLOR_BGR2GRAY)
        scaled[name]=gray
        crops[name]=gray[c['Y']:c['Y']+c['HEIGHT'],c['X']:c['X']+c['WIDTH']]
    cases=json.loads((ROOT/'docs/reference-images/ce-import-crops.json').read_text(encoding='utf-8'))['cases']
    positives=[]
    for case in cases:
        target=cv2.cvtColor(load(ROOT/case['reference']),cv2.COLOR_BGR2GRAY)
        template=crops[case['asset']]
        score=float(cv2.matchTemplate(target,template,cv2.TM_CCOEFF_NORMED).max())
        assert score>=threshold,(case,score)
        positives.append(dict(name=case['name'],layout=case['layout'],score=score))
    negatives=[]
    for name,template in crops.items():
        for other,target in scaled.items():
            if name==other:continue
            score=float(cv2.matchTemplate(target,template,cv2.TM_CCOEFF_NORMED).max())
            assert score<threshold,(name,other,score)
            negatives.append(dict(template=name,other=other,score=score))
    worst=max(negatives,key=lambda item:item['score'])
    report=dict(passed=True,opencv=cv2.__version__,crop=c,default_similarity=threshold,
                recorded_art_checks=positives,different_ce_checks=len(negatives),largest_different_ce_score=worst,
                limitations=['Recorded JP normal and Grand CE art; not current game gestures or all CE/game-region combinations.',
                             'Historical built-in images have varying sizes and are not used as uniform full-strip ground truth.'])
    args.report.parent.mkdir(parents=True,exist_ok=True)
    args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('PASS:',len(positives),'recorded CE art checks;',len(negatives),'different-CE checks; max wrong CE',round(worst['score'],4))

if __name__=='__main__':main()
