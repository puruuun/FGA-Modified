#!/usr/bin/env python3
"""Decision logic for Main CI: classify changed files and evaluate the gate.

  ci_gate.py classify   reads NUL-separated paths on stdin, prints true or false
                        (true = the change needs the build).
  ci_gate.py classify-native   same input, prints true or false
                        (true = the change needs the Rust checks).
  ci_gate.py gate CHANGES CODE NATIVE BUILD RUST   exits 0 only for an acceptable combination.

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


# Must equal the paths: filter of native-ci.yml (test_ci_gate.py compares them): the gate runs the
# Rust checks for exactly the changes that used to trigger Native CI.
NATIVE_PATTERNS = [
    'native/**',
    '.github/workflows/native-ci.yml',
]

_DOC_REGEXES = [_glob_to_regex(p) for p in DOC_PATTERNS]
_NATIVE_REGEXES = [_glob_to_regex(p) for p in NATIVE_PATTERNS]


def is_doc_path(path):
    return any(r.match(path) for r in _DOC_REGEXES)


def is_native_path(path):
    return any(r.match(path) for r in _NATIVE_REGEXES)


def needs_build(paths):
    """True unless the list is non-empty and every path is documentation.

    A Rust path is never documentation, even when it matches a documentation pattern such as
    native/AGENTS.md: the gate fails code=false with native=true, so the two must not disagree.
    """
    try:
        paths = [p for p in paths if p != '']
        if not paths:
            return True
        return not all(is_doc_path(p) and not is_native_path(p) for p in paths)
    except Exception:
        return True


def needs_native(paths):
    """True when any path is a Rust path, or when the list is empty or cannot be read."""
    try:
        paths = [p for p in paths if p != '']
        if not paths:
            return True
        return any(is_native_path(p) for p in paths)
    except Exception:
        return True


def gate_ok(changes, code, native, build, rust):
    """Pass table (anything not listed fails):

      changes  code   native  build    rust      result
      success  true   true    success  success   pass
      success  true   false   success  success   pass  (rust is not expected, but green is fine)
      success  true   false   success  skipped   pass
      success  false  false   success  success   pass  (as before: build ran although not needed)
      success  false  false   success  skipped   pass
      success  false  false   skipped  skipped   pass  (documentation only)
      success  false  false   skipped  success   pass
      success  false  true    any      any       FAIL  (a Rust path is never documentation)
      success  true   true    any      !success  FAIL  (failure, cancelled, skipped, empty)
      !success any    any     any      any       FAIL
      code or native not exactly true/false     FAIL
    """
    if changes != 'success':
        return False
    if code not in ('true', 'false') or native not in ('true', 'false'):
        return False
    if build == 'success':
        build_ok = True
    else:
        build_ok = code == 'false' and build == 'skipped'
    if not build_ok:
        return False
    if native == 'true':
        return code == 'true' and rust == 'success'
    return rust in ('skipped', 'success')


def main(argv):
    if len(argv) == 2 and argv[1] in ('classify', 'classify-native'):
        check = needs_build if argv[1] == 'classify' else needs_native
        try:
            data = sys.stdin.buffer.read().decode('utf-8', errors='surrogateescape')
            result = check(data.split('\0'))
        except Exception:
            result = True
        print('true' if result else 'false')
        return 0
    if len(argv) == 7 and argv[1] == 'gate':
        _, _, changes, code, native, build, rust = argv
        print('changes=%s code=%s native=%s test-and-build=%s native-checks=%s'
              % (changes, code, native, build, rust))
        ok = gate_ok(changes, code, native, build, rust)
        print('Main CI gate: ' + ('pass' if ok else 'FAIL'))
        return 0 if ok else 1
    print('usage: ci_gate.py classify | classify-native | gate CHANGES CODE NATIVE BUILD RUST',
          file=sys.stderr)
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv))
