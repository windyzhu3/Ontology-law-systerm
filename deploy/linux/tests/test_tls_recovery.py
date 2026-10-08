import importlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from ols_linux import journal

class RecoveryTests(unittest.TestCase):
    def test_gate_commit_response_loss_reuses_exact_intent(self):
        try:m=importlib.import_module('ols_linux.tls_rotation')
        except ImportError:self.fail('TLS gate recovery missing')
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/'runtime';op=journal.begin(root,'rotate-public-tls','a'*64)
            gate={'deployment_state_key':'PRIMARY','operating_mode':'ACTIVE','active_release_digest':'a'*64,'active_manifest_hash':'b'*64,'schema_contract_version':'52-plus-2-r2-v22','revision':29,'changed_at':'2026-10-08T00:00:00+00:00'}
            observed=[gate]
            def committed(*args):observed[0]=args[2];raise RuntimeError('response lost')
            with patch.object(m.database,'observe',side_effect=lambda root:{'gate':observed[0]}),patch.object(m.release,'_cas_gate',side_effect=committed):
                with self.assertRaises(RuntimeError):m.gate(root,op['operationId'],'maintenance','MAINTENANCE',expected_before=gate)
            with patch.object(m.database,'observe',side_effect=lambda root:{'gate':observed[0]}),patch.object(m.release,'_cas_gate',side_effect=AssertionError('must not replay CAS')):
                result=m.gate(root,op['operationId'],'maintenance','MAINTENANCE',expected_before=gate)
            self.assertEqual(result['revision'],30)

    def test_retry_response_loss_does_not_allocate_another_attempt(self):
        from ols_linux import tls_rotation as m
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/'runtime';op=journal.begin(root,'rotate-public-tls','a'*64);opid=op['operationId']
            journal.record(root,opid,{'phase':'BLOCKED'})
            m._save(root,opid,{'attempt':0,'candidate':{'directory':str(root)},'generationId':'g'})
            observed=[]
            def retry_gate(root,opid,name,mode,**kw):
                observed.append(name)
                if len(observed)==1:raise SystemExit('process lost after gate commit')
            with patch.object(m,'gate',side_effect=retry_gate),patch.object(m.tls_material,'verify',side_effect=SystemExit('stop before effects')):
                for _ in range(2):
                    with self.assertRaises(SystemExit):m.resume(root,opid,now=1)
            self.assertEqual(observed,['retry-1','retry-1'])

    def test_unknown_phase_fails_closed_without_reading_candidate(self):
        from ols_linux import tls_rotation as m
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/'runtime';op=journal.begin(root,'rotate-public-tls','a'*64);opid=op['operationId']
            journal.record(root,opid,{'phase':'CORRUPT_PHASE'})
            m._save(root,opid,{})
            with patch.object(m,'_fail') as closed,patch.object(m.tls_material,'verify') as verify:
                with self.assertRaisesRegex(RuntimeError,'Unknown original TLS phase'):m.resume(root,opid,now=1)
            closed.assert_called_once();verify.assert_not_called()

    def test_every_forward_phase_survives_process_loss_before_and_after_record(self):
        from contextlib import ExitStack
        from ols_linux import tls_rotation as m
        phases=['PREPARED','MAINTENANCE_REQUESTED','MAINTENANCE','STOPPING','QUIESCED','SWITCHING','SWITCHED','ACTIVATING','NATIVE_VERIFIED','PROXY_SWITCHING','READY_TO_OPEN','OPENING','COMPLETE']
        for lost_phase in phases:
            for after in [False,True]:
                with self.subTest(phase=lost_phase,after=after),tempfile.TemporaryDirectory() as directory,ExitStack() as stack:
                    root=Path(directory)/'runtime';original=journal.begin(root,'initialize','a'*64)
                    journal.record(root,original['operationId'],{'phase':'COMPLETE'})
                    original_path=root/'operations'/(original['operationId']+'.json');original_bytes=original_path.read_bytes()
                    op=journal.begin(root,'rotate-public-tls','b'*64);opid=op['operationId']
                    before={'deployment_state_key':'PRIMARY','operating_mode':'ACTIVE','active_release_digest':'a'*64,'active_manifest_hash':'b'*64,'schema_contract_version':'test','revision':29,'changed_at':'2026-10-08T00:00:00+00:00'}
                    observed=[before];revisions=[];generation={'generationId':'g','trust':{}}
                    data={'candidate':{'directory':str(root)},'generationId':'g','before':{'gate':before},'release':{'descriptor':{}}};m._save(root,opid,data)
                    def cas(root,before,expected):
                        self.assertEqual(observed[0],before);observed[0]=expected;revisions.append(expected['revision']);return expected
                    for obj,name,kwargs in [(m.tls_material,'verify',{}),(m,'_prepare',{'return_value':generation}),(m.tls_generation,'read',{'return_value':generation}),(m.tls_generation,'resolve',{'return_value':generation}),(m.tls_generation,'verify_trust',{}),(m.tls_deployment,'switch',{}),(m.tls_deployment,'verify_copies',{}),(m,'_stop',{}),(m,'_start',{}),(m,'_probe',{}),(m.tls_proxy,'apply',{}),(m.database,'observe',{'side_effect':lambda root:{'gate':observed[0]}}),(m.release,'_cas_gate',{'side_effect':cas})]:stack.enter_context(patch.object(obj,name,**kwargs))
                    stack.enter_context(patch('ols_linux.verify.ingress_ready'))
                    real_phase=m._phase;fired=[]
                    def lose(root,opid,phase,**kw):
                        if phase==lost_phase and not fired:
                            fired.append(True)
                            if after:real_phase(root,opid,phase,**kw)
                            raise SystemExit('process killed')
                        return real_phase(root,opid,phase,**kw)
                    with patch.object(m,'_phase',side_effect=lose):
                        with self.assertRaises(SystemExit):m.resume(root,opid,now=1)
                    result=m.resume(root,opid,now=1)
                    self.assertEqual(result['operationId'],opid);self.assertEqual(result['phase'],'COMPLETE')
                    self.assertEqual(revisions,[30,31]);self.assertEqual(original_path.read_bytes(),original_bytes)
                    self.assertEqual(len([p for p in (root/'operations').glob('*.json') if len(p.stem)==32]),2)

    def test_failure_stop_unknown_never_records_blocked_or_complete(self):
        from ols_linux import tls_rotation as m
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/'runtime';op=journal.begin(root,'rotate-public-tls','a'*64);opid=op['operationId']
            with patch.object(m,'_stop',side_effect=RuntimeError('stop unknown')),patch.object(m,'gate') as gate:
                with self.assertRaisesRegex(RuntimeError,'stop unknown'):m._fail(root,opid,{})
            self.assertEqual(journal.current(root)['phase'],'FAILING');gate.assert_not_called()

    def test_new_gate_intent_rejects_foreign_revision(self):
        from ols_linux import tls_rotation as m
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/'runtime';op=journal.begin(root,'rotate-public-tls','a'*64);opid=op['operationId']
            before={'deployment_state_key':'PRIMARY','operating_mode':'ACTIVE','active_release_digest':'a'*64,'active_manifest_hash':'b'*64,'schema_contract_version':'test','revision':29,'changed_at':'2026-10-08T00:00:00+00:00'}
            m._save(root,opid,{'before':{'gate':before}})
            with patch.object(m.database,'observe',return_value={'gate':dict(before,revision=99)}),patch.object(m.release,'_cas_gate') as cas:
                with self.assertRaisesRegex(RuntimeError,'another operation'):m.gate(root,opid,'active-0','ACTIVE')
            cas.assert_not_called()

    def test_rollback_probe_failure_can_resume_same_operation(self):
        from contextlib import ExitStack
        from ols_linux import tls_rotation as m
        with tempfile.TemporaryDirectory() as directory,ExitStack() as stack:
            root=Path(directory)/'runtime';op=journal.begin(root,'rotate-public-tls','a'*64);opid=op['operationId']
            before={'deployment_state_key':'PRIMARY','operating_mode':'ACTIVE','active_release_digest':'a'*64,'active_manifest_hash':'b'*64,'schema_contract_version':'test','revision':29,'changed_at':'2026-10-08T00:00:00+00:00'}
            old={'version':1,'generationId':'old','files':{},'candidate':{'notAfter':100}}
            resources={'containers':dict.fromkeys(['api','worker','identity','scanner','entry'],'fixture'),'ingress':'entry'}
            m._save(root,opid,{'previousGeneration':old,'generationId':'new','previousResources':resources,'previousLaunch':{},'release':{'descriptor':{}},'before':{'gate':before}})
            observed=[before]
            def cas(root,prior,expected):observed[0]=expected;return expected
            probes=[]
            def probe(*args):
                probes.append(True)
                if len(probes)==1:raise RuntimeError('probe unavailable')
            for obj,name,kw in [(m.runtime,'load',{'return_value':resources}),(m.runtime,'save',{}),(m.runtime,'stop_writers',{}),(m.tls_proxy,'apply',{'return_value':{'closed':True}}),(m.tls_deployment,'copy_identity',{}),(m,'_start',{}),(m,'_probe',{'side_effect':probe}),(m.database,'observe',{'side_effect':lambda root:{'gate':observed[0]}}),(m.release,'_cas_gate',{'side_effect':cas})]:stack.enter_context(patch.object(obj,name,**kw))
            with self.assertRaisesRegex(RuntimeError,'probe unavailable'):m.rollback(root,opid,now=1)
            result=m.resume(root,opid,now=1)
            self.assertEqual(result['operationId'],opid);self.assertEqual(result['outcome'],'ROLLED_BACK')

    def test_rollback_phase_crashes_preserve_original_operation(self):
        from contextlib import ExitStack
        from ols_linux import tls_rotation as m
        phases=['ROLLBACK_REQUESTED','ROLLBACK_STOPPING','ROLLBACK_SWITCHING','ROLLBACK_ACTIVATING','ROLLBACK_OPENING','COMPLETE']
        for lost_phase in phases:
            for after in [False,True]:
                with self.subTest(phase=lost_phase,after=after),tempfile.TemporaryDirectory() as directory,ExitStack() as stack:
                    root=Path(directory)/'runtime';op=journal.begin(root,'rotate-public-tls','a'*64);opid=op['operationId']
                    before={'deployment_state_key':'PRIMARY','operating_mode':'ACTIVE','active_release_digest':'a'*64,'active_manifest_hash':'b'*64,'schema_contract_version':'test','revision':29,'changed_at':'2026-10-08T00:00:00+00:00'}
                    old={'version':1,'generationId':'old','files':{},'candidate':{'notAfter':100}}
                    resources={'containers':dict.fromkeys(['api','worker','identity','scanner','entry'],'fixture'),'ingress':'entry'}
                    m._save(root,opid,{'previousGeneration':old,'generationId':'new','previousResources':resources,'previousLaunch':{},'release':{'descriptor':{}},'before':{'gate':before}})
                    observed=[before];revisions=[]
                    def cas(root,prior,expected):
                        self.assertEqual(observed[0],prior);observed[0]=expected;revisions.append(expected['revision']);return expected
                    for obj,name,kw in [(m.runtime,'load',{'return_value':resources}),(m.runtime,'save',{}),(m.runtime,'stop_writers',{}),(m.tls_proxy,'apply',{'return_value':{'closed':True}}),(m.tls_generation,'resolve',{'return_value':old}),(m.tls_deployment,'copy_identity',{}),(m,'_start',{}),(m,'_probe',{}),(m.database,'observe',{'side_effect':lambda root:{'gate':observed[0]}}),(m.release,'_cas_gate',{'side_effect':cas})]:stack.enter_context(patch.object(obj,name,**kw))
                    real_phase=m._phase;fired=[]
                    def lose(root,opid,phase,**kw):
                        if phase==lost_phase and not fired:
                            fired.append(True)
                            if after:real_phase(root,opid,phase,**kw)
                            raise SystemExit('process killed')
                        return real_phase(root,opid,phase,**kw)
                    with patch.object(m,'_phase',side_effect=lose):
                        with self.assertRaises(SystemExit):m.rollback(root,opid,now=1)
                    result=m.resume(root,opid,now=1) if journal.current(root)['phase']!='CREATED' else m.rollback(root,opid,now=1)
                    self.assertEqual(result['operationId'],opid);self.assertEqual(result['outcome'],'ROLLED_BACK');self.assertEqual(revisions,[30,31])

    def test_failing_phase_and_blocked_gate_crashes_reconcile(self):
        from ols_linux import tls_rotation as m
        for point in ['FAILING','BLOCKED','gate']:
            for after in [False,True]:
                with self.subTest(point=point,after=after),tempfile.TemporaryDirectory() as directory:
                    root=Path(directory)/'runtime';op=journal.begin(root,'rotate-public-tls','a'*64);opid=op['operationId']
                    before={'deployment_state_key':'PRIMARY','operating_mode':'ACTIVE','active_release_digest':'a'*64,'active_manifest_hash':'b'*64,'schema_contract_version':'test','revision':29,'changed_at':'2026-10-08T00:00:00+00:00'}
                    data={'before':{'gate':before}};m._save(root,opid,data);observed=[before];fired=[];real_phase=m._phase
                    def cas(root,prior,expected):
                        if point=='gate' and not fired:
                            fired.append(True)
                            if after:observed[0]=expected
                            raise SystemExit('gate response lost')
                        observed[0]=expected;return expected
                    def phase(root,opid,name,**kw):
                        if point==name and not fired:
                            fired.append(True)
                            if after:real_phase(root,opid,name,**kw)
                            raise SystemExit('record response lost')
                        return real_phase(root,opid,name,**kw)
                    with patch.object(m,'_stop'),patch.object(m.database,'observe',side_effect=lambda root:{'gate':observed[0]}),patch.object(m.release,'_cas_gate',side_effect=cas),patch.object(m,'_phase',side_effect=phase):
                        with self.assertRaises(SystemExit):m._fail(root,opid,data)
                        m._fail(root,opid,data)
                    self.assertEqual(journal.current(root)['phase'],'BLOCKED');self.assertEqual(observed[0]['revision'],30)
