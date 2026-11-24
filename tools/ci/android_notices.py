#!/usr/bin/env python3
"""Checks app/src/main/assets/licenses/THIRD_PARTY_NOTICES.txt against what the build declares (issue #659).

  android_notices.py check [--repo DIR]   exits 1 and names every problem found.

The notices file is written by hand; tools/ci/android_notices_manifest.json says where each text came
from. This check only keeps the file from going stale, in three ways:

1. Versions. Every component of the manifest that names a `version_ref` must have that version in
   gradle/libs.versions.toml, and every component's "<name> <version>" must stand in the notices file.
2. Data files. Every `*.traineddata` under app/src/main/assets/ must be in the manifest's `data_files`
   (file name and SHA-256) and be named in the notices file; an entry without a file also fails.
3. Declared dependencies. Every `implementation(libs.x)` / `api(libs.x)` / `runtimeOnly(libs.x)` of the
   modules' build files must match the `covers` patterns ("group:artifact", fnmatch) of a component.
   Only declared dependencies are looked at, so a new transitive dependency of a covered library
   raises no alarm; test, ksp and build-only configurations are not looked at.

What it cannot see: what is statically inside a native library. The third-party list of libopencv_java4.so
(libjpeg-turbo, libwebp, protobuf, KleidiCV, ...) and of Tesseract4Android's libraries changes with the
version of OpenCV or Tesseract4Android, and the check cannot read it; those components carry fixed
versions that are compared with the catalog only where a catalog key exists (docs/agents/dependencies.md
says to redo the list on a bump). Nor can it see transitive libraries, nor whether a copied text still
equals the upstream file (the manifest holds SHA-256 values for a reviewer to compare).

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
DEP_LINE = re.compile(r'^\s*(?:implementation|api|runtimeOnly)\(\s*libs\.([A-Za-z0-9_.\-]+)\s*\)')


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


def declared_dependencies(repo):
    """(build file, accessor) for each runtime dependency declared through the catalog."""
    found = []
    for module in MODULES:
        path = os.path.join(repo, module, 'build.gradle.kts')
        if not os.path.isfile(path):
            continue
        for line in read(path).split('\n'):
            m = DEP_LINE.match(line)
            if m:
                found.append((module + '/build.gradle.kts', accessor(m.group(1))))
    return found


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
        if '%s %s' % (c['name'], c['version']) not in notices:
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
    for build_file, alias in declared_dependencies(repo):
        module = modules.get(alias)
        if module is None:
            problems.append('%s: libs.%s is not a library of %s' % (build_file, alias, CATALOG))
        elif not any(fnmatch.fnmatchcase(module, p) for p in patterns):
            problems.append('%s: %s (libs.%s) is in no component\'s "covers" in the manifest; add its notice'
                            % (build_file, module, alias))
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
