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
        self.doc('.gitattributes')
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


NATIVE_CI = os.path.join(os.path.dirname(__file__), '..', '..', '.github', 'workflows', 'native-ci.yml')


class NativeClassifyTest(unittest.TestCase):
    def test_native_paths(self):
        for p in ('native/Cargo.toml', 'native/fga-ffi/src/lib.rs', '.github/workflows/native-ci.yml'):
            self.assertTrue(g.needs_native([p]), p)
        self.assertTrue(g.needs_native(['docs/a.md', 'native/x']))

    def test_non_native_paths(self):
        for p in ('app/src/A.kt', 'docs/a.md', 'native', 'nativex/a.rs', 'sub/native/a.rs',
                  '.github/workflows/main-ci.yml', '.github/workflows/native-advisories.yml',
                  '.github/workflows/native-ci.yml.bak'):
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

    def test_patterns_equal_native_ci_paths_filter(self):
        with open(NATIVE_CI, encoding='utf-8') as f:
            lines = f.read().splitlines()
        sections = []
        current = None
        for line in lines:
            if line.strip().startswith('#'):
                continue
            if re.match(r'^\s*paths:\s*$', line):
                current = []
                sections.append(current)
                continue
            m = re.match(r"^\s*-\s*'([^']*)'\s*$", line)
            if current is not None and m:
                current.append(m.group(1))
            else:
                current = None
        self.assertTrue(sections, 'no paths: filter found in native-ci.yml')
        for section in sections:
            self.assertEqual(sorted(section), sorted(g.NATIVE_PATTERNS))

    def test_native_ci_triggers(self):
        with open(NATIVE_CI, encoding='utf-8') as f:
            text = f.read()
        self.assertIsNone(re.search(r'(?m)^  pull_request:', text))
        self.assertIsNotNone(re.search(r'(?m)^  workflow_call:', text))
        group = re.search(r'(?m)^  group: (.*)$', text).group(1)
        for part in ('github.workflow', 'github.event_name', 'github.ref'):
            self.assertIn(part, group)

    def test_cli_classify_native(self):
        def run(data):
            r = subprocess.run([sys.executable, SCRIPT, 'classify-native'], input=data, capture_output=True)
            return r.stdout.decode().strip()
        self.assertEqual(run(b'docs/a.md\0app/a.kt\0'), 'false')
        self.assertEqual(run(b'app/a.kt\0native/fga-ffi/src/lib.rs\0'), 'true')
        self.assertEqual(run(b''), 'true')


class WorkflowWiringTest(unittest.TestCase):
    def setUp(self):
        with open(os.path.join(os.path.dirname(__file__), '..', '..', '.github', 'workflows', 'main-ci.yml'),
                  encoding='utf-8') as f:
            self.text = f.read()

    def test_native_job_calls_native_ci_only_when_needed(self):
        start = self.text.index('\n  native:\n')
        end = self.text.index('\n  gate:\n')
        m = self.text[start:end]
        body = m
        self.assertIn('needs: changes', body)
        self.assertIn("if: needs.changes.outputs.native == 'true'", body)
        self.assertIn('uses: ./.github/workflows/native-ci.yml', body)

    def test_gate_waits_for_and_reads_native(self):
        gate = self.text[self.text.index('  gate:'):]
        self.assertIn('needs: [changes, test-and-build, native]', gate)
        self.assertIn('NATIVE_RESULT: ${{ needs.native.result }}', gate)
        self.assertIn('NATIVE: ${{ needs.changes.outputs.native }}', gate)
        self.assertIn('"$CHANGES_RESULT" "$CODE" "$NATIVE" "$BUILD_RESULT" "$NATIVE_RESULT"', gate)
        self.assertIn('name: Main CI gate', gate)


def expected_gate(changes, code, native, build, rust):
    if changes != 'success' or code not in ('true', 'false') or native not in ('true', 'false'):
        return False
    if not (build == 'success' or (code == 'false' and build == 'skipped')):
        return False
    if native == 'true':
        return code == 'true' and rust == 'success'
    return rust in ('skipped', 'success')


