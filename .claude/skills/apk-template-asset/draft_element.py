#!/usr/bin/env python3
"""Draft a screen element's template and Region from a server's extracted APK data (extract_apk_ui.py output).

Pick the element by what you know about it; every widget that matches is listed:

    --path RX         widgets whose Transform path matches the regex (re.search)
    --text TEXT       labels that show TEXT (localization entry keyed by the label's path, else the scene text)
    --key KEY         labels that show the localization entry KEY (message ids such as CLOSE_BUTTON_LABEL are set
                      from code: combine --key with --path to say which label shows it)
    --sprite A/S      sprites that use atlas sprite A/S (or a bare sprite name)
    --at X,Y          widgets whose drawn rect holds this 720p point of a 16:9 screen
    --pick N[,N]      keep these of the listed matches (default: all, at most --max)
    --with RX         add the widgets of the same scene or prefab whose path matches RX (the button under a label)

or let one screenshot of the OTHER server find it (game code and layout are shared by every server):

    --locate-on SHOT --locate-data DIR (--locate-box X,Y,W,H | --locate-template PNG)
        the widgets of DIR drawn inside that box of SHOT (720p game-area pixels); the same paths are drafted from
        --data. The box's margin around the drawn content carries over as the crop.
    --locate-path RX    name the element instead: only widgets whose path matches RX are looked for in the box
                        (no size, flatness or box-explained floor). When none is found there, the named widgets
                        are drafted without the crop margin and without the sprites under them.
    --locate-sprite S   draw the named sprite widgets with sprite S of their atlas (a sprite set by code, which no
                        widget names in the data)
    --shown-text TEXT   the text the element shows on SHOT (labels set from code show something else in the
                        data); the target text is the same localization entry on the target server
    --target-text TEXT  the target text, for texts that come from server data and are not in the APK table

For each widget it prints the path, the size rule's branch, the 720p box (16:9) and the Region at 1440p
(`Region(x, y, w, h).xFromCenter()`, docs/agents/coordinates.md), and it writes the draft PNG(s) to --out-dir:

- text: the UILabel renderer (ngui_render.py) with the label's decoded font size, effect, colours and ShrinkContent;
- sprites: the size rule (ngui_layout.py); where N (native size) and W (stretched to the widget) disagree, BOTH
  drafts are written (`_N`, `_W`) with a warning unless --decide-from settles it;
- digits and other bitmap-font labels: the UIFont glyph table;
- a bar-driven length (UIScrollBar / UIProgressBar / UISlider) is drawn at the widget length and flagged
  "needs a screen".

    --decide-from SHOT --decide-data DIR   measure the widget (same path) on a screenshot of the other server, pick
                                           the mode whose prediction from the OTHER server's data matches, apply it
                                           here. Defaults to the --locate-on screenshot.

Scoring (screen_score.py), written to <name>_scores.csv:

    --screens DIR [--reference PNG | --positive RX] [--threshold code|T] [--image-name NAME]

Background: a draft is drawn on the widgets under it; what no widget covers is filled with --background RRGGBB or
the median of a template's 1 px border (--background-from PNG); otherwise it stays black and is reported.
"""
import argparse
import csv
import hashlib
import json
import os
import re
import sys

import cv2
import numpy as np
from PIL import Image

sys.dont_write_bytecode = True
# Korean and Japanese text; a Korean Windows console defaults to cp949, which cannot print Japanese
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, 'reconfigure'):
        _stream.reconfigure(encoding='utf-8')
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import element as el  # noqa: E402
import ngui_layout as nl  # noqa: E402
import ngui_render as nr  # noqa: E402
import screen_score as ss  # noqa: E402

MAX_LIST = 40
# below this the best located widget leaves most of the box unexplained (art not in the APK, or not a widget)
MIN_BOX_SCORE = 0.5
INSET_LIMIT = 12


_DATA = {}


def load_data(folder):
    """nl.Data and its Rule, kept for repeated main() calls in one process."""
    key = os.path.abspath(folder)
    if key not in _DATA:
        d = nl.Data(folder)
        d._rule = nl.Rule(d)
        _DATA[key] = d
    return _DATA[key]


