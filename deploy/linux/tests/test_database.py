import importlib
from pathlib import Path
import sys
import tempfile
import unittest
import json

LINUX=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(LINUX))


class DatabaseTest(unittest.TestCase):
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
