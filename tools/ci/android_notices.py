#!/usr/bin/env python3
"""Checks app/src/main/assets/licenses/THIRD_PARTY_NOTICES.txt against what the build declares (issue #659).

  android_notices.py check [--repo DIR]   exits 1 and names every problem found.

The notices file is written by hand; tools/ci/android_notices_manifest.json says where each text came
from. This check only keeps the file from going stale, in three ways:

1. Versions. Every component of the manifest that names a `version_ref` must have that version in
   gradle/libs.versions.toml, and every component's "<name> <version>" must stand in the notices file
   as whole tokens ("Shizuku API 13.1.5" does not mention version 13.1).
2. Data files. Every `*.traineddata` under app/src/main/assets/ must be in the manifest's `data_files`
   (file name and SHA-256) and be named in the notices file; an entry without a file also fails.
3. Declared dependencies. Every runtime dependency declared in a `dependencies { }` block of the build
   files of app, prefs, scripts and libautomata must resolve to a catalog library that the `covers`
   patterns ("group:artifact", fnmatch) of a component match. The rule, exactly:
   - A configuration is a runtime one when its name (also quoted, `"implementation"(...)`) is
     `implementation`, `api` or `runtimeOnly`, or a variant form of them (`betaImplementation`,
     `debugImplementation`, `ciRuntimeOnly`, ...), unless its variant prefix is a test one: `test`,
     `testFixtures`, or ending in a capitalised `Test` (`androidTest`, `debugAndroidTest`, `deviceTest`). A name that merely contains "test" (`latestImplementation`) is a runtime one.
     `ksp`, `kapt`, `compileOnly`, `lintChecks`, custom configurations such as `onnxRuntimeAar` and
     everything else are build-only and are not read.
   - Its argument is read across lines. Accepted: `libs.<alias>`, `platform(...)` / `enforcedPlatform(...)`
     around one, a local `val` that holds one of those, `project(...)` (our own modules, under the app's
     own licence) and `fileTree(...)` whose `dir` has no `.jar` file.
   - Comments are removed with strings known (`"jni/*/x.so"` is no comment; a `${...}` template is code);
     a local `val` is resolved to the nearest preceding definition in an enclosing `{ }` block. An
     unterminated string, comment or brace, or two definitions of one name in the same block, is an error.
   - Any other runtime declaration (string coordinates, `add("implementation", ...)`, Groovy-style
     `implementation "g:a:v"`, an unknown alias, a form this parser does not know) is an error that names
     the file and line: it fails closed instead of being skipped.

What it cannot see: what is statically inside a native library. The third-party list of libopencv_java5.so
(libjpeg-turbo, libwebp, protobuf, KleidiCV, ...) and of Tesseract4Android's libraries changes with the
version of OpenCV or Tesseract4Android, and the check cannot read it; those components carry fixed
versions that are compared with the catalog only where a catalog key exists (docs/agents/dependencies.md
says to redo the list on a bump). Nor can it see transitive libraries (a new transitive dependency of a
covered library raises no alarm), what a broad `covers` pattern such as `androidx.*` lets through, the
custom configurations that feed packaged native libraries, nor whether a copied text still equals the
upstream file (the manifest holds SHA-256 values for a reviewer to compare).

Standard library only.
"""
import argparse
import fnmatch
import hashlib
import json
import os
import re
import sys
import tomllib

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, '..', '..'))
MANIFEST = os.path.join('tools', 'ci', 'android_notices_manifest.json')
CATALOG = os.path.join('gradle', 'libs.versions.toml')
MODULES = ['app', 'prefs', 'scripts', 'libautomata']

