#!/usr/bin/env python3
"""Cross-server acceptance replay: draft every KR template from KR data plus ONE JP screenshot, and every JP template
from JP data plus one KR screenshot, freeze the drafts (sha256 list) before any scoring, then score them on the
target server's own reference screenshots against the existing templates and the code's threshold.

Element choice uses only the other server: its template marks the box on its best 16:9 screenshot (the one used to
locate and to decide N/W); cross_server_cases.json adds the text a label shows there (`shown`) and, for texts that
come from server data, the target text (`target_text`). The only thing taken from the target template is the flat
background colour of its 1 px border where no widget is drawn (reported per row).

    cross_server.py predict --kr build/apk-kr --jp build/apk-jp --work build/cross   (writes pred/, FROZEN.txt)
    cross_server.py score   --kr build/apk-kr --jp build/apk-jp --work build/cross   (checks FROZEN.txt first)

Writes <work>/acceptance.csv and prints one row per draft and the totals per server and element class.

--named runs the same replay with the element named instead of located: cross_server_cases.json "named" holds, per
template and per screenshot server, the widget path (and the sprite code sets, the text shown there and the target
text) chosen from that server's data, template and one screenshot only, or "art" where the element is not drawn
from the APK. The screenshot still gives the crop margin, the sprites under the element and the N/W decision.
Its files are pred_named/ (with its own FROZEN.txt), scores_named.json and acceptance_named.csv.
"""
import argparse
import collections
import contextlib
import csv
import hashlib
import io
import json
import os
import sys
from multiprocessing import Pool

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import screen_score as ss  # noqa: E402

R = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
OTHER = {'Kr': 'Jp', 'Jp': 'Kr'}
with open(os.path.join(HERE, 'cross_server_cases.json'), encoding='utf-8') as _f:
    _CASES = json.load(_f)
NAMES = _CASES['templates']
SPEC = _CASES['cases']
NAMED_SPEC = _CASES['named']
DATA, WORK, NAMED = {}, None, False


def configure(a):
    global WORK, PRED, NAMED, SUFFIX
    DATA.update({'Kr': os.path.abspath(a.kr), 'Jp': os.path.abspath(a.jp)})
    WORK = os.path.abspath(a.work)
    NAMED = a.named
    PRED = os.path.join(WORK, 'pred_named' if NAMED else 'pred')
    SUFFIX = '_named' if NAMED else ''
    os.environ['CROSS_SERVER_ARGS'] = json.dumps([DATA['Kr'], DATA['Jp'], WORK, NAMED])


if os.environ.get('CROSS_SERVER_ARGS'):  # pool workers on Windows re-import this module
    _k, _j, WORK, NAMED = json.loads(os.environ['CROSS_SERVER_ARGS'])
    DATA.update({'Kr': _k, 'Jp': _j})
PRED = os.path.join(WORK, 'pred_named' if NAMED else 'pred') if WORK else None
SUFFIX = '_named' if NAMED else ''


def asset(server, n):
    return os.path.join(R, 'app', 'src', 'main', 'assets', server, n + '.png')


def pick_shot(server, n):
    """The other server's screenshot used for this element: its own template's best 16:9 positive."""
    thr, _ = ss.threshold_from_code(n + '.png', R)
    t = ss.read_gray(asset(server, n))
    best = None
    for name, g in ss.load_screens(os.path.join(R, 'docs', 'reference-images', server), os.path.join(WORK, 'cache')):
        b = ss.best_in(g, t)
        if b is None or b[0] < thr:
            continue
        key = (g.shape[1] == 1280, b[0])
        if best is None or key > best[0]:
            best = (key, name)
    return best[1] if best else None


def init():
    import draft_element  # noqa: F401
    global DE
    DE = draft_element
    for d in DATA.values():
        DE.load_data(d)


