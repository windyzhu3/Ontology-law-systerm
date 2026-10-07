import importlib
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))


class AssemblyTests(unittest.TestCase):
    def module(self):
        try:return importlib.import_module('ols_linux.assembly')
        except ImportError:self.fail('Native bootstrap and API assembly missing')

    def test_technical_service_insert_is_bounded_and_never_human(self):
        value={'tenantId':'00000000-0000-4000-8000-000000000001','principalId':'00000000-0000-4000-8000-000000000002','appointmentId':'00000000-0000-4000-8000-000000000003','roleId':'00000000-0000-4000-8000-000000000020','grants':{code:'00000000-0000-4000-8000-'+str(i).zfill(12) for i,code in enumerate(self.module().SERVICE_CODES,4)}}
        statement=self.module().service_statement(value,'a'*64)
        self.assertNotIn("'HUMAN'",statement.split('INSERT INTO identity.authority_grant')[0])
        self.assertNotIn('IDENTITY_AUTHORITY_MANAGE',statement)
        self.assertNotIn('LEAD_ASSIGN',statement)
        self.assertIn("'SERVICE'",statement)
        self.assertIn("principal_kind='HUMAN'",statement)
        self.assertIn('INSERT INTO identity.appointment_role',statement)

    def test_invalid_service_uuid_is_rejected_before_sql(self):
        with self.assertRaises((ValueError,RuntimeError)):
            self.module().service_statement({'tenantId':"';DELETE FROM identity.principal;--",'principalId':'bad','appointmentId':'bad','grants':{}},'a'*64)

    def test_readiness_uses_existing_authenticated_service_contract(self):
        module=self.module()
        with patch.object(module.identity,'http',return_value={'status':204,'body':''}) as read:
            self.assertTrue(module.service_ready(Path('/private'),{'apiOrigin':'https://localhost:24845'}))
            self.assertEqual(read.call_args.args[1],'https://localhost:24845/internal/v1/projections/r1/readiness')
            self.assertTrue(read.call_args.kwargs['client_certificate'])

    def test_same_original_partial_migration_prefix_can_continue_but_drift_cannot(self):
        from ols_linux import database
        module=self.module();repo=Path(__file__).resolve().parents[3]
        rows=[{'version':None,'type':'SCHEMA','success':True}]
        for version,value in list(database.migration_checksums(repo).items())[:5]:rows.append(dict(value,version=version,type='SQL',success=True))
        self.assertFalse(module.migration_complete(repo,rows))
        with self.assertRaises(RuntimeError):module.migration_complete(repo,rows+[dict(rows[2],version='9999')])
        rows[1]['checksum']+=1
        with self.assertRaises(RuntimeError):module.migration_complete(repo,rows)
