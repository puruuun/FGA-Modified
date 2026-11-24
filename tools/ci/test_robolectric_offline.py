"""Tests for the Robolectric jar cache of Main CI (issue #658). Standard library only."""
import hashlib
import http.server
import os
import re
import shutil
import sys
import tempfile
import threading
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import robolectric_offline as r

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.join(HERE, '..', '..')

# Build.VERSION_CODES names the tests use in @Config(sdk = [...]).
NAMED_SDKS = {'VANILLA_ICE_CREAM': 35}


def read(*parts):
    with open(os.path.join(REPO, *parts), encoding='utf-8') as f:
        return f.read()


def sdks_used_by_tests():
    """Every SDK level named in an @Config(sdk = [...]) of app/src/test, plus targetSdk, which Robolectric
    uses for a test without one."""
    used = set()
    test_root = os.path.join(REPO, 'app', 'src', 'test')
    for base, _, files in os.walk(test_root):
        for name in files:
            if not name.endswith('.kt'):
                continue
            text = read(os.path.relpath(os.path.join(base, name), REPO))
            for m in re.finditer(r'\bsdk\s*=\s*\[([^\]]*)\]', text):
                for token in m.group(1).split(','):
                    token = token.strip()
                    if not token:
                        continue
                    if token.isdigit():
                        used.add(int(token))
                    else:
                        key = token.rsplit('.', 1)[-1]
                        if key not in NAMED_SDKS:
                            raise AssertionError('unknown SDK token %r in %s: add it to NAMED_SDKS' % (token, name))
                        used.add(NAMED_SDKS[key])
    m = re.search(r'\btargetSdk\s*=\s*(\d+)', read('app', 'build.gradle.kts'))
    if not m:
        raise AssertionError('targetSdk not found in app/build.gradle.kts')
    used.add(int(m.group(1)))
    return used


class ManifestTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = r.load_manifest()

    def test_version_equals_the_one_in_the_version_catalog(self):
        m = re.search(r'^robolectric_version\s*=\s*"([^"]+)"', read('gradle', 'libs.versions.toml'), re.M)
        self.assertIsNotNone(m)
        self.assertEqual(
            self.manifest['robolectric'], m.group(1),
            'Robolectric was bumped: list its android-all-instrumented jars (see DefaultSdkProvider of that '
            'version, sha256 of each from Maven Central) in tools/ci/robolectric_android_all.json')

    def test_every_sdk_the_tests_use_is_listed(self):
        listed = {a['sdk'] for a in self.manifest['artifacts']}
        self.assertEqual(sdks_used_by_tests() - listed, set(),
                         'a test uses an SDK level whose jar would be missing offline; add it to the manifest')

    def test_no_unused_sdk_is_cached(self):
        listed = {a['sdk'] for a in self.manifest['artifacts']}
        self.assertEqual(listed - sdks_used_by_tests(), set(), 'the cache holds a jar no test uses')

    def test_entries_are_well_formed(self):
        for a in self.manifest['artifacts']:
            self.assertRegex(a['sha256'], r'^[0-9a-f]{64}$')
            self.assertRegex(a['version'], r'^[0-9][0-9.]*-robolectric-[0-9-]+-i\d+$')


class _Server:
    """Serves {path: bytes}; the first `fail_first` requests of every path answer 503."""

    def __init__(self, files, fail_first=0):
        self.files = files
        self.hits = {}
        outer = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                n = outer.hits.get(self.path, 0) + 1
                outer.hits[self.path] = n
                body = outer.files.get(self.path)
                if body is None or n <= fail_first:
                    self.send_response(503 if body is not None else 404)
                    self.send_header('Content-Length', '0')
                    self.end_headers()
                    return
                self.send_response(200)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *a):
                pass

        self.httpd = http.server.HTTPServer(('127.0.0.1', 0), Handler)
        self.url = 'http://127.0.0.1:%d' % self.httpd.server_port
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


class PrefetchTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='robo-')
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.body = b'jar-bytes' * 100
        self.version = '9-robolectric-1-i7'
        self.manifest = {'artifacts': [{
            'sdk': 28, 'version': self.version, 'sha256': hashlib.sha256(self.body).hexdigest()}]}
        self.path = '/%s/%s' % (self.version, r.file_name(self.version))
        self.dest = os.path.join(self.tmp, 'dir', r.file_name(self.version))

    def serve(self, files, fail_first=0):
        s = _Server(files, fail_first)
        self.addCleanup(s.close)
        return s

    def run_prefetch(self, server, attempts=3):
        return r.prefetch(os.path.join(self.tmp, 'dir'), self.manifest, base_url=server.url,
                          attempts=attempts, backoff=0, sleep=lambda s: None)

    def test_downloads_a_missing_jar(self):
        s = self.serve({self.path: self.body})
        self.assertEqual(self.run_prefetch(s), 1)
        with open(self.dest, 'rb') as f:
            self.assertEqual(f.read(), self.body)

    def test_a_present_jar_is_not_downloaded_again(self):
        s = self.serve({self.path: self.body})
        self.run_prefetch(s)
        self.assertEqual(self.run_prefetch(s), 0)
        self.assertEqual(s.hits[self.path], 1)

    def test_retries_after_a_failed_request(self):
        s = self.serve({self.path: self.body}, fail_first=2)
        self.assertEqual(self.run_prefetch(s, attempts=3), 1)
        self.assertEqual(s.hits[self.path], 3)

    def test_gives_up_after_the_last_attempt(self):
        s = self.serve({self.path: self.body}, fail_first=99)
        with self.assertRaises(OSError):
            self.run_prefetch(s, attempts=3)
        self.assertFalse(os.path.exists(self.dest))

    def test_a_wrong_checksum_is_never_kept(self):
        s = self.serve({self.path: b'something else'})
        with self.assertRaises(OSError):
            self.run_prefetch(s, attempts=2)
        self.assertEqual(os.listdir(os.path.join(self.tmp, 'dir')), [])

    def test_a_corrupt_cached_jar_is_replaced(self):
        os.makedirs(os.path.dirname(self.dest))
        with open(self.dest, 'wb') as f:
            f.write(b'truncated')
        s = self.serve({self.path: self.body})
        self.assertEqual(self.run_prefetch(s), 1)
        with open(self.dest, 'rb') as f:
            self.assertEqual(f.read(), self.body)


if __name__ == '__main__':
    unittest.main()
