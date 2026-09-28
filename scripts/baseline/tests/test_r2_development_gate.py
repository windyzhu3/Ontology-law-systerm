import io
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

from scripts.baseline import verify_baseline as gate


PLAN = Path('docs/superpowers/plans/2026-09-13-r2-complete-sales-mvp-plan.md')
PROFILE = 'R2_DEVELOPMENT_ADMISSION_V1'
SCOPE = 'R2-COMPLETE-SALES-MVP-2026-09-13'


class R2DevelopmentGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for path, text in {
            gate.CANONICAL_BASELINE: (
                f'Baseline ID: {gate.CANONICAL_BASELINE_ID}\n'
                f'R2 development profile: {PROFILE}\n'
                f'R2 development plan: {PLAN.as_posix()}\n'
                f'R2 scope ID: {SCOPE}\n'
                'R1 acceptance: PAUSED; R2 release acceptance: NOT_GRANTED\n'
            ),
            PLAN: f'R2-Scope-ID: {SCOPE}\nR2-Scope: APPROVED\nR1-Acceptance: PAUSED\n',
        }.items():
            (self.root / path).parent.mkdir(parents=True, exist_ok=True)
            (self.root / path).write_text(text, encoding='utf-8')

    def run_gate(self, *flags, structural=False):
        findings = [gate.CategorizedFinding('R1 E2E not verified', gate.FindingCategory.R2_READINESS)]
        if structural:
            findings.append(gate.CategorizedFinding('broken baseline', gate.FindingCategory.STRUCTURAL))
        output = io.StringIO()
        with patch.object(gate, '_verify_repository_result', return_value=gate.VerificationResult(tuple(findings))), redirect_stdout(output):
            code = gate.main([*flags, str(self.root)])
        return code, output.getvalue()

    def test_schema_successor_is_enabled_only_by_development_flag(self):
        for flags, allowed in (([],False),(['--strict-r2'],False),(['--r2-development'],True)):
            with patch.object(gate, '_verify_repository_result', return_value=gate.VerificationResult(())) as verify, redirect_stdout(io.StringIO()):
                gate.main([*flags,str(self.root)])
                verify.assert_called_once_with(self.root.resolve(),allow_r2_schema=allowed)

    def test_development_admits_confirmed_scope_without_acceptance(self):
        code, output = self.run_gate('--r2-development')
        self.assertEqual(0, code)
        self.assertIn('R2 development admission: PASS', output)
        self.assertIn('R2 readiness: BLOCKED', output)
        self.assertIn('R1 acceptance: PAUSED', output)

    def test_development_keeps_structural_failures_fatal(self):
        code, output = self.run_gate('--r2-development', structural=True)
        self.assertEqual(1, code)
        self.assertNotIn('R2 development admission: PASS', output)

    def test_development_requires_every_profile_and_confirmation_marker(self):
        for path in (gate.CANONICAL_BASELINE, PLAN):
            original = (self.root / path).read_text(encoding='utf-8')
            for line in original.splitlines():
                with self.subTest(path=path, missing=line):
                    (self.root / path).write_text(original.replace(line, ''), encoding='utf-8')
                    self.assertEqual(1, self.run_gate('--r2-development')[0])
            (self.root / path).write_text(original, encoding='utf-8')

    def test_strict_acceptance_is_still_blocked(self):
        self.assertEqual(1, self.run_gate('--strict-r2')[0])

    def test_explanatory_prose_is_not_an_approval_guard(self):
        with (self.root / PLAN).open('a', encoding='utf-8') as plan:
            plan.write('开发已开始；此处说明可随进度更新。\n')
        self.assertEqual(0, self.run_gate('--r2-development')[0])

    def test_unapproved_wrong_or_duplicate_metadata_fails_closed(self):
        path = self.root / PLAN
        original = path.read_text(encoding='utf-8')
        for modified in (
            original.replace('APPROVED', 'DRAFT'),
            original.replace(SCOPE, 'R2-OTHER-SCOPE'),
            original.replace('PAUSED', 'PASSED'),
            original + 'R2-Scope: DRAFT\n',
            original.replace('R2-Scope: APPROVED', 'R2-Scope: APPROVED_WITHOUT_AUTHORITY'),
        ):
            with self.subTest(modified=modified):
                path.write_text(modified, encoding='utf-8')
                self.assertEqual(1, self.run_gate('--r2-development')[0])

    def test_missing_or_invalid_utf8_plan_fails_closed(self):
        (self.root / PLAN).unlink()
        self.assertEqual(1, self.run_gate('--r2-development')[0])
        (self.root / PLAN).write_bytes(b'\xff')
        self.assertEqual(1, self.run_gate('--r2-development')[0])

    def test_development_and_acceptance_cannot_be_combined(self):
        with self.assertRaises(SystemExit) as error:
            self.run_gate('--strict-r2', '--r2-development')
        self.assertEqual(2, error.exception.code)
