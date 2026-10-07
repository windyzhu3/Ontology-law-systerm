from pathlib import Path
import tempfile
import unittest
from ols_linux import checkpoint,journal


class DurableProofTests(unittest.TestCase):
    def test_proof_target_survives_controller_temp_directory_loss(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/'private';op=journal.begin(root,'initialize','a'*64)
            target=checkpoint.proof_target(root,op['operationId'])
            self.assertEqual(target.parent,root.parent)
            self.assertNotEqual(target.parent,Path(tempfile.gettempdir()))
            journal.begin(target,'initialize','b'*64)
            with self.assertRaises(RuntimeError):checkpoint.proof_target(root,op['operationId'])
