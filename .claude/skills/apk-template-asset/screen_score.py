#!/usr/bin/env python3
"""Score templates on real screenshots the way FGA matches, with both margins against the code's threshold.

FGA's pipeline: game area (extract-template-asset's crop_template.game_area), INTER_AREA to 720p height, grayscale,
TM_CCOEFF_NORMED, best score. For one template:

- true score: on each positive screenshot, the best score inside a window of +-12 px (plus the size difference to the
  reference) around where the element is (a reference template's best match, or a given box); the minimum over the
  positives is what the threshold has to clear.
- false score: the best score on the screenshots where the element is absent, over the whole screen (an upper bound:
  FGA searches only a Region) or inside a Region when one is given.
- margins: true - threshold, and threshold - false. Both must be positive; template-vs-template similarity does not
  count, only these screen margins do.

Positives and negatives come from a reference template (an existing one: positive where it scores at least the
threshold, negative where it scores below --negative, default 0.6, the rest is "unclear" and left out) or from a
regex on screenshot names (--positive).

    screen_score.py --screens docs/reference-images/Kr --reference app/src/main/assets/Kr/close.png \\
        --template build/drafts/close.png --threshold code --out-csv build/close.csv

`--threshold code` reads the threshold from scripts/ (threshold_from_code); a number overrides it.
"""
import argparse
import csv
import glob
import hashlib
import os
import re
import sys

import cv2
import numpy as np

sys.dont_write_bytecode = True
# Korean and Japanese text; a Korean Windows console defaults to cp949, which cannot print Japanese
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, 'reconfigure'):
        _stream.reconfigure(encoding='utf-8')
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
WINDOW = 12
NEGATIVE = 0.6
DEFAULT_THRESHOLD = 0.8  # IPlatformPrefs.minSimilarity default
IMAGE_EXT = ('.png', '.webp', '.jpg', '.jpeg')


def crop_template_module():
    sys.path.insert(0, os.path.join(HERE, '..', 'extract-template-asset'))
    import crop_template  # noqa: E402
    return crop_template


def read_gray(path):
    return cv2.imdecode(np.fromfile(path, np.uint8), cv2.IMREAD_GRAYSCALE)


def game_area_gray(path, ct):
    """The screenshot's game area at 720p, grayscale, as FGA sees it; None for a crop (under 600 px tall)."""
    image = cv2.imdecode(np.fromfile(path, np.uint8), cv2.IMREAD_COLOR)
    if image is None or image.shape[0] < 600:
        return None
    ax, ay, aw, ah = ct.game_area(image.shape[1], image.shape[0])
    area = image[ay:ay + ah, ax:ax + aw]
    if ah != ct.IMAGE_H:
        area = cv2.resize(area, (round(aw * ct.IMAGE_H / ah), ct.IMAGE_H), interpolation=cv2.INTER_AREA)
    return cv2.cvtColor(area, cv2.COLOR_BGR2GRAY)


_SCREENS = {}


def load_screens(folder, cache_dir=None):
    """[(name relative to folder with /, 720p gray game area)] of every full screenshot under folder (cached in
    cache_dir as .npz when given)."""
    folder = os.path.abspath(folder)
    if folder in _SCREENS:
        return _SCREENS[folder]
    paths = sorted(p for p in glob.glob(os.path.join(folder, '**', '*'), recursive=True)
                   if p.lower().endswith(IMAGE_EXT))
    key = hashlib.sha1('|'.join('%s:%d' % (p, os.path.getsize(p)) for p in paths).encode()).hexdigest()[:16]
    cache = os.path.join(cache_dir, 'screens-%s.npz' % key) if cache_dir else None
    out = []
    if cache and os.path.exists(cache):
        z = np.load(cache, allow_pickle=False)
        names = [str(n) for n in z['names']]
        out = [(n, z['s%d' % i]) for i, n in enumerate(names)]
    else:
        ct = crop_template_module()
        for p in paths:
            g = game_area_gray(p, ct)
            if g is not None:
                out.append((os.path.relpath(p, folder).replace(os.sep, '/'), g))
        if cache:
            os.makedirs(cache_dir, exist_ok=True)
            np.savez_compressed(cache, names=np.array([n for n, _ in out]), **{'s%d' % i: g for i, (_, g) in enumerate(out)})
    _SCREENS[folder] = out
    return out


