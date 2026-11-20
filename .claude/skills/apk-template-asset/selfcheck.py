#!/usr/bin/env python3
"""Replay the numbers this skill's rules were accepted on, from your own extraction of the KR and JP APKs.

1. Sprite sizes (selfcheck_sizes.csv): 90 KR elements (the rule was learned on them) and 106 JP elements (held out),
   each a widget identified by its scene file, path id and Transform path, with the size it was measured at on a
   screenshot of docs/reference-images (free size sweep, weighted NCC on the 720p game area). The rule's prediction
   is "right" within 2 px or 3 %. Expected: KR 89/90 and JP 97/106 (the old fixed x1.25: 74/90 and 79/106).
2. Text (selfcheck_text.csv): labels drawn by ngui_render.render_label from their decoded UILabel settings and found
   on a named reference screenshot near their data position; the alpha-weighted score must be within 0.01 of the
   recorded one.

A different game build moves path ids and sizes; then the per-element lines say what changed.

    selfcheck.py --kr build/apk-kr --jp build/apk-jp [--repo .]

The KR and JP folders are extract_apk_ui.py outputs of the build these numbers were taken on (KR 8.1.0 and the JP
build of the same week, 2026-11).
"""
import argparse
import csv
import os
import sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ngui_layout as nl  # noqa: E402

SIZE_OK = 2.0
SIZE_REL = 0.03
EXPECTED = {'kr': (89, 90, 74), 'jp': (97, 106, 79)}
TEXT_TOLERANCE = 0.01


def size_right(d, v):
    return abs(d) <= max(SIZE_OK, SIZE_REL * v)


def find_widget(data, row):
    w = data.by_key.get((row['file'], int(row['pid'])))
    if w is not None and w['path'] == row['path']:
        return w, ''
    sprite = row['element'].split('/')[-1]
    same = [x for x in data.widgets if x['path'] == row['path'] and x.get('sprite') == sprite]
    if len(same) == 1:
        return same[0], 'found by path (path id changed)'
    return None, 'not in this build (%d widgets on that path)' % len(same)


def check_sizes(server, data, rows, verbose):
    rule = nl.Rule(data)
    n = ok = old = 0
    misses = []
    for r in rows:
        w, note = find_widget(data, r)
        if w is None:
            misses.append('  %s %s: %s' % (server, r['element'], note))
            continue
        p = rule.predict(w)
        _, s = data.sprite_data(w)
        mw, mh = float(r['meas_w']), float(r['meas_h'])
        idw, idh = r['w_identified'] == '1', r['h_identified'] == '1'
        if p['long_axis_free'] == 'w':
            idw = False
        if p['long_axis_free'] == 'h':
            idh = False
        dw, dh = mw - p['W'], mh - p['H']
        good = all(size_right(d, v) for d, v, i in ((dw, mw, idw), (dh, mh, idh)) if i)
        sw, sh = s['w'] * 1.25, s['h'] * 1.25
        good_old = all(size_right(d, v) for d, v, i in ((mw - sw, mw, idw), (mh - sh, mh, idh)) if i)
        n += 1
        ok += good
        old += good_old
        if not good or verbose:
            misses.append('  %s %-4s %-44s measured %6.1f x %-6.1f predicted %6.1f x %-6.1f %s %s' % (
                'ok  ' if good else 'MISS', p['branch'], r['element'][:44], mw, mh, p['W'], p['H'],
                r['path'][-60:], note))
    return n, ok, old, misses


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--kr', required=True, help='extract_apk_ui.py output of the KR APK')
    ap.add_argument('--jp', required=True, help='extract_apk_ui.py output of the JP APK')
    ap.add_argument('--repo', default=os.path.abspath(os.path.join(HERE, '..', '..', '..')),
                    help='repository root (for docs/reference-images); default: the one holding this skill')
    ap.add_argument('--skip-text', action='store_true')
    ap.add_argument('-v', '--verbose', action='store_true', help='print every element, not only the misses')
    a = ap.parse_args()

    with open(os.path.join(HERE, 'selfcheck_sizes.csv'), encoding='utf-8') as f:
        rows = list(csv.DictReader(f))
    datas = {'kr': nl.Data(a.kr), 'jp': nl.Data(a.jp)}
    failed = False
    print('Sprite sizes (right = within %.0f px or %.0f %%):' % (SIZE_OK, SIZE_REL * 100))
    for server in ('kr', 'jp'):
        n, ok, old, lines = check_sizes(server, datas[server], [r for r in rows if r['server'] == server], a.verbose)
        exp_ok, exp_n, exp_old = EXPECTED[server]
        verdict = 'as recorded' if (ok, n, old) == (exp_ok, exp_n, exp_old) else 'DIFFERS (recorded %d/%d, x1.25 %d)' % (
            exp_ok, exp_n, exp_old)
        failed |= (ok, n) != (exp_ok, exp_n)
        print('  %s %s: rule %d/%d, old x1.25 %d/%d  -> %s' % (
            server.upper(), 'training' if server == 'kr' else 'held out', ok, n, old, n, verdict))
        for line in lines:
            print(line)

    if not a.skip_text:
        import text_check  # noqa: E402 - needs OpenCV and the reference screenshots, sizes do not
        failed |= not text_check.run(datas, a.repo, os.path.join(HERE, 'selfcheck_text.csv'), TEXT_TOLERANCE)
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
