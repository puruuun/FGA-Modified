#!/usr/bin/env python3
"""Fail when the normal APK contains the Rust library (design 0025, D-6, D-7).

  apk_check.py APK...   exit 0 only when every APK is a readable zip without a libfga_ffi.so entry.

The library belongs only in a build made with the fga.native switch; the APK of Main CI must not
carry it. A missing argument or an unreadable file fails: an error never counts as a pass.
"""
import sys
import zipfile

LIBRARY = 'libfga_ffi.so'


def forbidden_entries(names):
    # Whole file name only: libfga_ffi.so.txt and xlibfga_ffi.so are other files and pass.
    return [n for n in names if n == LIBRARY or n.endswith('/' + LIBRARY)]


def check_apk(path):
    """Returns a list of problems for one APK; empty means it is fine."""
    try:
        with zipfile.ZipFile(path) as z:
            names = z.namelist()
    except (OSError, zipfile.BadZipFile) as e:
        return ['%s: not a readable zip (%s)' % (path, e)]
    return ['%s: contains %s' % (path, n) for n in forbidden_entries(names)]


def main(argv):
    paths = argv[1:]
    if not paths:
        print('apk_check: FAIL: no APK path given', file=sys.stderr)
        return 1
    problems = []
    for p in paths:
        problems.extend(check_apk(p))
    for line in problems:
        print('apk_check: FAIL: ' + line)
    if not problems:
        print('apk_check: %d APK(s) without %s' % (len(paths), LIBRARY))
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
