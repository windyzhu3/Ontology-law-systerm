"""Worker assembly tests use temporary material and synthetic external boundaries only."""
import copy
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
import io
from types import SimpleNamespace
from unittest.mock import patch
import subprocess

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
MODULE = Path(__file__).resolve().parents[1] / 'local_worker.py'
T, P, A, R, F, FA = [str(__import__('uuid').UUID(int=n)) for n in range(1, 7)]
AT = '2026-09-09T01:00:00+00:00'


def fixture():
    common = {'tenant_id': T, 'state': 'ACTIVE', 'revision': 0}
    return {'tenant': {**common, 'tenant_code': 'LOCAL_R1'},
        'principal': {**common, 'principal_id': P, 'principal_kind': 'SERVICE', 'identity_provider_code': 'LOCAL_SERVICE'},
        'appointment': {**common, 'appointment_id': A, 'principal_id': P, 'organization_unit_id': R,
                        'role_code': 'SERVICE', 'effective_from': AT, 'effective_until': None, 'ended_at': None},
        'root': {**common, 'organization_unit_id': R, 'unit_code': 'ROOT', 'parent_organization_unit_id': None},
        'founder': {**common, 'principal_id': F, 'principal_kind': 'HUMAN', 'identity_provider_code': 'LOCAL_R1'},
        'founderAppointment': {**common, 'appointment_id': FA, 'principal_id': F, 'organization_unit_id': R,
                               'role_code': 'IDENTITY_ADMIN', 'effective_from': AT, 'effective_until': None},
        'bootstrap': {'appointmentId': FA, 'founderPrincipalId': F, 'rootOrganizationId': R},
        'grants': []}


class WorkerTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(MODULE.exists(), 'local Worker assembly is missing')
        spec = importlib.util.spec_from_file_location('local_worker', MODULE)
        self.m = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.m)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.runtime = Path(self.tmp.name)
        self.identity = {'tenantId': T, 'principalId': P, 'appointmentId': A}
        self.now = datetime(2026, 9, 10, tzinfo=timezone.utc)

    def test_original_hyphen_alias_is_rejected_as_binding(self):
        with self.assertRaises(RuntimeError): self.m.validate_alias('local-service')
        self.m.validate_alias('local_service')

    def test_grant_plan_is_exact_three_with_fixed_original_founder_and_root(self):
        plan = self.m.grant_plan(self.identity, fixture(), self.now)
        self.assertEqual([g['authority_code'] for g in plan['grants']],
            ['R1_PROJECTION_CONSUME', 'CONTACT_TASK_RECOVER', 'ROUTING_REVIEW_TASK_RECOVER'])
        for row in plan['grants']:
            self.assertEqual(row['grantee_appointment_id'], A)
            self.assertEqual(row['granted_by_appointment_id'], FA)
            self.assertEqual(row['scope_organization_unit_id'], R)
        self.assertEqual(self.m.grant_delta(plan, fixture()), 3)
        complete = fixture(); complete['grants'] = copy.deepcopy(plan['grants'])
        self.assertEqual(self.m.grant_delta(plan, complete), 0)

    def test_grants_reject_partial_extra_changed_and_changed_original_facts(self):
        plan = self.m.grant_plan(self.identity, fixture(), self.now)
        for fault in ('partial', 'extra', 'changed', 'original', 'foreign'):
            current = fixture(); current['grants'] = copy.deepcopy(plan['grants'])
            if fault == 'partial': current['grants'].pop()
            if fault == 'extra': current['grants'].append({**current['grants'][0], 'authority_code': 'IDENTITY_ADMIN'})
            if fault == 'changed': current['grants'][0]['revision'] = 1
            if fault == 'original': current['principal']['revision'] = 1
            if fault == 'foreign': current['grants'][0]['grantee_appointment_id'] = FA
            with self.subTest(fault=fault), self.assertRaises(RuntimeError): self.m.grant_delta(plan, current)

    def test_plan_rejects_wrong_service_root_founder_and_any_existing_grant(self):
        for section, field, value in [('principal', 'principal_kind', 'HUMAN'), ('tenant', 'state', 'CLOSED'),
            ('root', 'parent_organization_unit_id', F), ('appointment', 'role_code', 'IDENTITY_ADMIN'),
            ('appointment', 'effective_until', AT), ('bootstrap', 'appointmentId', A),
            ('founderAppointment', 'state', 'SUSPENDED')]:
            current = fixture(); current[section][field] = value
            with self.subTest(section=section, field=field), self.assertRaises(RuntimeError):
                self.m.grant_plan(self.identity, current, self.now)
        current = fixture(); current['grants'] = [{'authority_code': 'CONTACT_TASK_RECOVER'}]
        with self.assertRaises(RuntimeError): self.m.grant_plan(self.identity, current, self.now)

    def test_grant_plan_mutation_cannot_authorize_human_or_more_capabilities(self):
        plan = self.m.grant_plan(self.identity, fixture(), self.now)
        for key, value in [('grantee_appointment_id', FA), ('authority_code', 'IDENTITY_ADMIN'),
                           ('granted_by_appointment_id', A), ('scope_organization_unit_id', F), ('revision', 1)]:
            altered = copy.deepcopy(plan); altered['grants'][0][key] = value
            with self.subTest(key=key), self.assertRaises(RuntimeError): self.m.grant_delta(altered, fixture())

    def test_config_uses_only_worker_credentials_current_release_and_exact_role(self):
        package = {'jar': self.runtime / 'releases' / ('a'*32) / 'app.jar', 'release': 'b'*64, 'manifest': 'c'*64}
        data = self.m.worker_properties(self.runtime, package, self.identity, 'd'*64, 'db-secret', 'tls-secret')
        self.assertIn('ols.runtime-role=worker\n', data)
        self.assertIn('ols.worker.database.username=law_worker_login\n', data)
        self.assertIn('ols.worker.api-origin=https://localhost:19445\n', data)
        self.assertIn('ols.worker.bindings[0].credential-alias=local_service\n', data)
        self.assertIn('sslmode=verify-full', data)
        self.assertIn('release-digest=' + 'b'*64, data)
        for forbidden in ('ols.api.', 'offline', 'directory', 'introspection', 'migrator', 'APP_ROLE'):
            self.assertNotIn(forbidden, data)
        for field in ('release', 'manifest'):
            bad = dict(package); bad[field] = ''
            with self.assertRaises(RuntimeError): self.m.worker_properties(self.runtime, bad, self.identity, 'd'*64, 'db', 'tls')

    def test_log_ready_requires_exact_logger_fresh_isolation_latest_state(self):
        prefix = '2026-09-10T00:00:01.000Z INFO 123 --- [main] io.github.windyzhu3.ontologylaw.worker.WorkerRuntimeHealth : '
        isolated = prefix + 'R1_WORKER_ASSEMBLY_ISOLATED\n'
        ready = prefix + 'R1_WORKER_READY\n'
        self.assertTrue(self.m.log_ready(isolated + ready, self.now, 123))
        for log in (ready, isolated, isolated + ready + prefix + 'R1_WORKER_UNAVAILABLE\n',
                    (isolated + ready).replace('2026-09-10', '2026-09-09'),
                    (isolated + ready).replace('INFO 123', 'INFO 321'),
                    (isolated + ready).replace('worker.WorkerRuntimeHealth', 'worker.Other')):
            with self.subTest(log=log): self.assertFalse(self.m.log_ready(log, self.now, 123))

    def test_grant_transaction_locks_original_facts_and_rejects_uncertain_ack(self):
        self.assertTrue(hasattr(self.m, 'WorkerBoundary'), 'Worker external adapter missing')
        runner = SimpleNamespace(RUNTIME=self.runtime)
        boundary = self.m.WorkerBoundary(runner, SimpleNamespace())
        plan = self.m.grant_plan(self.identity, fixture(), self.now)
        requests = []
        boundary.release_boundary.sql = lambda sql: requests.append(sql) or 'LOCAL_WORKER_GRANTS_3'
        self.assertEqual(boundary.apply_grants(plan, fixture(), 'SELECT NULL::jsonb'), 3)
        self.assertIn('LOCK TABLE identity.tenant', requests[0])
        self.assertIn('IS DISTINCT FROM', requests[0])
        self.assertIn('INSERT INTO identity.authority_grant', requests[0])
        self.assertNotIn('ON CONFLICT', requests[0])
        self.assertNotIn('INSERT INTO execution.', requests[0])
        boundary.release_boundary.sql = lambda sql: '0'
        with self.assertRaises(RuntimeError): boundary.apply_grants(plan, fixture(), 'SELECT NULL::jsonb')

    def test_registered_worker_command_uses_jar_only_and_no_web_or_api_config(self):
        self.assertTrue(hasattr(self.m, 'worker_command'), 'Worker command missing')
        runner = SimpleNamespace(JAVA=self.runtime/'java.exe', RUNTIME=self.runtime)
        command = self.m.worker_command(runner, self.runtime/'releases'/('a'*32))
        self.assertIn('-Xmx384m', command)
        self.assertIn('--ols.runtime-role=worker', command)
        self.assertIn('--spring.main.web-application-type=none', command)
        self.assertIn('--spring.config.location=' + (self.runtime/'worker/application.properties').as_uri(), command)
        self.assertNotIn('19445', ' '.join(command))

    def assembly(self):
        self.assertTrue(hasattr(self.m, 'LocalWorker'), 'local Worker orchestration missing')
        (self.runtime/'worker').mkdir(exist_ok=True)
        (self.runtime/'service-fixture.json').write_text(json.dumps(self.identity))
        (self.runtime/'original-manifest.json').write_text(json.dumps({'commandId':str(__import__('uuid').UUID(int=7))}))
        (self.runtime/'secrets').mkdir(exist_ok=True)
        (self.runtime/'secrets/worker-db.txt').write_text('existing-worker-secret')
        (self.runtime/'secrets/trust-password.txt').write_text('existing-tls-secret')
        (self.runtime/'processes.json').write_text(json.dumps({'api':1,'spa':2}))
        package = {'jar':self.runtime/'releases'/('a'*32)/'app.jar','release':'b'*64,'manifest':'c'*64}
        runner = SimpleNamespace(RUNTIME=self.runtime, ROOT=self.runtime, JAVA=self.runtime/'java.exe')
        state = {'facts':fixture(), 'db':True, 'verified':0, 'writes':0, 'process':None}
        release = SimpleNamespace(paths=lambda:package)
        class Boundary:
            release_boundary = SimpleNamespace(protect=lambda:None, processes=lambda:[], process=lambda pid:state['process'])
            def verify_original(self): state['verified'] += 1
            def facts(self, identity, command): return copy.deepcopy(state['facts'])
            def database(self, paths):
                if not state['db']: raise RuntimeError('Worker database unavailable')
            def certificate(self): return 'd'*64
            def prepare_certificate(self): return 'd'*64
            def apply_grants(self, plan, current, query):
                delta = self_outer.m.grant_delta(plan,current)
                state['writes'] += delta; state['facts']['grants'] = copy.deepcopy(plan['grants'])
                return delta
        self_outer = self
        boundary = Boundary()
        return self.m.LocalWorker(runner, release, boundary), state, package

    def test_operator_grants_save_original_before_write_retry_zero_and_never_adopt_existing(self):
        worker,state,package = self.assembly()
        self.assertEqual(worker.grant(), 3)
        self.assertTrue((self.runtime/'worker/grants.json').is_file())
        self.assertEqual(worker.grant(), 0)
        self.assertEqual(state['writes'], 3)
        self.assertEqual(state['verified'], 2)
        state['facts']['grants'][0]['valid_until'] = AT
        with self.assertRaises(RuntimeError): worker.grant()
        self.assertEqual(state['writes'], 3)

    def test_operator_interruption_keeps_plan_and_retry_checks_committed_rows(self):
        worker,state,package = self.assembly()
        apply = worker.boundary.apply_grants
        def uncertain(*args):
            apply(*args)
            raise RuntimeError('lost committed response')
        worker.boundary.apply_grants = uncertain
        with self.assertRaises(RuntimeError): worker.grant()
        self.assertTrue((self.runtime/'worker/grants.json').exists())
        worker.boundary.apply_grants = apply
        self.assertEqual(worker.grant(), 0)
        self.assertEqual(state['writes'], 3)

    def test_prepare_requires_grants_current_database_and_never_changes_existing_config_on_failure(self):
        worker,state,package = self.assembly()
        with self.assertRaises((RuntimeError,FileNotFoundError)): worker.prepare()
        worker.grant(); worker.prepare()
        config = self.runtime/'worker/application.properties'
        before = config.read_bytes()
        self.assertNotIn(b'api-db',before)
        state['db'] = False
        with self.assertRaises(RuntimeError): worker.prepare()
        self.assertEqual(config.read_bytes(),before)
        state['db'] = True
        config.write_bytes(before + b'ols.worker.api-origin=http://evil\n')
        with self.assertRaises(RuntimeError): worker.validate()

    def test_release_change_requires_explicit_prepare_and_retains_old_config(self):
        worker,state,package = self.assembly(); worker.grant(); worker.prepare()
        old = (self.runtime/'worker/application.properties').read_bytes()
        package['release'] = 'e'*64; package['manifest'] = 'f'*64
        package['jar'] = self.runtime/'releases'/('b'*32)/'app.jar'
        with self.assertRaises(RuntimeError):worker.validate()
        worker.prepare()
        self.assertEqual(worker.validate()['release'],'e'*64)
        self.assertIn(old,[p.read_bytes() for p in (self.runtime/'worker').glob('config-*.properties')])

    def test_start_records_exact_hidden_process_and_uncertain_launch_marker_blocks_retry(self):
        worker,state,package = self.assembly()
        worker.grant(); worker.prepare()
        command = self.m.worker_command(worker.runner, package['jar'].parent)
        state['process'] = {'pid':321,'executable':command[0],'args':command[1:],'created':AT}
        worker.boundary.unregistered = lambda:None
        with patch.object(self.m.subprocess,'Popen', return_value=SimpleNamespace(pid=321)) as launch:
            worker.start()
        registry = json.loads((self.runtime/'processes.json').read_text())
        self.assertEqual(registry['worker']['created'],AT)
        self.assertEqual(launch.call_args.kwargs['creationflags'],subprocess.CREATE_NO_WINDOW)
        self.assertNotIn('OLS_RUNTIME_ROLE',launch.call_args.kwargs['env'])
        self.assertFalse((self.runtime/'worker-start.pending').exists())
        with self.assertRaises(RuntimeError): worker.start()
        state['process'] = None
        with patch.object(self.m.subprocess,'Popen', side_effect=OSError('uncertain')):
            with self.assertRaises(OSError): worker.start()
        self.assertTrue((self.runtime/'worker-start.pending').exists())
        with self.assertRaises(RuntimeError): worker.start()

    def test_restart_preserves_previous_process_logs_for_interruption_review(self):
        worker,state,package = self.assembly(); worker.grant(); worker.prepare()
        (self.runtime/'worker.stdout').write_bytes(b'previous protected health evidence')
        (self.runtime/'worker.stderr').write_bytes(b'previous protected errors')
        worker.boundary.unregistered = lambda:None
        with patch.object(self.m.subprocess,'Popen',side_effect=OSError('failed next launch')):
            with self.assertRaises(OSError):worker.start()
        retained = [p.read_bytes() for p in (self.runtime/'worker').glob('previous-*')]
        self.assertIn(b'previous protected health evidence',retained)
        self.assertIn(b'previous protected errors',retained)

    def test_prepare_cannot_recreate_a_missing_registered_certificate_copy_during_health(self):
        self.assertTrue(hasattr(self.m,'verify_certificate'), 'read-only certificate validation missing')
        runner = SimpleNamespace(RUNTIME=self.runtime)
        with self.assertRaises((RuntimeError,FileNotFoundError)):self.m.verify_certificate(runner)
        self.assertFalse((self.runtime/'worker').exists())

    def test_health_requires_all_current_external_gates_and_latest_worker_state(self):
        worker,state,package = self.assembly(); worker.grant(); worker.prepare()
        command = self.m.worker_command(worker.runner,package['jar'].parent)
        process = {'pid':321,'executable':command[0],'args':command[1:],'created':AT,'startedAt':'2026-09-09T00:59:59+00:00'}
        (self.runtime/'processes.json').write_text(json.dumps({'api':1,'spa':2,'worker':process}))
        state['process'] = process
        prefix = '2026-09-09T01:00:01Z INFO 321 --- [main] io.github.windyzhu3.ontologylaw.worker.WorkerRuntimeHealth : '
        logs = prefix+'R1_WORKER_ASSEMBLY_ISOLATED\n'+prefix+'R1_WORKER_READY\n'
        (self.runtime/'worker.stdout').write_text(logs)
        worker.boundary.mtls = lambda: None
        worker.boundary.listeners = lambda pid:0
        self.assertEqual(worker.health()['requiredLoops'],3)
        for fault in ('database','mtls','listener','latest'):
            state['db'] = fault != 'database'
            worker.boundary.listeners = lambda pid:1 if fault=='listener' else 0
            def mtls():
                if fault=='mtls': raise RuntimeError('no mTLS')
            worker.boundary.mtls = mtls
            (self.runtime/'worker.stdout').write_text(logs + (prefix+'R1_WORKER_UNAVAILABLE\n' if fault=='latest' else ''))
            with self.subTest(fault=fault), self.assertRaises(RuntimeError):worker.health()

    def test_health_cannot_accept_ready_from_before_exact_process_creation(self):
        worker,state,package = self.assembly(); worker.grant(); worker.prepare()
        command = self.m.worker_command(worker.runner,package['jar'].parent)
        process = {'pid':321,'executable':command[0],'args':command[1:], 'created':'2026-09-10T00:00:00+00:00', 'startedAt':'2026-09-09T00:59:59+00:00'}
        state['process'] = process
        (self.runtime/'processes.json').write_text(json.dumps({'api':1,'spa':2,'worker':process}))
        prefix = '2026-09-09T01:00:01Z INFO 321 --- [main] io.github.windyzhu3.ontologylaw.worker.WorkerRuntimeHealth : '
        (self.runtime/'worker.stdout').write_text(prefix+'R1_WORKER_ASSEMBLY_ISOLATED\n'+prefix+'R1_WORKER_READY\n')
        worker.boundary.mtls = lambda:None; worker.boundary.listeners = lambda pid:0
        with self.assertRaises(RuntimeError):worker.health()

    def test_refusal_scan_excludes_itself_but_detects_unregistered_child_config(self):
        boundary = self.m.WorkerBoundary(SimpleNamespace(RUNTIME=self.runtime),SimpleNamespace())
        try: boundary.unregistered()
        except RuntimeError: self.fail('refusal scan incorrectly counts its own inspection process')
        child = subprocess.Popen([sys.executable,'-c','import time; time.sleep(90)',
            (self.runtime/'worker/application.properties').as_uri()],stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,creationflags=subprocess.CREATE_NO_WINDOW)
        try:
            with self.assertRaises(RuntimeError): boundary.unregistered()
        finally:
            child.kill(); child.wait(timeout=5)

    def test_runner_dispatches_only_named_worker_infrastructure_operations(self):
        import local_login
        self.assertTrue(hasattr(local_login,'worker_operation'), 'Worker command dispatch missing')
        worker,state,package = self.assembly()
        import local_release
        with patch.object(local_login,'RUNTIME',self.runtime), patch.object(local_login,'ROOT',self.runtime),\
            patch.object(local_release,'RuntimeBoundary',return_value=worker.boundary.release_boundary),\
            patch.object(local_release,'LocalRelease',return_value=worker.release),\
            patch.dict(sys.modules,{'local_worker':self.m}),\
            patch.object(self.m,'WorkerBoundary',return_value=worker.boundary), redirect_stdout(io.StringIO()):
            local_login.worker_operation('worker-grant')
            local_login.worker_operation('worker-prepare')
            self.assertTrue((self.runtime/'worker/application.properties').exists())
            with self.assertRaises(RuntimeError): local_login.worker_operation('worker-grant-human')

    def test_database_adapter_uses_only_worker_secret_and_rejects_gate_or_sql_failure(self):
        (self.runtime/'deploy/identity').mkdir(parents=True)
        (self.runtime/'deploy/identity/identity-toolchain.lock.json').write_text(json.dumps({'identityDatabase':{'image':'synthetic-postgres','digest':'sha256:'+'a'*64}}))
        runner = SimpleNamespace(ROOT=self.runtime,RUNTIME=self.runtime,PREFIX='synthetic-local')
        boundary = self.m.WorkerBoundary(runner,SimpleNamespace())
        paths = {'release':'b'*64,'manifest':'c'*64}
        gate = {'operating_mode':'ACTIVE','schema_contract_version':'52-plus-2-v1.2','active_release_digest':'b'*64,'active_manifest_hash':'c'*64}
        calls = []
        def execute(args,**kwargs):
            calls.append((args,kwargs))
            return SimpleNamespace(returncode=0,stdout=json.dumps(gate).encode())
        with patch.object(self.m.subprocess,'run',side_effect=execute):
            boundary.database(paths)
            gate['active_release_digest'] = 'd'*64
            with self.assertRaises(RuntimeError):boundary.database(paths)
        args,kwargs = calls[0]
        self.assertIn('secrets\\worker-db.txt', ' '.join(args))
        self.assertNotIn('migrator', ' '.join(args)); self.assertNotIn('api-db',' '.join(args))
        self.assertIn('user=law_worker_login sslmode=verify-full',args[-1])
        self.assertIn(b'SET LOCAL ROLE law_app_worker',kwargs['input'])
        with patch.object(self.m.subprocess,'run',return_value=SimpleNamespace(returncode=1,stdout=b'')):
            with self.assertRaises(RuntimeError):boundary.database(paths)

    def test_certificate_copy_preserves_original_key_and_der_and_rejects_drift(self):
        self.assertTrue(hasattr(self.m, 'prepare_certificate'), 'certificate adapter missing')
        keytool = Path('C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/jdk-25.0.4.1+1/bin/keytool.exe')
        java = keytool.with_name('java.exe')
        if not keytool.exists(): self.skipTest('pinned JDK unavailable')
        (self.runtime/'secrets').mkdir(); (self.runtime/'certs').mkdir(); (self.runtime/'worker').mkdir()
        password = self.runtime/'secrets/trust-password.txt'; password.write_text('synthetic-test-secret')
        def run(args):
            result = subprocess.run([str(x) for x in args], capture_output=True, creationflags=subprocess.CREATE_NO_WINDOW)
            self.assertEqual(result.returncode, 0, 'synthetic keytool fixture failed')
        run([keytool, '-genkeypair', '-alias', 'local-service', '-keyalg', 'RSA', '-keysize', '2048', '-dname', 'CN=localhost',
             '-validity', '2', '-ext', 'EKU=clientAuth,serverAuth', '-ext', 'SAN=dns:localhost', '-keystore', self.runtime/'service.p12',
             '-storetype', 'PKCS12', '-storepass:file', password])
        run([keytool, '-exportcert', '-rfc', '-alias', 'local-service', '-keystore', self.runtime/'service.p12',
             '-storepass:file', password, '-file', self.runtime/'service.crt'])
        (self.runtime/'certs/server.crt').write_bytes((self.runtime/'service.crt').read_bytes())
        for name in ('identity-trust.p12', 'server-client-trust.p12'):
            run([keytool, '-importcert', '-noprompt', '-alias', 'service', '-file', self.runtime/'service.crt',
                 '-keystore', self.runtime/name, '-storetype', 'PKCS12', '-storepass:file', password])
        runner = SimpleNamespace(JAVA=java, KEYTOOL=keytool, RUNTIME=self.runtime)
        original = (self.runtime/'service.p12').read_bytes()
        fingerprint = self.m.prepare_certificate(runner)
        self.assertEqual(len(fingerprint), 64)
        self.assertEqual((self.runtime/'service.p12').read_bytes(), original)
        self.assertEqual(self.m.prepare_certificate(runner), fingerprint)
        copied = (self.runtime/'worker/service.p12').read_bytes()
        original_crt = (self.runtime/'service.crt').read_bytes()
        (self.runtime/'service.crt').write_bytes(b'wrong certificate')
        with self.assertRaises(RuntimeError):self.m.prepare_certificate(runner)
        (self.runtime/'service.crt').write_bytes(original_crt)
        trust = (self.runtime/'worker/trust.p12').read_bytes()
        (self.runtime/'worker/trust.p12').write_bytes(b'wrong trust')
        with self.assertRaises(RuntimeError):self.m.prepare_certificate(runner)
        (self.runtime/'worker/trust.p12').write_bytes(trust)
        (self.runtime/'worker/service.p12').write_bytes(original)
        with self.assertRaises(RuntimeError): self.m.prepare_certificate(runner)
        self.assertEqual((self.runtime/'service.p12').read_bytes(), original)
        (self.runtime/'worker/service.p12').write_bytes(copied)
        # Separate expired identity; no replacement or renewal is attempted by the adapter.
        expired = self.runtime/'expired.p12'
        run([keytool,'-genkeypair','-alias','expired','-keyalg','RSA','-keysize','2048','-dname','CN=expired',
             '-startdate','-10d','-validity','1','-keystore',expired,'-storetype','PKCS12','-storepass:file',password])
        run([keytool,'-exportcert','-rfc','-alias','expired','-keystore',expired,'-storepass:file',password,'-file',self.runtime/'service.crt'])
        with self.assertRaises(RuntimeError):self.m.prepare_certificate(runner)
        self.assertEqual((self.runtime/'service.p12').read_bytes(),original)


if __name__ == '__main__': unittest.main()
