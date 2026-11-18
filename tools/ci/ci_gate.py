#!/usr/bin/env python3
"""Decision logic for Main CI: classify changed files and evaluate the gate.

  ci_gate.py classify   reads NUL-separated paths on stdin, prints true or false
                        (true = the change needs the build).
  ci_gate.py gate CHANGES CODE BUILD   exits 0 only for an acceptable combination.

Every error path must lead to "true" / failure, never to a skipped build.
"""
import re
import sys

# Same list and same glob meaning as the former paths-ignore of main-ci.yml.
DOC_PATTERNS = [
    '.idea/**',
    '.github/instructions/**',
    '.github/copilot-instructions.md',
    '.gitattributes',
    '.gitignore',
    '*.md',
    'LICENSE',
    'custom-game-area/**',
    'wiki/**',
    'docs/**',
    '.claude/**',
    '**/AGENTS.md',
]


def _glob_to_regex(pattern):
    out = []
    i = 0
    while i < len(pattern):
        if pattern.startswith('**/', i):
            out.append('(?:.*/)?')
            i += 3
        elif pattern.startswith('**', i):
            out.append('.*')
            i += 2
        elif pattern[i] == '*':
            out.append('[^/]*')
            i += 1
        else:
            out.append(re.escape(pattern[i]))
            i += 1
    return re.compile('(?s)' + ''.join(out) + r'\Z')


_DOC_REGEXES = [_glob_to_regex(p) for p in DOC_PATTERNS]


def is_doc_path(path):
    return any(r.match(path) for r in _DOC_REGEXES)


def needs_build(paths):
    """True unless the list is non-empty and every path is documentation."""
    try:
        paths = [p for p in paths if p != '']
        if not paths:
            return True
        return not all(is_doc_path(p) for p in paths)
    except Exception:
        return True


def gate_ok(changes, code, build):
    if changes != 'success':
        return False
    if build == 'success':
        return code in ('true', 'false')
    return code == 'false' and build == 'skipped'


def main(argv):
    if len(argv) >= 2 and argv[1] == 'classify':
        try:
            data = sys.stdin.buffer.read().decode('utf-8', errors='surrogateescape')
            result = needs_build(data.split('\0'))
        except Exception:
            result = True
        print('true' if result else 'false')
        return 0
    if len(argv) == 5 and argv[1] == 'gate':
        _, _, changes, code, build = argv
        print('changes=%s code=%s test-and-build=%s' % (changes, code, build))
        ok = gate_ok(changes, code, build)
        print('Main CI gate: ' + ('pass' if ok else 'FAIL'))
        return 0 if ok else 1
    print('usage: ci_gate.py classify | gate CHANGES CODE BUILD', file=sys.stderr)
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv))