# ---------------------------------------------------------------- selecting widgets
def select(data, rule, a):
    loc = data.localization()
    out = []
    want_text = a.text
    if a.key:
        if a.key not in loc:
            sys.exit('key %s not in the localization table of %s' % (a.key, data.folder))
        want_text = el.strip_markup(loc[a.key])
    rx = re.compile(a.path) if a.path else None
    atlas, _, sprite = (a.sprite or '').rpartition('/')
    at = tuple(float(v) for v in a.at.split(',')) if a.at else None
    for w in data.widgets:
        if rx and not rx.search(w['path']):
            continue
        if a.sprite and (w.get('sprite') != sprite or (atlas and (data.sprite_data(w)[0] or {}).get('name') != atlas)):
            continue
        if want_text and not a.key:
            if 'label' not in w or el.strip_markup(data.label_text(w)[0]).strip() != want_text.strip():
                continue
        if a.key and not rx and ('label' not in w or el.strip_markup(data.label_text(w)[0]).strip() != want_text.strip()):
            continue
        if at:
            r = el.predicted_rect(data, rule, w)
            if r is None or not r[4]:
                continue
            cx, cy, W, H, _ = r
            if not (640 + cx - W / 2 <= at[0] <= 640 + cx + W / 2 and 360 + cy - H / 2 <= at[1] <= 360 + cy + H / 2):
                continue
        if not el.drawable(w):
            continue
        out.append(w)
    return out, (el.strip_markup(loc[a.key]) if a.key else None)


def with_siblings(data, chosen, rx):
    tops = {el.top_node(w) for w in chosen}
    have = {(w['file'], w['pid']) for w in chosen}
    r = re.compile(rx)
    return chosen + [w for w in data.widgets if el.top_node(w) in tops and (w['file'], w['pid']) not in have
                     and r.search(w['path']) and el.drawable(w)]


def same_widget(data, w):
    """The widget of `data` with the path, class and sprite name of w (another server's), or None."""
    if not hasattr(data, '_by_path'):
        data._by_path = {}
        for x in data.widgets:
            data._by_path.setdefault(x['path'], []).append(x)
    cands = [x for x in data._by_path.get(w['path'], []) if x['class'] == w['class']]
    if w.get('sprite'):
        cands = [x for x in cands if x.get('sprite') == w['sprite']] or cands
    return cands[0] if cands else None


# ---------------------------------------------------------------- locating on the other server's screenshot
def locate_on(a, target):
    other = load_data(a.locate_data)
    orule = other._rule
    shot = load_shot(a.locate_on)
    if a.locate_box:
        box = tuple(float(v) for v in a.locate_box.split(','))
    else:
        t = ss.read_gray(a.locate_template)
        b = ss.best_in(shot, t)
        box = (b[1], b[2], t.shape[1], t.shape[0])
        print('locate: %s found at %d,%d (%.3f) on %s' % (os.path.basename(a.locate_template), b[1], b[2], b[0],
                                                         os.path.basename(a.locate_on)))
    only = re.compile(a.locate_path) if a.locate_path else None
    found = el.locate(other, orule, shot, box, text=a.shown_text, only=only, only_sprite=a.locate_sprite)
    # only widgets the target build also has can be drafted there
    missing = [f for f in found if same_widget(target, f['widget']) is None]
    if missing:
        print('locate: %d located widget(s) are not in the target data and are skipped' % len(missing))
        found = [f for f in found if f not in missing]
    if found and only is None and found[0]['box_score'] < MIN_BOX_SCORE:
        print('locate: best widget explains the box at %.2f only (< %.2f): not located' % (found[0]['box_score'],
                                                                                       MIN_BOX_SCORE))
        for f in found[:5]:
            print('   %.3f %.3f   %-40s %s' % (f['score'], f['box_score'],
                                              (f['widget'].get('sprite') or f['layer']['info'].get('text', ''))[:40],
                                              f['widget']['path'][-80:]))
        found = []
    members = el.group(found, other, orule)
    backs = el.locate_backgrounds(other, orule, shot, box, members) if not a.no_background_widgets else []
    named = []
    if only is not None and not members:
        for w in other.widgets:
            if only.search(w['path']) and el.drawable(w) and w['path'] not in {n['path'] for n in named}:
                named.append(sprite_as(w, a.locate_sprite))
        print('locate: no widget matching %r is found in the box; drafting the %d named widget(s) without the crop '
              'margin and without the sprites under them' % (a.locate_path, len(named)))
    print('locate: %d widget(s) drawn in the box; kept %d (one scene or prefab, consistent offsets), %d under them'
          % (len(found), len(members), len(backs)))
    for f in found[:8]:
        print('   %.3f %.3f %s %-40s %s' % (f['score'], f['box_score'], '*' if f in members else ' ',
                                      (f['widget'].get('sprite') or f['layer']['info'].get('text', ''))[:40],
                                      f['widget']['path'][-80:]))
    for f in backs:
        print('   %.3f B %-40s %s' % (f['score'], f['widget'].get('sprite', ''), f['widget']['path'][-80:]))
    ink = [el.ink_box({**m['layer'], 'left': m['layer']['left'] + m['offset'][0],
                       'top': m['layer']['top'] + m['offset'][1]}) for m in members]
    sw = shot.shape[1]
    ink = [(l + sw / 2, t + 360, r + sw / 2, b + 360) for l, t, r, b in [i for i in ink if i]]
    insets = None
    if ink:
        L, T = min(i[0] for i in ink), min(i[1] for i in ink)
        R, B = max(i[2] for i in ink), max(i[3] for i in ink)
        insets = tuple(int(round(max(-INSET_LIMIT, min(INSET_LIMIT, v)))) for v in
                       (L - box[0], T - box[1], box[0] + box[2] - R, box[1] + box[3] - B))
    target_text = None
    labels = [m['widget'] for m in members if m['layer']['kind'] == 'label'] + [w for w in named if 'label' in w]
    if a.shown_text and labels:
        own = el.strip_markup(other.label_text(labels[0])[0]).strip()
        if own == a.shown_text.strip():
            print('text: the located label shows %r in the data itself; the target label keeps its own text' % own)
        else:
            target_text = map_text(other, target, a.shown_text)
            if target_text is None and a.target_text:
                target_text = a.target_text
                print('text: not in the APK localization; target text %r as given (--target-text)' % target_text)
            elif target_text is None:
                sys.exit('no localization entry shows %r on the other server with an entry on the target (the text '
                         'comes from server data): give the target text with --target-text' % a.shown_text)
    pairs = []
    found_pairs = [(m['widget'], 'background' if m in backs else 'element') for m in members + backs]
    for ow, role in found_pairs + [(w, 'element') for w in named]:
        tw = same_widget(target, ow)
        if tw is not None and role == 'element' and only is not None:
            tw = sprite_as(tw, a.locate_sprite)
        pairs.append((ow, tw, role))
        if tw is None:
            print('   %s is not in the target data' % ow['path'])
    return other, shot, pairs, insets, target_text


