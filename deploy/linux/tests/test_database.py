import importlib
from pathlib import Path
import sys
import tempfile
import unittest
import json

LINUX=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(LINUX))


class DatabaseTest(unittest.TestCase):
    def test_sealed_v20_migration_sources_remain_valid_after_installation(self):
        import shutil
        repo=LINUX.parents[1]
        with tempfile.TemporaryDirectory() as folder:
            old=Path(folder);generated=old/self.m.GENERATED
            shutil.copytree(repo/self.m.GENERATED,generated)
            for name in ('V1070__configurable_appointment_roles.sql','V1080__metadata_comments.sql'):
                (generated/'db/migration'/name).unlink()
            manifest=generated/'schema-contract-manifest.json';value=json.loads(manifest.read_text(encoding='utf-8'))
            value['contractVersion']='52-plus-2-r2-v20';manifest.write_text(json.dumps(value),encoding='utf-8')
            self.m.verify_sources(old)
            (generated/'db/migration/V1060__r25_contract_responsibility_recovery.sql').write_bytes(b'changed')
            with self.assertRaises(RuntimeError):self.m.verify_sources(old)

    def test_forward_migration_source_is_the_exact_original_upgrade_candidate(self):
        from unittest.mock import patch
        from ols_linux import journal
        self.assertTrue(callable(getattr(self.m,'source_directory',None)), 'Original sealed migration source selector absent')
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)/'runtime';op=journal.begin(root,'upgrade','a'*64)
            candidate=Path(folder)/'candidate';candidate.mkdir()
            journal._write(root,root/'operations'/(op['operationId']+'-inputs.json'),{'bundleDirectory':str(candidate),'descriptor':{'descriptorDigest':'a'*64}})
            with patch('ols_linux.bundle.verify') as verified:
                self.assertEqual(self.m.source_directory(root,candidate),candidate)
                verified.assert_called_once()
            with self.assertRaises(RuntimeError):self.m.source_directory(root,Path(folder)/'foreign')

    def setUp(self):
        self.assertTrue((LINUX/'ols_linux/database.py').is_file(),'controlled Flyway boundary missing')
        self.m=importlib.import_module('ols_linux.database')

    def test_clean_repair_and_unknown_target_rejected_before_effect(self):
        for action,target in [('clean',None),('repair',None),('migrate','latest'),('migrate','1071')]:
            with self.assertRaises(ValueError):self.m.flyway(Path('/missing'),'a'*32,action,target)

    def test_expected_sql_versions_are_numeric_and_complete(self):
        versions=self.m.expected_versions(LINUX.parents[1])
        self.assertEqual(len(versions),43)
        self.assertEqual(versions[:2],['001','002'])
        self.assertEqual(versions[-3:],['1060','1070','1080'])
        self.assertEqual(len(self.m.expected_versions(LINUX.parents[1],'1060')),41)

    def test_empty_database_source_validation_is_not_manual_schema_creation(self):
        self.assertTrue(callable(getattr(self.m,'verify_sources',None)), 'fresh source preflight missing')
        self.m.verify_sources(LINUX.parents[1])

    def test_history_checksums_and_scripts_are_checked_before_maintenance(self):
        self.assertTrue(callable(getattr(self.m,'verify_history',None)), 'Exact history checksum preflight missing')
        checksums=self.m.migration_checksums(LINUX.parents[1],'1060')
        history=[{'version':None,'type':'SCHEMA','success':True}]+[{'version':version,'type':'SQL','success':True,'checksum':row['checksum'],'script':row['script']} for version,row in checksums.items()]
        self.m.verify_history(LINUX.parents[1],history,'1060')
        for field,value in [('checksum',0),('script','V999__unreviewed.sql'),('type','JDBC')]:
            changed=json.loads(json.dumps(history));changed[1][field]=value
            with self.assertRaises(RuntimeError):self.m.verify_history(LINUX.parents[1],changed,'1060')
        with self.assertRaises(RuntimeError):self.m.verify_history(LINUX.parents[1],history+[history[0]],'1060')


if __name__=='__main__':unittest.main()