def best_in(screen, t, box=None):
    """(score, x, y) of the template's best match inside box (x, y, w, h) of the screen, or None if it does not fit."""
    if box is None:
        box = (0, 0, screen.shape[1], screen.shape[0])
    x, y, w, h = [int(round(v)) for v in box]
    x0, y0 = max(0, x), max(0, y)
    x1, y1 = min(screen.shape[1], x + w), min(screen.shape[0], y + h)
    if t is None or x1 - x0 < t.shape[1] or y1 - y0 < t.shape[0]:
        return None
    r = cv2.matchTemplate(screen[y0:y1, x0:x1], t, cv2.TM_CCOEFF_NORMED)
    _, v, _, (bx, by) = cv2.minMaxLoc(r)
    return float(v), x0 + bx, y0 + by


def window_around(cx, cy, t, extra=0):
    m = WINDOW + extra
    return (cx - t.shape[1] / 2 - m, cy - t.shape[0] / 2 - m, t.shape[1] + 2 * m, t.shape[0] + 2 * m)


# ---- the threshold of the code that uses an image
def threshold_from_code(image_name, repo=REPO):
    """(threshold used for margins, [(file:line, value, text)]) for an image name such as 'close.png'.

    Each call that uses the Images entry is read for a `similarity = X` (a literal or a named constant); a call
    without one uses minSimilarity (0.8 by default). The highest threshold of the calls is returned: every call
    must accept the element. Read the listed lines: some calls take a tuned or a user setting."""
    scripts = os.path.join(repo, 'scripts', 'src', 'main')
    kt = [p for p in glob.glob(os.path.join(scripts, '**', '*.kt'), recursive=True)]
    images = next((p for p in kt if p.endswith(os.sep + 'Images.kt')), None)
    if images is None:
        return DEFAULT_THRESHOLD, []
    with open(images, encoding='utf-8') as f:
        entries = re.findall(r'^\s*(\w+)\("%s"' % re.escape(image_name), f.read(), re.M)
    if not entries:
        return DEFAULT_THRESHOLD, []
    consts = {}
    texts = {}
    for p in kt:
        with open(p, encoding='utf-8') as f:
            s = f.read()
        texts[p] = s.splitlines()
        for m in re.finditer(r'\b(?:const\s+)?val\s+(\w+)\s*(?::\s*Double)?\s*=\s*(0?\.\d+|1\.0)\b', s):
            consts.setdefault(m.group(1), float(m.group(2)))
    found = []
    for p, lines in texts.items():
        for i, line in enumerate(lines):
            if not any(re.search(r'\bImages\.%s\b' % e, line) for e in entries) or p == images:
                continue
            ctx = ' '.join(lines[i:i + 3])
            m = re.search(r'similarity\s*=\s*([\w.]+)', ctx, re.I)
            value = None
            if m:
                tok = m.group(1)
                value = float(tok) if re.fullmatch(r'0?\.\d+|1\.0', tok) else consts.get(tok.split('.')[-1])
            found.append(('%s:%d' % (os.path.relpath(p, repo).replace(os.sep, '/'), i + 1),
                          value if value is not None else (None if m else DEFAULT_THRESHOLD), line.strip()[:140]))
    values = [v for _, v, _ in found if v is not None]
    return (max(values) if values else DEFAULT_THRESHOLD), found


# ---- scoring
def classify(screens, reference, threshold, positive_rx=None, negative=NEGATIVE):
    """{name: ('positive', (cx, cy)) | ('negative', None) | ('unclear', None)} and the reference's best per screen."""
    roles, ref_best = {}, {}
    for name, g in screens:
        b = best_in(g, reference) if reference is not None else None
        ref_best[name] = b
        if positive_rx is not None:
            if re.search(positive_rx, name):
                roles[name] = ('positive', (b[1] + reference.shape[1] / 2, b[2] + reference.shape[0] / 2) if b else None)
            else:
                roles[name] = ('negative', None)
            continue
        if b is None:
            roles[name] = ('unclear', None)
        elif b[0] >= threshold:
            roles[name] = ('positive', (b[1] + reference.shape[1] / 2, b[2] + reference.shape[0] / 2))
        elif b[0] < negative:
            roles[name] = ('negative', None)
        else:
            roles[name] = ('unclear', None)
    return roles, ref_best


