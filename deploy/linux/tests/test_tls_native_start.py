from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from ols_linux import identity,runtime
from ols_linux.config import digest


class NativeIdentityStartTests(unittest.TestCase):
    def test_unrotated_identity_start_reaches_original_database_login(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            plan={'instanceId':'i','operationId':'o','pod':'pod','identity':'identity','ports':{}}
            resources={'repo':str(root),'containers':{'pod':'pod','identity':'identity'},'writers':['identity']}
            actual={'Config':{'Labels':{'ols.identity-plan':digest(plan)}},'State':{'Running':True},'NetworkSettings':{'Ports':{}}}
            with patch.object(runtime,'load',return_value=resources),patch.object(runtime,'save'),patch.object(runtime,'inspect',return_value=actual),patch.object(runtime,'owned',return_value=actual),patch.object(identity,'_database_login',side_effect=RuntimeError('Original login reached')):
                with self.assertRaisesRegex(RuntimeError,'Original login reached'):identity._start(root,plan)