def sprite_as(w, sprite):
    """w drawn with another sprite of its atlas (set by code), or w."""
    return dict(w, sprite=sprite) if sprite and w['class'] in ('UISprite', 'UISkinSprite') else w


def map_text(other, target, shown):
    """The target server's text of the localization entries that show `shown` on the other server."""
    ol, tl = other.localization(), target.localization()
    keys = [k for k, v in ol.items() if el.strip_markup(v).strip() == shown.strip()]
    vals = {}
    for k in keys:
        if k in tl:
            vals.setdefault(el.strip_markup(tl[k]).strip(), []).append(k)
    if not vals:
        return None
    best = max(vals, key=lambda v: len(vals[v]))
    print('text: %r is shown by %d entr%s; target text %r (%s)%s' % (
        shown, len(keys), 'y' if len(keys) == 1 else 'ies', best, ', '.join(vals[best][:3]),
        '' if len(vals) == 1 else '; other target texts: %s' % ', '.join(repr(v) for v in vals if v != best)))
    return best


def load_shot(path):
    g = ss.game_area_gray(path, ss.crop_template_module())
    if g is None:
        sys.exit('%s: not a full screenshot' % path)
    return g


# ---------------------------------------------------------------- drafting
def border_median(path):
    rgb = np.array(Image.open(path).convert('RGB'))
    b = np.concatenate([rgb[0], rgb[-1], rgb[:, 0], rgb[:, -1]])
    return tuple(int(v) for v in np.median(b, axis=0))


def draft_variants(data, rule, widgets, roles, decided, text_override):
    """[(variant name, [layers], notes)]: one variant, or two (_N, _W) when a sprite's mode is open."""
    open_ = []
    for w in widgets:
        if w['class'] in ('UISprite', 'UISkinSprite') and data.sprite_data(w)[1] is not None:
            c = rule.candidates(w)
            if len(c) > 1 and (w['file'], w['pid']) not in decided:
                open_.append(w)
    variants = [('', {})] if not open_ else [('_N', {k: 'N' for k in [(w['file'], w['pid']) for w in open_]}),
                                             ('_W', {k: 'W' for k in [(w['file'], w['pid']) for w in open_]})]
    out = []
    order = sorted(widgets, key=lambda w: (roles.get((w['file'], w['pid'])) != 'background', w['depth']))
    for name, modes in variants:
        layers, notes = [], []
        for w in order:
            k = (w['file'], w['pid'])
            mode = decided.get(k) or modes.get(k)
            lay = el.widget_layer(data, rule, w, mode=mode,
                                  text=text_override if (text_override and 'label' in w) else None)
            if 'error' in lay:
                notes.append('%s: %s' % (w['path'][-60:], lay['error']))
                continue
            layers.append((w, lay))
        out.append((name, layers, notes))
    return out, open_


