"""Tests for the workflow files that carry the Main CI gate.

Standard library only: the CI runner has no PyYAML, so the workflow text is read with small parsers
that fail loudly on anything they do not understand.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import ci_gate as g

HERE = os.path.dirname(os.path.abspath(__file__))
WORKFLOWS = os.path.join(HERE, '..', '..', '.github', 'workflows')
MAIN_CI = os.path.join(WORKFLOWS, 'main-ci.yml')
NATIVE_CI = os.path.join(WORKFLOWS, 'native-ci.yml')
ENGINE_CI = os.path.join(WORKFLOWS, 'engine-apk.yml')
REPO = os.path.join(HERE, '..', '..')
GATE_SCRIPT = os.path.join(HERE, 'ci_gate.py')


def read(path):
    with open(path, encoding='utf-8', newline='') as f:
        return f.read().replace('\r\n', '\n')


def strip_comments(text):
    """Drops whole-line comments and trailing ' # ...' comments, so that a commented-out line cannot satisfy a check."""
    out = []
    for line in text.split('\n'):
        if line.strip().startswith('#'):
            continue
        out.append(re.sub(r'\s+#.*$', '', line))
    return '\n'.join(out)


def job_block(text, name):
    """The lines of one job (from its two-space-indented key to the next job), comments removed."""
    lines = strip_comments(text).split('\n')
    try:
        start = lines.index('  %s:' % name)
    except ValueError:
        raise AssertionError('job %r not found' % name)
    end = len(lines)
    for i in range(start + 1, len(lines)):
        if re.match(r'^  \S', lines[i]) or re.match(r'^\S', lines[i]):
            end = i
            break
    return '\n'.join(lines[start:end]).rstrip('\n')


def top_level_block(text, key):
    """The indented lines under a top-level key (permissions, concurrency), comments removed."""
    lines = strip_comments(text).split('\n')
    try:
        start = lines.index('%s:' % key)
    except ValueError:
        raise AssertionError('top-level key %r not found' % key)
    block = []
    for line in lines[start + 1:]:
        if line.strip() == '':
            if block:
                break
            continue
        if not line.startswith(' '):
            break
        block.append(line.strip())
    return block


def classify_run_block(main_ci_text):
    """The shell text of the step with id: classify, taken from the raw file."""
    lines = main_ci_text.split('\n')
    steps = []
    current = None
    for line in lines:
        if line.startswith('      - '):
            current = [line]
            steps.append(current)
        elif current is not None:
            current.append(line)
    found = [s for s in steps if any(l.strip() == 'id: classify' for l in s)]
    if len(found) != 1:
        raise AssertionError('expected one step with id: classify, found %d' % len(found))
    step = found[0]
    idx = [i for i, l in enumerate(step) if l == '        run: |']
    if len(idx) != 1:
        raise AssertionError('the classify step has no "run: |" block')
    body = []
    for line in step[idx[0] + 1:]:
        if line.strip() == '':
            body.append('')
        elif line.startswith('          '):
            body.append(line[10:])
        else:
            break
    script = '\n'.join(body).strip('\n')
    if 'GITHUB_OUTPUT' not in script:
        raise AssertionError('the classify run block does not write GITHUB_OUTPUT')
    return script + '\n'


def parse_paths_filters(text):
    """Every paths: list of the file, as a list of lists. Fails on any line it cannot parse."""
    lines = text.split('\n')
    result = []
    i = 0
    while i < len(lines):
        m = re.match(r'^(\s*)paths(-ignore)?:(.*)$', lines[i])
        if not m:
            i += 1
            continue
        indent = len(m.group(1))
        if m.group(2):
            raise AssertionError('paths-ignore is not supported: %r' % lines[i])
        if re.sub(r'\s*#.*$', '', m.group(3)).strip() != '':
            raise AssertionError('paths must be a block list: %r' % lines[i])
        items = []
        i += 1
        while i < len(lines):
            line = lines[i]
            stripped = line.strip()
            if stripped == '' or stripped.startswith('#'):
                i += 1
                continue
            if len(line) - len(line.lstrip(' ')) <= indent:
                break
            item = re.match(
                r'''^\s*-\s+(?:'([^']*)'|"([^"]*)"|([^\s'"#][^\s#]*))\s*(?:#.*)?$''', line)
            if not item:
                raise AssertionError('cannot parse a paths entry: %r' % line)
            items.append(next(v for v in item.groups() if v is not None))
            i += 1
        result.append(items)
    return result


def need_tool(case, name):
    if shutil.which(name) is None:
        case.fail('%s is not on PATH; this test needs it and must not be skipped' % name)


class PathsParserTest(unittest.TestCase):
    def test_quoting_styles_and_comments(self):
        text = ("on:\n  push:\n    paths:\n      - 'a/**'  # why\n      - \"b/**\"\n"
                "      # a comment line\n      - c/**\n  other:\n    x: 1\n")
        self.assertEqual(parse_paths_filters(text), [['a/**', 'b/**', 'c/**']])

    def test_unreadable_entries_fail(self):
        for bad in ("      - 'a' 'b'\n", "      - \n", "      - {a: b}\n", "      key: v\n"):
            with self.assertRaises(AssertionError, msg=bad):
                parse_paths_filters('on:\n  push:\n    paths:\n' + bad)
        with self.assertRaises(AssertionError):
            parse_paths_filters("on:\n  push:\n    paths: ['a']\n")
        with self.assertRaises(AssertionError):
            parse_paths_filters("on:\n  push:\n    paths-ignore:\n      - 'a'\n")


class NativePathsTest(unittest.TestCase):
    def test_every_paths_filter_equals_native_patterns(self):
        filters = parse_paths_filters(read(NATIVE_CI))
        self.assertTrue(filters, 'no paths: filter found in native-ci.yml')
        for items in filters:
            self.assertEqual(sorted(items), sorted(g.NATIVE_PATTERNS))

    def test_the_only_paths_filter_belongs_to_push(self):
        text = strip_comments(read(NATIVE_CI))
        on_block = text[text.index('\non:\n'):text.index('\npermissions:\n')]
        self.assertEqual(on_block.count('paths:'), 1)
        self.assertRegex(on_block, r'(?s)\n  push:\n    branches: \[main\]\n    paths:\n')
        self.assertRegex(on_block, r'\n  workflow_call:\n')
        self.assertRegex(on_block, r'\n  workflow_dispatch:\n')
        self.assertNotRegex(on_block, r'\n  pull_request')


def github_paths_match(patterns, path):
    """GitHub's paths filter: the last pattern that matches decides, and a pattern with ! excludes."""
    matched = False
    for pattern in patterns:
        negated = pattern.startswith('!')
        if g._glob_to_regex(pattern[1:] if negated else pattern).match(path):
            matched = not negated
    return matched


