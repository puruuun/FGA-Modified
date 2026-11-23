#!/usr/bin/env python3
"""Builds and checks native/THIRD_PARTY_NOTICES.txt (design 0026, D-10).

  native_notices.py generate   writes the file from `cargo metadata --locked --offline` and the
                               crate sources in the local cargo registry.
  native_notices.py check      exits 1 when the committed file differs from what generate writes.

Both take --metadata FILE and --tree FILE (saved cargo output, for tests) in place of running cargo.

Which crates: every package that libfga_ffi.so links for the two Android ABIs the app ships, that is
the normal (not dev, not build) dependencies of fga-ffi with its default features, on either target
(`cargo tree`, see run_tree). Proc-macro crates and what only they depend on run on the build machine
and are not linked, so they are left out; so are the workspace's own crates, which are the app's
(MIT, the app's LICENSE). Licence expressions, authors and the crate folders come from
`cargo metadata`; the licence texts are copied from the crates' own files, never written here.

Standard library only; deterministic output (sorted, no timestamps, \\n line ends).
"""
import argparse
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, '..', '..'))
NATIVE = os.path.join(REPO, 'native')
OUTPUT = os.path.join(NATIVE, 'THIRD_PARTY_NOTICES.txt')
MODEL_NOTICE = os.path.join(NATIVE, 'ocr-model', 'NOTICE.txt')
TOOLCHAIN = os.path.join(NATIVE, 'rust-toolchain.toml')

# The Rust standard library (std, core, alloc) is compiled into libfga_ffi.so as well. Its licence is
# the Rust project's. The toolchain on the owner's PC carries no plain LICENSE-MIT, LICENSE-APACHE or
# COPYRIGHT file (only licence templates and an HTML page whose contents depend on the install), so
# the entry names the toolchain version and the project's copyright line and uses the MIT and
# Apache-2.0 texts of part 3. Nothing here depends on the PC: the version is the channel that
# rust-toolchain.toml pins for every build, CI included.
RUST_STD = {
    'name': 'Rust standard library (std, core, alloc)',
    'expression': 'MIT OR Apache-2.0',
    'copyright': 'Copyright (c) The Rust Project Contributors',
}

ROOT_PACKAGE = 'fga-ffi'
# The ABIs of shippedAbis in app/build.gradle.kts.
TARGETS = ['aarch64-linux-android', 'armv7-linux-androideabi']

# For a choice ("A OR B") the first licence of this list that the expression offers is used. MIT
# first: its text is the shortest to carry and asks only for the notice.
PREFERENCE = ['MIT', 'Apache-2.0', 'ISC', 'BSD-3-Clause', 'BSD-2-Clause', 'Zlib', 'Unicode-3.0']

# What a licence file must contain to count as the text of that licence.
MARKERS = {
    'MIT': ['Permission is hereby granted, free of charge'],
    'Apache-2.0': ['Apache License', 'Version 2.0, January 2004', 'END OF TERMS AND CONDITIONS'],
    'ISC': ['Permission to use, copy, modify, and', 'THE SOFTWARE IS PROVIDED "AS IS"'],
    'BSD-3-Clause': ['Redistribution and use in source and binary forms', 'Neither the name'],
    'BSD-2-Clause': ['Redistribution and use in source and binary forms'],
    'Zlib': ["This software is provided 'as-is'"],
    'Unicode-3.0': ['UNICODE LICENSE V3'],
}

# Texts carried once for every crate; the others are carried per crate, because they name the
# copyright holder inside the licence text itself.
SHARED_TEXTS = ['MIT', 'Apache-2.0']

