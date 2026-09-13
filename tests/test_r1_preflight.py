from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[1]
DRIVER = ROOT / "scripts" / "ci" / "r1-preflight.sh"
WORKFLOW = ROOT / ".github" / "workflows" / "r1-vertical-slice.yml"

EXPECTED_CALLS = [
    "root | python3 scripts/baseline/verify_baseline.py",
    "schema | python3 generate.py --check",
    "schema | python3 -m unittest discover -s tests -v",
    "schema | python3 scripts/verify_generated_sql.py",
    "schema | python3 -m unittest discover -s runtime/tests -v",
    "schema | python3 runtime/verify_runtime.py validate-promoted-evidence",
    "schema | python3 runtime/verify_runtime.py verify --ci-only --runs 2 --evidence-dir ../../.artifacts/schema-runtime",
    "schema | python3 runtime/verify_runtime.py validate-ci-artifact",
    "root | mvnw -f backend/pom.xml verify -Pit",
    "root | generate-jooq.sh --check",
    "root | npm run openapi:check",
    "root | npm run typecheck",
    "root | npm test",
    "root | npm run build",
    "root | npm run test:e2e:offline",
]


class WorkflowLoader(yaml.BaseLoader):
    """Keep GitHub's `on` key as text instead of a YAML 1.1 boolean."""


