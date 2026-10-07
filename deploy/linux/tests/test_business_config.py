import importlib
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from ols_linux.config import load
from ols_linux import journal


class BusinessConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.cfg=load(Path(__file__).resolve().parents[1]/'config/haihua.json')
        uid=lambda n:'00000000-0000-4000-8000-'+str(n).zfill(12)
        self.state={'organizations':{o['code']:uid(i) for i,o in enumerate(self.cfg['organizations'],1)},'principals':{p['username']:uid(i) for i,p in enumerate(self.cfg['people'],20)},'appointments':{a['key']:uid(i) for i,a in enumerate([a for p in self.cfg['people'] for a in p['appointments']],50)}}
        self.tenant=uid(100)
    def module(self):
        try:return importlib.import_module('ols_linux.business_config')
        except ImportError:self.fail('Bounded trusted business configuration module missing')
    def test_source_routes_and_defaults_use_exact_instance_ids(self):
        props=self.module().settings(self.cfg,self.state,self.tenant)
        self.assertEqual(sum(k.endswith('.principal-id') and 'human-intake-bindings' in k for k in props),17)
        for i,source in enumerate(self.cfg['intakeSources']):
            self.assertEqual(props[f'ols.api.human-intake-bindings[{i}].principal-id'],self.state['principals'][source['username']])
            self.assertEqual(props[f'ols.api.human-intake-bindings[{i}].source-account-code'],source['account'])
        self.assertEqual(sum(k.endswith('.appointment-id') and 'responsibility-routes' in k for k in props),14)
        for i,route in enumerate(self.cfg['responsibilityRoutes']):self.assertEqual(props[f'ols.api.responsibility-routes[{i}].appointment-id'],self.state['appointments'][route['appointment']])
        self.assertEqual(props[f'ols.api.tenant-keys[{self.tenant}].payment.account-label'],self.cfg['defaults']['receiptLabel'])
        self.assertEqual(props[f'ols.api.tenant-keys[{self.tenant}].transfer-destination-organization-id'],self.state['organizations']['CASE_ADMIN'])
    def test_missing_uuid_and_unapproved_stage_fail_closed(self):
        state=dict(self.state,appointments={k:v for k,v in self.state['appointments'].items() if k!='yangsheng'})
        with self.assertRaises((KeyError,ValueError,RuntimeError)):self.module().settings(self.cfg,state,self.tenant)
        cfg=dict(self.cfg,responsibilityRoutes=[dict(self.cfg['responsibilityRoutes'][0],stage='PREPARE')])
        with self.assertRaises((ValueError,RuntimeError)):self.module().settings(cfg,self.state,self.tenant)

    def test_policy_statement_uses_original_ids_and_only_existing_configuration_tables(self):
        module=self.module();plan=module.policy_plan(self.cfg,self.state,self.tenant)
        self.assertEqual(len(plan),2)
        self.assertEqual([p['approver'] for p in plan],[self.state['appointments']['wanhefeng'],self.state['appointments']['gengtangqi']])
        sql=module.policy_statement(self.tenant,plan)
        for forbidden in ('CREATE ','UPDATE ','DELETE ','identity.authority_grant','approval_decision','signature_readiness','payment_confirmation'):self.assertNotIn(forbidden,sql)
        self.assertEqual(sql.count('INSERT INTO '),8)
        for p in plan:
            for key in ('quotePolicyId','quoteSignerId','contractPolicyId','contractMemberId'):self.assertIn(p[key],sql)

    def test_committed_unknown_policy_transaction_reconciles_exact_original_ids_without_new_sql(self):
        m=self.module();plan=m.policy_plan(self.cfg,self.state,self.tenant)
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)/'private';op=journal.begin(root,'initialize','a'*64)
            journal._write(root,root/'business/configuration.json',{'operationId':op['operationId'],'policies':plan,'policyState':'PLANNED'})
            empty={k:[] for k in m.policy_expected(self.tenant,plan)}
            def uncertain(*args,**kwargs):
                self.assertEqual(journal._read(root,root/'business/configuration.json')['policyState'],'UNKNOWN')
                raise RuntimeError('Committed response lost')
            with patch.object(m,'policy_inventory',return_value=empty),patch.object(m.database,'sql',side_effect=uncertain):
                with self.assertRaises(RuntimeError):m.ensure_policies(root,self.tenant,plan)
            with patch.object(m,'policy_inventory',return_value=m.policy_expected(self.tenant,plan)),patch.object(m.database,'sql',side_effect=AssertionError('Original committed policies must not be rewritten')):
                m.ensure_policies(root,self.tenant,plan)
            self.assertEqual(journal._read(root,root/'business/configuration.json')['policyState'],'VERIFIED')
            with patch.object(m,'policy_inventory',return_value=dict(empty,foreign=[{'id':'foreign'}])),patch.object(m.database,'sql',side_effect=AssertionError('Foreign policies must not be adopted')):
                with self.assertRaises(RuntimeError):m.ensure_policies(root,self.tenant,plan)
