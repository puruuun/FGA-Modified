"""The text half of selfcheck.py: labels drawn from their decoded UILabel settings, found on a named screenshot.

Each row of selfcheck_text.csv names a label (server, Transform path, the text it shows) and a screenshot of
docs/reference-images on which that label is visible. The label is drawn by ngui_render at its data scale (font
size, effect, colours, ShrinkContent, world scale x 720/576), and matched alpha-weighted (only the drawn pixels count)
within half its box + 30 px of its data position (x from the screen centre, or from either edge on a screen
wider than 16:9). `expected` is the score this skill
version gives; `research` is the score of the study the renderer was accepted on, which also swept the scale (so
it can be a little higher).

    text_check.py --kr build/apk-kr --jp build/apk-jp [--record]   (--record rewrites `expected`)
"""
import argparse
import csv
import os
import sys

sys.dont_write_bytecode = True
# Korean and Japanese text; a Korean Windows console defaults to cp949, which cannot print Japanese
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, 'reconfigure'):
        _stream.reconfigure(encoding='utf-8')
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import element as el  # noqa: E402
import ngui_layout as nl  # noqa: E402
import screen_score as ss  # noqa: E402

FIELDS = ['server', 'text', 'path', 'screen', 'research', 'expected']


def score_row(data, rule, row, screens):
    ws = [w for w in data.widgets if w['path'] == row['path'] and 'label' in w]
    if not ws:
        return None, 'label not in this build'
    w = ws[0]
    lay = el.widget_layer(data, rule, w, text=row['text'])
    if 'error' in lay:
        return None, lay['error']
    g = screens.get(row['screen'])
    if g is None:
        return None, 'screenshot %s missing' % row['screen']
    t, m = el.gray_mask(lay)
    x, y = el.screen_xy(lay, g.shape[1])
    xs = {x}
    if g.shape[1] > 1290:
        # wider than 16:9: the label may follow the left or right edge instead of the centre
        xs |= {x - (g.shape[1] - 1280) / 2, x + (g.shape[1] - 1280) / 2}
    mx, my = lay['W'] / 2 + 30, lay['H'] / 2 + 30
    rs = [el.match_layer(g, t, m, (xx - mx, y - my, t.shape[1] + 2 * mx, t.shape[0] + 2 * my)) for xx in xs]
    rs = [r for r in rs if r]
    if not rs:
        return None, 'does not fit the screenshot'
    return max(r[0] for r in rs), 'size %d%s' % (lay['info']['size'], ' (shrunk)' if lay['info']['shrunk'] else '')


def run(datas, repo, csv_path, tolerance, record=False):
    with open(csv_path, encoding='utf-8') as f:
        rows = list(csv.DictReader(f))
    ok = True
    screens = {}
    print('Text (alpha-weighted score of the drawn label on a reference screenshot; expected within %.2f):' % tolerance)
    for server in ('kr', 'jp'):
        sel = [r for r in rows if r['server'] == server]
        if not sel:
            continue
        screens = dict(ss.load_screens(os.path.join(repo, 'docs', 'reference-images', server.capitalize())))
        data = datas[server]
        rule = getattr(data, '_rule', None) or nl.Rule(data)
        data._rule = rule
        vals = []
        for r in sel:
            v, note = score_row(data, rule, r, screens)
            if record and v is not None:
                r['expected'] = '%.3f' % v
            exp = float(r['expected']) if r.get('expected') else None
            good = v is not None and exp is not None and abs(v - exp) <= tolerance
            ok &= good
            if v is not None:
                vals.append(v)
            print('  %s %s %-24s %-40s score %s expected %s research %s  %s' % (
                'ok  ' if good else 'DIFF', server.upper(), r['text'][:24], r['screen'][:40],
                '-' if v is None else '%.3f' % v, r.get('expected') or '-', r['research'], note))
        if vals:
            print('  %s: %d labels, score %.3f-%.3f' % (server.upper(), len(vals), min(vals), max(vals)))
    if record:
        with open(csv_path, 'w', newline='', encoding='utf-8') as f:
            w = csv.DictWriter(f, fieldnames=FIELDS)
            w.writeheader()
            w.writerows(rows)
        print('recorded', csv_path)
    return ok


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--kr', required=True)
    ap.add_argument('--jp', required=True)
    ap.add_argument('--repo', default=os.path.abspath(os.path.join(HERE, '..', '..', '..')))
    ap.add_argument('--record', action='store_true')
    a = ap.parse_args()
    ok = run({'kr': nl.Data(a.kr), 'jp': nl.Data(a.jp)}, a.repo, os.path.join(HERE, 'selfcheck_text.csv'), 0.01,
             a.record)
    sys.exit(0 if ok or a.record else 1)


if __name__ == '__main__':
    main()
