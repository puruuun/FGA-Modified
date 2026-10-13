"""Check compound Class/Grand OCR and preserve the original Class +62 matcher.

The new source is a cropped score panel, not a full support screen. Its numeric
line is normalized using the existing 720p panel size; two compatible scales and
two resampling orders test OCR only. Layout positioning of that new display in a
full screen still needs device validation. No screenshots with other Grand scores
were supplied; unit tests cover the corresponding selection policies.
"""
import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

sys.dont_write_bytecode = True
import cv2
import tesserocr
from PIL import Image
from verify_support_np_images import ROOT, SCRIPTS, value, fill_text, matches
from verify_support_append_images import normalized, rounded, constants

LEGACY_CASES = {
    '134741': ('Kr', [(204,0),(404,62)]),
    '134749': ('Kr', [(204,0),(404,62)]),
    '134803': ('Jp', [(58,62),(258,62),(458,62)]),
    '134808': ('Jp', [(58,62),(258,62),(458,62)]),
    '143344': ('Jp', [(111,23),(311,0),(512,43)])}


class Reader:
    def __init__(self, api):
        self.api = api
        self.layout = constants(SCRIPTS/'locations/SupportScoreLayout.kt')
        src = (SCRIPTS/'supportSelection/SupportScoreReader.kt').read_text()
        self.settings = {n:value(src,n) for n in ['MIN_OCR_CONFIDENCE','MIN_CONFIRM_CONFIDENCE','MIN_AGREEMENT',
                        'OCR_SCALE','OCR_LARGE_SCALE','OCR_THRESHOLD','OCR_BRIGHT_THRESHOLD','OCR_PADDING']}
        self.similarity = constants(SCRIPTS/'supportSelection/SupportScoreSelection.kt')['SCORE_SIMILARITY']
        self.regex = re.compile(re.search(r'compoundScore = Regex\("""([^\"]+)"""\)',src)[1])
        self.whitelist = re.search(r'allowedCharacters = "([^"]+)"',src)[1]
        s = self.settings
        self.profiles = [(int(s['OCR_SCALE']),s['OCR_THRESHOLD']), (int(s['OCR_SCALE']),s['OCR_BRIGHT_THRESHOLD']),
                         (int(s['OCR_LARGE_SCALE']),s['OCR_THRESHOLD']), (int(s['OCR_LARGE_SCALE']),s['OCR_BRIGHT_THRESHOLD'])]
        self.templates = {server:cv2.imread(str(ROOT/'app/src/main/assets'/folder/'support_score_62.png'),0)
                          for server,folder in [('Kr','Kr'),('Jp','En')]}

    def read(self, roi, server, check_class, check_grand):
        if not check_class and not check_grand:
            return {'class':None,'grand':None,'readings':[]}
        legacy = check_class and bool(matches(roi,self.templates[server],self.similarity))
        if legacy and not check_grand:
            return {'class':62,'grand':None,'readings':[],'legacy':True}
        readings=[]
        for scale,threshold in self.profiles:
            up=cv2.resize(roi,None,fx=scale,fy=scale,interpolation=cv2.INTER_CUBIC)
            prepared=fill_text(cv2.threshold(up,threshold*255,255,cv2.THRESH_BINARY)[1].copy())
            pad=int(self.settings['OCR_PADDING'])
            prepared=cv2.copyMakeBorder(prepared,pad,pad,pad,pad,cv2.BORDER_CONSTANT,value=255)
            self.api.SetVariable('tessedit_char_whitelist',self.whitelist)
            self.api.SetImage(Image.fromarray(prepared).convert('RGB'))
            text,confidence=self.api.GetUTF8Text().strip(),self.api.MeanTextConf()
            self.api.Clear()
            match=self.regex.fullmatch(re.sub(r'\s+','',text))
            parsed=tuple(map(int,match.groups())) if match and confidence>=self.settings['MIN_OCR_CONFIDENCE'] else None
            readings.append({'scale':scale,'threshold':threshold,'text':text,'confidence':confidence,'parsed':parsed})
        def field(i):
            known=[r for r in readings if r['parsed'] is not None]
            distinct={r['parsed'][i] for r in known}
            if len(distinct)!=1:return None
            n=next(iter(distinct))
            return n if sum(r['confidence']>=self.settings['MIN_CONFIRM_CONFIDENCE'] and r['parsed'][i]==n for r in known)>=self.settings['MIN_AGREEMENT'] else None
        result={'class':field(0),'grand':field(1),'readings':readings,'legacy':bool(legacy)}
        if result['class'] is None and result['grand'] is None and legacy and not any('/' in r['text'] for r in readings):
            result['class']=62
        return result


