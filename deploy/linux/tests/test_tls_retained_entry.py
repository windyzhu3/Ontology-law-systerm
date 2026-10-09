"""A rollback-lost candidate entry remains recoverable only by its sealed launch."""
import copy,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
from ols_linux import journal,runtime,tls_deployment as m


class RetainedEntryTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)/'runtime';op=journal.begin(self.root,'rotate-public-tls','a'*64);self.oid=op['operationId']
        journal.record(self.root,self.oid,{'phase':'RETRYING','recoveryFrom':'ROLLBACK_BLOCKED'})
        journal.record(self.root,self.oid,{'phase':'SWITCHING'})
        self.image='sha256:'+'f'*64
        self.labels={runtime.LABEL:op['instanceId'],'ols.operation':self.oid,'ols.launch':'sealed'}
        args=['docker','run','-d','--name','candidate-entry']
        for k,v in self.labels.items():args+=['--label',k+'='+v]
        args+=['--network','container:pod','--log-driver','local','--log-opt','max-size=10m','--log-opt','max-file=2','--mount',f'type=bind,source={self.root},target={self.root},readonly','--memory','768m','--cpus','2','--entrypoint','node',self.image,'/release/server.mjs','/generation/entry.json']
        self.entry={'name':'candidate-entry','role':'entry','digest':'sealed','args':args}
        self.generation={'operationId':self.oid,'generationId':'new','deployment':{'launch':{'containers':[],'ingress':self.entry}}}
        journal._write(self.root,self.root/'operations'/(self.oid+'-tls.json'),{'generationId':'new'})
        runtime.save(self.root,{'containers':{'entry':'old-entry','pod':'pod','identity':'id'},'writers':['id'],'ingress':'old-entry'})
        self.actual={'Name':'/candidate-entry','Id':'candidate-id','Image':self.image,'Path':'node','Args':args[-2:],'State':{'Running':False},'Config':{'Labels':self.labels.copy(),'Image':self.image,'Entrypoint':['node'],'Cmd':args[-2:],'Env':['PATH=/bin'],'User':''},'HostConfig':{'NetworkMode':'container:pod-id','PortBindings':{},'PublishAllPorts':False,'Privileged':False,'Memory':805306368,'NanoCpus':2000000000,'LogConfig':{'Type':'local','Config':{'max-size':'10m','max-file':'2'}},'RestartPolicy':{'Name':'no'}},'Mounts':[{'Type':'bind','Source':str(self.root),'Destination':str(self.root),'RW':False}],'NetworkSettings':{'Ports':{}}}
        self.image_info={'Id':self.image,'Config':{'Env':['PATH=/bin'],'User':''}}
    def inspect(self,kind,name):
        if kind=='image':return self.image_info
        return self.actual if name=='candidate-entry' else {'Id':'pod-id','Config':{'Labels':{runtime.LABEL:journal._owner(self.root)['instanceId']}},'State':{'Running':False}}
    def recover(self):
        with patch.object(runtime,'inspect',side_effect=self.inspect),patch.object(m.tls_generation,'read',return_value=copy.deepcopy(self.generation)):
            return m._recover_retained_entry(self.root,self.oid,self.generation,self.entry)
    def test_exact_candidate_after_explicit_forward_recovery_is_recognized(self):
        before=(self.root/'resources.json').read_bytes()
        self.recover()
        self.assertEqual((self.root/'resources.json').read_bytes(),before)
    def test_foreign_or_drifted_candidate_is_refused(self):
        changes=[('Config','Labels',dict(self.labels,**{runtime.LABEL:'foreign'})),('Config','Labels',dict(self.labels,**{'ols.operation':'other'})),('Config','Labels',dict(self.labels,**{'ols.launch':'other'})),('Config','Image','different'),('Config','Cmd',['unsealed']),('Config','User','other'),('HostConfig','NetworkMode','host'),('HostConfig','PortBindings',{'443/tcp':[{}]}),('HostConfig','Privileged',True),('State','Running',True)]
        for section,key,value in changes:
            old=copy.deepcopy(self.actual)
            with self.subTest(section=section,key=key):
                self.actual[section][key]=value
                with self.assertRaises(RuntimeError):self.recover()
            self.actual=old
        self.actual['Mounts'][0]['RW']=True
        with self.assertRaises(RuntimeError):self.recover()
    def test_recognition_requires_original_sealed_entry(self):
        self.generation['deployment']['launch']['ingress']=dict(self.entry,name='other')
        with self.assertRaises(RuntimeError):self.recover()

    def test_unsealed_host_namespaces_and_device_rules_are_refused(self):
        for key,value in [('PidMode','host'),('IpcMode','host'),('UTSMode','host'),('UsernsMode','host'),('DeviceCgroupRules',['c *:* rwm'])]:
            with self.subTest(key=key):
                self.actual['HostConfig'][key]=value
                with self.assertRaises(RuntimeError):self.recover()
                del self.actual['HostConfig'][key]

    def test_switch_restores_only_verified_candidate_registration_without_creating_container(self):
        self.generation['parentGenerationId']='old'
        with patch.object(runtime,'inspect',side_effect=self.inspect),patch.object(m.tls_generation,'read',return_value=copy.deepcopy(self.generation)),patch.object(m,'copy_identity'),patch.object(m.tls_generation,'select'),patch.object(runtime,'run',side_effect=AssertionError('No Docker mutation required')):
            m.switch(self.root,self.oid,self.generation)
        resources=runtime.load(self.root)
        self.assertEqual(resources['ingress'],'candidate-entry')
        self.assertEqual(resources['containers']['entry'],'candidate-entry')
        self.assertEqual(resources['containers']['tlsRetainedentry'+self.oid],'old-entry')
        self.assertEqual(journal._read(self.root,self.root/'launch.json'),self.generation['deployment']['launch'])
