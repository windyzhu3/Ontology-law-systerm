"""Fixed configuration release tests; all files and database outcomes are synthetic."""
from contextlib import redirect_stdout
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import unittest
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import test_local_release as release_fixture

MODULE = Path(__file__).resolve().parents[1] / 'local_source_release.py'
TENANT = '00000000-0000-0000-0000-000000000001'
MANUAL = (b'ols.api.sources[LOCAL_SYNTHETIC].assignment-mode=MANUAL\n'
          b'ols.api.sources[LOCAL_SYNTHETIC].routing-organization-root-codes[0]=ROOT\n'
          b'ols.api.sources[LOCAL_SYNTHETIC].routing-supervisor-root-code=ROOT\n'
          b'ols.api.sources[LOCAL_SYNTHETIC].source-intake-root-code=ROOT\n'
          b'ols.api.sources[LOCAL_SYNTHETIC].business-timezone=Asia/Shanghai\n')
AUTO = MANUAL.replace(b'LOCAL_SYNTHETIC', b'LOCAL_SYNTHETIC_AUTO').replace(b'=MANUAL', b'=AUTOMATIC')


class SourceTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(MODULE.exists(), 'fixed local source release module is missing')
        self.s = __import__('local_source_release')
        release_fixture.ReleaseTest.setUp(self)
        self.config = self.config.replace(b'secret=unchanged', b'ols.api.cursor-key=synthetic-key') + MANUAL
        self.config += b'ols.api.registrations[0].source-account-codes[0]=LOCAL_SYNTHETIC\n'
        (self.runtime / 'application.properties').write_bytes(self.config)
        self.deployment['tenantId'] = TENANT
        (self.runtime / 'deployment.json').write_text(json.dumps(self.deployment))
        operator = self.m.read_json(self.runtime / 'operator.json')
        operator['tenantId'] = TENANT
        (self.runtime / 'operator.json').write_text(json.dumps(operator))
        self.facts = False
        def facts(tenant):
            self.assertEqual(tenant, TENANT)
            self.assertEqual(self.events[-1], 'stopped')
            self.events.append('source-query')
            return self.facts
        self.boundary.source_facts = facts
        self.boundary.operator_provenance = lambda commit: None

    snapshot = release_fixture.ReleaseTest.snapshot
    candidate = release_fixture.ReleaseTest.candidate

    def parent(self):
        self.snapshot()
        name = self.release.stage(self.candidate())
        self.release.activate(name)
        return self.release.current()

    def staged(self):
        old = self.parent()
        return old, self.s.stage_local_auto_source(self.release, '3' * 40)

    def test_fixed_delta_preserves_existing_bytes_and_does_not_expand_service(self):
        after = self.s.add_fixed_auto_source(self.config)
        self.assertEqual(after, self.config + AUTO)
        self.assertEqual(self.s.AUTO_SOURCE_LINES, AUTO)
        self.assertNotIn(b'source-account-codes[1]', after)
        with self.assertRaises(RuntimeError): self.s.add_fixed_auto_source(after)

    def test_ambiguous_unknown_conflicting_and_wrong_manual_inputs_are_rejected(self):
        for change in [lambda b: b + b'unknown.input=true\n', lambda b: b + MANUAL,
                       lambda b: b.replace(b'=ROOT', b'=OTHER'),
                       lambda b: b.replace(b'Asia/Shanghai', b'UTC'),
                       lambda b: b + b'ols.api.sources[OTHER].assignment-mode=AUTOMATIC\n',
                       lambda b: b + b'ols.api.registrations[0].source-account-codes[1]=OTHER\n',
                       lambda b: b.replace(b'assignment-mode=', b'assignment-mode:'),
                       lambda b: b.replace(b'LOCAL_SYNTHETIC]', b'LOCAL_SYNTHETIC\\u005d'),
                       lambda b: b[:-1]]:
            with self.subTest(change=change), self.assertRaises(RuntimeError):
                self.s.add_fixed_auto_source(change(self.config))

    def test_stage_uses_only_saved_bytes_and_inherits_binary_provenance(self):
        old = self.parent()
        package = self.release.package(old['id'])
        before = self.m.files(package)
        self.jar.write_bytes(b'unrelated live target')
        (self.dist / 'index.html').write_bytes(b'unrelated live dist')
        (self.root / 'deploy/local-login/server.mjs').write_bytes(b'unrelated live host')
        name = self.s.stage_local_auto_source(self.release, '3' * 40)
        target = self.release.package(name)
        manifest = self.m.read_json(target / 'release-manifest.json')
        parent = self.release.load(old['id'])
        self.assertEqual(manifest['profile'], 'LOCAL_SYNTHETIC_SOURCE_RELEASE_V1')
        self.assertEqual(manifest['parent'], {'id': old['id'], 'descriptorHash': old['descriptorHash'],
                                          'manifestHash': old['gate']['active_manifest_hash']})
        self.assertEqual(manifest['operatorCommit'], '3' * 40)
        self.assertEqual(manifest['binaryProvenance'], parent['provenance'])
        self.assertEqual(manifest['originalConfigSha256'], before['application.properties'])
        self.assertEqual(manifest['sourceDelta'], AUTO.decode().splitlines())
        self.assertNotIn('buildExitCodes', manifest)
        for file in ['app.jar', 'dist/index.html', 'dist/assets/app.js', 'server.mjs']:
            self.assertEqual((target / file).read_bytes(), (package / file).read_bytes())
        expected = self.m.change_config((package / 'application.properties').read_bytes() + AUTO,
                                       old['gate']['active_release_digest'], manifest_hash := self.m.digest(self.m.encoded(manifest)))
        self.assertEqual((target / 'application.properties').read_bytes(), expected)
        self.assertEqual(self.m.read_json(target / 'deployment.json')['manifestHash'], manifest_hash)
        self.assertEqual(self.release.current(), old)
        self.assertEqual(self.m.files(package), before)

    def test_stage_rejects_dirty_operator_drift_and_parent_change_without_activation(self):
        for fault in ['operator', 'config', 'schema', 'material', 'parent']:
            with self.subTest(fault=fault):
                self.setUp()
                old = self.parent()
                if fault == 'operator': self.boundary.operator_provenance = lambda c: (_ for _ in ()).throw(RuntimeError('dirty'))
                if fault == 'config': (self.runtime / 'application.properties').write_bytes(b'drift')
                if fault == 'schema': self.schema['schema'] = 'drift'
                if fault == 'material': (self.runtime / 'operator.json').write_bytes(b'drift')
                if fault == 'parent':
                    original = self.release.stable
                    def changed(state):
                        result = original(state)
                        self.m.atomic(self.release.pointer, {**state, 'descriptorHash': 'f' * 64})
                        return result
                    self.release.stable = changed
                count = self.events.count('cas')
                with self.assertRaises(RuntimeError): self.s.stage_local_auto_source(self.release, '3' * 40)
                self.assertEqual(self.events.count('cas'), count)

    def test_parent_config_gate_mismatch_is_not_carried_into_new_source_package(self):
        old = self.parent()
        package = self.release.package(old['id'])
        config = (package / 'application.properties').read_bytes().replace(
            old['gate']['active_manifest_hash'].encode(), b'f' * 64)
        (package / 'application.properties').write_bytes(config)
        (self.runtime / 'application.properties').write_bytes(config)
        record = self.m.read_json(package / 'release.json')
        record['files']['application.properties'] = self.m.digest(config)
        self.m.atomic(package / 'release.json', record)
        self.m.atomic(self.release.pointer, {**old, 'descriptorHash': self.m.digest(self.m.encoded(record))})
        before = self.m.files(self.runtime)
        with self.assertRaises(RuntimeError): self.s.stage_local_auto_source(self.release, '3' * 40)
        self.assertEqual(self.m.files(self.runtime), before)

    def test_linked_saved_artifact_is_rejected_before_staging(self):
        old = self.parent()
        jar = self.release.package(old['id']) / 'app.jar'
        is_link = Path.is_symlink
        with patch.object(Path, 'is_symlink', lambda path: path == jar or is_link(path)):
            with self.assertRaises(RuntimeError): self.s.stage_local_auto_source(self.release, '3' * 40)
        self.assertEqual(self.release.current(), old)

    def test_worker_must_stop_and_new_gate_and_rollback_restage_use_same_saved_binary(self):
        old, name = self.staged()
        with patch.object(self.boundary, 'stopped', side_effect=RuntimeError('Worker alive')):
            with self.assertRaises(RuntimeError): self.release.activate(name)
        self.assertEqual(self.release.current(), old)
        self.release.activate(name)
        self.assertEqual(self.release.paths()['release'], old['gate']['active_release_digest'])
        self.assertEqual(self.release.paths()['manifest'], self.release.load(name)['manifestHash'])
        self.release.rollback()
        self.assertEqual(self.release.current()['id'], old['id'])
        again = self.s.stage_local_auto_source(self.release, '3' * 40)
        self.assertNotEqual(again, name)
        self.release.activate(again)

    def test_any_source_fact_blocks_rollback_before_journal_pointer_or_cas_changes(self):
        old, name = self.staged()
        self.release.activate(name)
        self.facts = True
        before = self.m.files(self.runtime)
        calls = self.events.count('cas')
        with self.assertRaises(RuntimeError): self.release.rollback()
        self.assertEqual(self.m.files(self.runtime), before)
        self.assertEqual(self.events.count('cas'), calls)

    def test_forward_business_upgrade_preserving_auto_does_not_query_facts(self):
        old, name = self.staged()
        self.release.activate(name)
        self.facts = True
        next_id = self.release.stage(self.candidate())
        self.release.activate(next_id)
        self.assertNotIn('source-query', self.events)

    def test_lost_response_recovery_completion_keeps_auto_but_rollback_checks_facts(self):
        old, name = self.staged()
        cas = self.boundary.cas
        def lost(*args):
            cas(*args)
            raise RuntimeError('lost response')
        with patch.object(self.boundary, 'cas', side_effect=lost):
            with self.assertRaises(RuntimeError): self.release.activate(name)
        self.facts = True
        before = self.m.files(self.runtime)
        count = self.events.count('cas')
        with self.assertRaises(RuntimeError): self.release.recover('rollback')
        self.assertEqual(self.m.files(self.runtime), before)
        self.assertEqual(self.events.count('cas'), count)
        self.release.recover('complete')
        self.assertEqual(self.release.current()['id'], name)

    def test_rollback_recovery_after_reverse_cas_still_checks_source_facts(self):
        old, name = self.staged()
        self.release.activate(name)
        cas = self.boundary.cas
        def lost(*args):
            cas(*args)
            raise RuntimeError('lost')
        with patch.object(self.boundary, 'cas', side_effect=lost):
            with self.assertRaises(RuntimeError): self.release.rollback()
        self.facts = True
        before = self.m.files(self.runtime)
        with self.assertRaises(RuntimeError): self.release.recover('complete')
        self.assertEqual(self.m.files(self.runtime), before)

    def test_pending_reverse_recovery_guards_both_applied_and_unapplied_cas(self):
        for applied in [False, True]:
            with self.subTest(applied=applied):
                self.setUp()
                old, name = self.staged()
                with patch.object(self.release, 'install', side_effect=OSError('interrupted')):
                    with self.assertRaises(OSError): self.release.activate(name)
                cas = self.boundary.cas
                def lost(*args):
                    if applied: cas(*args)
                    raise RuntimeError('unknown outcome')
                with patch.object(self.boundary, 'cas', side_effect=lost):
                    with self.assertRaises(RuntimeError): self.release.recover('rollback')
                self.assertEqual(self.m.read_json(self.release.journal)['phase'], 'ROLLBACK_CAS_PENDING')
                self.facts = True
                before = self.m.files(self.runtime)
                count = self.events.count('cas')
                with self.assertRaises(RuntimeError): self.release.recover('rollback')
                self.assertEqual(self.m.files(self.runtime), before)
                self.assertEqual(self.events.count('cas'), count)
                self.facts = False
                self.release.recover('rollback')
                self.assertEqual(self.release.current()['id'], old['id'])

    def test_cas_never_applied_requires_explicit_rollback_and_no_auto_retry(self):
        old, name = self.staged()
        with patch.object(self.boundary, 'cas', side_effect=RuntimeError('not applied')):
            with self.assertRaises(RuntimeError): self.release.activate(name)
        before = self.m.files(self.runtime)
        with self.assertRaises(RuntimeError): self.release.recover('complete')
        self.assertEqual(self.m.files(self.runtime), before)
        self.release.recover('rollback')
        self.assertEqual(self.release.current()['id'], old['id'])

    def test_malformed_live_auto_and_owner_failures_block_recovery_without_writes(self):
        for bad in [b'ols.api.sources[LOCAL_SYNTHETIC_AUTO].assignment-mode=MANUAL\n',
                    b'ols.api.sources.local-synthetic-auto.assignment-mode=AUTOMATIC\n', AUTO + AUTO]:
            self.setUp()
            old, name = self.staged()
            with patch.object(self.release, 'install', side_effect=OSError('interrupted')):
                with self.assertRaises(OSError): self.release.activate(name)
            (self.runtime / 'application.properties').write_bytes(self.config + bad)
            before = self.m.files(self.runtime)
            with self.assertRaises(RuntimeError): self.release.recover('complete')
            self.assertEqual(self.m.files(self.runtime), before)
        self.setUp()
        old, name = self.staged()
        self.release.activate(name)
        self.boundary.source_facts = lambda t: (_ for _ in ()).throw(RuntimeError('Owner unavailable'))
        before = self.m.files(self.runtime)
        with self.assertRaises(RuntimeError): self.release.rollback()
        self.assertEqual(self.m.files(self.runtime), before)

    def test_readonly_owner_query_binds_original_tenant_and_all_lead_facts(self):
        boundary = self.m.RuntimeBoundary(SimpleNamespace(ROOT=self.root, RUNTIME=self.runtime,
                                                        require_protected_runtime=lambda: None))
        statements = []
        def sql(statement):
            statements.append(statement)
            return 't'
        boundary.sql = sql
        self.assertTrue(boundary.source_facts(TENANT))
        statement = statements[0]
        self.assertIn('BEGIN READ ONLY', statement)
        self.assertIn("source_account_code='LOCAL_SYNTHETIC_AUTO'", statement)
        self.assertIn("tenant_id='" + TENANT + "'::uuid", statement)
        self.assertIn('lead.lead', statement)
        self.assertNotIn('task.', statement)
        self.assertNotIn('UPDATE ', statement)
        with self.assertRaises(RuntimeError): boundary.source_facts('00000000-0000-0000-0000-000000000002')
        boundary.sql = lambda s: 'uncertain'
        with self.assertRaises(RuntimeError): boundary.source_facts(TENANT)

    def test_operator_commit_is_exact_head_and_clean_tracked_tooling(self):
        folder = self.root / 'deploy/local-login'
        for name in ['local_source_release.py', 'local_release.py', 'local_login.py', 'local_worker.py']:
            (folder / name).write_text('synthetic tool')
        def git(*args):
            return subprocess.run(['git', *args], cwd=self.root, capture_output=True, check=True).stdout.decode().strip()
        git('init', '-q')
        git('add', '.')
        git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'fixture')
        commit = git('rev-parse', 'HEAD')
        boundary = self.m.RuntimeBoundary(SimpleNamespace(ROOT=self.root))
        boundary.operator_provenance(commit)
        with self.assertRaises(RuntimeError): boundary.operator_provenance('0' * 40)
        (folder / 'local_source_release.py').write_text('dirty tool')
        with self.assertRaises(RuntimeError): boundary.operator_provenance(commit)
        (folder / 'local_source_release.py').write_text('synthetic tool')
        git('rm', '--cached', 'deploy/local-login/local_source_release.py')
        git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'untrack tool')
        with self.assertRaises(RuntimeError): boundary.operator_provenance(git('rev-parse', 'HEAD'))

    def test_named_command_only_stages_fixed_delta_and_rejects_extra_arguments(self):
        old = self.parent()
        spec = importlib.util.spec_from_file_location('source_test_runner', MODULE.with_name('local_login.py'))
        runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(runner)
        runner.ROOT, runner.RUNTIME = self.root, self.runtime
        with patch.dict(sys.modules, {'local_release': self.m, 'source_test_runner': runner}), \
                patch.object(self.m, 'RuntimeBoundary', return_value=self.boundary):
            for arguments in [[], ['3' * 40, 'OTHER'], ['HEAD']]:
                with self.assertRaises(RuntimeError): runner.release_operation('stage-local-auto-source', arguments)
            output = io.StringIO()
            with redirect_stdout(output): runner.release_operation('stage-local-auto-source', ['3' * 40])
        name = output.getvalue().strip().removeprefix('staged release: ')
        self.assertEqual(self.release.load(name)['kind'], 'controlled-local-source-release')
        self.assertEqual(self.release.current(), old)
        self.assertFalse((self.runtime / 'release-operation.lock').exists())


if __name__ == '__main__':
    unittest.main()