def render_draft(layers, roles, background, insets):
    """(rgb uint8, uncovered fraction, box (left, top, w, h) centre-relative)."""
    parts = [(lay['prgb'], lay['alpha'], (lay['left'], lay['top'])) for _, lay in layers]
    rgb, al, (L, T) = nr.compose(parts)
    fg = [lay for w, lay in layers if roles.get((w['file'], w['pid'])) != 'background'] or [lay for _, lay in layers]
    inks = [el.ink_box(lay) for lay in fg]
    inks = [i for i in inks if i]
    l, t = min(i[0] for i in inks), min(i[1] for i in inks)
    r, b = max(i[2] for i in inks), max(i[3] for i in inks)
    if insets:
        l, t, r, b = l - insets[0], t - insets[1], r + insets[2], b + insets[3]
    x0, y0 = int(np.floor(l - L)), int(np.floor(t - T))
    x1, y1 = int(np.ceil(r - L)), int(np.ceil(b - T))
    pad = max(0, -x0, -y0, x1 - rgb.shape[1], y1 - rgb.shape[0])
    if pad:
        rgb = cv2.copyMakeBorder(rgb, pad, pad, pad, pad, cv2.BORDER_CONSTANT, value=0)
        al = cv2.copyMakeBorder(al, pad, pad, pad, pad, cv2.BORDER_CONSTANT, value=0)
        x0, y0, x1, y1 = x0 + pad, y0 + pad, x1 + pad, y1 + pad
    rgb, al = rgb[y0:y1, x0:x1].astype(np.float32), al[y0:y1, x0:x1]
    uncovered = float((al < 0.99).mean())
    if background is not None:
        rgb = rgb + np.array(background, np.float32) * (1 - al[..., None])
    return np.clip(rgb + 0.5, 0, 255).astype(np.uint8), uncovered, (L + x0 - pad, T + y0 - pad, x1 - x0, y1 - y0)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--data', required=True, help='extract_apk_ui.py output of the server to draft for')
    ap.add_argument('--path')
    ap.add_argument('--text')
    ap.add_argument('--key')
    ap.add_argument('--sprite')
    ap.add_argument('--at')
    ap.add_argument('--pick')
    ap.add_argument('--with', dest='with_rx')
    ap.add_argument('--max', type=int, default=MAX_LIST)
    ap.add_argument('--locate-on')
    ap.add_argument('--locate-data')
    ap.add_argument('--locate-box')
    ap.add_argument('--locate-template')
    ap.add_argument('--locate-path', help='regex of the widget path(s) of the element: name the element instead of '
                    'letting the screenshot find it')
    ap.add_argument('--locate-sprite', help='with --locate-path: the sprite the named sprite widgets show (set by code)')
    ap.add_argument('--shown-text', help="the text the element shows on the --locate-on screenshot (that server's "
                    'language); labels are matched with it, and the target text is the same localization entry')
    ap.add_argument('--target-text', help='the text the element shows on the target server, when the APK localization '
                    'does not hold it (texts from server data)')
    ap.add_argument('--no-background-widgets', action='store_true', help='do not add the sprites under the element')
    ap.add_argument('--decide-from')
    ap.add_argument('--decide-data')
    ap.add_argument('--background')
    ap.add_argument('--background-from')
    ap.add_argument('--screens')
    ap.add_argument('--reference')
    ap.add_argument('--positive')
    ap.add_argument('--threshold', default='code')
    ap.add_argument('--image-name')
    ap.add_argument('--cache')
    ap.add_argument('--out-dir', required=True)
    ap.add_argument('--name', default='draft')
    a = ap.parse_args(argv)
    os.makedirs(a.out_dir, exist_ok=True)

    data = load_data(a.data)
    rule = data._rule
    roles, insets, text_override = {}, None, None
    decide_shot = decide_data = None
    if a.locate_on:
        other, shot, pairs, insets, text_override = locate_on(a, data)
        widgets = []
        for ow, tw, role in pairs:
            if tw is not None:
                widgets.append(tw)
                roles[(tw['file'], tw['pid'])] = role
        decide_shot, decide_data = shot, other
        if not widgets:
            sys.exit('nothing located')
    else:
        widgets, text_override = select(data, rule, a)
        if not widgets:
            sys.exit('no widget matches')
        if len(widgets) > 1 or a.pick:
            for i, w in enumerate(widgets[:a.max]):
                r = el.predicted_rect(data, rule, w)
                print('  [%d] %-10s %-36s %s %s' % (i, w['class'], (w.get('sprite') or data.label_text(w)[0])[:36],
                                                    'box %.0f,%.0f %.0fx%.0f' % (640 + r[0] - r[2] / 2, 360 + r[1] - r[3] / 2,
                                                                                 r[2], r[3]) if r else '', w['path']))
            if len(widgets) > a.max:
                print('  ... %d more' % (len(widgets) - a.max))
        if a.pick:
            widgets = [widgets[int(i)] for i in a.pick.split(',')]
        elif len(widgets) > 1:
            print('%d widgets match; drafting the first (choose with --pick)' % len(widgets))
            widgets = widgets[:1]
        if a.with_rx:
            widgets = with_siblings(data, widgets, a.with_rx)
    if a.decide_from:
        decide_shot, decide_data = load_shot(a.decide_from), load_data(a.decide_data or a.locate_data)

    # modes decided from the other server's screenshot
    decided, report = {}, {'widgets': [], 'notes': []}
    for w in widgets:
        if w['class'] not in ('UISprite', 'UISkinSprite') or data.sprite_data(w)[1] is None:
            continue
        cands = rule.candidates(w)
        if len(cands) < 2 and cands[0][0] != 'D':
            continue
        if roles.get((w['file'], w['pid'])) == 'background':
            # only its pixels under the element are used: the rule's choice is kept
            decided[(w['file'], w['pid'])] = cands[0][0] if cands[0][0] in ('N', 'W') else None
            continue
        if cands[0][0] == 'D':
            report['notes'].append('%s: bar-driven %s length - needs a screen' % (w['path'][-60:], cands[0][1]['long_axis_free']))
            continue
        if decide_shot is None:
            report['notes'].append('%s: N %.1fx%.1f vs W %.1fx%.1f undecided - both drafted' % (
                w['path'][-60:], *[v for _, p in cands for v in (p['W'], p['H'])]))
            continue
        ow = same_widget(decide_data, w)
        if ow is not None and a.locate_sprite and w.get('sprite') == a.locate_sprite:
            ow = sprite_as(ow, a.locate_sprite)
        if ow is None:
            report['notes'].append('%s: not in the deciding server data' % w['path'][-60:])
            continue
        r = el.decide_mode(rule, w, decide_data, ow, decide_shot)
        report['notes'].append('%s: decide %s' % (w['path'][-60:], r['note']))
        if r.get('mode') in ('N', 'W'):
            decided[(w['file'], w['pid'])] = r['mode']

    background = None
    if a.background:
        background = tuple(int(a.background[i:i + 2], 16) for i in (0, 2, 4))
    elif a.background_from:
        background = border_median(a.background_from)

    variants, open_ = draft_variants(data, rule, widgets, roles, decided, text_override)
    print('element (%s):' % a.data)
    for w in widgets:
        r = el.predicted_rect(data, rule, w)
        lay = el.widget_layer(data, rule, w, mode=decided.get((w['file'], w['pid'])),
                              text=text_override if (text_override and 'label' in w) else None)
        info = lay.get('info', {})
        br = info.get('branch', 'label' if 'label' in w else '')
        print('  %-10s %-10s %-7s %s' % (roles.get((w['file'], w['pid']), 'element'), w['class'], br, w['path']))
        if r:
            cx, cy, W, H, rooted = r
            print('      %s box720 (16:9) %.1f,%.1f %.1fx%.1f | Region(%d, %d, %d, %d).xFromCenter() | %s' % (
                'screen' if rooted else 'prefab (relative to its top node, not a screen position)',
                640 + cx - W / 2, 360 + cy - H / 2, W, H, *nl.region_1440(cx, cy, W, H), info))
        if 'error' in lay:
            print('      cannot draw: ' + lay['error'])
        anchors = [c for c in data.components_on_chain(w) if c[1] in nl.EDGE_ANCHOR_CLASSES]
        if anchors or w.get('anchors'):
            print('      anchored (%s): on screens wider than 16:9 the position may follow an edge; check a wide screen'
                  % ', '.join(sorted({c[1] for c in anchors} | set(w.get('anchors') or {}))))
        report['widgets'].append({'path': w['path'], 'class': w['class'], 'role': roles.get((w['file'], w['pid']), 'element'),
                                  'box720_16x9': [round(640 + r[0] - r[2] / 2, 1), round(360 + r[1] - r[3] / 2, 1),
                                                  round(r[2], 1), round(r[3], 1)] if r else None,
                                  'region1440_from_center': nl.region_1440(*r[:4]) if r else None,
                                  'rooted': bool(r and r[4]), 'info': {k: v for k, v in info.items() if k != 'prgb'}})
    for n in report['notes']:
        print('  NOTE ' + n)

    drafts = []
    for vname, layers, notes in variants:
        for n in notes:
            print('  cannot draw ' + n)
        if not layers:
            continue
        img, uncovered, box = render_draft(layers, roles, background, insets)
        path = os.path.join(a.out_dir, a.name + vname + '.png')
        Image.fromarray(img).save(path)
        digest = hashlib.sha256(open(path, 'rb').read()).hexdigest()
        cx, cy = box[0] + box[2] / 2, box[1] + box[3] / 2
        print('draft %s: %dx%d, box720 (16:9) %.0f,%.0f, Region(%d, %d, %d, %d).xFromCenter(), +12 px margin '
              'Region(%d, %d, %d, %d)%s%s' % (
                  path, img.shape[1], img.shape[0], 640 + box[0], 360 + box[1], *nl.region_1440(cx, cy, box[2], box[3]),
                  *nl.region_1440(cx, cy, box[2], box[3], 12),
                  ('; %.0f %% not covered by any widget%s' % (100 * uncovered, ', filled with %s' % (background,)
                                                               if background else ' (black: give --background)'))
                  if uncovered > 0.01 else '', '; WARNING: mode open, %s variant' % vname[1:] if vname else ''))
        drafts.append({'file': path, 'sha256': digest, 'variant': vname[1:] or 'decided', 'size': [img.shape[1], img.shape[0]],
                       'uncovered': round(uncovered, 3), 'background': background, 'insets': insets})
    report['drafts'] = drafts
    if open_ and decide_shot is None:
        print('WARNING: N/W open for %d sprite(s): both drafts written; one screenshot of either server decides '
              '(--decide-from SHOT --decide-data DIR)' % len(open_))

    if a.screens and drafts:
        name = a.image_name or (os.path.basename(a.reference) if a.reference else None)
        if a.threshold == 'code':
            thr, calls = ss.threshold_from_code(name) if name else (ss.DEFAULT_THRESHOLD, [])
            print('threshold %.2f from %d call(s) of %s' % (thr, len(calls), name))
        else:
            thr = float(a.threshold)
        screens = ss.load_screens(a.screens, a.cache)
        ref = ss.read_gray(a.reference) if a.reference else None
        if ref is None and not a.positive:
            sys.exit('scoring needs --reference or --positive')
        roles_s, _ = ss.classify(screens, ref, thr, a.positive)
        rows = []
        for d in ([{'file': a.reference, 'variant': 'reference'}] if a.reference else []) + drafts:
            t = ss.read_gray(d['file'])
            s, per = ss.score_template(t, screens, roles_s, thr, None, ref.shape if ref is not None else None)
            d['scores'] = s
            print('  %-10s true min %s (%s) | best false %s (%s) | thr %.2f | margins %s / %s' % (
                d['variant'], ss.fmt(s['true_min']), s['true_min_screen'], ss.fmt(s['fp_max']), s['fp_max_screen'],
                thr, ss.fmt(s['margin_true']), ss.fmt(s['margin_false'])))
            rows += [(d['variant'], n, r, ss.fmt(v)) for n, r, v in per]
        with open(os.path.join(a.out_dir, a.name + '_scores.csv'), 'w', newline='', encoding='utf-8') as f:
            w = csv.writer(f)
            w.writerow(['draft', 'screen', 'role', 'score'])
            w.writerows(rows)
    with open(os.path.join(a.out_dir, a.name + '.json'), 'w', encoding='utf-8') as f:
        json.dump(report, f, ensure_ascii=False, indent=1, default=str)
    return report


if __name__ == '__main__':
    main()
