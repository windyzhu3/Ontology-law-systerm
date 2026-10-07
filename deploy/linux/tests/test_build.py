import importlib
import json
from pathlib import Path
import tempfile
import unittest


class BuildTests(unittest.TestCase):
    def test_native_completion_requires_the_complete_runtime_payload(self):
        from unittest.mock import patch
        module=self.module()
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);jar=root/'backend/target/ontology-law-system-0.1.0-SNAPSHOT.jar'
            jar.parent.mkdir(parents=True);jar.write_bytes(b'packaged-fixture')
            spa=root/'apps/workbench/dist';spa.mkdir(parents=True);(spa/'index.html').write_bytes(b'fixture')
            with patch.object(module.bundle,'describe',return_value={'version':2}) as describe:
                self.assertEqual(module.completion(root,'a'*40,0,0)['version'],2)
                self.assertTrue(describe.call_args.kwargs.get('include_runtime'),
                    'Native releases must seal the executable helper, template and runtime payload')

    def module(self):
        try:
            return importlib.import_module('ols_linux.build')
        except ImportError:
            self.fail('Controlled native Linux build entry is missing')

    def test_build_resources_are_exact_source_and_manifest_before_packaging(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); path=root/'database/schema-contract-52-plus-2/generated/schema-contract-manifest.json'
            path.parent.mkdir(parents=True); path.write_bytes(b'{"contractVersion":"52-plus-2-r2-v22"}\n')
            target=self.module().resources(root,'a'*40)
            self.assertEqual((target/'schema-contract/schema-contract-manifest.json').read_bytes(),path.read_bytes())
            source=json.loads((target/'schema-contract/build-source.json').read_text())
            self.assertEqual(source['commit'],'a'*40)
            self.assertEqual(len(source['manifestHash']),64)

    def test_failed_build_cannot_emit_a_success_proof(self):
        module=self.module()
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            with self.assertRaises(RuntimeError): module.completion(root,'a'*40,1,0)
            self.assertFalse((root/'.artifacts/linux-build-proof.json').exists())

    def test_archive_rejects_links_and_traversal_before_extraction(self):
        import io,tarfile
        module=self.module()
        for name in ['../outside','/absolute']:
            payload=io.BytesIO()
            with tarfile.open(fileobj=payload,mode='w') as archive:
                entry=tarfile.TarInfo(name); archive.addfile(entry)
            with tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(RuntimeError): module.extract(payload.getvalue(),Path(directory))

    def test_maven_download_rejects_an_unpinned_wrapper(self):
        module=self.module()
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);p=root/'.mvn/wrapper/maven-wrapper.properties';p.parent.mkdir(parents=True)
            p.write_text('distributionUrl=https://example.invalid/maven.zip\ndistributionSha256Sum='+'0'*64)
            with self.assertRaises(RuntimeError): module.maven(root)