def score_template(t, screens, roles, threshold, region=None, ref_shape=None):
    """Summary dict and per-screen rows for template t (gray) on classified screens."""
    rows, trues, fps = [], [], []
    extra = 0
    if ref_shape is not None:
        extra = max(0, (t.shape[1] - ref_shape[1]) // 2, (t.shape[0] - ref_shape[0]) // 2) + 1
    for name, g in screens:
        role, loc = roles[name]
        if role == 'positive' and loc is not None:
            b = best_in(g, t, window_around(loc[0], loc[1], t, extra))
            v = b[0] if b else None
            if v is not None:
                trues.append((v, name))
            rows.append((name, role, v))
        elif role == 'negative':
            b = best_in(g, t, region)
            v = b[0] if b else None
            if v is not None:
                fps.append((v, name))
            rows.append((name, role, v))
        else:
            rows.append((name, role, None))
    tmin = min(trues) if trues else (None, '')
    fmax = max(fps) if fps else (None, '')
    return {'positives': len(trues), 'negatives': len(fps),
            'unclear': sum(1 for r in roles.values() if r[0] == 'unclear'),
            'true_min': tmin[0], 'true_min_screen': tmin[1], 'fp_max': fmax[0], 'fp_max_screen': fmax[1],
            'threshold': threshold,
            'margin_true': None if tmin[0] is None else tmin[0] - threshold,
            'margin_false': None if fmax[0] is None else threshold - fmax[0]}, rows


def fmt(v, d=3):
    return '' if v is None else ('%.*f' % (d, v))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--screens', required=True, help='folder of screenshots (searched recursively)')
    ap.add_argument('--template', action='append', required=True, help='template(s) to score; repeat')
    ap.add_argument('--reference', help='existing template that decides positives, negatives and where the element is')
    ap.add_argument('--positive', help='regex on screenshot names: positives (the rest are negatives)')
    ap.add_argument('--threshold', default='code', help="a number, or 'code' to read it from scripts/ (default)")
    ap.add_argument('--image-name', help='image name for --threshold code (default: the reference file name)')
    ap.add_argument('--region', help='x,y,w,h in 720p game-area pixels for false positives (default: whole screen)')
    ap.add_argument('--cache', help='folder for a screenshot cache')
    ap.add_argument('--out-csv')
    a = ap.parse_args()
    if not a.reference and not a.positive:
        sys.exit('give --reference or --positive')
    name = a.image_name or os.path.basename(a.reference or a.template[0])
    if a.threshold == 'code':
        thr, calls = threshold_from_code(name)
        print('threshold %.2f from %d call(s) of %s:' % (thr, len(calls), name))
        for loc, v, text in calls:
            print('  %-70s %-5s %s' % (loc, '?' if v is None else '%.2f' % v, text))
    else:
        thr = float(a.threshold)
    screens = load_screens(a.screens, a.cache)
    ref = read_gray(a.reference) if a.reference else None
    roles, _ = classify(screens, ref, thr, a.positive)
    region = tuple(int(v) for v in a.region.split(',')) if a.region else None
    out = []
    for tp in ([a.reference] if a.reference else []) + a.template:
        t = read_gray(tp)
        s, rows = score_template(t, screens, roles, thr, region, ref.shape if ref is not None else None)
        print('%-40s pos %d neg %d unclear %d | true min %s (%s) | best false %s (%s) | thr %.2f | margins %s / %s' % (
            os.path.basename(tp), s['positives'], s['negatives'], s['unclear'], fmt(s['true_min']), s['true_min_screen'],
            fmt(s['fp_max']), s['fp_max_screen'], thr, fmt(s['margin_true']), fmt(s['margin_false'])))
        out += [(tp, n, r, fmt(v)) for n, r, v in rows]
    if a.out_csv:
        with open(a.out_csv, 'w', newline='', encoding='utf-8') as f:
            w = csv.writer(f)
            w.writerow(['template', 'screen', 'role', 'score'])
            w.writerows(out)


if __name__ == '__main__':
    main()