def predict(job):
    target, n = job
    other = OTHER[target]
    shot = pick_shot(other, n)
    out = io.StringIO()
    rec = {'target': target, 'template': n, 'shot': None if shot is None else other + '/' + shot}
    if shot is None:
        rec['error'] = 'no positive screenshot of %s' % other
        return rec
    args = ['--data', DATA[target], '--locate-on', os.path.join(R, 'docs', 'reference-images', other, shot),
            '--locate-data', DATA[other], '--locate-template', asset(other, n),
            '--out-dir', os.path.join(PRED, target), '--name', n, '--background-from', asset(target, n)]
    if NAMED:
        named = NAMED_SPEC[n][other]
        rec['named'] = named
        if 'art' in named:
            rec['error'] = 'not in the APK: ' + named['art']
            rec['art'] = True
            return rec
        args += ['--locate-path', named['path']]
        if named.get('sprite'):
            args += ['--locate-sprite', named['sprite']]
        shown, tt = named.get('shown'), named.get('target_text')
    else:
        args += SPEC.get(n, {}).get('args', [])
        shown = SPEC.get(n, {}).get('shown', {}).get(other)
        tt = SPEC.get(n, {}).get('target_text', {}).get(target)
    if shown:
        args += ['--shown-text', shown]
    if tt:
        args += ['--target-text', tt]
        rec['target_text_given'] = tt
    try:
        with contextlib.redirect_stdout(out):
            rep = DE.main(args)
        rec['report'] = rep
    except SystemExit as e:
        rec['error'] = str(e)
    except Exception as e:  # noqa: BLE001
        import traceback
        rec['error'] = traceback.format_exc()
    rec['log'] = out.getvalue()
    return rec


def do_predict(only=None):
    jobs = [(t, n) for n in NAMES for t in ('Kr', 'Jp') if not only or n in only]
    os.makedirs(PRED, exist_ok=True)
    recs = []
    with Pool(4, initializer=init) as p:
        for rec in p.imap_unordered(predict, jobs):
            recs.append(rec)
            print('%s %-32s %s' % (rec['target'], rec['template'], rec.get('error', '').strip().splitlines()[-1:] or
                                   [d['file'] for d in rec['report']['drafts']]), flush=True)
    old = {}
    mp = os.path.join(PRED, 'predictions.json')
    if only and os.path.exists(mp):
        old = {(r['target'], r['template']): r for r in json.load(open(mp, encoding='utf-8'))}
    for r in recs:
        old[(r['target'], r['template'])] = r
    recs = list(old.values())
    json.dump(recs, open(mp, 'w', encoding='utf-8'), ensure_ascii=False, indent=1, default=str)
    # freeze: hashes of every draft before any scoring
    with open(os.path.join(PRED, 'FROZEN.txt'), 'w', encoding='utf-8') as f:
        for r in sorted(recs, key=lambda r: (r['target'], r['template'])):
            for d in (r.get('report') or {}).get('drafts', []):
                f.write('%s  %s\n' % (hashlib.sha256(open(d['file'], 'rb').read()).hexdigest(),
                                       os.path.relpath(d['file'], PRED).replace(os.sep, '/')))
    print('frozen', sum(len((r.get('report') or {}).get('drafts', [])) for r in recs), 'drafts')




def element_class(rep):
    kinds = set()
    for w in rep.get('widgets', []):
        info = w.get('info') or {}
        if 'uifont' in info:
            kinds.add('number')
        elif 'text' in info:
            kinds.add('text')
        elif 'sprite' in info:
            kinds.add('sprite')
    if len(kinds) > 1:
        return 'mixed'
    return kinds.pop() if kinds else '?'


AS_GOOD = 0.02


def grade(d, e):
    if d['true_min'] is None:
        return 'fails the threshold'
    if d['margin_true'] < 0 or (d['margin_false'] is not None and d['margin_false'] <= 0):
        return 'fails the threshold'
    dm = min(d['margin_true'], d['margin_false'] if d['margin_false'] is not None else 1)
    em = min(e['margin_true'], e['margin_false'] if e['margin_false'] is not None else 1)
    return 'as good as existing' if dm >= em - AS_GOOD else 'passes but lower'


def score_job(job):
    rec = job
    target, n = rec['target'], rec['template']
    thr, calls = ss.threshold_from_code(n + '.png', R)
    screens = ss.load_screens(os.path.join(R, 'docs', 'reference-images', target), os.path.join(WORK, 'cache'))
    ref = ss.read_gray(asset(target, n))
    roles, _ = ss.classify(screens, ref, thr)
    e, _ = ss.score_template(ref, screens, roles, thr)
    out = {'target': target, 'template': n, 'threshold': thr, 'existing': e, 'shot': rec.get('shot'), 'drafts': []}
    rep = rec.get('report')
    if not rep or not rep.get('drafts'):
        out['grade'] = 'not in the APK' if rec.get('art') else 'cannot build'
        out['why'] = (rec.get('error') or '').strip().splitlines()[-1:] or ['']
        return out
    if rec.get('named'):
        out['located'] = 'no widget matching' not in rec.get('log', '')
    out['class'] = element_class(rep)
    out['notes'] = rep.get('notes', [])
    out['elements'] = [(w['role'], w['path']) for w in rep['widgets']]
    for d in rep['drafts']:
        t = ss.read_gray(d['file'])
        s, _ = ss.score_template(t, screens, roles, thr, None, ref.shape)
        out['drafts'].append({'variant': d['variant'], 'scores': s, 'grade': grade(s, e),
                              'bg_from_border': d['uncovered'] > 0.01, 'size': d['size'], 'file': d['file']})
    out['grade'] = out['drafts'][0]['grade']
    return out


