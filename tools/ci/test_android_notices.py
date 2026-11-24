import hashlib
import json
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import android_notices as a

DATA = b'trained data'
DATA_SHA = hashlib.sha256(DATA).hexdigest()

CATALOG = '''[versions]
opencv_version = "4.11.0"
timber_version = "5.0.1"

[libraries]
opencv = { group = "org.opencv", name = "opencv", version.ref = "opencv_version" }
timber = { module = "com.jakewharton.timber:timber", version.ref = "timber_version" }
androidx-core-ktx = { module = "androidx.core:core-ktx", version = "1.0.0" }
junit4 = { group = "junit", name = "junit", version = "4.13.2" }
'''

BUILD = '''dependencies {
    implementation(project(":scripts"))
    implementation(libs.opencv)
    implementation(libs.timber)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit4)
}
'''

MANIFEST = {
    'notices_file': 'app/src/main/assets/licenses/THIRD_PARTY_NOTICES.txt',
    'components': [
        {'id': 'opencv', 'name': 'OpenCV', 'version': '4.11.0', 'version_ref': 'opencv_version', 'covers': ['org.opencv:opencv']},
        {'id': 'timber', 'name': 'Timber', 'version': '5.0.1', 'version_ref': 'timber_version', 'covers': ['com.jakewharton.timber:*']},
        {'id': 'androidx', 'name': 'AndroidX', 'version': 'various', 'covers': ['androidx.*']},
        {'id': 'bundled', 'name': 'libjpeg-turbo', 'version': '3.0.3'},
    ],
    'data_files': [{'file': 'eng.traineddata', 'sha256': DATA_SHA, 'component': 'bundled'}],
}

NOTICES = 'OpenCV 4.11.0\nTimber 5.0.1\nAndroidX various\nlibjpeg-turbo 3.0.3\neng.traineddata\n'


class Tree:
    """A repository with the files the check reads."""

    def __init__(self, test):
        self.root = tempfile.mkdtemp()
        test.addCleanup(shutil.rmtree, self.root, True)
        self.write('gradle/libs.versions.toml', CATALOG)
        self.write('app/build.gradle.kts', BUILD)
        self.write('tools/ci/android_notices_manifest.json', json.dumps(MANIFEST))
        self.write(MANIFEST['notices_file'], NOTICES)
        self.write('app/src/main/assets/tessdata/eng.traineddata', DATA, binary=True)

    def write(self, rel, content, binary=False):
        path = os.path.join(self.root, *rel.split('/'))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, 'wb' if binary else 'w', **({} if binary else {'encoding': 'utf-8', 'newline': '\n'})) as f:
            f.write(content)

    def manifest(self, change):
        m = json.loads(json.dumps(MANIFEST))
        change(m)
        self.write('tools/ci/android_notices_manifest.json', json.dumps(m))

    def problems(self):
        return a.check(self.root)


