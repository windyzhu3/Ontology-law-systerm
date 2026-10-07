import importlib
from pathlib import Path
import sys
import unittest
import tempfile
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))


class DeploymentTests(unittest.TestCase):
    def module(self):
        try:return importlib.import_module('ols_linux.deployment')
        except ImportError:self.fail('Final Linux runtime assembly missing')

    def inputs(self):
        uid=lambda n:'00000000-0000-4000-8000-'+str(n).zfill(12)
        return (Path('/private'),{'schemaVersion':'52-plus-2-r2-v22','jar':'application.jar','files':{'application.jar':'a'*64},'manifestHash':'b'*64},
            {'tenantId':uid(1),'apiOrigin':'https://localhost:24845'},
            {'tenantId':uid(1),'principalId':uid(2),'appointmentId':uid(3)},
            {'ols.api.database.url':'jdbc:postgresql://own-db:5432/law_contract_runtime?sslmode=verify-full&sslrootcert=/private/certs/ca.pem'},'private-worker-password','private-trust-password')

    def test_worker_uses_original_service_and_exact_new_release_without_http_or_api_password(self):
        root,descriptor,plan,service,api,password,trust=self.inputs()
        props=self.module().worker_properties(root,descriptor,plan,service,api,password,trust)
        self.assertEqual(props['ols.runtime-role'],'worker')
        self.assertEqual(props['spring.main.web-application-type'],'none')
        self.assertEqual(props['ols.worker.database.username'],'law_worker_login')
        self.assertEqual(props['ols.worker.database.password'],password)
        self.assertEqual(props['ols.worker.database.release-digest'],'a'*64)
        self.assertEqual(props['ols.worker.database.manifest-hash'],'b'*64)
        self.assertEqual(props['ols.worker.bindings[0].appointment-id'],service['appointmentId'])
        self.assertEqual(props['ols.worker.bindings[0].credential-alias'],'ols_service')
        self.assertTrue(props['ols.worker.opportunity-task-scheduling-enabled'])
        self.assertFalse(any(k.startswith(('server.','ols.api.')) for k in props))

    def test_worker_refuses_foreign_service_tenant_and_non_tls_database_before_launch(self):
        inputs=list(self.inputs());inputs[3]=dict(inputs[3],tenantId='00000000-0000-4000-8000-000000000099')
        with self.assertRaises((ValueError,RuntimeError)):self.module().worker_properties(*inputs)
        inputs=list(self.inputs());inputs[4]={'ols.api.database.url':'jdbc:postgresql://own-db/law_contract_runtime?sslmode=disable'}
        with self.assertRaises((ValueError,RuntimeError)):self.module().worker_properties(*inputs)

    def test_worker_certificate_binding_requires_actual_fingerprint_not_fabricated_default(self):
        inputs=self.inputs()
        props=self.module().worker_properties(*inputs)
        self.assertNotIn('ols.worker.bindings[0].certificate-sha256',props)
        # bind_release must add the fingerprint of the registered original certificate.
        self.assertEqual(props['ols.worker.bindings[0].key-store-path'],str(inputs[0]/'certs/service.p12'))

    def test_api_rebinding_changes_only_release_metadata_and_keeps_original_authority_configuration(self):
        base={'ols.api.database.schema-version':'52-plus-2-r2-v20','ols.api.database.release-digest':'c'*64,
            'ols.api.database.manifest-hash':'d'*64,'ols.api.human-intake-bindings[0].principal-id':'original-principal',
            'ols.api.responsibility-routes[0].appointment-id':'original-appointment','ols.api.database.password':'original-secret'}
        new=self.module().api_properties(base,self.inputs()[1])
        self.assertEqual(new['ols.api.database.schema-version'],'52-plus-2-r2-v22')
        self.assertEqual(new['ols.api.database.release-digest'],'a'*64)
        self.assertEqual(new['ols.api.database.manifest-hash'],'b'*64)
        self.assertEqual({k:v for k,v in new.items() if not k.endswith(('schema-version','release-digest','manifest-hash'))},
            {k:v for k,v in base.items() if not k.endswith(('schema-version','release-digest','manifest-hash'))})
        self.assertEqual(base['ols.api.database.release-digest'],'c'*64)

    def test_final_binding_refuses_management_only_bundle_before_any_runtime_effect(self):
        from ols_linux import initialize,journal,runtime
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/'private';journal.begin(root,'initialize','a'*64)
            descriptor={'version':1,'descriptorDigest':'b'*64}
            with patch.object(runtime,'run',side_effect=AssertionError('No runtime effects')),patch.object(runtime,'save',side_effect=AssertionError('No resource mutation')):
                with self.assertRaises(RuntimeError):initialize.bind_release(root,descriptor)

    def test_ingress_create_keeps_name_labels_and_command_but_cannot_start_listener(self):
        args=['docker','run','-d','--name','own-entry','--label','ols.instance=owned','--entrypoint','node','sha256:'+'a'*64,'/private/server.mjs','/private/config.json']
        converted=self.module().create_args(args)
        self.assertEqual(converted,['docker','create',*args[3:]])
        self.assertNotIn('-d',converted)
        self.assertEqual(args[1],'run')

    def test_worker_readiness_uses_last_current_boot_status_and_requires_role_isolation(self):
        ready='R1_WORKER_ASSEMBLY_ISOLATED\nR1_WORKER_READY\n'
        self.assertTrue(self.module().worker_ready(ready))
        self.assertFalse(self.module().worker_ready(ready+'R1_WORKER_UNAVAILABLE\n'))
        self.assertFalse(self.module().worker_ready('R1_WORKER_READY\n'))
        self.assertFalse(self.module().worker_ready(''))

    def test_changed_binding_never_reuses_prior_scanner_launch_name(self):
        m=self.module()
        old=m.final_names('ols-private','a'*64);new=m.final_names('ols-private','b'*64)
        self.assertNotEqual(old['scanner'],new['scanner'])
        self.assertEqual(old,m.final_names('ols-private','a'*64))
