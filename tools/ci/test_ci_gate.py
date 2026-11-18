import itertools
import os
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


class GateTest(unittest.TestCase):
    def test_all_combinations(self):
        results = ['success', 'failure', 'cancelled', 'skipped', '']
        codes = ['true', 'false', '', 'weird']
        for c, k, b in itertools.product(results, codes, results):
            expected = c == 'success' and (
                (b == 'success' and k in ('true', 'false')) or (k == 'false' and b == 'skipped'))
            self.assertEqual(g.gate_ok(c, k, b), expected, (c, k, b))

    def test_named_cases(self):
        self.assertTrue(g.gate_ok('success', 'true', 'success'))
        self.assertTrue(g.gate_ok('success', 'false', 'skipped'))
        self.assertFalse(g.gate_ok('success', 'true', 'skipped'))
        self.assertFalse(g.gate_ok('success', '', 'skipped'))
        self.assertFalse(g.gate_ok('failure', 'false', 'skipped'))
        self.assertFalse(g.gate_ok('success', 'true', 'failure'))
        self.assertFalse(g.gate_ok('success', 'true', 'cancelled'))

    def test_cli_exit_codes(self):
        def rc(*a):
            return subprocess.run([sys.executable, SCRIPT, 'gate', *a], capture_output=True).returncode
        self.assertEqual(rc('success', 'false', 'skipped'), 0)
        self.assertEqual(rc('success', 'true', 'skipped'), 1)
        self.assertEqual(rc('success', 'true'), 2)


if __name__ == '__main__':
    unittest.main()
