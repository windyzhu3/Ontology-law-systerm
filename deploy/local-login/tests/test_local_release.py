"""Synthetic file/DB fixtures; Windows stop checks use only new hidden test children."""
import copy
from contextlib import contextmanager, redirect_stdout
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

MODULE = Path(__file__).resolve().parents[1] / 'local_release.py'
BUILD_INPUT_FIXTURES = (
    'backend/pom.xml', 'package.json', 'package-lock.json',
    'apps/workbench/package.json', 'apps/workbench/vite.config.ts',
    'apps/workbench/index.html', 'apps/workbench/tsconfig.json',
    'backend/src/generated/jooq/Synthetic.java',
    'mvnw.cmd', '.mvn/wrapper/maven-wrapper.properties',
)
REVIEW_INPUTS = ('apps/workbench/index.html', 'backend/src/generated/jooq/Synthetic.java',
                 'apps/workbench/tsconfig.json')


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(MODULE.exists(), 'controlled local release operations are missing')
        spec = importlib.util.spec_from_file_location('local_release', MODULE)
        self.m = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.m)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.runtime = self.root / 'private'
        self.runtime.mkdir()
        self.jar = self.root / 'backend/target/ontology-law-system-0.1.0-SNAPSHOT.jar'
        self.jar.parent.mkdir(parents=True)
        self.jar.write_bytes(b'old jar')
        self.dist = self.root / 'apps/workbench/dist'
        self.dist.mkdir(parents=True)
        (self.dist / 'index.html').write_bytes(b'old spa')
        for name in self.m.SOURCE_FILES:
            p = self.root / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text('{}' if name.endswith('.json') else 'synthetic source')
        for name in BUILD_INPUT_FIXTURES:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('synthetic build input')
        (self.root / 'deploy/local-login/server.mjs').write_bytes(b'old server')
        self.gate = {'deployment_state_key': 'PRIMARY', 'operating_mode': 'ACTIVE',
                     'active_release_digest': hashlib.sha256(b'old jar').hexdigest(),
                     'active_manifest_hash': '1' * 64, 'schema_contract_version': '52-plus-2-v1.2',
                     'revision': 7, 'changed_at': '2026-09-09T01:00:00.000000Z'}
        self.schema = {'schema': 'synthetic catalog fingerprint', 'history': ['V001', 'V860']}
        self.config = ('ols.api.database.release-digest=' + self.gate['active_release_digest'] +
                       '\nols.api.database.manifest-hash=' + '1' * 64 + '\nsecret=unchanged\n').encode()
        (self.runtime / 'application.properties').write_bytes(self.config)
        self.deployment = {'tenantId': 'synthetic-tenant', 'releaseDigest': self.gate['active_release_digest'], 'manifestHash': '1' * 64}
        (self.runtime / 'deployment.json').write_text(json.dumps(self.deployment))
        (self.runtime / 'operator.json').write_text(json.dumps({'subjectHmacPath': 'synthetic', 'database': {
            'releaseDigest': self.gate['active_release_digest'], 'manifestHash': '1' * 64, 'passwordPath': 'synthetic'}}))
        (self.runtime / 'original-manifest.json').write_bytes(b'original')
        (self.runtime / 'secrets').mkdir()
        (self.runtime / 'secrets/subject-key.txt').write_bytes(b'original key')
        self.events = []
        outer = self
        class Boundary:
            def protect(self): outer.events.append('protect')
            def provenance(self, commit): pass
            def legacy_server(self): return b'historical hardcoded server'
            def read(self): return copy.deepcopy({'gate': outer.gate, 'schema': outer.schema})
            def cas(self, old, new, schema):
                if old != outer.gate or schema != outer.schema: raise RuntimeError('CAS conflict')
                outer.events.append('cas')
                outer.gate = copy.deepcopy(new)
            def stopped(self): outer.events.append('stopped')
        self.boundary = Boundary()
        self.release = self.m.LocalRelease(self.root, self.runtime, self.boundary)

    def snapshot(self):
        self.release.snapshot()
        return self.release.current()

    def candidate(self):
        self.release.capture_inputs('2' * 40)
        self.jar.write_bytes(b'new jar')
        (self.dist / 'index.html').write_bytes(b'new spa')
        (self.dist / 'assets').mkdir(exist_ok=True)
        (self.dist / 'assets/app.js').write_bytes(b'new js')
        return self.release.describe('2' * 40, {'jar': 0, 'spa': 0})

    def stage(self):
        self.snapshot()
        request = self.candidate()
        return self.release.stage(request)

    def test_snapshot_preserves_old_bytes_and_gate_before_candidate_overwrites(self):
        old = self.snapshot()
        self.candidate()
        package = self.runtime / 'releases' / old['id']
        self.assertEqual((package / 'app.jar').read_bytes(), b'old jar')
        self.assertEqual((package / 'dist/index.html').read_bytes(), b'old spa')
        self.assertEqual((package / 'application.properties').read_bytes(), self.config)
        self.assertEqual(old['gate'], self.gate)
        self.assertEqual(self.events.count('cas'), 0)

    def test_stage_rejects_wrong_jar_missing_spa_failed_build_and_source_drift(self):
        for fault in ['jar', 'spa', 'build', 'source']:
            with self.subTest(fault=fault):
                self.setUp()
                self.snapshot()
                request = self.candidate()
                if fault == 'jar': self.jar.write_bytes(b'bad jar')
                if fault == 'spa': (self.dist / 'assets/app.js').unlink()
                if fault == 'build': request['buildExitCodes']['jar'] = 1
                if fault == 'source': (self.root / self.m.SOURCE_FILES[0]).write_text('changed source')
                before = (self.runtime / 'application.properties').read_bytes()
                with self.assertRaises(RuntimeError): self.release.stage(request)
                self.assertEqual((self.runtime / 'application.properties').read_bytes(), before)
                self.assertEqual(self.events.count('cas'), 0)

    def test_copy_failure_cannot_publish_partial_package_or_overwrite_live_state(self):
        self.snapshot()
        request = self.candidate()
        with patch.object(self.m.shutil, 'copy2', side_effect=OSError('copy failed')):
            with self.assertRaises(OSError): self.release.stage(request)
        self.assertEqual((self.runtime / 'application.properties').read_bytes(), self.config)
        self.assertEqual(self.events.count('cas'), 0)

    def test_snapshot_config_change_during_copy_cannot_publish_current_pointer(self):
        original_copy = self.m.shutil.copy2
        def changed(*args, **kwargs):
            result = original_copy(*args, **kwargs)
            (self.runtime / 'application.properties').write_bytes(b'concurrent configuration change')
            return result
        with patch.object(self.m.shutil, 'copy2', side_effect=changed):
            with self.assertRaises(RuntimeError): self.release.snapshot()
        self.assertFalse((self.runtime / 'current-release.json').exists())

    def test_activation_rejects_configuration_material_gate_and_unknown_process_before_cas(self):
        for fault in ['config', 'material', 'gate', 'pid', 'package']:
            with self.subTest(fault=fault):
                self.setUp()
                candidate = self.stage()
                if fault == 'config': (self.runtime / 'application.properties').write_text('drift')
                if fault == 'material': (self.runtime / 'secrets/subject-key.txt').write_text('drift')
                if fault == 'gate': self.gate['revision'] += 1
                if fault == 'pid': self.boundary.stopped = lambda: (_ for _ in ()).throw(RuntimeError('unknown PID'))
                if fault == 'package': (self.runtime / 'releases' / candidate / 'dist/index.html').write_text('drift')
                with self.assertRaises(RuntimeError): self.release.activate(candidate)
                self.assertEqual(self.events.count('cas'), 0)

    def test_activation_and_rollback_restore_only_verified_saved_bytes_and_gate_values(self):
        candidate = self.stage()
        self.release.activate(candidate)
        self.assertEqual(self.release.current()['id'], candidate)
        self.assertEqual(self.gate['revision'], 8)
        self.assertNotEqual(self.gate['active_manifest_hash'], '1' * 64)
        self.release.rollback()
        self.assertEqual(self.gate['active_manifest_hash'], '1' * 64)
        self.assertEqual(self.gate['revision'], 9)
        self.assertEqual((self.runtime / 'application.properties').read_bytes(), self.config)
        self.assertEqual((self.runtime / 'original-manifest.json').read_bytes(), b'original')
        self.assertEqual(self.release.paths()['jar'].read_bytes(), b'old jar')

    def test_schema_change_blocks_rollback(self):
        candidate = self.stage()
        self.release.activate(candidate)
        self.schema['schema'] = 'changed catalog'
        with self.assertRaises(RuntimeError): self.release.rollback()
        self.assertEqual(self.gate['revision'], 8)

    def test_lost_cas_response_is_not_success_and_explicit_recovery_reconciles_it(self):
        candidate = self.stage()
        real_cas = self.boundary.cas
        def lost(old, new, schema):
            real_cas(old, new, schema)
            raise RuntimeError('lost response')
        self.boundary.cas = lost
        with self.assertRaises(RuntimeError): self.release.activate(candidate)
        with self.assertRaises(RuntimeError): self.release.paths()
        self.assertEqual(self.release.status()['phase'], 'CAS_PENDING')
        self.boundary.cas = real_cas
        self.release.recover('complete')
        self.assertEqual(self.release.current()['id'], candidate)
        self.assertEqual(self.gate['revision'], 8)

    def test_partial_file_install_can_explicitly_restore_previous_release(self):
        candidate = self.stage()
        real_write = self.m.atomic
        def fail(path, data):
            if path.name == 'deployment.json': raise OSError('interrupted file replacement')
            return real_write(path, data)
        with patch.object(self.m, 'atomic', side_effect=fail):
            with self.assertRaises(OSError): self.release.activate(candidate)
        with self.assertRaises(RuntimeError): self.release.paths()
        self.release.recover('rollback')
        self.assertEqual(self.gate['active_manifest_hash'], '1' * 64)
        self.assertEqual((self.runtime / 'application.properties').read_bytes(), self.config)

    def test_derived_operator_changes_only_two_expected_fields_and_preserves_original(self):
        candidate = self.stage()
        self.release.activate(candidate)
        original = (self.runtime / 'operator.json').read_bytes()
        derived = self.release.derived_operator()
        old = json.loads(original)
        current = json.loads(derived.read_bytes())
        old['database']['releaseDigest'] = self.gate['active_release_digest']
        old['database']['manifestHash'] = self.gate['active_manifest_hash']
        self.assertEqual(current, old)
        self.assertEqual((self.runtime / 'operator.json').read_bytes(), original)

    def test_package_traversal_is_rejected(self):
        self.snapshot()
        with self.assertRaises(RuntimeError): self.release.activate('../outside')

    def test_build_input_change_cannot_be_described_as_successful_candidate(self):
        self.assertTrue(hasattr(self.release, 'capture_inputs'), 'before-build provenance checkpoint missing')
        self.snapshot()
        self.release.capture_inputs('2' * 40)
        self.jar.write_bytes(b'new jar')
        (self.root / self.m.SOURCE_FILES[0]).write_text('source changed during build')
        with self.assertRaises(RuntimeError): self.release.describe('2' * 40, {'jar': 0, 'spa': 0})

    def test_provenance_rejects_modified_and_untracked_inputs_in_temporary_git_repository(self):
        # Real Git diff/untracked behavior; no application processes or builds.
        (self.root / '.gitignore').write_text('private/\nbackend/target/\napps/workbench/dist/\n')
        hooks = self.root / 'empty-hooks'
        hooks.mkdir()
        def git(*args):
            result = subprocess.run(['git', '-c', 'core.autocrlf=false', '-c', 'commit.gpgsign=false',
                '-c', 'user.name=Release Test', '-c', 'user.email=release-test@example.invalid',
                '-c', 'core.hooksPath=' + str(hooks), *args], cwd=self.root, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            return result.stdout.strip()
        git('init')
        git('add', '--all')
        git('commit', '-m', 'synthetic release input baseline')
        commit = git('rev-parse', 'HEAD')
        boundary = self.m.RuntimeBoundary(SimpleNamespace(ROOT=self.root))
        boundary.provenance(commit)
        for name in REVIEW_INPUTS:
            with self.subTest(modified=name):
                path = self.root / name
                original = path.read_bytes()
                try:
                    path.write_bytes(b'changed build input not in recorded commit')
                    with self.assertRaisesRegex(RuntimeError, 'source commit'):
                        boundary.provenance(commit)
                finally:
                    path.write_bytes(original)
        for name in ('backend/src/generated/jooq/Untracked.java', '.mvn/jvm.config'):
            with self.subTest(untracked=name):
                path = self.root / name
                path.write_text('untracked build input')
                try:
                    with self.assertRaisesRegex(RuntimeError, 'source commit'):
                        boundary.provenance(commit)
                finally:
                    path.unlink()
        boundary.provenance(commit)

    def test_captured_html_jooq_and_typescript_inputs_cannot_change_or_disappear_during_build(self):
        self.snapshot()
        for name in REVIEW_INPUTS:
            for mutation in ('change', 'remove'):
                with self.subTest(input=name, mutation=mutation):
                    self.release.capture_inputs('2' * 40)
                    path = self.root / name
                    original = path.read_bytes()
                    try:
                        if mutation == 'change': path.write_bytes(b'changed after capture')
                        else: path.unlink()
                        with self.assertRaises(RuntimeError):
                            self.release.describe('2' * 40, {'jar': 0, 'spa': 0})
                    finally:
                        path.write_bytes(original)

    def test_failed_cas_with_unchanged_old_gate_requires_explicit_abort_before_restaging(self):
        candidate = self.stage()
        self.boundary.cas = lambda *args: (_ for _ in ()).throw(RuntimeError('connection failed before CAS'))
        with self.assertRaises(RuntimeError): self.release.activate(candidate)
        with self.assertRaises(RuntimeError): self.release.recover('complete')
        self.release.recover('rollback')
        self.assertEqual(self.release.paths()['jar'].read_bytes(), b'old jar')
        self.assertEqual(self.gate['revision'], 7)

    def test_recovery_refuses_unknown_gate_and_preserves_partial_state(self):
        candidate = self.stage()
        self.boundary.cas = lambda *args: (_ for _ in ()).throw(RuntimeError('unknown result'))
        with self.assertRaises(RuntimeError): self.release.activate(candidate)
        self.gate['revision'] += 20
        with self.assertRaises(RuntimeError): self.release.recover('rollback')
        self.assertEqual(self.release.status()['observedGateRelation'], 'other')
        self.assertEqual((self.runtime / 'application.properties').read_bytes(), self.config)

    def test_current_verify_uses_original_manifest_and_only_verify_with_derived_operator(self):
        candidate = self.stage()
        self.release.activate(candidate)
        original = (self.runtime / 'operator.json').read_bytes()
        spec = importlib.util.spec_from_file_location('release_test_runner', MODULE.with_name('local_login.py'))
        runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(runner)
        runner.ROOT, runner.RUNTIME = self.root, self.runtime
        invocations = []
        def external(args, label):
            invocations.append([str(value) for value in args])
            return (b'09:00:00.000 [main] INFO org.jooq.Constants -- \n'
                    b'@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@\n'
                    b'@@  jOOQ synthetic startup banner  @@\n'
                    b'09:00:00.010 [main] INFO org.jooq.impl.DefaultExecuteContext.logVersionSupport -- '
                    b'Version : Database version is supported by dialect POSTGRES: 18.0\n'
                    b'{"mode":"VERIFIED_ORIGINAL","plannedDelta":{}}\n')
        runner.run = external
        captured = io.StringIO()
        with patch.dict(sys.modules, {'local_release': self.m, 'release_test_runner': runner}), patch.object(self.m, 'RuntimeBoundary', return_value=self.boundary), redirect_stdout(captured):
            try:
                runner.release_operation('bootstrap-verify-current-release', [])
            except ValueError:
                self.fail('successful verifier result after jOOQ startup logs was rejected')
        self.assertEqual(captured.getvalue(), 'VERIFIED_ORIGINAL with current release expectations; original operator preserved\n')
        self.assertEqual(len(invocations), 1)
        self.assertEqual(invocations[0][-3:], ['verify', str(self.runtime / 'operator-current-release.json'), str(self.runtime / 'original-manifest.json')])
        self.assertEqual(invocations[0][3], str(self.runtime / 'releases' / candidate / 'app.jar'))
        self.assertEqual((self.runtime / 'operator.json').read_bytes(), original)

    def test_current_verify_child_failure_cannot_be_overridden_by_successful_stdout(self):
        candidate = self.stage()
        self.release.activate(candidate)
        spec = importlib.util.spec_from_file_location('release_test_runner', MODULE.with_name('local_login.py'))
        runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(runner)
        runner.ROOT, runner.RUNTIME = self.root, self.runtime
        child = SimpleNamespace(returncode=2, stdout=b'{"mode":"VERIFIED_ORIGINAL","plannedDelta":{}}',
                                stderr=b'synthetic diagnostic body must not be echoed')
        captured = io.StringIO()
        with patch.dict(sys.modules, {'local_release': self.m, 'release_test_runner': runner}), patch.object(self.m, 'RuntimeBoundary', return_value=self.boundary), patch.object(runner.subprocess, 'run', return_value=child), redirect_stdout(captured):
            with self.assertRaises(RuntimeError):
                runner.release_operation('bootstrap-verify-current-release', [])
        self.assertEqual(captured.getvalue(), 'bootstrap-verify-current-release: exit 2\n')

    def test_original_wrapper_cannot_overwrite_operator_after_snapshot(self):
        self.snapshot()
        spec = importlib.util.spec_from_file_location('release_test_runner', MODULE.with_name('local_login.py'))
        runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(runner)
        runner.RUNTIME = self.runtime
        before = (self.runtime / 'operator.json').read_bytes()
        with self.assertRaises(RuntimeError): runner.bootstrap_config()
        self.assertEqual((self.runtime / 'operator.json').read_bytes(), before)

    def test_rollback_pins_bridge_host_and_never_reads_candidate_assets(self):
        candidate = self.stage()
        self.release.activate(candidate)
        self.release.rollback()
        paths = self.release.paths()
        self.assertEqual(paths['server'].read_bytes(), b'old server')
        self.assertEqual((paths['dist'] / 'index.html').read_bytes(), b'old spa')
        self.assertFalse((paths['dist'] / 'assets/app.js').exists())
        self.assertTrue((paths['server'].parent / 'legacy-server.mjs').exists(), 'historical host bytes missing')
        self.assertEqual((paths['server'].parent / 'legacy-server.mjs').read_bytes(), b'historical hardcoded server')

    def test_owner_cas_checks_complete_old_gate_and_asserts_exactly_one_row(self):
        self.assertTrue(hasattr(self.m, 'cas_sql'), 'migration Owner CAS boundary missing')
        desired = {**self.gate, 'revision': 8, 'active_manifest_hash': '2' * 64}
        (self.root / 'deploy/identity/identity-toolchain.lock.json').write_text(json.dumps({
            'identityDatabase': {'image': 'synthetic-postgres', 'digest': 'sha256:' + 'a' * 64}}))
        runner = SimpleNamespace(ROOT=self.root, RUNTIME=self.runtime, PREFIX='synthetic-local', require_protected_runtime=lambda: None)
        boundary = self.m.RuntimeBoundary(runner)
        requests = []
        def execute(args, **kwargs):
            requests.append((args, kwargs['input'].decode()))
            return SimpleNamespace(returncode=0, stdout=b'RELEASE_CAS_ONE', stderr=b'')
        with patch.object(self.m.subprocess, 'run', side_effect=execute):
            boundary.cas(self.gate, desired, self.schema)
        args, sql = requests[0]
        self.assertIn('-i', args)
        self.assertIn('--pull=never', args)
        self.assertIn('synthetic-postgres@sha256:' + 'a' * 64, args)
        self.assertIn('sslmode=verify-full', args[-1])
        for field in self.gate:
            self.assertIn(field, sql)
        self.assertIn('law_schema_migrator', sql)
        self.assertIn('ROW_COUNT', sql)
        self.assertIn('affected <> 1', sql)
        with patch.object(self.m.subprocess, 'run', return_value=SimpleNamespace(returncode=0, stdout=b'0', stderr=b'')):
            with self.assertRaises(RuntimeError): boundary.cas(self.gate, desired, self.schema)

    def test_process_ownership_rejects_suffix_match_wrong_executable_and_reused_pid(self):
        self.assertTrue(hasattr(self.m, 'owned_process'), 'exact process ownership boundary missing')
        expected = {'pid': 42, 'executable': 'C:/runtime/java.exe', 'args': ['-jar', 'C:/private/app.jar'], 'created': 'stamp'}
        actual = {'pid': 42, 'executable': 'C:/runtime/java.exe', 'args': ['-jar', 'C:/private/app.jar'], 'created': 'stamp'}
        self.m.owned_process(expected, actual)
        for field, value in [('args', ['-jar', 'C:/private/app.jar.evil']), ('executable', 'C:/other/java.exe'), ('created', 'reused')]:
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                self.m.owned_process(expected, {**actual, field: value})

    def test_registered_worker_is_owned_and_blocks_switch_until_it_exits(self):
        self.snapshot()
        sys.path.insert(0, str(MODULE.parent))
        import local_worker
        self.assertTrue(hasattr(local_worker, 'worker_command'), 'Worker command missing')
        runner = SimpleNamespace(ROOT=self.root, RUNTIME=self.runtime, JAVA=self.root/'java.exe',
            TOOLS=self.root/'tools', JAR=self.jar, require_protected_runtime=lambda:None)
        boundary = self.m.RuntimeBoundary(runner)
        package = self.release.paths()['jar'].parent
        commands = {**self.m.app_commands(runner, package), 'worker':local_worker.worker_command(runner, package)}
        registry = {name: {'pid': index, 'executable': command[0], 'args':command[1:], 'created':'stamp'}
                    for index,(name,command) in enumerate(commands.items(), 1)}
        (self.runtime/'processes.json').write_text(json.dumps(registry))
        boundary.process = lambda pid: registry['worker'] if pid == 3 else None
        with self.assertRaises(RuntimeError): boundary.stopped()
        boundary.process = lambda pid: None
        boundary.stopped()
        registry['worker']['args'][-1] = '--spring.main.web-application-type=servlet'
        (self.runtime/'processes.json').write_text(json.dumps(registry))
        with self.assertRaises(RuntimeError): boundary.stopped()

    @contextmanager
    def hidden_stop_children(self, count=1):
        children = []
        try:
            for _ in range(count):
                children.append(subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(90)'],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=subprocess.CREATE_NO_WINDOW))
            runner = SimpleNamespace(RUNTIME=self.runtime, require_protected_runtime=lambda: None)
            boundary = self.m.RuntimeBoundary(runner)
            # Substitute only the registry/ACL boundary. CIM reads, exact script
            # identity checks, termination and bounded waiting are real Windows I/O
            # against the newly created children, never the login environment.
            boundary.processes = lambda: [boundary.process(child.pid) for child in children]
            yield boundary, children
        finally:
            for child in children:
                if child.poll() is None: child.kill()
                child.wait(timeout=5)

    def stop_with_real_pwsh(self, boundary, preamble):
        real_run = subprocess.run
        def execute(args, **kwargs):
            return real_run([*args[:-1], preamble + args[-1]],
                            creationflags=subprocess.CREATE_NO_WINDOW, **kwargs)
        with patch.object(self.m.subprocess, 'run', side_effect=execute):
            boundary.stop()

    @unittest.skipUnless(os.name == 'nt', 'requires Windows process/CIM adapter')
    def test_stop_succeeds_for_both_owned_children_when_exit_precedes_wait(self):
        # Delay only after the real cmdlet terminates our child, deterministically
        # reproducing the vanished-PID gap before the production wait executes.
        after_stop_delay = """function Stop-Process {
          [CmdletBinding()] param([int]$Id,[System.Diagnostics.Process]$InputObject)
          if($PSBoundParameters.ContainsKey('InputObject')) {
            Microsoft.PowerShell.Management\\Stop-Process -InputObject $InputObject -ErrorAction Stop
          } else { Microsoft.PowerShell.Management\\Stop-Process -Id $Id -ErrorAction Stop }
          Start-Sleep -Milliseconds 150
        }; """
        with self.hidden_stop_children(2) as (boundary, children):
            try:
                self.stop_with_real_pwsh(boundary, after_stop_delay)
            except RuntimeError:
                self.assertIsNotNone(children[0].poll(), 'first child was not actually terminated')
                self.fail('successful termination before wait was reported as stop failure')
            self.assertTrue(all(child.poll() is not None for child in children))

    @unittest.skipUnless(os.name == 'nt', 'requires Windows process/CIM adapter')
    def test_stop_preserves_real_powershell_failure_when_owned_child_remains_alive(self):
        denied_stop = "function Stop-Process { throw 'synthetic stop denied' }; "
        with self.hidden_stop_children() as (boundary, children):
            with self.assertRaisesRegex(RuntimeError, 'owned process stop failed'):
                self.stop_with_real_pwsh(boundary, denied_stop)
            self.assertIsNone(children[0].poll())

    @unittest.skipUnless(os.name == 'nt', 'requires Windows process/CIM adapter')
    def test_stop_timeout_remains_failure_when_owned_child_does_not_exit(self):
        ignored_stop = "function Stop-Process { }; "
        with self.hidden_stop_children() as (boundary, children):
            with self.assertRaisesRegex(RuntimeError, 'owned process stop failed'):
                self.stop_with_real_pwsh(boundary, ignored_stop)
            self.assertIsNone(children[0].poll())

    @unittest.skipUnless(os.name == 'nt', 'requires Windows process/CIM adapter')
    def test_m1_pending_worker_launch_retains_marker_after_three_owned_children_are_stopped(self):
        with self.hidden_stop_children(3) as (boundary, children):
            marker = self.runtime/'worker-start.pending'
            marker.write_text('synthetic interrupted launch')
            with self.assertRaisesRegex(RuntimeError,'interrupted process launch'):
                boundary.stop()
            self.assertTrue(all(child.poll() is not None for child in children))
            self.assertTrue(marker.exists(), 'uncertain launch marker must remain for explicit reconciliation')


if __name__ == '__main__':
    unittest.main()