RUNTIME_CONFIG = re.compile(r'^(?P<variant>[a-z][A-Za-z0-9]*?)?(?P<kind>[Ii]mplementation|[Aa]pi|[Rr]untimeOnly)$')
CONFIG_CALL = re.compile(r'(?<![\w.$])(?:"(?P<q>[A-Za-z][A-Za-z0-9]*)"|(?P<n>[A-Za-z][A-Za-z0-9]*))\s*(?P<open>\(?)')
ADD_CALL = re.compile(r'(?<![\w.$])(?:add|dependencies\.add)\s*\(\s*"(?P<name>[A-Za-z][A-Za-z0-9]*)"')
GROOVY_CALL = re.compile(r'''(?<![\w.$])(?P<name>[A-Za-z][A-Za-z0-9]*)[ \t]+["'`]''')
LOCAL_VAL = re.compile(r'(?m)^[ \t]*val[ \t]+(\w+)[ \t]*=[ \t]*(.+)$')
CATALOG_REF = re.compile(r'^libs\.([A-Za-z0-9_.\-]+)$')
PLATFORM_REF = re.compile(r'^(?:platform|enforcedPlatform)\(\s*(libs\.[A-Za-z0-9_.\-]+)\s*\)$')
PROJECT_REF = re.compile(r'^project\(\s*":[A-Za-z0-9_:\-]+"\s*\)$')


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 16), b''):
            h.update(chunk)
    return h.hexdigest()


def read(path):
    with open(path, encoding='utf-8') as f:
        return f.read()


def accessor(key):
    """Gradle's catalog accessor for a library key: '-', '_' and '.' all separate words."""
    return re.sub(r'[-_]', '.', key)


def catalog_modules(catalog):
    """accessor -> 'group:artifact' for every library of the catalog."""
    result = {}
    for key, lib in catalog.get('libraries', {}).items():
        if 'module' in lib:
            module = lib['module']
        elif 'group' in lib and 'name' in lib:
            module = lib['group'] + ':' + lib['name']
        else:
            continue
        result[accessor(key)] = module
    return result


def is_test_variant(variant):
    """A test configuration by the structure of its name, not by a substring ("latestImplementation" is not one).

    The variant prefix is a test one when it is `test`, `testFixtures`, or ends in a capitalised `Test`
    segment: `androidTest`, `debugAndroidTest`, `deviceTest`. `deviceTest` is the build type
    of the device-QA APK (tools/device-qa/run.py), which is never shipped, so it stays out on purpose.
    """
    return variant in ('test', 'testFixtures') or variant.endswith('Test')


def is_runtime_config(name):
    m = RUNTIME_CONFIG.match(name)
    return bool(m) and not is_test_variant(m.group('variant') or '')


class BuildFileError(Exception):
    """The build file cannot be read reliably; carries the 1-based line."""

    def __init__(self, line, reason):
        super().__init__(reason)
        self.line = line
        self.reason = reason


CHAR_LITERAL = re.compile(r"'(?:\\.|[^\\'\n])'")


