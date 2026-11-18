import os
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import repo_guard as g

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, 'repo_guard.py')
REPO = os.path.dirname(os.path.dirname(HERE))
MB = 1024 * 1024


def record(path, size, kind='blob'):
    return ('100644 %s %s %7s\t%s\0' % (kind, 'a' * 40, size, path)).encode('utf-8')


def run(stdin, *args):
    p = subprocess.run([sys.executable, SCRIPT, *args], input=stdin, capture_output=True)
    return p.returncode, p.stdout.decode('utf-8', 'replace')


def refused(entries, allow=()):
    return [p for p, _ in g.check_files(entries, list(allow))]


class RuleTest(unittest.TestCase):
    def test_each_model_pattern_is_refused(self):
        for ext in ('onnx', 'ort', 'tflite', 'pt', 'pth', 'safetensors', 'pb', 'traineddata',
                    'pcap', 'pcapng', 'har'):
            path = 'some/dir/file.' + ext
            self.assertEqual(refused([(path, 10)]), [path], ext)

    def test_pattern_is_case_insensitive_and_whole_extension(self):
        self.assertEqual(refused([('a/M.ONNX', 1)]), ['a/M.ONNX'])
        self.assertEqual(refused([('a/m.onnx.txt', 1), ('a/notes.pt.md', 1), ('a/x.ptx', 1)]), [])

    def test_size_limit_is_exact(self):
        self.assertEqual(refused([('a.bin', 5 * MB)]), [])
        self.assertEqual(refused([('a.bin', 5 * MB + 1)]), ['a.bin'])

    def test_build_and_target_directories(self):
        for p in ('build/x.txt', 'app/build/x.txt', 'native/target/debug/x', 'a/b/build/c/d.kt'):
            self.assertEqual(refused([(p, 1)]), [p], p)

    def test_similar_names_are_not_build_dirs(self):
        self.assertEqual(refused([('rebuild/x.txt', 1), ('a/targets/x', 1), ('docs/build', 1)]), [])

    def test_allow_list_allows_model_and_big_file(self):
        self.assertEqual(refused([('m/model.onnx', 21 * MB)], ['m/model.onnx']), [])
        self.assertEqual(refused([('m/model.onnx', 21 * MB)], ['m/*.onnx']), [])
        self.assertEqual(refused([('m/other.onnx', 1)], ['m/model.onnx']), ['m/other.onnx'])

    def test_allow_list_cannot_allow_build_or_target(self):
        self.assertEqual(refused([('build/m.onnx', 1)], ['build/m.onnx', '**']), ['build/m.onnx'])
        self.assertEqual(refused([('x/target/a.txt', 1)], ['x/target/**']), ['x/target/a.txt'])

    def test_spaces_and_non_ascii(self):
        p = 'assets/모델 파일/my model.onnx'
        self.assertEqual(refused([(p, 1)]), [p])
        self.assertEqual(refused([(p, 1)], [p]), [])
        self.assertEqual(refused([('assets/모델 파일/ok file.txt', 1)]), [])

    def test_allow_list_parsing(self):
        text = '# comment\n\n  a/b.onnx  \n   \n#c\nx/*.pt\r\n'
        self.assertEqual(g.parse_allow_list(text), ['a/b.onnx', 'x/*.pt'])


class ParseTest(unittest.TestCase):
    def test_ls_tree_with_unicode_and_spaces(self):
        data = record('a b/한글.onnx', 12) + record('c.txt', 3)
        self.assertEqual(g.parse_ls_tree(data), [('a b/한글.onnx', 12), ('c.txt', 3)])

    def test_submodule_has_no_size(self):
        data = ('160000 commit %s       -\tsub\0' % ('a' * 40)).encode()
        self.assertEqual(g.parse_ls_tree(data), [('sub', 0)])

    def test_malformed_raises(self):
        for bad in (b'garbage\0', b'100644 blob abc\tx\0', record('x', 'NaN'), b'1 2 3 4\0'):
            with self.assertRaises(g.GuardError, msg=bad):
                g.parse_ls_tree(bad)


class CommandLineTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def write(self, name, content):
        path = os.path.join(self.tmp.name, name)
        with open(path, 'wb') as f:
            f.write(content)
        return path

    def test_clean_input_passes(self):
        allow = self.write('allow.txt', b'# none\n')
        code, out = run(record('a/b.kt', 10), '--allow', allow)
        self.assertEqual(code, 0, out)

    def test_empty_input_passes(self):
        allow = self.write('allow.txt', b'')
        self.assertEqual(run(b'', '--allow', allow)[0], 0)

    def test_big_model_fails_and_allow_list_fixes_it(self):
        data = record('x/model.onnx', 21 * MB)
        allow = self.write('allow.txt', b'')
        code, out = run(data, '--allow', allow)
        self.assertEqual(code, 1)
        self.assertIn('REFUSED x/model.onnx', out)
        self.assertIn('large-files-allow.txt', out)
        allow2 = self.write('allow2.txt', b'# the planner said so\nx/model.onnx\n')
        self.assertEqual(run(data, '--allow', allow2)[0], 0)

    def test_allow_listed_build_path_still_fails(self):
        allow = self.write('allow.txt', b'build/x.txt\n')
        self.assertEqual(run(record('build/x.txt', 1), '--allow', allow)[0], 1)

    def test_missing_allow_list_fails(self):
        missing = os.path.join(self.tmp.name, 'nope.txt')
        self.assertEqual(run(record('a.txt', 1), '--allow', missing)[0], 1)

    def test_malformed_input_fails(self):
        allow = self.write('allow.txt', b'')
        self.assertEqual(run(b'not a listing\0', '--allow', allow)[0], 1)

    def test_bad_usage_is_not_a_pass(self):
        self.assertNotEqual(run(b'', '--bogus')[0], 0)
        self.assertNotEqual(run(b'', '--allow')[0], 0)

    def test_only_checks_listed_paths_and_ignores_deleted(self):
        allow = self.write('allow.txt', b'')
        listing = record('old/big.onnx', 1) + record('new/ok.txt', 1)
        only = self.write('only.txt', b'new/ok.txt\0deleted/gone.onnx\0')
        self.assertEqual(run(listing, '--allow', allow, '--only', only)[0], 0)
        only2 = self.write('only2.txt', b'old/big.onnx\0')
        self.assertEqual(run(listing, '--allow', allow, '--only', only2)[0], 1)

    def test_unreadable_only_file_fails(self):
        allow = self.write('allow.txt', b'')
        self.assertEqual(run(b'', '--allow', allow, '--only', os.path.join(self.tmp.name, 'x'))[0], 1)

    def test_head_tree_passes_with_the_allow_list_committed_with_it(self):
        listing = subprocess.run(['git', 'ls-tree', '-r', '-l', '-z', 'HEAD'],
                                 cwd=REPO, capture_output=True)
        self.assertEqual(listing.returncode, 0, listing.stderr)
        self.assertTrue(listing.stdout)
        code, out = run(listing.stdout)
        self.assertEqual(code, 0, out)


class AnnotationTest(unittest.TestCase):
    def test_refused_file_prints_log_line_and_annotation(self):
        with tempfile.TemporaryDirectory() as tmp:
            allow = os.path.join(tmp, 'allow.txt')
            open(allow, 'wb').close()
            code, out = run(record('x/model.onnx', 1), '--allow', allow)
        self.assertEqual(code, 1)
        self.assertIn('REFUSED x/model.onnx: .onnx file', out)
        self.assertIn('::error title=repo_guard::REFUSED x/model.onnx', out)

    def test_annotation_escapes_line_breaks(self):
        self.assertEqual(g.annotation_escape('a%b' + chr(10) + 'c' + chr(13)), 'a%25b%0Ac%0D')


