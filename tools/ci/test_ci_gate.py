import itertools
import os
import re
import subprocess
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import ci_gate as g

SCRIPT = os.path.join(os.path.dirname(__file__), 'ci_gate.py')


class ClassifyTest(unittest.TestCase):
    def doc(self, *p):
        self.assertFalse(g.needs_build(list(p)), p)

    def code(self, *p):
        self.assertTrue(g.needs_build(list(p)), p)

    def test_doc_patterns(self):
        self.doc('.idea/x/y.xml')
        self.doc('.github/instructions/a.md')
        self.doc('.github/copilot-instructions.md')
        self.doc('.gitignore')
        self.doc('README.md')
        self.doc('LICENSE')
        self.doc('custom-game-area/a.json')
        self.doc('wiki/a/b.png')
        self.doc('docs/x.kt')
        self.doc('.claude/rules/x.md')
        self.doc('AGENTS.md')
        self.doc('sub/dir/AGENTS.md')

    def test_near_misses(self):
        self.code('app/README.md')
        self.code('.github/workflows/main-ci.yml')
        self.code('.github/instructions')
        self.code('tools/docs-site/x.py')
        self.code('mkdocs.yml')
        self.code('LICENSE.txt')
        self.code('sub/LICENSE')
        self.code('subdocs/x.md')
        self.code('docs')
        self.code('sub/MYAGENTS.md')
        self.code('sub/gitignore')
        self.code('.gitignore/x')
        self.code('.idea')
        self.code('.gitattributes')

    def test_mixed_and_empty(self):
        self.code('README.md', 'app/src/A.kt')
        self.doc('README.md', 'docs/a.md')
        self.code()
        self.code('')

    def test_odd_names(self):
        self.doc('docs/a b/ü 한글.md')
        self.doc('wiki/-rf')
        self.code('-rf')
        self.code('app/한 글 .kt')
        self.code('app/new\nline/docs/x')

    def test_cli_classify(self):
        def run(data):
            r = subprocess.run([sys.executable, SCRIPT, 'classify'], input=data, capture_output=True)
            return r.stdout.decode().strip()
        self.assertEqual(run(b'docs/a.md\0README.md\0'), 'false')
        self.assertEqual(run(b'docs/a.md\0app/a.kt\0'), 'true')
        self.assertEqual(run(b''), 'true')
        self.assertEqual(run(b'docs/\xff\xfe.md\0'), 'false')
        self.assertEqual(run(b'\xff\0'), 'true')


class NativeClassifyTest(unittest.TestCase):
    def test_native_paths(self):
        for p in ('native/Cargo.toml', 'native/fga-ffi/src/lib.rs', 'native/THIRD_PARTY_NOTICES.txt',
                  'native/ndk-version.txt', 'native/ocr-model/NOTICE.txt',
                  '.github/workflows/native-ci.yml', '.github/workflows/main-ci.yml',
                  'tools/ci/native_notices.py'):
            self.assertTrue(g.needs_native([p]), p)
        self.assertTrue(g.needs_native(['docs/a.md', 'native/x']))
        self.assertTrue(g.needs_native(['app/src/A.kt', 'native/Cargo.lock']))

    def test_non_native_paths(self):
        # Kotlin, the build scripts, the engine APK workflow and the other CI scripts need the build and
        # the engine APK (classify), not the Rust checks.
        for p in ('app/src/A.kt', 'docs/a.md', 'native', 'nativex/a.rs', 'sub/native/a.rs',
                  '.github/workflows/native-advisories.yml', '.github/workflows/pr-build.yml',
                  '.github/workflows/engine-apk.yml', 'app/engine-apk-entries.txt',
                  'app/build.gradle.kts', 'app/proguard-rust.pro',
                  'app/src/main/java/io/github/fate_grand_automata/sensing/RustSensing.kt',
                  'app/src/main/java/io/github/fate_grand_automata/di/script/ScriptsModule.kt',
                  'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradle/libs.versions.toml',
                  'tools/ci/apk_check.py',
                  '.github/workflows/native-ci.yml.bak',
                  'app/src/main/java/io/github/fate_grand_automata/ui/more/LicenseScreen.kt',
                  'app/src/main/java/io/github/fate_grand_automata/sensingx/A.kt',
                  'app/src/main/java/io/github/fate_grand_automata/di/script/OtherModule.kt',
                  'app/src/test/java/io/github/fate_grand_automata/sensing/SupportScoreEngineBindingTest.kt',
                  'app/proguard.txt', 'scripts/build.gradle.kts', 'prefs/build.gradle.kts',
                  'tools/ci/ci_gate.py', 'tools/ci/test_apk_check.py', 'gradlew', 'version.properties'):
            self.assertFalse(g.needs_native([p]), p)

    def test_empty_or_unreadable_means_native(self):
        self.assertTrue(g.needs_native([]))
        self.assertTrue(g.needs_native(['']))
        self.assertTrue(g.needs_native(None))

    def test_native_implies_build(self):
        samples = ['native/a.md', 'native/docs/x.md', 'native/README.md', 'native/AGENTS.md',
                   '.github/workflows/native-ci.yml', 'native/fga-ffi/src/lib.rs']
        for p in samples:
            self.assertTrue(g.needs_native([p]), p)
            self.assertTrue(g.needs_build([p]), p)
            self.assertTrue(g.needs_build([p, 'docs/a.md']), p)

    def test_cli_classify_native(self):
        def run(data):
            r = subprocess.run([sys.executable, SCRIPT, 'classify-native'], input=data, capture_output=True)
            return r.stdout.decode().strip()
        self.assertEqual(run(b'docs/a.md\0app/a.kt\0'), 'false')
        self.assertEqual(run(b'app/a.kt\0native/fga-ffi/src/lib.rs\0'), 'true')
        self.assertEqual(run(b''), 'true')

    def test_classify_commands_reject_extra_arguments(self):
        for cmd in ('classify', 'classify-native'):
            r = subprocess.run([sys.executable, SCRIPT, cmd, 'extra'], input=b'docs/a.md\0', capture_output=True)
            self.assertEqual(r.returncode, 2, cmd)
            self.assertEqual(r.stdout, b'', cmd)


