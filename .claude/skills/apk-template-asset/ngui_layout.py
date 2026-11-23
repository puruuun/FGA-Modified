"""One server's extracted NGUI data (extract_apk_ui.py output) and the size rule: where a widget is drawn and how large.

The size rule was learned on KR 8.1.0 and frozen before JP was scored (90 KR and 106 JP elements measured on the
screenshots of docs/reference-images; selfcheck.py replays both). Decision list, first match wins:

  D  driven  - a UIScrollBar / UIProgressBar / UISlider in the same file references the widget: the bar sets its
               length at run time. The cross axis follows W; the long axis is not predicted ("needs a screen").
  N  native  - Simple or Filled sprite whose slot (last three path names) holds two or more sprite names in the APK,
               whose widget does not fit the sprite (+padding) within 2 units, and whose widget aspect ratio differs
               from the sprite's by more than 25 %: code swaps the sprite and calls MakePixelPerfect, so the stored
               widget size belongs to another sprite. Drawn = sprite px x atlas pixelSize x |world scale| x ppu.
  W  widget  - everything else: NGUI UISprite.drawingDimensions of the widget rect. Simple/Filled shrink the widget by
               the sprite's padding in proportion (+1 on the right/top when the padded sprite is odd), Sliced subtract
               padding x pixelSize. Drawn = that x |world scale| x ppu.
  world scale = product of localScale from the widget up to (not including) UI Root; ppu = 720 / UIRoot.manualHeight
  (576 on FGO, so 1.25 px per unit at 720p).

N and W cannot always be told apart from data (a sprite set from code in a slot that only ever names one sprite);
`candidates()` returns both whenever they differ, and one screenshot of either server decides (decide.py).

Position: centre = pivot point (sum of localPosition x parent scale) moved by (0.5 - pivot) x drawn size, relative to
the screen centre. Edge anchors (UIAnchor, UIRect anchors) that move an element on wide screens are reported, not
applied.
"""
import collections
import json
import os
import re

import numpy as np
from PIL import Image

PIVOT = {0: (0, 1), 1: (.5, 1), 2: (1, 1), 3: (0, .5), 4: (.5, .5), 5: (1, .5), 6: (0, 0), 7: (.5, 0), 8: (1, 0)}
TYPES = {0: 'Simple', 1: 'Sliced', 2: 'Tiled', 3: 'Filled', 4: 'Advanced'}
ODD_FIX = True
ASPECT_OFF = 0.25
FIT_UNITS = 2
# Prefab widgets (no UI Root above them) are assumed to sit under FGO's 576-unit root.
DEFAULT_PPU = 720 / 576
LOC_ENTRY = re.compile(r'"((?:[^"\\\n]|\\.)*)"\s*:\s*"((?:[^"\\\n]|\\.)*)"')
BAR_CLASSES = ('UIProgressBar', 'UIScrollBar', 'UISlider')
EDGE_ANCHOR_CLASSES = ('UIAnchor', 'FSAnchor', 'UIStretch')


def safe_name(name):
    return re.sub(r'[^\w.\-]+', '_', name or 'unnamed')