LICENSE_FILE = re.compile(r'(?i)^(licen[cs]e|copying|copyright|unlicense|notice)')
NOTICE_FILE = re.compile(r'(?i)^notice')
# Licence files written as source comments start their lines with //, # or *.
# "(c)" only before a year: Apache-2.0's clause 4 (c) starts a line too.
# Capitalised: a sentence that merely continues with "copyright ..." is not a notice.
COPYRIGHT_LINE = re.compile(r'^\s*(?:/{2,}|#+|\*+)?\s*(Copyright\b|COPYRIGHT\b|\([cC]\)\s*\d|©)')
# Template lines of the licence texts themselves, not anybody's copyright.
TEMPLATE_LINE = re.compile(r'(?i)copyright\s*(\[yyyy\]|\{yyyy\}|<year>|\(c\)\s*<year>|notice|license|holder|owner)')

# Crates that ship extra notice files, named in design 0026 (D-10): ring's three.
EXTRA_FILES = {'ring': ['LICENSE', 'LICENSE-BoringSSL', 'LICENSE-other-bits']}


class NoticeError(Exception):
    pass


def read_text(path):
    with open(path, 'rb') as f:
        data = f.read()
    text = data.decode('utf-8', errors='replace').replace('\r\n', '\n').replace('\r', '\n')
    if text.startswith('﻿'):
        text = text[1:]
    lines = [line.rstrip() for line in text.split('\n')]
    while lines and lines[-1] == '':
        lines.pop()
    return '\n'.join(lines) + '\n'


# --- SPDX expressions -------------------------------------------------------------------------

def tokens(expression):
    # Old crates write "MIT/Apache-2.0", which crates.io reads as OR.
    text = expression.replace('/', ' OR ').replace('(', ' ( ').replace(')', ' ) ')
    return text.split()


def parse(expression):
    """Returns a tree: ('or', [..]), ('and', [..]) or ('id', name). 'X WITH Y' stays one id."""
    toks = tokens(expression)
    pos = [0]

    def peek():
        return toks[pos[0]] if pos[0] < len(toks) else None

    def take():
        t = peek()
        pos[0] += 1
        return t

    def atom():
        t = take()
        if t is None:
            raise NoticeError('licence expression %r ends early' % expression)
        if t == '(':
            node = either()
            if take() != ')':
                raise NoticeError('licence expression %r has an unclosed bracket' % expression)
            return node
        if t in ('AND', 'OR', 'WITH', ')'):
            raise NoticeError('licence expression %r: unexpected %s' % (expression, t))
        if peek() == 'WITH':
            take()
            exception = take()
            if exception is None:
                raise NoticeError('licence expression %r: WITH without an exception' % expression)
            return ('id', '%s WITH %s' % (t, exception))
        return ('id', t)

    def both():
        parts = [atom()]
        while peek() == 'AND':
            take()
            parts.append(atom())
        return parts[0] if len(parts) == 1 else ('and', parts)

    def either():
        parts = [both()]
        while peek() == 'OR':
            take()
            parts.append(both())
        return parts[0] if len(parts) == 1 else ('or', parts)

    tree = either()
    if peek() is not None:
        raise NoticeError('licence expression %r: unexpected %s' % (expression, peek()))
    return tree


def choose(tree):
    """The licences used under the expression: all of an AND, the preferred side of an OR."""
    kind, value = tree
    if kind == 'id':
        return [value]
    if kind == 'and':
        out = []
        for part in value:
            for lic in choose(part):
                if lic not in out:
                    out.append(lic)
        return out
    options = [choose(part) for part in value]

    def rank(option):
        return max((PREFERENCE.index(l) if l in PREFERENCE else len(PREFERENCE)) for l in option), len(option)

    return min(options, key=rank)


# --- the crate graph --------------------------------------------------------------------------

def run_metadata():
    command = ['cargo', 'metadata', '--locked', '--offline', '--format-version', '1']
    for target in TARGETS:
        command += ['--filter-platform', target]
    result = subprocess.run(command, cwd=NATIVE, capture_output=True)
    if result.returncode != 0:
        raise NoticeError('cargo metadata failed:\n' + result.stderr.decode('utf-8', 'replace'))
    return json.loads(result.stdout.decode('utf-8'))


