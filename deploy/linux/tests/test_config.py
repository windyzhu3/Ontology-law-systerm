import copy
import importlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

LINUX = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(LINUX))


class ConfigTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue((LINUX / 'ols_linux/config.py').is_file(), 'configuration validator missing')
        self.m = importlib.import_module('ols_linux.config')
        self.config = self.m.load(LINUX / 'config/haihua.json')

    def changed(self, change):
        value = copy.deepcopy(self.config)
        change(value)
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / '初始化 配置.json'
            p.write_text(json.dumps(value, ensure_ascii=False), encoding='utf-8')
            with self.assertRaises(ValueError):
                self.m.load(p)

    def test_exact_roster_appointments_and_scopes(self):
        value = self.config
        self.assertEqual(len(value['people']), 17)
        self.assertEqual(sum(len(p['appointments']) for p in value['people']), 20)
        self.assertEqual(len(value['organizations']), 7)
        people = {p['username']: p for p in value['people']}
        self.assertEqual(len(people['dingqiming']['appointments']), 3)
        self.assertEqual(len(people['huangxuexue']['appointments']), 2)
        grants = lambda name: [g['authority'] for a in people[name]['appointments'] for g in a['grants']]
        for name in ['wanhefeng', 'gengtangqi']:
            self.assertNotIn('LEAD_ASSIGN', grants(name))
        self.assertNotIn('PAYMENT_CONFIRM', grants('chenlu'))
        for name in ['jinhuijun', 'wanghui', 'wujingshu', 'houxiaofan', 'huyeru']:
            self.assertEqual(grants(name), [])
        for name in ['dingqiming', 'huangxuexue']:
            self.assertIn('AUDIT_READ', grants(name))
            self.assertIn('IDENTITY_AUTHORITY_MANAGE', grants(name))
        self.assertEqual(value['defaults']['representative'], 'dingqiming')
        self.assertIsNone(value['leadAssignment']['appointment'])

    def test_unknown_secret_duplicate_and_scope_rejected(self):
        self.changed(lambda v: v.update(initialPassword='do-not-disclose'))
        self.changed(lambda v: v['people'].append(copy.deepcopy(v['people'][0])))
        self.changed(lambda v: v['people'][0]['appointments'][0]['grants'][0].update(scope='MISSING'))
        self.changed(lambda v: v['people'][0]['appointments'][0]['grants'][0].update(authority='FUTURE_POWER'))
        self.changed(lambda v: v['organizations'][1].update(parent='SALES_2'))
        self.changed(lambda v: v['people'][0]['appointments'][0].update(role='UNKNOWN'))

    def test_digest_stable_and_templates_are_candidates(self):
        self.assertEqual(self.m.digest(self.config), self.m.digest(dict(reversed(list(self.config.items())))))
        manifest = json.loads((LINUX / 'templates/manifest.json').read_text(encoding='utf-8'))
        self.assertEqual(len(manifest['templates']), 3)
        import hashlib
        for template in manifest['templates']:
            self.assertEqual(template['status'], 'CANDIDATE_NOT_APPROVED')
            for name, digest in template['files'].items():
                self.assertEqual(hashlib.sha256((LINUX / 'templates' / name).read_bytes()).hexdigest(), digest)
            fields = json.loads((LINUX / 'templates' / template['fields']).read_text(encoding='utf-8'))
            self.assertEqual(set(fields), {'customer','parties','scope','fees','payment','signing','transfer','basis'})


if __name__ == '__main__': unittest.main()
