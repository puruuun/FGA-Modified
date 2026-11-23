import json
import os
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import native_notices as n

SCRIPT = os.path.join(os.path.dirname(__file__), 'native_notices.py')

MIT = ('Copyright (c) 2020 The A Authors\n\nPermission is hereby granted, free of charge, to any person\n'
       'obtaining a copy of this software.\n')
APACHE = ('                              Apache License\n                        Version 2.0, January 2004\n'
          '   TERMS AND CONDITIONS FOR USE\n   (c) You must retain the notices\n   END OF TERMS AND CONDITIONS\n')
ISC = ('Copyright 2015 Someone.\n\nPermission to use, copy, modify, and/or distribute this software\n'
       'THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES\n')


class ExpressionTest(unittest.TestCase):
    def used(self, expression):
        return n.choose(n.parse(expression))

    def test_choices(self):
        self.assertEqual(self.used('MIT OR Apache-2.0'), ['MIT'])
        self.assertEqual(self.used('Apache-2.0 OR MIT'), ['MIT'])
        self.assertEqual(self.used('MIT/Apache-2.0'), ['MIT'])
        self.assertEqual(self.used('Unlicense OR MIT'), ['MIT'])
        self.assertEqual(self.used('Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT'), ['MIT'])
        self.assertEqual(self.used('Apache-2.0 AND ISC'), ['Apache-2.0', 'ISC'])
        self.assertEqual(self.used('(MIT OR Apache-2.0) AND Unicode-3.0'), ['MIT', 'Unicode-3.0'])
        self.assertEqual(self.used('BSD-3-Clause'), ['BSD-3-Clause'])
        self.assertEqual(self.used('Apache-2.0'), ['Apache-2.0'])

    def test_malformed(self):
        for bad in ('', 'MIT OR', '(MIT', 'MIT AND AND ISC', 'MIT )', 'MIT WITH'):
            with self.assertRaises(n.NoticeError, msg=bad):
                n.parse(bad)


class GeneratorTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = self.tmp.name
        self.packages = []
        self.add('alpha', '1.0.0', 'MIT OR Apache-2.0', {'LICENSE-MIT': MIT, 'LICENSE-APACHE': APACHE})
        self.add('beta', '0.2.1', 'Apache-2.0 AND ISC', {'LICENSE': APACHE, 'LICENSE-ISC': ISC})
        self.add('gamma', '3.1.0', 'MIT', {})
        self.notice = self.write('NOTICE.txt', 'The model, under Apache-2.0.\n\n' + APACHE)
        self.output = os.path.join(self.root, 'THIRD_PARTY_NOTICES.txt')

    def write(self, rel, text):
        path = os.path.join(self.root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, 'w', encoding='utf-8', newline='\n') as f:
            f.write(text)
        return path

    def add(self, name, version, licence, files):
        folder = '%s-%s' % (name, version)
        manifest = self.write(os.path.join('registry', folder, 'Cargo.toml'), '[package]\n')
        for file, text in files.items():
            self.write(os.path.join('registry', folder, file), text)
        self.packages.append({
            'id': 'registry#%s@%s' % (name, version), 'name': name, 'version': version,
            'license': licence, 'license_file': None, 'manifest_path': manifest,
            'authors': ['Ann Author <ann@example.com>'], 'targets': [{'kind': ['lib']}],
        })

    def metadata(self):
        ffi = {'id': 'path#fga-ffi@0.1.0', 'name': 'fga-ffi', 'version': '0.1.0', 'license': 'MIT',
               'license_file': None, 'manifest_path': os.path.join(self.root, 'Cargo.toml'),
               'authors': [], 'targets': [{'kind': ['cdylib', 'rlib']}]}
        return {'packages': [ffi] + self.packages, 'workspace_members': [ffi['id']]}

    def tree(self):
        return ['fga-ffi v0.1.0 (C:\\x\\native\\fga-ffi)'] + \
            ['%s v%s' % (p['name'], p['version']) for p in self.packages]

    def run_cli(self, mode, metadata=None, tree=None):
        meta_path = self.write('metadata.json', json.dumps(metadata or self.metadata()))
        tree_path = self.write('tree.txt', '\n'.join(tree or self.tree()) + '\n')
        p = subprocess.run([sys.executable, SCRIPT, mode, '--metadata', meta_path, '--tree', tree_path,
                            '--output', self.output, '--model-notice', self.notice], capture_output=True)
        return p.returncode, (p.stdout + p.stderr).decode('utf-8', 'replace')

    def test_generate_then_check_passes(self):
        self.assertEqual(self.run_cli('generate')[0], 0)
        code, out = self.run_cli('check')
        self.assertEqual(code, 0, out)

    def test_output_is_deterministic_and_has_unix_line_ends(self):
        first = n.build(self.metadata(), self.tree(), n.read_text(self.notice))
        self.packages.reverse()
        second = n.build(self.metadata(), list(reversed(self.tree())), n.read_text(self.notice))
        self.assertEqual(first, second)
        self.assertNotIn('\r', first)
        self.assertTrue(first.endswith('\n'))

    def test_contents(self):
        text = n.build(self.metadata(), self.tree(), n.read_text(self.notice))
        self.assertIn('The model, under Apache-2.0.', text)
        self.assertIn('alpha 1.0.0\n  Licence: MIT OR Apache-2.0\n  Used under: MIT\n', text)
        self.assertIn('  Copyright (c) 2020 The A Authors\n', text)
        crate_list = text[text.index('2. Rust crates'):text.index('3. Licence texts')]
        self.assertNotIn('You must', crate_list)
        self.assertIn('beta 0.2.1\n  Licence: Apache-2.0 AND ISC\n  Used under: Apache-2.0 AND ISC\n', text)
        self.assertIn('beta 0.2.1: LICENSE-ISC', text)
        self.assertIn('gamma 3.1.0\n  Licence: MIT\n  Used under: MIT\n'
                      '  Licence files in the crate: none; the licence is taken from its Cargo.toml\n'
                      '  No copyright line in its licence files. Authors in its Cargo.toml: Ann Author\n', text)
        self.assertIn('2. Rust crates linked into libfga_ffi.so (3)', text)
        self.assertNotIn('fga-ffi 0.1.0', text)

    def test_a_changed_lock_entry_fails_the_check(self):
        self.assertEqual(self.run_cli('generate')[0], 0)
        self.packages[0]['version'] = '1.0.1'
        self.packages[0]['id'] = 'registry#alpha@1.0.1'
        code, out = self.run_cli('check')
        self.assertEqual(code, 1, out)
        self.assertIn('out of date', out)

    def test_an_added_crate_fails_the_check(self):
        self.assertEqual(self.run_cli('generate')[0], 0)
        self.add('delta', '0.0.1', 'MIT', {'LICENSE': MIT})
        self.assertEqual(self.run_cli('check')[0], 1)

    def test_a_changed_licence_file_fails_the_check(self):
        self.assertEqual(self.run_cli('generate')[0], 0)
        self.write(os.path.join('registry', 'beta-0.2.1', 'LICENSE-ISC'), ISC.replace('2015', '2016'))
        self.assertEqual(self.run_cli('check')[0], 1)

    def test_missing_output_fails_the_check(self):
        self.assertEqual(self.run_cli('check')[0], 1)

    def test_tree_crate_unknown_to_metadata_fails(self):
        code, out = self.run_cli('generate', tree=self.tree() + ['omega v9.9.9'])
        self.assertEqual(code, 1)
        self.assertIn('omega', out)

    def test_licence_without_its_text_fails(self):
        self.packages[1]['license'] = 'BSD-3-Clause'
        code, out = self.run_cli('generate')
        self.assertEqual(code, 1)
        self.assertIn('BSD-3-Clause', out)

    def test_model_notice_without_the_apache_text_fails(self):
        self.write('NOTICE.txt', 'The model, under Apache-2.0.\n')
        code, out = self.run_cli('generate')
        self.assertEqual(code, 1)
        self.assertIn('NOTICE.txt', out)


class CommittedFileTest(unittest.TestCase):
    def test_committed_file_has_unix_line_ends_and_the_model_notice(self):
        with open(n.OUTPUT, 'rb') as f:
            data = f.read()
        self.assertNotIn(b'\r', data)
        with open(n.MODEL_NOTICE, 'rb') as f:
            notice = f.read()
        self.assertIn(notice.rstrip(b'\n'), data)


if __name__ == '__main__':
    unittest.main()