def run_tree():
    """`name vVERSION` lines of what fga-ffi links on each target.

    cargo tree, not the resolve of cargo metadata: metadata resolves the whole workspace at once,
    dev-dependencies included, and its graph names edges that the product build does not take
    (rcgen -> x509-parser here). cargo tree resolves the features of fga-ffi alone, as
    `cargo ndk build -p fga-ffi` does; `-e normal,no-proc-macro` leaves out dev and build
    dependencies and everything that only a proc-macro needs.
    """
    lines = []
    for target in TARGETS:
        command = ['cargo', 'tree', '-p', ROOT_PACKAGE, '-e', 'normal,no-proc-macro', '--target', target,
                   '--locked', '--offline', '--prefix', 'none', '-f', '{p}']
        result = subprocess.run(command, cwd=NATIVE, capture_output=True)
        if result.returncode != 0:
            raise NoticeError('cargo tree failed:\n' + result.stderr.decode('utf-8', 'replace'))
        lines += result.stdout.decode('utf-8').split('\n')
    return lines


def linked_packages(metadata, tree_lines):
    members = set(metadata['workspace_members'])
    by_key = {(p['name'], p['version']): p for p in metadata['packages']}
    wanted = set()
    for line in tree_lines:
        line = line.strip()
        if not line:
            continue
        m = re.match(r'^(\S+) v(\S+)', line)
        if not m:
            raise NoticeError('cannot read the cargo tree line %r' % line)
        wanted.add((m.group(1), m.group(2)))
    linked = []
    for key in wanted:
        package = by_key.get(key)
        if package is None:
            raise NoticeError('cargo tree names %s %s, which cargo metadata does not list' % key)
        if package['id'] not in members:
            linked.append(package)
    if not linked:
        raise NoticeError('cargo tree named no third-party crate')
    return sorted(linked, key=lambda p: (p['name'], version_key(p['version'])))


def version_key(version):
    return [int(x) if x.isdigit() else x for x in re.split(r'[.+-]', version)]


# --- one crate --------------------------------------------------------------------------------

def crate_files(package):
    root = os.path.dirname(package['manifest_path'])
    names = sorted(n for n in os.listdir(root) if os.path.isfile(os.path.join(root, n)) and LICENSE_FILE.match(n))
    lf = package.get('license_file')
    if lf:
        rel = os.path.normpath(lf)
        if os.path.isfile(os.path.join(root, rel)) and rel not in names:
            names.append(rel.replace('\\', '/'))
    return root, names


def file_licence(text):
    found = []
    for lic in PREFERENCE:
        if all(m in text for m in MARKERS[lic]):
            found.append(lic)
    # A BSD-3-Clause text also holds every BSD-2-Clause marker.
    if 'BSD-3-Clause' in found and 'BSD-2-Clause' in found:
        found.remove('BSD-2-Clause')
    return found


def copyright_lines(texts):
    out = []
    for text in texts:
        for line in text.split('\n'):
            if COPYRIGHT_LINE.match(line) and not TEMPLATE_LINE.search(line):
                stripped = re.sub(r'^(?:/{2,}|#+|\*+)\s*', '', line.strip())
                if stripped not in out:
                    out.append(stripped)
    return out


def describe(package):
    expression = package.get('license')
    root, names = crate_files(package)
    texts = {n: read_text(os.path.join(root, n)) for n in names}
    if expression:
        used = choose(parse(expression))
    else:
        used = sorted({l for t in texts.values() for l in file_licence(t)}, key=PREFERENCE.index)
        if not used:
            raise NoticeError('%s %s has neither a licence expression nor a recognisable licence file'
                              % (package['name'], package['version']))
    unknown = [l for l in used if l not in MARKERS]
    if unknown:
        raise NoticeError('%s %s is used under %s, which this tool has no text check for'
                          % (package['name'], package['version'], unknown))
    by_licence = {}
    licences_of = {}
    for name in names:
        licences_of[name] = file_licence(texts[name])
        for lic in licences_of[name]:
            by_licence.setdefault(lic, []).append(name)
    return {
        'name': package['name'],
        'version': package['version'],
        'expression': expression or '(none in Cargo.toml; %s from its licence file)' % ' AND '.join(used),
        'used': used,
        'files': names,
        'texts': texts,
        'by_licence': by_licence,
        'licences_of': licences_of,
        'copyright': copyright_lines(list(texts.values())),
        'authors': package.get('authors') or [],
    }


