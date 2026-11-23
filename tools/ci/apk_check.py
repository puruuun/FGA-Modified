#!/usr/bin/env python3
"""Checks whether an APK carries the Rust engine, in the mode the caller names (design 0026, D-8).

  apk_check.py absent APK...    every APK is a readable zip without a libfga_ffi.so entry and
                                without an .onnx entry (a build without the engine: the ci APK).
  apk_check.py present APK...   every APK holds libfga_ffi.so for both shipped ABIs, the pinned OCR
                                model, its notice and the third-party notices, and its arm64-v8a
                                library is 16 KB page aligned: every ELF LOAD segment aligned to
                                0x4000 and, when the library is stored uncompressed, its data at a
                                16 KB boundary of the APK (a beta, release or rust APK).

There is no default mode: an APK checked for the wrong kind of build must fail, not pass. A missing
argument, an unknown mode or an unreadable file fails: an error never counts as a pass.
"""
import os
import struct
import sys
import zipfile

LIBRARY = 'libfga_ffi.so'
MODEL_EXTENSION = '.onnx'
# The ABIs of shippedAbis in app/build.gradle.kts.
SHIPPED_ABIS = ['arm64-v8a', 'armeabi-v7a']
PAGE = 0x4000
PIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'native', 'ocr-model', 'model.properties')
NOTICES = 'assets/ocr/THIRD_PARTY_NOTICES.txt'


def forbidden_entries(names):
    # Whole file name only: libfga_ffi.so.txt and xlibfga_ffi.so are other files and pass. Any
    # .onnx file is refused, not only the pinned name, so a renamed or second model is caught too.
    return [n for n in names
            if n == LIBRARY or n.endswith('/' + LIBRARY) or n.casefold().endswith(MODEL_EXTENSION)]


def pinned_model(pin_path=PIN):
    with open(pin_path, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if line.startswith('file='):
                name = line[len('file='):].strip()
                if name.endswith(MODEL_EXTENSION) and '/' not in name:
                    return name
    raise ValueError('%s names no model file' % pin_path)


def required_entries(model):
    return (['lib/%s/%s' % (abi, LIBRARY) for abi in SHIPPED_ABIS] +
            ['assets/ocr/' + model, 'assets/ocr/' + model[:-len(MODEL_EXTENSION)] + '.NOTICE.txt', NOTICES])


def load_alignments(elf):
    """p_align of every PT_LOAD segment of a 64-bit little-endian ELF file."""
    if len(elf) < 64 or elf[:4] != b'\x7fELF':
        raise ValueError('not an ELF file')
    if elf[4] != 2 or elf[5] != 1:
        raise ValueError('not a 64-bit little-endian ELF file')
    phoff, = struct.unpack_from('<Q', elf, 0x20)
    phentsize, phnum = struct.unpack_from('<HH', elf, 0x36)
    if phentsize < 56 or phoff + phentsize * phnum > len(elf):
        raise ValueError('program headers out of range')
    aligns = []
    for i in range(phnum):
        base = phoff + i * phentsize
        p_type, = struct.unpack_from('<I', elf, base)
        if p_type == 1:  # PT_LOAD
            p_align, = struct.unpack_from('<Q', elf, base + 48)
            aligns.append(p_align)
    if not aligns:
        raise ValueError('no LOAD segment')
    return aligns


def data_offset(path, info):
    """Where the entry's data starts in the file: its local header plus name and extra field."""
    with open(path, 'rb') as f:
        f.seek(info.header_offset)
        header = f.read(30)
    if len(header) != 30 or header[:4] != b'PK\x03\x04':
        raise ValueError('bad local header for %s' % info.filename)
    name_len, extra_len = struct.unpack_from('<HH', header, 26)
    return info.header_offset + 30 + name_len + extra_len


def check_absent(path, z):
    return ['%s: contains %s' % (path, n) for n in forbidden_entries(z.namelist())]


def check_present(path, z, model):
    names = set(z.namelist())
    problems = ['%s: lacks %s' % (path, n) for n in required_entries(model) if n not in names]
    lib = 'lib/arm64-v8a/' + LIBRARY
    if lib in names:
        info = z.getinfo(lib)
        try:
            aligns = load_alignments(z.read(lib))
            bad = sorted({a for a in aligns if a % PAGE != 0})
            if bad:
                problems.append('%s: %s has LOAD segments aligned to %s, not 0x4000'
                                % (path, lib, ', '.join(hex(a) for a in bad)))
            if info.compress_type == zipfile.ZIP_STORED:
                offset = data_offset(path, info)
                if offset % PAGE != 0:
                    problems.append('%s: %s is stored at offset %d, not on a 16 KB boundary' % (path, lib, offset))
        except (ValueError, struct.error, zipfile.BadZipFile, OSError) as e:
            problems.append('%s: cannot read %s: %s' % (path, lib, e))
    return problems


def check_apk(path, mode, model=None):
    """Returns a list of problems for one APK; empty means it is fine."""
    try:
        with zipfile.ZipFile(path) as z:
            if mode == 'absent':
                return check_absent(path, z)
            return check_present(path, z, model)
    except (OSError, zipfile.BadZipFile) as e:
        return ['%s: not a readable zip (%s)' % (path, e)]


def main(argv):
    if len(argv) < 2 or argv[1] not in ('absent', 'present'):
        print('apk_check: FAIL: the first argument must be the mode, absent or present', file=sys.stderr)
        return 1
    mode = argv[1]
    paths = argv[2:]
    if not paths:
        print('apk_check: FAIL: no APK path given', file=sys.stderr)
        return 1
    model = None
    if mode == 'present':
        try:
            model = pinned_model()
        except (OSError, ValueError) as e:
            print('apk_check: FAIL: %s' % e, file=sys.stderr)
            return 1
    problems = []
    for p in paths:
        problems.extend(check_apk(p, mode, model))
    for line in problems:
        print('apk_check: FAIL: ' + line)
    if not problems:
        if mode == 'absent':
            print('apk_check: %d APK(s) without %s or a %s model' % (len(paths), LIBRARY, MODEL_EXTENSION))
        else:
            print('apk_check: %d APK(s) with %s for %s, %s, its notice and the third-party notices; '
                  'arm64-v8a 16 KB aligned' % (len(paths), LIBRARY, ' and '.join(SHIPPED_ABIS), model))
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
