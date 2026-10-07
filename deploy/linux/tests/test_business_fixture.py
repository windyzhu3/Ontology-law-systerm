import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch


class BusinessFixtureIsolationTests(unittest.TestCase):
    def module(self):
        path=Path(__file__).resolve().parents[1]/'verification/business_fixture.py'
        self.assertTrue(path.exists(),'Synthetic fixture isolation guard missing')
        spec=importlib.util.spec_from_file_location('business_fixture',path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m

    def test_production_like_parent_is_refused_before_synthetic_effects(self):
        m=self.module()
        with self.assertRaises(RuntimeError):m.allow(Path('/parent'),Path('/parent'))

    def test_unregistered_other_instance_is_refused(self):
        m=self.module()
        with patch.object(m.journal,'_read',return_value={'runtime':'/other','instanceId':'other'}):
            with self.assertRaises(RuntimeError):m.allow(Path('/synthetic'),Path('/parent'))