class Data:
    """layout.json, index.json, sprites/, textures/, fonts/, text/ and uifonts.json of one extracted APK."""

    def __init__(self, folder):
        self.folder = folder
        with open(os.path.join(folder, 'layout.json'), encoding='utf-8') as f:
            d = json.load(f)
        self.widgets = d['widgets']
        self.atlases = {(a['file'], a['pid']): a for a in d['atlases'] if 'sprites' in a}
        self.atlas_by_name = {}
        for a in self.atlases.values():
            self.atlas_by_name.setdefault(a['name'], a)
        self.roots = {(r['chain'][0][1], r['chain'][0][2]): r for r in d['roots'] if r.get('chain')}
        self.comps = collections.defaultdict(list)
        for c in d['comps']:
            self.comps[tuple(c['tkey'])].append(c)
        with open(os.path.join(folder, 'index.json'), encoding='utf-8') as f:
            self.idx = json.load(f)
        p = os.path.join(folder, 'uifonts.json')
        self.uifonts = {}
        if os.path.exists(p):
            with open(p, encoding='utf-8') as f:
                self.uifonts = {u['name']: u for u in json.load(f)}
        self._tex, self._spr, self._loc = {}, {}, None
        self.by_key = {(w['file'], int(w['pid'])): w for w in self.widgets}

    # ---- text
    def font_path(self):
        for n in os.listdir(os.path.join(self.folder, 'fonts')):
            if n.startswith('FGO-Main-Font'):
                return os.path.join(self.folder, 'fonts', n)
        raise SystemExit('no FGO-Main-Font in %s/fonts' % self.folder)

    def localization(self):
        """Key -> text of text/LocalizationJpn.txt (the table the game ships; on KR it holds the Korean texts).
        Keys are message ids (RECOMMENDED_MATERIAL_BUTTON_LABEL) or label scene paths."""
        if self._loc is None:
            self._loc = {}
            folder = os.path.join(self.folder, 'text')
            for n in sorted(os.listdir(folder)):
                with open(os.path.join(folder, n), encoding='utf-8-sig', errors='replace') as f:
                    raw = f.read()
                # JSON-like, but the JP table has comments and trailing commas: read "key" : "value" pairs
                for m in LOC_ENTRY.finditer(raw):
                    try:
                        self._loc[json.loads('"%s"' % m.group(1))] = json.loads('"%s"' % m.group(2))
                    except ValueError:
                        continue
        return self._loc

    def label_text(self, w):
        """(text, source) the game shows on a label: the localization entry keyed by the label's path, else the text
        stored in the scene."""
        loc = self.localization()
        if w['path'] in loc:
            return loc[w['path']], 'localization[path]'
        return (w.get('label') or {}).get('text', ''), 'scene text'

    # ---- sprites
    def sprite_data(self, w):
        a = self.atlases.get(tuple(w['atlas_ref'])) if w.get('atlas_ref') else None
        if a is None:
            return None, None
        for s in a['sprites']:
            if s['name'] == w.get('sprite'):
                return a, s
        return a, None

    def find_sprite(self, atlas_name, sprite_name):
        cands = [a for a in self.atlases.values() if not atlas_name or a['name'] == atlas_name]
        for a in cands:
            for s in a['sprites']:
                if s['name'] == sprite_name:
                    return a, s
        return None, None

    def texture(self, atlas):
        name = atlas['name']
        if name in self._tex:
            return self._tex[name]
        tex = None
        for key, sprites in self.idx['atlases'].items():
            if key != name and not key.startswith(name + '_'):
                continue
            r = {x['name']: (x['x'], x['y'], x['w'], x['h']) for x in sprites}
            if all(r.get(s['name']) == (s['x'], s['y'], s['w'], s['h']) for s in atlas['sprites'] if s['name'] in r) \
                    and len(r) >= 0.9 * len(atlas['sprites']):
                tex = ('folder', os.path.join(self.folder, 'sprites', safe_name(key)))
                break
        if tex is None:
            for cand in (name, name + 'Texture'):
                p = os.path.join(self.folder, 'textures', cand + '.png')
                if os.path.exists(p):
                    im = Image.open(p)
                    if all(s['x'] + s['w'] <= im.width and s['y'] + s['h'] <= im.height for s in atlas['sprites']):
                        tex = ('sheet', np.array(im.convert('RGBA')))
                        break
        self._tex[name] = tex
        return tex

    def sprite_rgba(self, atlas, s):
        """The sprite's own pixels (RGBA uint8), or None when its atlas texture is not in the APK."""
        key = (atlas['name'], s['name'])
        if key in self._spr:
            return self._spr[key]
        tex = self.texture(atlas)
        img = None
        if tex is not None and tex[0] == 'folder':
            p = os.path.join(tex[1], safe_name(s['name']) + '.png')
            if os.path.exists(p):
                img = np.array(Image.open(p).convert('RGBA'))
        elif tex is not None:
            img = tex[1][s['y']:s['y'] + s['h'], s['x']:s['x'] + s['w']].copy()
        if img is not None and img.shape[:2] != (s['h'], s['w']):
            img = None
        self._spr[key] = img
        return img

    def texture_rgba(self, name):
        p = os.path.join(self.folder, 'textures', safe_name(name) + '.png')
        return np.array(Image.open(p).convert('RGBA')) if os.path.exists(p) else None

    # ---- transforms
    def root_of(self, w):
        """(ppu, root record or None, index of 'UI Root' in the chain or None)."""
        for i, n in enumerate(w['chain']):
            if n[0] == 'UI Root':
                r = self.roots.get((n[1], n[2]))
                mh = r['words'][2] if r else 576
                return 720.0 / mh, r, i
        return None, None, None

    def components_on_chain(self, w):
        """[(depth from the widget, class, path)] of layout components on the widget's Transform and its parents."""
        out = []
        for i, n in enumerate(w['chain']):
            for c in self.comps.get((n[1], n[2]), []):
                out.append((i, c['class'], c['path']))
        return out