class EnginePathsTest(unittest.TestCase):
    """engine-apk.yml must run on push for exactly the changes that Main CI classifies as code."""

    def filters(self):
        filters = parse_paths_filters(read(ENGINE_CI))
        self.assertEqual(len(filters), 1, 'expected one paths: filter in engine-apk.yml')
        return filters[0]

    def test_filter_is_code_classification(self):
        self.assertEqual(self.filters(),
                         ['**'] + ['!' + p for p in g.DOC_PATTERNS] + list(g.NATIVE_PATTERNS))

    def test_filter_agrees_with_classify_on_every_tracked_file_and_samples(self):
        r = subprocess.run(['git', 'ls-files', '-z'], cwd=REPO, capture_output=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        paths = [p for p in r.stdout.decode('utf-8', 'surrogateescape').split('\0') if p]
        self.assertGreater(len(paths), 100)
        paths += ['README.md', 'docs/a.md', 'native/AGENTS.md', 'native/README.md', 'sub/AGENTS.md',
                  'app/src/A.kt', '.github/workflows/engine-apk.yml', '.gitattributes', 'wiki/x/y.png',
                  'app/README.md', 'LICENSE', 'LICENSE.txt']
        patterns = self.filters()
        for path in paths:
            self.assertEqual(github_paths_match(patterns, path), g.needs_build([path]), path)

    def test_the_only_paths_filter_belongs_to_push(self):
        text = strip_comments(read(ENGINE_CI))
        on_block = text[text.index('\non:\n'):text.index('\npermissions:\n')]
        self.assertEqual(on_block.count('paths:'), 1)
        self.assertRegex(on_block, r'(?s)\n  push:\n    branches: \[main\]\n    paths:\n')
        self.assertRegex(on_block, r'\n  workflow_call:\n')
        self.assertNotRegex(on_block, r'\n  pull_request')

    def test_github_paths_match(self):
        self.assertTrue(github_paths_match(['**', '!docs/**', 'docs/x/**'], 'docs/x/a'))
        self.assertFalse(github_paths_match(['**', '!docs/**'], 'docs/a'))
        self.assertTrue(github_paths_match(['**', '!docs/**'], 'app/a'))


class EngineApkJobTest(unittest.TestCase):
    def setUp(self):
        self.job = job_block(read(ENGINE_CI), 'engine-apk')

    def test_uses_the_pinned_ndk_only(self):
        self.assertNotIn('ANDROID_NDK_LATEST_HOME', self.job)
        self.assertIn('native/ndk-version.txt', self.job)
        self.assertIn('test "$revision" = "$NDK_VERSION"', self.job)
        self.assertIn('echo "ANDROID_NDK_HOME=$NDK_DIR" >> "$GITHUB_ENV"', self.job)

    def test_builds_and_checks_the_beta(self):
        self.assertIn('./gradlew :app:assembleBeta -PfgaDebugSigning=true', self.job)
        self.assertIn('python3 tools/ci/apk_check.py present "$@"', self.job)

    def test_ndk_pin_file_names_one_version(self):
        with open(os.path.join(REPO, 'native', 'ndk-version.txt'), encoding='utf-8') as f:
            lines = [l.strip() for l in f if l.strip() and not l.startswith('#')]
        self.assertEqual(len(lines), 1, lines)
        self.assertRegex(lines[0], r'^[0-9]+\.[0-9]+\.[0-9]+$')

    def test_cargo_ndk_version_equal_in_both_workflows(self):
        def version(path):
            found = re.findall(r'(?m)^  CARGO_NDK_VERSION: (\S+)$', strip_comments(read(path)))
            self.assertEqual(len(found), 1, path)
            return found[0]
        self.assertEqual(version(NATIVE_CI), version(ENGINE_CI))


class NativeCiSettingsTest(unittest.TestCase):
    def test_concurrency_group_and_cancel(self):
        for path, prefix in ((NATIVE_CI, 'native-ci-'), (ENGINE_CI, 'engine-apk-')):
            block = top_level_block(read(path), 'concurrency')
            groups = [l for l in block if l.startswith('group:')]
            self.assertEqual(len(groups), 1, block)
            value = groups[0][len('group:'):].strip()
            self.assertTrue(value.startswith(prefix), value)
            for part in ('github.workflow', 'github.event_name', 'github.ref'):
                self.assertIn(part, value)
            self.assertIn('cancel-in-progress: true', block)

    def test_permissions_equal_in_all_three_workflows(self):
        main = top_level_block(read(MAIN_CI), 'permissions')
        self.assertEqual(main, ['contents: read'])
        for path in (NATIVE_CI, ENGINE_CI):
            self.assertEqual(top_level_block(read(path), 'permissions'), main, path)
        jobs = {MAIN_CI: ('native', 'engine-apk', 'gate', 'changes', 'test-and-build'),
                NATIVE_CI: ('rust',), ENGINE_CI: ('engine-apk',)}
        for path, names in jobs.items():
            for name in names:
                self.assertNotIn('permissions:', job_block(read(path), name), (path, name))

    def test_native_ci_holds_only_the_rust_job(self):
        text = strip_comments(read(NATIVE_CI))
        jobs = re.findall(r'(?m)^  ([A-Za-z0-9_-]+):$', text[text.index('\njobs:\n'):])
        self.assertEqual(jobs, ['rust'])


class WorkflowWiringTest(unittest.TestCase):
    def setUp(self):
        self.text = read(MAIN_CI)

    def test_native_job(self):
        block = job_block(self.text, 'native').split('\n')
        self.assertEqual(block, [
            '  native:',
            '    needs: changes',
            "    if: needs.changes.outputs.native == 'true'",
            '    uses: ./.github/workflows/native-ci.yml',
        ])

    def test_engine_apk_job_runs_for_every_code_change(self):
        block = job_block(self.text, 'engine-apk').split('\n')
        self.assertEqual(block, [
            '  engine-apk:',
            '    needs: changes',
            "    if: needs.changes.outputs.code == 'true'",
            '    uses: ./.github/workflows/engine-apk.yml',
        ])

    def test_gate_job(self):
        lines = [l.strip() for l in job_block(self.text, 'gate').split('\n')]
        for want in ('name: Main CI gate',
                     'needs: [changes, test-and-build, native, engine-apk]',
                     'if: always()',
                     'CHANGES_RESULT: ${{ needs.changes.result }}',
                     'CODE: ${{ needs.changes.outputs.code }}',
                     'NATIVE: ${{ needs.changes.outputs.native }}',
                     'BUILD_RESULT: ${{ needs.test-and-build.result }}',
                     'NATIVE_RESULT: ${{ needs.native.result }}',
                     'ENGINE_RESULT: ${{ needs.engine-apk.result }}',
                     'run: python3 tools/ci/ci_gate.py gate "$CHANGES_RESULT" "$CODE" "$NATIVE" '
                     '"$BUILD_RESULT" "$NATIVE_RESULT" "$ENGINE_RESULT"'):
            self.assertEqual(lines.count(want), 1, want)

    def test_changes_job_outputs(self):
        lines = [l.strip() for l in job_block(self.text, 'changes').split('\n')]
        self.assertIn('code: ${{ steps.classify.outputs.code }}', lines)
        self.assertIn('native: ${{ steps.classify.outputs.native }}', lines)

    def test_native_ci_is_defined_once(self):
        # The Rust steps and the engine build must not be copied into the caller.
        self.assertNotIn('cargo ', strip_comments(self.text))
        self.assertNotIn('assembleBeta', strip_comments(self.text))

    def test_job_block_ignores_comments(self):
        text = "jobs:\n  a:\n    # if: x == 'true'\n    needs: b  # if: y\n  c:\n    d: 1\n"
        self.assertEqual(job_block(text, 'a'), '  a:\n    needs: b')


class ClassifyStepTest(unittest.TestCase):
    """Runs the shell block of the classify step of main-ci.yml in a throw-away git repository."""

    @classmethod
    def setUpClass(cls):
        cls.script = classify_run_block(read(MAIN_CI))

    def setUp(self):
        need_tool(self, 'sh')
        need_tool(self, 'git')
        self.tmp = tempfile.mkdtemp(prefix='classify-')
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.repo = os.path.join(self.tmp, 'repo')
        self.runner_temp = os.path.join(self.tmp, 'runner')
        self.shim = os.path.join(self.tmp, 'shim')
        for d in (self.repo, self.runner_temp, self.shim):
            os.makedirs(d)
        with open(os.path.join(self.shim, 'python3'), 'w', newline='\n') as f:
            f.write('#!/bin/sh\nexec "%s" "$@"\n' % sys.executable.replace('\\', '/'))
        os.chmod(os.path.join(self.shim, 'python3'), 0o755)
        self.git('init', '-q', '.')
        self.git('config', 'user.email', 't@example.com')
        self.git('config', 'user.name', 't')
        self.git('config', 'core.autocrlf', 'false')
        self.git('config', 'commit.gpgsign', 'false')
        os.makedirs(os.path.join(self.repo, 'tools', 'ci'))
        shutil.copy(GATE_SCRIPT, os.path.join(self.repo, 'tools', 'ci', 'ci_gate.py'))
        self.write('docs/x.md', 'docs\n')
        self.write('app/src/main/A.kt', 'kotlin\n')
        self.write('native/fga-ffi/src/lib.rs', 'rust\n')
        self.write('native/old.rs', 'a file that is long enough to be detected as a rename\n' * 5)
        self.write('.github/workflows/native-ci.yml', 'name: x\n')
        self.git('add', '-A')
        self.git('commit', '-q', '-m', 'base')
        self.base = self.git('rev-parse', 'HEAD').strip()

    def git(self, *args):
        r = subprocess.run(['git', *args], cwd=self.repo, capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, (args, r.stderr))
        return r.stdout

    def write(self, rel, text):
        path = os.path.join(self.repo, *rel.split('/'))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, 'w', newline='\n') as f:
            f.write(text)

    def commit_change(self, change):
        self.git('checkout', '-q', '-f', '-B', 'work', self.base)
        change()
        self.git('add', '-A')
        self.git('commit', '-q', '-m', 'change')
        return self.git('rev-parse', 'HEAD').strip()

    def run_step(self, event='pull_request', base='', head='', before='', after=''):
        out = os.path.join(self.tmp, 'github-output')
        open(out, 'w').close()
        env = dict(os.environ)
        env.update({
            'RUNNER_TEMP': self.runner_temp, 'GITHUB_OUTPUT': out, 'EVENT_NAME': event,
            'PR_BASE': base, 'PR_HEAD': head, 'PUSH_BEFORE': before, 'PUSH_AFTER': after,
            'PATH': self.shim + os.pathsep + env.get('PATH', ''),
        })
        r = subprocess.run(['sh', '-e', '-c', self.script], cwd=self.repo, env=env, capture_output=True)
        self.assertEqual(r.returncode, 0, r.stderr.decode(errors='replace'))
        values = {}
        with open(out, encoding='utf-8') as f:
            for line in f.read().splitlines():
                k, _, v = line.partition('=')
                values[k] = v
        return values

    def check_pr(self, change, code, native):
        head = self.commit_change(change)
        v = self.run_step(base=self.base, head=head)
        self.assertEqual((v.get('code'), v.get('native')), (code, native), v)
        self.assertEqual(v.get('range'), '%s...%s' % (self.base, head))
        return v

    def test_docs_only(self):
        self.check_pr(lambda: self.write('docs/x.md', 'changed\n'), 'false', 'false')

    def test_kotlin_only(self):
        self.check_pr(lambda: self.write('app/src/main/A.kt', 'changed\n'), 'true', 'false')

    def test_native_only(self):
        self.check_pr(lambda: self.write('native/fga-ffi/src/lib.rs', 'changed\n'), 'true', 'true')

    def test_native_workflow_only(self):
        self.check_pr(lambda: self.write('.github/workflows/native-ci.yml', 'name: y\n'), 'true', 'true')

    def test_deleted_native_file(self):
        self.check_pr(lambda: self.git('rm', '-q', 'native/old.rs'), 'true', 'true')

    def test_file_moved_out_of_native(self):
        # Without --no-renames the diff lists only docs/old.rs, which looks like documentation.
        def move():
            os.makedirs(os.path.join(self.repo, 'docs'), exist_ok=True)
            self.git('mv', 'native/old.rs', 'docs/old.rs')
        self.check_pr(move, 'true', 'true')

    def test_no_usable_range(self):
        for kwargs in ({'event': 'workflow_dispatch'},
                       {'event': 'push', 'before': '0' * 40, 'after': self.base},
                       {'event': 'push', 'before': '', 'after': self.base},
                       {'event': 'pull_request'}):
            v = self.run_step(**kwargs)
            self.assertEqual((v.get('code'), v.get('native'), v.get('range')), ('true', 'true', ''), kwargs)

    def test_failing_git_diff(self):
        v = self.run_step(base='1' * 40, head='2' * 40)
        self.assertEqual((v.get('code'), v.get('native')), ('true', 'true'))
        self.assertEqual(v.get('range'), '%s...%s' % ('1' * 40, '2' * 40))

    def broken_script(self, body):
        with open(os.path.join(self.repo, 'tools', 'ci', 'ci_gate.py'), 'w', newline='\n') as f:
            f.write(body)

    def check_broken(self, body):
        # A docs-only change is the case where a lost fallback would skip checks.
        head = self.commit_change(lambda: self.write('docs/x.md', 'changed\n'))
        self.broken_script(body)
        v = self.run_step(base=self.base, head=head)
        self.assertEqual((v.get('code'), v.get('native')), ('true', 'true'), v)

    def test_script_missing(self):
        head = self.commit_change(lambda: self.write('docs/x.md', 'changed\n'))
        os.remove(os.path.join(self.repo, 'tools', 'ci', 'ci_gate.py'))
        v = self.run_step(base=self.base, head=head)
        self.assertEqual((v.get('code'), v.get('native')), ('true', 'true'), v)

    def test_script_exits_non_zero_after_printing_false(self):
        self.check_broken('import sys\nprint("false")\nsys.exit(1)\n')

    def test_script_prints_garbage(self):
        self.check_broken('print("banana")\n')

    def test_script_prints_nothing(self):
        self.check_broken('')


class NeedToolTest(unittest.TestCase):
    def test_missing_tool_fails_with_a_clear_message(self):
        with self.assertRaises(AssertionError) as cm:
            need_tool(self, 'no-such-tool-for-ci-gate-tests')
        self.assertIn('is not on PATH', str(cm.exception))


if __name__ == '__main__':
    unittest.main()
