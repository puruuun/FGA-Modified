"""Draw NGUI widgets the way a phone screenshot shows them after FGA shrinks it to 720p.

Every layer is drawn on a device-sized screen (DEVICE_HEIGHT, bilinear, as the GPU samples) and then shrunk with
INTER_AREA to 720p, like a screenshot. Layers come back premultiplied: (rgb * a float32 HxWx3 in 0..255, a HxW).

Labels (UILabel with a dynamic font), following NGUI 3:
- glyphs on the label's own unit grid at the printed size (keepCrispWhenShrunk OnDesktop: a phone rasterizes at
  fontSize), pen moved by the rounded advance plus spacingX, no kerning; anti-aliasing is box coverage;
- the baseline is NGUIText's: the ')' glyph centred in a line of fontSize units;
- effect copies drawn first in effectColor (Shadow +dx,+dy; Outline the four diagonals; Outline8 the diagonals and
  the four axis offsets), then the glyphs in color x the vertical gradient;
- ShrinkContent: the largest integer size whose pen width fits the widget width (one line);
- placed in the widget rect by its alignment (Automatic follows the pivot) and pivot (vertical).
Bitmap-font labels (UIFont, digits) are drawn from the font's glyph table at label size / font size.
Sprites: Simple/Filled stretched to the drawn size, Sliced with corners kept at border x pixelSize x scale.
"""
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

import ngui_layout as nl

SS = 8
DEVICE_HEIGHT = 1080


# ---------------------------------------------------------------- labels (dynamic font)
def _font(path, px):
    return ImageFont.truetype(path, px, layout_engine=ImageFont.Layout.BASIC)


def pen_width(font_path, text, size, spacing_x=0):
    big = _font(font_path, size * SS)
    return sum(int(round(big.getlength(ch) / SS)) + spacing_x for ch in text) - spacing_x if text else 0


def baseline_from_top(font_path, size):
    """NGUIText.Update for a dynamic font: baseline = round(maxY + (size - maxY + minY) / 2) of ')'."""
    f = _font(font_path, size * SS)
    l, t, r, b = f.getbbox(')', anchor='ls')
    max_y, min_y = -t / SS, -b / SS
    if max_y <= 0:
        return float(size)
    return float(round(max_y + (size - max_y + min_y) * 0.5))


def printed_size(font_path, text, st, width, height):
    """(size, shrunk) NGUI prints a one-line label at."""
    size = st['fontSize']
    if st.get('overflow') != 'ShrinkContent':
        return size, False
    while size > 6 and (pen_width(font_path, text, size, st.get('spacingX', 0)) > width or size > height + 1):
        size -= 1
    return size, size != st['fontSize']