def world(w, upto=None):
    """Widget pivot position (x, y) and total scale (sx, sy) in units of the node at index `upto` of the chain
    (None: the topmost node), and whether a rotation is on the way."""
    ch = w['chain']
    top = len(ch) if upto is None else upto
    x = y = 0.0
    sx = sy = 1.0
    rot = False
    for n in reversed(ch[:top]):
        p, s, r = n[3], n[4], n[5]
        x += p[0] * sx
        y += p[1] * sy
        sx *= s[0]
        sy *= s[1]
        if abs(r[3]) < 0.999:
            rot = True
    return (x, y), (sx, sy), rot


def tail(w):
    return '/'.join(w['path'].split('/')[-3:])


class Rule:
    """The frozen size rule (see the module docstring)."""

    def __init__(self, data):
        self.d = data
        self.driven = set()
        widget_pids = {(w['file'], w['pid']) for w in data.widgets}
        for cs in data.comps.values():
            for c in cs:
                if c['class'] in BAR_CLASSES:
                    words = c['words']
                    for i in range(len(words) - 1):
                        if words[i] > 100 and words[i + 1] == 0 and (c['tkey'][0], words[i]) in widget_pids:
                            self.driven.add((c['tkey'][0], words[i]))
        self.slots = collections.defaultdict(set)
        for w in data.widgets:
            if w['class'] == 'UISprite' and w.get('sprite'):
                self.slots[tail(w)].add(w['sprite'])

    def branch(self, w, s):
        pl, pr, pt, pb = s['padding']
        sw, sh = s['w'], s['h']
        fits = abs(w['w'] - (sw + pl + pr)) <= FIT_UNITS and abs(w['h'] - (sh + pt + pb)) <= FIT_UNITS
        if (w['file'], w['pid']) in self.driven:
            return 'D', fits
        if w.get('type', 0) in (0, 3) and len(self.slots[tail(w)]) >= 2 and not fits and \
                abs((w['w'] / w['h']) / ((sw + pl + pr) / (sh + pt + pb)) - 1) > ASPECT_OFF:
            return 'N', fits
        return 'W', fits

    def predict(self, w, sprite=None, mode=None, parent_scale=(1.0, 1.0)):
        """dict(W, H (720p px), cx, cy (720p px from the screen centre, or from the prefab's top node when the
        widget has no UI Root: rooted False), branch, rooted,
        long_axis_free, ox, oy). sprite: (atlas, sprite data) to use instead of the widget's own (another server's
        art). mode: force 'N' or 'W' instead of the rule's choice (a D widget keeps D)."""
        a, s = sprite if sprite else self.d.sprite_data(w)
        ppu, _, ri = self.d.root_of(w)
        rooted = ri is not None
        if not rooted:
            ppu = DEFAULT_PPU
            (x, y), (sx, sy), _ = world(w)
            sx, sy = sx * parent_scale[0], sy * parent_scale[1]
        else:
            (x, y), (sx, sy), _ = world(w, ri)
        sx, sy = abs(sx), abs(sy)
        ps = (a.get('pixelSize') or 1.0) if a else 1.0
        sw, sh = s['w'], s['h']
        pl, pr, pt, pb = s['padding']
        typ = w.get('type', 0)
        branch, _ = self.branch(w, s)
        if mode in ('N', 'W') and branch != 'D':
            branch = mode
        if branch == 'N':
            ww, wh = sw * ps, sh * ps
            ox = oy = 0.0
            wu, hu = (sw + pl + pr) * ps, (sh + pt + pb) * ps
        else:
            if typ in (0, 3):
                wt, ht = sw + pl + pr, sh + pt + pb
                pr2 = pr + (1 if (wt & 1 and ODD_FIX) else 0)
                pt2 = pt + (1 if (ht & 1 and ODD_FIX) else 0)
                ww = w['w'] * (wt - pl - pr2) / wt
                wh = w['h'] * (ht - pt2 - pb) / ht
                ox = w['w'] * (pl - pr2) / wt / 2
                oy = w['h'] * (pb - pt2) / ht / 2
            else:
                ww = w['w'] - round(ps * pl) - round(ps * pr)
                wh = w['h'] - round(ps * pt) - round(ps * pb)
                ox = (round(ps * pl) - round(ps * pr)) / 2
                oy = (round(ps * pb) - round(ps * pt)) / 2
            wu, hu = w['w'], w['h']
        px, py = PIVOT.get(w['pivot'], (.5, .5))
        cxu = x + ((0.5 - px) * wu + ox) * sx
        cyu = y + ((0.5 - py) * hu + oy) * sy
        return {'W': ww * sx * ppu, 'H': wh * sy * ppu, 'branch': branch, 'rooted': rooted,
                'cx': cxu * ppu, 'cy': -cyu * ppu,
                'long_axis_free': ('h' if w['h'] >= w['w'] else 'w') if branch == 'D' else '',
                'scale': (sx * ppu, sy * ppu), 'pixelSize': ps}

    def candidates(self, w, sprite=None):
        """[(mode, prediction)]: the rule's choice first, then the other of N/W when its size differs by more than
        2 px or 3 % (the size-right tolerance of the measurements)."""
        p = self.predict(w, sprite)
        out = [(p['branch'], p)]
        if p['branch'] in ('N', 'W'):
            other = 'W' if p['branch'] == 'N' else 'N'
            q = self.predict(w, sprite, mode=other)
            if not same_size(p, q):
                out.append((other, q))
        return out


