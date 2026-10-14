"""Verify bond OCR and screen/party layout against supplied 720p KR/JP references.

Uses shipped assets and reads coordinates, match thresholds and OCR profiles from Kotlin.
This is desktop OpenCV/Tesseract validation, not Android gesture/device verification.
"""
import argparse
import hashlib
import json
import math
import re
from pathlib import Path
import cv2
import tesserocr
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / 'scripts/src/main/java/io/github/fate_grand_automata/scripts'


def rounded(value):
    return math.floor(value + .5)


class Reader:
    def __init__(self):
        self.layout = (SCRIPTS / 'locations/BondLocations.kt').read_text()
        source = (SCRIPTS / 'modules/BondReader.kt').read_text()
        self.scales = [float(n) for n in re.search(r'IMAGE_SCALES = listOf\(([^)]+)', source)[1].split(',')]
        self.similarity = float(re.search(r'IMAGE_SIMILARITY = ([0-9.]+)', source)[1])
        self.confidence = int(re.search(r'TEXT_CONFIDENCE = (\d+)', source)[1])
        self.ocr_scales = [int(n) for n in re.search(r'GAIN_PROFILES = listOf\(([^)]+)', source)[1].split(',')]
        threshold_groups = re.findall(r'listOf\(([^)]+)\)\.map \{ threshold', source)
        self.thresholds = [int(n) for n in threshold_groups[0].split(',')]
        self.jp_thresholds = self.thresholds + [int(n) for n in threshold_groups[1].split(',')]
        self.api = tesserocr.PyTessBaseAPI(path=str(ROOT/'app/src/main/assets/tessdata'), lang='eng', psm=7)
        self.templates = {}

    def region(self, name, width, index=0):
        exprs = re.search(r'(?:val|fun) '+name+r'(?:\([^)]*\))?\s*= Region\(([^)]+)', self.layout)[1].split(',')
        values = []
        for expr in exprs:
            assert re.fullmatch(r'[\s0-9+*\-index]+', expr), expr
            values.append(eval(expr, {'__builtins__': {}}, {'index': index}))
        values[0] += width  # center of the 1440p script area
        return tuple(rounded(v/2) for v in values)

    @staticmethod
    def crop(im, box):
        x, y, w, h = box
        return im[max(0,y):y+h, max(0,x):x+w]

    def match(self, im, server, name, box):
        key = server, name
        if key not in self.templates:
            p = ROOT/'app/src/main/assets'/server/(name+'.png')
            if not p.exists(): p = ROOT/'app/src/main/assets/En'/(name+'.png')
            self.templates[key] = cv2.imread(str(p), 0)
        t = self.templates[key]
        assert t is not None, key
        roi = self.crop(im, box)
        best = (-1, None)
        for scale in self.scales:
            tt = cv2.resize(t, (int(t.shape[1]*scale), int(t.shape[0]*scale)), interpolation=cv2.INTER_CUBIC)
            if tt.shape[0] > roi.shape[0] or tt.shape[1] > roi.shape[1]: continue
            _, value, _, loc = cv2.minMaxLoc(cv2.matchTemplate(roi, tt, cv2.TM_CCOEFF_NORMED))
            if value > best[0]:
                best = (float(value), (loc[0]+max(0,box[0]), loc[1]+max(0,box[1]), tt.shape[1], tt.shape[0]))
        return best

    def text(self, im, box, level=False, server="Kr"):
        roi = self.crop(im, box)
        readings = []
        for scale in self.ocr_scales:
            if level and scale == 6: continue
            for threshold in (self.jp_thresholds if level or server == "Jp" else self.thresholds):
                up = cv2.resize(roi, None, fx=scale, fy=scale, interpolation=cv2.INTER_CUBIC)
                prepared = 255-up if threshold == 0 else cv2.threshold(up, threshold, 255, cv2.THRESH_BINARY_INV)[1]
                padded = cv2.copyMakeBorder(prepared, 12, 12, 12, 12, cv2.BORDER_CONSTANT, value=255)
                self.api.SetVariable('tessedit_char_whitelist', '0123456789 ' if level else '+(),0123456789 ')
                self.api.SetImage(Image.fromarray(padded))
                text = self.api.GetUTF8Text().strip()
                confidence = self.api.MeanTextConf()
                self.api.Clear()
                compact = re.sub(r'\s+', '', text)
                parsed = None
                if level:
                    if re.fullmatch(r'[0-9]{1,2}', text.strip()) and 1 <= int(text) <= 99: parsed = int(text)
                else:
                    m = re.fullmatch(r'\+?(\d{1,3}(?:,\d{3})*|\d+)\(\+?(\d{1,3}(?:,\d{3})*|\d+)\)', compact)
                    if m:
                        pair = [int(n.replace(',', '')) for n in m.groups()]
                        if all(n <= 2147483647 for n in pair): parsed = pair
                readings.append(dict(text=text, confidence=confidence, parsed=parsed))
        strong = [r['parsed'] for r in readings if r['confidence'] >= self.confidence and r['parsed'] is not None]
        value = strong[0] if len(strong) >= 2 and all(v == strong[0] for v in strong) else None
        return value, readings

    def read(self, im, server):
        width = im.shape[1]
        match = lambda name, region, index=0: self.match(im, server, name, self.region(region, width, index))
        popup = match('bond_popup_title', 'popupTitle')[0]
        normal = match('bond_result_title', 'resultTitle')[0]
        headings = dict(popup=popup, normal=normal)
        if popup >= self.similarity:
            arrow_score, arrow = match('bond', 'popupArrow')
            level = None; readings = []
            if arrow_score >= self.similarity:
                x,y,w,h = arrow
                # reachedLevel(arrow): arrow.right + 22, arrow.y - 24, 180, 110 at 1440p.
                assert 'Region(arrow.right + 22, arrow.y - 24, 180, 110)' in self.layout
                level, readings = self.text(im, (x+w+11,y-12,90,55), level=True, server=server)
            markers = [match('bond_level_up_marker','levelUpMarker',i)[0] for i in range(5)]
            candidates = [i for i,v in enumerate(markers) if v >= self.similarity]
            return dict(kind='popup', level=level, column=candidates[0] if len(candidates)==1 else None,
                        headings=headings, arrow_score=arrow_score, markers=markers, readings=readings)
        if normal >= self.similarity:
            rows = []
            for i in range(5):
                score = match('bond_max', 'gain', i)[0]
                value, readings = ('MAX', []) if score >= self.similarity else self.text(im, self.region('gain', width, i), server=server)
                rows.append(dict(value=value, max_score=score, readings=readings))
            return dict(kind='normal', headings=headings, rows=rows)
        if match('bond_party_heading', 'partyHeading')[0] >= self.similarity:
            levels = ['bond_party_level','bond_party_level_bronze','bond_party_level_kr','bond_party_level_plain']
            present = [i for i in range(6) if max(match(name,'partyLevel',i)[0] for name in levels) >= self.similarity]
            support = [i for i in range(6) if match('bond_party_support','partySupport',i)[0] >= self.similarity]
            own = [i+1 for i in present if i not in support]
            vacant = [i for i in range(6) if self.crop(im,self.region('partyVacant',width,i)).max() <= 55]
            valid = all(i in present or i in vacant for i in range(6)) and len(support) <= 1 and all(i in present for i in support) and 1 <= len(own) <= 5
            return dict(kind='party', headings=headings, owned=own if valid else None, present=present, support=support)
        return dict(kind='other', headings=headings)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    reader = Reader(); results = []
    try:
        cases = json.loads((ROOT/'tools/bond-image-cases.json').read_text())
        for case in cases:
            p = ROOT/case['file']; im = cv2.imread(str(p), 0)
            assert im is not None and im.shape[0] == 720, p
            actual = reader.read(im, case['server'])
            passed = actual['kind'] == case['kind']
            if case['kind'] == 'normal': passed &= [r['value'] for r in actual.get('rows',[])] == case['expected']
            elif case['kind'] == 'popup': passed &= actual.get('level') == case['expected_level'] and actual.get('column') == case['expected_column']
            else: passed &= actual.get('owned') == case['expected']
            results.append(dict(case=case, sha256=hashlib.sha256(p.read_bytes()).hexdigest(), actual=actual, passed=passed))
        # Blank controls must neither become a result nor a filled party.
        import numpy as np
        for server in ['Kr','Jp']:
            actual=reader.read(np.zeros((720,1280),dtype=np.uint8),server)
            results.append(dict(case=dict(name='blank-'+server,kind='other'),actual=actual,passed=actual['kind']=='other'))
    finally:
        reader.api.End()
    report = dict(scope='Full-frame desktop tests with shipped assets, source coordinates and bundled eng OCR data. No Android device validation.',
                  summary=dict(cases=len(results), passed=sum(r['passed'] for r in results), normal_slots=15, gain_lines=6, max_lines=9, popup_levels=5, party_maps=3), results=results)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report['summary']))
    for row in results:
        if not row['passed']: print('FAILED',row['case']['name'],row['actual'])
    return 0 if all(r['passed'] for r in results) else 1

if __name__ == '__main__': raise SystemExit(main())
