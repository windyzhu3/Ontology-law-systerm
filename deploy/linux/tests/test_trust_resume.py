import subprocess
import unittest
from unittest.mock import patch
from pathlib import Path
from ols_linux import assembly


class TrustResumeTests(unittest.TestCase):
    def test_existing_store_does_not_skip_missing_public_anchor(self):
        calls=[]
        def command(args,**kwargs):
            calls.append(args)
            if '-exportcert' in args:
                alias=args[args.index('-alias')+1]
                return subprocess.CompletedProcess(args,0 if alias=='ols-ca' or len(calls)>2 else 1,b'CERT',b'')
            return subprocess.CompletedProcess(args,0,b'',b'')
        with patch.object(assembly.runtime,'run',side_effect=command),patch.object(assembly,'certificate_der',return_value=b'CERT'):
            assembly.ensure_trust_anchor(Path('/private'),{'instanceId':'instance','runtimeImage':'pinned'},'ols-public-ca',Path('/private/certs/public-ca.pem'))
        self.assertTrue(any('-importcert' in args for args in calls))

    def test_existing_wrong_anchor_is_not_adopted(self):
        result=subprocess.CompletedProcess([],0,b'OTHER',b'')
        with patch.object(assembly.runtime,'run',return_value=result),patch.object(assembly,'certificate_der',return_value=b'CERT'):
            with self.assertRaises(RuntimeError):
                assembly.ensure_trust_anchor(Path('/private'),{'instanceId':'instance','runtimeImage':'pinned'},'ols-public-ca',Path('/private/certs/public-ca.pem'))