# --- the document -----------------------------------------------------------------------------

def shared_text(crates, licence):
    """The text carried once: the one most crates ship byte for byte, ties to the first crate by name."""
    counts = {}
    first = {}
    for c in crates:
        for name in c['by_licence'].get(licence, []):
            text = c['texts'][name]
            counts[text] = counts.get(text, 0) + 1
            first.setdefault(text, '%s %s, %s' % (c['name'], c['version'], name))
    if not counts:
        raise NoticeError('no crate ships a %s licence file to copy the text from' % licence)
    best = max(counts.values())
    candidates = sorted(first[t] for t in counts if counts[t] == best)
    text = next(t for t in counts if counts[t] == best and first[t] == candidates[0])
    return text, first[text]


def rule(title):
    return '=' * 100 + '\n' + title + '\n' + '=' * 100 + '\n'


def toolchain_channel(text):
    m = re.search(r'(?m)^\s*channel\s*=\s*"([^"]+)"', text)
    if not m:
        raise NoticeError('native/rust-toolchain.toml pins no channel')
    return m.group(1)


def build(metadata, tree_lines, model_notice, toolchain_text):
    channel = toolchain_channel(toolchain_text)
    std_used = choose(parse(RUST_STD['expression']))
    crates = [describe(p) for p in linked_packages(metadata, tree_lines)]
    used = sorted({l for c in crates for l in c['used']}, key=PREFERENCE.index)
    shared = {lic: shared_text(crates, lic) for lic in SHARED_TEXTS if lic in used}
    if 'Apache-2.0' in shared and shared['Apache-2.0'][0].strip() not in model_notice:
        raise NoticeError('native/ocr-model/NOTICE.txt does not hold the Apache-2.0 text of %s; '
                          'append that file to it' % shared['Apache-2.0'][1])

    out = []
    out.append('Third-party notices of the FGA text recogniser\n\n')
    out.append('The app\'s own code is under the MIT License shown on the licence screen. The text\n'
               'recogniser it carries (libfga_ffi.so and its model) also contains the third-party\n'
               'works below. Generated by tools/ci/native_notices.py from native/Cargo.lock and the\n'
               'crates\' own licence files; do not edit by hand.\n\n')
    out.append('Contents: 1. the recognition model; 2. the Rust standard library and the Rust crates\n'
               'linked into libfga_ffi.so; 3. licence texts.\n\n')

    out.append(rule('1. PP-OCRv6 text recognition model'))
    out.append(model_notice.rstrip('\n') + '\n\n')

    out.append(rule('2. Rust crates linked into libfga_ffi.so (%d)' % len(crates)))
    out.append('Each crate is used under the licence named after "Used under"; for a choice of licences\n'
               'the first of %s is taken. The full texts are in part 3:\n'
               'MIT and Apache-2.0 once for all crates, any other licence with its crate.\n\n'
               % ', '.join(PREFERENCE))
    out.append('%s, Rust %s\n' % (RUST_STD['name'], channel))
    out.append('  Licence: %s\n' % RUST_STD['expression'])
    out.append('  Used under: %s\n' % ' AND '.join(std_used))
    out.append('  Compiled into the library by the Rust toolchain that native/rust-toolchain.toml pins\n')
    out.append('  %s\n\n' % RUST_STD['copyright'])
    for c in crates:
        out.append('%s %s\n' % (c['name'], c['version']))
        out.append('  Licence: %s\n' % c['expression'])
        out.append('  Used under: %s\n' % ' AND '.join(c['used']))
        if c['files']:
            out.append('  Licence files in the crate: %s\n' % ', '.join(c['files']))
        else:
            out.append('  Licence files in the crate: none; the licence is taken from its Cargo.toml\n')
        if c['copyright']:
            for line in c['copyright']:
                out.append('  %s\n' % line)
        else:
            authors = ', '.join(re.sub(r'\s*<[^>]*>', '', a) or a for a in c['authors']) or 'none listed'
            out.append('  No copyright line in its licence files. Authors in its Cargo.toml: %s\n' % authors)
        out.append('\n')

    out.append(rule('3. Licence texts'))
    for lic in SHARED_TEXTS:
        if lic not in shared:
            continue
        text, source = shared[lic]
        users = sum(1 for c in crates if lic in c['used'])
        std = ' and the Rust standard library' if lic in std_used else ''
        out.append(rule('%s (used by %d crates%s; text as in %s)' % (lic, users, std, source)))
        out.append(text + '\n')
    for c in crates:
        files = []
        for lic in c['used']:
            if lic in SHARED_TEXTS:
                continue
            names = c['by_licence'].get(lic)
            if not names:
                raise NoticeError('%s %s is used under %s but ships no file with that text'
                                  % (c['name'], c['version'], lic))
            files += [n for n in names if n not in files]
        files += [n for n in EXTRA_FILES.get(c['name'], []) if n not in files]
        # A file that holds several licences at once (the Rust project's COPYRIGHT, a combined
        # LICENSE) is not the plain text carried in part 3, so it travels whole with its crate.
        files += [n for n in c['files'] if len(c['licences_of'][n]) > 1 and n not in files]
        files += [n for n in c['files'] if NOTICE_FILE.match(os.path.basename(n)) and n not in files]
        for name in files:
            if name not in c['texts']:
                raise NoticeError('%s %s has no file %s' % (c['name'], c['version'], name))
            out.append(rule('%s %s: %s' % (c['name'], c['version'], name)))
            out.append(c['texts'][name] + '\n')
    return ''.join(out).rstrip('\n') + '\n'


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('mode', choices=['generate', 'check'])
    parser.add_argument('--metadata', help='a saved cargo metadata JSON instead of running cargo')
    parser.add_argument('--tree', help='saved cargo tree lines (name vVERSION) instead of running cargo')
    parser.add_argument('--output', default=OUTPUT)
    parser.add_argument('--model-notice', default=MODEL_NOTICE)
    parser.add_argument('--toolchain', default=TOOLCHAIN)
    args = parser.parse_args(argv[1:])
    try:
        if args.metadata:
            with open(args.metadata, encoding='utf-8') as f:
                metadata = json.load(f)
        else:
            metadata = run_metadata()
        if args.tree:
            with open(args.tree, encoding='utf-8') as f:
                tree_lines = f.read().splitlines()
        else:
            tree_lines = run_tree()
        text = build(metadata, tree_lines, read_text(args.model_notice), read_text(args.toolchain))
    except (NoticeError, OSError, ValueError, KeyError) as e:
        print('native_notices: FAIL: %s' % e, file=sys.stderr)
        return 1
    data = text.encode('utf-8')
    if args.mode == 'generate':
        with open(args.output, 'wb') as f:
            f.write(data)
        print('native_notices: wrote %s (%d bytes)' % (args.output, len(data)))
        return 0
    try:
        with open(args.output, 'rb') as f:
            current = f.read()
    except OSError as e:
        print('native_notices: FAIL: cannot read %s: %s' % (args.output, e), file=sys.stderr)
        return 1
    if current != data:
        print('native_notices: FAIL: %s is out of date (Cargo.lock or a crate changed); run '
              '`python3 tools/ci/native_notices.py generate` and commit the result' % args.output)
        return 1
    print('native_notices: %s is up to date (%d bytes)' % (args.output, len(data)))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
