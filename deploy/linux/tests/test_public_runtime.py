import importlib
from pathlib import Path
import unittest


class PublicRuntimeTests(unittest.TestCase):
    def module(self):
        try:return importlib.import_module('ols_linux.public_runtime')
        except ImportError:self.fail('Execution-time public HTTPS inputs missing')

    def test_explicit_https_origins_are_bounded_and_do_not_change_internal_api(self):
        m=self.module();ports={'entry':24844,'identity':24843,'api':24845}
        self.assertEqual(m.origins({},ports)['origin'],'https://localhost:24844')
        value=m.origins({'publicOrigin':'https://work.example.invalid','identityOrigin':'https://login.example.invalid'},ports)
        self.assertEqual(value,{'origin':'https://work.example.invalid','identityOrigin':'https://login.example.invalid','apiOrigin':'https://localhost:24845'})
        for bad in ['http://work.example.invalid','https://user:password@work.example.invalid','https://work.example.invalid/path',
                    'https://work.example.invalid?query=1','https://work.example.invalid#fragment','https://work.example.invalid/../']:
            with self.assertRaises(ValueError):m.origins({'publicOrigin':bad,'identityOrigin':'https://login.example.invalid'},ports)
        with self.assertRaises(ValueError):m.origins({'publicOrigin':'https://work.example.invalid'},ports)

    def test_remote_origin_requires_paired_private_tls_inputs_before_effects(self):
        m=self.module()
        with self.assertRaises(RuntimeError):m.inputs({'publicOrigin':'https://work.example.invalid','identityOrigin':'https://login.example.invalid'})
        self.assertEqual(m.inputs({}),{})

