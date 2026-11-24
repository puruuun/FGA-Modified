#!/usr/bin/env python3
"""Fills the directory Robolectric reads its android-all jars from, with retries.

Main CI caches this directory (actions/cache) and runs the unit tests with robolectric.offline=true
(robolectric-offline.init.gradle), so a test never downloads a jar itself: a failed download used to
surface as dozens of `initializationError`s in unrelated tests (issue #658). The jars to fetch are
listed in robolectric_android_all.json with their sha256; test_robolectric_offline.py keeps that list
equal to the Robolectric version and to the SDK levels the tests use.

Usage: robolectric_offline.py prefetch DIR
Standard library only.
"""
import hashlib
import json
import os
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
MANIFEST = os.path.join(HERE, 'robolectric_android_all.json')
BASE_URL = 'https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented'
ATTEMPTS = 6
BACKOFF_SECONDS = 5


def load_manifest(path=MANIFEST):
    with open(path, encoding='utf-8') as f:
        return json.load(f)


def file_name(version):
    return 'android-all-instrumented-%s.jar' % version


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def is_valid(path, sha256):
    return os.path.isfile(path) and sha256_of(path) == sha256


def download(url, dest, sha256):
    """One attempt: to a temporary name, checked, then renamed, so a partial file never looks complete."""
    tmp = dest + '.part'
    try:
        with urllib.request.urlopen(url, timeout=60) as r, open(tmp, 'wb') as f:
            while True:
                chunk = r.read(1 << 20)
                if not chunk:
                    break
                f.write(chunk)
        got = sha256_of(tmp)
        if got != sha256:
            raise IOError('sha256 mismatch for %s: expected %s, got %s' % (url, sha256, got))
        os.replace(tmp, dest)
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)


def prefetch(directory, manifest, base_url=BASE_URL, attempts=ATTEMPTS, backoff=BACKOFF_SECONDS, sleep=time.sleep):
    """Returns the number of jars downloaded. Raises after the last failed attempt of any jar."""
    os.makedirs(directory, exist_ok=True)
    fetched = 0
    for art in manifest['artifacts']:
        version = art['version']
        name = file_name(version)
        dest = os.path.join(directory, name)
        if is_valid(dest, art['sha256']):
            print('present  %s' % name)
            continue
        url = '%s/%s/%s' % (base_url, version, name)
        for attempt in range(1, attempts + 1):
            try:
                download(url, dest, art['sha256'])
                break
            except (OSError, IOError) as e:
                print('attempt %d/%d failed for %s: %s' % (attempt, attempts, name, e), file=sys.stderr)
                if attempt == attempts:
                    raise
                sleep(backoff * attempt)
        print('fetched  %s' % name)
        fetched += 1
    return fetched


def main(argv):
    if len(argv) != 3 or argv[1] != 'prefetch':
        print(__doc__, file=sys.stderr)
        return 2
    prefetch(argv[2], load_manifest())
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
