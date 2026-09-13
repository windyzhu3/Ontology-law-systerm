"""Current-runtime release gate tests use only temporary synthetic boundaries."""
import copy
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path
import shutil
from types import SimpleNamespace
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import local_release
import local_worker
from test_local_worker import fixture, renamed_runtime_facts, T, P, A


class CurrentRuntimeVerificationTest(unittest.TestCase):
    def setUp(self):
        try:
            import local_runtime_verification
        except ModuleNotFoundError:
            self.fail('current-release runtime verifier is missing')
        self.verifier = local_runtime_verification
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.runtime = self.root / 'private'
        self.runtime.mkdir()
        self.release_id = 'a' * 32
        self.source_commit = 'b' * 40
        self.jar_bytes = b'synthetic controlled jar'
        self.spa_bytes = b'synthetic spa'
        self.server_bytes = b'synthetic host'
        self.jar_hash = hashlib.sha256(self.jar_bytes).hexdigest()
        self.schema = {'catalog': 'synthetic schema', 'history': ['V860']}
        self.identity = {'tenantId': T, 'principalId': P, 'appointmentId': A}
        self.command_id = str(__import__('uuid').UUID(int=7))

        (self.runtime / 'secrets').mkdir()
        (self.runtime / 'secrets/worker-db.txt').write_text('synthetic-worker-secret')
        (self.runtime / 'secrets/trust-password.txt').write_text('synthetic-tls-secret')
        (self.runtime / 'certs').mkdir()
        (self.runtime / 'certs/ca.pem').write_text('synthetic-ca')
        (self.runtime / 'operator.json').write_text(json.dumps({'tenantId': T}))
        (self.runtime / 'original-manifest.json').write_text(json.dumps({'commandId': self.command_id}))
        (self.runtime / 'service-fixture.json').write_text(json.dumps(self.identity))

        self.provenance = {'profile': 'SYNTHETIC_CONTROLLED_RELEASE',
            'sourceCommit': self.source_commit, 'jarSha256': self.jar_hash,
            'spaFiles': {'index.html': hashlib.sha256(self.spa_bytes).hexdigest()},
            'serverSha256': hashlib.sha256(self.server_bytes).hexdigest()}
        self.provenance['spaSha256'] = local_release.digest(local_release.encoded(self.provenance['spaFiles']))
        self.manifest_hash = local_release.digest(local_release.encoded(self.provenance))
        self.gate = {'deployment_state_key': 'PRIMARY', 'operating_mode': 'ACTIVE',
            'active_release_digest': self.jar_hash, 'active_manifest_hash': self.manifest_hash,
            'schema_contract_version': '52-plus-2-v1.2', 'revision': 12,
            'changed_at': '2026-09-10T01:00:00.000000Z'}
        self.deployment = {'tenantId': T, 'releaseDigest': self.jar_hash,
            'manifestHash': self.manifest_hash}
        self.config = ('ols.api.database.release-digest=' + self.jar_hash + '\n'
            'ols.api.database.manifest-hash=' + self.manifest_hash + '\n').encode()

        package = self.runtime / 'releases' / self.release_id
        (package / 'dist').mkdir(parents=True)
        (package / 'app.jar').write_bytes(self.jar_bytes)
        (package / 'dist/index.html').write_bytes(self.spa_bytes)
        (package / 'server.mjs').write_bytes(self.server_bytes)
        (package / 'application.properties').write_bytes(self.config)
        (package / 'deployment.json').write_bytes(local_release.encoded(self.deployment))
        (package / 'release-manifest.json').write_bytes(local_release.encoded(self.provenance))

        outer = self
        class Boundary(local_release.RuntimeBoundary):
            def protect(self):
                return None

            def read(self):
                return copy.deepcopy({'gate': outer.gate, 'schema': outer.schema})

            def sql(self, statement):
                outer.sql.append(statement)
                return json.dumps(outer.current_facts)

            def process(self, pid):
                value = outer.actual_processes.get(pid)
                return copy.deepcopy(value) if value is not None else None

        self.runner = SimpleNamespace(ROOT=self.root, RUNTIME=self.runtime,
            JAVA=self.root / 'java.exe', TOOLS=self.root / 'tools',
            JAR=self.root / 'legacy.jar')
        self.boundary = Boundary(self.runner)
        self.release = local_release.LocalRelease(self.root, self.runtime, self.boundary)
        record = {'id': self.release_id, 'kind': 'controlled-local-release',
            'schema': self.schema, 'materials': self.release.materials(),
            'schemaSources': {'synthetic': 'source'}, 'jarSha256': self.jar_hash,
            'manifestHash': self.manifest_hash, 'provenance': self.provenance}
        record['files'] = local_release.files(package)
        (package / 'release.json').write_bytes(local_release.encoded(record))
        self.current = {'id': self.release_id, 'gate': self.gate,
            'descriptorHash': local_release.digest(local_release.encoded(record))}
        (self.runtime / 'current-release.json').write_bytes(local_release.encoded(self.current))
        (self.runtime / 'application.properties').write_bytes(self.config)
        (self.runtime / 'deployment.json').write_bytes(local_release.encoded(self.deployment))

        original = fixture()
        original.update(originalAudit={'audit_entry_id': 'synthetic-original-audit'},
            originalSlot={'command_execution_slot_id': 'synthetic-original-slot'},
            originalReceipt={'command_receipt_id': 'synthetic-original-receipt'})
        plan = local_worker.grant_plan(self.identity, original, datetime(2026, 9, 9, 1, tzinfo=timezone.utc))
        self.current_facts = renamed_runtime_facts(plan)
        (self.runtime / 'worker').mkdir()
        (self.runtime / 'worker/grants.json').write_bytes(local_release.encoded(plan))
        paths = self.release.paths()
        worker_config = local_worker.worker_properties(self.runtime, paths, self.identity,
            'd' * 64, 'synthetic-worker-secret', 'synthetic-tls-secret').encode()
        (self.runtime / 'worker/application.properties').write_bytes(worker_config)
        (self.runtime / 'worker/configuration.json').write_bytes(local_release.encoded({
            'configHash': local_release.digest(worker_config), 'jar': str(paths['jar']),
            'release': paths['release'], 'manifest': paths['manifest']}))

        package_path = self.release.package(self.release_id)
        commands = {**local_release.app_commands(self.runner, package_path),
            'worker': local_worker.worker_command(self.runner, package_path)}
        started = '2026-09-10T00:59:59+00:00'
        created = '2026-09-10T01:00:00+00:00'
        registry = {name: {'pid': index, 'executable': command[0],
            'args': command[1:], 'created': created}
            for index, (name, command) in enumerate(commands.items(), 101)}
        registry['worker']['startedAt'] = started
        (self.runtime / 'processes.json').write_bytes(local_release.encoded(registry))
        self.actual_processes = copy.deepcopy({value['pid']: value for value in registry.values()})
        prefix = '2026-09-10T01:00:01+00:00 INFO 103 --- [main] io.github.windyzhu3.ontologylaw.worker.WorkerRuntimeHealth : '
        (self.runtime / 'worker.stdout').write_text(
            prefix + 'R1_WORKER_ASSEMBLY_ISOLATED\n' + prefix + 'R1_WORKER_READY\n')
        self.sql = []

    def verify(self, database=None, certificate=None, mtls=None, listeners=None):
        database = database or (lambda boundary, paths: None)
        certificate = certificate or (lambda boundary: 'd' * 64)
        mtls = mtls or (lambda boundary: None)
        listeners = listeners or (lambda boundary, pid: 0)
        with patch.object(local_worker.WorkerBoundary, 'database', database), \
                patch.object(local_worker.WorkerBoundary, 'certificate', certificate), \
                patch.object(local_worker.WorkerBoundary, 'mtls', mtls), \
                patch.object(local_worker.WorkerBoundary, 'listeners', listeners):
            return self.verifier.verify_current_runtime(self.runner, self.release, self.boundary)

    def rewrite_as_source_release(self):
        package = self.release.package(self.release_id)
        source_release = {'profile': 'LOCAL_SYNTHETIC_SOURCE_RELEASE_V1',
            'parent': {'id': 'f' * 32, 'descriptorHash': '1' * 64, 'manifestHash': '2' * 64},
            'originalConfigSha256': local_release.digest(self.config),
            'sourceDelta': ['ols.api.sources[LOCAL_SYNTHETIC_AUTO].assignment-mode=AUTOMATIC'],
            'operatorCommit': 'c' * 40, 'binaryProvenance': copy.deepcopy(self.provenance),
            'jarSha256': self.jar_hash, 'spaFiles': local_release.files(package / 'dist'),
            'serverSha256': local_release.digest((package / 'server.mjs').read_bytes())}
        source_release['spaSha256'] = local_release.digest(local_release.encoded(source_release['spaFiles']))
        manifest_hash = local_release.digest(local_release.encoded(source_release))
        self.gate['active_manifest_hash'] = manifest_hash
        self.deployment['manifestHash'] = manifest_hash
        self.config = ('ols.api.database.release-digest=' + self.jar_hash + '\n'
            'ols.api.database.manifest-hash=' + manifest_hash + '\n').encode()
        (package / 'release-manifest.json').write_bytes(local_release.encoded(source_release))
        (package / 'application.properties').write_bytes(self.config)
        (package / 'deployment.json').write_bytes(local_release.encoded(self.deployment))
        record = json.loads((package / 'release.json').read_text())
        (package / 'release.json').unlink()
        record.update(kind='controlled-local-source-release', manifestHash=manifest_hash,
            sourceRelease=source_release)
        record['files'] = local_release.files(package)
        (package / 'release.json').write_bytes(local_release.encoded(record))
        self.current = {'id': self.release_id, 'gate': copy.deepcopy(self.gate),
            'descriptorHash': local_release.digest(local_release.encoded(record))}
        (self.runtime / 'current-release.json').write_bytes(local_release.encoded(self.current))
        (self.runtime / 'application.properties').write_bytes(self.config)
        (self.runtime / 'deployment.json').write_bytes(local_release.encoded(self.deployment))
        paths = self.release.paths()
        worker_config = local_worker.worker_properties(self.runtime, paths, self.identity,
            'd' * 64, 'synthetic-worker-secret', 'synthetic-tls-secret').encode()
        (self.runtime / 'worker/application.properties').write_bytes(worker_config)
        (self.runtime / 'worker/configuration.json').write_bytes(local_release.encoded({
            'configHash': local_release.digest(worker_config), 'jar': str(paths['jar']),
            'release': paths['release'], 'manifest': paths['manifest']}))

    def test_current_controlled_release_with_approved_root_rename_is_verified_read_only(self):
        before = {path.relative_to(self.runtime).as_posix(): path.read_bytes()
            for path in self.runtime.rglob('*') if path.is_file()}
        result = self.verify()

        self.assertEqual(result, {'status': 'VERIFIED_CURRENT_RUNTIME',
            'releaseId': self.release_id, 'sourceCommit': self.source_commit,
            'gateRevision': 12, 'workerHealth': {'state': 'READY', 'requiredLoops': 3,
                'listeners': 0, 'database': 'READY', 'mtls': 'READY'}})
        self.assertEqual(len(self.sql), 1)
        self.assertTrue(self.sql[0].startswith("BEGIN READ ONLY; SET LOCAL TIME ZONE 'UTC';"))
        for mutation in ('INSERT INTO', 'UPDATE ', 'DELETE FROM', 'LOCK TABLE'):
            self.assertNotIn(mutation, self.sql[0])
        self.assertEqual({path.relative_to(self.runtime).as_posix(): path.read_bytes()
            for path in self.runtime.rglob('*') if path.is_file()}, before)

    def test_current_controlled_source_release_uses_binary_source_commit(self):
        self.rewrite_as_source_release()
        result = self.verify()
        self.assertEqual(result['status'], 'VERIFIED_CURRENT_RUNTIME')
        self.assertEqual(result['releaseId'], self.release_id)
        self.assertEqual(result['sourceCommit'], self.source_commit)
        self.assertEqual(result['gateRevision'], 12)

    def test_worker_facts_certificate_mtls_loops_and_grants_must_all_be_current(self):
        def missing_rename(facts):
            facts['rootRenameEvidence'] = []
        def damaged_evidence(facts):
            facts['rootRenameEvidence'][0]['audit']['change_summary_digest'] = '0' * 64
        def changed_original_slot(facts):
            facts['originalSlot']['command_execution_slot_id'] = 'changed'
        def changed_original_receipt(facts):
            facts['originalReceipt']['command_receipt_id'] = 'changed'
        def changed_original_audit(facts):
            facts['originalAudit']['audit_entry_id'] = 'changed'
        def changed_principal(facts):
            facts['principal']['revision'] = 1
        def changed_grant(facts):
            facts['grants'][0]['revision'] = 1
        for name, mutation in {'ROOT rename without evidence': missing_rename,
                'damaged rename evidence': damaged_evidence, 'original Slot drift': changed_original_slot,
                'original Receipt drift': changed_original_receipt, 'original Audit drift': changed_original_audit,
                'other original fact drift': changed_principal, 'Worker grant drift': changed_grant}.items():
            original = copy.deepcopy(self.current_facts)
            mutation(self.current_facts)
            with self.subTest(fault=name), self.assertRaises(RuntimeError):
                self.verify()
            self.current_facts = original

        failures = {
            'certificate': {'certificate': lambda boundary: (_ for _ in ()).throw(RuntimeError('certificate'))},
            'mTLS': {'mtls': lambda boundary: (_ for _ in ()).throw(RuntimeError('mTLS'))},
            'listener': {'listeners': lambda boundary, pid: 1},
            'database': {'database': lambda boundary, paths: (_ for _ in ()).throw(RuntimeError('database'))},
        }
        for name, options in failures.items():
            with self.subTest(fault=name), self.assertRaises(RuntimeError):
                self.verify(**options)
        stdout = self.runtime / 'worker.stdout'
        healthy = stdout.read_bytes()
        stdout.write_text('2026-09-10T01:00:01+00:00 INFO 103 --- [main] '
            'io.github.windyzhu3.ontologylaw.worker.WorkerRuntimeHealth : R1_WORKER_UNAVAILABLE\n')
        with self.assertRaises(RuntimeError):
            self.verify()
        stdout.write_bytes(healthy)

    def test_release_gate_schema_package_material_and_pending_state_are_refused(self):
        package = self.release.package(self.release_id)
        cases = {
            'gate': lambda: self.gate.__setitem__('revision', 13),
            'schema': lambda: self.schema.__setitem__('catalog', 'changed'),
            'package': lambda: (package / 'app.jar').write_bytes(b'changed'),
            'material': lambda: (self.runtime / 'certs/ca.pem').write_text('changed'),
            'apps pending': lambda: (self.runtime / 'apps-start.pending').write_text('pending'),
            'worker pending': lambda: (self.runtime / 'worker-start.pending').write_text('pending'),
            'release pending': lambda: (self.release.store / ('f' * 32 + '.pending')).mkdir(),
        }
        for name, mutation in cases.items():
            with self.subTest(fault=name):
                gate, schema = copy.deepcopy(self.gate), copy.deepcopy(self.schema)
                jar, ca = (package / 'app.jar').read_bytes(), (self.runtime / 'certs/ca.pem').read_bytes()
                mutation()
                with self.assertRaises(RuntimeError):
                    self.verify()
                self.gate.clear(); self.gate.update(gate)
                self.schema.clear(); self.schema.update(schema)
                (package / 'app.jar').write_bytes(jar)
                (self.runtime / 'certs/ca.pem').write_bytes(ca)
                for marker in (self.runtime / 'apps-start.pending', self.runtime / 'worker-start.pending'):
                    marker.unlink(missing_ok=True)
                pending = self.release.store / ('f' * 32 + '.pending')
                if pending.exists(): pending.rmdir()

    def test_old_package_stopped_changed_and_unknown_processes_are_refused(self):
        registry_path = self.runtime / 'processes.json'
        registry = json.loads(registry_path.read_text())
        package = self.release.package(self.release_id)
        historical_id = 'e' * 32
        historical = self.release.package(historical_id)
        shutil.copytree(package, historical)
        old_record = json.loads((historical / 'release.json').read_text())
        old_record['id'] = historical_id
        (historical / 'release.json').unlink()
        old_record['files'] = local_release.files(historical)
        (historical / 'release.json').write_bytes(local_release.encoded(old_record))
        old_state = {'id': historical_id, 'gate': self.gate,
            'descriptorHash': local_release.digest(local_release.encoded(old_record))}
        (self.release.journal).write_bytes(local_release.encoded(
            {'phase': 'COMPLETE', 'old': old_state, 'new': self.current}))
        old_command = local_release.app_commands(self.runner, historical)['api']
        old_registry = copy.deepcopy(registry)
        old_registry['api']['executable'], old_registry['api']['args'] = old_command[0], old_command[1:]
        registry_path.write_bytes(local_release.encoded(old_registry))
        self.actual_processes[old_registry['api']['pid']] = copy.deepcopy(old_registry['api'])
        with self.assertRaisesRegex(RuntimeError, 'current package'):
            self.verify()

        self.release.journal.unlink()
        registry_path.write_bytes(local_release.encoded(registry))
        self.actual_processes = {value['pid']: copy.deepcopy(value) for value in registry.values()}
        stopped = registry['worker']['pid']
        self.actual_processes[stopped] = None
        with self.assertRaises(RuntimeError):
            self.verify()
        self.actual_processes = {value['pid']: copy.deepcopy(value) for value in registry.values()}
        unknown = {**registry, 'other': copy.deepcopy(registry['api'])}
        registry_path.write_bytes(local_release.encoded(unknown))
        with self.assertRaises(RuntimeError):
            self.verify()

    def test_release_or_process_change_during_health_cannot_report_success(self):
        registry_path = self.runtime / 'processes.json'
        original_registry = registry_path.read_bytes()
        for name, mutation in {
                'release gate': lambda: self.gate.__setitem__('revision', 13),
                'process registry': lambda: registry_path.write_bytes(
                    local_release.encoded({**json.loads(original_registry),
                        'worker': {**json.loads(original_registry)['worker'], 'startedAt': '2026-09-10T00:59:58+00:00'}})),
                'original material': lambda: (self.runtime / 'certs/ca.pem').write_text('changed during check')}.items():
            gate = copy.deepcopy(self.gate)
            ca = (self.runtime / 'certs/ca.pem').read_bytes()
            with self.subTest(fault=name), self.assertRaises(RuntimeError):
                self.verify(mtls=lambda boundary: mutation())
            self.gate.clear(); self.gate.update(gate)
            registry_path.write_bytes(original_registry)
            (self.runtime / 'certs/ca.pem').write_bytes(ca)

    def test_named_release_operation_prints_only_safe_result_and_rejects_arguments(self):
        import local_login
        captured = io.StringIO()
        with patch.object(local_login, 'ROOT', self.root), patch.object(local_login, 'RUNTIME', self.runtime), \
                patch.object(local_login, 'JAVA', self.runner.JAVA), patch.object(local_login, 'TOOLS', self.runner.TOOLS), \
                patch.object(local_login, 'JAR', self.runner.JAR), \
                patch.object(local_release, 'RuntimeBoundary', return_value=self.boundary), \
                patch.object(local_release, 'LocalRelease', return_value=self.release), \
                patch.object(local_worker.WorkerBoundary, 'database', lambda boundary, paths: None), \
                patch.object(local_worker.WorkerBoundary, 'certificate', lambda boundary: 'd' * 64), \
                patch.object(local_worker.WorkerBoundary, 'mtls', lambda boundary: None), \
                patch.object(local_worker.WorkerBoundary, 'listeners', lambda boundary, pid: 0), \
                redirect_stdout(captured):
            local_login.release_operation('release-verify-current-runtime', [])
            with self.assertRaises(RuntimeError):
                local_login.release_operation('release-verify-current-runtime', ['unexpected'])
        output = captured.getvalue().strip().splitlines()
        self.assertEqual(len(output), 1)
        self.assertEqual(json.loads(output[0])['status'], 'VERIFIED_CURRENT_RUNTIME')
        self.assertNotIn('VERIFIED_ORIGINAL', output[0])
        self.assertNotIn('tenantId', output[0])
        self.assertNotIn('principalId', output[0])


if __name__ == '__main__':
    unittest.main()
