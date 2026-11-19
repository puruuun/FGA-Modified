"""Where NGUI puts a sprite on screen, from the APK's scenes, in 720p (16:9) and FGA script (1440p) coordinates,
and the scale a draft of that sprite should use.

The APK's scenes (assets/bin/Data) hold every UISprite widget: its pivot, size and the Transform chain up to `UI Root`.
The widget size, not the sprite's own size, decides how large the sprite is drawn, so the draft scale is
1.25 * widget / sprite and is not always 1.25.

UISprite raw layout (NGUI 3, no type tree): after the 32-byte header, UIRect's four AnchorPoints (20 bytes each:
target PPtr 12, relative 4, absolute 4) and updateAnchors (4), then UIWidget: mColor (16), mPivot (4), mWidth (4),
mHeight (4), mDepth (4). That puts mPivot at offset 132.
NGUI's UI Root uses a 576-unit-tall virtual screen (root scale 2/576), so 1 unit = 1.25 px at 720p, 2.5 px at 1440p.
The box is for a 16:9 screen (centre 640,360). On a wider or taller screen the X origin moves; see coordinates.md.

usage: layout_calc.py --data DIR --extracted DIR [--out-json FILE] SPRITE [SPRITE ...]
  --data is the APK's assets/bin/Data, --extracted is extract_apk_ui.py's output folder (index.json).
  SPRITE is Atlas/sprite or a bare sprite name.
"""
import argparse
import json
import os
import struct
import sys

sys.dont_write_bytecode = True
import UnityPy  # noqa: E402

PIVOT = {0: (0, 1), 1: (.5, 1), 2: (1, 1), 3: (0, .5), 4: (.5, .5), 5: (1, .5), 6: (0, 0), 7: (.5, 0), 8: (1, 0)}
ANCHOR_OFFSETS = {'left': 32, 'right': 52, 'bottom': 72, 'top': 92}
# Two axes that differ by less than this are one scale (rounding of the widget size).
AXIS_TOLERANCE = 0.05


def transform_of(go):
    for comp in go.m_Component:
        c = comp.component if hasattr(comp, 'component') else comp
        try:
            o = c.deref()
        except Exception:
            continue
        if o.type.name in ('Transform', 'RectTransform'):
            return o.read()
    return None


def world(tr):
    """Position and scale in UI Root units, and the path; position is None when no UI Root was found."""
    nodes = []
    while tr is not None:
        nodes.append(tr)
        tr = tr.m_Father.read() if tr.m_Father.path_id else None
    names = [n.m_GameObject.read().m_Name for n in nodes]
    path = '/'.join(reversed(names))
    if 'UI Root' not in names:
        return None, None, path
    root = names.index('UI Root')
    x = y = 0.0
    sx = sy = 1.0
    # apply from the UI Root's child down to the widget
    for n in reversed(nodes[:root]):
        p, s = n.m_LocalPosition, n.m_LocalScale
        x += p.x * sx
        y += p.y * sy
        sx *= s.x
        sy *= s.y
    return (x, y), (sx, sy), path


def sprite_sizes(extracted):
    """bare name -> [(atlas, w, h)] from index.json."""
    with open(os.path.join(extracted, 'index.json'), encoding='utf-8') as f:
        atlases = json.load(f)['atlases']
    by_name = {}
    for atlas, sprites in atlases.items():
        for s in sprites:
            by_name.setdefault(s['name'], []).append((atlas, s['w'], s['h']))
    return by_name


def resolve(target, by_name):
    atlas, _, name = target.rpartition('/')
    cands = by_name.get(name, [])
    if atlas:
        cands = [c for c in cands if c[0] == atlas]
    return name, cands


