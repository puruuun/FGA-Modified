#!/usr/bin/env python3
"""Refuse model files, dumps, oversized files and build output in a change.

  repo_guard.py [--allow FILE] [--only FILE] [--label TEXT]   reads `git ls-tree -r -l -z <head commit>` on stdin.

The listing comes from the committed head commit, so sizes are those of the blobs, not of a
working tree after a checkout filter. With --only FILE (NUL-separated paths, as printed by
`git diff --name-only -z --diff-filter=d`) only those paths are checked; paths that are not in the
listing (deleted files) are ignored. Without --only the whole tree is checked.

Exit 0 when nothing is refused, 1 when something is refused or the input cannot be trusted,
2 for a usage error. Every error path ends in failure, never in a silent pass.
"""
import os
import subprocess
import sys
import unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ci_gate  # noqa: E402  (reuses the glob meaning of the doc patterns)

# Model and dump files: large, opaque and not reviewable, and some can carry captured traffic.
MODEL_EXTENSIONS = {
    '.onnx': 'ONNX model',
    '.ort': 'ONNX Runtime model',
    '.tflite': 'TensorFlow Lite model',
    '.pt': 'PyTorch model',
    '.pth': 'PyTorch model',
    '.safetensors': 'model weights',
    '.pb': 'TensorFlow protobuf model',
    '.traineddata': 'Tesseract language data',
    '.pcap': 'packet capture (may hold account traffic)',
    '.pcapng': 'packet capture (may hold account traffic)',
    '.har': 'HTTP archive (may hold account traffic)',
}
# A file over this size makes every clone bigger for good, because history keeps it.
MAX_BYTES = 5 * 1024 * 1024
# Build output belongs to the build, never to the repository; no allow-list entry can change that.
FORBIDDEN_DIRS = ('build', 'target')

DEFAULT_ALLOW_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'large-files-allow.txt')
HINT = ('add the path to tools/ci/large-files-allow.txt in this PR '
        'if the planner said this file is to be committed')


class GuardError(Exception):
    pass


def parse_allow_list(text):
    patterns = []
    for line in text.splitlines():
        line = line.strip()
        if line and not line.startswith('#'):
            patterns.append(line)
    return patterns


def load_allow_list(path):
    try:
        with open(path, 'rb') as f:
            return parse_allow_list(f.read().decode('utf-8'))
    except (OSError, UnicodeDecodeError) as e:
        raise GuardError('cannot read the allow-list %s: %s' % (path, e))


def parse_ls_tree(data):
    """Parse `git ls-tree -r -l -z` output into [(path, size)]; raise GuardError when malformed."""
    entries = []
    for record in data.split(b'\0'):
        if record == b'':
            continue
        meta, sep, raw_path = record.partition(b'\t')
        fields = meta.split()
        if not sep or len(fields) != 4 or raw_path == b'':
            raise GuardError('malformed ls-tree record: %r' % record[:200])
        _mode, kind, _sha, size = fields
        path = raw_path.decode('utf-8', errors='surrogateescape')
        if kind == b'commit':  # a submodule has no size
            entries.append((path, 0))
            continue
        if kind != b'blob' or not size.isdigit():
            raise GuardError('malformed ls-tree record: %r' % record[:200])
        entries.append((path, int(size)))
    return entries


def parse_only(data):
    return {p.decode('utf-8', errors='surrogateescape') for p in data.split(b'\0') if p}


def refusal_reasons(path, size, allow_regexes):
    """Reasons to refuse one file; empty when it may be committed."""
    parts = path.split('/')
    # Case-insensitive: Build/ and TARGET/ are the same output on a case-insensitive checkout.
    if any(d.casefold() in FORBIDDEN_DIRS for d in parts[:-1]):
        return ['build output (a build/ or target/ directory) is never committed']
    if any(r.match(path) for r in allow_regexes):
        return []
    reasons = []
    name = parts[-1].casefold()
    for ext, what in MODEL_EXTENSIONS.items():
        if name.endswith(ext):
            reasons.append('%s file (%s)' % (ext, what))
    if size > MAX_BYTES:
        reasons.append('%d bytes is over the %d byte limit' % (size, MAX_BYTES))
    return reasons


def regexes_of(patterns):
    return [ci_gate._glob_to_regex(p) for p in patterns]


def check_files(entries, allow_patterns):
    """entries: [(path, size)]. Returns [(path, [reason, ...])] for the refused files."""
    regexes = [ci_gate._glob_to_regex(p) for p in allow_patterns]
    refused = []
    for path, size in entries:
        reasons = refusal_reasons(path, size, regexes)
        if reasons:
            refused.append((path, reasons))
    return refused


def printable(text):
    # Escape only what can forge or break a log line (control characters including CR, LF and ESC,
    # Unicode line and paragraph separators, undecodable bytes); a Korean or Japanese name stays readable.
    out = []
    for c in text:
        o = ord(c)
        if 0xDC80 <= o <= 0xDCFF:  # a byte that was not valid UTF-8 (surrogateescape)
            out.append(chr(92) + 'x%02x' % (o - 0xDC00))
        elif unicodedata.category(c) in ('Cc', 'Zl', 'Zp', 'Cs'):
            out.append(chr(92) + ('x%02x' % o if o < 256 else 'u%04x' % o))
        else:
            out.append(c)
    return ''.join(out)


