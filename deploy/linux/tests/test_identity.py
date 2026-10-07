import importlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

LINUX = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(LINUX))


class IdentityTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue((LINUX/'ols_linux/identity.py').exists(), 'Native Linux identity provisioning missing')
        self.m = importlib.import_module('ols_linux.identity')
        from ols_linux import config, journal, runtime
        self.j, self.r = journal, runtime
        self.cfg = config.load(LINUX/'config/haihua.json')
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)/'private'
        self.op = journal.begin(self.root, 'initialize', config.digest(self.cfg))
        runtime.save(self.root, {'name':'ols-id-test', 'repo':str(LINUX.parents[1]), 'ports':{},
                                'runtimeImage':'sha256:'+'a'*64, 'writers':[], 'containers':{}})
        self.password = Path(self.tmp.name)/'password'; self.password.write_text('Abc12345'); self.password.chmod(0o600)
        self.start = patch.object(self.m, '_start', return_value=None).start()
        self.addCleanup(patch.stopall)
        patch.object(self.r,'inspect',return_value=None).start()
        patch.object(self.m, '_empty_identity_database', return_value=True).start()
        patch.object(self.m, '_directory', side_effect=self.directory).start()
        self.changed = set()

    def directory(self, *args):
        realm = json.loads((self.root/'identity/realm.json').read_text(encoding='utf-8'))
        users = [dict(u) for u in realm['users'] if not u.get('serviceAccountClientId')]
        for user in users:
            user.pop('credentials', None)
            if user['username'] in self.changed: user['requiredActions'] = []
        return users

    def test_private_import_records_exact_subjects_and_all_temporary_passwords(self):
        result = self.m.prepare(self.root, self.cfg, self.password)
        realm = json.loads((self.root/'identity/realm.json').read_text(encoding='utf-8'))
        users = [u for u in realm['users'] if not u.get('serviceAccountClientId')]
        self.assertEqual(17, len(result['subjects']))
        self.assertEqual(17, len({u['id'] for u in users}))
        self.assertTrue(all(u['credentials'][0]['temporary'] is True and u['requiredActions']==['UPDATE_PASSWORD'] for u in users))
        self.assertEqual('length(8) and notUsername and notEmail', realm['passwordPolicy'])
        self.assertTrue(all(c['directAccessGrantsEnabled'] is False for c in realm['clients']))
        self.assertEqual('S256', realm['clients'][0]['attributes']['pkce.code.challenge.method'])
        profile=json.loads(realm['components']['org.keycloak.userprofile.UserProfileProvider'][0]['config']['kc.user.profile.config'][0])
        attributes={a['name']:a for a in profile['attributes']}
        self.assertNotIn('required',attributes['email'])
        for key in ['olsInstance','olsOperation','olsConfig']:
            self.assertEqual({'view':['admin'],'edit':['admin']},attributes[key]['permissions'])
        self.assertNotIn('Abc12345', json.dumps(self.j.current(self.root)))

    def test_unknown_import_reconciles_same_subjects_without_password_reset(self):
        self.start.side_effect = RuntimeError('response lost')
        with self.assertRaises(RuntimeError): self.m.prepare(self.root, self.cfg, self.password)
        plan = self.j._read(self.root, self.root/'identity/plan.json')
        original = (self.root/'identity/realm.json').read_bytes()
        self.start.side_effect = None
        self.changed = {'dingqiming','huangxuexue'}
        result = self.m.prepare(self.root, self.cfg, self.password)
        self.assertEqual(plan['subjects'], result['subjects'])
        self.assertEqual(original, (self.root/'identity/realm.json').read_bytes())
        self.assertEqual(15, self.m.verify(self.root)['passwordUpdatesRequired'])

    def test_foreign_database_and_config_drift_are_rejected_before_effects(self):
        with patch.object(self.m, '_empty_identity_database', return_value=False):
            with self.assertRaises(RuntimeError): self.m.prepare(self.root, self.cfg, self.password)
        self.start.assert_not_called()
        self.m.prepare(self.root, self.cfg, self.password)
        changed = dict(self.cfg, tenantName='different')
        with self.assertRaises(RuntimeError): self.m.prepare(self.root, changed, self.password)
        self.password.write_text('Different8')
        with self.assertRaises(RuntimeError): self.m.prepare(self.root, self.cfg, self.password)

    def test_same_name_wrong_subject_or_owner_marker_is_not_adopted(self):
        self.m.prepare(self.root, self.cfg, self.password)
        users = self.directory(None); users[0]['id'] = 'foreign'
        with patch.object(self.m, '_directory', return_value=users):
            with self.assertRaises(RuntimeError): self.m.verify(self.root)
        users = self.directory(None); users[0]['attributes'] = {}
        with patch.object(self.m, '_directory', return_value=users):
            with self.assertRaises(RuntimeError): self.m.verify(self.root)

    def test_verification_is_read_only(self):
        self.m.prepare(self.root, self.cfg, self.password)
        before = self.j.current(self.root)
        self.start.reset_mock()
        self.m.verify(self.root)
        self.start.assert_not_called()
        self.assertEqual(before, self.j.current(self.root))

    def test_password_seven_characters_rejected(self):
        self.password.write_text('Ab12345')
        with self.assertRaises(ValueError): self.m.prepare(self.root, self.cfg, self.password)
        self.assertFalse((self.root/'identity/plan.json').exists())

    def test_original_bootstrap_unknown_checks_verify_before_any_other_effect(self):
        self.m.prepare(self.root, self.cfg, self.password)
        from ols_linux.config import digest,canonical
        from ols_linux.runtime import private_file
        manifest={'commandId':'original'};private_file(self.root/'identity/original-manifest.json',canonical(manifest))
        self.j._write(self.root, self.root/'identity/bootstrap.json', {'state':'EXECUTE_UNKNOWN','manifestDigest':digest(manifest)})
        with patch.object(self.m, '_bootstrap_command', return_value={'mode':'VERIFIED_ORIGINAL'}) as command:
            result = self.m.bootstrap(self.root, self.cfg)
        self.assertEqual('VERIFIED_ORIGINAL', result['mode'])
        self.assertEqual(['verify'], [call.args[1] for call in command.call_args_list])

    def test_original_bootstrap_changed_manifest_is_not_verified_or_replaced(self):
        self.m.prepare(self.root,self.cfg,self.password)
        self.j._write(self.root,self.root/'identity/bootstrap.json',{'state':'EXECUTE_UNKNOWN','manifestDigest':'a'*64})
        (self.root/'identity/original-manifest.json').write_text('{}')
        with patch.object(self.m,'_bootstrap_command') as command:
            with self.assertRaises(RuntimeError):self.m.bootstrap(self.root,self.cfg)
        command.assert_not_called()

    def test_identity_writer_is_not_a_postgres_superuser(self):
        self.assertTrue(callable(getattr(self.m, '_database_login', None)), 'Least-privilege identity database login missing')
        with patch.object(self.m.database,'sql',return_value='0') as sql:
            self.m._database_login(self.root)
        statement=sql.call_args.args[1]
        self.assertIn('NOSUPERUSER NOCREATEDB NOCREATEROLE',statement)
        self.assertIn('ols_identity_login',statement)

    def test_original_bootstrap_not_dispatched_can_resume_only_after_confirmed_absence(self):
        self.assertTrue(callable(getattr(self.m,'_bootstrap_absent',None)), 'Authoritative original bootstrap absence check missing')
        self.m.prepare(self.root,self.cfg,self.password)
        from ols_linux.config import canonical,digest
        from ols_linux.runtime import private_file
        manifest={'commandId':'original'};private_file(self.root/'identity/original-manifest.json',canonical(manifest))
        self.j._write(self.root,self.root/'identity/bootstrap.json',{'state':'EXECUTE_UNKNOWN','manifestDigest':digest(manifest)})
        with patch.object(self.m,'_bootstrap_absent',return_value=True),patch.object(self.m,'_bootstrap_command',side_effect=[RuntimeError('not confirmed'),{'mode':'CREATED'},{'mode':'VERIFIED_ORIGINAL'}]) as command:
            self.m.bootstrap(self.root,self.cfg)
        self.assertEqual(['verify','execute','verify'],[call.args[1] for call in command.call_args_list])


if __name__ == '__main__': unittest.main()
