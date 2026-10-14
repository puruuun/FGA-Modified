"""Verify Teapot/Storm Pod recognition against local KR/JP screenshots.

Uses shipped templates, Kotlin thresholds and 1440p regions (converted to 720p).
Full screenshots stay local. The launcher UI is a placement reference, not a game-state test.
"""
import argparse
import hashlib
import json
import re
import sys
import unicodedata
from pathlib import Path
sys.dont_write_bytecode = True
import cv2
from verify_support_append_images import ROOT, SCRIPTS, constants, rounded, score
from verify_support_np_images import matches

CASES = {
    'kr 버튼 off 스톰포드.png': ('Kr', 'StormPod', False, None),
    'Kr 버튼 off 티포트.png': ('Kr', 'Teapot', False, None),
    'kr 버튼 클릭시 나오게되는 창.png': ('Kr', 'StormPod', True, (True, True)),
    'jp 버튼 off 티포트.png': ('Jp', 'Teapot', False, None),
    'jp 버튼 on 티포트.png': ('Jp', 'Teapot', True, None),
    'jp 버튼 off 스톰포드.png': ('Jp', 'StormPod', False, None),
    'Jp 버튼 on 스톰포드.png': ('Jp', 'StormPod', True, None),
    'jp 버튼 off 클릭시 나오게되는 창.png': ('Jp', 'Teapot', False, (False, False)),
    'jp 버튼 on 클릭시 나오게되는 창.png': ('Jp', 'Teapot', True, (True, True)),
}

def normalize(raw, method):
    h, w = raw.shape[:2]
    height = min(h, rounded(w * 9 / 16))
    top = max(0, rounded((h - height) / 2))
    if method == 'crop_then_resize':
        return cv2.resize(raw[top:top+height], (1280, 720), interpolation=cv2.INTER_AREA)
    rate = 720 / height
    scaled = cv2.resize(raw, (1280, rounded(h*rate)), interpolation=cv2.INTER_AREA)
    offset = rounded(top*rate)
    return scaled[offset:offset+720]

