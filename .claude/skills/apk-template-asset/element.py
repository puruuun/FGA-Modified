"""Widgets as drawn layers, matching against screenshots, measuring a drawn size, and locating widgets on a screenshot.

Shared by draft_element.py and the acceptance replay. Screen positions are 720p game-area pixels: the data gives
centres relative to the screen centre, so x = screen width / 2 + cx and y = 360 + cy (edge-anchored widgets on
screens wider than 16:9 are not corrected).
"""
import cv2
import numpy as np

import ngui_layout as nl
import ngui_render as nr

SWEEP_UNIFORM = np.arange(0.70, 1.461, 0.02)
SWEEP_AXIS = 0.12
MIN_MASK_PX = 60
# a smaller layer matches noise somewhere in any box
LOCATE_MIN_PX = 150
# a nearly flat layer scores high on any flat patch
LOCATE_MIN_STD = 12.0


# ---------------------------------------------------------------- one widget as a layer
_LAYERS = {}


def widget_layer(data, rule, w, mode=None, text=None, sprite=None):
    """Cached _widget_layer."""
    key = (id(data), w['file'], w['pid'], mode, text,
           None if sprite is None else (sprite[0]['name'], sprite[1]['name']))
    if key not in _LAYERS:
        _LAYERS[key] = _widget_layer(data, rule, w, mode, text, sprite)
    return _LAYERS[key]


def _widget_layer(data, rule, w, mode=None, text=None, sprite=None):
    """dict(prgb, alpha, left, top (720p px from the screen centre / prefab top), W, H, kind, info) or
    dict(error=...) when the widget cannot be drawn from the APK."""
    cls = w['class']
    if cls in ('UISprite', 'UISkinSprite'):
        a, s = sprite if sprite else data.sprite_data(w)
        if s is None:
            return {'error': 'sprite %s not in its atlas' % w.get('sprite')}
        rgba = data.sprite_rgba(a, s)
        if rgba is None:
            return {'error': 'atlas %s has no texture in the APK (downloaded or runtime art)' % a['name']}
        p = rule.predict(w, (a, s), mode=mode)
        sx, _ = p['scale']
        prgb, al = nr.sprite_layer(rgba, p['W'], p['H'], kind=w.get('type', 0), border=tuple(s['border']),
                                   border_scale=p['pixelSize'] * sx, color=w['color'], flip=w.get('flip', 0))
        return {'prgb': prgb, 'alpha': al, 'left': p['cx'] - p['W'] / 2, 'top': p['cy'] - p['H'] / 2,
                'W': p['W'], 'H': p['H'], 'kind': 'sprite', 'rooted': p['rooted'],
                'info': {'sprite': '%s/%s' % (a['name'], s['name']), 'branch': p['branch'],
                         'type': nl.TYPES.get(w.get('type', 0), '?'), 'sprite_px': (s['w'], s['h']),
                         'widget_units': (w['w'], w['h']), 'long_axis_free': p['long_axis_free']}}
    if 'label' in w:
        st = w['label']
        if text is None:
            text, source = data.label_text(w)
        else:
            source = 'given'
        text = strip_markup(text)
        if not text.strip():
            return {'error': 'label has no text (set at run time; give --text or --key)'}
        cx, cy, W, H, rooted = nl.label_box(data, w)
        ppu, _, ri = data.root_of(w)
        ppu = ppu or nl.DEFAULT_PPU
        (_, _), (sx, sy), _ = nl.world(w, ri if ri is not None else len(w['chain']) - 1)
        px_per_unit = abs(sx) * ppu
        if (st.get('uifontName') or '').startswith('UIFont:'):
            r = nr.glyph_layer(data, w, text, px_per_unit)
            if r is None:
                return {'error': 'bitmap font %s or its texture is not in the APK' % st.get('uifontName')}
            prgb, al, (ox, oy), info = r
        else:
            if '\n' in text:
                text = text.split('\n')[0]
            prgb, al, (ox, oy), info = nr.label_layer(data, w, text, px_per_unit)
        info.update({'text': text, 'text_source': source})
        left, top = cx - W / 2 + ox, cy - H / 2 + oy
        return {'prgb': prgb, 'alpha': al, 'left': left, 'top': top, 'W': W, 'H': H, 'kind': 'label',
                'rooted': rooted, 'info': info}
    return {'error': '%s is not drawn from APK data' % cls}


