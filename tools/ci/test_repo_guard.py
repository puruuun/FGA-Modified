import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(__file__))
import repo_guard as g

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, 'repo_guard.py')
REPO = os.path.dirname(os.path.dirname(HERE))
MB = 1024 * 1024


def record(path, size, kind='blob'):
    return ('100644 %s %s %7s\t%s\0' % (kind, 'a' * 40, size, path)).encode('utf-8')


def run(stdin, *args, cwd=None, **env):
    p = subprocess.run([sys.executable, SCRIPT, *args], input=stdin, capture_output=True, cwd=cwd,
                       env=dict(os.environ, **env))
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

    def test_build_and_target_directories_in_any_case(self):
        for p in ('Build/x.txt', 'app/BUILD/x.txt', 'native/TARGET/x', 'a/Target/b/c'):
            self.assertEqual(refused([(p, 1)]), [p], p)

    def test_allow_list_entry_is_anchored(self):
        self.assertEqual(refused([('evil/m/model.onnx', 1)], ['m/model.onnx']), ['evil/m/model.onnx'])
        self.assertEqual(refused([('m/model.onnx.bak.onnx', 1)], ['m/model.onnx']), ['m/model.onnx.bak.onnx'])

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

    def test_empty_listing_fails(self):
        allow = self.write('allow.txt', b'')
        self.assertEqual(run(b'', '--allow', allow)[0], 1)
        only = self.write('only.txt', b'')
        self.assertEqual(run(b'', '--allow', allow, '--only', only)[0], 1)

    def test_label_names_the_commit(self):
        allow = self.write('allow.txt', b'')
        code, out = run(record('x/m.onnx', 1), '--allow', allow, '--label', 'abc1234')
        self.assertEqual(code, 1)
        self.assertIn('REFUSED x/m.onnx (in commit abc1234)', out)

    def test_korean_and_japanese_names_stay_readable(self):
        allow = self.write('allow.txt', b'')
        for name in ('assets/' + chr(0xBAA8) + chr(0xB378) + '.onnx', 'a/' + chr(0x30E2) + chr(0x30C7) + chr(0x30EB) + '.pt'):
            # A console encoding that cannot hold the name must not matter: the output is UTF-8 bytes.
            code, out = run(record(name, 1), '--allow', allow, PYTHONIOENCODING='ascii')
            self.assertEqual(code, 1)
            self.assertIn('REFUSED ' + name + ':', out)

    def test_undecodable_bytes_and_separators_are_escaped(self):
        allow = self.write('allow.txt', b'')
        data = (b'100644 blob ' + b'a' * 40 + b'       1' + bytes([9]) + b'a/' + bytes([0xff]) + b'x'
                + chr(0x2028).encode() + chr(0x85).encode() + b'.onnx' + bytes([0]))
        code, out = run(data, '--allow', allow)
        self.assertEqual(code, 1)
        self.assertIn('a/' + chr(92) + 'xffx' + chr(92) + 'u2028' + chr(92) + 'x85.onnx', out)
        self.assertEqual(len(out.splitlines()), 3, out)

    def test_path_with_line_break_cannot_forge_a_log_line(self):
        allow = self.write('allow.txt', b'')
        path = 'a' + chr(10) + '::warning::forged.onnx'
        code, out = run(record(path, 1), '--allow', allow)
        self.assertEqual(code, 1)
        for line in out.splitlines():
            self.assertTrue(line.startswith(('REFUSED ', '::error title=repo_guard::REFUSED ', 'repo_guard:')), line)
        self.assertIn('a' + chr(92) + 'x0a::warning::forged.onnx', out)

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

    def commit(self, files, seed_allow_list=True):
        # The head commit carries the allow-list, as the real repository does.
        files = dict(files)
        if seed_allow_list and not os.path.exists(os.path.join(self.repo, 'tools', 'ci', 'large-files-allow.txt')):
            os.makedirs(os.path.join(self.repo, 'tools', 'ci'))
            shutil.copy(os.path.join(HERE, 'large-files-allow.txt'), os.path.join(self.repo, 'tools', 'ci'))
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

    def delete_commit(self, name):
        os.remove(os.path.join(self.repo, name))
        self.git('add', '-A')
        self.git('commit', '-q', '-m', 'delete')
        return self.git('rev-parse', 'HEAD')

    def test_file_added_and_deleted_inside_the_range_is_refused(self):
        base = self.commit({'ok.txt': 1})
        added = self.commit({'tmp/big.bin': g.MAX_BYTES + 1})
        head = self.delete_commit('tmp/big.bin')
        for range_, event in ((base + '..' + head, 'push'), (base + '...' + head, 'pull_request')):
            code, out = self.step(head, range_, event, pr_head=head)
            self.assertEqual(code, 1, (range_, out))
            self.assertIn('REFUSED tmp/big.bin', out)
            self.assertIn('(in commit %s)' % self.git('rev-parse', '--short', added), out)

    def test_normal_multi_commit_range_passes(self):
        base = self.commit({'ok.txt': 1})
        self.commit({'a/one.txt': 10})
        head = self.commit({'a/two.txt': 10})
        for range_, event in ((base + '..' + head, 'push'), (base + '...' + head, 'pull_request')):
            code, out = self.step(head, range_, event, pr_head=head)
            self.assertEqual(code, 0, (range_, out))

    def test_merge_of_the_base_does_not_recheck_the_base_files(self):
        root = self.commit({'ok.txt': 1})
        self.git('checkout', '-q', '-b', 'topic')
        self.commit({'topic/a.txt': 1})
        self.git('checkout', '-q', '-')
        # The base moves on and holds a file the guard would refuse; it is not part of this change.
        base = self.commit({'oldbase/legacy.onnx': 1})
        self.git('checkout', '-q', 'topic')
        self.git('merge', '-q', '--no-edit', base)
        head = self.git('rev-parse', 'HEAD')
        code, out = self.step(head, base + '...' + head, 'pull_request', pr_head=head)
        self.assertEqual(code, 0, out)
        self.assertNotEqual(root, head)

    def merge_base_into_topic(self, base, evil_files=None):
        self.git('checkout', '-q', 'topic')
        if evil_files is None:
            self.git('merge', '-q', '--no-edit', base)
            return self.git('rev-parse', 'HEAD')
        self.git('merge', '-q', '--no-commit', '--no-ff', base)
        for name, size in evil_files.items():
            path = os.path.join(self.repo, name)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, 'wb') as f:
                f.write(b'x' * size)
        self.git('add', '-A')
        self.git('commit', '-q', '-m', 'merge with a resolution')
        return self.git('rev-parse', 'HEAD')

    def start_topic(self):
        self.commit({'ok.txt': 1})
        self.git('checkout', '-q', '-b', 'topic')
        self.commit({'topic/a.txt': 1})
        self.git('checkout', '-q', '-')

    def test_file_introduced_by_a_merge_and_deleted_later_is_refused(self):
        self.start_topic()
        base = self.commit({'base/b.txt': 1})
        merge = self.merge_base_into_topic(base, {'tmp/evil.bin': g.MAX_BYTES + 1})
        head = self.delete_commit('tmp/evil.bin')
        code, out = self.step(head, base + '...' + head, 'pull_request', pr_head=head)
        self.assertEqual(code, 1, out)
        self.assertIn('REFUSED tmp/evil.bin', out)
        self.assertIn('(in commit %s)' % merge[:7], out)

    def test_clean_merge_of_a_base_with_a_large_file_is_not_rechecked(self):
        self.start_topic()
        base = self.commit({'base/huge.bin': g.MAX_BYTES + 1, 'base/m.onnx': 1})
        head = self.merge_base_into_topic(base)
        code, out = self.step(head, base + '...' + head, 'pull_request', pr_head=head)
        self.assertEqual(code, 0, out)

    def test_base_commits_that_added_and_deleted_a_large_file_are_not_checked(self):
        self.start_topic()
        self.commit({'base/huge.bin': g.MAX_BYTES + 1})
        base = self.delete_commit('base/huge.bin')
        self.git('checkout', '-q', 'topic')
        head = self.commit({'topic/b.txt': 1})
        code, out = self.step(head, base + '...' + head, 'pull_request', pr_head=head)
        self.assertEqual(code, 0, out)

    def test_file_named_like_a_commit_id_is_a_path_of_its_commit(self):
        base = self.commit({'ok.txt': 1})
        # A file named with the id of a commit outside the range, as the verifier constructed it.
        for name, size in ((base + 'z-evil.onnx', 1), (base + 'z-big.bin', g.MAX_BYTES + 1)):
            added = self.commit({name: size})
            head = self.delete_commit(name)
            code, out = self.step(head, base + '..' + head)
            self.assertEqual(code, 1, (name, out))
            self.assertIn('REFUSED ' + name, out)
            self.assertIn('(in commit %s)' % added[:7], out)
            base = head

    def test_file_named_like_a_commit_inside_the_range_is_a_path(self):
        base = self.commit({'ok.txt': 1})
        inside = self.commit({'a.txt': 1})
        for name, size in ((inside + 'x.onnx', 1), (inside, g.MAX_BYTES + 1)):
            added = self.commit({name: size})
            head = self.delete_commit(name)
            code, out = self.step(head, base + '..' + head)
            self.assertEqual(code, 1, (name, out))
            self.assertIn('(in commit %s)' % added[:7], out)
            base = head
            inside = head

    def wrapper(self, failing_flag):
        path = os.path.join(self.tmp.name, 'python-wrapper.sh')
        real = sys.executable.replace(chr(92), '/')
        with open(path, 'w', newline=chr(10)) as f:
            f.write('#!/bin/sh' + chr(10))
            f.write('case " $* " in *" %s "*) echo "fake failure of %s"; exit 1;; esac' % (failing_flag, failing_flag) + chr(10))
            f.write('exec "%s" "$@"' % real + chr(10))
        os.chmod(path, 0o755)
        return path.replace(chr(92), '/')

    def run_with(self, python, head, range_):
        env = dict(os.environ, RANGE=range_, EVENT_NAME='push', PR_HEAD='', HEAD_SHA=head,
                   RUNNER_TEMP=self.tmp.name, PYTHON=python)
        script = os.path.join(HERE, 'run_repo_guard.sh')
        p = subprocess.run([self.sh, script], cwd=self.repo, env=env, capture_output=True)
        return p.returncode, (p.stdout + p.stderr).decode('utf-8', 'replace')

    def test_both_checks_run_even_when_the_net_diff_check_fails(self):
        base = self.commit({'ok.txt': 1})
        head = self.commit({'bad/m.onnx': 1})
        # The net-diff check is replaced by a failure; the history check must still run and report.
        code, out = self.run_with(self.wrapper('--only'), head, base + '..' + head)
        self.assertNotEqual(code, 0, out)
        self.assertIn('fake failure of --only', out)
        self.assertIn('REFUSED bad/m.onnx (in commit', out)

    def test_a_failing_net_diff_check_alone_fails_the_step(self):
        base = self.commit({'ok.txt': 1})
        head = self.commit({'a.txt': 1})
        self.assertEqual(self.run_with(sys.executable, head, base + '..' + head)[0], 0)
        code, out = self.run_with(self.wrapper('--only'), head, base + '..' + head)
        self.assertNotEqual(code, 0, out)

    def test_a_failing_history_check_fails_the_step_even_when_the_net_diff_check_passes(self):
        base = self.commit({'ok.txt': 1})
        head = self.commit({'a.txt': 1})
        code, out = self.run_with(self.wrapper('--history'), head, base + '..' + head)
        self.assertNotEqual(code, 0, out)

    def test_head_without_an_allow_list_fails(self):
        head = self.commit({'ok.txt': 1}, seed_allow_list=False)
        code, out = self.step(head, '', 'push')
        self.assertNotEqual(code, 0, out)

    def test_invalid_head_commit_fails(self):
        self.commit({'ok.txt': 1})
        code, out = self.step('0' * 40, '', 'push')
        self.assertNotEqual(code, 0, out)
        code, out = self.step('0' * 40, 'a..b', 'pull_request', pr_head='f' * 40)
        self.assertNotEqual(code, 0, out)

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