class GateTest(unittest.TestCase):
    def test_full_grid(self):
        results = ['success', 'failure', 'cancelled', 'skipped', '']
        flags = ['true', 'false', '', 'weird']
        for c, k, n, b, r in itertools.product(results, flags, flags, results, results):
            self.assertEqual(g.gate_ok(c, k, n, b, r), expected_gate(c, k, n, b, r), (c, k, n, b, r))

    def test_passing_rows_are_exactly_these(self):
        results = ['success', 'failure', 'cancelled', 'skipped', '']
        flags = ['true', 'false', '', 'weird']
        passing = {t for t in itertools.product(results, flags, flags, results, results) if g.gate_ok(*t)}
        self.assertEqual(passing, {
            ('success', 'true', 'true', 'success', 'success'),
            ('success', 'true', 'false', 'success', 'success'),
            ('success', 'true', 'false', 'success', 'skipped'),
            ('success', 'false', 'false', 'success', 'success'),
            ('success', 'false', 'false', 'success', 'skipped'),
            ('success', 'false', 'false', 'skipped', 'success'),
            ('success', 'false', 'false', 'skipped', 'skipped'),
        })

    def test_named_rows(self):
        ok = g.gate_ok
        self.assertTrue(ok('success', 'true', 'true', 'success', 'success'))
        self.assertTrue(ok('success', 'false', 'false', 'skipped', 'skipped'))
        self.assertFalse(ok('success', 'true', 'true', 'success', 'skipped'))
        self.assertFalse(ok('success', 'true', 'true', 'success', 'cancelled'))
        self.assertFalse(ok('success', 'true', 'true', 'success', 'failure'))
        self.assertFalse(ok('success', 'true', 'true', 'success', ''))
        self.assertFalse(ok('success', 'false', 'true', 'skipped', 'success'))
        self.assertFalse(ok('success', 'true', '', 'success', 'skipped'))
        self.assertFalse(ok('success', 'true', 'weird', 'success', 'success'))
        self.assertFalse(ok('success', 'true', 'false', 'success', 'cancelled'))
        self.assertFalse(ok('failure', 'false', 'false', 'skipped', 'skipped'))
        self.assertFalse(ok('success', 'true', 'false', 'skipped', 'skipped'))

    def test_native_output_normalisation(self):
        # Same line as in main-ci.yml: anything but exactly "false" becomes "true".
        with open(os.path.join(os.path.dirname(__file__), '..', '..', '.github', 'workflows', 'main-ci.yml'),
                  encoding='utf-8') as f:
            self.assertIn('[ "$native" = false ] || native=true', f.read())
        script = '[ "$native" = false ] || native=true; printf %s "$native"'
        for raw, want in (('false', 'false'), ('true', 'true'), ('', 'true'), ('False', 'true'),
                          ('false ', 'true'), ('x', 'true')):
            r = subprocess.run(['sh', '-c', script], env={'native': raw, 'PATH': os.environ['PATH']},
                               capture_output=True)
            self.assertEqual(r.stdout.decode(), want, raw)

    def test_cli_exit_codes(self):
        def rc(*a):
            return subprocess.run([sys.executable, SCRIPT, 'gate', *a], capture_output=True).returncode
        self.assertEqual(rc('success', 'false', 'false', 'skipped', 'skipped'), 0)
        self.assertEqual(rc('success', 'true', 'true', 'success', 'success'), 0)
        self.assertEqual(rc('success', 'true', 'true', 'success', 'cancelled'), 1)
        self.assertEqual(rc('success', 'true', 'true', 'skipped', 'success'), 1)
        self.assertEqual(rc('success', 'true', 'skipped'), 2)
        self.assertEqual(rc('success', 'true', 'success', 'success'), 2)
        self.assertEqual(rc('success', 'true', 'true', 'success', 'success', 'extra'), 2)
        self.assertEqual(rc(), 2)


if __name__ == '__main__':
    unittest.main()