def scan(text):
    """(text with comments blanked, [(open, close)] of every code `{ }` block, text with strings blanked too).

    The third text has every string (quotes, body and `${` of a template) and character literal replaced by
    spaces as well, so that brackets and `val` lines inside them are invisible to the matching code.

    Offsets and line numbers stay. Strings are known: a `/*` or `//` inside `"..."` or a raw triple-quoted
    string is not a comment and a brace inside one is not a block. A string template `${ ... }` is code
    again, so quotes, comments and braces inside it are read as code; it nests. Character literals (`'"'`)
    are skipped. Block comments nest like Kotlin's. Anything unterminated (string, comment, brace) raises
    BuildFileError, so the check fails closed instead of reading a wrong span.
    """
    out = list(text)
    spans = []  # string and character literal segments, blanked in the third text
    blocks = []
    stack = []  # open braces of code, innermost last: ('{', offset) or ('$', offset) of a template
    modes = []  # (mode, offset where the string opened) to resume after each open template
    n = len(text)

    def line_at(offset):
        return text.count('\n', 0, offset) + 1

    def blank(a, b):
        for k in range(a, b):
            if out[k] != '\n':
                out[k] = ' '

    def string(i, raw, opened):
        """Reads a string body from i. Returns the index after it, or minus the index after `${` of a template.

        An unterminated string is reported at the line where it opened, not where the file ends.
        """
        while i < n:
            c = text[i]
            if raw:
                if text.startswith('"""', i):
                    j = i + 3
                    while j < n and text[j] == '"':
                        j += 1  # a raw string may end in more than three quotes
                    return j
            elif c == '\\':
                i += 2
                continue
            elif c == '"':
                return i + 1
            elif c == '\n':
                raise BuildFileError(line_at(opened), 'unterminated string')
            if c == '$' and text.startswith('{', i + 1):
                stack.append(('$', i + 1))
                modes.append((raw, opened))
                return -(i + 2)
            i += 1
        raise BuildFileError(line_at(opened), 'unterminated string')

    i = 0
    while i < n:
        c = text[i]
        if text.startswith('//', i):
            j = text.find('\n', i)
            j = n if j < 0 else j
            blank(i, j)
            i = j
        elif text.startswith('/*', i):
            depth, j = 1, i + 2
            while j < n and depth:
                if text.startswith('/*', j):
                    depth, j = depth + 1, j + 2
                elif text.startswith('*/', j):
                    depth, j = depth - 1, j + 2
                else:
                    j += 1
            if depth:
                raise BuildFileError(line_at(i), 'unterminated block comment')
            blank(i, j)
            i = j
        elif c == '"':
            raw = text.startswith('"""', i)
            r = string(i + (3 if raw else 1), raw, i)
            spans.append((i, abs(r)))
            i = abs(r)
        elif c == "'":
            m = CHAR_LITERAL.match(text, i)
            if m:
                spans.append((i, m.end()))
            i = m.end() if m else i + 1
        elif c == '{':
            stack.append(('{', i))
            i += 1
        elif c == '}':
            if not stack:
                raise BuildFileError(line_at(i), 'unbalanced }')
            kind, start = stack.pop()
            if kind == '{':
                blocks.append((start, i))
                i += 1
            else:
                raw, opened = modes.pop()
                r = string(i + 1, raw, opened)
                spans.append((i, abs(r)))
                i = abs(r)
        else:
            i += 1
    if stack:
        raise BuildFileError(line_at(stack[-1][1]), 'unbalanced {')
    code = ''.join(out)
    masked = list(code)
    for a, b in spans:
        for k in range(a, b):
            if masked[k] != '\n':
                masked[k] = ' '
    return code, sorted(blocks), ''.join(masked)


def blank_comments(text):
    """The text with comments replaced by spaces, so that offsets and line numbers stay."""
    return scan(text)[0]


class LocalVals:
    """Local `val name = value` definitions, resolved to the nearest preceding one in an enclosing block.

    A definition is visible from its own offset to the end of the innermost `{ }` block that holds it (the
    whole file at top level). A name is resolved to the visible definition closest before the reference. When
    the two closest ones sit in the same block, the scanner cannot say which one the build means, so
    resolution raises BuildFileError with the line of the reference instead of picking one.
    """

    def __init__(self, text, blocks, masked):
        self.text = text
        self.blocks = blocks
        self.defs = {}
        for m in LOCAL_VAL.finditer(masked):
            start = m.start(1)
            value = text[m.start(2):m.end(2)]
            self.defs.setdefault(m.group(1), []).append((start, value, self.block_of(start)))

    def block_of(self, offset):
        """(open, close) of the innermost block that holds offset; (-1, len) at top level."""
        best = (-1, len(self.text))
        for o, c in self.blocks:
            if o < offset < c and o > best[0]:
                best = (o, c)
        return best

    def line_of(self, offset):
        return self.text.count('\n', 0, offset) + 1

    def resolve(self, name, at):
        """(value, offset of its definition) or None when no definition is visible at `at`."""
        visible = [d for d in self.defs.get(name, []) if d[0] < at and d[2][0] < at < d[2][1]]
        if not visible:
            return None
        visible.sort(key=lambda d: d[0])
        if len(visible) > 1 and visible[-1][2] == visible[-2][2]:
            raise BuildFileError(self.line_of(at), 'the local val %s is defined more than once in one block '
                                 '(lines %d and %d); cannot tell which one is meant'
                                 % (name, self.line_of(visible[-2][0]), self.line_of(visible[-1][0])))
        return visible[-1][1], visible[-1][0]


