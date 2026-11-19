#!/usr/bin/env python3
"""Fail when the normal APK contains the Rust library or a model (design 0025, D-6, D-7).

  apk_check.py APK...   exit 0 only when every APK is a readable zip without a libfga_ffi.so entry
                        and without an .onnx entry.

The library and the OCR model it reads (issue #568, assets/ocr/PP-OCRv6_rec_small.onnx) belong only
in a build made with the fga.native switch; the APK of Main CI must not carry either. A missing
argument or an unreadable file fails: an error never counts as a pass.
"""
import sys
import zipfile

LIBRARY = 'libfga_ffi.so'


MODEL_EXTENSION = '.onnx'


def forbidden_entries(names):
    # Whole file name only: libfga_ffi.so.txt and xlibfga_ffi.so are other files and pass. Any
    # .onnx file is refused, not only the pinned name, so a renamed or second model is caught too.
    return [n for n in names
            if n == LIBRARY or n.endswith('/' + LIBRARY) or n.casefold().endswith(MODEL_EXTENSION)]


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
        print('apk_check: %d APK(s) without %s or a %s model' % (len(paths), LIBRARY, MODEL_EXTENSION))
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
