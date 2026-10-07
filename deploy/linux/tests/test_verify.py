import json
import base64
import hashlib
import hmac
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from ols_linux import database,bundle,verify


class VerificationTests(unittest.TestCase):
    def test_service_subject_uuid_scope_and_validity_are_exact(self):
        uid=lambda n:'00000000-0000-4000-8000-'+str(n).zfill(12)
        from ols_linux.assembly import SERVICE_CODES
        plan={'tenantId':uid(1)};state={'organizations':{'ROOT':uid(2)},'appointments':{'dingqiming_bootstrap':uid(3)},'steps':{'role:'+code:{'createdId':uid(i)} for i,code in enumerate(('DIRECTOR','FINANCE_SUPERVISOR','CASE_SUPERVISOR'),5)}}
        service={'tenantId':uid(1),'principalId':uid(10),'appointmentId':uid(11),'roleId':uid(12),'grants':{code:uid(i) for i,code in enumerate(SERVICE_CODES,20)}}
        defaults=['IDENTITY_ADMIN','INTAKE_OPERATOR','ROUTING_SUPERVISOR','CONTACT_OPERATOR','SALES_REPRESENTATIVE','SALES_MANAGER','FINANCE_OPERATOR','CASE_ADMINISTRATOR']
        roles=[{'tenant':uid(1),'id':uid(i),'code':code,'state':'ACTIVE'} for i,code in enumerate(defaults,40)]+[{'tenant':uid(1),'id':state['steps']['role:'+code]['createdId'],'code':code,'state':'ACTIVE'} for code in ('DIRECTOR','FINANCE_SUPERVISOR','CASE_SUPERVISOR')]+[{'tenant':uid(1),'id':uid(12),'code':'SERVICE','state':'ACTIVE'}]
        subject=hmac.new(b'a'*32,b'linux-infrastructure',hashlib.sha256).hexdigest()
        principals=[{'tenant':uid(1),'id':uid(10),'provider':'LINUX_SERVICE','state':'ACTIVE','subject':subject}]
        appointments=[{'tenant':uid(1),'id':uid(11),'principal':uid(10),'organization':uid(2),'role':'SERVICE','state':'ACTIVE','valid':True}]
        grants=[{'tenant':uid(1),'id':id,'appointment':uid(11),'granted_by':uid(3),'scope':uid(2),'authority':code,'state':'ACTIVE','valid':True} for code,id in service['grants'].items()]
        with patch.object(verify.journal,'_read',return_value=service),patch.object(verify.identity,'secret_file',return_value=base64.b64encode(b'a'*32).decode()):
            with patch.object(verify,'_rows',side_effect=[roles,principals,appointments,grants]):verify.technical(Path('/private'),plan,state)
            for foreign in [[dict(principals[0],subject='b'*64)],[dict(principals[0],id=uid(99))]]:
                with patch.object(verify,'_rows',side_effect=[roles,foreign,appointments,grants]):
                    with self.assertRaises(RuntimeError):verify.technical(Path('/private'),plan,state)
            for field,new in [('id',uid(99)),('scope',uid(99)),('valid',False)]:
                wrong=[dict(grants[0],**{field:new})]+grants[1:]
                with patch.object(verify,'_rows',side_effect=[roles,principals,appointments,wrong]):
                    with self.assertRaises(RuntimeError):verify.technical(Path('/private'),plan,state)

    def test_exact_tenant_verification_rejects_foreign_or_inactive_inventory(self):
        expected={'id':'00000000-0000-4000-8000-000000000001','code':'HAIHUA','name':'海华律师事务所总所','state':'ACTIVE'}
        with patch.object(verify,'_rows',return_value=[expected]):verify.tenant(Path('/private'),expected['id'],expected['name'])
        for rows in [[dict(expected,state='SUSPENDED')],[dict(expected,id='00000000-0000-4000-8000-000000000002')],[expected,dict(expected,id='00000000-0000-4000-8000-000000000002')]]:
            with patch.object(verify,'_rows',return_value=rows):
                with self.assertRaises(RuntimeError):verify.tenant(Path('/private'),expected['id'],expected['name'])

    def test_readonly_schema_verification_never_attempts_ddl(self):
        repo=Path(__file__).resolve().parents[3]
        versions=database.expected_versions(repo)
        manifest=json.loads((repo/bundle.GENERATED/'schema-contract-manifest.json').read_text())
        observed={'history':[{'version':v,'success':True} for v in versions], 'gate':{'schema_contract_version':'52-plus-2-r2-v22'},'tables':list(range(manifest['physicalTableCountAfterFlywayBootstrap']))}
        queries=[]
        def sql(root,statement,**kwargs):
            queries.append(statement)
            self.assertTrue(statement.startswith('SELECT '),'Read-only verification must never execute a mutating/DDL statement')
            return '1'
        with patch.object(database.runtime,'load',return_value={'repo':str(repo)}),patch.object(database,'observe',return_value=observed),patch.object(database,'verify_history'),patch.object(database,'sql',side_effect=sql):
            database.verify_schema(Path('/private'),assert_role_boundaries=False)
        self.assertEqual(len(queries),2)