def strip_markup(text):
    import re
    return re.sub(r'\[[0-9A-Fa-f]{6}\]|\[-\]|\[[bius]\]|\[/[bius]\]', '', text or '')


def ink_box(layer, thr=0.05):
    """(left, top, right, bottom) of the drawn pixels of a layer, in the layer's coordinates."""
    ys, xs = np.where(layer['alpha'] > thr)
    if len(xs) == 0:
        return None
    return (layer['left'] + xs.min(), layer['top'] + ys.min(), layer['left'] + xs.max() + 1,
            layer['top'] + ys.max() + 1)


def gray_mask(layer, background=None):
    """Template (gray) and weight mask for masked matching. Without a background: the un-premultiplied layer
    weighted by its alpha. With a background gray level: the layer over that flat gray in its ink box grown by
    2 px, all weights 1 (text in one flat colour has no contrast inside its own ink)."""
    prgb, a = layer['prgb'], layer['alpha']
    g = prgb[..., 0] * 0.299 + prgb[..., 1] * 0.587 + prgb[..., 2] * 0.114
    if background is not None:
        t = (g + background * (1 - a)).astype(np.float32)
        ys, xs = np.where(a > 0.05)
        m = np.zeros_like(a, np.float32)
        if len(xs):
            m[max(0, ys.min() - 2):ys.max() + 3, max(0, xs.min() - 2):xs.max() + 3] = 1
        return t, m
    t = np.where(a > 1e-3, g / np.maximum(a, 1e-3), 0).astype(np.float32)
    m = ((a > 0.35) * a).astype(np.float32)
    return t, m


# ---------------------------------------------------------------- masked matching
def wncc(img, t, m):
    """TM_CCOEFF_NORMED with weights m; windows with almost no variance score -1."""
    m = m.astype(np.float32)
    sm = float(m.sum())
    tw = float((m * t).sum()) / sm
    tz = (m * (t - tw)).astype(np.float32)
    tv = float((m * (t - tw) ** 2).sum())
    A = cv2.matchTemplate(img, tz, cv2.TM_CCORR)
    B = cv2.matchTemplate(img, m, cv2.TM_CCORR)
    C = cv2.matchTemplate(img * img, m, cv2.TM_CCORR)
    var = C - B * B / sm
    ok = var > sm * 4.0
    r = np.full(A.shape, -1.0, np.float32)
    r[ok] = A[ok] / np.sqrt(tv * var[ok] + 1e-6)
    return np.clip(r, -1, 1)


def match_layer(screen, t, m, box):
    """(score, left, top) of the best masked match of (t, m) inside box (x, y, w, h) of the screen, or None."""
    x, y, w, h = [int(round(v)) for v in box]
    x0, y0 = max(0, x), max(0, y)
    x1, y1 = min(screen.shape[1], x + w), min(screen.shape[0], y + h)
    if x1 - x0 < t.shape[1] or y1 - y0 < t.shape[0] or (m > 0.5).sum() < MIN_MASK_PX:
        return None
    r = wncc(screen[y0:y1, x0:x1].astype(np.float32), t, m)
    _, v, _, (bx, by) = cv2.minMaxLoc(r)
    return float(v), x0 + bx, y0 + by


def screen_xy(layer, screen_w):
    return screen_w / 2 + layer['left'], 360 + layer['top']


# ---------------------------------------------------------------- measuring a drawn size
def _resized(rgba_layer, W, H):
    prgb, a = rgba_layer
    W, H = max(2, int(round(W))), max(2, int(round(H)))
    interp = cv2.INTER_AREA if W < a.shape[1] and H < a.shape[0] else cv2.INTER_LINEAR
    return cv2.resize(prgb, (W, H), interpolation=interp), cv2.resize(a, (W, H), interpolation=interp)


