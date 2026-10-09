"""Isolated observation contract; no live systemd qualification claim."""
import hashlib
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from ols_linux import tls_systemd,runtime


class SystemdObservationTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name);self.unit=self.root/'fixture.service';self.unit.write_text('fixture unit bytes')
        self.config=self.root/'nginx.conf';self.config.write_text('fixture configuration')
        def record(path):
            st=path.stat();return {'path':str(path),'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'uid':st.st_uid,'gid':st.st_gid,'mode':st.st_mode&0o777}
        commands={'ExecStart':'/usr/sbin/nginx -c '+str(self.config)+' -g daemon off;',
                  'ExecStartPre':'/usr/sbin/nginx -t -c '+str(self.config)}
        self.properties={'FragmentPath':str(self.unit),'DropInPaths':'','User':'','Group':'','Type':'simple',
                         'ExecStart':commands['ExecStart'],'ExecStartPre':commands['ExecStartPre'],'LoadCredential':''}
        self.properties={**dict.fromkeys(tls_systemd.UNIT_PROPERTIES,''),**self.properties}
        self.profile={'unitFile':record(self.unit),'immutableFiles':[record(self.config)],'includes':{},
                      'mainConfig':str(self.config),'properties':self.properties,
                      'process':{'executable':'/usr/sbin/nginx','argv':['nginx: master process '+commands['ExecStart']],
                                 'uid':os.geteuid(),'gid':os.getegid(),'cgroup':'/system.slice/fixture.service'},
                      'credentialNames':[]}
        self.service={'transport':'systemd','role':'nginx','name':'fixture.service','identity':self.profile['unitFile']['sha256'],'image':'sha256:'+'a'*64,'systemd':self.profile}
        self.process=dict(self.profile['process'],pid=41,startTicks=123)
        self.state={'ActiveState':'active','SubState':'running','MainPID':'41','ControlPID':'0','ControlGroup':'/system.slice/fixture.service','NeedDaemonReload':'no'}

    def observation(self,properties=None,state=None,pids=None):
        self.assertTrue(callable(getattr(tls_systemd,'observe_service',None)),'Qualified systemd observation missing')
        with patch.object(tls_systemd,'unit_snapshot',return_value={'properties':properties or self.properties,'state':state or self.state}),patch.object(tls_systemd,'read_process',return_value=self.process),patch.object(tls_systemd,'cgroup_pids',return_value=[41] if pids is None else pids),patch.object(tls_systemd,'_binary_sha',return_value='a'*64):
            return tls_systemd.observe_service(self.service)

    def test_exact_profile_produces_live_process_proof(self):
        value=self.observation();self.assertEqual(value['process'],self.process);self.assertTrue(value['running'])

    def test_loaded_properties_or_unit_file_drift_is_refused(self):
        for key,value in [('ExecStart','/bin/sh -c nginx'),('DropInPaths','/foreign.conf'),('LoadCredential','unknown:/foreign')]:
            with self.subTest(key=key),self.assertRaises(RuntimeError):self.observation(properties=dict(self.properties,**{key:value}))
        self.unit.write_text('changed unit bytes')
        with self.assertRaises(RuntimeError):self.observation()

    def test_inactive_unit_requires_no_control_process_or_descendant(self):
        stopped=dict(self.state,ActiveState='inactive',SubState='dead',MainPID='0',ControlGroup='')
        self.assertFalse(self.observation(state=stopped,pids=[])['running'])
        with self.assertRaises(RuntimeError):self.observation(state=stopped,pids=[99])
        with self.assertRaises(RuntimeError):self.observation(state=dict(stopped,ControlPID='9'),pids=[])

    def test_pending_daemon_reload_and_changing_snapshot_are_unknown(self):
        with self.assertRaises(RuntimeError):self.observation(state=dict(self.state,NeedDaemonReload='yes'))
        self.assertTrue(callable(getattr(tls_systemd,'observe_service',None)),'Qualified systemd observation missing')
        before={'properties':self.properties,'state':self.state};after={'properties':self.properties,'state':dict(self.state,MainPID='42')}
        with patch.object(tls_systemd,'unit_snapshot',side_effect=[before,after]),patch.object(tls_systemd,'read_process',return_value=self.process),patch.object(tls_systemd,'cgroup_pids',return_value=[41]),patch.object(tls_systemd,'_binary_sha',return_value='a'*64):
            with self.assertRaises(RuntimeError):tls_systemd.observe_service(self.service)

    def test_registered_process_cannot_point_to_a_different_configuration(self):
        self.profile['process']['argv']=['nginx: master process /usr/sbin/nginx -c /foreign.conf -g daemon off;']
        self.process['argv']=self.profile['process']['argv']
        with self.assertRaises(RuntimeError):self.observation()

    def test_manager_fragment_must_be_the_hashed_unit_file(self):
        self.properties['FragmentPath']='/foreign/fixture.service'
        with self.assertRaises(RuntimeError):self.observation()

    def test_exec_timestamps_do_not_change_the_loaded_command_identity(self):
        self.assertTrue(callable(getattr(tls_systemd,'unit_snapshot',None)))
        for timestamp in ('old','new'):
            rows=dict(self.properties,**self.state)
            for key in ('ExecStart','ExecStartPre'):
                argv=rows[key];rows[key]='{ path=/usr/sbin/nginx ; argv[]='+argv+' ; ignore_errors=no ; start_time=['+timestamp+'] ; stop_time=[n/a] ; pid=41 ; code=(null) ; status=0/0 }'
            payload=''.join(key+'='+value+'\n' for key,value in rows.items()).encode()
            with patch.object(runtime,'run',return_value=SimpleNamespace(stdout=payload)):
                value=tls_systemd.unit_snapshot('fixture.service',self.properties)
                self.assertEqual(value['properties'],self.properties)

    def test_credential_hashes_use_the_process_root_and_reject_links(self):
        self.assertTrue(callable(getattr(tls_systemd,'credential_hashes_at',None)),'Process-root credential reader missing')
        directory=self.root/'run/credentials/fixture.service';directory.mkdir(parents=True)
        (directory/'server.key').write_bytes(b'synthetic-test-key')
        (directory/'issuer-ca.pem').write_bytes(b'synthetic-test-anchor')
        fd=os.open(self.root,os.O_RDONLY|os.O_DIRECTORY)
        try:
            hashes=tls_systemd.credential_hashes_at(fd,'fixture.service',['issuer-ca.pem','server.key'])
            self.assertEqual(hashes['server.key'],hashlib.sha256(b'synthetic-test-key').hexdigest())
            self.assertEqual(hashes['issuer-ca.pem'],hashlib.sha256(b'synthetic-test-anchor').hexdigest())
            (directory/'server.key').unlink();(directory/'server.key').symlink_to(self.unit)
            with self.assertRaises(RuntimeError):tls_systemd.credential_hashes_at(fd,'fixture.service',['issuer-ca.pem','server.key'])
        finally:os.close(fd)

    def test_unbounded_registered_material_is_refused(self):
        self.config.write_bytes(b'x'*(1024*1024+1))
        with self.assertRaises(RuntimeError):tls_systemd._read(self.config)
