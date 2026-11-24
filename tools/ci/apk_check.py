#!/usr/bin/env python3
"""Checks what an APK carries, in the mode the caller names (design 0026, D-8 and PR C).

  apk_check.py absent APK...    every APK is a readable zip without the Rust engine (no libfga_ffi.so
                                entry, no .onnx entry) and without the support score scan (below):
                                Main CI's ci APK, and the emergency builds without the engine.
  apk_check.py stable APK...    every APK carries the engine (below) and not the score scan: the
                                stable ci APK built with -Pfga.native=true.
  apk_check.py beta APK...      every APK carries the engine and the score scan: the beta and the
                                rust APK.

Carrying the engine: the APK holds each entry of app/engine-apk-entries.txt, the list that the
build's checkEngineApk<V> reads too (libfga_ffi.so for both shipped ABIs, the pinned OCR model, its
pin file, its notice and the third-party notices), and its arm64-v8a library is 16 KB page aligned:
a 64-bit little-endian ELF file whose every LOAD segment is aligned to a non-zero multiple of 0x4000
and, when the library is stored uncompressed, whose data starts at a 16 KB boundary of the APK.
No libfga_ffi.so of a beta or stable APK exports the stage 1-0 crypto probes (design 0026 PR D):
fga-ffi's crypto-probes feature links ring, rustls and rcgen, which THIRD_PARTY_NOTICES.txt does
not list, so the dynamic symbol table of every ABI's library (ELF32 or ELF64, little-endian) is
read and a defined NativeProbe ringProbe or rustlsProbe symbol fails the check; a library whose
symbol table cannot be read fails too.

The score scan (app/src/scoreScan) is found by two markers that survive R8, each in its own place:
its string resources by name in the key pool of resources.arsc (the builds do not shrink or rename
resources), and its code by the folder name it writes to (ScoreScanLog.ROOT, a constant inlined
into the scan's classes) in the string pool of a classes*.dex file; R8 renames the classes, so a
class name is no marker. An APK carries the scan when both markers are there and does not carry it
when neither is; one without the other is a failure in every mode.

There is no default mode: an APK checked for the wrong kind of build must fail, not pass. A missing
argument, an unknown mode or an unreadable file fails: an error never counts as a pass, so a
resources.arsc or a dex file that cannot be read fails too. A 32-bit, big-endian or truncated
arm64-v8a library fails with a message that names it, not with a traceback.
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
# app/src/scoreScan/res/values/strings.xml: the launcher entry's label and the scan's result text.
SCAN_RESOURCES = ('support_score_scan', 'support_score_scan_result')
# ScoreScanLog.ROOT in scripts/.../supportSelection/SupportScoreScanLog.kt.
SCAN_DEX_STRING = b'support-score-scan'
MODES = ('absent', 'stable', 'beta')
# fga-ffi's jni_exports.rs, only with the crypto-probes feature.
PROBE_PREFIX = 'Java_io_github_fate_1grand_1automata_qa_NativeProbe_'
CRYPTO_PROBE_SYMBOLS = (PROBE_PREFIX + 'ringProbe', PROBE_PREFIX + 'rustlsProbe')
SHT_DYNSYM = 11

RES_STRING_POOL = 0x0001
RES_TABLE = 0x0002
RES_TABLE_PACKAGE = 0x0200
UTF8_FLAG = 0x100


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


def defined_dynamic_symbols(elf):
    """Names of the defined symbols in .dynsym of a little-endian ELF32 or ELF64 file."""
    if len(elf) < 52 or elf[:4] != b'\x7fELF':
        raise ValueError('not an ELF file')
    if elf[5] != 1 or elf[4] not in (1, 2):
        raise ValueError('not a little-endian ELF32 or ELF64 file (EI_CLASS %d, EI_DATA %d)' % (elf[4], elf[5]))
    wide = elf[4] == 2
    if wide:
        if len(elf) < 64:
            raise ValueError('truncated ELF header')
        shoff, = struct.unpack_from('<Q', elf, 0x28)
        shentsize, shnum = struct.unpack_from('<HH', elf, 0x3a)
        need = 64
    else:
        shoff, = struct.unpack_from('<I', elf, 0x20)
        shentsize, shnum = struct.unpack_from('<HH', elf, 0x2e)
        need = 40
    if shnum == 0 or shentsize < need or shoff + shentsize * shnum > len(elf):
        raise ValueError('no readable section header table')

    def section(i):
        base = shoff + i * shentsize
        if wide:
            _, sh_type, _, _, offset, size, link, _, _, entsize = struct.unpack_from('<IIQQQQIIQQ', elf, base)
        else:
            _, sh_type, _, _, offset, size, link, _, _, entsize = struct.unpack_from('<IIIIIIIIII', elf, base)
        return sh_type, offset, size, link, entsize

    tables = [section(i) for i in range(shnum) if section(i)[0] == SHT_DYNSYM]
    if not tables:
        raise ValueError('no dynamic symbol table')
    names = set()
    for _, offset, size, link, entsize in tables:
        sym_size = 24 if wide else 16
        if entsize != sym_size or offset + size > len(elf) or link >= shnum:
            raise ValueError('malformed dynamic symbol table')
        _, str_offset, str_size, _, _ = section(link)
        if str_offset + str_size > len(elf):
            raise ValueError('malformed dynamic string table')
        for at in range(offset, offset + size - sym_size + 1, sym_size):
            if wide:
                name, _, _, shndx = struct.unpack_from('<IBBH', elf, at)
            else:
                name, _, _, _, _, shndx = struct.unpack_from('<IIIBBH', elf, at)
            if shndx == 0 or name == 0:
                continue
            if name >= str_size:
                raise ValueError('symbol name outside the dynamic string table')
            end = elf.find(b'\0', str_offset + name, str_offset + str_size)
            if end < 0:
                raise ValueError('unterminated symbol name')
            names.add(elf[str_offset + name:end].decode('utf-8', 'replace'))
    return names


def crypto_probe_problems(path, z):
    problems = []
    for lib in sorted(n for n in z.namelist() if n.startswith('lib/') and n.endswith('/' + LIBRARY)):
        try:
            found = [s for s in CRYPTO_PROBE_SYMBOLS if s in defined_dynamic_symbols(z.read(lib))]
        except Exception as e:  # any error is a failed check with its reason, never a traceback
            problems.append('%s: cannot read the dynamic symbols of %s: %s: %s' % (path, lib, type(e).__name__, e))
            continue
        problems += ['%s: %s exports the crypto probe %s (build without -Pfga.native.crypto=true)'
                     % (path, lib, s) for s in found]
    return problems


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


def chunk_header(data, offset, end):
    """(type, header size, size) of the resource chunk at offset, checked to lie within end."""
    if offset + 8 > end:
        raise ValueError('chunk header at %d out of range' % offset)
    kind, header_size, size = struct.unpack_from('<HHI', data, offset)
    if header_size < 8 or size < header_size or offset + size > end:
        raise ValueError('chunk at %d has a bad size' % offset)
    return kind, header_size, size


def string_pool(data, offset):
    """The strings of the ResStringPool chunk at offset (UTF-8 or UTF-16)."""
    kind, header_size, size = chunk_header(data, offset, len(data))
    if kind != RES_STRING_POOL or header_size < 28:
        raise ValueError('no string pool at %d' % offset)
    count, _, flags, strings_start, _ = struct.unpack_from('<IIIII', data, offset + 8)
    end = offset + size
    index = offset + header_size
    if index + 4 * count > end:
        raise ValueError('string pool offsets out of range')
    base = offset + strings_start
    out = []
    for i in range(count):
        at = base + struct.unpack_from('<I', data, index + 4 * i)[0]
        if flags & UTF8_FLAG:
            # Two lengths, each one byte, or two with the high bit set: UTF-16 units, then bytes.
            for _ in range(2):
                if at >= end:
                    raise ValueError('string %d out of range' % i)
                n = data[at]
                at += 1
                if n & 0x80:
                    if at >= end:
                        raise ValueError('string %d out of range' % i)
                    n = ((n & 0x7f) << 8) | data[at]
                    at += 1
            raw = data[at:at + n]
            if at + n > end:
                raise ValueError('string %d out of range' % i)
            out.append(raw.decode('utf-8', 'replace'))
        else:
            if at + 2 > end:
                raise ValueError('string %d out of range' % i)
            n, = struct.unpack_from('<H', data, at)
            at += 2
            if n & 0x8000:
                if at + 2 > end:
                    raise ValueError('string %d out of range' % i)
                n = ((n & 0x7fff) << 16) | struct.unpack_from('<H', data, at)[0]
                at += 2
            if at + 2 * n > end:
                raise ValueError('string %d out of range' % i)
            out.append(data[at:at + 2 * n].decode('utf-16-le', 'replace'))
    return out


def resource_names(arsc):
    """Every resource entry name (the key pools of every package) of a resources.arsc file."""
    kind, header_size, size = chunk_header(arsc, 0, len(arsc))
    if kind != RES_TABLE:
        raise ValueError('not a resource table')
    names = set()
    packages = 0
    offset = header_size
    while offset < size:
        kind, _, chunk_size = chunk_header(arsc, offset, size)
        if kind == RES_TABLE_PACKAGE:
            # ResTable_package: header (8), id (4), name (256), typeStrings, lastPublicType, keyStrings.
            if offset + 280 > size:
                raise ValueError('package header out of range')
            key_strings, = struct.unpack_from('<I', arsc, offset + 8 + 4 + 256 + 8)
            names.update(string_pool(arsc[:offset + chunk_size], offset + key_strings))
            packages += 1
        offset += chunk_size
    if not packages:
        raise ValueError('no package in the resource table')
    return names


def uleb128(data, at):
    value = shift = 0
    while True:
        if at >= len(data) or shift > 28:
            raise ValueError('bad uleb128 at %d' % at)
        b = data[at]
        at += 1
        value |= (b & 0x7f) << shift
        if not b & 0x80:
            return value, at
        shift += 7


def dex_strings(dex):
    """The raw (MUTF-8) bytes of every string_ids entry of a dex file."""
    if len(dex) < 0x70 or dex[:4] != b'dex\n':
        raise ValueError('not a dex file')
    count, ids = struct.unpack_from('<II', dex, 0x38)
    if ids + 4 * count > len(dex):
        raise ValueError('string ids out of range')
    out = []
    for i in range(count):
        at, = struct.unpack_from('<I', dex, ids + 4 * i)
        _, at = uleb128(dex, at)
        end = dex.find(b'\0', at)
        if end < 0:
            raise ValueError('string %d is not terminated' % i)
        out.append(dex[at:end])
    return out


def scan_markers(z):
    """(string resources found, whether the dex marker was found) of an open APK."""
    names = set(z.namelist())
    if 'resources.arsc' not in names:
        raise ValueError('no resources.arsc')
    keys = resource_names(z.read('resources.arsc'))
    dexes = sorted(n for n in names if '/' not in n and n.startswith('classes') and n.endswith('.dex'))
    if not dexes:
        raise ValueError('no classes*.dex')
    in_dex = any(SCAN_DEX_STRING in dex_strings(z.read(n)) for n in dexes)
    return [r for r in SCAN_RESOURCES if r in keys], in_dex


def check_scan(path, z, wanted):
    try:
        resources, in_dex = scan_markers(z)
    except Exception as e:  # an unreadable marker place proves neither presence nor absence
        return ['%s: cannot look for the support score scan: %s: %s' % (path, type(e).__name__, e)]
    found = ['string resource %s' % r for r in resources]
    if in_dex:
        found.append('dex string %s' % SCAN_DEX_STRING.decode())
    if wanted:
        missing = ['string resource %s' % r for r in SCAN_RESOURCES if r not in resources]
        if not in_dex:
            missing.append('dex string %s' % SCAN_DEX_STRING.decode())
        return ['%s: lacks the support score scan\'s %s' % (path, m) for m in missing]
    return ['%s: contains the support score scan\'s %s' % (path, f) for f in found]


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
    return problems + crypto_probe_problems(path, z)


def check_apk(path, mode, required=None):
    """Returns a list of problems for one APK; empty means it is fine."""
    try:
        with zipfile.ZipFile(path) as z:
            if mode == 'absent':
                return check_absent(path, z) + check_scan(path, z, False)
            return check_present(path, z, required) + check_scan(path, z, mode == 'beta')
    except Exception as e:
        return ['%s: not a readable zip (%s: %s)' % (path, type(e).__name__, e)]


def main(argv):
    if len(argv) < 2 or argv[1] not in MODES:
        print('apk_check: FAIL: the first argument must be the mode, one of %s' % ', '.join(MODES), file=sys.stderr)
        return 1
    mode = argv[1]
    paths = argv[2:]
    if not paths:
        print('apk_check: FAIL: no APK path given', file=sys.stderr)
        return 1
    model = required = None
    if mode != 'absent':
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
        scan = ('with the support score scan (string resources %s, dex string %s)'
                % (', '.join(SCAN_RESOURCES), SCAN_DEX_STRING.decode())) if mode == 'beta' \
            else 'without the support score scan'
        if mode == 'absent':
            print('apk_check: %d APK(s) without %s or a %s model, %s' % (len(paths), LIBRARY, MODEL_EXTENSION, scan))
        else:
            print('apk_check: %d APK(s) with the %d entries of app/engine-apk-entries.txt (%s); '
                  'arm64-v8a 16 KB aligned; no crypto probes; %s' % (len(paths), len(required), model, scan))
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
