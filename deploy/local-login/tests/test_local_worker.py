"""Worker assembly tests use temporary material and synthetic external boundaries only."""
import copy
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import re
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
T, P, A, R, F, FA, G, COMMAND, SLOT, RECEIPT = [str(__import__('uuid').UUID(int=n)) for n in range(1, 11)]
AT = '2026-09-09T01:00:00+00:00'


def fixture():
    common = {'tenant_id': T, 'state': 'ACTIVE', 'revision': 0}
    return {'tenant': {**common, 'tenant_code': 'LOCAL_R1'},
        'principal': {**common, 'principal_id': P, 'principal_kind': 'SERVICE', 'identity_provider_code': 'LOCAL_SERVICE'},
        'appointment': {**common, 'appointment_id': A, 'principal_id': P, 'organization_unit_id': R,
                        'role_code': 'SERVICE', 'effective_from': AT, 'effective_until': None, 'ended_at': None},
        'root': {**common, 'organization_unit_id': R, 'unit_code': 'ROOT', 'display_name': 'Original Root',
                 'parent_organization_unit_id': None},
        'founder': {**common, 'principal_id': F, 'principal_kind': 'HUMAN', 'identity_provider_code': 'LOCAL_R1'},
        'founderAppointment': {**common, 'appointment_id': FA, 'principal_id': F, 'organization_unit_id': R,
                               'role_code': 'IDENTITY_ADMIN', 'effective_from': AT, 'effective_until': None},
        'bootstrap': {'appointmentId': FA, 'founderPrincipalId': F, 'rootOrganizationId': R},
        'founderGrants': [{'tenant_id': T, 'authority_grant_id': G, 'grantee_appointment_id': FA,
            'scope_organization_unit_id': R, 'authority_code': 'IDENTITY_ORGANIZATION_MANAGE',
            'state': 'ACTIVE', 'revision': 0, 'valid_from': AT, 'valid_until': None,
            'revoked_at': None, 'revocation_reason_code': None}],
        'grants': []}


def canonical(value):
    ordered = lambda v: ({k: ordered(v[k]) for k in sorted(v, key=lambda x: x.encode('utf-16-be'))}
                         if type(v) is dict else [ordered(x) for x in v] if type(v) is list else v)
    return json.dumps(ordered(value), ensure_ascii=False, separators=(',', ':'))