def meta(mode, blob, status=b'A'):
    return b':000000 ' + mode + b' ' + b'0' * 40 + b' ' + blob + b' ' + status


class ParseHistoryTest(unittest.TestCase):
    BLOB = b'a' * 40
    C1 = 'c1' * 20
    C2 = 'd2' * 20

    def out(self, *tokens):
        return b''.join(t + bytes([0]) for t in tokens)

    def test_paths_that_look_like_headers_or_metadata_are_paths(self):
        out = self.out(self.C1.encode(), meta(b'100644', self.BLOB), self.C2.encode(),
                       self.C1.encode(), meta(b'100644', self.BLOB), self.C1.encode(),
                       meta(b'100644', self.BLOB), b':000000 fake')
        records = g.parse_diff_tree(out)
        self.assertEqual([(r[0], r[3]) for r in records],
                         [(self.C1, self.C2.encode()), (self.C1, self.C1.encode()),
                          (self.C1, b':000000 fake')])
        # The same name after a header of another commit stays a path of the commit it follows.
        out = self.out(self.C1.encode(), meta(b'100644', self.BLOB), b'p',
                       self.C2.encode(), meta(b'100644', self.BLOB), self.C1.encode() + b'z-evil.onnx')
        self.assertEqual([(r[0], r[3]) for r in g.parse_diff_tree(out)],
                         [(self.C1, b'p'), (self.C2, self.C1.encode() + b'z-evil.onnx')])

    def test_path_with_line_break_is_handled_and_escaped(self):
        path = b'x' + chr(10).encode() + b'::warning::y.onnx'
        records = g.parse_diff_tree(self.out(self.C1.encode(), meta(b'100644', self.BLOB), path))
        self.assertEqual(records[0][3], path)
        shown = g.printable(path.decode())
        self.assertNotIn(chr(10), shown)

    def test_deletion_is_skipped_and_gitlink_is_kept(self):
        gone = b':100644 000000 ' + self.BLOB + b' ' + b'0' * 40 + b' D'
        out = self.out(self.C1.encode(), gone, b'old', meta(b'160000', self.BLOB), b'sub')
        self.assertEqual([(r[1], r[3]) for r in g.parse_diff_tree(out)], [(b'160000', b'sub')])

    def test_combined_merge_record(self):
        combined = b'::000000 000000 100644 ' + b'0' * 40 + b' ' + b'0' * 40 + b' ' + self.BLOB + b' AA'
        records = g.parse_diff_tree(self.out(self.C1.encode(), combined, b'evil.bin'))
        self.assertEqual([(r[1], r[2], r[3]) for r in records], [(b'100644', self.BLOB, b'evil.bin')])

    def test_malformed_output_fails(self):
        for bad in (self.out(meta(b'100644', self.BLOB), b'p'),  # a record before any header
                    self.out(self.C1.encode(), meta(b'100644', self.BLOB)),  # metadata without a path
                    self.out(self.C1.encode(), meta(b'100644', self.BLOB), b''),
                    self.out(self.C1.encode(), b':too few fields', b'p'),
                    self.out(self.C1.encode(), b'', b'p')):
            with self.assertRaises(g.GuardError, msg=bad):
                g.parse_diff_tree(bad)

    def test_missing_object_is_a_failure_not_a_skipped_file(self):
        ident = self.BLOB
        with self.assertRaises(g.GuardError):
            g.parse_batch_check([ident], ident + b' missing' + chr(10).encode(), b'blob')

    def test_batch_check_answers_are_checked(self):
        nl = chr(10).encode()
        a, b = b'a' * 40, b'b' * 40
        self.assertEqual(g.parse_batch_check([a, b], a + b' blob 5' + nl + b + b' blob 7' + nl, b'blob'), [5, 7])
        for answers in (a + b' blob 5' + nl,  # too few
                        a + b' blob 5' + nl + b + b' blob 7' + nl + a + b' blob 1' + nl,  # too many
                        a + b' tree 5' + nl + b + b' blob 7' + nl,  # wrong type
                        a + b' blob x' + nl + b + b' blob 7' + nl,  # size is not a number
                        b + b' blob 5' + nl + a + b' blob 7' + nl):  # answers for other ids
            with self.assertRaises(g.GuardError, msg=answers):
                g.parse_batch_check([a, b], answers, b'blob')

    def test_a_failing_git_fails(self):
        with self.assertRaises(g.GuardError):
            g.git_output(['no-such-subcommand'], b'')


class HistoryCommandTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = os.path.join(self.tmp.name, 'repo')
        os.mkdir(self.repo)
        self.git('init', '-q')
        self.allow = os.path.join(self.tmp.name, 'allow.txt')
        open(self.allow, 'wb').close()

    def git(self, *args):
        p = subprocess.run(['git', '-c', 'user.name=t', '-c', 'user.email=t@example.com',
                            '-c', 'commit.gpgsign=false', *args], cwd=self.repo, capture_output=True)
        self.assertEqual(p.returncode, 0, p.stderr)
        return p.stdout.decode().strip()

    def commit(self, files):
        for name, size in files.items():
            with open(os.path.join(self.repo, name), 'wb') as f:
                f.write(b'x' * size)
        self.git('add', '-A')
        self.git('commit', '-q', '-m', 'c')
        return self.git('rev-parse', 'HEAD')

    def history(self, *commits, cwd=None):
        path = os.path.join(self.tmp.name, 'commits')
        with open(path, 'w') as f:
            f.write(chr(10).join(commits))
        return run(b'', '--allow', self.allow, '--history', path, cwd=cwd or self.repo)

    def test_renamed_and_copied_files_are_refused_under_their_new_path(self):
        self.commit({'plain.txt': 3000, 'src.txt': 3000})
        os.rename(os.path.join(self.repo, 'plain.txt'), os.path.join(self.repo, 'renamed.onnx'))
        # The source is modified in the same commit so that git can detect the copy.
        with open(os.path.join(self.repo, 'src.txt'), 'a', newline=chr(10)) as f:
            f.write('one more line' + chr(10))
        shutil.copy(os.path.join(self.repo, 'src.txt'), os.path.join(self.repo, 'copied.pt'))
        self.git('add', '-A')
        self.git('commit', '-q', '-m', 'rename and copy')
        head = self.git('rev-parse', 'HEAD')
        shown = self.git('diff-tree', '--no-commit-id', '-r', '-M', '-C', '--name-status', head)
        self.assertIn('R', shown)  # the construction really is a rename and a copy for git
        self.assertIn('C', shown)
        code, out = self.history(head)
        self.assertEqual(code, 1, out)
        self.assertIn('REFUSED renamed.onnx', out)
        self.assertIn('REFUSED copied.pt', out)

    def test_root_commit_is_checked(self):
        root = self.commit({'model.onnx': 1})
        code, out = self.history(root)
        self.assertEqual(code, 1, out)
        self.assertIn('REFUSED model.onnx (in commit %s)' % root[:7], out)

    def test_clean_history_passes(self):
        first = self.commit({'a.txt': 1})
        second = self.commit({'b.txt': 2})
        self.assertEqual(self.history(first, second)[0], 0)

    def test_commit_id_format_is_checked(self):
        self.commit({'a.txt': 1})
        for bad in ('xyz', 'A' * 40, 'a' * 39, 'a' * 41, 'HEAD'):
            self.assertEqual(self.history(bad)[0], 1, bad)

    def test_unknown_commit_id_fails(self):
        self.commit({'a.txt': 1})
        code, out = self.history('0123456789abcdef' * 2 + '01234567')
        self.assertEqual(code, 1, out)

    def test_git_failure_fails_the_guard(self):
        root = self.commit({'a.txt': 1})
        outside = os.path.join(self.tmp.name, 'not-a-repo')
        os.mkdir(outside)
        code, out = self.history(root, cwd=outside)
        self.assertEqual(code, 1, out)
        self.assertIn('git', out)

    def test_submodule_entry_is_size_zero_and_the_path_rules_apply(self):
        root = self.commit({'a.txt': 1})
        self.git('update-index', '--add', '--cacheinfo', '160000,%s,sub' % root)
        self.git('commit', '-q', '-m', 'gitlink')
        ok = self.git('rev-parse', 'HEAD')
        code, out = self.history(ok)
        self.assertEqual(code, 0, out)
        self.git('update-index', '--add', '--cacheinfo', '160000,%s,sub.onnx' % root)
        self.git('commit', '-q', '-m', 'gitlink model')
        code, out = self.history(self.git('rev-parse', 'HEAD'))
        self.assertEqual(code, 1, out)
        self.assertIn('REFUSED sub.onnx', out)



