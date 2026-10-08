import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from ols_linux import journal,runtime

class AcceptanceTests(unittest.TestCase):
    def module(self):
        path=Path(__file__).parents[1]/'verification/public_tls_rotation.py'
        self.assertTrue(path.exists(),'Real TLS acceptance driver missing')
        spec=importlib.util.spec_from_file_location('tls_acceptance',path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
    def test_missing_or_skipped_scenario_is_incomplete(self):
        m=self.module()
        for reports in [{},{k:{'status':'SKIP'} for k in m.SCENARIOS}]:
            with self.assertRaises(RuntimeError):m.require_coverage(reports)
    def test_production_or_unowned_fixture_refused_before_effects(self):
        m=self.module()
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)/'runtime';journal.begin(root,'initialize','a'*64);runtime.save(root,{'verification':False})
            with patch.object(m.tls_rotation,'begin') as begin:
                with self.assertRaises(RuntimeError):m.require_fixture(root)
                begin.assert_not_called()
    def test_proxy_only_success_does_not_satisfy_acceptance(self):
        m=self.module()
        with self.assertRaises(RuntimeError):m.require_proof({'status':'PASS','targets':{k:{'status':'PASS'} for k in ['publicIdentity','publicEntry']},'consumers':{}})