class Reader:
    def __init__(self):
        self.settings = constants(SCRIPTS/'modules/QuestItemReader.kt')
        text = (SCRIPTS/'locations/QuestItemLocations.kt').read_text()
        self.regions = {}
        for key in ['mainControl','partyHeading','dialogHeading','stormPodControl','teapotControl','dialogClose']:
            expression = re.search(r'val '+key+r'\s*=([^\n]+)', text)[1]
            rects = re.findall(r'Region\((-?\d+),\s*(-?\d+),\s*(\d+),\s*(\d+)\)', expression)
            x,y,w,h = map(int,rects[-1]) # 16:9 branch
            if key in ['mainControl','partyHeading']: x += 2560
            else: x += 1280
            if key == 'mainControl': y += 1440
            self.regions[key] = tuple(rounded(v/2) for v in (x,y,w,h))
        self.cache = {}
    def template(self, name, server):
        key = name, server
        if key not in self.cache:
            path = ROOT/'app/src/main/assets'/server/f'quest_item_{name}.png'
            if not path.exists(): path = ROOT/'app/src/main/assets/En'/f'quest_item_{name}.png'
            self.cache[key] = cv2.imread(str(path), 0)
            assert self.cache[key] is not None, path
        return self.cache[key]
    def values(self, gray, region, names, server):
        x,y,w,h = self.regions[region]; roi = gray[y:y+h,x:x+w]
        return {name:score(roi,self.template(name,server)) for name in names}
    def button(self, values):
        on = max(values['on_modal'], values['on_main']) >= self.settings['STATE_SIMILARITY']
        off = max(values['off_modal'], values['off_main']) >= self.settings['STATE_SIMILARITY']
        return on if on != off else None
    def confirmed_button(self, gray, region, server):
        x,y,w,h=self.regions[region];roi=gray[y:y+h,x:x+w];found=[]
        for on,name in [(True,'on_modal'),(True,'on_main'),(False,'off_modal'),(False,'off_main')]:
            for similarity,mx,my,mw,mh in matches(roi,self.template(name,server),self.settings['STATE_SIMILARITY']):
                # Match centers are transformed to 1440p in the Kotlin reader.
                found.append((on,similarity,2*mx+mw,2*my+mh))
        if not found:return None
        best=max(found,key=lambda item:item[1])
        if any(on!=best[0] or abs(cx-best[2])>20 or abs(cy-best[3])>20 for on,_,cx,cy in found):return None
        return best[0]
    def read(self, gray, server):
        v = {key:self.values(gray,key,['on_modal','on_main','off_modal','off_main'],server)
             for key in ['mainControl','stormPodControl','teapotControl']}
        title = self.values(gray,'dialogHeading',['modal'],server)['modal'] >= self.settings['SCREEN_SIMILARITY']
        close = self.values(gray,'dialogClose',['close'],server)['close'] >= self.settings['SCREEN_SIMILARITY']
        party = self.values(gray,'partyHeading',['party'],server)['party'] >= self.settings['SCREEN_SIMILARITY']
        icons = self.values(gray,'mainControl',['tea','storm'],server)
        tea = icons['tea'] >= self.settings['ICON_SIMILARITY']; storm = icons['storm'] >= self.settings['ICON_SIMILARITY']
        kind = 'Teapot' if tea and not storm else 'StormPod' if storm and not tea else None
        main = self.confirmed_button(gray,'mainControl',server); left=self.confirmed_button(gray,'stormPodControl',server); right=self.confirmed_button(gray,'teapotControl',server)
        if title or close: view = 'Dialog' if title and close and left is not None and right is not None else 'Unknown'
        elif not party: view = 'Unknown'
        elif kind is not None and main is not None: view = 'Single'
        elif not tea and not storm and all(value < self.settings['STATE_SIMILARITY'] for value in v['mainControl'].values()): view = 'Unavailable'
        else: view = 'Unknown'
        return dict(view=view, main_kind=kind, main_on=main, storm_on=left, tea_on=right, dialog=title, close=close,
                    similarities=v, icon_similarities=icons)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input-dir',type=Path,required=True)
    parser.add_argument('--report',type=Path,required=True)
    args=parser.parse_args()
    files={unicodedata.normalize('NFC',p.name):p for p in args.input_dir.glob('*.png')}
    reader=Reader(); report=dict(passed=True,opencv=cv2.__version__,thresholds=reader.settings,regions_720p=reader.regions,
        source_inputs={},screens=[],negative_checks=[],limitations=['Desktop screenshot tests; no live Android/FGO interaction.',
        'Provided game screens are near 16:9; wide/notch layouts are not image-tested.',
        'Launcher screenshot is a UI placement reference.'])
    for name,(server,kind,on,dialog) in CASES.items():
        path=files[name]; raw=cv2.imread(str(path),0); assert raw is not None
        report['source_inputs'][name]={'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'native_size':list(raw.shape[::-1])}
        for method in ['crop_then_resize','resize_then_crop']:
            gray=normalize(raw,method); result=reader.read(gray,server)
            assert result['view']==('Dialog' if dialog else 'Single'),(name,method,result)
            assert result['main_kind']==kind and result['main_on']==on,(name,method,result)
            assert result['dialog']==bool(dialog) and result['close']==bool(dialog),(name,method,result)
            if dialog: assert (result['storm_on'],result['tea_on'])==dialog,(name,method,result)
            else: assert result['storm_on'] is None and result['tea_on'] is None,(name,method,result)
            report['screens'].append(dict(file=name,normalization=method,detected=result,passed=True))
            if not dialog:
                # Deliberately remove the complete control: ordinary quests without items can continue.
                missing=gray.copy();x,y,w,h=reader.regions['mainControl']; missing[y:y+h,x:x+w]=0
                assert reader.read(missing,server)['view']=='Unavailable'
                report['negative_checks'].append(dict(file=name,normalization=method,case='synthetic absent main control',passed=True))
    button_path=files['버튼.png']; button=cv2.imread(str(button_path),0)
    report['cropped_button']=[]
    for rate in [0.30,0.32]:
        scaled=cv2.resize(button,None,fx=rate,fy=rate,interpolation=cv2.INTER_AREA)
        values={name:score(scaled,reader.template(name,'Jp')) for name in ['on_modal','on_main','off_modal','off_main']}
        assert reader.button(values) is True,values
        report['cropped_button'].append(dict(rate=rate,on=True,similarities=values,passed=True))
    report['source_inputs']['버튼.png']={'sha256':hashlib.sha256(button_path.read_bytes()).hexdigest()}
    report['summary']=dict(real_game_screens=len(CASES),normalization_checks=len(report['screens']),
        correct_game_screens=len(CASES),recognition_percent=100,synthetic_negative_checks=len(report['negative_checks']),passed=True)
    args.report.parent.mkdir(parents=True,exist_ok=True);args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print('PASS:',json.dumps(report['summary']))
if __name__=='__main__': main()