class RenameAndProtectionTest(unittest.TestCase):
    BLOB = b'a' * 40

    def out(self, *tokens):
        return b''.join(t + bytes([0]) for t in tokens)

    def test_rename_and_copy_records_fail_instead_of_being_misread(self):
        commit = ('c1' * 20).encode()
        for status in (b'R100', b'C075', b'MR'):
            record = b':100644 100644 ' + self.BLOB + b' ' + self.BLOB + b' ' + status
            with self.assertRaises(g.GuardError, msg=status):
                g.parse_diff_tree(self.out(commit, record, b'old.txt', b'new.onnx'))

    def test_header_that_was_not_asked_for_fails(self):
        asked = 'c1' * 20
        other = ('d2' * 20).encode()
        nl = chr(10).encode()
        commit_answer = asked.encode() + b' commit 1' + nl
        record = b':000000 100644 ' + b'0' * 40 + b' ' + self.BLOB + b' A'
        diff = self.out(other, record, b'a.txt')
        blob_answer = self.BLOB + b' blob 1' + nl
        with mock.patch.object(g, 'git_output', side_effect=[commit_answer, diff, blob_answer]):
            with self.assertRaises(g.GuardError) as caught:
                g.history_entries([asked])
        self.assertIn('not asked for', str(caught.exception))

    def test_unexpected_exception_exits_non_zero(self):
        messages = []
        with tempfile.TemporaryDirectory() as tmp:
            allow = os.path.join(tmp, 'allow.txt')
            open(allow, 'wb').close()
            with mock.patch.object(g, 'load_allow_list', side_effect=KeyError('boom')), \
                    mock.patch.object(g, 'emit', side_effect=messages.append):
                code = g.main(['repo_guard.py', '--allow', allow])
        self.assertEqual(code, 1)
        self.assertTrue(any('KeyError' in m for m in messages), messages)

    def test_unreadable_history_file_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            allow = os.path.join(tmp, 'allow.txt')
            open(allow, 'wb').close()
            code, out = run(b'', '--allow', allow, '--history', os.path.join(tmp, 'missing.txt'))
        self.assertEqual(code, 1, out)
        self.assertIn('cannot read', out)


if __name__ == '__main__':
    unittest.main()
