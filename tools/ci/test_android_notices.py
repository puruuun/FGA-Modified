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
            '}'))
        self.assertEqual(t.problems(), [])

    def uncovered(self, declaration, extra=''):
        t = Tree(self)
        t.write('gradle/libs.versions.toml', CATALOG + 'newlib = { module = "org.example:newlib", version = "1" }\n')
        t.write('app/build.gradle.kts', BUILD + 'dependencies {\n' + declaration + '\n}\n' + extra)
        return t.problems()

    def test_variant_forms_of_the_runtime_configurations_are_read(self):
        for config in ('betaImplementation', 'debugImplementation', 'ciRuntimeOnly', 'releaseApi', '"rustImplementation"'):
            problems = self.uncovered('    %s(libs.newlib)' % config)
            self.assertEqual(len(problems), 1, (config, problems))
            self.assertIn('org.example:newlib', problems[0])

    def test_a_declaration_over_several_lines_and_a_platform_are_read(self):
        for declaration in ('    implementation(\n        libs.newlib\n    )', '    implementation(platform(libs.newlib))',
                            '    api(enforcedPlatform(libs.newlib)) { because("x") }'):
            problems = self.uncovered(declaration)
            self.assertEqual(len(problems), 1, (declaration, problems))
            self.assertIn('org.example:newlib', problems[0])

    def test_a_local_val_and_a_declaration_in_an_if_block_are_read(self):
        problems = self.uncovered('    val bom = platform(libs.newlib)\n    implementation(bom)')
        self.assertEqual(len(problems), 1, problems)
        self.assertIn('org.example:newlib', problems[0])
        problems = self.uncovered('    if (flag) {\n        implementation(libs.newlib)\n    }')
        self.assertEqual(len(problems), 1, problems)

    def test_a_runtime_declaration_that_cannot_be_resolved_is_an_error_with_file_and_line(self):
        for declaration in ('    implementation("org.example:newlib:1")', '    implementation(libs.newlib.get())',
                            '    implementation(kotlin("stdlib"))', '    add("implementation", libs.newlib)',
                            '    implementation "org.example:newlib:1"', '    runtimeOnly(files("x.jar"))'):
            problems = self.uncovered(declaration)
            self.assertTrue(any(p.startswith('app/build.gradle.kts:') and p.split(':')[1].isdigit() for p in problems),
                            (declaration, problems))
        line = self.uncovered('    implementation("org.example:newlib:1")')[0]
        self.assertTrue(line.startswith('app/build.gradle.kts:%d:' % (BUILD.count('\n') + 2)), line)

    def test_project_and_a_fileTree_without_jars_are_accepted_and_one_with_jars_is_not(self):
        self.assertEqual(self.uncovered('    implementation(project(":libautomata"))'), [])
        decl = '    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))'
        self.assertEqual(self.uncovered(decl), [])
        t = Tree(self)
        t.write('app/build.gradle.kts', BUILD + 'dependencies {\n' + decl + '\n}\n')
        t.write('app/libs/x.jar', b'jar', binary=True)
        self.assertTrue(any('x.jar' in p for p in t.problems()))

    def test_build_only_and_test_configurations_and_comments_are_not_read(self):
        declaration = '\n'.join([
            '    testImplementation(libs.newlib)', '    androidTestImplementation(libs.newlib)',
            '    debugAndroidTestImplementation(libs.newlib)', '    testRuntimeOnly("org.x:y:1")',
            '    ksp(libs.newlib)', '    kapt("org.x:y:1")', '    compileOnly(libs.newlib)', '    lintChecks(libs.newlib)',
            '    alignedLibCxx("org.x:y:1@aar")', '    // implementation(libs.newlib)', '    /* api("org.x:y:1") */'])
        self.assertEqual(self.uncovered(declaration), [])

    def test_a_comment_marker_inside_a_string_is_not_a_comment(self):
        for before in ('    include("jni/*/libc++_shared.so")\n', '    val url = "http://x"\n',
                       '    val raw = """/* not a comment"""\n', '    val t = "${"/*"}"\n', "    val c = '\"'\n"):
            problems = self.uncovered(before + '    implementation(libs.newlib)\n    val after = "*/"')
            self.assertEqual(len(problems), 1, (before, problems))
            self.assertIn('org.example:newlib', problems[0])
        problems = self.uncovered('    val s = "a // b"; implementation(libs.newlib)')
        self.assertEqual(len(problems), 1, problems)

    def test_real_comments_are_still_blanked_and_unterminated_input_fails_closed(self):
        text = 'a /* x\n y */ b // c\nd "// e" /* nested /* in */ out */ f'
        blank = a.blank_comments(text)
        self.assertEqual(len(blank), len(text))
        self.assertEqual(blank.split(), ['a', 'b', 'd', '"//', 'e"', 'f'])
        for bad in ('val s = "open', '/* open', 'x {', 'x }', 'val s = "${ "a" '):
            with self.assertRaises(a.BuildFileError, msg=bad):
                a.scan(bad)
        problems = self.uncovered('    implementation(libs.newlib)\n    val s = "open')
        self.assertTrue(any(p.startswith('app/build.gradle.kts:') and 'reliably' in p for p in problems), problems)

    def test_a_configuration_is_a_test_one_by_the_structure_of_its_name(self):
        for name in ('implementation', 'api', 'runtimeOnly', 'latestImplementation', 'contestApi', 'attestRuntimeOnly',
                     'betaImplementation', 'ciRuntimeOnly', 'testerImplementation'):
            self.assertTrue(a.is_runtime_config(name), name)
        for name in ('testImplementation', 'testApi', 'testRuntimeOnly', 'testFixturesImplementation',
                     'androidTestImplementation', 'debugAndroidTestImplementation', 'ciUnitTestRuntimeOnly',
                     'deviceTestImplementation', 'ksp', 'compileOnly', 'onnxRuntimeAar'):
            self.assertFalse(a.is_runtime_config(name), name)
        problems = self.uncovered('    latestImplementation(libs.newlib)')
        self.assertEqual(len(problems), 1, problems)
        self.assertEqual(self.uncovered('    deviceTestImplementation(libs.newlib)'), [])

    def test_a_local_val_resolves_to_the_nearest_preceding_definition_in_scope(self):
        other = 'other = { module = "org.example:other", version = "1" }\n'

        def run(body):
            t = Tree(self)
            t.write('gradle/libs.versions.toml', CATALOG + 'newlib = { module = "org.example:newlib", version = "1" }\n' + other)
            t.write('app/build.gradle.kts', BUILD + body)
            return t.problems()

        # A later definition elsewhere does not win.
        problems = run('dependencies {\n    val x = libs.newlib\n    implementation(x)\n}\nval x = libs.other\n')
        self.assertEqual(len(problems), 1, problems)
        self.assertIn('org.example:newlib', problems[0])
        # A definition in a block that has been left is not visible; the enclosing one is.
        problems = run('val x = libs.newlib\ndependencies {\n    if (f) {\n        val x = libs.other\n    }\n    implementation(x)\n}\n')
        self.assertEqual(len(problems), 1, problems)
        self.assertIn('org.example:newlib', problems[0])
        # The nearest enclosing definition shadows an outer one.
        problems = run('val x = libs.other\ndependencies {\n    val x = libs.newlib\n    implementation(x)\n}\n')
        self.assertEqual(len(problems), 1, problems)
        self.assertIn('org.example:newlib', problems[0])
        # A definition after the use does not count: the argument cannot be resolved.
        problems = run('dependencies {\n    implementation(x)\n    val x = libs.newlib\n}\n')
        self.assertTrue(any('not resolvable' in p for p in problems), problems)
        # Two definitions in one block cannot be ordered: fail closed with file and line.
        problems = run('dependencies {\n    val x = libs.newlib\n    val x = libs.other\n    implementation(x)\n}\n')
        line = BUILD.count('\n') + 4
        self.assertTrue(any(p.startswith('app/build.gradle.kts:%d:' % line) and 'more than once' in p for p in problems), problems)

    def test_a_version_must_match_as_a_whole_token(self):
        t = Tree(self)
        t.write('gradle/libs.versions.toml', CATALOG.replace('timber_version = "5.0.1"', 'timber_version = "5.0"'))
        t.manifest(lambda m: m['components'][1].update(version='5.0'))
        problems = t.problems()
        self.assertEqual(len(problems), 1, problems)
        self.assertIn('Timber 5.0', problems[0])
        t.write(MANIFEST['notices_file'], NOTICES.replace('Timber 5.0.1', 'Timber 5.0, a library'))
        self.assertEqual(t.problems(), [])
        t.write(MANIFEST['notices_file'], NOTICES.replace('Timber 5.0.1', 'Timber 5.0.'))
        self.assertEqual(t.problems(), [])
        t.write(MANIFEST['notices_file'], NOTICES.replace('Timber 5.0.1', 'Timber 5.0-beta'))
        self.assertEqual(len(t.problems()), 1)

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