class StepScriptTest(unittest.TestCase):
    """tools/ci/run_repo_guard.sh, the guard step of the changes job."""

    def setUp(self):
        self.sh = shutil.which('sh')
        self.assertIsNotNone(self.sh, 'sh is needed to run the guard step')
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = os.path.join(self.tmp.name, 'repo')
        os.mkdir(self.repo)
        self.git('init', '-q')

    def git(self, *args, cwd=None):
        p = subprocess.run(['git', '-c', 'user.name=t', '-c', 'user.email=t@example.com',
                            '-c', 'commit.gpgsign=false', *args],
                           cwd=cwd or self.repo, capture_output=True)
        self.assertEqual(p.returncode, 0, p.stderr)
        return p.stdout.decode().strip()

    def commit(self, files):
        for name, size in files.items():
            path = os.path.join(self.repo, name)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, 'wb') as f:
                f.write(b'x' * size)
        self.git('add', '-A')
        self.git('commit', '-q', '-m', 'c')
        return self.git('rev-parse', 'HEAD')

    def step(self, head, range_='', event='push', cwd=None, pr_head=''):
        env = dict(os.environ, RANGE=range_, EVENT_NAME=event, PR_HEAD=pr_head,
                   HEAD_SHA=head, RUNNER_TEMP=self.tmp.name, PYTHON=sys.executable)
        script = os.path.join(HERE, 'run_repo_guard.sh')
        p = subprocess.run([self.sh, script], cwd=cwd or self.repo, env=env, capture_output=True)
        return p.returncode, (p.stdout + p.stderr).decode('utf-8', 'replace')

    def head_commit(self):
        p = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=REPO, capture_output=True)
        self.assertEqual(p.returncode, 0, p.stderr)
        return p.stdout.decode().strip()

    def test_no_range_cases_check_the_whole_tree_and_pass_on_the_current_tree(self):
        head = self.head_commit()
        # workflow_dispatch, a push whose before is forty zeros: classify leaves the range empty.
        for event in ('workflow_dispatch', 'push'):
            code, out = self.step(head, '', event, cwd=REPO)
            self.assertEqual(code, 0, out)
            self.assertIn('checking every tracked file', out)
        # a failed git diff
        code, out = self.step(head, 'deadbeef...cafebabe', 'pull_request', cwd=REPO, pr_head=head)
        self.assertEqual(code, 0, out)
        self.assertIn('checking every tracked file', out)

    def test_no_range_cases_fail_when_the_tree_holds_a_refused_file(self):
        head = self.commit({'ok.txt': 1, 'bad/model.onnx': 1})
        for range_, event in (('', 'workflow_dispatch'), ('', 'push'), ('deadbeef..cafebabe', 'push')):
            code, out = self.step(head, range_, event)
            self.assertEqual(code, 1, (range_, event, out))
            self.assertIn('REFUSED bad/model.onnx', out)

    def test_range_checks_only_the_changed_files(self):
        first = self.commit({'old/model.onnx': 1})
        second = self.commit({'new/ok.txt': 1})
        code, out = self.step(second, first + '..' + second)
        self.assertEqual(code, 0, out)
        self.assertNotIn('checking every tracked file', out)
        third = self.commit({'new/big.bin': g.MAX_BYTES + 1})
        code, out = self.step(third, second + '..' + third)
        self.assertEqual(code, 1, out)
        self.assertIn('REFUSED new/big.bin', out)

    def test_deletion_in_range_is_ignored(self):
        first = self.commit({'old/model.onnx': 1, 'a.txt': 1})
        os.remove(os.path.join(self.repo, 'old', 'model.onnx'))
        self.git('add', '-A')
        self.git('commit', '-q', '-m', 'd')
        second = self.git('rev-parse', 'HEAD')
        self.assertEqual(self.step(second, first + '..' + second)[0], 0)

    def test_pull_request_uses_the_pr_head_not_the_merge_commit(self):
        clean = self.commit({'ok.txt': 1})
        bad = self.commit({'bad/model.onnx': 1})
        code, out = self.step(clean, clean + '..' + bad, 'pull_request', pr_head=bad)
        self.assertEqual(code, 1, out)


if __name__ == '__main__':
    unittest.main()