def balanced(masked, start, open_char, close_char):
    """The index after the bracket that closes the one at masked[start]; -1 when it never closes.

    Works on the scan() text with strings blanked, so quotes, character literals and templates cannot
    confuse it.
    """
    depth = 0
    for i in range(start, len(masked)):
        c = masked[i]
        if c == open_char:
            depth += 1
        elif c == close_char:
            depth -= 1
            if depth == 0:
                return i + 1
    return -1


def dependency_blocks(masked, blocks):
    """(start, end) of the body of every `dependencies { }` block, from the blocks that scan() found."""
    closes = dict(blocks)
    return [(m.end(), closes[m.end() - 1])
            for m in re.finditer(r'(?<![\w.])dependencies\s*\{', masked) if m.end() - 1 in closes]


def resolve_argument(arg, local_vals, module_dir, at=0):
    """('lib', accessor), ('ok', None) or ('error', reason) for the argument of a runtime declaration.

    `local_vals` is a LocalVals (or None); `at` is the offset of the declaration in the scanned text.
    """
    arg = arg.strip()
    for _ in range(4):
        found = local_vals.resolve(arg, at) if local_vals and re.fullmatch(r'\w+', arg) else None
        if found is None:
            break
        arg, at = found[0].strip(), found[1]
    m = CATALOG_REF.match(arg)
    if m:
        return 'lib', accessor(m.group(1))
    m = PLATFORM_REF.match(arg)
    if m:
        return 'lib', accessor(CATALOG_REF.match(m.group(1)).group(1))
    if PROJECT_REF.match(arg):
        return 'ok', None
    if arg.startswith('fileTree('):
        d = re.search(r'"dir"\s+to\s+"([^"]+)"|\bdir\s*=\s*"([^"]+)"', arg)
        if not d:
            return 'error', 'fileTree without a readable dir'
        folder = os.path.join(module_dir, d.group(1) or d.group(2))
        jars = [f for f in os.listdir(folder) if f.endswith('.jar')] if os.path.isdir(folder) else []
        return ('error', 'fileTree dir holds jar files: ' + ', '.join(jars)) if jars else ('ok', None)
    return 'error', 'not resolvable to a catalog library by this check: ' + re.sub(r'\s+', ' ', arg)[:80]


def declared_dependencies(repo):
    """([(build file, line, accessor)], [(build file, line, reason)]) of the runtime dependencies."""
    found, errors = [], []
    for module in MODULES:
        path = os.path.join(repo, module, 'build.gradle.kts')
        if not os.path.isfile(path):
            continue
        rel = module + '/build.gradle.kts'
        try:
            text, blocks, masked = scan(read(path))
        except BuildFileError as e:
            errors.append((rel, e.line, 'cannot be read reliably: ' + e.reason))
            continue
        local_vals = LocalVals(text, blocks, masked)

        def line_of(offset):
            return text.count('\n', 0, offset) + 1

        for start, end in dependency_blocks(masked, blocks):
            body = text[start:end]
            masked_body = masked[start:end]
            for m in CONFIG_CALL.finditer(body):
                name = m.group('q') or m.group('n')
                if not is_runtime_config(name) or not m.group('open'):
                    continue
                line = line_of(start + m.start())
                close = balanced(masked_body, m.end() - 1, '(', ')')
                if close < 0:
                    errors.append((rel, line, 'unbalanced parentheses in the declaration of ' + name))
                    continue
                try:
                    kind, value = resolve_argument(body[m.end():close - 1], local_vals, os.path.join(repo, module),
                                                   start + m.start())
                except BuildFileError as e:
                    errors.append((rel, e.line, name + ': ' + e.reason))
                    continue
                if kind == 'lib':
                    found.append((rel, line, value))
                elif kind == 'error':
                    errors.append((rel, line, name + ': ' + value))
            for m in ADD_CALL.finditer(body):
                if is_runtime_config(m.group('name')):
                    errors.append((rel, line_of(start + m.start()),
                                   'add("%s", ...) cannot be read by this check' % m.group('name')))
            for m in GROOVY_CALL.finditer(body):
                if is_runtime_config(m.group('name')):
                    errors.append((rel, line_of(start + m.start()),
                                   '%s "..." (Groovy style) cannot be read by this check' % m.group('name')))
    return found, errors


