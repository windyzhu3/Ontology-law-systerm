import importlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
LINUX=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(LINUX))
from ols_linux import journal


class CheckpointTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue((LINUX/'ols_linux/checkpoint.py').is_file(),'Linked checkpoint capability missing')
        self.m=importlib.import_module('ols_linux.checkpoint')
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)/'runtime';self.op=journal.begin(self.root,'upgrade','a'*64)

    def test_capture_requires_confirmed_stopped_writers(self):
        with self.assertRaises(RuntimeError):self.m.capture(self.root,self.op['operationId'])
        self.assertEqual(journal.current(self.root)['phase'],'CREATED')

    def test_old_facts_include_identity_grants_business_tasks_and_audit(self):
        before={n:{'count':1,'digest':'a'*64} for n in ['identity.appointment','identity.authority_grant','lead.lead','responsibility.task_occurrence','audit.audit_entry']}
        self.m.assert_preserved(before,before)
        for name in before:
            changed=dict(before);changed[name]={'count':1,'digest':'b'*64}
            with self.assertRaises(RuntimeError):self.m.assert_preserved(before,changed)
        with self.assertRaises(RuntimeError):self.m.assert_preserved(before,{})

    def test_restore_refuses_missing_or_unverified_checkpoint(self):
        with self.assertRaises(RuntimeError):self.m.restore(self.root,self.op['operationId'])

    def test_only_preexisting_postgres_create_is_omitted_from_role_restore(self):
        self.assertTrue(callable(getattr(self.m,'role_restore_sql',None)))
        sql=b'CREATE ROLE "postgres";\nALTER ROLE "postgres" WITH SUPERUSER;\nCREATE ROLE "law_api_login";\n'
        actual=self.m.role_restore_sql(sql)
        self.assertNotIn(b'CREATE ROLE "postgres";',actual)
        self.assertIn(b'ALTER ROLE "postgres" WITH SUPERUSER;',actual)
        self.assertIn(b'CREATE ROLE "law_api_login";',actual)
        for invalid in [b'CREATE ROLE postgres;',sql+sql]:
            with self.assertRaises(RuntimeError):self.m.role_restore_sql(invalid)

    def test_original_restore_asset_conflict_is_not_overwritten(self):
        source=Path(self.tmp.name)/'source';source.mkdir();(source/'key').write_bytes(b'original fixture')
        target=Path(self.tmp.name)/'target';target.mkdir();(target/'key').write_bytes(b'conflicting fixture')
        with self.assertRaises(RuntimeError):self.m._copy(source,target)
        self.assertEqual((target/'key').read_bytes(),b'conflicting fixture')

    def test_empty_material_store_survives_checkpoint_copy_and_restore(self):
        source=Path(self.tmp.name)/'materials';source.mkdir()
        target=Path(self.tmp.name)/'backup/materials'
        self.m._copy(source,target)
        self.assertTrue(target.is_dir(),'Empty material directory must be preserved for the registered API mount')
        restored=Path(self.tmp.name)/'restored/materials';self.m._copy(target,restored)
        self.assertTrue(restored.is_dir())

    def test_database_acl_from_original_archive_is_bounded_to_that_database(self):
        sql='CREATE DATABASE law_contract_runtime;\nREVOKE CONNECT,TEMPORARY ON DATABASE law_contract_runtime FROM PUBLIC;\nGRANT CONNECT ON DATABASE law_contract_runtime TO law_api_login;\n'
        actual=self.m.database_acl_sql(sql,'law_contract_runtime')
        self.assertNotIn('CREATE DATABASE',actual)
        self.assertIn('REVOKE CONNECT,TEMPORARY',actual)
        self.assertIn('GRANT CONNECT',actual)
        for bad in [sql.replace('TO law_api_login','TO law_api_login; DROP TABLE identity.appointment'),sql.replace('ON DATABASE law_contract_runtime','ON DATABASE other_database')]:
            with self.assertRaises(RuntimeError):self.m.database_acl_sql(bad,'law_contract_runtime')

    def test_cluster_fact_verification_includes_database_acl(self):
        expected={key:{'databaseOwner':'postgres','rolesDigest':'a'*64,'membersDigest':'b'*64,'databaseAclDigest':'c'*64} for key in ['businessDb','identityDb']}
        self.m.assert_cluster_facts(expected,expected)
        changed=json.loads(json.dumps(expected));changed['businessDb']['databaseAclDigest']='d'*64
        with self.assertRaises(RuntimeError):self.m.assert_cluster_facts(expected,changed)

    def test_fact_digest_batches_queries_without_merging_tables(self):
        from ols_linux.config import digest
        rows=[{'name':'identity.appointment','rows':[{'id':'one','state':'ACTIVE'}]},
              {'name':'audit.audit_entry','rows':[{'id':'two','summary':{'a':[1,2]}}]}]
        def query(root,statement,**kwargs):
            if 'pg_tables' in statement:return json.dumps([item['name'] for item in rows])
            return json.dumps(rows)
        with patch('ols_linux.database.sql',side_effect=query) as sql:
            actual=self.m.table_facts(self.root)
        self.assertEqual(actual,{item['name']:{'count':1,'digest':digest(item['rows'])} for item in rows})
        self.assertEqual(sql.call_count,2)

    @unittest.skipIf(os.name=='nt','POSIX ancestor symlink check runs in Linux verification')
    def test_checkpoint_parent_link_cannot_write_backups_outside_instance(self):
        outside=Path(self.tmp.name)/'outside';outside.mkdir()
        (self.root/'checkpoints').symlink_to(outside,target_is_directory=True)
        with self.assertRaises(RuntimeError):self.m._directory(self.root,self.op['operationId'])
        self.assertEqual(list(outside.iterdir()),[])


if __name__=='__main__':unittest.main()
