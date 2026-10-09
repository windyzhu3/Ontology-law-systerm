"""Entry loss must preserve sealed launch identity and original-operation recovery."""
import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from ols_linux import journal,runtime,tls_rotation,tls_generation,tls_maintenance
from ols_linux.config import digest
from ols_linux.bundle import sha


class EntryRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)/'runtime'
        self.op=journal.begin(self.root,'rotate-public-tls','a'*64)
        self.opid=self.op['operationId'];self.name='ols-test-entry'
        journal.record(self.root,self.opid,{'phase':'ACTIVATING'})
        config=self.root/'deployments'/'test'/'entry.json'
        runtime.private_file(config,b'{}')
        binding={'descriptorDigest':'d'*64,'names':{'entry':self.name},'files':{str(config.relative_to(self.root)):sha(config)}}
        bp=config.parent/'binding.json';journal._write(self.root,bp,binding)
        labels={runtime.LABEL:self.op['instanceId'],'ols.operation':self.opid,'ols.launch':'sealed'}
        args=['docker','run','-d','--name',self.name]
        for k,v in labels.items():args+=['--label',k+'='+v]
        args+=['image@sha256:'+'f'*64,str(config)]
        self.entry={'name':self.name,'role':'entry','digest':'sealed','args':args}
        self.launch={'descriptorDigest':'d'*64,'binding':str(bp),'bindingDigest':digest(binding),'containers':[],'ingress':self.entry}
        self.generation={'version':1,'generationId':'g','deployment':{'launch':copy.deepcopy(self.launch)},'paths':{}}
        self.data={'release':{'descriptor':{'descriptorDigest':'d'*64}},'previousLaunch':copy.deepcopy(self.launch),'probeTargets':[]}
        self.write_launch()
        runtime.save(self.root,{'instanceId':self.op['instanceId'],'containers':{'entry':self.name},'ingress':self.name})
        journal._write(self.root,self.root/'operations'/(self.opid+'-proxy.json'),{'services':[]})
        self.container=None;self.creates=0;self.crash=False
        self.labels=labels;self.config=config
        for target,name,kw in [(runtime,'inspect',{'side_effect':lambda *a:self.container}),
                               (runtime,'run',{'side_effect':self.docker}),
                               (runtime,'validate_tls',{}), (runtime,'start_internal',{}),
                               (tls_generation,'resolve',{'side_effect':lambda *a:copy.deepcopy(self.generation)}),
                               (tls_rotation.tls_proxy,'apply',{}),(tls_maintenance,'start',{})]:
            p=patch.object(target,name,**kw);p.start();self.addCleanup(p.stop)
    def write_launch(self):journal._write(self.root,self.root/'launch.json',self.launch)
    def docker(self,args,**kw):
        if args[:2]==['docker','create']:
            self.creates+=1
            self.container={'Config':{'Labels':copy.deepcopy(self.labels)},'State':{'Running':False}}
            if self.crash:raise RuntimeError('result unknown after create')
        elif args==['docker','start',self.name]:self.container['State']['Running']=True
        else:raise AssertionError('Unexpected Docker mutation')
    def start(self):tls_rotation._start(self.root,self.opid,self.generation,self.data)
    def test_missing_entry_recreated_from_sealed_launch(self):
        try:self.start()
        except RuntimeError as e:self.fail('Missing sealed entry must be recreated: '+str(e))
        self.assertTrue(self.container['State']['Running']);self.assertEqual(self.creates,1)
    def test_unknown_create_result_resumes_same_container(self):
        self.crash=True
        with self.assertRaises(RuntimeError):self.start()
        self.assertIsNotNone(self.container,'Create must have occurred before injected interruption')
        self.assertFalse(self.container['State']['Running'])
        self.crash=False;self.start()
        self.assertTrue(self.container['State']['Running']);self.assertEqual(self.creates,1)
    def test_foreign_container_never_adopted(self):
        self.container={'Config':{'Labels':dict(self.labels,**{runtime.LABEL:'foreign'})},'State':{'Running':False}}
        with self.assertRaises(RuntimeError):self.start()
        self.assertFalse(self.container['State']['Running']);self.assertEqual(self.creates,0)
    def test_wrong_launch_label_never_started(self):
        self.container={'Config':{'Labels':dict(self.labels,**{'ols.launch':'other'})},'State':{'Running':False}}
        with self.assertRaises(RuntimeError):self.start()
        self.assertFalse(self.container['State']['Running'])
    def test_changed_launch_args_refused_before_effects(self):
        self.launch['ingress']['args']+=['unsealed'];self.write_launch()
        self.assert_refused()
    def test_changed_binding_bytes_refused_before_effects(self):
        self.config.write_bytes(b'changed');self.assert_refused()
    def test_selected_generation_conflict_refused_before_effects(self):
        with patch.object(tls_generation,'resolve',return_value=dict(self.generation,generationId='other')):self.assert_refused()
    def test_unregistered_entry_refused_before_effects(self):
        r=runtime.load(self.root);r['ingress']='other';runtime.save(self.root,r);self.assert_refused()
    def test_legacy_rollback_recreates_exact_previous_entry(self):
        self.generation={'version':0,'generationId':'legacy','paths':{}}
        journal.record(self.root,self.opid,{'phase':'ROLLBACK_ACTIVATING'})
        try:self.start()
        except RuntimeError as e:self.fail('Legacy rollback entry must be recreated: '+str(e))
        self.assertTrue(self.container['State']['Running'])
    def assert_refused(self):
        with self.assertRaises(RuntimeError):self.start()
        self.assertIsNone(self.container);self.assertEqual(self.creates,0)
        tls_rotation.tls_proxy.apply.assert_not_called()
        runtime.start_internal.assert_not_called()


if __name__=='__main__':unittest.main()
