import importlib
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'verification'))


class BrowserRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.m = importlib.import_module('browser_runner')

    def test_nss_creation_is_noninteractive_bounded_and_idempotent(self):
        self.assertIn('if [ ! -f ', self.m.STARTUP)
        self.assertIn('timeout 10s certutil -N', self.m.STARTUP)
        self.assertIn('</dev/null >/dev/null 2>&1', self.m.STARTUP)

    def test_stopped_legacy_browser_is_preserved_before_new_launch(self):
        resources = {'name':'ols-test','containers':{'browser':'ols-test-browser'}}
        plan = {'operationId':'original'}
        previous = {'State':{'Running':False}, 'Config':{'Labels':{'ols.operation':'original'}}}
        with patch.object(self.m.runtime,'inspect',side_effect=lambda _,n: previous if n=='ols-test-browser' else None), \
             patch.object(self.m.runtime,'owned',return_value=previous), \
             patch.object(self.m.runtime,'save') as save:
            name, existing = self.m.browser_resource(Path('/private'),resources,plan,'a'*64)
        self.assertEqual('ols-test-browser-v2',name)
        self.assertIsNone(existing)
        self.assertEqual('ols-test-browser',resources['containers']['browserPrevious'])
        self.assertEqual(name,resources['containers']['browser'])
        save.assert_called_once()

    def test_running_or_changed_browser_is_never_replaced_or_restarted(self):
        for name,running in [('ols-test-browser',True),('ols-test-browser-v2',False)]:
            resources = {'name':'ols-test','containers':{'browser':name}}
            current={'State':{'Running':running},'Config':{'Labels':{'ols.operation':'original','ols.browser-launch':'wrong'}}}
            with patch.object(self.m.runtime,'inspect',return_value=current), \
                 patch.object(self.m.runtime,'owned',return_value=current), \
                 patch.object(self.m.runtime,'save') as save:
                with self.assertRaises(RuntimeError):self.m.browser_resource(Path('/private'),resources,{'operationId':'original'},'a'*64)
            save.assert_not_called()


if __name__ == '__main__': unittest.main()