def emit(text):
    # Bytes, not str: the console encoding of the runner must not decide whether a name can be printed.
    sys.stdout.buffer.write((text + chr(10)).encode('utf-8', 'backslashreplace'))
    sys.stdout.buffer.flush()


NEWLINE = bytes([10])


def git_output(args, data):
    p = subprocess.run(['git'] + args, input=data, capture_output=True)
    if p.returncode != 0:
        raise GuardError('git %s failed: %s' % (args[0], p.stderr.decode('utf-8', 'replace').strip()))
    return p.stdout


def history_entries(commits):
    """(path, size, short hash) of every file that each commit itself adds or modifies.

    One git process lists the changes of all commits and one reads the blob sizes, so the cost does
    not grow with a process per commit. -c lists a merge only for what differs from every parent, so
    the files a clean merge brings in from the base are not listed again.
    """
    known = set(c.encode('ascii') for c in commits)
    out = git_output(['diff-tree', '--stdin', '-c', '--root', '-r', '--no-renames', '--name-only', '-z'],
                     ''.join(c + chr(10) for c in commits).encode('ascii'))
    current = None
    pairs = []
    for token in out.split(bytes([0])):
        if token == b'':
            continue
        # A header is the id of one of the commits; a path equal to the id of a commit that does not
        # exist yet cannot be written.
        if token in known:
            current = token.decode('ascii')
        elif current is None:
            raise GuardError('unexpected output of git diff-tree')
        else:
            pairs.append((current, token))
    if any(NEWLINE in path for _, path in pairs):
        raise GuardError('a path with a line break in the history cannot be checked')
    request = b''.join(c.encode('ascii') + b':' + path + NEWLINE for c, path in pairs)
    sizes = git_output(['cat-file', '--batch-check'], request).split(NEWLINE)
    if sizes and sizes[-1] == b'':
        sizes.pop()
    if len(sizes) != len(pairs):
        raise GuardError('git cat-file answered %d of %d questions' % (len(sizes), len(pairs)))
    entries = []
    for (c, path), answer in zip(pairs, sizes):
        fields = answer.split()
        if answer.endswith(b' missing'):  # deleted by that commit
            continue
        if len(fields) != 3 or fields[1] != b'blob' or not fields[2].isdigit():
            raise GuardError('unexpected answer of git cat-file: %r' % answer[:200])
        entries.append((path.decode('utf-8', errors='surrogateescape'), int(fields[2]), c[:7]))
    return entries


def annotation_escape(text):
    # A path may hold a newline; unescaped it would end the annotation and start another command.
    return text.replace('%', '%25').replace(chr(13), '%0D').replace(chr(10), '%0A')


def main(argv):
    allow_file = DEFAULT_ALLOW_FILE
    only_file = None
    label = ''
    history_file = None
    args = list(argv[1:])
    while args:
        flag = args.pop(0)
        if flag in ('--allow', '--only', '--label', '--history') and args:
            value = args.pop(0)
            if flag == '--allow':
                allow_file = value
            elif flag == '--only':
                only_file = value
            elif flag == '--label':
                label = value
            else:
                history_file = value
        else:
            print('usage: repo_guard.py [--allow FILE] [--only FILE] [--label TEXT] [--history FILE] < ls-tree-output', file=sys.stderr)
            return 2
    try:
        allow = load_allow_list(allow_file)
        if history_file is not None:
            try:
                with open(history_file, 'rb') as f:
                    commits = f.read().decode('ascii').split()
            except (OSError, UnicodeDecodeError) as e:
                raise GuardError('cannot read %s: %s' % (history_file, e))
            if any(len(c) != 40 or c.strip('0123456789abcdef') for c in commits):
                raise GuardError('the commit list holds something that is not a commit id')
            entries = history_entries(commits) if commits else []
        else:
            entries = [(p, n, label) for p, n in parse_ls_tree(sys.stdin.buffer.read())]
            if not entries:  # a failed git command leaves an empty listing; it must not count as a pass
                raise GuardError('the file listing is empty')
            if only_file is not None:
                try:
                    with open(only_file, 'rb') as f:
                        only = parse_only(f.read())
                except OSError as e:
                    raise GuardError('cannot read %s: %s' % (only_file, e))
                entries = [e for e in entries if e[0] in only]
        refused = []
        for path, size, where in entries:
            reasons = refusal_reasons(path, size, regexes_of(allow))
            if reasons:
                refused.append((path, reasons, where))
    except GuardError as e:
        emit('repo_guard: FAIL: ' + printable(str(e)))
        return 1
    except Exception as e:  # an unexpected error must not pass the check
        emit('repo_guard: FAIL: %s: %s' % (type(e).__name__, printable(str(e))))
        return 1
    for path, reasons, where in refused:
        shown = printable(path) + (' (in commit %s)' % printable(where) if where else '')
        if reasons[0].startswith('build output'):
            line = 'REFUSED %s: %s. The allow-list cannot allow it; keep the file local.' % (shown, reasons[0])
        else:
            line = 'REFUSED %s: %s. If it is meant to be committed, %s.' % (shown, '; '.join(reasons), HINT)
        emit(line)
        # A workflow annotation, so the cause of a failed gate is visible without opening the log.
        emit('::error title=repo_guard::' + annotation_escape(line))
    emit('repo_guard: checked %d file(s), %d refused' % (len(entries), len(refused)))
    return 1 if refused else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
