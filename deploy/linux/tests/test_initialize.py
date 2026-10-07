import importlib
from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from ols_linux.config import load


class InitializationTests(unittest.TestCase):
    def module(self):
        try:return importlib.import_module('ols_linux.initialize')
        except ImportError:self.fail('Exact HUMAN initialization planner missing')

    def test_cross_admin_plan_has_exact_roster_and_no_self_authorization(self):
        cfg=load(Path(__file__).resolve().parents[1]/'config/haihua.json')
        steps=self.module().steps(cfg)
        self.assertEqual(sum(s['kind']=='organization' for s in steps),6)
        self.assertEqual(sum(s['kind']=='principal' for s in steps),16)
        self.assertEqual(sum(s['kind']=='appointment' for s in steps),19)
        self.assertEqual([s['role']['code'] for s in steps if s['kind']=='role'],['DIRECTOR','FINANCE_SUPERVISOR','CASE_SUPERVISOR'])
        for s in steps:
            if s['kind']=='grant':
                self.assertNotEqual(s['actor'],s['username'])
                self.assertNotEqual(s['grant']['authority'],'LEAD_ASSIGN')
        manager=[s for s in steps if s['kind']=='grant' and s['grant']['authority'].startswith('IDENTITY_')]
        self.assertEqual(len(manager),8);self.assertEqual({s['actor'] for s in manager},{'dingqiming','huangxuexue'})
        huang_ready=max(i for i,s in enumerate(steps) if s in manager and s['username']=='huangxuexue')
        ding_business=min(i for i,s in enumerate(steps) if s['kind']=='grant' and s['username']=='dingqiming')
        self.assertLess(huang_ready,ding_business)

    def test_ordinary_case_people_remain_without_handling_grants(self):
        cfg=load(Path(__file__).resolve().parents[1]/'config/haihua.json')
        steps=self.module().steps(cfg)
        self.assertFalse(any(s['kind']=='grant' and s['username'] in {'jinhuijun','wanghui','wujingshu','houxiaofan','huyeru'} for s in steps))
        self.assertFalse(any(s['kind']=='grant' and s['username']=='chenlu' and s['grant']['authority']=='PAYMENT_CONFIRM' for s in steps))

    def test_created_uuid_must_correlate_to_original_receipt_not_name(self):
        module=self.module()
        actor={'username':'dingqiming','appointmentId':'00000000-0000-4000-8000-000000000001'}
        state={'principals':{'dingqiming':'00000000-0000-4000-8000-000000000002'}}
        row={'id':'00000000-0000-4000-8000-000000000003','displayName':'external same name'}
        with self.assertRaises(RuntimeError):module.created_id('00000000-0000-4000-8000-000000000004',state,actor,'principal',[row],[],{'resultFact':{'factRef':'unrelated'}})

    def test_all_create_routes_exist_in_the_closed_http_contract(self):
        module=self.module();contract=(Path(__file__).resolve().parents[3]/'contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf-8')
        for endpoint in module.ENDPOINT.values():self.assertIn('\n  /api/v1/admin/identity/'+endpoint+':\n',contract)