def policy(scores, class_on, grand_on, mode):
    selected=[]
    if class_on:selected.append(scores['class']==62)
    if grand_on:selected.append(scores['grand']==16)
    return not selected or (all(selected) if mode=='All' else any(selected))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--input-dir',required=True,type=Path)
    p.add_argument('--compound-image',required=True,type=Path)
    p.add_argument('--report',required=True,type=Path)
    args=p.parse_args()
    assert cv2.__version__=='4.11.0'
    assert tesserocr.tesseract_version().startswith('tesseract 5.5.1')
    report={'opencv':cv2.__version__,'tesseract':tesserocr.tesseract_version(),'source_inputs':{},'legacy':[],'compound':[],
            'limitations':['Desktop image testing; not an Android runtime.',
                           'New compound display is a crop without full-screen coordinates or native screen size.',
                           'Only one real Grand +16 sample was supplied. Non-16 Grand values are unit-tested, not real-image tested.']}
    raw=cv2.imread(str(args.compound_image),0)
    assert raw is not None and raw.shape==(210,250),raw.shape
    report['source_inputs']['compound']={'file':args.compound_image.name,'sha256':hashlib.sha256(args.compound_image.read_bytes()).hexdigest()}
    with tesserocr.PyTessBaseAPI(path=str(ROOT/'app/src/main/assets/tessdata'),lang='eng',psm=7) as api:
        reader=Reader(api)
        report.update(settings=reader.settings,layout=reader.layout,profiles=reader.profiles)
        for name,(server,rows) in LEGACY_CASES.items():
            path=args.input_dir/f'Screenshot_20261013-{name}.png'
            im=cv2.imread(str(path),0);assert im is not None,path
            report['source_inputs'][name]={'file':path.name,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
            for method in ['crop_then_resize','resize_then_crop']:
                gray=normalized(im,method)
                for row_y,expected in rows:
                    x=rounded((76+reader.layout['SCORE_X'])/2)
                    y=rounded(row_y+reader.layout['SCORE_Y']/2)
                    w,h=rounded(reader.layout['SCORE_WIDTH']/2),rounded(reader.layout['SCORE_HEIGHT']/2)
                    roi=gray[y:y+h,x:x+w]
                    # All and Any exercise the actual new reader even on ordinary displays.
                    for class_on,grand_on,mode in [(True,False,'All'),(False,True,'All'),(True,True,'All'),(True,True,'Any')]:
                        result=reader.read(roi,server,class_on,grand_on)
                        detected=policy(result,class_on,grand_on,mode)
                        wanted=expected==62 and class_on and (not grand_on or mode=='Any')
                        assert detected==wanted,(name,method,row_y,expected,class_on,grand_on,mode,result)
                        report['legacy'].append({'file':name,'normalization':method,'row_y':row_y,'expected_class':expected,
                                                 'class_on':class_on,'grand_on':grand_on,'mode':mode,'detected':result,'passed':True})
        # The score box is about 50px wide at matching resolution. Normalize the
        # supplied enlarged crop at compatible 0.28/0.30 rates, then pad the numeric
        # line into the production 55x30px ROI. This is OCR validation, not row detection.
        for rate in [.28,.30]:
            for method in ['crop_then_resize','resize_then_crop']:
                if method=='crop_then_resize':
                    line=cv2.resize(raw[113:178,33:216],None,fx=rate,fy=rate,interpolation=cv2.INTER_AREA)
                else:
                    scaled=cv2.resize(raw,(rounded(raw.shape[1]*rate),rounded(raw.shape[0]*rate)),interpolation=cv2.INTER_AREA)
                    line=scaled[rounded(113*rate):rounded(178*rate),rounded(33*rate):rounded(216*rate)]
                w,h=rounded(reader.layout['SCORE_WIDTH']/2),rounded(reader.layout['SCORE_HEIGHT']/2)
                assert line.shape[1]<=w and line.shape[0]<=h
                dx,dy=w-line.shape[1],h-line.shape[0]
                roi=cv2.copyMakeBorder(line,dy//2,dy-dy//2,dx//2,dx-dx//2,cv2.BORDER_CONSTANT,value=int(line[0].mean()))
                for class_on,grand_on,mode in [(True,False,'All'),(False,True,'All'),(True,True,'All'),(True,True,'Any')]:
                    result=reader.read(roi,'Jp',class_on,grand_on)
                    assert result['class']==62 and result['grand']==16,(rate,method,result)
                    assert policy(result,class_on,grand_on,mode)
                    report['compound'].append({'rate':rate,'normalization':method,'class_on':class_on,'grand_on':grand_on,
                                                'mode':mode,'detected':result,'passed':True})
    report['summary']={'legacy_full_screens':5,'legacy_numeric_regions':26,'legacy_policy_checks':len(report['legacy']),
                       'compound_real_samples':1,'compound_resampling_variants':4,'compound_policy_checks':len(report['compound']),'passed':True}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print('PASS:',json.dumps(report['summary']))


if __name__=='__main__':main()
