import os
import subprocess
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(__file__))
import apk_check as a

SCRIPT = os.path.join(os.path.dirname(__file__), 'apk_check.py')

BASE = ['AndroidManifest.xml', 'classes.dex', 'lib/arm64-v8a/libopencv_java4.so', 'resources.arsc']


class ApkCheckTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def apk(self, name, entries):
        path = os.path.join(self.tmp.name, name)
        with zipfile.ZipFile(path, 'w') as z:
            for e in entries:
                z.writestr(e, b'x')
        return path

    def run_cli(self, *args):
        p = subprocess.run([sys.executable, SCRIPT, *args], capture_output=True)
        return p.returncode, (p.stdout + p.stderr).decode('utf-8', 'replace')

    def test_apk_without_library_passes(self):
        self.assertEqual(self.run_cli(self.apk('a.apk', BASE))[0], 0)

    def test_library_in_any_abi_fails(self):
        for abi in ('arm64-v8a', 'armeabi-v7a', 'x86_64'):
            apk = self.apk('b.apk', BASE + ['lib/%s/libfga_ffi.so' % abi])
            code, out = self.run_cli(apk)
            self.assertEqual(code, 1, abi)
            self.assertIn('libfga_ffi.so', out)

    def test_similar_names_are_other_files_and_pass(self):
        apk = self.apk('c.apk', BASE + ['lib/arm64-v8a/libfga_ffi.so.txt',
                                        'lib/arm64-v8a/xlibfga_ffi.so', 'assets/libfga_ffi.so/readme'])
        self.assertEqual(self.run_cli(apk)[0], 0)

    def test_model_asset_fails(self):
        for entry in ('assets/ocr/PP-OCRv6_rec_small.onnx', 'assets/other/model.ONNX', 'm.onnx'):
            code, out = self.run_cli(self.apk('m.apk', BASE + [entry]))
            self.assertEqual(code, 1, entry)
            self.assertIn(entry, out)

    def test_model_notice_and_pin_alone_pass(self):
        apk = self.apk('n.apk', BASE + ['assets/ocr/ocr-model.properties',
                                        'assets/ocr/PP-OCRv6_rec_small.NOTICE.txt', 'assets/ocr/m.onnx.txt'])
        self.assertEqual(self.run_cli(apk)[0], 0)

    def test_library_at_archive_root_fails(self):
        self.assertEqual(self.run_cli(self.apk('d.apk', BASE + ['libfga_ffi.so']))[0], 1)

    def test_no_arguments_fails(self):
        self.assertEqual(self.run_cli()[0], 1)

    def test_non_zip_fails(self):
        path = os.path.join(self.tmp.name, 'e.apk')
        with open(path, 'wb') as f:
            f.write(b'not a zip')
        self.assertEqual(self.run_cli(path)[0], 1)

    def test_missing_file_fails(self):
        self.assertEqual(self.run_cli(os.path.join(self.tmp.name, 'nope.apk'))[0], 1)

    def test_one_bad_apk_among_good_ones_fails(self):
        good = self.apk('g.apk', BASE)
        bad = self.apk('h.apk', BASE + ['lib/arm64-v8a/libfga_ffi.so'])
        self.assertEqual(self.run_cli(good, bad)[0], 1)
        self.assertEqual(a.check_apk(good), [])


if __name__ == '__main__':
    unittest.main()
