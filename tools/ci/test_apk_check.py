import os
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(__file__))
import apk_check as a

SCRIPT = os.path.join(os.path.dirname(__file__), 'apk_check.py')

BASE = ['AndroidManifest.xml', 'classes.dex', 'lib/arm64-v8a/libopencv_java4.so', 'resources.arsc']
MODEL = a.pinned_model()
ENGINE = [
    'lib/armeabi-v7a/libfga_ffi.so',
    'assets/ocr/' + MODEL,
    'assets/ocr/' + MODEL[:-len('.onnx')] + '.NOTICE.txt',
    'assets/ocr/ocr-model.properties',
    'assets/ocr/THIRD_PARTY_NOTICES.txt',
]
ARM64 = 'lib/arm64-v8a/libfga_ffi.so'


def elf(aligns, load=True):
    """A minimal 64-bit little-endian ELF header with one program header per alignment."""
    phoff = 64
    header = bytearray(64)
    header[:4] = b'\x7fELF'
    header[4] = 2
    header[5] = 1
    struct.pack_into('<Q', header, 0x20, phoff)
    struct.pack_into('<HH', header, 0x36, 56, len(aligns))
    body = bytearray()
    for align in aligns:
        ph = bytearray(56)
        struct.pack_into('<I', ph, 0, 1 if load else 6)
        struct.pack_into('<Q', ph, 48, align)
        body += ph
    return bytes(header + body)


class ApkCheckTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def apk(self, name, entries, arm64=None, align_stored=True, compress=False):
        """entries get b'x'; arm64 (ELF bytes) is written last, stored and 16 KB aligned unless told otherwise."""
        path = os.path.join(self.tmp.name, name)
        with zipfile.ZipFile(path, 'w') as z:
            for e in entries:
                z.writestr(e, b'x')
            if arm64 is not None:
                info = zipfile.ZipInfo(ARM64)
                info.compress_type = zipfile.ZIP_DEFLATED if compress else zipfile.ZIP_STORED
                if align_stored and not compress:
                    start = z.fp.tell() + 30 + len(ARM64.encode())
                    pad = (-(start + 4)) % a.PAGE
                    # Android's zipalign pads with the same kind of extra field (id 0xd935).
                    info.extra = struct.pack('<HH', 0xd935, pad) + b'\0' * pad
                z.writestr(info, arm64)
        return path

    def engine_apk(self, name='e.apk', **kwargs):
        return self.apk(name, BASE + ENGINE, arm64=kwargs.pop('arm64', elf([0x4000, 0x4000])), **kwargs)

    def run_cli(self, *args):
        p = subprocess.run([sys.executable, SCRIPT, *args], capture_output=True)
        return p.returncode, (p.stdout + p.stderr).decode('utf-8', 'replace')

    # --- mode absent: the ci APK ---

    def test_apk_without_library_passes(self):
        self.assertEqual(self.run_cli('absent', self.apk('a.apk', BASE))[0], 0)

    def test_library_in_any_abi_fails(self):
        for abi in ('arm64-v8a', 'armeabi-v7a', 'x86_64'):
            apk = self.apk('b.apk', BASE + ['lib/%s/libfga_ffi.so' % abi])
            code, out = self.run_cli('absent', apk)
            self.assertEqual(code, 1, abi)
            self.assertIn('libfga_ffi.so', out)

    def test_similar_names_are_other_files_and_pass(self):
        apk = self.apk('c.apk', BASE + ['lib/arm64-v8a/libfga_ffi.so.txt',
                                        'lib/arm64-v8a/xlibfga_ffi.so', 'assets/libfga_ffi.so/readme'])
        self.assertEqual(self.run_cli('absent', apk)[0], 0)

    def test_model_asset_fails(self):
        for entry in ('assets/ocr/PP-OCRv6_rec_small.onnx', 'assets/other/model.ONNX', 'm.onnx'):
            code, out = self.run_cli('absent', self.apk('m.apk', BASE + [entry]))
            self.assertEqual(code, 1, entry)
            self.assertIn(entry, out)

    def test_model_notice_and_pin_alone_pass(self):
        apk = self.apk('n.apk', BASE + ['assets/ocr/ocr-model.properties',
                                        'assets/ocr/PP-OCRv6_rec_small.NOTICE.txt', 'assets/ocr/m.onnx.txt'])
        self.assertEqual(self.run_cli('absent', apk)[0], 0)

    def test_library_at_archive_root_fails(self):
        self.assertEqual(self.run_cli('absent', self.apk('d.apk', BASE + ['libfga_ffi.so']))[0], 1)

    def test_engine_apk_fails_absent(self):
        self.assertEqual(self.run_cli('absent', self.engine_apk())[0], 1)

    # --- mode present: the beta, the rust APK and the stable ci APK built with -Pfga.native=true ---

    def test_engine_apk_passes_present(self):
        code, out = self.run_cli('present', self.engine_apk())
        self.assertEqual(code, 0, out)

    def test_required_entries_are_the_shared_list(self):
        self.assertEqual(sorted(a.required_entries(MODEL)), sorted(ENGINE + [ARM64]))

    def test_plain_apk_fails_present_naming_every_missing_file(self):
        code, out = self.run_cli('present', self.apk('p.apk', BASE))
        self.assertEqual(code, 1)
        for entry in [ARM64] + ENGINE:
            self.assertIn('lacks ' + entry, out)

    def test_only_one_abi_fails(self):
        apk = self.apk('o.apk', BASE + [e for e in ENGINE if 'armeabi-v7a' not in e], arm64=elf([0x4000]))
        code, out = self.run_cli('present', apk)
        self.assertEqual(code, 1)
        self.assertIn('lacks lib/armeabi-v7a/libfga_ffi.so', out)
        self.assertNotIn('lacks ' + ARM64, out)
        apk = self.apk('o2.apk', BASE + ENGINE)
        code, out = self.run_cli('present', apk)
        self.assertEqual(code, 1)
        self.assertIn('lacks ' + ARM64, out)

    def test_missing_notices_or_model_fails(self):
        for left_out in ('assets/ocr/THIRD_PARTY_NOTICES.txt', 'assets/ocr/' + MODEL,
                         'assets/ocr/' + MODEL[:-len('.onnx')] + '.NOTICE.txt',
                         'assets/ocr/ocr-model.properties'):
            apk = self.apk('x.apk', BASE + [e for e in ENGINE if e != left_out], arm64=elf([0x4000]))
            code, out = self.run_cli('present', apk)
            self.assertEqual(code, 1, left_out)
            self.assertIn('lacks ' + left_out, out)

    def test_4k_aligned_segment_fails(self):
        code, out = self.run_cli('present', self.engine_apk(arm64=elf([0x4000, 0x1000])))
        self.assertEqual(code, 1)
        self.assertIn('0x1000', out)

    def test_stored_library_off_a_16k_boundary_fails(self):
        code, out = self.run_cli('present', self.engine_apk(align_stored=False))
        self.assertEqual(code, 1)
        self.assertIn('16 KB boundary', out)

    def test_compressed_library_needs_no_zip_alignment(self):
        self.assertEqual(self.run_cli('present', self.engine_apk(compress=True))[0], 0)

    def test_zero_or_small_alignment_fails(self):
        for aligns in ([0], [1], [0x4000, 0], [0x2000]):
            code, out = self.run_cli('present', self.engine_apk(arm64=elf(aligns)))
            self.assertEqual(code, 1, aligns)
            self.assertIn('not 0x4000', out, aligns)

    def test_larger_page_multiple_passes(self):
        self.assertEqual(self.run_cli('present', self.engine_apk(arm64=elf([0x10000, 0x4000])))[0], 0)

    def test_32_bit_big_endian_and_truncated_elf_fail_with_a_message(self):
        good = elf([0x4000])
        cases = {
            '32-bit': good[:4] + b'\x01' + good[5:],
            'big-endian': good[:5] + b'\x02' + good[6:],
            'truncated header': good[:40],
            'truncated program headers': good[:64 + 20],
            'no program headers': elf([]),
        }
        for label, data in cases.items():
            code, out = self.run_cli('present', self.engine_apk(arm64=data))
            self.assertEqual(code, 1, label)
            self.assertIn('cannot read ' + ARM64, out, label)
            self.assertNotIn('Traceback', out, label)

    def test_library_that_zipfile_cannot_read_fails_with_a_message(self):
        # Compression method 99 (AES): zipfile raises NotImplementedError on read.
        path = self.engine_apk(compress=True)
        with open(path, 'rb') as f:
            data = bytearray(f.read())
        with zipfile.ZipFile(path) as z:
            info = z.getinfo(ARM64)
        struct.pack_into('<H', data, info.header_offset + 8, 99)
        name = ARM64.encode()
        pos = 0
        while True:
            pos = data.index(b'PK\x01\x02', pos)
            name_len, = struct.unpack_from('<H', data, pos + 28)
            if bytes(data[pos + 46:pos + 46 + name_len]) == name:
                struct.pack_into('<H', data, pos + 10, 99)
                break
            pos += 4
        with open(path, 'wb') as f:
            f.write(data)
        code, out = self.run_cli('present', path)
        self.assertEqual(code, 1)
        self.assertIn(ARM64, out)
        self.assertNotIn('Traceback', out)

    def test_entry_list_parser(self):
        abis, templates = a.read_entry_list('# c\n\nabi x\nentry lib/{abi}/l.so\nentry a/{model}\n')
        self.assertEqual((abis, templates), (['x'], ['lib/{abi}/l.so', 'a/{model}']))
        self.assertEqual(a.expand_entries(['x', 'y'], ['lib/{abi}/l.so', 'a/{model_stem}.N'], 'm.onnx'),
                         ['lib/x/l.so', 'lib/y/l.so', 'a/m.N'])
        for bad in ('abi\nentry a\n', 'abi x\n', 'entry a\n', 'abi x\nentry a b\n', 'abi x\nfile a\n'):
            with self.assertRaises(ValueError, msg=bad):
                a.read_entry_list(bad)
        with self.assertRaises(ValueError):
            a.expand_entries(['x'], ['a/{other}'], 'm.onnx')

    def test_unreadable_entry_list_fails_the_cli(self):
        missing = os.path.join(self.tmp.name, 'no-list.txt')
        with self.assertRaises(OSError):
            a.required_entries(MODEL, missing)

    def test_library_that_is_not_elf_fails(self):
        code, out = self.run_cli('present', self.engine_apk(arm64=b'not an elf'))
        self.assertEqual(code, 1)
        self.assertIn('cannot read', out)
        self.assertEqual(self.run_cli('present', self.engine_apk(arm64=elf([0x4000], load=False)))[0], 1)

    # --- arguments and files ---

    def test_mode_is_required(self):
        apk = self.apk('a.apk', BASE)
        for args in ((apk,), ('ABSENT', apk), ('', apk), ()):
            self.assertEqual(self.run_cli(*args)[0], 1, args)

    def test_no_apk_fails(self):
        for mode in ('absent', 'present'):
            self.assertEqual(self.run_cli(mode)[0], 1, mode)

    def test_non_zip_fails(self):
        path = os.path.join(self.tmp.name, 'e.apk')
        with open(path, 'wb') as f:
            f.write(b'not a zip')
        for mode in ('absent', 'present'):
            self.assertEqual(self.run_cli(mode, path)[0], 1, mode)

    def test_missing_file_fails(self):
        for mode in ('absent', 'present'):
            self.assertEqual(self.run_cli(mode, os.path.join(self.tmp.name, 'nope.apk'))[0], 1, mode)

    def test_one_bad_apk_among_good_ones_fails(self):
        good = self.apk('g.apk', BASE)
        bad = self.apk('h.apk', BASE + ['lib/arm64-v8a/libfga_ffi.so'])
        self.assertEqual(self.run_cli('absent', good, bad)[0], 1)
        self.assertEqual(a.check_apk(good, 'absent'), [])
        self.assertEqual(self.run_cli('present', self.engine_apk('e1.apk'), self.apk('p.apk', BASE))[0], 1)


if __name__ == '__main__':
    unittest.main()