class R1PreflightDriverTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.repository = Path(self.temporary.name)
        self.log = self.repository / "calls.log"
        self.bin = self.repository / "bin"
        self.bin.mkdir()
        (self.repository / ".preflight-test-root").write_text("bounded\n", encoding="utf-8")
        schema = self.repository / "database" / "schema-contract-52-plus-2"
        schema.mkdir(parents=True)
        (schema / ".preflight-test-schema").write_text("bounded\n", encoding="utf-8")

        if not DRIVER.is_file():
            self.fail("R1 preflight driver is missing")
        copied_driver = self.repository / "scripts" / "ci" / "r1-preflight.sh"
        copied_driver.parent.mkdir(parents=True)
        shutil.copy2(DRIVER, copied_driver)

        self._write_double(self.bin / "python3", "python3")
        self._write_double(self.bin / "npm", "npm")
        self._write_double(self.repository / "mvnw", "mvnw")
        self._write_double(
            self.repository / "backend" / "scripts" / "generate-jooq.sh",
            "generate-jooq.sh",
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _write_double(self, path: Path, command_name: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            textwrap.dedent(
                f"""\
                #!/usr/bin/env bash
                if [[ -f "$PWD/.preflight-test-root" ]]; then
                  location=root
                elif [[ -f "$PWD/.preflight-test-schema" ]]; then
                  location=schema
                else
                  exit 97
                fi
                invocation='{command_name}'
                if [[ -n "$*" ]]; then invocation="$invocation $*"; fi
                printf '%s | %s\\n' "$location" "$invocation" >> "$R1_PREFLIGHT_TEST_LOG"
                if [[ "${{R1_PREFLIGHT_TEST_FAIL:-}}" == "$invocation" ]]; then
                  exit "${{R1_PREFLIGHT_TEST_FAIL_CODE:-1}}"
                fi
                """
            ),
            encoding="utf-8",
            newline="\n",
        )
        path.chmod(0o755)

    def _run_driver(self, *, fail: str | None = None, fail_code: int = 1) -> subprocess.CompletedProcess[str]:
        git_bash = Path(r"C:\Program Files\Git\bin\bash.exe")
        bash = str(git_bash) if os.name == "nt" else shutil.which("bash")
        if not bash:
            self.fail("bash is required to exercise the R1 preflight driver")
        environment = os.environ.copy()
        environment["PATH"] = os.pathsep.join((str(self.bin), environment["PATH"]))
        environment["R1_PREFLIGHT_TEST_LOG"] = str(self.log)
        environment.pop("NO_COLOR", None)
        environment.pop("FORCE_COLOR", None)
        if fail is not None:
            environment["R1_PREFLIGHT_TEST_FAIL"] = fail
            environment["R1_PREFLIGHT_TEST_FAIL_CODE"] = str(fail_code)
        return subprocess.run(
            [bash, "scripts/ci/r1-preflight.sh"],
            cwd=self.repository,
            env=environment,
            text=True,
            encoding="utf-8",
            capture_output=True,
            check=False,
        )

    def _calls(self) -> list[str]:
        return self.log.read_text(encoding="utf-8").splitlines()

    def test_success_runs_the_fixed_bounded_order_and_claims_only_preflight(self) -> None:
        """Break caught: a stage is skipped, reordered, widened, or reported as acceptance."""
        result = self._run_driver()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(EXPECTED_CALLS, self._calls())
        self.assertIn("R1 preflight passed (not runtime acceptance)", result.stdout)
        self.assertNotIn("RUNTIME_VERIFIED", result.stdout)
        self.assertNotIn("R1 runtime accepted", result.stdout)

    def test_intermediate_failure_preserves_exit_code_and_stops_later_stages(self) -> None:
        """Break caught: a failed backend gate is masked or later consumers still run."""
        result = self._run_driver(fail="mvnw -f backend/pom.xml verify -Pit", fail_code=23)

        self.assertEqual(23, result.returncode)
        self.assertEqual(EXPECTED_CALLS[:9], self._calls())
        self.assertNotIn("OpenAPI generation drift", result.stdout)
        self.assertNotIn("R1 preflight passed", result.stdout)

    def test_schema_failure_preserves_exit_code_and_stops_before_runtime(self) -> None:
        """Break caught: a schema import failure is masked or runtime verification starts."""
        result = self._run_driver(
            fail="python3 -m unittest discover -s runtime/tests -v",
            fail_code=19,
        )

        self.assertEqual(19, result.returncode)
        self.assertEqual(EXPECTED_CALLS[:5], self._calls())
        self.assertNotIn("PostgreSQL 18 runtime", result.stdout)


class R1PreflightWorkflowTest(unittest.TestCase):
    def test_workflow_uses_pinned_tools_and_the_single_driver_without_write_authority(self) -> None:
        """Break caught: CI bypasses the driver, floats a toolchain, or gains write authority."""
        self.assertTrue(WORKFLOW.is_file(), "R1 preflight workflow is missing")
        workflow = yaml.load(WORKFLOW.read_text(encoding="utf-8"), Loader=WorkflowLoader)

        self.assertEqual("R1 preflight (not runtime acceptance)", workflow["name"])
        self.assertEqual({"pull_request", "push", "workflow_dispatch"}, set(workflow["on"]))
        self.assertEqual(["main"], workflow["on"]["push"]["branches"])
        self.assertEqual({"contents": "read"}, workflow["permissions"])
        self.assertEqual(["preflight"], list(workflow["jobs"]))

        job = workflow["jobs"]["preflight"]
        self.assertNotIn("continue-on-error", job)
        steps = job["steps"]
        self.assertTrue(all("continue-on-error" not in step for step in steps))
        actions = {step.get("uses", "").split("@", 1)[0]: step for step in steps if "uses" in step}
        self.assertEqual(
            "actions/checkout@11d5960a326750d5838078e36cf38b85af677262",
            actions["actions/checkout"]["uses"],
        )
        self.assertEqual("false", actions["actions/checkout"]["with"]["persist-credentials"])
        self.assertEqual("0", actions["actions/checkout"]["with"]["fetch-depth"])
        self.assertEqual(
            "actions/setup-python@a26af69be951a213d495a4c3e4e4022e16d87065",
            actions["actions/setup-python"]["uses"],
        )
        self.assertEqual("3.12.14", actions["actions/setup-python"]["with"]["python-version"])
        self.assertEqual(
            "actions/setup-java@dd06d9cba3e5552c54d9f8ea23572deb30010f7c",
            actions["actions/setup-java"]["uses"],
        )
        self.assertEqual("25.0.4.1", actions["actions/setup-java"]["with"]["java-version"])
        self.assertEqual(
            "actions/setup-node@49933ea5288caeca8642d1e84afbd3f7d6820020",
            actions["actions/setup-node"]["uses"],
        )
        self.assertEqual("24.20.0", actions["actions/setup-node"]["with"]["node-version"])

        run_commands = [step["run"] for step in steps if "run" in step]
        self.assertIn("python -m pip install -r database/schema-contract-52-plus-2/requirements-dev.txt", run_commands)
        self.assertIn("npm install --global npm@11.9.0", run_commands)
        self.assertIn('test "$(npm --version)" = "11.9.0"', run_commands)
        self.assertIn("npm ci", run_commands)
        self.assertIn("node node_modules/@playwright/test/cli.js install --with-deps chromium", run_commands)
        self.assertEqual(1, run_commands.count("bash scripts/ci/r1-preflight.sh"))
        self.assertFalse(any("push" in command or "deploy" in command for command in run_commands))

        package = json.loads((ROOT / "package.json").read_text(encoding="utf-8"))
        self.assertEqual(
            "playwright test --config playwright.config.ts --project offline-harness "
            "&& playwright test --config e2e/business.config.ts --project offline-business",
            package["scripts"]["test:e2e:offline"],
        )


if __name__ == "__main__":
    unittest.main()