def expected_gate(changes, code, native, build, rust, engine):
    if changes != 'success' or code not in ('true', 'false') or native not in ('true', 'false'):
        return False
    if native == 'true' and code == 'false':
        return False
    if code == 'true':
        if build != 'success' or engine != 'success':
            return False
    elif build not in ('success', 'skipped') or engine not in ('success', 'skipped'):
        return False
    if native == 'true':
        return rust == 'success'
    return rust in ('skipped', 'success')


RESULTS = ['success', 'failure', 'cancelled', 'skipped', '']
FLAGS = ['true', 'false', '', 'weird']


def grid():
    return itertools.product(RESULTS, FLAGS, FLAGS, RESULTS, RESULTS, RESULTS)


class GateTest(unittest.TestCase):
    def test_full_grid(self):
        for t in grid():
            self.assertEqual(g.gate_ok(*t), expected_gate(*t), t)

    def test_passing_rows_are_exactly_these(self):
        passing = {t for t in grid() if g.gate_ok(*t)}
        either = ('success', 'skipped')
        want = {('success', 'true', 'true', 'success', 'success', 'success')}
        want |= {('success', 'true', 'false', 'success', r, 'success') for r in either}
        want |= {('success', 'false', 'false', b, r, e) for b in either for r in either for e in either}
        self.assertEqual(passing, want)

    def test_named_rows(self):
        ok = g.gate_ok
        self.assertTrue(ok('success', 'true', 'true', 'success', 'success', 'success'))
        self.assertTrue(ok('success', 'true', 'false', 'success', 'skipped', 'success'))
        self.assertTrue(ok('success', 'false', 'false', 'skipped', 'skipped', 'skipped'))
        # The engine APK is required for every code change, with or without Rust paths.
        self.assertFalse(ok('success', 'true', 'false', 'success', 'skipped', 'skipped'))
        self.assertFalse(ok('success', 'true', 'false', 'success', 'skipped', 'failure'))
        self.assertFalse(ok('success', 'true', 'true', 'success', 'success', 'cancelled'))
        self.assertFalse(ok('success', 'true', 'true', 'success', 'success', ''))
        self.assertFalse(ok('success', 'true', 'true', 'success', 'skipped', 'success'))
        self.assertFalse(ok('success', 'true', 'true', 'success', 'failure', 'success'))
        self.assertFalse(ok('success', 'false', 'true', 'skipped', 'success', 'success'))
        self.assertFalse(ok('success', 'false', 'false', 'skipped', 'skipped', 'failure'))
        self.assertFalse(ok('success', 'true', 'weird', 'success', 'success', 'success'))
        self.assertFalse(ok('failure', 'false', 'false', 'skipped', 'skipped', 'skipped'))
        self.assertFalse(ok('success', 'true', 'false', 'skipped', 'skipped', 'success'))

    def test_cli_exit_codes(self):
        def rc(*a):
            return subprocess.run([sys.executable, SCRIPT, 'gate', *a], capture_output=True).returncode
        self.assertEqual(rc('success', 'false', 'false', 'skipped', 'skipped', 'skipped'), 0)
        self.assertEqual(rc('success', 'true', 'true', 'success', 'success', 'success'), 0)
        self.assertEqual(rc('success', 'true', 'false', 'success', 'skipped', 'success'), 0)
        self.assertEqual(rc('success', 'true', 'false', 'success', 'skipped', 'skipped'), 1)
        self.assertEqual(rc('success', 'true', 'true', 'success', 'cancelled', 'success'), 1)
        self.assertEqual(rc('success', 'true', 'true', 'success', 'success'), 2)
        self.assertEqual(rc('success', 'true', 'true', 'success', 'success', 'success', 'extra'), 2)
        self.assertEqual(rc(), 2)


if __name__ == '__main__':
    unittest.main()