def measure_size(screen, data, w, sprite, near, base_wh, search=24):
    """Drawn size of a sprite widget on a screenshot: the sprite's own art at its native aspect, scaled uniformly
    0.70-1.46 x base_wh, then each axis +-12 % in 1 px steps; masked NCC in a window around `near` (centre, screen
    px). Returns (score, W, H, centre x, centre y) or None."""
    a, s = sprite
    rgba = data.sprite_rgba(a, s)
    if rgba is None:
        return None
    # the art at a large size, resized per candidate (kind and borders as the widget draws it)
    big = nr.sprite_layer(rgba, s['w'] * 4, s['h'] * 4, kind=w.get('type', 0), border=tuple(s['border']),
                          border_scale=4 * (a.get('pixelSize') or 1.0), color=w['color'], flip=w.get('flip', 0))
    best = None

    def score(W, H, cx, cy, pad):
        prgb, al = _resized(big, W, H)
        t, m = gray_mask({'prgb': prgb, 'alpha': al})
        r = match_layer(screen, t, m, (cx - W / 2 - pad, cy - H / 2 - pad, W + 2 * pad, H + 2 * pad))
        if r is None:
            return None
        return r[0], W, H, r[1] + W / 2, r[2] + H / 2

    bw, bh = base_wh
    for k in SWEEP_UNIFORM:
        res = score(bw * k, bh * k, near[0], near[1], search)
        if res and (best is None or res[0] > best[0]):
            best = res
    if best is None:
        return None
    _, W0, H0, cx, cy = best
    # 1 px steps up to 25 per axis, coarser on large sprites
    sx, sy = max(1, int(W0 * SWEEP_AXIS) // 12), max(1, int(H0 * SWEEP_AXIS) // 12)
    for dw in range(-int(W0 * SWEEP_AXIS), int(W0 * SWEEP_AXIS) + 1, sx):
        for dh in range(-int(H0 * SWEEP_AXIS), int(H0 * SWEEP_AXIS) + 1, sy):
            res = score(W0 + dw, H0 + dh, cx, cy, 4)
            if res and res[0] > best[0]:
                best = res
    return best


def decide_mode(target_rule, target_w, other_data, other_w, screen, near=None):
    """Pick N or W for target_w from one screenshot of the other server. The other server's widget (same path) is
    measured on the screenshot with the other server's art; the mode whose prediction from the other server's data
    is closest decides, and the same mode is applied to the target. Returns dict(mode, measured, predicted, note)."""
    if not hasattr(other_data, '_rule'):
        other_data._rule = nl.Rule(other_data)
    other_rule = other_data._rule
    a, s = other_data.sprite_data(other_w)
    if s is None:
        return {'mode': None, 'note': 'other server widget has no sprite'}
    cands = {m: other_rule.predict(other_w, mode=m) for m in ('N', 'W')}
    if cands['W']['branch'] == 'D':
        return {'mode': 'D', 'note': 'bar-driven length: needs a screen of the target server'}
    sw = screen.shape[1]
    p = cands['W']
    if near is None:
        near = (sw / 2 + p['cx'], 360 + p['cy'])
    widest = max(cands.values(), key=lambda q: q['W'] * q['H'])
    m = measure_size(screen, other_data, other_w, (a, s), near, (widest['W'], widest['H']))
    if m is None or m[0] < 0.6:
        return {'mode': None, 'note': 'not found on the screenshot (best %.2f)' % (m[0] if m else -1)}

    def err(q):
        return max(abs(m[1] - q['W']) / max(q['W'], 1), abs(m[2] - q['H']) / max(q['H'], 1))
    mode = min(cands, key=lambda k: err(cands[k]))
    return {'mode': mode, 'score': m[0], 'measured': (m[1], m[2]),
            'predicted': {k: (q['W'], q['H']) for k, q in cands.items()},
            'note': 'measured %.1f x %.1f (score %.2f); N predicts %.1f x %.1f, W %.1f x %.1f -> %s' % (
                m[1], m[2], m[0], cands['N']['W'], cands['N']['H'], cands['W']['W'], cands['W']['H'], mode)}


# ---------------------------------------------------------------- locating widgets on a screenshot
def drawable(w):
    return w['class'] in ('UISprite', 'UISkinSprite') or 'label' in w


def top_node(w):
    """Identity of the widget's topmost Transform: widgets that share it move together (one scene or one prefab)."""
    n = w['chain'][-1]
    return (n[1], n[2])


def predicted_rect(data, rule, w):
    """(cx, cy, W, H, rooted) of a drawable widget, or None."""
    if 'label' in w:
        return nl.label_box(data, w)
    _, s = data.sprite_data(w)
    if s is None:
        return None
    p = rule.predict(w)
    return p['cx'], p['cy'], p['W'], p['H'], p['rooted']


def locate(data, rule, screen, box, min_score=0.45, tol=6, grow=2.5, text=None):
    """Widgets of `data` drawn inside box (x, y, w, h) of a screenshot of the same server.

    Candidates: drawable widgets no larger than `grow` x the box on either axis (bigger ones are backgrounds, see
    locate_backgrounds) whose rect overlaps the box when rooted; prefab widgets (no UI Root, no position in the data)
    of a size that fits are searched anywhere in the box. Each is drawn alone and matched alpha-weighted. Copies of
    the same path are tried once. text: what the element shows on this screenshot; every label is then drawn with
    it (labels whose text is set by code show something else in the data). Returns [dict(score, widget, layer, found (x, y), offset (found - predicted))]
    best first."""
    sw = screen.shape[1]
    bx, by, bw, bh = box
    seen, out = set(), []
    for w in data.widgets:
        if not drawable(w):
            continue
        if text is not None and 'label' not in w:
            continue
        key = (w['path'], w.get('sprite') or (w.get('label') or {}).get('text'))
        if key in seen:
            continue
        r = predicted_rect(data, rule, w)
        if r is None:
            continue
        cx, cy, W, H, rooted = r
        if 'label' in w:
            ppu = data.root_of(w)[0] or nl.DEFAULT_PPU
            H = w['label']['fontSize'] * ppu * abs(nl.world(w, data.root_of(w)[2])[1][1])
            W = min(W, bw)
        if W > grow * bw + 8 or H > grow * bh + 8 or W < 4 or H < 4:
            continue
        L, T = sw / 2 + cx - W / 2, 360 + cy - H / 2
        if rooted:
            ix = max(0, min(L + W, bx + bw) - max(L, bx))
            iy = max(0, min(T + H, by + bh) - max(T, by))
            if ix * iy <= 0:
                continue
        seen.add(key)
        lay = widget_layer(data, rule, w, text=text)
        if 'error' in lay:
            continue
        bgl = None
        if lay['kind'] == 'label':
            bgl = float(np.median(screen[max(0, int(by)):int(by + bh), max(0, int(bx)):int(bx + bw)]))
        t, m = gray_mask(lay, bgl)
        if (m > 0.5).sum() < LOCATE_MIN_PX or float(t[m > 0.5].std()) < LOCATE_MIN_STD:
            continue
        x, y = screen_xy(lay, sw)
        if rooted:
            win = (x - tol, y - tol, t.shape[1] + 2 * tol, t.shape[0] + 2 * tol)
        else:
            # anywhere its centre falls inside the box
            win = (bx - t.shape[1] / 2 - tol, by - t.shape[0] / 2 - tol, bw + t.shape[1] + 2 * tol,
                   bh + t.shape[0] + 2 * tol)
        res = match_layer(screen, t, m, win)
        if res is None or res[0] < min_score:
            continue
        out.append({'score': res[0], 'widget': w, 'layer': lay, 'found': (res[1], res[2]),
                    'offset': (res[1] - x, res[2] - y), 'rooted': rooted,
                    'box_score': box_explained(screen, lay, (res[1], res[2]), box)})
    # the widget that explains the whole box best first: a gradient strip can match its own spot perfectly and
    # still leave most of the box unexplained
    out.sort(key=lambda o: -o['box_score'])
    return out


def box_explained(screen, lay, at, box):
    """TM_CCOEFF_NORMED between the screenshot's box and the layer drawn at `at` over the box's median gray."""
    bx, by, bw, bh = [int(round(v)) for v in box]
    crop = screen[max(0, by):by + bh, max(0, bx):bx + bw].astype(np.float32)
    if crop.size == 0 or crop.std() < 1:
        return -1.0
    prgb, a = lay['prgb'], lay['alpha']
    g = prgb[..., 0] * 0.299 + prgb[..., 1] * 0.587 + prgb[..., 2] * 0.114
    canvas = np.full(crop.shape, float(np.median(crop)), np.float32)
    x, y = int(round(at[0])) - max(0, bx), int(round(at[1])) - max(0, by)
    h, w = a.shape
    x0, y0, x1, y1 = max(0, x), max(0, y), min(crop.shape[1], x + w), min(crop.shape[0], y + h)
    if x1 <= x0 or y1 <= y0:
        return -1.0
    aa = a[y0 - y:y1 - y, x0 - x:x1 - x]
    canvas[y0:y1, x0:x1] = g[y0 - y:y1 - y, x0 - x:x1 - x] + canvas[y0:y1, x0:x1] * (1 - aa)
    if canvas.std() < 1:
        return -1.0
    return float(cv2.matchTemplate(crop, canvas, cv2.TM_CCOEFF_NORMED)[0, 0])


def group(found, data, rule, tol=3.0):
    """The best match and every other match from the same scene or prefab whose offset (found - predicted) agrees
    with it within tol px: widgets the data places consistently with each other on the screenshot."""
    if not found:
        return []
    best = found[0]
    ox, oy = best['offset']
    top = top_node(best['widget'])
    keep = [best]
    drawn = {(best['widget'].get('sprite') or best['layer']['info'].get('text'), round(best['layer']['left']),
              round(best['layer']['top']))}
    for f in found[1:]:
        if top_node(f['widget']) != top:
            continue
        if abs(f['offset'][0] - ox) <= tol and abs(f['offset'][1] - oy) <= tol:
            # dialogs stacked at one spot draw the same thing: keep one
            k = (f['widget'].get('sprite') or f['layer']['info'].get('text'), round(f['layer']['left']),
                 round(f['layer']['top']))
            if k not in drawn:
                drawn.add(k)
                keep.append(f)
    return keep


def _box_score(screen, layers, box, tol=3):
    """Masked score of layers (screen-positioned dicts) composed in order, cut to box, within +-tol px."""
    sw = screen.shape[1]
    parts = [(l['prgb'], l['alpha'], (sw / 2 + l['left'], 360 + l['top'])) for l in layers]
    rgb, al, (L, T) = nr.compose(parts)
    bx, by, bw, bh = [int(round(v)) for v in box]
    x0, y0 = bx - L, by - T
    sub = {'prgb': rgb[max(0, y0):y0 + bh, max(0, x0):x0 + bw].astype(np.float32) *
           al[max(0, y0):y0 + bh, max(0, x0):x0 + bw, None],
           'alpha': al[max(0, y0):y0 + bh, max(0, x0):x0 + bw]}
    if sub['alpha'].size == 0:
        return None
    t, m = gray_mask(sub)
    r = match_layer(screen, t, m, (L + max(0, x0) - tol, T + max(0, y0) - tol, t.shape[1] + 2 * tol, t.shape[0] + 2 * tol))
    return r[0] if r else None


def locate_backgrounds(data, rule, screen, box, members, min_score=0.6, max_area=8.0):
    """Sprites of the members' scene or prefab drawn behind them (lower depth) whose rect covers the box: the button
    or window the element sits on. Each is drawn under the members at their offset; it is kept when that
    composite, cut to the box, scores at least min_score and no worse than the members alone."""
    if not members:
        return []
    top = top_node(members[0]['widget'])
    ox, oy = members[0]['offset']
    sw = screen.shape[1]

    def placed(m):
        return {**m['layer'], 'left': m['layer']['left'] + m['offset'][0], 'top': m['layer']['top'] + m['offset'][1]}
    fg = [placed(m) for m in sorted(members, key=lambda m: m['widget']['depth'])]
    alone = _box_score(screen, fg, box) or -1
    paths = {m['widget']['path'] for m in members}
    min_depth = min(m['widget']['depth'] for m in members)
    bx, by, bw, bh = box
    out, seen = [], set()
    for w in data.widgets:
        if w['class'] not in ('UISprite', 'UISkinSprite') or top_node(w) != top or w['path'] in paths:
            continue
        if w['depth'] >= min_depth or w['path'] in seen:
            continue
        r = predicted_rect(data, rule, w)
        if r is None:
            continue
        cx, cy, W, H, rooted = r
        L, T = sw / 2 + cx - W / 2 + ox, 360 + cy - H / 2 + oy
        if not (L <= bx + 2 and T <= by + 2 and L + W >= bx + bw - 2 and T + H >= by + bh - 2):
            continue
        # a full-screen dim or a whole window is not the element's own background
        if W * H > max_area * bw * bh:
            continue
        seen.add(w['path'])
        lay = widget_layer(data, rule, w)
        if 'error' in lay:
            continue
        b = {**lay, 'left': lay['left'] + ox, 'top': lay['top'] + oy}
        sc = _box_score(screen, [b] + fg, box)
        if sc is None or sc < min_score or sc < alone - 0.02:
            continue
        out.append({'score': sc, 'widget': w, 'layer': lay, 'found': (L, T), 'offset': (ox, oy), 'rooted': rooted})
    return out