def recommend(sx, sy):
    """One scale for the draft. A sliced sprite is stretched on one axis only, and the part a template is cut from
    (a label, an icon) keeps its own proportions, so the axis closer to the device scale 1.25 is the one to use."""
    if abs(sx - sy) <= AXIS_TOLERANCE * max(sx, sy):
        return round((sx + sy) / 2, 3), 'both axes agree'
    axis, val = ('x', sx) if abs(sx - 1.25) <= abs(sy - 1.25) else ('y', sy)
    return round(val, 3), 'stretched: x %.2f vs y %.2f, %s is nearer 1.25' % (sx, sy, axis)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--data', required=True, help="the APK's assets/bin/Data folder")
    ap.add_argument('--extracted', required=True, help="extract_apk_ui.py output (for each sprite's own size)")
    ap.add_argument('--out-json')
    ap.add_argument('sprites', nargs='+')
    a = ap.parse_args()

    by_name = sprite_sizes(a.extracted)
    wanted = {}
    for t in a.sprites:
        name, cands = resolve(t, by_name)
        if not cands:
            print('%s: not in index.json of %s' % (t, a.extracted), file=sys.stderr)
        elif len({c[0] for c in cands}) > 1:
            print('%s: name is in several atlases %s; widgets are matched by name only (the atlas PPtr is not '
                  'resolved), so check the size used below' % (t, [c[0] for c in cands]), file=sys.stderr)
        wanted[t] = (name.encode(), cands)

    env = UnityPy.load(a.data)
    results = []
    seen = set()
    for obj in env.objects:
        if obj.type.name != 'MonoBehaviour':
            continue
        try:
            raw = obj.get_raw_data()
        except Exception:
            continue
        if len(raw) < 150:
            continue
        for t, (tb, cands) in wanted.items():
            if raw.find(struct.pack('<i', len(tb)) + tb) < 0:
                continue
            pivot, w, h, depth = struct.unpack_from('<4i', raw, 132)
            if pivot not in PIVOT or not (0 < w < 3000 and 0 < h < 3000):
                continue
            key = (obj.assets_file.name, obj.path_id, t)
            if key in seen:
                continue
            seen.add(key)
            go = obj.assets_file.objects[struct.unpack_from('<q', raw, 4)[0]].read()
            tr = transform_of(go)
            if tr is None:
                continue
            pos, scale, path = world(tr)
            rec = {'sprite': t, 'file': obj.assets_file.name, 'path': path, 'pivot': pivot, 'size': [w, h],
                   'depth': depth,
                   # a nonzero target PPtr means the widget is anchored and may be resized at runtime
                   'anchored': [s for s, off in ANCHOR_OFFSETS.items()
                                if struct.unpack_from('<q', raw, off + 4)[0] != 0]}
            if pos is None:
                rec['no_ui_root'] = True
                results.append(rec)
                continue
            w2, h2 = w * scale[0], h * scale[1]
            px, py = PIVOT[pivot]
            left, top = pos[0] - px * w2, pos[1] + (1 - py) * h2
            # 720p 16:9 screen: centre (640, 360), y down; FGA script coords are 2x of that
            box720 = (640 + left * 1.25, 360 - top * 1.25, w2 * 1.25, h2 * 1.25)
            rec['ui_units_center'] = [round(pos[0] - (px - .5) * w2, 1), round(pos[1] - (py - .5) * h2, 1)]
            rec['box720'] = [round(v, 1) for v in box720]
            rec['box1440'] = [round(v * 2) for v in box720]
            if cands:
                _, sw, sh = cands[0]
                rec['sprite_size'] = [sw, sh]
                sx, sy = 1.25 * w2 / sw, 1.25 * h2 / sh
                rec['scale_x'], rec['scale_y'] = round(sx, 3), round(sy, 3)
                rec['draft_scale'], rec['scale_note'] = recommend(sx, sy)
            results.append(rec)

    if a.out_json:
        with open(a.out_json, 'w', encoding='utf-8') as f:
            json.dump(results, f, ensure_ascii=False, indent=1)
    for r in results:
        print('%-34s %s [%s]' % (r['sprite'], r['path'][-70:], r['file']))
        if r.get('no_ui_root'):
            print('    no UI Root above this widget: position unknown (prefab or scene without a root)')
            continue
        print('    box720 x=%.0f y=%.0f w=%.0f h=%.0f | box1440 x=%d y=%d w=%d h=%d | widget %dx%d pivot %d' % (
            *r['box720'], *r['box1440'], *r['size'], r['pivot']))
        if 'draft_scale' in r:
            print('    sprite %dx%d | scale x %.3f y %.3f -> draft --scale %s (%s)' % (
                *r['sprite_size'], r['scale_x'], r['scale_y'], r['draft_scale'], r['scale_note']))
        if r['anchored']:
            print('    anchored (%s): may be resized at runtime, check against a screenshot' % ', '.join(r['anchored']))


if __name__ == '__main__':
    main()