def renamed_runtime_facts(plan):
    current = copy.deepcopy(plan['original'])
    current['grants'] = copy.deepcopy(plan['grants'])
    current['root']['display_name'] = 'Approved Renamed Root'
    current['root']['revision'] = 1
    scope = {'profile': 'R1_IDENTITY_COMMAND_SCOPE_V1', 'tenantId': T,
        'commandType': 'RENAME_ORGANIZATION_UNIT', 'principalId': F, 'appointmentId': FA,
        'target': {'kind': 'identity.organization_unit', 'id': R}}
    result = {'outcome': 'SUCCEEDED',
        'resultFact': {'type': 'identity.organization_unit', 'id': R, 'revision': 1},
        'rejectionCode': None}
    summary = {'result': result, 'authorizationEvidence': 'synthetic original authorization evidence',
        'receiptRecovery': {'profile': 'R1_IDENTITY_RECEIPT_RECOVERY_V1', 'scope': scope,
            'target': result['resultFact'],
            'authorizationAnchor': {'type': 'identity.organization_unit', 'id': R, 'revision': 0}}}
    sha = lambda text: hashlib.sha256(text.encode('utf-8')).hexdigest()
    current['rootRenameEvidence'] = [{'slot': {
            'tenant_id': T, 'command_execution_slot_id': SLOT, 'command_id': COMMAND,
            'envelope_type': 'INTERNAL_ADMIN', 'command_type': 'RENAME_ORGANIZATION_UNIT',
            'command_scope_digest': sha(canonical(scope)), 'payload_digest': 'ab'*32, 'occupied_at': AT},
        'receipt': {'tenant_id': T, 'command_receipt_id': RECEIPT,
            'command_execution_slot_id': SLOT, 'outcome': 'SUCCEEDED', 'rejection_code': None,
            'completed_at': '2026-09-09T01:00:02+00:00', 'result_fact_type': 'identity.organization_unit',
            'result_fact_id': R, 'result_fact_revision': 1, 'result_fact_hash': None},
        'audit': {'tenant_id': T, 'entry_type': 'EVENT', 'audit_scope_code': 'OBJECT',
            'trusted_at': '2026-09-09T01:00:01+00:00', 'action_code': 'RENAME_ORGANIZATION_UNIT',
            'result_code': 'SUCCEEDED', 'actor_principal_id': F, 'actor_appointment_id': FA,
            'on_behalf_of_principal_id': None, 'on_behalf_of_appointment_id': None,
            'command_id': COMMAND, 'command_type': 'RENAME_ORGANIZATION_UNIT',
            'correlation_id': str(__import__('uuid').UUID(int=11)), 'causation_id': None,
            'authorization_slot_code': 'IDENTITY_ADMIN', 'authorization_path_code': 'DIRECT',
            'authorization_scope_organization_unit_id': R,
            'authorization_snapshot_digest': sha(summary['authorizationEvidence']),
            'trace_id': str(__import__('uuid').UUID(int=11)), 'service_role_code': 'API',
            'summary_schema_code': 'R1_IDENTITY_COMMAND_AUDIT_V1', 'summary_schema_version': 1,
            'change_summary': summary, 'change_summary_digest': sha(canonical(summary)),
            'subject_type': 'identity.organization_unit', 'subject_id': R, 'subject_revision': 0,
            'subject_hash': None, 'correction_target_type': None, 'correction_target_id': None,
            'correction_target_revision': None, 'correction_target_hash': None,
            'authorization_fact_type': 'identity.authority_grant', 'authorization_fact_id': G,
            'authorization_fact_revision': 0, 'authorization_fact_hash': None}}]
    return current