def same_size(p, q, px=2.0, rel=0.03):
    return all(abs(p[k] - q[k]) <= max(px, rel * max(p[k], q[k])) for k in ('W', 'H'))


def label_box(data, w):
    """(cx, cy, W, H, rooted) of a label or plain widget rect in 720p px from the screen centre (or from the prefab's
    top node without a UI Root)."""
    ppu, _, ri = data.root_of(w)
    if ri is None:
        ppu = DEFAULT_PPU
    (x, y), (sx, sy), _ = world(w, ri if ri is not None else len(w['chain']) - 1)
    px, py = PIVOT.get(w['pivot'], (.5, .5))
    cx = (x + (0.5 - px) * w['w'] * sx) * ppu
    cy = -(y + (0.5 - py) * w['h'] * sy) * ppu
    return cx, cy, abs(w['w'] * sx) * ppu, abs(w['h'] * sy) * ppu, ri is not None


def region_1440(cx, cy, W, H, margin=0):
    """FGA Region of a box centred (cx, cy) relative to the 720p screen centre: x from the centre (`xFromCenter()`),
    y from the top, 1440p script units (2 x 720p). Returns (x, y, w, h)."""
    return (int(round(2 * (cx - W / 2 - margin))), int(round(2 * (360 + cy - H / 2 - margin))),
            int(round(2 * (W + 2 * margin))), int(round(2 * (H + 2 * margin))))