def mentions(notices, name, version):
    """`<name> <version>` as whole tokens: "Shizuku API 13.1.5" does not mention version 13.1."""
    pattern = r'(?<![\w.\-])' + re.escape(name) + ' ' + re.escape(version) + r'(?![\w\-+]|\.\w)'
    return re.search(pattern, notices) is not None


def check_versions(manifest, catalog, notices):
    problems = []
    versions = catalog.get('versions', {})
    for c in manifest['components']:
        ref = c.get('version_ref')
        if ref:
            if ref not in versions:
                problems.append('%s: version key %s is not in %s' % (c['name'], ref, CATALOG))
            elif versions[ref] != c['version']:
                problems.append('%s: the manifest says %s, %s has %s = %s; update the component (and read '
                                'the bump step in docs/agents/dependencies.md)'
                                % (c['name'], c['version'], CATALOG, ref, versions[ref]))
        if not mentions(notices, c['name'], c['version']):
            problems.append('%s %s: not found in the notices file as "<name> <version>"' % (c['name'], c['version']))
    return problems


def check_data_files(repo, manifest, notices):
    problems = []
    assets = os.path.join(repo, 'app', 'src', 'main', 'assets')
    entries = {d['file']: d for d in manifest.get('data_files', [])}
    seen = set()
    for root, _dirs, files in os.walk(assets):
        for name in sorted(files):
            if not name.endswith('.traineddata'):
                continue
            seen.add(name)
            digest = sha256(os.path.join(root, name))
            entry = entries.get(name)
            if entry is None:
                problems.append('%s (SHA-256 %s) has no entry in data_files of the manifest' % (name, digest))
            elif entry['sha256'] != digest:
                problems.append('%s: the file has SHA-256 %s, the manifest says %s' % (name, digest, entry['sha256']))
            if name not in notices:
                problems.append('%s is not named in the notices file' % name)
    for name in entries:
        if name not in seen:
            problems.append('data_files lists %s, which is not under app/src/main/assets' % name)
    return problems


def check_dependencies(repo, manifest, catalog):
    problems = []
    modules = catalog_modules(catalog)
    patterns = [p for c in manifest['components'] for p in c.get('covers', [])]
    found, errors = declared_dependencies(repo)
    for build_file, line, reason in errors:
        problems.append('%s:%d: %s' % (build_file, line, reason))
    for build_file, line, alias in found:
        module = modules.get(alias)
        if module is None:
            problems.append('%s:%d: libs.%s is not a library of %s' % (build_file, line, alias, CATALOG))
        elif not any(fnmatch.fnmatchcase(module, p) for p in patterns):
            problems.append('%s:%d: %s (libs.%s) is in no component\'s "covers" in the manifest; add its notice'
                            % (build_file, line, module, alias))
    return problems


def check(repo):
    manifest = json.loads(read(os.path.join(repo, MANIFEST)))
    with open(os.path.join(repo, CATALOG), 'rb') as f:
        catalog = tomllib.load(f)
    notices = read(os.path.join(repo, manifest['notices_file']))
    return (check_versions(manifest, catalog, notices)
            + check_data_files(repo, manifest, notices)
            + check_dependencies(repo, manifest, catalog))


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('command', choices=['check'])
    parser.add_argument('--repo', default=REPO)
    args = parser.parse_args(argv)
    problems = check(args.repo)
    for p in problems:
        print('android_notices: ' + p, file=sys.stderr)
    if problems:
        return 1
    print('android_notices: ok')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
