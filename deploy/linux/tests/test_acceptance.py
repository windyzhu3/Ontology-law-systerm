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