def redigest_evidence(current):
    audit = current['rootRenameEvidence'][0]['audit']
    audit['change_summary_digest'] = hashlib.sha256(canonical(audit['change_summary']).encode()).hexdigest()


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

    def test_grant_transaction_joins_exact_business_then_identity_fences_before_table_locks(self):
        # Independent .NET SHA256/BitConverter vectors for canonical tenant ...0001.
        # A different namespace/tenant, unsigned conversion, swapped order, shared
        # lock, missing timeout, or default isolation must fail this boundary test.
        boundary = self.m.WorkerBoundary(SimpleNamespace(RUNTIME=self.runtime), SimpleNamespace())
        plan = self.m.grant_plan(self.identity, fixture(), self.now)
        for delta in (3, 0):
            current = fixture()
            if delta == 0: current['grants'] = copy.deepcopy(plan['grants'])
            statements = []
            boundary.release_boundary.sql = lambda sql: statements.append(sql) or 'LOCAL_WORKER_GRANTS_' + str(delta)
            self.assertEqual(boundary.apply_grants(plan, current, 'SELECT NULL::jsonb'), delta)
            sql = statements[0]
            with self.subTest(delta=delta):
                locks = list(re.finditer(r'(pg_advisory_xact_lock(?:_shared)?)\((-?\d+)\)', sql))
                self.assertEqual([(m[1], m[2]) for m in locks], [
                    ('pg_advisory_xact_lock', '3054790668159973240'),
                    ('pg_advisory_xact_lock', '-6113651264468117507')])
                self.assertTrue(sql.startswith('BEGIN ISOLATION LEVEL READ COMMITTED;'))
                self.assertLess(sql.index("SET LOCAL lock_timeout='5s'"), locks[0].start())
                self.assertLess(sql.index("SET LOCAL statement_timeout='30s'"), locks[0].start())
                self.assertLess(locks[1].end(), sql.index('LOCK TABLE identity.tenant'))
                self.assertLess(sql.index('LOCK TABLE identity.tenant'), sql.index('IS DISTINCT FROM'))
                if delta == 3:
                    self.assertLess(sql.index('IS DISTINCT FROM'), sql.index('INSERT INTO identity.authority_grant'))
                else:
                    self.assertNotIn('INSERT INTO', sql)
                self.assertNotIn('INSERT INTO execution.', sql)

    def test_grant_fences_never_accept_noncanonical_or_changed_existing_tenant(self):
        boundary = self.m.WorkerBoundary(SimpleNamespace(RUNTIME=self.runtime), SimpleNamespace())
        plan = self.m.grant_plan(self.identity, fixture(), self.now)
        statements = []
        boundary.release_boundary.sql = lambda sql: statements.append(sql) or 'LOCAL_WORKER_GRANTS_3'
        for tenant in ('00000000000000000000000000000001', P, "' OR true --"):
            changed = copy.deepcopy(plan)
            changed['identity']['tenantId'] = tenant
            with self.subTest(tenant=tenant), self.assertRaises(RuntimeError):
                boundary.apply_grants(changed, fixture(), 'SELECT NULL::jsonb')
        self.assertEqual(statements, [], 'invalid tenant must fail before any external SQL call')

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
            def runtime_facts(self, identity, command):
                result = copy.deepcopy(state['facts'])
                result.setdefault('rootRenameEvidence', [])
                return result
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

    def test_runtime_consumer_accepts_approved_root_rename_without_mutating_original_plan(self):
        worker,state,package = self.assembly()
        worker.grant()
        plan_path = self.runtime/'worker/grants.json'
        plan = json.loads(plan_path.read_text())
        state['facts'] = renamed_runtime_facts(plan)
        original = plan_path.read_bytes()
        with self.assertRaises(RuntimeError):
            self.m.grant_delta(plan, {k:v for k,v in state['facts'].items() if k != 'rootRenameEvidence'})
        worker.prepare()
        self.assertEqual(plan_path.read_bytes(), original)
        self.assertTrue((self.runtime/'worker/application.properties').is_file())

    def test_runtime_validator_accepts_unchanged_root_without_synthetic_history(self):
        plan = self.m.grant_plan(self.identity, fixture(), self.now)
        current = copy.deepcopy(plan['original'])
        current['grants'] = copy.deepcopy(plan['grants'])
        current['rootRenameEvidence'] = []
        self.assertIsNone(self.m.runtime_grants_current(plan, current))
        self.assertEqual(self.m.grant_delta(plan, {k:v for k,v in current.items() if k != 'rootRenameEvidence'}), 0)

    def test_runtime_rename_rejects_missing_ambiguous_or_broken_command_closure(self):
        plan = self.m.grant_plan(self.identity, fixture(), self.now)
        def missing(x): x['rootRenameEvidence'].clear()
        def ambiguous(x): x['rootRenameEvidence'].append(copy.deepcopy(x['rootRenameEvidence'][0]))
        def slot_tenant(x): x['rootRenameEvidence'][0]['slot'].__setitem__('tenant_id', P)
        def envelope(x): x['rootRenameEvidence'][0]['slot'].__setitem__('envelope_type', 'INTERNAL_TASK')
        def command_type(x): x['rootRenameEvidence'][0]['slot'].__setitem__('command_type', 'CLOSE_ORGANIZATION_UNIT')
        def payload_digest(x): x['rootRenameEvidence'][0]['slot'].__setitem__('payload_digest', 'ab')
        def receipt_id(x): x['rootRenameEvidence'][0]['receipt'].__setitem__('command_receipt_id', 'not-a-uuid')
        def no_change(x): x['rootRenameEvidence'][0]['receipt'].__setitem__('outcome', 'NO_CHANGE')
        def rejected(x): x['rootRenameEvidence'][0]['receipt'].update(outcome='REJECTED', rejection_code='DENIED')
        def result_revision(x): x['rootRenameEvidence'][0]['receipt'].__setitem__('result_fact_revision', 2)
        def audit_actor(x): x['rootRenameEvidence'][0]['audit'].__setitem__('actor_principal_id', P)
        def audit_action(x): x['rootRenameEvidence'][0]['audit'].__setitem__('action_code', 'CLOSE_ORGANIZATION_UNIT')
        def audit_revision(x): x['rootRenameEvidence'][0]['audit'].__setitem__('subject_revision', 1)
        def scope_digest(x): x['rootRenameEvidence'][0]['slot'].__setitem__('command_scope_digest', '00'*32)
        def summary_digest(x): x['rootRenameEvidence'][0]['audit'].__setitem__('change_summary_digest', '00'*32)
        def authorization_digest(x):
            x['rootRenameEvidence'][0]['audit']['change_summary']['authorizationEvidence'] += '-tampered'
            redigest_evidence(x)
        def wrong_root_scope(x):
            summary = x['rootRenameEvidence'][0]['audit']['change_summary']
            summary['receiptRecovery']['scope']['target']['id'] = F
            x['rootRenameEvidence'][0]['slot']['command_scope_digest'] = hashlib.sha256(
                canonical(summary['receiptRecovery']['scope']).encode()).hexdigest()
            redigest_evidence(x)
        def wrong_anchor(x):
            x['rootRenameEvidence'][0]['audit']['change_summary']['receiptRecovery']['authorizationAnchor']['id'] = F
            redigest_evidence(x)
        def wrong_grant(x): x['rootRenameEvidence'][0]['audit'].__setitem__('authorization_fact_id', F)
        def invalid_name(x): x['root'].__setitem__('display_name', None)
        faults = {'missing':missing, 'ambiguous':ambiguous, 'slot tenant':slot_tenant,
            'envelope':envelope, 'command type':command_type, 'payload digest':payload_digest,
            'receipt id':receipt_id, 'NO_CHANGE':no_change, 'rejected':rejected,
            'result revision':result_revision, 'audit actor':audit_actor, 'audit action':audit_action,
            'audit revision gap':audit_revision, 'scope digest':scope_digest,
            'summary digest':summary_digest, 'authorization digest':authorization_digest,
            'wrong ROOT scope':wrong_root_scope, 'wrong recovery anchor':wrong_anchor,
            'wrong authorization grant':wrong_grant, 'invalid renamed display':invalid_name}
        for name, mutation in faults.items():
            current = renamed_runtime_facts(plan)
            mutation(current)
            with self.subTest(fault=name), self.assertRaises(RuntimeError):
                self.m.runtime_grants_current(plan, current)

    def test_runtime_rename_never_relaxes_grants_or_other_original_facts(self):
        plan = self.m.grant_plan(self.identity, fixture(), self.now)
        def partial(x): x['grants'].pop()
        def extra(x): x['grants'].append(copy.deepcopy(x['grants'][0]))
        def changed(x): x['grants'][0].__setitem__('revision', 1)
        def revoked(x): x['grants'][0].update(state='REVOKED', revoked_at=AT)
        def principal(x): x['principal'].__setitem__('revision', 1)
        def root_code(x): x['root'].__setitem__('unit_code', 'OTHER')
        def root_parent(x): x['root'].__setitem__('parent_organization_unit_id', F)
        def founder_grant(x): x['founderGrants'][0].__setitem__('revision', 1)
        for name, mutation in {'partial grants':partial, 'extra grants':extra, 'changed grant':changed,
                'revoked grant':revoked, 'principal drift':principal, 'root code drift':root_code,
                'root parent drift':root_parent, 'original management grant drift':founder_grant}.items():
            current = renamed_runtime_facts(plan); mutation(current)
            with self.subTest(fault=name), self.assertRaises(RuntimeError):
                self.m.runtime_grants_current(plan, current)

    def test_runtime_rename_requires_a_contiguous_unique_revision_chain(self):
        plan = self.m.grant_plan(self.identity, fixture(), self.now)
        current = renamed_runtime_facts(plan)
        second = copy.deepcopy(current['rootRenameEvidence'][0])
        second['slot'].update(command_execution_slot_id=str(__import__('uuid').UUID(int=13)),
            command_id=str(__import__('uuid').UUID(int=12)), occupied_at='2026-09-09T01:00:03+00:00')
        second['receipt'].update(command_receipt_id=str(__import__('uuid').UUID(int=14)),
            command_execution_slot_id=second['slot']['command_execution_slot_id'],
            completed_at='2026-09-09T01:00:05+00:00', result_fact_revision=2)
        second['audit'].update(command_id=second['slot']['command_id'],
            correlation_id=str(__import__('uuid').UUID(int=15)), trace_id=str(__import__('uuid').UUID(int=15)),
            trusted_at='2026-09-09T01:00:04+00:00', subject_revision=1)
        summary = second['audit']['change_summary']
        summary['result']['resultFact']['revision'] = 2
        summary['receiptRecovery']['target']['revision'] = 2
        summary['receiptRecovery']['authorizationAnchor']['revision'] = 1
        second['audit']['change_summary_digest'] = hashlib.sha256(canonical(summary).encode()).hexdigest()
        current['root'].update(display_name='Second Approved Name', revision=2)
        current['rootRenameEvidence'].append(second)
        self.assertIsNone(self.m.runtime_grants_current(plan, current))
        restored_name = copy.deepcopy(current)
        restored_name['root']['display_name'] = plan['original']['root']['display_name']
        self.assertIsNone(self.m.runtime_grants_current(plan, restored_name),
            'stored summaries do not record rename text, while two succeeded revisions can legitimately restore it')
        for fault in ('gap', 'duplicate', 'future', 'out of order'):
            broken = copy.deepcopy(current)
            if fault == 'gap': broken['rootRenameEvidence'].pop(0)
            if fault == 'duplicate': broken['rootRenameEvidence'][1]['slot']['command_id'] = COMMAND
            if fault == 'future': broken['rootRenameEvidence'][1]['receipt']['result_fact_revision'] = 3
            if fault == 'out of order': broken['rootRenameEvidence'].reverse()
            with self.subTest(fault=fault), self.assertRaises(RuntimeError):
                self.m.runtime_grants_current(plan, broken)

    def test_runtime_query_is_one_read_only_snapshot_scoped_to_canonical_tenant_and_root(self):
        calls = []
        expected = renamed_runtime_facts(self.m.grant_plan(self.identity, fixture(), self.now))
        boundary = self.m.WorkerBoundary(SimpleNamespace(RUNTIME=self.runtime),
            SimpleNamespace(sql=lambda sql: calls.append(sql) or json.dumps(expected)))
        self.assertEqual(boundary.runtime_facts(self.identity, str(__import__('uuid').UUID(int=7))), expected)
        self.assertEqual(len(calls), 1)
        sql = calls[0]
        self.assertTrue(sql.startswith("BEGIN READ ONLY; SET LOCAL TIME ZONE 'UTC'; WITH worker_facts"))
        self.assertGreater(sql.count("'"+T+"'::uuid"), 1)
        self.assertIn("a.change_summary#>>'{receiptRecovery,scope,target,id}'=worker_facts.value->'bootstrap'->>'rootOrganizationId'", sql)
        self.assertIn('JOIN execution.command_execution_slot s', sql)
        self.assertIn('JOIN execution.command_receipt r', sql)
        self.assertTrue(sql.endswith('; COMMIT;'))
        for mutation in ('INSERT INTO', 'UPDATE ', 'DELETE FROM', 'LOCK TABLE'):
            self.assertNotIn(mutation, sql)

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
