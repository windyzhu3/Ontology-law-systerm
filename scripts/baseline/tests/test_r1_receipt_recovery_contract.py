from pathlib import Path
import importlib
import importlib.util
import unittest
import copy
import shutil
import tempfile

import yaml

ROOT = Path(__file__).resolve().parents[3]
ADR = 'docs/adr/ADR-0013-r1-command-receipt-recovery.md'
API = 'contracts/openapi/ontology-law-api.yaml'
BASELINE = 'docs/baseline/CURRENT-MVP-BASELINE.md'
HTTP = 'docs/contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md'
COMMAND = 'docs/contracts/r1/R1-COMMAND-POLICY-EVENT-CONTRACT.md'
WORKBENCH = 'docs/contracts/r1/R1-WORKBENCH-PRESENTATION-CONTRACT.md'
RUNTIME = 'database/schema-contract-52-plus-2/docs/runtime-validation-contract.md'
LEDGER = 'docs/progress/MVP-DELIVERY-LEDGER.md'
DESIGN = 'docs/superpowers/specs/2026-09-05-r1-business-closure-alignment-design.md'
PLAN = 'docs/superpowers/plans/2026-09-05-r1-business-closure-plan.md'
ACTIVE_FILES = (ADR, API, BASELINE, HTTP, COMMAND, WORKBENCH, RUNTIME, LEDGER, DESIGN, PLAN)
RECEIPT_PATH = '/api/v1/commands/{commandId}/receipt'


def validate(root: Path) -> list[str]:
    name = 'scripts.baseline.r1_receipt_recovery_contract'
    if importlib.util.find_spec(name) is None:
        return ['R1 receipt recovery validator missing']
    return importlib.import_module(name).validate(root)


