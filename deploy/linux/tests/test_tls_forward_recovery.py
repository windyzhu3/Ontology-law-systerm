"""Forward-only recovery boundary: real private journal, simulated host effects."""
from contextlib import ExitStack
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from ols_linux import journal,tls_rotation as m,tls_systemd_qualification as q


class ForwardRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)/'runtime';self.op=journal.begin(self.root,'rotate-public-tls','a'*64);self.oid=self.op['operationId']
        journal.record(self.root,self.oid,{'phase':'ROLLBACK_BLOCKED'})
        self.gate={'operating_mode':'BLOCKED','revision':35}
        self.data={'attempt':0,'rollbackAttempt':1,'candidate':{'directory':str(self.root)},'generationId':'new','previousGeneration':{'generationId':'old'}}
        m._save(self.root,self.oid,self.data)
        journal._write(self.root,self.root/'operations'/(self.oid+'-tls-gate-rollback-blocked-1.json'),{'expected':self.gate})
        journal._write(self.root,self.root/'operations'/(self.oid+'-proxy.json'),{'registration':{'version':1,'services':[]}})
        self.stack=ExitStack();self.addCleanup(self.stack.close)
        for obj,name,kw in [(m.tls_material,'verify',{}),(m.tls_generation,'read',{'return_value':{'generationId':'new','parentGenerationId':'old','operationId':self.oid,'trust':{}}}),(m.tls_generation,'resolve',{'return_value':{'generationId':'old'}}),(m.tls_generation,'verify_trust',{}),(m.database,'observe',{'return_value':{'gate':self.gate}}),(m.tls_proxy,'observe',{'return_value':{'closed':True}}),(m.runtime,'load',{'return_value':{'writers':['api'],'ingress':'entry'}}),(m.runtime,'inspect',{'return_value':{'State':{'Running':False}}}),(m.runtime,'owned',{'return_value':{'State':{'Running':False}}})]:
            self.stack.enter_context(patch.object(obj,name,**kw))

    def test_explicit_forward_recovery_records_new_attempt_without_rollback(self):
        def resume(root,oid,**kw):
            op=journal.current(root)
            self.assertEqual(op['phase'],'RETRYING')
            self.assertEqual(op['events'][-1]['retryAttempt'],1)
            self.assertEqual(op['events'][-1]['recoveryFrom'],'ROLLBACK_BLOCKED')
            return {'operationId':oid,'phase':'RETRYING'}
        with patch.object(m,'resume',side_effect=resume),patch.object(m,'rollback',side_effect=AssertionError('Forbidden')):
            result=m.forward_resume(self.root,self.oid,now=1)
        self.assertEqual(result['operationId'],self.oid)

    def test_foreign_gate_or_live_writer_cannot_change_recovery_phase(self):
        for obj,name,kwargs in [(m.database,'observe',{'return_value':{'gate':dict(self.gate,revision=36)}}),(m.runtime,'owned',{'return_value':{'State':{'Running':True}}}),(m.tls_proxy,'observe',{'return_value':{'closed':False}}),(m.tls_generation,'resolve',{'return_value':{'generationId':'foreign'}})]:
            with self.subTest(boundary=name),patch.object(obj,name,**kwargs),self.assertRaises(RuntimeError):
                m.forward_resume(self.root,self.oid,now=1)
            self.assertEqual(journal.current(self.root)['phase'],'ROLLBACK_BLOCKED')

    def test_candidate_rejection_preserves_blocked_phase(self):
        with patch.object(m.tls_material,'verify',side_effect=RuntimeError('Expired candidate')),self.assertRaisesRegex(RuntimeError,'Expired candidate'):
            m.forward_resume(self.root,self.oid,now=1)
        self.assertEqual(journal.current(self.root)['phase'],'ROLLBACK_BLOCKED')

    def test_other_rollback_phases_do_not_silently_move_forward(self):
        journal.record(self.root,self.oid,{'phase':'ROLLBACK_ACTIVATING'})
        with self.assertRaises(RuntimeError):m.forward_resume(self.root,self.oid,now=1)
        self.assertEqual(journal.current(self.root)['phase'],'ROLLBACK_ACTIVATING')

    def test_interruption_after_forward_intent_does_not_allocate_another_attempt(self):
        with patch.object(m,'resume',side_effect=SystemExit('lost response')):
            with self.assertRaises(SystemExit):m.forward_resume(self.root,self.oid,now=1)
            with self.assertRaises(SystemExit):m.forward_resume(self.root,self.oid,now=1)
        events=[e for e in journal.current(self.root)['events'] if e.get('recoveryFrom')]
        self.assertEqual(len(events),1);self.assertEqual(events[0]['retryAttempt'],1)

    def test_complete_forward_recovery_is_idempotent(self):
        journal.record(self.root,self.oid,{'phase':'RETRYING','recoveryFrom':'ROLLBACK_BLOCKED','retryAttempt':1})
        journal.record(self.root,self.oid,{'phase':'COMPLETE','outcome':'ROTATED'})
        with patch.object(m,'resume',return_value={'phase':'COMPLETE','outcome':'ROTATED'}):
            self.assertEqual(m.forward_resume(self.root,self.oid,now=1)['outcome'],'ROTATED')

    def test_resume_revalidates_forward_qualification_before_gate_or_attempt_write(self):
        journal.record(self.root,self.oid,{'phase':'RETRYING','recoveryFrom':'ROLLBACK_BLOCKED','retryAttempt':1})
        journal._write(self.root,self.root/'operations'/(self.oid+'-proxy.json'),{'registration':{'version':2,'services':[]}})
        for function in [m.resume,m.forward_resume]:
            with patch.object(q,'require',side_effect=RuntimeError('Proof invalid')),patch.object(m,'gate',side_effect=AssertionError('Gate must not run')):
                with self.assertRaisesRegex(RuntimeError,'Proof invalid'):function(self.root,self.oid,now=1)
            self.assertEqual(journal._read(self.root,m._path(self.root,self.oid))['attempt'],0)
