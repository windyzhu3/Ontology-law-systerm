import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from ols_linux import journal


class OriginalProofTests(unittest.TestCase):
    def test_readonly_repeat_retains_only_confirmed_original_lost_response_proof(self):
        path=Path(__file__).resolve().parents[1]/'verification/initialization.py'
        spec=importlib.util.spec_from_file_location('initialization_proof',path)
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)/'private';op=journal.begin(root,'initialize','a'*64)
            command='00000000-0000-4000-8000-000000000001'
            self.assertFalse(module.lost_response_confirmed(root,op['operationId']))
            journal._write(root,root/'verification/lost-admin-response.json',{'operationId':op['operationId'],'commandId':command})
            journal._write(root,root/'admin-commands'/(command+'.json'),{'operationId':op['operationId'],'state':'DISPATCH_UNKNOWN'})
            self.assertFalse(module.lost_response_confirmed(root,op['operationId']))
            journal._write(root,root/'admin-commands'/(command+'.json'),{'operationId':op['operationId'],'state':'CONFIRMED','receipt':{'commandId':command,'outcome':'SUCCEEDED'}})
            self.assertTrue(module.lost_response_confirmed(root,op['operationId']))
            self.assertFalse(module.lost_response_confirmed(root,'b'*32))