def _glyph_mask(font_path, text, size, spacing_x, pad):
    big = _font(font_path, size * SS)
    xs, x = [], 0
    for ch in text:
        xs.append(x)
        x += int(round(big.getlength(ch) / SS)) + spacing_x
    width = x - spacing_x if text else 0
    base = baseline_from_top(font_path, size)
    w_u = width + 2 * pad + size
    h_u = int(size + 2 * pad + size // 2)
    img = Image.new('L', (w_u * SS, h_u * SS), 0)
    d = ImageDraw.Draw(img)
    for ch, gx in zip(text, xs):
        d.text(((pad + gx) * SS, (pad + base) * SS), ch, font=big, fill=255, anchor='ls')
    m = cv2.resize(np.asarray(img, np.float32) / 255, (w_u, h_u), interpolation=cv2.INTER_AREA)
    return m, width


def _offsets(effect, dist):
    dx, dy = dist
    if effect == 'Shadow':
        return [(dx, dy)]
    if effect == 'Outline':
        return [(dx, dy), (-dx, -dy), (dx, -dy), (-dx, dy)]
    if effect == 'Outline8':
        return [(dx, dy), (-dx, -dy), (dx, -dy), (-dx, dy), (-dx, 0), (dx, 0), (0, dy), (0, -dy)]
    return []


_TEXT = {}


def render_text(font_path, text, size, st, px_per_unit, color=None, device_height=DEVICE_HEIGHT, pad=6):
    """(prgb, alpha, origin, pen width) of one line of text; origin = (x, y) in units of the layer's top-left
    relative to (pen start, line top). Cached: many labels share one style."""
    key = (font_path, text, size, px_per_unit, device_height, pad, tuple(color or st['color']),
           tuple(st.get(k) if not isinstance(st.get(k), list) else tuple(st.get(k)) for k in
                 ('spacingX', 'effect', 'effectColor', 'effectDistance', 'gradient', 'gradientTop', 'gradientBottom')))
    if key not in _TEXT:
        _TEXT[key] = _render_text(font_path, text, size, st, px_per_unit, color, device_height, pad)
    return _TEXT[key]


def _render_text(font_path, text, size, st, px_per_unit, color=None, device_height=DEVICE_HEIGHT, pad=6):
    m, width = _glyph_mask(font_path, text, size, st.get('spacingX', 0), pad)
    h_u, w_u = m.shape
    k = px_per_unit * device_height / 720
    dw, dh = max(1, int(round(w_u * k))), max(1, int(round(h_u * k)))
    color = color or st['color']

    def sample(ox, oy):
        M = np.float32([[k, 0, (k - 1) / 2 + ox * k], [0, k, (k - 1) / 2 + oy * k]])
        return cv2.warpAffine(m, M, (dw, dh), flags=cv2.INTER_LINEAR, borderValue=0)

    rgb = np.zeros((dh, dw, 3), np.float32)
    a = np.zeros((dh, dw), np.float32)

    def over(la, lrgb):
        nonlocal rgb, a
        rgb = lrgb * la[..., None] + rgb * (1 - la[..., None])
        a = la + a * (1 - la)

    ec = st.get('effectColor', [0, 0, 0, 1])
    # NGUI's effect distance is in units; the shadow goes down (positive y on screen)
    for ox, oy in _offsets(st.get('effect', 'None'), st.get('effectDistance', [1, 1])):
        over(sample(ox, oy) * ec[3] * color[3], np.broadcast_to(np.array(ec[:3], np.float32) * 255, (dh, dw, 3)))
    base_col = np.array(color[:3], np.float32)
    if st.get('gradient'):
        ys = (np.arange(dh, dtype=np.float32) - (k - 1) / 2) / k
        t = np.clip(1 - (ys - pad) / size, 0, 1)[:, None]
        top = np.array(st['gradientTop'][:3], np.float32)
        bot = np.array(st['gradientBottom'][:3], np.float32)
        main = np.broadcast_to(((bot + (top - bot) * t) * base_col)[:, None, :] * 255, (dh, dw, 3))
    else:
        main = np.broadcast_to(base_col * 255, (dh, dw, 3))
    over(sample(0, 0) * color[3], main)
    W, H = max(1, int(round(w_u * px_per_unit))), max(1, int(round(h_u * px_per_unit)))
    prgb = cv2.resize(rgb * a[..., None], (W, H), interpolation=cv2.INTER_AREA)
    pa = cv2.resize(a, (W, H), interpolation=cv2.INTER_AREA)
    return prgb, pa, (-pad, -pad), width


def label_layer(data, w, text, px_per_unit, font_path=None):
    """(prgb, alpha, top-left offset from the widget rect's top-left in 720p px, info) of a dynamic-font label."""
    st = w['label']
    font_path = font_path or data.font_path()
    size, shrunk = printed_size(font_path, text, st, w['w'], w['h'])
    prgb, pa, (ox, oy), width = render_text(font_path, text, size, st, px_per_unit, color=w['color'])
    align = st.get('alignment', 'Automatic')
    px, py = nl.PIVOT.get(w['pivot'], (.5, .5))
    if align == 'Automatic':
        align = 'Left' if px == 0 else ('Right' if px == 1 else 'Center')
    if align == 'Right':
        x0 = w['w'] - width
    elif align == 'Center':
        x0 = (w['w'] - width) / 2
    else:
        x0 = 0
    if py == 1:
        y0 = 0
    elif py == 0:
        y0 = w['h'] - size
    else:
        y0 = (w['h'] - size) / 2
    info = {'size': size, 'shrunk': shrunk, 'pen_width_units': width, 'fontSize': st['fontSize'],
            'effect': st.get('effect'), 'overflow': st.get('overflow'),
            'wraps': (st.get('overflow') in ('ShrinkContent', 'ResizeHeight') and w['h'] >= 2 * st['fontSize']
                      and pen_width(font_path, text, st['fontSize'], st.get('spacingX', 0)) > w['w'])}
    return prgb, pa, ((x0 + ox) * px_per_unit, (y0 + oy) * px_per_unit), info


# ---------------------------------------------------------------- labels (bitmap font)
def glyph_layer(data, w, text, px_per_unit):
    """(prgb, alpha, offset, info) of a UIFont (bitmap) label, or None when the font or its texture is missing."""
    st = w['label']
    name = (st.get('uifontName') or '').split(':', 1)[-1]
    font = data.uifonts.get(name)
    if not font:
        return None
    tex = None
    for cand in (font.get('texture'), font.get('sprite'), name):
        if cand:
            tex = data.texture_rgba(cand)
            if tex is not None:
                break
    if tex is None:
        return None
    th, tw = tex.shape[:2]
    u, v, uw, vh = font['uvRect']
    ox0, oy0 = u * tw, (1 - v - vh) * th
    glyphs = {g['index']: g for g in font['glyphs']}
    k = st['fontSize'] / font['size']
    pen, cells = 0, []
    for ch in text:
        g = glyphs.get(ord(ch))
        if g is None:
            return None
        cells.append((pen + g['offsetX'], g['offsetY'], g))
        pen += g['advance'] + st.get('spacingX', 0)
    width_u = max(x + g['width'] for x, _, g in cells)
    height_u = max(y + g['height'] for _, y, g in cells)
    canvas = np.zeros((height_u, width_u, 4), np.float32)
    for x, y, g in cells:
        gx, gy = int(round(ox0 + g['x'])), int(round(oy0 + g['y']))
        cell = tex[gy:gy + g['height'], gx:gx + g['width']].astype(np.float32)
        canvas[y:y + cell.shape[0], x:x + cell.shape[1]] = cell
    color = np.array(w['color'], np.float32)
    canvas[..., :3] *= color[:3]
    canvas[..., 3] *= color[3]
    prgb, pa = _scale_rgba(canvas, width_u * k * px_per_unit, height_u * k * px_per_unit)
    px, py = nl.PIVOT.get(w['pivot'], (.5, .5))
    x0 = {0: 0, 1: w['w'] - width_u * k}.get(px, (w['w'] - width_u * k) / 2)
    y0 = {1: 0, 0: w['h'] - height_u * k}.get(py, (w['h'] - height_u * k) / 2)
    return prgb, pa, (x0 * px_per_unit, y0 * px_per_unit), {'uifont': name, 'scale': k * px_per_unit}


# ---------------------------------------------------------------- sprites
def _scale_rgba(rgba, W, H, device_height=DEVICE_HEIGHT):
    """Premultiplied (rgb, a) of an RGBA float/uint8 image drawn W x H at 720p via a device screen."""
    W, H = max(1, int(round(W))), max(1, int(round(H)))
    f = rgba.astype(np.float32)
    a = f[..., 3] / 255.0
    prem = np.dstack([f[..., :3] * a[..., None], a])
    k = device_height / 720
    big = cv2.resize(prem, (max(1, int(round(W * k))), max(1, int(round(H * k)))), interpolation=cv2.INTER_LINEAR)
    small = cv2.resize(big, (W, H), interpolation=cv2.INTER_AREA).reshape(H, W, 4)
    return small[..., :3], np.clip(small[..., 3], 0, 1)


def sprite_layer(rgba, W, H, kind=0, border=(0, 0, 0, 0), border_scale=1.25, color=(1, 1, 1, 1), flip=0):
    """(prgb, alpha) of a sprite drawn W x H (720p px). kind 1 (Sliced) keeps the corners: border = NGUI
    (left, right, top, bottom) in sprite px, drawn at border_scale px per sprite px."""
    src = rgba.astype(np.float32).copy()
    src[..., :3] *= np.array(color[:3], np.float32)
    src[..., 3] *= color[3]
    if flip in (1, 3):
        src = src[:, ::-1]
    if flip in (2, 3):
        src = src[::-1]
    Wi, Hi = max(1, int(round(W))), max(1, int(round(H)))
    if kind != 1 or not any(border):
        return _scale_rgba(src, Wi, Hi)
    h, w = src.shape[:2]
    bl, br, bt, bb = border
    dl, dr, dt, db = [b * border_scale for b in (bl, br, bt, bb)]
    if dl + dr >= Wi or dt + db >= Hi:
        f = min(Wi / max(1e-6, dl + dr + 1), Hi / max(1e-6, dt + db + 1), 1.0)
        dl, dr, dt, db = dl * f, dr * f, dt * f, db * f
    xs = [(0, bl, 0, dl), (bl, w - br, dl, Wi - dr), (w - br, w, Wi - dr, Wi)]
    ys = [(0, bt, 0, dt), (bt, h - bb, dt, Hi - db), (h - bb, h, Hi - db, Hi)]
    rgb = np.zeros((Hi, Wi, 3), np.float32)
    al = np.zeros((Hi, Wi), np.float32)
    for y0, y1, Y0, Y1 in ys:
        for x0, x1, X0, X1 in xs:
            X0i, X1i, Y0i, Y1i = int(round(X0)), int(round(X1)), int(round(Y0)), int(round(Y1))
            if x1 - x0 <= 0 or y1 - y0 <= 0 or X1i - X0i <= 0 or Y1i - Y0i <= 0:
                continue
            p, q = _scale_rgba(src[y0:y1, x0:x1], X1i - X0i, Y1i - Y0i)
            rgb[Y0i:Y1i, X0i:X1i] = p
            al[Y0i:Y1i, X0i:X1i] = q
    return rgb, al


# ---------------------------------------------------------------- composition
def compose(layers, background=None, pad=0):
    """Composite [(prgb, alpha, (left, top) in 720p px)] in order on the union box (plus pad px).
    Returns (rgb uint8 or None, coverage alpha, (left, top) of the image). Without a background the RGB is the
    premultiplied colour over black and the alpha tells where nothing was drawn."""
    left = min(l for _, _, (l, _) in layers) - pad
    top = min(t for _, _, (_, t) in layers) - pad
    right = max(l + a.shape[1] for _, a, (l, _) in layers) + pad
    bottom = max(t + a.shape[0] for _, a, (_, t) in layers) + pad
    L, T = int(np.floor(left)), int(np.floor(top))
    W, H = int(np.ceil(right)) - L, int(np.ceil(bottom)) - T
    rgb = np.zeros((H, W, 3), np.float32)
    al = np.zeros((H, W), np.float32)
    if background is not None:
        rgb[:] = np.array(background, np.float32)
        al[:] = 1
    for prgb, a, (l, t) in layers:
        x, y = int(round(l)) - L, int(round(t)) - T
        h, w = a.shape
        x0, y0, x1, y1 = max(0, x), max(0, y), min(W, x + w), min(H, y + h)
        if x1 <= x0 or y1 <= y0:
            continue
        pa = a[y0 - y:y1 - y, x0 - x:x1 - x]
        pr = prgb[y0 - y:y1 - y, x0 - x:x1 - x]
        rgb[y0:y1, x0:x1] = pr + rgb[y0:y1, x0:x1] * (1 - pa[..., None])
        al[y0:y1, x0:x1] = pa + al[y0:y1, x0:x1] * (1 - pa)
    return np.clip(rgb + 0.5, 0, 255).astype(np.uint8), al, (L, T)
