"""Explicit pinned-image regression; run separately from fast helper tests."""
import json
from pathlib import Path
import re
import subprocess
import unittest


class PinnedFeatureTest(unittest.TestCase):
    def test_deployment_disabled_features_are_accepted_by_locked_keycloak(self):
        root = Path(__file__).resolve().parents[3]
        lock = json.loads((root / 'deploy/identity/identity-toolchain.lock.json').read_text())['keycloak']
        configuration = (root / 'deploy/identity/compose.yaml').read_text()
        disabled = re.search(r'KC_FEATURES_DISABLED: ([^\r\n]+)', configuration).group(1)
        result = subprocess.run(['docker', 'run', '--rm', '--network', 'none', '--memory', '2g',
            lock['image'] + '@' + lock['platformDigest'], 'build', '--features-disabled=' + disabled], capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr.decode(errors='replace'))


if __name__ == '__main__':
    unittest.main()
