"""Systemd qualification primitives; these tests do not qualify a live service."""
import copy
import hashlib
import importlib
import os
from pathlib import Path
import tempfile
import unittest


class SystemdTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        self.file=self.root/'proxy.conf';self.file.write_bytes(b'approved configuration')
        self.file.chmod(0o600)
        st=self.file.stat()
        self.record=dict(path=str(self.file),sha256=hashlib.sha256(self.file.read_bytes()).hexdigest(),uid=st.st_uid,gid=st.st_gid,mode=0o600)
        try:self.m=importlib.import_module('ols_linux.tls_systemd')
        except ImportError:self.fail('Missing systemd qualification primitives')

    def test_kernel_process_snapshot_binds_pid_start_argv_executable_and_cgroup(self):
        import subprocess,sys
        child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(30)'])
        try:
            observed=self.m.read_process(child.pid)
            self.assertEqual(observed['pid'],child.pid)
            self.assertGreater(observed['startTicks'],0)
            self.assertEqual(observed['executable'],str(Path(sys.executable).resolve()))
            self.assertEqual(observed['argv'],[sys.executable,'-c','import time; time.sleep(30)'])
            self.assertEqual(observed['uid'],os.geteuid())
            self.assertEqual(observed['gid'],os.getegid())
            self.assertTrue(observed['cgroup'].startswith('/'))
        finally:child.terminate();child.wait()
        with self.assertRaises(RuntimeError):self.m.read_process(child.pid)

    def test_registered_files_bind_content_owner_and_mode(self):
        self.m.verify_files([self.record])
        for field,value in [('sha256','0'*64),('uid',self.record['uid']+1),('gid',self.record['gid']+1),('mode',0o644)]:
            with self.subTest(field=field),self.assertRaises(RuntimeError):
                self.m.verify_files([dict(self.record,**{field:value})])

    def test_symlink_ancestor_and_duplicate_file_are_refused(self):
        link=self.root/'alias';link.symlink_to(self.root,target_is_directory=True)
        with self.assertRaises(RuntimeError):self.m.verify_files([dict(self.record,path=str(link/'proxy.conf'))])
        with self.assertRaises(RuntimeError):self.m.verify_files([self.record,self.record])

    def test_non_regular_file_is_rejected_without_blocking(self):
        import subprocess,sys
        fifo=self.root/'fifo';os.mkfifo(fifo)
        code='from ols_linux.tls_systemd import _read; import sys; _read(sys.argv[1])'
        try:
            result=subprocess.run([sys.executable,'-B','-c',code,str(fifo)],capture_output=True,timeout=1)
        except subprocess.TimeoutExpired:self.fail('Registered FIFO blocked file verification')
        self.assertNotEqual(result.returncode,0)
        self.assertIn(b'not a regular file',result.stderr)

    def test_include_membership_detects_new_configuration(self):
        pattern=str(self.root/'*.conf');expected={pattern:[str(self.file)]}
        self.m.verify_includes(expected)
        (self.root/'foreign.conf').write_text('foreign')
        with self.assertRaises(RuntimeError):self.m.verify_includes(expected)

    def profile(self):
        return dict(executable='/usr/sbin/nginx',argv=['nginx: master process /usr/sbin/nginx -c /fixture/nginx.conf'],uid=0,gid=0,cgroup='/system.slice/fixture.service')

    def running(self):
        return dict(ActiveState='active',SubState='running',MainPID='100',ControlPID='0',ControlGroup='/system.slice/fixture.service')

    def process(self):
        return dict(pid=100,startTicks=123,executable='/usr/sbin/nginx',argv=self.profile()['argv'],uid=0,gid=0,cgroup='/system.slice/fixture.service')

    def test_active_process_must_match_registered_identity(self):
        result=self.m.verify_process(self.profile(),self.running(),self.process(),[100,101])
        self.assertTrue(result['running']);self.assertEqual(result['process']['startTicks'],123)
        for field,value in [('executable','/bin/sh'),('argv',['sh','-c','nginx']),('uid',99),('gid',99),('cgroup','/foreign.service'),('pid',101),('startTicks',0)]:
            with self.subTest(field=field),self.assertRaises(RuntimeError):
                self.m.verify_process(self.profile(),self.running(),dict(self.process(),**{field:value}),[100])

    def test_stop_requires_inactive_dead_and_empty_entire_cgroup(self):
        state=dict(self.running(),ActiveState='inactive',SubState='dead',MainPID='0')
        self.assertFalse(self.m.verify_process(self.profile(),state,None,[])['running'])
        for changes,pids in [({},[101]),({'ControlPID':'12'},[]),({'ActiveState':'failed','SubState':'failed'},[]),({'ControlGroup':'/other'},[])]:
            with self.subTest(changes=changes,pids=pids),self.assertRaises(RuntimeError):
                self.m.verify_process(self.profile(),dict(state,**changes),None,pids)

    def test_restart_proof_rejects_same_process_and_pid_reuse_is_distinguished(self):
        before=self.m.verify_process(self.profile(),self.running(),self.process(),[100])
        with self.assertRaises(RuntimeError):self.m.verify_restart(before,before)
        after=copy.deepcopy(before);after['process']['startTicks']+=1
        self.m.verify_restart(before,after)

    def test_loadcredential_change_requires_restart_not_reload(self):
        current={'server.crt':'a'*64,'server.key':'b'*64,'internal-ca.pem':'c'*64}
        desired=dict(current,**{'server.crt':'d'*64})
        self.assertEqual(self.m.credential_action(current,desired),'restart')
        self.assertEqual(self.m.credential_action(current,current),'none')
        with self.assertRaises(RuntimeError):self.m.credential_action(current,dict(desired,unknown='e'*64))
        with self.assertRaises(RuntimeError):self.m.credential_action(current,{'server.crt':'d'*64})

    def test_loaded_credentials_are_verified_against_exact_expected_bytes(self):
        credentials=self.root/'credentials';credentials.mkdir()
        (credentials/'server.crt').write_bytes(b'new certificate')
        expected={'server.crt':hashlib.sha256(b'new certificate').hexdigest()}
        self.m.verify_credentials(credentials,expected)
        (credentials/'server.crt').write_bytes(b'old certificate')
        with self.assertRaises(RuntimeError):self.m.verify_credentials(credentials,expected)
        with self.assertRaises(RuntimeError):self.m.verify_credentials(credentials,{'../proxy.conf':self.record['sha256']})

    def test_fixed_start_commands_match_observed_simple_nginx_and_caddy(self):
        self.assertTrue(callable(getattr(self.m,'start_commands',None)),'Fixed startup profile missing')
        nginx=self.m.start_commands('nginx','/fixture/nginx.conf')
        self.assertEqual(nginx,{'check':['/usr/sbin/nginx','-t','-c','/fixture/nginx.conf'],
                               'start':['/usr/sbin/nginx','-c','/fixture/nginx.conf','-g','daemon off;']})
        caddy=self.m.start_commands('caddy','/fixture/bridge.Caddyfile')
        self.assertEqual(caddy,{'check':['/usr/bin/caddy','validate','--config','/fixture/bridge.Caddyfile','--adapter','caddyfile'],
                               'start':['/usr/bin/caddy','run','--config','/fixture/bridge.Caddyfile','--adapter','caddyfile']})
        for role,path in [('wrapper','/fixture/nginx.conf'),('nginx','relative.conf'),('nginx','/fixture/../foreign.conf')]:
            with self.subTest(role=role,path=path),self.assertRaises(RuntimeError):self.m.start_commands(role,path)

    def test_credential_override_changes_only_issuer_source_without_reading_keys(self):
        from unittest.mock import patch
        self.assertTrue(callable(getattr(self.m,'credential_override',None)),'Bounded credential override missing')
        sources={'server.crt':'/fixture/certs/server.crt','server.key':'/fixture/certs/server.key',
                 'internal-ca.pem':'/fixture/certs/ca.pem','issuer-ca.pem':'/fixture/prior/root.pem'}
        with patch.object(self.m,'_read',side_effect=AssertionError('Credential material must not be read')):
            text=self.m.credential_override(Path('/fixture/runtime'),'a'*64,sources)
        self.assertTrue(text.startswith('[Service]\nLoadCredential=\n'))
        for name in ('server.crt','server.key','internal-ca.pem'):
            self.assertIn('LoadCredential='+name+':'+sources[name]+'\n',text)
        self.assertIn('LoadCredential=issuer-ca.pem:/fixture/runtime/tls/generations/'+'a'*64+'/http-trust.pem\n',text)
        self.assertNotIn('/fixture/prior/root.pem',text)
        self.assertEqual(text.count('LoadCredential='),5)
        for changed in [dict(sources,unknown='/fixture/other'),dict(sources,**{'server.key':'/fixture/%n.key'}),dict(sources,**{'issuer-ca.pem':'/fixture/x\nExecStart=/bad'})]:
            with self.subTest(changed=changed),self.assertRaises(RuntimeError):
                self.m.credential_override(Path('/fixture/runtime'),'a'*64,changed)
        with self.assertRaises(RuntimeError):self.m.credential_override(Path('/fixture/runtime'),'../foreign',sources)
