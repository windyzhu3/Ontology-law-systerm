"""Exact maintenance admission is distinct from application health PASS."""
from contextlib import redirect_stdout
import io,json,sys,tempfile
from pathlib import Path
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'verification/systemd_qualification'))
import bootstrap
from ols_linux import journal,runtime,database,tls_generation,tls_proxy


class MaintenanceGuardTests(unittest.TestCase):
    def test_probe_requires_original_blocked_gate_and_no_running_writers(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/'runtime';op=journal.begin(root,'rotate-public-tls','a'*64);oid=op['operationId']
            journal.record(root,oid,{'phase':'ROLLBACK_BLOCKED'})
            gate={'operating_mode':'BLOCKED','revision':35}
            data={'rollbackAttempt':1,'previousGeneration':{'generationId':'old'}}
            for name,payload in [(oid+'-tls.json',data),(oid+'-tls-gate-rollback-blocked-1.json',{'expected':gate})]:journal._write(root,root/'operations'/name,payload)
            for name in ['resources.json','launch.json','tls-selection.json','tls/active.json']:journal._write(root,root/name,{})
            resources={'writers':['api'],'ingress':'entry','containers':{'businessDb':'db','identityDb':'idb','pod':'pod','api':'api','entry':'entry'}}
            states={n:{'Id':n,'State':{'Running':n in {'db','idb','pod'}}} for n in resources['containers'].values()}
            def run():
                out=io.StringIO()
                with patch.object(sys,'argv',['probe',str(Path(__file__).resolve().parents[1]),str(root),oid]),redirect_stdout(out):
                    exec(compile(bootstrap.MAINTENANCE_PROBE,'maintenance-probe','exec'),{'__name__':'__main__'})
                return json.loads(out.getvalue())
            with patch.object(runtime,'load',return_value=resources),patch.object(runtime,'inspect',side_effect=lambda kind,n:states[n]),patch.object(runtime,'owned',side_effect=lambda r,kind,n:states[n]),patch.object(database,'observe',return_value={'gate':gate}),patch.object(database,'sql',return_value='1'),patch.object(tls_generation,'resolve',return_value={'generationId':'old'}),patch.object(tls_proxy,'observe',return_value={'closed':True}):
                first=run();self.assertEqual(first['status'],'MAINTENANCE_OBSERVED')
                self.assertEqual(first,run())
                states['api']['State']['Running']=True
                with self.assertRaises(RuntimeError):run()
                states['api']['State']['Running']=False
                with patch.object(database,'observe',return_value={'gate':dict(gate,revision=36)}),self.assertRaises(RuntimeError):run()

    def test_pinned_maintenance_snapshot_drift_is_rejected(self):
        from types import SimpleNamespace
        inputs={'healthCli':'/source/linux.py','healthPython':'/usr/bin/python3','healthRuntime':'/runtime','healthCliSha256':'hash','maintenanceBaseline':{'operationId':'a'*32,'snapshotSha256':'b'*64}}
        with patch.object(bootstrap,'command',return_value=SimpleNamespace(stdout=json.dumps({'status':'MAINTENANCE_OBSERVED','operationId':'a'*32,'snapshotSha256':'c'*64}).encode())),patch.object(Path,'read_bytes',return_value=b'cli'):
            inputs['healthCliSha256']=bootstrap.hashlib.sha256(b'cli').hexdigest()
            with self.assertRaisesRegex(RuntimeError,'baseline'):bootstrap.production_health(inputs)