class R1ReceiptRecoveryContractTest(unittest.TestCase):
    def setUp(self):
        self.mutation_root = None

    def copy_valid_repository(self, root):
        for relative in ACTIVE_FILES:
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, target)
        self.assertEqual([], validate(root), 'valid copied control')

    def assert_mutation_rejected(self, relative, mutate):
        if self.mutation_root is None:
            directory = tempfile.TemporaryDirectory()
            self.addCleanup(directory.cleanup)
            self.mutation_root = Path(directory.name)
            self.copy_valid_repository(self.mutation_root)
        root = self.mutation_root
        self.assertEqual([], validate(root), 'valid control before each mutation')
        path = root / relative
        original = path.read_text(encoding='utf-8')
        altered = mutate(original)
        self.assertNotEqual(original, altered, 'mutation must change its target')
        try:
            path.write_text(altered, encoding='utf-8')
            self.assertTrue(validate(root), 'mutated contract must fail closed')
        finally:
            path.write_text(original, encoding='utf-8')
        self.assertEqual([], validate(root), 'restored valid control')

    def test_approved_recovery_contract_is_active(self):
        self.assertEqual([], validate(ROOT))

    def test_each_normative_registry_row_is_closed_required_and_unique(self):
        rows = [line for line in (ROOT / ADR).read_text(encoding='utf-8').splitlines()
                if line.startswith('| R1_')]
        self.assertGreater(len(rows), 70)
        # Every security rule must reject deletion, substitution and duplicate authority.
        # Fixtures come from the accepted artifact, not the validator's allowlist.
        for row in rows:
            for operation in ('remove', 'unknown-value', 'duplicate', 'unknown-profile', 'unknown-key'):
                with self.subTest(row=row, operation=operation):
                    def mutate(text):
                        if operation == 'remove':
                            replacement = ''
                        elif operation == 'duplicate':
                            replacement = row + '\n' + row
                        elif operation == 'unknown-profile':
                            replacement = row.replace('| R1_', '| UNKNOWN_R1_', 1)
                        elif operation == 'unknown-key':
                            cells = row.split(' | ')
                            cells[1] = 'unknownBindingField'
                            replacement = ' | '.join(cells)
                        else:
                            replacement = row.rsplit(' | ', 1)[0] + ' | UNAPPROVED |'
                        return text.replace(row, replacement, 1)
                    self.assert_mutation_rejected(ADR, mutate)

    def test_security_regressions_cannot_replace_approved_rules(self):
        cases = (
            ('R1_RECEIPT_RECOVERY_METADATA_LOOKUP_V1', 'UNRESTRICTED_AUDIT_LOOKUP'),
            ('draft=NULL_OR_ORIGINAL_EXACT_SELECTOR', 'draft=CURRENT_SELECTOR_ONLY'),
            ('evidence.evidence_submission', 'evidence.unknown'),
            ('ORIGINAL_RFC8785_JCS_UTF8_SHA256_EQUALS_SLOT_COMMAND_SCOPE_DIGEST', 'TRUST_SCOPE'),
            ('NULL_SAFE_EXACT_ON_BEHALF_PAIR', 'ANY_ON_BEHALF'),
            ('CURRENT_TRUSTED_EXACT_ACCOUNT_AND_SERVICE_BINDING_NOT_GENERIC_SOURCE_GRANT', 'GENERIC_SOURCE_GRANT'),
            ('CURRENT_POLICY_ORGANIZATION_EQUALS_ORIGINAL_AUDIT_SUBJECT_TYPE_ID_REVISION_AND_SCOPE_ORGANIZATION_ID', 'CURRENT_POLICY_NEW_ORGANIZATION'),
            ('TASK_LEAD_SUBMISSION_BINDING_DENY', 'TASK_DENY_ONLY'),
            ('NO_HISTORICAL_ALLOW_OR_GRANT', 'HISTORICAL_ALLOW'),
            ('NO_HANDLER_VALIDATE_BEFORE_WORK', 'RERUN_HANDLER_VALIDATE_BEFORE_WORK'),
            ('COMMIT_ACK_THEN_HTTP_SERIALIZATION', 'HTTP_SERIALIZATION_THEN_COMMIT_ACK'),
            ('result_fact_revision,result_fact_hash', 'result_fact_hash'),
            ('ALL_CURRENT_PRIMARY_AND_EXTRA_SUBJECT_DECISIONS', 'PRIMARY_SUBJECT_ONLY'),
            ('READ_AUDIT_PLUS_1_ALL_OTHER_DELTA_0', 'RECEIPT_PLUS_1'),
            ('READ_AUDIT_MAY_BE_COMMITTED_NO_CERTAIN_ZERO_DELTA', 'DELTA_0'),
            ('ALL_RESPONSES_NO_STORE_NO_SUCCESS_ETAG_NO_304', 'CACHE_ETAG_304'),
            ('NO_READ_AUDIT_METADATA_REPAIR_BUSINESS_REEXECUTION_RECEIPT_REWRITE', 'REPLAY_READ_AUDIT_PLUS_1'),
            ('ORIGINAL_MTLS_REQUEST_AND_KEY_ONLY_NO_PUBLIC_OR_NEW_MTLS_RECEIPT_GET', 'PUBLIC_GET_INTERNAL_RECOVERY'),
            ('NOT_COMMAND_FAILURE_NO_AUTOMATIC_NEW_KEY', 'FAILED_OUTCOME_AUTOMATIC_NEW_KEY'),
            ('NO_REWRITE_BACKFILL_DELETE_TEXT_PARSE', 'PARSE_AND_BACKFILL_OLD_TEXT'),
        )
        for old, new in cases:
            with self.subTest(rule=old):
                self.assert_mutation_rejected(ADR, lambda text: text.replace(old, new))

    def test_fenced_commented_missing_and_duplicate_authority_cannot_activate(self):
        for wrap in (lambda text: '```md\n' + text + '\n```',
                     lambda text: '<!--\n' + text + '\n-->',
                     lambda text: text + '\n' + text,
                     lambda text: text.replace('Status: Accepted', 'Status: Proposed')):
            self.assert_mutation_rejected(ADR, wrap)

    def test_missing_artifacts_and_malformed_yaml_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_valid_repository(root)
            for relative in ACTIVE_FILES:
                with self.subTest(relative=relative):
                    path = root / relative
                    original = path.read_bytes()
                    self.assertEqual([], validate(root))
                    path.unlink()
                    try:
                        self.assertTrue(validate(root))
                    finally:
                        path.write_bytes(original)
                    self.assertEqual([], validate(root))
        for changed in ('paths: [', 'null', 'paths: {}\npaths: {}'):
            self.assert_mutation_rejected(API, lambda text: changed)

    def test_active_versions_and_resolved_authority_pointers_are_required(self):
        for relative, old in ((BASELINE, 'Baseline ID: MVP-2026-09-08.3'),
                              (HTTP, 'Contract ID: R1-HTTP-V1.5'),
                              (COMMAND, 'Contract ID: R1-COMMAND-POLICY-EVENT-V1.3')):
            for new in ('UNKNOWN_ACTIVE_VERSION', old.replace('09-08.3', '09-07.1').replace('V1.5', 'V1.2').replace('EVENT-V1.3', 'EVENT-V1.1')):
                with self.subTest(relative=relative, new=new):
                    self.assert_mutation_rejected(relative, lambda text: text.replace(old, new))
        for relative in (BASELINE, HTTP, COMMAND, WORKBENCH, RUNTIME, DESIGN, PLAN):
            with self.subTest(pointer=relative):
                self.assert_mutation_rejected(relative, lambda text: text.replace('ADR-0013-r1-command-receipt-recovery.md', 'missing-ADR.md'))

    def test_openapi_transport_is_structural_and_frozen(self):
        mutations = (
            lambda d: d['paths'].update({'/api/v1/new-receipts': {'get': copy.deepcopy(d['paths'][RECEIPT_PATH]['get'])}}),
            lambda d: d['paths'][RECEIPT_PATH]['get'].update(security=[{'internalMutualTls': []}]),
            lambda d: d['paths'][RECEIPT_PATH]['get']['parameters'].append({'name': 'scope', 'in': 'query'}),
            lambda d: d['paths'][RECEIPT_PATH]['get'].update(requestBody={'required': False}),
            lambda d: d['components']['schemas']['SuccessfulCommandReceipt']['properties'].update(recovery={'type': 'object'}),
            lambda d: d['paths'][RECEIPT_PATH]['get']['x-error-codes'].append('UNKNOWN_RECEIPT'),
            lambda d: d['paths'][RECEIPT_PATH]['get']['responses'].update({'304': {'description': 'Cached'}}),
            lambda d: d['paths'][RECEIPT_PATH]['get'].update(description='Historical authorization allows receipt recovery.'),
        )
        for mutate in mutations:
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_valid_repository(root)
                path = root / API
                original = yaml.safe_load(path.read_text(encoding='utf-8'))
                changed = copy.deepcopy(original)
                mutate(changed)
                self.assertNotEqual(original, changed)
                path.write_text(yaml.safe_dump(changed, sort_keys=False), encoding='utf-8')
                self.assertTrue(validate(root))
                path.write_text(yaml.safe_dump(original, sort_keys=True), encoding='utf-8')
                self.assertEqual([], validate(root), 'YAML formatting is not contract semantics')

    def test_physical_grant_and_static_delivery_expansion_is_rejected(self):
        for old, new in (('APPLICATION_TABLES_52', 'APPLICATION_TABLES_53'),
                         ('FIELDS_GRANTS_JOOQ_UNCHANGED', 'EXPAND_GRANTS'),
                         ('STATIC_FROZEN_ONLY_ORIGINAL_TASK8', 'RUNTIME_VERIFIED_TASK8')):
            self.assert_mutation_rejected(ADR, lambda text: text.replace(old, new))
        self.assert_mutation_rejected(LEDGER, lambda text: '\n'.join(
            line.replace(' | FROZEN | ', ' | IMPLEMENTED | ') if line.startswith('| R1-RECEIPT-RECOVERY-CONTRACT |') else line
            for line in text.splitlines()))

    def test_baseline_entry_point_runs_recovery_validation(self):
        from scripts.baseline.tests.test_verify_baseline import VerifyBaselineTest
        from scripts.baseline.verify_baseline import verify_repository
        fixture = VerifyBaselineTest()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture.create_valid_repository(root)
            self.assertEqual([], verify_repository(root), 'real verifier valid control')
            path = root / ADR
            original = path.read_text(encoding='utf-8')
            changed = original.replace('R1_RECEIPT_RECOVERY_METADATA_LOOKUP_V1', 'UNRESTRICTED_AUDIT_LOOKUP')
            self.assertNotEqual(original, changed)
            path.write_text(changed, encoding='utf-8')
            findings = verify_repository(root)
            self.assertTrue(any('receipt recovery' in finding for finding in findings), findings)
            result = fixture.run_cli(root)
            self.assertEqual(1, result.returncode)
            self.assertEqual('', result.stderr)
            self.assertEqual(findings, result.stdout.strip().splitlines())
            path.write_text(original, encoding='utf-8')
            self.assertEqual([], verify_repository(root))
