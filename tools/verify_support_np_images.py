"""Verify production NP recognition against the 16 submitted KR/JP screenshots.

Uses production layout, recovery constants, templates and bundled eng.traineddata.
Requires OpenCV 4.11.0 and Tesseract 5.5.1. This is a desktop image test, not an
Android runtime. Original player screenshots stay local; 11 partial rows have
annotated origins. Recovery parameters were selected using this dataset.
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
import tesserocr
from PIL import Image
from verify_support_append_images import normalized, constants, rounded

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT/'scripts/src/main/java/io/github/fate_grand_automata/scripts'
CASES = [('143344','Jp',[(110,1),(311,1),(512,5)]),
         ('134808','Jp',[(58,1),(258,5),(458,5)]),
         ('134749','Kr',[(204,5),(404,5)]),
         ('132621','Kr',[(183,5),(383,5)]),
         ('132655','Jp',[(179,1),(379,5)])]
EXTRA_CASES = [
    ('9.16.38','Kr',[(105,3,True),(305,5,False),(506,4,False)]),
    ('9.17.04','Kr',[(91,2,True),(291,1,False),(492,3,False)]),
    ('9.17.19','Kr',[(91,5,True),(291,3,False),(492,1,False)]),
    ('9.17.35','Kr',[(27,1,True),(227,4,False),(428,2,False)]),
    ('9.18.11','Jp',[(176,4,False),(376,1,False)]),
    ('9.18.31','Jp',[(101,1,True),(301,1,False),(501,1,False)]),
    ('9.18.38','Jp',[(110,2,True),(310,4,False),(510,5,False)]),
    ('9.18.51','Jp',[(111,3,True),(311,5,False),(512,2,False)]),
    ('9.19.01','Jp',[(93,4,True),(293,4,False),(494,1,False)]),
    ('9.19.12','Jp',[(132,4,False),(332,2,False),(533,1,False)]),
    ('9.19.35','Jp',[(58,5,True),(258,5,False),(459,3,False)])]


def value(source, name):
    match = re.search(r'const val '+name+r' = ([\d.]+)(?: / ([\d.]+))?', source)
    assert match, name
    return float(match[1])/(float(match[2]) if match[2] else 1)


def fill_text(binary):
    mask = cv2.copyMakeBorder(binary*0, 1, 1, 1, 1, cv2.BORDER_CONSTANT, value=0)
    contours, hierarchy = cv2.findContours(cv2.bitwise_not(binary), cv2.RETR_TREE, cv2.CHAIN_APPROX_SIMPLE)
    if hierarchy is not None:
        hierarchy = hierarchy[0]
        for index, contour in enumerate(contours):
            parent = hierarchy[index][3]
            if parent >= 0 and hierarchy[parent][3] < 0:
                points = contour.reshape(-1, 2)
                point = points[points[:, 1].argmin()]
                cv2.floodFill(binary, mask, (int(point[0]), int(point[1])+1), 255,
                              loDiff=0, upDiff=0, flags=4+(255<<8)+cv2.FLOODFILL_MASK_ONLY)
    return cv2.bitwise_not(mask)


def matches(roi, template, threshold):
    if roi.shape[0] < template.shape[0] or roi.shape[1] < template.shape[1]:
        return []
    response = cv2.matchTemplate(roi, template, cv2.TM_CCOEFF_NORMED)
    found = []
    while True:
        _, similarity, _, (x, y) = cv2.minMaxLoc(response)
        if similarity < threshold:
            break
        found.append((similarity, x, y, template.shape[1], template.shape[0]))
        cv2.floodFill(response, None, (x, y), 0, loDiff=.3, upDiff=.3, flags=4+cv2.FLOODFILL_FIXED_RANGE)
    return found


class Reader:
    def __init__(self, api):
        self.api = api
        self.layout = constants(SCRIPTS/'locations/SupportNpLayout.kt')
        source = (SCRIPTS/'supportSelection/SupportNpLevelReader.kt').read_text()
        names = ['PREFIX_SIMILARITY', 'MIN_OCR_CONFIDENCE', 'AMBIGUITY_SCORE_GAP',
                 'SAME_PREFIX_DISTANCE', 'OCR_SCALE', 'OCR_THRESHOLD', 'OCR_PADDING',
                 'RECOVERY_PREFIX_SIMILARITY', 'MIN_RECOVERY_OCR_CONFIDENCE', 'MIN_RECOVERY_AGREEMENT',
                 'RECOVERY_BRIGHT_THRESHOLD', 'RECOVERY_DARK_THRESHOLD', 'RECOVERY_OCR_SCALE']
        self.settings = {name: value(source, name) for name in names}
        self.scales = [float(n) for n in re.search(r'RECOVERY_PREFIX_SCALES = listOf\(([^)]+)\)', source)[1].split(',')]
        self.whitelist = re.search(r'allowedCharacters = "([^"]+)"', source)[1]
        s = self.settings
        self.profiles = [(int(s['OCR_SCALE']), s['OCR_THRESHOLD']),
                         (int(s['OCR_SCALE']), s['RECOVERY_BRIGHT_THRESHOLD']),
                         (int(s['OCR_SCALE']), s['RECOVERY_DARK_THRESHOLD']),
                         (int(s['RECOVERY_OCR_SCALE']), s['OCR_THRESHOLD'])]
        self.templates = {}
        self.recovery_templates = {}
        self.asset_hashes = {}
        for server in ['Jp', 'Kr']:
            self.templates[server] = []
            self.recovery_templates[server] = []
            for name in ['support_np_lv.png', 'support_np_lv_gold.png']:
                path = ROOT/'app/src/main/assets'/server/name
                if not path.exists():
                    path = ROOT/'app/src/main/assets/En'/name
                template = cv2.imread(str(path), 0)
                assert template is not None, path
                self.asset_hashes[str(path.relative_to(ROOT))] = hashlib.sha256(path.read_bytes()).hexdigest()
                self.templates[server].append(template)
                for scale in self.scales:
                    size = tuple(rounded(n*scale) for n in template.shape[::-1])
                    # Pattern.resize and Kotlin Size.times use INTER_AREA and roundToInt.
                    resized = template if scale == 1 else cv2.resize(template, size, interpolation=cv2.INTER_AREA)
                    self.recovery_templates[server].append((scale, resized))

    def parse(self, result):
        match = re.fullmatch(r'Lv\.?\s*([1-5])', result['text'].strip(), re.I)
        return int(match[1]) if match and result['confidence'] >= self.settings['MIN_OCR_CONFIDENCE'] else None

    def ambiguous(self, found, best):
        score, x, y = best[:3]
        return any(m[0] >= score-self.settings['AMBIGUITY_SCORE_GAP'] and
                   (abs(m[1]-x)*2 > self.settings['SAME_PREFIX_DISTANCE'] or
                    abs(m[2]-y)*2 > self.settings['SAME_PREFIX_DISTANCE']) for m in found)

    def read_suffix(self, roi, candidate, profiles):
        _, x, y, w, h = candidate[:5]
        margin = int(self.layout['SUFFIX_MARGIN']/2)
        digit = int(self.layout['DIGIT_WIDTH']/2)
        if x-margin < 0 or y-margin < 0 or x+w+digit > roi.shape[1] or y+h+margin > roi.shape[0]:
            return None
        suffix = roi[y-margin:y+h+margin, x-margin:x+w+digit]
        results = []
        for scale, threshold in profiles:
            up = cv2.resize(suffix, None, fx=scale, fy=scale, interpolation=cv2.INTER_CUBIC)
            binary = cv2.threshold(up, threshold*255, 255, cv2.THRESH_BINARY)[1]
            filled = fill_text(binary.copy())
            padding = int(self.settings['OCR_PADDING'])
            prepared = cv2.copyMakeBorder(filled, padding, padding, padding, padding, cv2.BORDER_CONSTANT, value=255)
            self.api.SetVariable('tessedit_char_whitelist', self.whitelist)
            self.api.SetImage(Image.fromarray(prepared).convert('RGB'))
            text, confidence = self.api.GetUTF8Text().strip(), self.api.MeanTextConf()
            self.api.Clear()
            result = {'scale': scale, 'threshold': threshold, 'text': text, 'confidence': confidence}
            result['parsed'] = self.parse(result)
            results.append(result)
        return results

    def read(self, roi, server, recovery=True):
        found = [m for template in self.templates[server]
                 for m in matches(roi, template, self.settings['PREFIX_SIMILARITY'])]
        if found:
            best = max(found, key=lambda m: m[0])
            if self.ambiguous(found, best):
                return {'level': None, 'reason': 'ambiguous_prefix'}
            readings = self.read_suffix(roi, best, self.profiles[:1])
            if readings is None:
                return {'level': None, 'reason': 'clipped_suffix'}
            if readings[0]['parsed'] is not None:
                return {'level': readings[0]['parsed'], 'reason': 'fast', 'candidate': best, 'readings': readings}
        if not recovery:
            return {'level': None, 'reason': 'fast_failed'}
        found = [(*m, scale) for scale, template in self.recovery_templates[server]
                 for m in matches(roi, template, self.settings['RECOVERY_PREFIX_SIMILARITY'])]
        if not found:
            return {'level': None, 'reason': 'recovery_no_prefix'}
        best = max(found, key=lambda m: m[0])
        if self.ambiguous(found, best):
            return {'level': None, 'reason': 'recovery_ambiguous_prefix'}
        readings = self.read_suffix(roi, best, self.profiles)
        if readings is None:
            return {'level': None, 'reason': 'clipped_suffix'}
        confident_levels = {r['parsed'] for r in readings if r['parsed'] is not None}
        strong = [r for r in readings if r['parsed'] is not None and
                  r['confidence'] >= self.settings['MIN_RECOVERY_OCR_CONFIDENCE']]
        level = strong[0]['parsed'] if len(strong) >= self.settings['MIN_RECOVERY_AGREEMENT'] and len(confident_levels) == 1 else None
        return {'level': level, 'reason': 'recovery_consensus' if level else 'recovery_no_consensus',
                'candidate': best, 'readings': readings}


def cases(input_dir, initial_only):
    files = {unicodedata.normalize('NFC', p.name): p for p in input_dir.glob('*.png')}
    for name, server, rows in CASES:
        rows = [(y, level, (name, y) in [('143344', 110), ('134808', 58)]) for y, level in rows]
        yield name, server, rows, files[f'Screenshot_20261013-{name}.png']
    if not initial_only:
        for name, server, rows in EXTRA_CASES:
            yield name, server, rows, files[f'스크린샷 2026-10-13 오후 {name}.png']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input-dir', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--initial-only', action='store_true', help='Only the five original screenshots')
    args = parser.parse_args()
    assert cv2.__version__ == '4.11.0', cv2.__version__
    assert tesserocr.tesseract_version().startswith('tesseract 5.5.1'), tesserocr.tesseract_version()
    report = {'opencv': cv2.__version__, 'tesseract': tesserocr.tesseract_version(),
              'source_inputs': {}, 'checks': [], 'negative_checks': [], 'nonvisible_checks': [],
              'limitations': ['Desktop binding, not Android execution.',
                              '11 partial top rows use annotated origins in the full dataset.',
                              'Original prefix assets came from two original screenshots.',
                              'Recovery settings were chosen after examining failures in this dataset; it is not independent validation.']}
    with tesserocr.PyTessBaseAPI(path=str(ROOT/'app/src/main/assets/tessdata'), lang='eng', psm=7) as api:
        reader = Reader(api)
        report.update(settings=reader.settings, layout=reader.layout, recovery_scales=reader.scales,
                      recovery_profiles=reader.profiles, asset_hashes=reader.asset_hashes)
        for name, server, rows, path in cases(args.input_dir, args.initial_only):
            raw = cv2.imread(str(path), 0)
            assert raw is not None, path
            report['source_inputs'][name] = {'file': unicodedata.normalize('NFC', path.name), 'server': server,
                                           'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
            for method in ['crop_then_resize', 'resize_then_crop']:
                image = normalized(raw, method)
                tool = cv2.imread(str(ROOT/'app/src/main/assets'/server/'support_region_tool.png'), 0)
                origins = sorted(y-35 for _, _, y, _, _ in matches(image[:, 1050:1235], tool, .75))
                used = []
                for annotated_y, expected, partial in rows:
                    row_y = annotated_y if partial else min(origins, key=lambda y: abs(y-annotated_y))
                    assert abs(row_y-annotated_y) <= 2
                    if not partial:
                        used.append(row_y)
                    x = int((76+reader.layout['NAME_X'])/2)
                    y = int(row_y+reader.layout['NAME_Y']/2)
                    w, h = int(reader.layout['NAME_WIDTH']/2), int(reader.layout['NAME_HEIGHT']/2)
                    roi = image[y:y+h, x:x+w]
                    baseline, detected = reader.read(roi, server, recovery=False), reader.read(roi, server)
                    filter_checks = {n: (detected['level'] is not None and detected['level'] >= n) == (expected >= n)
                                     for n in range(1, 6)}
                    report['checks'].append({'file': name, 'server': server, 'normalization': method, 'row_y': row_y,
                                             'manual_partial': partial, 'expected': expected, 'baseline': baseline,
                                             'detected': detected, 'correct': detected['level'] == expected,
                                             'minimum_level_checks': filter_checks})
                    if 'candidate' in detected:
                        prefix_x, prefix_w = detected['candidate'][1], detected['candidate'][3]
                        for kind, end in [('np_name_without_Lv', prefix_x-3), ('Lv_without_digit', prefix_x+prefix_w)]:
                            negative = reader.read(roi[:, :max(0, end)], server)
                            report['negative_checks'].append({'file': name, 'row_y': row_y, 'normalization': method,
                                                             'kind': kind, 'detected': negative,
                                                             'correct': negative['level'] is None})
                for row_y in origins:
                    if row_y not in used:
                        roi = image[row_y+130:row_y+182, 362:810]
                        report['nonvisible_checks'].append({'file': name, 'normalization': method, 'row_y': row_y,
                                                           'detected': reader.read(roi, server)})
    checks, negative = report['checks'], report['negative_checks']
    report['summary'] = {
        'files': len(report['source_inputs']), 'visible_labels': len(checks)//2,
        'level_checks': len(checks), 'level_correct': sum(c['correct'] for c in checks),
        'baseline_correct': sum(c['baseline']['level'] == c['expected'] for c in checks),
        'automatic_row_checks': sum(not c['manual_partial'] for c in checks),
        'minimum_level_checks': len(checks)*5,
        'minimum_level_correct': sum(sum(c['minimum_level_checks'].values()) for c in checks),
        'negative_checks': len(negative), 'negative_correct': sum(c['correct'] for c in negative),
        'nonvisible_checks': len(report['nonvisible_checks']),
        'nonvisible_unknown': sum(c['detected']['level'] is None for c in report['nonvisible_checks']),
        'passed': all(c['correct'] and all(c['minimum_level_checks'].values()) for c in checks) and
                  all(c['correct'] for c in negative) and all(c['detected']['level'] is None for c in report['nonvisible_checks'])}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    assert report['summary']['passed'], report['summary']
    print('PASS:', json.dumps(report['summary']))


if __name__ == '__main__':
    main()