class CheckTest(unittest.TestCase):
    def test_a_consistent_tree_passes(self):
        self.assertEqual(Tree(self).problems(), [])

    def test_a_version_bump_in_the_catalog_fails(self):
        t = Tree(self)
        t.write('gradle/libs.versions.toml', CATALOG.replace('opencv_version = "4.11.0"', 'opencv_version = "5.1.0"'))
        problems = t.problems()
        self.assertEqual(len(problems), 1, problems)
        self.assertIn('OpenCV', problems[0])
        self.assertIn('5.1.0', problems[0])

    def test_a_notices_file_without_the_version_fails(self):
        t = Tree(self)
        t.write(MANIFEST['notices_file'], NOTICES.replace('Timber 5.0.1', 'Timber 5.0.0'))
        problems = t.problems()
        self.assertEqual(len(problems), 1, problems)
        self.assertIn('Timber 5.0.1', problems[0])

    def test_a_missing_version_key_fails(self):
        t = Tree(self)
        t.write('gradle/libs.versions.toml', CATALOG.replace('timber_version = "5.0.1"\n', ''))
        self.assertTrue(any('timber_version' in p for p in t.problems()))

    def test_a_new_traineddata_file_fails(self):
        t = Tree(self)
        t.write('app/src/main/assets/tessdata/fra.traineddata', b'other', binary=True)
        problems = t.problems()
        self.assertTrue(any('fra.traineddata' in p and 'no entry' in p for p in problems), problems)

    def test_a_changed_traineddata_file_fails(self):
        t = Tree(self)
        t.write('app/src/main/assets/tessdata/eng.traineddata', b'a newer model', binary=True)
        problems = t.problems()
        self.assertEqual(len(problems), 1, problems)
        self.assertIn('SHA-256', problems[0])

    def test_a_traineddata_file_not_named_in_the_notices_fails(self):
        t = Tree(self)
        t.write(MANIFEST['notices_file'], NOTICES.replace('eng.traineddata\n', ''))
        self.assertTrue(any('not named in the notices' in p for p in t.problems()))

    def test_a_manifest_entry_without_a_file_fails(self):
        t = Tree(self)
        os.remove(os.path.join(t.root, 'app', 'src', 'main', 'assets', 'tessdata', 'eng.traineddata'))
        self.assertTrue(any('not under app/src/main/assets' in p for p in t.problems()))

    def test_a_traineddata_file_in_another_asset_folder_is_found(self):
        t = Tree(self)
        t.write('app/src/main/assets/Kr/x.traineddata', b'x', binary=True)
        self.assertTrue(any('x.traineddata' in p for p in t.problems()))

    def test_a_new_runtime_dependency_without_a_notice_fails(self):
        t = Tree(self)
        t.write('gradle/libs.versions.toml', CATALOG + 'newlib = { module = "org.example:newlib", version = "1" }\n')
        t.write('app/build.gradle.kts', BUILD + 'dependencies {\n    api(libs.newlib)\n}\n')
        problems = t.problems()
        self.assertEqual(len(problems), 1, problems)
        self.assertIn('org.example:newlib', problems[0])

    def test_a_dependency_of_another_module_is_checked_too(self):
        t = Tree(self)
        t.write('gradle/libs.versions.toml', CATALOG + 'newlib = { module = "org.example:newlib", version = "1" }\n')
        t.write('prefs/build.gradle.kts', 'dependencies {\n    implementation(libs.newlib)\n}\n')
        self.assertTrue(any(p.startswith('prefs/build.gradle.kts') for p in t.problems()))

    def test_a_component_that_covers_the_dependency_passes(self):
        t = Tree(self)
        t.write('gradle/libs.versions.toml', CATALOG + 'newlib = { module = "org.example:newlib", version = "1" }\n')
        t.write('app/build.gradle.kts', BUILD + 'dependencies {\n    implementation(libs.newlib)\n}\n')
        t.manifest(lambda m: m['components'].append({'id': 'n', 'name': 'newlib', 'version': '1', 'covers': ['org.example:*']}))
        t.write(MANIFEST['notices_file'], NOTICES + 'newlib 1\n')
        self.assertEqual(t.problems(), [])

    def test_test_and_build_only_dependencies_and_transitive_ones_raise_no_alarm(self):
        t = Tree(self)
        t.write('gradle/libs.versions.toml', CATALOG + 'other = { module = "org.example:other", version = "1" }\n')
        t.write('app/build.gradle.kts', BUILD + (
            'dependencies {\n    testImplementation(libs.other)\n    androidTestImplementation(libs.other)\n'
            '    ksp(libs.other)\n    compileOnly(libs.other)\n    implementation(project(":x"))\n'
            '    implementation(platform(libs.other))\n}\n'))
        self.assertEqual(t.problems(), [])

    def test_an_unknown_alias_fails(self):
        t = Tree(self)
        t.write('app/build.gradle.kts', BUILD + 'dependencies {\n    implementation(libs.not.there)\n}\n')
        self.assertTrue(any('libs.not.there' in p for p in t.problems()))

    def test_catalog_keys_map_to_gradle_accessors(self):
        self.assertEqual(a.accessor('androidx-core-ktx'), 'androidx.core.ktx')
        self.assertEqual(a.accessor('flow_preferences-x'), 'flow.preferences.x')


class RealTreeTest(unittest.TestCase):
    def test_the_committed_notices_match_the_build(self):
        self.assertEqual(a.check(a.REPO), [])

    def test_every_source_of_every_component_is_in_the_manifest(self):
        manifest = json.loads(a.read(os.path.join(a.REPO, a.MANIFEST)))
        known = {s['id'] for s in manifest['sources']}
        for c in manifest['components']:
            for s in c['sources']:
                self.assertIn(s, known, c['id'])
        for s in manifest['sources']:
            self.assertEqual(len(s['sha256']), 64, s['id'])


if __name__ == '__main__':
    unittest.main()
