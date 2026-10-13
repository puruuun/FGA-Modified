"""Verify the production NP layout/assets/options against the five submitted KR/JP images.

Requires OpenCV 4.11.0, Pillow and tesserocr using Tesseract 5.5.1. Uses the actual
bundled eng.traineddata; does not emulate an Android device or synthesize NP 2/3/4.
Original player screenshots stay local. The two clipped top rows are annotated.
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
from verify_support_append_images import normalized, constants

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT/'scripts/src/main/java/io/github/fate_grand_automata/scripts'
CASES = [('143344','Jp',[(110,1),(311,1),(512,5)]),
         ('134808','Jp',[(58,1),(258,5),(458,5)]),
         ('134749','Kr',[(204,5),(404,5)]),
         ('132621','Kr',[(183,5),(383,5)]),
         ('132655','Jp',[(179,1),(379,5)])]

def value(source,name):
    match=re.search(r'const val '+name+r' = ([\d.]+)(?: / ([\d.]+))?',source)
    assert match, name
    return float(match[1])/(float(match[2]) if match[2] else 1)

def fill_text(binary):
    mask=cv2.copyMakeBorder(binary*0,1,1,1,1,cv2.BORDER_CONSTANT,value=0)
    contours,hierarchy=cv2.findContours(cv2.bitwise_not(binary),cv2.RETR_TREE,cv2.CHAIN_APPROX_SIMPLE)
    if hierarchy is not None:
        hierarchy=hierarchy[0]
        for index,contour in enumerate(contours):
            parent=hierarchy[index][3]
            if parent>=0 and hierarchy[parent][3]<0:
                points=contour.reshape(-1,2)
                point=points[points[:,1].argmin()]
                cv2.floodFill(binary,mask,(int(point[0]),int(point[1])+1),255,
                              loDiff=0,upDiff=0,flags=4+(255<<8)+cv2.FLOODFILL_MASK_ONLY)
    return cv2.bitwise_not(mask)

def matches(roi,template,threshold):
    if roi.shape[0]<template.shape[0] or roi.shape[1]<template.shape[1]:return []
    response=cv2.matchTemplate(roi,template,cv2.TM_CCOEFF_NORMED)
    found=[]
    while True:
        _,similarity,_,(x,y)=cv2.minMaxLoc(response)
        if similarity<threshold:break
        found.append((similarity,x,y,template.shape[1],template.shape[0]))
        cv2.floodFill(response,None,(x,y),0,loDiff=.3,upDiff=.3,flags=4+cv2.FLOODFILL_FIXED_RANGE)
    return found

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input-dir',type=Path,required=True)
    parser.add_argument('--report',type=Path,required=True)
    args=parser.parse_args()
    layout=constants(SCRIPTS/'locations/SupportNpLayout.kt')
    source=(SCRIPTS/'supportSelection/SupportNpLevelReader.kt').read_text()
    setting={name:value(source,name) for name in ['PREFIX_SIMILARITY','MIN_OCR_CONFIDENCE',
             'AMBIGUITY_SCORE_GAP','SAME_PREFIX_DISTANCE','OCR_SCALE','OCR_THRESHOLD','OCR_PADDING']}
    whitelist=re.search(r'allowedCharacters = "([^"]+)"',source)[1]
    templates={}
    for server in ['Jp','Kr']:
        templates[server]=[]
        for name in ['support_np_lv.png','support_np_lv_gold.png']:
            path=ROOT/'app/src/main/assets'/server/name
            if not path.exists():path=ROOT/'app/src/main/assets/En'/name
            templates[server].append(cv2.imread(str(path),0))
    report={'opencv':cv2.__version__,'tesseract':tesserocr.tesseract_version(),
            'source_inputs':{},'settings':setting,'layout':layout,'checks':[],
            'limitations':['Desktop binding, not Android execution.',
                           'Only NP 1 and 5 were submitted; no real NP 2/3/4 validation.',
                           'Prefix assets originate from 143344 and 134749. Other three images are held out.',
                           'Two clipped top rows use annotated row origins.',
                           'Negative checks exclude the suffix from actual NP name fragments.']}
    with tesserocr.PyTessBaseAPI(path=str(ROOT/'app/src/main/assets/tessdata'),lang='eng',psm=7) as api:
        def read(roi,server):
            found=[m for template in templates[server] for m in matches(roi,template,setting['PREFIX_SIMILARITY'])]
            if not found:return {'level':None,'reason':'no_prefix'}
            best=max(found)
            similarity,x,y,w,h=best
            if any(m[0]>=similarity-setting['AMBIGUITY_SCORE_GAP'] and
                   (abs(m[1]-x)*2>setting['SAME_PREFIX_DISTANCE'] or abs(m[2]-y)*2>setting['SAME_PREFIX_DISTANCE']) for m in found):
                return {'level':None,'reason':'ambiguous_prefix'}
            margin=int(layout['SUFFIX_MARGIN']/2)
            digit=int(layout['DIGIT_WIDTH']/2)
            if x-margin<0 or y-margin<0 or x+w+digit>roi.shape[1] or y+h+margin>roi.shape[0]:
                return {'level':None,'reason':'clipped_suffix'}
            suffix=roi[y-margin:y+h+margin,x-margin:x+w+digit]
            scale=int(setting['OCR_SCALE'])
            up=cv2.resize(suffix,None,fx=scale,fy=scale,interpolation=cv2.INTER_CUBIC)
            binary=cv2.threshold(up,setting['OCR_THRESHOLD']*255,255,cv2.THRESH_BINARY)[1]
            filled=fill_text(binary.copy())
            padding=int(setting['OCR_PADDING'])
            prepared=cv2.copyMakeBorder(filled,padding,padding,padding,padding,cv2.BORDER_CONSTANT,value=255)
            api.SetVariable('tessedit_char_whitelist',whitelist)
            api.SetImage(Image.fromarray(prepared).convert('RGB'))
            text=api.GetUTF8Text().strip()
            confidence=api.MeanTextConf()
            api.Clear()
            match=re.fullmatch(r'Lv\.?\s*([1-5])',text,re.I)
            level=int(match[1]) if match and confidence>=setting['MIN_OCR_CONFIDENCE'] else None
            return {'level':level,'text':text,'confidence':confidence,'prefix_score':similarity,'prefix_x':x,'prefix_y':y}

        for name,server,rows in CASES:
            path=args.input_dir/f'Screenshot_20261013-{name}.png'
            raw=cv2.imread(str(path),0)
            assert raw is not None,path
            report['source_inputs'][name]={'file':path.name,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
            for method in ['crop_then_resize','resize_then_crop']:
                im=normalized(raw,method)
                tool=cv2.imread(str(ROOT/'app/src/main/assets'/server/'support_region_tool.png'),0)
                origins=[y-35 for _,_,y,_,_ in matches(im[:,1050:1235],tool,.75)]
                for annotated_y,expected in rows:
                    partial=(name,annotated_y) in [('143344',110),('134808',58)]
                    row_y=annotated_y if partial else min(origins,key=lambda y:abs(y-annotated_y))
                    assert abs(row_y-annotated_y)<=2
                    x=int((76+layout['NAME_X'])/2)
                    y=int(row_y+layout['NAME_Y']/2)
                    w,h=int(layout['NAME_WIDTH']/2),int(layout['NAME_HEIGHT']/2)
                    roi=im[y:y+h,x:x+w]
                    detected=read(roi,server)
                    assert detected['level']==expected,(name,method,row_y,expected,detected)
                    negative=read(roi[:,:detected['prefix_x']-3],server)
                    assert negative['level'] is None,(name,method,negative)
                    thresholds={minimum:detected['level']>=minimum for minimum in range(1,6)}
                    assert thresholds=={minimum:expected>=minimum for minimum in range(1,6)}
                    report['checks'].append({'file':name,'server':server,'normalization':method,
                        'row_y':row_y,'manual_partial':partial,'expected':expected,'detected':detected,
                        'negative':negative,'minimum_level_checks':thresholds})
    report['summary']={'level_checks':len(report['checks']),'negative_checks':len(report['checks']),
                       'minimum_level_checks':len(report['checks'])*5,
                       'automatic_rows':sum(not c['manual_partial'] for c in report['checks']),
                       'passed':True}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print('PASS:',json.dumps(report['summary']))

if __name__=='__main__':main()
