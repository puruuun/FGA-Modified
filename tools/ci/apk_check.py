#!/usr/bin/env python3
"""Checks whether an APK carries the Rust engine, in the mode the caller names (design 0026, D-8).

  apk_check.py absent APK...    every APK is a readable zip without a libfga_ffi.so entry and
                                without an .onnx entry (a build without the engine: Main CI's ci APK).
  apk_check.py present APK...   every APK holds each entry of app/engine-apk-entries.txt, the list
                                that the build's checkEngineApk<V> reads too (libfga_ffi.so for both
                                shipped ABIs, the pinned OCR model, its pin file, its notice and the
                                third-party notices), and its arm64-v8a library is 16 KB page
                                aligned: a 64-bit little-endian ELF file whose every LOAD segment is
                                aligned to a non-zero multiple of 0x4000 and, when the library is
                                stored uncompressed, whose data starts at a 16 KB boundary of the APK
                                (the beta, the rust APK and the stable ci APK built with
                                -Pfga.native=true).

There is no default mode: an APK checked for the wrong kind of build must fail, not pass. A missing
argument, an unknown mode or an unreadable file fails: an error never counts as a pass. A 32-bit,
big-endian or truncated arm64-v8a library fails with a message that names it, not with a traceback.
"""
import os
import struct
import sys
import zipfile

LIBRARY = 'libfga_ffi.so'
MODEL_EXTENSION = '.onnx'
PAGE = 0x4000
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')
PIN = os.path.join(ROOT, 'native', 'ocr-model', 'model.properties')
ENTRIES = os.path.join(ROOT, 'app', 'engine-apk-entries.txt')


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


def read_entry_list(text):
    """(abis, entry templates) of app/engine-apk-entries.txt; a line it cannot read is an error."""
    abis, templates = [], []
    for number, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        kind, _, value = line.partition(' ')
        value = value.strip()
        if kind not in ('abi', 'entry') or not value or ' ' in value:
            raise ValueError('line %d of the entry list is not "abi NAME" or "entry PATH": %r' % (number, raw))
        (abis if kind == 'abi' else templates).append(value)
    if not abis or not templates:
        raise ValueError('the entry list names no abi or no entry')
    return abis, templates


def expand_entries(abis, templates, model):
    stem = model[:-len(MODEL_EXTENSION)]
    out = []
    for template in templates:
        for abi in (abis if '{abi}' in template else [None]):
            entry = template.replace('{model_stem}', stem).replace('{model}', model)
            if abi is not None:
                entry = entry.replace('{abi}', abi)
            if '{' in entry or '}' in entry:
                raise ValueError('unknown placeholder in the entry list: %r' % template)
            out.append(entry)
    return out


def required_entries(model, entries_path=ENTRIES):
    with open(entries_path, encoding='utf-8') as f:
        abis, templates = read_entry_list(f.read())
    return expand_entries(abis, templates, model)


def load_alignments(elf):
    """p_align of every PT_LOAD segment of a 64-bit little-endian ELF file."""
    if len(elf) < 64 or elf[:4] != b'\x7fELF':
        raise ValueError('not an ELF file')
    if elf[4] != 2 or elf[5] != 1:
        raise ValueError('not a 64-bit little-endian ELF file (EI_CLASS %d, EI_DATA %d)' % (elf[4], elf[5]))
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


def bad_alignments(aligns):
    # p_align 0 and 1 mean "no alignment", and 0 is a multiple of everything.
    return sorted({a for a in aligns if a < PAGE or a % PAGE != 0})


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


def check_present(path, z, required):
    names = set(z.namelist())
    problems = ['%s: lacks %s' % (path, n) for n in required if n not in names]
    lib = 'lib/arm64-v8a/' + LIBRARY
    if lib in names:
        info = z.getinfo(lib)
        try:
            bad = bad_alignments(load_alignments(z.read(lib)))
            if bad:
                problems.append('%s: %s has LOAD segments aligned to %s, not 0x4000'
                                % (path, lib, ', '.join(hex(a) for a in bad)))
            if info.compress_type == zipfile.ZIP_STORED:
                offset = data_offset(path, info)
                if offset % PAGE != 0:
                    problems.append('%s: %s is stored at offset %d, not on a 16 KB boundary' % (path, lib, offset))
        except Exception as e:  # any error is a failed check with its reason, never a traceback
            problems.append('%s: cannot read %s: %s: %s' % (path, lib, type(e).__name__, e))
    return problems


def check_apk(path, mode, required=None):
    """Returns a list of problems for one APK; empty means it is fine."""
    try:
        with zipfile.ZipFile(path) as z:
            if mode == 'absent':
                return check_absent(path, z)
            return check_present(path, z, required)
    except Exception as e:
        return ['%s: not a readable zip (%s: %s)' % (path, type(e).__name__, e)]


def main(argv):
    if len(argv) < 2 or argv[1] not in ('absent', 'present'):
        print('apk_check: FAIL: the first argument must be the mode, absent or present', file=sys.stderr)
        return 1
    mode = argv[1]
    paths = argv[2:]
    if not paths:
        print('apk_check: FAIL: no APK path given', file=sys.stderr)
        return 1
    model = required = None
    if mode == 'present':
        try:
            model = pinned_model()
            required = required_entries(model)
        except (OSError, ValueError) as e:
            print('apk_check: FAIL: %s' % e, file=sys.stderr)
            return 1
    problems = []
    for p in paths:
        problems.extend(check_apk(p, mode, required))
    for line in problems:
        print('apk_check: FAIL: ' + line)
    if not problems:
        if mode == 'absent':
            print('apk_check: %d APK(s) without %s or a %s model' % (len(paths), LIBRARY, MODEL_EXTENSION))
        else:
            print('apk_check: %d APK(s) with the %d entries of app/engine-apk-entries.txt (%s); '
                  'arm64-v8a 16 KB aligned' % (len(paths), len(required), model))
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
