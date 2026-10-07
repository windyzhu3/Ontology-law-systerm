import importlib.util
from pathlib import Path
import unittest


class AcceptanceContractTests(unittest.TestCase):
    def module(self):
        path=Path(__file__).resolve().parents[1]/'verification/acceptance.py'
        self.assertTrue(path.exists(),'Real Linux acceptance coordinator is absent')
        spec=importlib.util.spec_from_file_location('linux_acceptance',path)
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

    def test_missing_or_skipped_real_scenario_never_becomes_pass(self):
        m=self.module()
        with self.assertRaises(RuntimeError):m.require_coverage('all',{})
        with self.assertRaises(RuntimeError):m.require_coverage('empty',{'empty':{'status':'SKIP'}})

    def test_four_chain_combinations_must_be_distinct_and_complete(self):
        m=self.module()
        cases=[{'department':department,'entry':entry,'status':'PASS','classified':True,'ownersMatch':True}
               for department in ('SALES_1','SALES_2') for entry in ('QUOTE','DIRECT')]
        m.require_chains(cases)
        for replacement in [cases[:3],cases[:-1]+[cases[0]],cases[:-1]+[dict(cases[-1],classified=False)],
                            cases[:-1]+[dict(cases[-1],ownersMatch=False)]]:
            with self.assertRaises(RuntimeError):m.require_chains(replacement)

    def test_live_case_owner_and_final_matter_relationships_cannot_be_claimed(self):
        m=self.module()
        expected={'review':'yang','approval':'manager','signature':'yang','archive':'yang','payment':'finance','transferReview':'yang','intake':'yang','classification':'yang'}
        facts={key:[{'id':key,'owner':owner,'decision':m.CASE_DECISIONS[key]}] for key,owner in expected.items()}
        facts['signature']*=2;facts['contract']=[{'approvedRevision':'revision','execution':'execution'}]
        facts['intake'][0]['matter']='matter';facts['classification'][0].update(matter='matter',intake='intake',recipient='yang')
        m.require_case_facts(facts,expected)
        import copy
        for key,field,value in [('payment','owner','sales'),('classification','matter','another'),('classification','intake','another'),('classification','recipient','director')]:
            changed=copy.deepcopy(facts);changed[key][0][field]=value
            with self.assertRaises(RuntimeError):m.require_case_facts(changed,expected)
        changed=copy.deepcopy(facts);changed['contract'][0]['execution']=None
        with self.assertRaises(RuntimeError):m.require_case_facts(changed,expected)

