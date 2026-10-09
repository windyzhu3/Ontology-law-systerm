import importlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from ols_linux import journal,runtime
from ols_linux.bundle import inventory


class TlsStatusTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)/'runtime';self.op=journal.begin(self.root,'initialize','a'*64)
        journal.record(self.root,self.op['operationId'],{'phase':'COMPLETE'})
        try:self.m=importlib.import_module('ols_linux.tls_status')
        except ImportError:self.fail('Read-only public TLS status missing')
        self.now=1800000000;self.generation={'generationId':'g','candidate':{'notAfter':self.now+90*86400},'paths':{'certificate':'/fixture'}}
    def status(self,previous=None):
        with patch.object(self.m.tls_generation,'resolve',return_value=self.generation),patch.object(self.m.tls_probe,'collect',return_value={'status':'PASS','targets':{'nativeIdentity':{'status':'PASS'}},'consumers':{}}),patch.object(self.m.tls_material,'metadata',return_value={'notBefore':'Jan 1 00:00:00 2020 GMT'}):
            return self.m.status(self.root,now=self.now,previous_check=previous)
    def test_exact_expiry_thresholds_and_stale_evidence(self):
        for days,expected in [(31,'OK'),(30,'WARNING'),(14,'ACTION_REQUIRED'),(7,'CRITICAL'),(0,'BLOCKED')]:
            self.assertEqual(self.m.evaluate(self.now+days*86400,self.now,self.now,verified=True)['status'],expected)
        self.assertEqual(self.m.evaluate(self.now+90*86400,self.now,self.now-26*3600-1,verified=True)['status'],'UNKNOWN')
        self.assertEqual(self.m.evaluate(self.now+90*86400,self.now,self.now-26*3600,verified=True)['status'],'OK')
    def test_status_does_not_mutate_runtime(self):
        before=inventory(self.root)
        with patch.object(journal,'_write',side_effect=AssertionError('status must not write')):
            result=self.status()
        self.assertEqual(inventory(self.root),before)
        self.assertEqual(result['monitoringState'],'UNCONFIGURED');self.assertEqual(result['status'],'UNKNOWN')
        self.assertEqual(result['probe']['status'],'PASS')
        previous=Path(self.temp.name)/'check.json';runtime.private_file(previous,json.dumps(result).encode())
        self.assertEqual(self.status(previous)['status'],'OK')
    def test_check_evidence_rejects_wrong_instance_mac_and_future_time(self):
        original=self.status()['checkEvidence']
        for field,value in [('instanceId','wrong'),('checkedAt',self.now+1)]:
            changed=json.loads(json.dumps(original));changed['payload'][field]=value
            changed=self.m.sign(self.root,changed['payload'])
            previous=Path(self.temp.name)/'check.json';runtime.private_file(previous,json.dumps({'checkEvidence':changed}).encode())
            with self.assertRaises(RuntimeError):self.status(previous)
        changed=json.loads(json.dumps(original));changed['mac']='0'*64
        runtime.private_file(previous,json.dumps({'checkEvidence':changed}).encode())
        with self.assertRaises(RuntimeError):self.status(previous)
    def test_failed_old_check_time_is_not_a_successful_check(self):
        payload=self.status()['checkEvidence']['payload']
        payload.update(probeStatus='UNKNOWN',lastSuccessfulCheckAt=None)
        previous=Path(self.temp.name)/'check.json';runtime.private_file(previous,json.dumps({'checkEvidence':self.m.sign(self.root,payload)}).encode())
        self.assertEqual(self.status(previous)['monitoringState'],'UNCONFIGURED')
