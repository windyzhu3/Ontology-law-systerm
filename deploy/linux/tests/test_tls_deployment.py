import importlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from ols_linux import journal,runtime,tls_material,tls_generation
from ols_linux.config import digest
from ols_linux.bundle import sha
from tls_fixtures import materials,instance,inputs

class DeploymentTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp=tempfile.TemporaryDirectory();cls.f=materials(Path(cls.tmp.name)/'source')
    @classmethod
    def tearDownClass(cls):cls.tmp.cleanup()
    def setUp(self):
        self.t=tempfile.TemporaryDirectory();self.addCleanup(self.t.cleanup);self.root=instance(Path(self.t.name),self.f)
        try:self.m=importlib.import_module('ols_linux.tls_deployment')
        except ImportError:self.fail('TLS deployment binding missing')
        self.c=tls_material.stage(self.root,inputs(self.f),now=self.f['now']);op=journal.current(self.root)
        journal.record(self.root,op['operationId'],{'phase':'COMPLETE'});self.op=journal.begin(self.root,'rotate-public-tls','a'*64)
        self.layout=tls_generation.layout(self.root,self.op['operationId'],self.c)
        directory=self.root/'deployments'/('d'*64)
        runtime.private_file(directory/'api.properties',('issuer=unchanged\ntrust='+str(self.root/'certs/identity-trust.p12')+'\nservice=unchanged\n').encode())
        runtime.private_file(directory/'worker.properties',(directory/'api.properties').read_bytes())
        import json
        runtime.private_file(directory/'entry.json',json.dumps({'certificate':str(self.root/'certs/public.crt'),'privateKey':str(self.root/'certs/public.key'),'ca':str(self.root/'certs/http-trust.pem'),'apiOrigin':'https://localhost:24845'}).encode())
        names={r:'ols-test-'+r for r in ['api','worker','entry','identity','scanner']}
        binding={'descriptorDigest':'d'*64,'files':{str(p.relative_to(self.root)):sha(p) for p in directory.iterdir()},'names':names,'originalIdentityPlanDigest':'i','originalServiceDigest':'s'}
        journal._write(self.root,directory/'binding.json',binding)
        entries=[]
        for role in ['api','worker','scanner','identity','entry']:
            args=[] if role=='identity' else ['docker','run','-d','--name',names[role],'--label','ols.launch=old','--label','ols.operation=old','image',str(directory/('entry.json' if role=='entry' else role+'.properties'))]
            entries.append({'name':names[role],'role':role,'digest':'old','args':args})
        journal._write(self.root,self.root/'launch.json',{'descriptorDigest':'d'*64,'binding':str(directory/'binding.json'),'bindingDigest':digest(binding),'containers':entries[:-1],'ingress':entries[-1]})
        self.original=journal._read(self.root,self.root/'launch.json')
        self.trust={'httpTrust':self.layout['paths']['httpTrust'],'javaTrustStore':self.layout['paths']['javaTrustStore'],'files':{}}
    def test_derived_config_changes_only_tls_paths(self):
        result=self.m.prepare(self.root,self.op['operationId'],self.c,self.trust)
        launch=result['launch'];api=next(e for e in launch['containers'] if e['role']=='api')
        text=Path(api['args'][-1]).read_text()
        self.assertIn('issuer=unchanged',text);self.assertIn('service=unchanged',text)
        self.assertIn(self.trust['javaTrustStore'],text)
        self.assertEqual(journal._read(self.root,self.root/'launch.json'),self.original)
    def test_running_or_foreign_container_refused(self):
        with patch.object(runtime,'inspect',return_value={'State':{'Running':True}}):
            with self.assertRaises(RuntimeError):self.m.switch(self.root,self.op['operationId'],{'deployment':{'launch':self.original}})
    def test_stopped_identity_receives_only_tls_files(self):
        from ols_linux import identity
        container={'State':{'Running':False},'Config':{'Labels':{runtime.LABEL:journal._owner(self.root)['instanceId']}}}
        with patch.object(runtime,'owned',return_value=container),patch.object(identity,'_copy_files') as copy:
            self.m.copy_identity(self.root,'ols-test-identity',{'paths':{'certificate':str(self.f['new-leaf']),'privateKey':str(self.f['directory']/'new-leaf.key')}})
            self.assertEqual(set(copy.call_args.args[2]),{'server.crt','server.key'})
    def test_future_release_uses_selected_generation(self):
        from ols_linux import public_runtime
        self.assertTrue(callable(getattr(public_runtime,'effective_paths',None)),'common TLS paths missing')
    def test_partial_copy_cannot_start_identity(self):
        from ols_linux import identity
        with patch.object(runtime,'owned',return_value={'State':{'Running':False}}),patch.object(identity,'_copy_files',side_effect=RuntimeError('partial')),patch.object(runtime,'run') as run:
            with self.assertRaises(RuntimeError):self.m.copy_identity(self.root,'id',{'paths':{'certificate':str(self.f['new-leaf']),'privateKey':str(self.f['directory']/'new-leaf.key')}})
            run.assert_not_called()