def do_score():
    recs = json.load(open(os.path.join(PRED, 'predictions.json'), encoding='utf-8'))
    # the drafts must be the frozen ones
    frozen = dict(l.split('  ')[::-1] for l in open(os.path.join(PRED, 'FROZEN.txt'), encoding='utf-8').read().splitlines())
    for r in recs:
        for d in (r.get('report') or {}).get('drafts', []):
            rel = os.path.relpath(d['file'], PRED).replace(os.sep, '/')
            assert hashlib.sha256(open(d['file'], 'rb').read()).hexdigest() == frozen[rel], rel
    with Pool(6) as p:
        res = list(p.imap(score_job, sorted(recs, key=lambda r: (r['template'], r['target']))))
    json.dump(res, open(os.path.join(WORK, 'scores%s.json' % SUFFIX), 'w', encoding='utf-8'), ensure_ascii=False, indent=1,
              default=str)
    f3 = ss.fmt
    rows = []
    for r in res:
        e = r['existing']
        if not r['drafts']:
            rows.append([r['template'], r['target'], '-', r['grade'], '', '', '', '%.2f' % r['threshold'],
                         '', '', f3(e['true_min']), f3(e['fp_max']), '', r.get('why', [''])[0][:90], r['shot']])
            continue
        for i, d in enumerate(r['drafts']):
            s = d['scores']
            rows.append([r['template'], r['target'], r['class'], d['grade'] + ('' if i == 0 else ' (alt %s)' % d['variant']),
                         s['positives'], f3(s['true_min']), f3(s['fp_max']), '%.2f' % r['threshold'],
                         f3(s['margin_true']), f3(s['margin_false']), f3(e['true_min']), f3(e['fp_max']),
                         'border bg' if d['bg_from_border'] else '',
                         '; '.join(([] if r.get('located', True) else ['named widget not found on the screenshot']) +
                                   [x[-90:] for x in r['notes']]) if i == 0 else '',
                         r['shot']])
    with open(os.path.join(WORK, 'acceptance%s.csv' % SUFFIX), 'w', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        w.writerow(['template', 'target', 'class', 'grade', 'positives', 'draft_true_min', 'draft_fp_max', 'threshold',
                    'margin_true', 'margin_false', 'existing_true_min', 'existing_fp_max', 'background', 'notes',
                    'other_server_screenshot'])
        w.writerows(rows)
    for row in rows:
        print(' | '.join(str(v) for v in row[:13]))
    grades = ('as good as existing', 'passes but lower', 'fails the threshold', 'cannot build') + \
        (('not in the APK',) if NAMED else ())
    tot = collections.Counter()
    for r in res:
        tot[(r['target'], r.get('class', '-'), r['grade'])] += 1
        tot[(r['target'], 'all', r['grade'])] += 1
    print('totals (first draft of each template; %s)' % ' / '.join(grades))
    for t in ('Kr', 'Jp'):
        for k in ('all', 'text', 'sprite', 'number', 'mixed', '-'):
            n = [tot[(t, k, g)] for g in grades]
            if sum(n):
                print('  %s %-6s %s  (%d)' % (t, k, ' / '.join(str(v) for v in n), sum(n)))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('step', choices=('predict', 'score'))
    ap.add_argument('--kr', required=True)
    ap.add_argument('--jp', required=True)
    ap.add_argument('--work', required=True)
    ap.add_argument('--only', nargs='*', help='template names (predict only)')
    ap.add_argument('--named', action='store_true', help='name the element (cases "named") instead of locating it')
    a = ap.parse_args()
    configure(a)
    if a.step == 'predict':
        do_predict(a.only or None)
    else:
        do_score()


if __name__ == '__main__':
    main()
