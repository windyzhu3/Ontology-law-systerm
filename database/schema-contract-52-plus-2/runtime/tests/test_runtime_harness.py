"""Contract tests for the PostgreSQL runtime-verification harness."""

from __future__ import annotations

import copy
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

from pglast import ast, parse_sql, parser
from pglast.visitors import Visitor


PROJECT_ROOT = Path(__file__).resolve().parents[2]
PARSER_STATE_DIAGNOSTIC_CODES = (
    "verifier_parser_evidence_invalid",
    "verifier_parser_error_missing",
    "verifier_parser_error_multiple",
    "verifier_parser_record_missing",
    "verifier_parser_record_multiple",
    "verifier_parser_assertion_multiple",
    "verifier_parser_assertion_malformed",
    "verifier_parser_phase_conflict",
)
FINGERPRINT_SQLSTATE_DIAGNOSTICS = {
    "42883": "verifier_fingerprint_sqlstate_undefined_function_operator",
    "42804": "verifier_fingerprint_sqlstate_datatype_mismatch",
    "42846": "verifier_fingerprint_sqlstate_cannot_coerce",
    "42P18": "verifier_fingerprint_sqlstate_indeterminate_datatype",
    "42703": "verifier_fingerprint_sqlstate_undefined_column",
    "42P01": "verifier_fingerprint_sqlstate_undefined_table",
    "42704": "verifier_fingerprint_sqlstate_undefined_object",
    "42501": "verifier_fingerprint_sqlstate_insufficient_privilege",
    "42601": "verifier_fingerprint_sqlstate_syntax_error",
    "0A000": "verifier_fingerprint_sqlstate_feature_not_supported",
    "XX000": "verifier_fingerprint_sqlstate_internal_error",
}
FINGERPRINT_SQLSTATE_UNMAPPED_DIAGNOSTIC = "verifier_fingerprint_sqlstate_unmapped"

_ASSERTION_START_PATTERN = re.compile(r"assertion=", re.IGNORECASE)
_STATIC_ASSERTION_PATTERN = re.compile(
    r"assertion=(?P<label>[^%'\r\n]+?)[ \t]+expected(?:[ \t]+SQLSTATE)?=",
    re.IGNORECASE,
)
_GENERIC_ASSERTION_PATTERN = re.compile(
    r"assertion=[^'\r\n]*%[^'\r\n]*?[ \t]+expected(?:[ \t]+SQLSTATE)?=",
    re.IGNORECASE,
)
_REVIEWED_HELPER_TEMPLATES = (
    "assertion=% expected SQLSTATE=% actual=success",
    "assertion=% expected SQLSTATE=% actual=%",
)


@dataclass(frozen=True)
class _VerifierAssertionInventory:
    labels: set[str]
    expect_call_count: int
    helper_definition_count: int
    unresolved_expect_calls: tuple[str, ...]
    unresolved_assertions: tuple[str, ...]


def _inventory_verifier_assertions(sql: str) -> _VerifierAssertionInventory:
    """Inventory closed assertion labels from parsed SQL without formatting assumptions."""
    labels: set[str] = set()
    literal_values: list[tuple[str, str]] = []
    plpgsql_bodies: list[tuple[str, str]] = []
    unresolved_expect_calls: list[str] = []
    unresolved_assertions: list[str] = []
    expect_call_count = 0
    helper_definition_count = 0
    helper_body_scopes: list[str] = []
    function_count = 0
    do_count = 0

    def body_values(options: object) -> tuple[str, ...] | None:
        if not isinstance(options, tuple):
            return None
        as_options = tuple(
            option
            for option in options
            if isinstance(option, ast.DefElem) and option.defname.casefold() == "as"
        )
        if len(as_options) != 1:
            return None
        argument = as_options[0].arg
        if isinstance(argument, ast.String):
            return (argument.sval,)
        if (
            isinstance(argument, tuple)
            and argument
            and all(isinstance(value, ast.String) for value in argument)
        ):
            return tuple(value.sval for value in argument)
        return None

    def decoded_string_token(body: str, start: int, end: int) -> str | None:
        token_source = body[start : end + 1]
        try:
            statements = parse_sql("SELECT " + token_source)
        except Exception:
            return None
        if len(statements) != 1 or not isinstance(statements[0].stmt, ast.SelectStmt):
            return None
        targets = tuple(statements[0].stmt.targetList or ())
        if len(targets) != 1 or not isinstance(targets[0], ast.ResTarget):
            return None
        value = targets[0].val
        if (
            not isinstance(value, ast.A_Const)
            or value.isnull
            or not isinstance(value.val, ast.String)
        ):
            return None
        return value.val.sval

    def identifier_token_value(source: str, token: object) -> str | None:
        if getattr(token, "name", None) not in {"IDENT", "UIDENT"}:
            return None
        value = source[token.start : token.end + 1]
        if value[:2].casefold() == "u&":
            value = value[2:]
        if len(value) >= 2 and value.startswith('"') and value.endswith('"'):
            value = value[1:-1].replace('""', '"')
        return value.casefold()

    def contains_helper_reference(source: str, tokens: list[object]) -> bool:
        significant = [
            token for token in tokens if token.name not in {"C_COMMENT", "SQL_COMMENT"}
        ]
        for index in range(len(significant) - 2):
            qualifier = significant[index]
            separator = significant[index + 1]
            function = significant[index + 2]
            if separator.name != "ASCII_46":
                continue
            if (
                identifier_token_value(source, qualifier) == "pg_temp"
                and identifier_token_value(source, function) == "expect_sqlstate"
            ):
                return True
        return False

    class InventoryVisitor(Visitor):
        def visit_FuncCall(self, ancestors: object, node: ast.FuncCall) -> None:
            nonlocal expect_call_count
            components = tuple(
                component.sval if isinstance(component, ast.String) else None
                for component in node.funcname
            )
            folded_components = tuple(
                component.casefold() if isinstance(component, str) else None
                for component in components
            )
            if not folded_components or folded_components[-1] != "expect_sqlstate":
                return
            call_name = (
                f"expect-call-{expect_call_count + len(unresolved_expect_calls) + 1}"
            )
            if components != ("pg_temp", "expect_sqlstate"):
                reason = (
                    "helper-identity"
                    if folded_components == ("pg_temp", "expect_sqlstate")
                    else "qualified-name"
                )
                unresolved_expect_calls.append(f"{call_name}:{reason}")
                return
            raw_statement = ancestors.find_nearest(ast.RawStmt)
            if raw_statement is None or not isinstance(
                raw_statement.node.stmt, ast.SelectStmt
            ):
                unresolved_expect_calls.append(f"{call_name}:nested-body")
                return
            expect_call_count += 1
            arguments = tuple(node.args or ())
            if len(arguments) != 3:
                unresolved_expect_calls.append(f"{call_name}:argument-count")
                return
            label_argument = arguments[2]
            if (
                not isinstance(label_argument, ast.A_Const)
                or label_argument.isnull
                or not isinstance(label_argument.val, ast.String)
            ):
                unresolved_expect_calls.append(f"{call_name}:non-text-label")
                return
            labels.add(label_argument.val.sval)

        def visit_A_Const(self, ancestors: object, node: ast.A_Const) -> None:
            del ancestors
            if not node.isnull and isinstance(node.val, ast.String):
                literal_values.append(("sql-literal", node.val.sval))

        def visit_CreateFunctionStmt(
            self,
            ancestors: object,
            node: ast.CreateFunctionStmt,
        ) -> None:
            del ancestors
            nonlocal function_count, helper_definition_count
            function_count += 1
            components = tuple(
                component.sval if isinstance(component, ast.String) else None
                for component in node.funcname
            )
            folded_components = tuple(
                component.casefold() if isinstance(component, str) else None
                for component in components
            )
            is_helper = components == ("pg_temp", "expect_sqlstate")
            if folded_components == ("pg_temp", "expect_sqlstate") and not is_helper:
                unresolved_expect_calls.append(
                    f"function-{function_count}:helper-identity"
                )
            if is_helper:
                helper_definition_count += 1
                scope = f"reviewed-helper-{helper_definition_count}"
            else:
                scope = f"function-body-{function_count}"
            bodies = body_values(node.options)
            if bodies is None or len(bodies) != 1:
                if is_helper:
                    unresolved_assertions.append(f"{scope}:invalid-as-body")
                return
            plpgsql_bodies.append((scope, bodies[0]))
            if is_helper:
                helper_body_scopes.append(scope)

        def visit_DoStmt(self, ancestors: object, node: ast.DoStmt) -> None:
            del ancestors
            nonlocal do_count
            do_count += 1
            scope = f"do-body-{do_count}"
            bodies = body_values(node.args)
            if bodies is None or len(bodies) != 1:
                unresolved_assertions.append(f"{scope}:invalid-as-body")
                return
            plpgsql_bodies.append((scope, bodies[0]))

    InventoryVisitor()(parse_sql(sql))

    for body_index, (scope, body) in enumerate(plpgsql_bodies, start=1):
        try:
            tokens = parser.scan(body)
        except Exception:
            unresolved_assertions.append(f"body-{body_index}:unscannable")
            continue
        for token_index, token in enumerate(tokens, start=1):
            if token.name == "USCONST":
                unresolved_expect_calls.append(
                    f"body-{body_index}/string-{token_index}:unicode-string"
                )
                continue
            if token.name != "SCONST":
                continue
            value = decoded_string_token(body, token.start, token.end)
            if value is None:
                unresolved_assertions.append(
                    f"body-{body_index}/string-{token_index}:undecodable"
                )
                continue
            literal_values.append((scope, value))

    reference_sources = [*plpgsql_bodies, *literal_values]
    for source_index, (_, source) in enumerate(reference_sources, start=1):
        pending = [source]
        scanned: set[str] = set()
        while pending:
            candidate = pending.pop()
            if candidate in scanned:
                continue
            scanned.add(candidate)
            if len(scanned) > 256:
                unresolved_expect_calls.append(
                    f"nested-source-{source_index}:scan-limit"
                )
                break
            try:
                tokens = parser.scan(candidate)
            except Exception:
                folded = candidate.casefold()
                if "pg_temp" in folded or "expect_sqlstate" in folded:
                    unresolved_expect_calls.append(
                        f"nested-source-{source_index}:unscannable-reference"
                    )
                continue
            has_unicode_identifier = any(token.name == "UIDENT" for token in tokens)
            has_unicode_string = any(token.name == "USCONST" for token in tokens)
            if has_unicode_identifier:
                unresolved_expect_calls.append(
                    f"nested-source-{source_index}:unicode-identifier"
                )
            if has_unicode_string:
                unresolved_expect_calls.append(
                    f"nested-source-{source_index}:unicode-string"
                )
            if (
                not has_unicode_identifier
                and not has_unicode_string
                and contains_helper_reference(candidate, tokens)
            ):
                unresolved_expect_calls.append(
                    f"nested-source-{source_index}:helper-reference"
                )
            for token in tokens:
                if token.name != "SCONST":
                    continue
                nested = decoded_string_token(candidate, token.start, token.end)
                if nested is None:
                    token_source = candidate[token.start : token.end + 1].casefold()
                    if "pg_temp" in token_source or "expect_sqlstate" in token_source:
                        unresolved_expect_calls.append(
                            f"nested-source-{source_index}:undecodable-reference"
                        )
                    continue
                if nested not in scanned:
                    pending.append(nested)

    reviewed_helper_scope = (
        helper_body_scopes[0]
        if helper_definition_count == 1 and len(helper_body_scopes) == 1
        else None
    )
    reviewed_template_counts = {template: 0 for template in _REVIEWED_HELPER_TEMPLATES}
    for string_index, (scope, value) in enumerate(literal_values, start=1):
        occurrences = list(_ASSERTION_START_PATTERN.finditer(value))
        for assertion_index, occurrence in enumerate(occurrences, start=1):
            next_start = (
                occurrences[assertion_index].start()
                if assertion_index < len(occurrences)
                else len(value)
            )
            fragment = value[occurrence.start() : next_start]
            static_assertion = _STATIC_ASSERTION_PATTERN.match(fragment)
            if static_assertion is not None:
                labels.add(static_assertion.group("label"))
                continue
            if _GENERIC_ASSERTION_PATTERN.match(fragment) is not None:
                if scope == reviewed_helper_scope and value in reviewed_template_counts:
                    reviewed_template_counts[value] += 1
                    continue
            unresolved_assertions.append(
                f"string-{string_index}/assertion-{assertion_index}"
            )

    if helper_definition_count == 1:
        for template_index, template in enumerate(_REVIEWED_HELPER_TEMPLATES, start=1):
            if reviewed_template_counts[template] != 1:
                unresolved_assertions.append(
                    f"reviewed-helper-template-{template_index}:count"
                )

    return _VerifierAssertionInventory(
        labels=labels,
        expect_call_count=expect_call_count,
        helper_definition_count=helper_definition_count,
        unresolved_expect_calls=tuple(unresolved_expect_calls),
        unresolved_assertions=tuple(unresolved_assertions),
    )


class RuntimeHarnessTests(unittest.TestCase):
    def _assert_verifier_inventory_matches_map(
        self,
        sql_by_script: dict[str, str],
        diagnostic_map: dict[str, tuple[str, str]],
    ) -> None:
        expected_phase_by_script = {
            "assert_schema_contract.sql": "schema",
            "assert_capabilities.sql": "capability",
        }
        self.assertEqual(set(sql_by_script), set(expected_phase_by_script))
        labels_by_script: dict[str, set[str]] = {}
        expect_call_count = 0
        for script, expected_phase in expected_phase_by_script.items():
            inventory = _inventory_verifier_assertions(sql_by_script[script])
            self.assertEqual(inventory.unresolved_expect_calls, ())
            self.assertEqual(inventory.unresolved_assertions, ())
            self.assertEqual(
                inventory.helper_definition_count,
                1 if script == "assert_capabilities.sql" else 0,
            )
            self.assertTrue(inventory.labels)
            labels_by_script[script] = inventory.labels
            expect_call_count += inventory.expect_call_count
            for label in inventory.labels:
                self.assertIn(label, diagnostic_map)
                self.assertEqual(diagnostic_map[label][0], expected_phase)

        sql_labels = set().union(*labels_by_script.values())
        self.assertEqual(expect_call_count, 23)
        self.assertEqual(len(sql_labels), 47)
        self.assertEqual(set(diagnostic_map), sql_labels)

    def _successful_runtime_stage(
        self,
        command: list[str],
        *,
        evidence_path: Path,
        cwd: Path,
        timeout_seconds: float,
    ) -> int:
        """Emulate Compose itself while exercising the real orchestration decisions."""
        del cwd, timeout_seconds
        stdout = ""
        stderr = ""
        return_code = 0
        scenario_messages = {
            "missing-role": "configured application database role does not exist",
            "extra-managed-table": "expected 52 application tables",
            "forbidden-delete-grant": "forbidden DELETE or TRUNCATE",
            "missing-mutation-guard": "mutation guard coverage mismatch",
        }
        if "ps" in command:
            stdout = json.dumps([{"Service": command[-1], "ExitCode": 0}])
        elif "logs" in command and command[-1] == "verifier":
            stdout = json.dumps(
                {
                    "fingerprint": "0123456789abcdef0123456789abcdef",
                    "postgresVersion": (
                        "PostgreSQL 18.0 (Debian 18.0-1.pgdg13+3) on x86_64-pc-linux-gnu, "
                        "compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit"
                    ),
                    "serverVersion": "18.0",
                    "status": "PASSED",
                }
            ) + "\n"
        elif evidence_path.name == "postgres-image.json":
            stdout = json.dumps(
                ["docker.io/library/postgres@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280"]
            )
        elif evidence_path.name == "flyway-image.json":
            stdout = json.dumps(
                ["docker.io/redgate/flyway@sha256:c093a247b19ff09a6a72774569171ee355fff2ae44ceba4a4aa4b23235d99c93"]
            )
        elif evidence_path.name == "flyway-version.json":
            stdout = "Flyway Community Edition 13.4.0 by Redgate\n"
        elif evidence_path.name == "expected-failure.json":
            return_code = 17
            stderr = scenario_messages[evidence_path.parent.name]
        elif evidence_path.name == "strict-validate.json":
            return_code = 17
            stderr = "checksum mismatch for migration version 010"
        evidence_path.parent.mkdir(parents=True, exist_ok=True)
        evidence_path.write_text(
            json.dumps(
                {
                    "returncode": return_code,
                    "status": "passed" if return_code == 0 else "failed",
                    "stderr": stderr,
                    "stdout": stdout,
                    "timedOut": False,
                }
            ),
            encoding="utf-8",
        )
        return return_code

    def test_staged_detached_services_wait_for_each_exit_with_explicit_budgets(self) -> None:
        """Break caught: a one-shot Flyway exit aborts the stack or a stage falls back to 60 seconds."""
        from runtime import verify_runtime

        calls: list[tuple[list[str], float]] = []
        temporary_paths: set[Path] = set()
        with tempfile.TemporaryDirectory() as temporary_directory:
            output_directory = Path(temporary_directory) / "evidence"

            def successful_stage(
                command: list[str],
                *,
                evidence_path: Path,
                cwd: Path,
                timeout_seconds: float,
            ) -> int:
                calls.append((command, timeout_seconds))
                if "--env-file" in command:
                    temporary_paths.add(Path(command[command.index("--env-file") + 1]))
                    temporary_paths.add(Path(command[[index for index, value in enumerate(command) if value == "-f"][-1] + 1]))
                return self._successful_runtime_stage(
                    command,
                    evidence_path=evidence_path,
                    cwd=cwd,
                    timeout_seconds=timeout_seconds,
                )

            result = verify_runtime.run_runtime_verification(
                PROJECT_ROOT,
                output_directory,
                runs=2,
                stage_runner=successful_stage,
            )

        self.assertEqual(result["status"], "PASSED")
        commands = [command for command, _ in calls]
        self.assertTrue(any(command[-4:] == ["up", "-d", "--wait", "postgres"] for command in commands))
        self.assertTrue(any(command[-3:] == ["up", "-d", "flyway"] for command in commands))
        self.assertTrue(any(command[-2:] == ["wait", "flyway"] for command in commands))
        self.assertTrue(any(command[-1:] == ["flyway"] and "ps" in command for command in commands))
        self.assertTrue(any(command[-4:] == ["up", "-d", "--no-deps", "verifier"] for command in commands))
        self.assertTrue(any(command[-2:] == ["wait", "verifier"] for command in commands))
        self.assertTrue(any(command[-1:] == ["verifier"] and "ps" in command for command in commands))
        verifier_start = next(command for command in commands if command[-1:] == ["verifier"] and "up" in command)
        self.assertIn("--no-deps", verifier_start)
        self.assertEqual(2, sum(command[-1:] == ["flyway"] and "up" in command for command in commands))
        self.assertEqual(2, sum(command[-1:] == ["flyway"] and "wait" in command for command in commands))
        self.assertEqual(2, sum(command[-1:] == ["flyway"] and "ps" in command for command in commands))
        self.assertTrue(any("migrate" in command and "--entrypoint" in command for command in commands))
        verifier_log_commands = [
            command
            for command in commands
            if "logs" in command and command[-1:] == ["verifier"]
        ]
        self.assertEqual(len(verifier_log_commands), 3)
        self.assertTrue(
            all(
                command[-4:] == ["logs", "--no-color", "--no-log-prefix", "verifier"]
                for command in verifier_log_commands
            )
        )
        self.assertFalse(any("--abort-on-container-exit" in command for command in commands))
        self.assertTrue(all(timeout != verify_runtime._DEFAULT_TIMEOUT_SECONDS for _, timeout in calls))
        self.assertTrue(all(0 < timeout <= 300 for _, timeout in calls))
        self.assertTrue(all(not path.is_relative_to(PROJECT_ROOT) and not path.exists() for path in temporary_paths))

    def test_verifier_assertion_classifier_is_exhaustive_closed_and_secret_safe(self) -> None:
        """Break caught: raw verifier output escapes or an existing assertion collapses to a generic failure."""
        from runtime import verify_runtime

        assertion_codes = (
            ("assert_schema_contract.sql", "13 managed schemas", "verifier_schema_managed_schema_count"),
            ("assert_schema_contract.sql", "managed schema allowlist", "verifier_schema_managed_schema_allowlist"),
            ("assert_schema_contract.sql", "52 application tables", "verifier_schema_application_table_count"),
            ("assert_schema_contract.sql", "2 platform_meta tables", "verifier_schema_platform_meta_table_set"),
            ("assert_schema_contract.sql", "public schema table count", "verifier_schema_public_table_count"),
            ("assert_schema_contract.sql", "19 successful migrations", "verifier_schema_migration_count"),
            ("assert_schema_contract.sql", "all migrations successful", "verifier_schema_migration_success"),
            ("assert_schema_contract.sql", "maximum migration version", "verifier_schema_max_migration_version"),
            ("assert_schema_contract.sql", "V840 successful", "verifier_schema_v840_success"),
            ("assert_schema_contract.sql", "206 composite foreign keys", "verifier_schema_foreign_key_count"),
            ("assert_schema_contract.sql", "application foreign keys NO ACTION", "verifier_schema_foreign_key_actions"),
            ("assert_schema_contract.sql", "validated MATCH SIMPLE foreign keys", "verifier_schema_foreign_key_validation"),
            ("assert_schema_contract.sql", "tenant_id first in tenant foreign keys", "verifier_schema_foreign_key_tenant_prefix"),
            ("assert_schema_contract.sql", "53 mutation guards", "verifier_schema_mutation_guard_count"),
            ("assert_schema_contract.sql", "four distinct capability roles", "verifier_schema_capability_role_count"),
            ("assert_schema_contract.sql", "capability roles NOLOGIN", "verifier_schema_capability_roles_nologin"),
            ("assert_schema_contract.sql", "capability parent role memberships", "verifier_schema_capability_parent_membership"),
            ("assert_schema_contract.sql", "capability roles cannot obtain migration owner", "verifier_schema_capability_migrator_isolation"),
            ("assert_schema_contract.sql", "deployment_state PRIMARY/BLOCKED/52-plus-2-v1/revision=0 with 32 zero bytes", "verifier_schema_deployment_state_seed"),
            ("assert_capabilities.sql", "cross-tenant organization parent", "verifier_capability_cross_tenant_parent"),
            ("assert_capabilities.sql", "deployment no-op update", "verifier_capability_deployment_noop_guard"),
            ("assert_capabilities.sql", "deployment revision must increment exactly once", "verifier_capability_deployment_revision_guard"),
            ("assert_capabilities.sql", "query role INSERT", "verifier_capability_query_insert"),
            ("assert_capabilities.sql", "query role UPDATE", "verifier_capability_query_update"),
            ("assert_capabilities.sql", "query role DELETE", "verifier_capability_query_delete"),
            ("assert_capabilities.sql", "query role direct audit read", "verifier_capability_query_audit_read"),
            ("assert_capabilities.sql", "query role CREATE SCHEMA", "verifier_capability_query_create_schema"),
            ("assert_capabilities.sql", "query role CREATE TABLE", "verifier_capability_query_create_table"),
            ("assert_capabilities.sql", "query role migration owner", "verifier_capability_query_migrator_isolation"),
            ("assert_capabilities.sql", "audit append role SELECT", "verifier_capability_audit_select"),
            ("assert_capabilities.sql", "audit append role UPDATE", "verifier_capability_audit_update"),
            ("assert_capabilities.sql", "audit append role CREATE SCHEMA", "verifier_capability_audit_create_schema"),
            ("assert_capabilities.sql", "audit append role CREATE TABLE", "verifier_capability_audit_create_table"),
            ("assert_capabilities.sql", "audit append role migration owner", "verifier_capability_audit_migrator_isolation"),
            ("assert_capabilities.sql", "worker frozen outbox column", "verifier_capability_worker_frozen_column"),
            ("assert_capabilities.sql", "worker outbox INSERT", "verifier_capability_worker_outbox_insert"),
            ("assert_capabilities.sql", "worker domain write", "verifier_capability_worker_domain_write"),
            ("assert_capabilities.sql", "worker role CREATE SCHEMA", "verifier_capability_worker_create_schema"),
            ("assert_capabilities.sql", "worker role CREATE TABLE", "verifier_capability_worker_create_table"),
            ("assert_capabilities.sql", "worker role migration owner", "verifier_capability_worker_migrator_isolation"),
            ("assert_capabilities.sql", "command role DELETE", "verifier_capability_command_delete"),
            ("assert_capabilities.sql", "command role TRUNCATE", "verifier_capability_command_truncate"),
            ("assert_capabilities.sql", "command role frozen column", "verifier_capability_command_frozen_column"),
            ("assert_capabilities.sql", "command role platform_meta write", "verifier_capability_command_platform_meta_write"),
            ("assert_capabilities.sql", "command role CREATE SCHEMA", "verifier_capability_command_create_schema"),
            ("assert_capabilities.sql", "command role CREATE TABLE", "verifier_capability_command_create_table"),
            ("assert_capabilities.sql", "command role migration owner", "verifier_capability_command_migrator_isolation"),
        )
        hostile = (
            " password=hunter2 postgresql://user:secret@db.internal/law "
            "/private/tmp/runtime token=top-secret"
        )
        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence_directory = Path(temporary_directory)
            for index, (script, assertion, expected_code) in enumerate(assertion_codes):
                evidence_path = evidence_directory / f"known-{index}.json"
                evidence_path.write_text(
                    json.dumps(
                        {
                            "returncode": 0,
                            "stderr": "",
                            "stdout": (
                                f"verifier | psql:/runtime/sql/{script}:42: ERROR:  "
                                f"assertion={assertion} expected=value actual=other{hostile}"
                            ),
                        }
                    ),
                    encoding="utf-8",
                )
                with self.subTest(assertion=assertion):
                    code = verify_runtime.classify_verifier_log(evidence_path)
                    self.assertEqual(code, expected_code)
                    self.assertNotIn("hunter2", code)
                    self.assertNotIn("db.internal", code)
                    self.assertNotIn("/private/tmp", code)
                    self.assertNotIn("top-secret", code)

            phase_unknowns = (
                ("assert_schema_contract.sql", "verifier_schema_assertion_unknown"),
                ("assert_capabilities.sql", "verifier_capability_assertion_unknown"),
            )
            for index, (script, expected_code) in enumerate(phase_unknowns):
                evidence_path = evidence_directory / f"phase-unknown-{index}.json"
                evidence_path.write_text(
                    json.dumps(
                        {
                            "returncode": 0,
                            "stderr": "",
                            "stdout": (
                                f"psql:/runtime/sql/{script}:42: ERROR:  "
                                "assertion=unmapped controlled assertion expected=value actual=other"
                            ),
                        }
                    ),
                    encoding="utf-8",
                )
                self.assertEqual(verify_runtime.classify_verifier_log(evidence_path), expected_code)

            capability_record = (
                "psql:/runtime/sql/assert_capabilities.sql:2: ERROR:  "
                "assertion=query role INSERT expected SQLSTATE=42501 actual=00000"
            )
            accepted_record_shapes = {
                "direct-psql.json": (
                    capability_record,
                    "verifier_capability_query_insert",
                ),
                "compose-prefix.json": (
                    "law-verifier-1   |   " + capability_record,
                    "verifier_capability_query_insert",
                ),
                "ordinary-schema-error.json": (
                    "psql:/runtime/sql/assert_schema_contract.sql:9: ERROR: relation is unavailable",
                    "verifier_schema_assertion_unknown",
                ),
                "known-with-context.json": (
                    "law-verifier-1 | "
                    + capability_record
                    + "\nlaw-verifier-1 | CONTEXT: PL/pgSQL function inline_code_block line 4 at RAISE",
                    "verifier_capability_query_insert",
                ),
                "canonical-fingerprint-error.json": (
                    "psql:/runtime/sql/schema_fingerprint.sql:37: ERROR: "
                    "fingerprint query failed" + hostile,
                    "verifier_fingerprint_error",
                ),
                "prefixed-fingerprint-error.json": (
                    "law-verifier-1 | psql:/runtime/sql/schema_fingerprint.sql:37: ERROR: "
                    "fingerprint query failed" + hostile,
                    "verifier_fingerprint_error",
                ),
            }
            for name, (output, expected_code) in accepted_record_shapes.items():
                evidence_path = evidence_directory / name
                evidence_path.write_text(
                    json.dumps({"returncode": 0, "stderr": "", "stdout": output}),
                    encoding="utf-8",
                )
                with self.subTest(accepted=name):
                    code = verify_runtime.classify_verifier_log(evidence_path)
                    self.assertEqual(code, expected_code)
                    for secret in ("hunter2", "db.internal", "/private/tmp", "top-secret"):
                        self.assertNotIn(secret, code)

            unavailable = evidence_directory / "unavailable.json"
            unavailable.write_text(
                json.dumps({"returncode": 17, "stderr": hostile, "stdout": ""}),
                encoding="utf-8",
            )
            self.assertEqual(
                verify_runtime.classify_verifier_log(unavailable),
                "verifier_logs_unavailable",
            )

            malformed = evidence_directory / "malformed.json"
            malformed.write_text("{not-json", encoding="utf-8")
            invalid_utf8 = evidence_directory / "invalid-utf8.json"
            invalid_utf8.write_bytes(b"\xff\xfe")
            non_string = evidence_directory / "non-string.json"
            non_string.write_text(
                json.dumps({"returncode": 0, "stderr": [], "stdout": ""}),
                encoding="utf-8",
            )
            unknown = evidence_directory / "unknown.json"
            unknown.write_text(
                json.dumps({"returncode": 0, "stderr": "", "stdout": "unclassified failure"}),
                encoding="utf-8",
            )
            schema_record = (
                "psql:/runtime/sql/assert_schema_contract.sql:1: ERROR:  "
                "assertion=13 managed schemas expected=13 actual=12"
            )
            parser_state_outputs = {
                "unstructured-forgery.json": (
                    (
                        "narrative assert_capabilities.sql says "
                        "assertion=query role INSERT expected SQLSTATE=42501 actual=00000"
                        + hostile
                    ),
                    "verifier_parser_error_missing",
                ),
                "duplicate-record.json": (
                    f"{capability_record}\n{capability_record}{hostile}",
                    "verifier_parser_record_multiple",
                ),
                "multiple-records.json": (
                    f"{schema_record}\n{capability_record}{hostile}",
                    "verifier_parser_record_multiple",
                ),
                "known-plus-error.json": (
                    (
                        f"{capability_record}\n"
                        "psql:/runtime/sql/assert_capabilities.sql:3: ERROR: unexpected failure"
                        + hostile
                    ),
                    "verifier_parser_record_multiple",
                ),
                "phase-conflict.json": (
                    (
                        "psql:/runtime/sql/assert_schema_contract.sql:4: ERROR:  "
                        "assertion=query role INSERT expected SQLSTATE=42501 actual=00000"
                        + hostile
                    ),
                    "verifier_parser_phase_conflict",
                ),
                "zero-line.json": (
                    capability_record.replace(":2:", ":0:") + hostile,
                    "verifier_parser_record_missing",
                ),
                "missing-error-token.json": (
                    capability_record.replace(": ERROR:", ": NOTICE:") + hostile,
                    "verifier_parser_error_missing",
                ),
                "known-plus-bare-error.json": (
                    f"{capability_record}\nERROR: second failure{hostile}",
                    "verifier_parser_error_multiple",
                ),
                "same-line-second-error.json": (
                    f"{capability_record} ERROR: second failure{hostile}",
                    "verifier_parser_error_multiple",
                ),
                "embedded-near-prefix.json": (
                    f"not{capability_record}{hostile}",
                    "verifier_parser_record_missing",
                ),
                "fingerprint-suffix.json": (
                    "psql:/runtime/sql/schema_fingerprint.sql.bak:7: ERROR: failed" + hostile,
                    "verifier_parser_record_missing",
                ),
                "fingerprint-nested-path.json": (
                    "psql:/runtime/sql/private/schema_fingerprint.sql:7: ERROR: failed" + hostile,
                    "verifier_parser_record_missing",
                ),
                "fingerprint-zero-line.json": (
                    "psql:/runtime/sql/schema_fingerprint.sql:0: ERROR: failed" + hostile,
                    "verifier_parser_record_missing",
                ),
                "fingerprint-multiple-records.json": (
                    "psql:/runtime/sql/schema_fingerprint.sql:7: ERROR: first\n"
                    "psql:/runtime/sql/schema_fingerprint.sql:8: ERROR: second" + hostile,
                    "verifier_parser_record_multiple",
                ),
                "fingerprint-phase-conflict.json": (
                    "psql:/runtime/sql/schema_fingerprint.sql:7: ERROR: "
                    "assertion=query role INSERT expected SQLSTATE=42501 actual=00000"
                    + hostile,
                    "verifier_parser_phase_conflict",
                ),
                "fingerprint-malformed-assertion.json": (
                    "psql:/runtime/sql/schema_fingerprint.sql:7: ERROR: "
                    "assertion=query role INSERT expected=" + hostile,
                    "verifier_parser_assertion_malformed",
                ),
                "fingerprint-multiple-assertions.json": (
                    "psql:/runtime/sql/schema_fingerprint.sql:7: ERROR: "
                    "assertion=query role INSERT expected SQLSTATE=42501 actual=00000\n"
                    "assertion=unmapped expected=1 actual=2" + hostile,
                    "verifier_parser_assertion_multiple",
                ),
                "empty-expected.json": (
                    (
                        "psql:/runtime/sql/assert_capabilities.sql:2: ERROR:  "
                        "assertion=query role INSERT expected=" + hostile
                    ),
                    "verifier_parser_assertion_malformed",
                ),
                "whitespace-expected.json": (
                    (
                        "psql:/runtime/sql/assert_capabilities.sql:2: ERROR:  "
                        "assertion=query role INSERT expected=   " + hostile
                    ),
                    "verifier_parser_assertion_malformed",
                ),
                "missing-actual-field.json": (
                    (
                        "psql:/runtime/sql/assert_capabilities.sql:2: ERROR:  "
                        "assertion=query role INSERT expected=42501 detail=failed" + hostile
                    ),
                    "verifier_parser_assertion_malformed",
                ),
                "empty-actual.json": (
                    (
                        "psql:/runtime/sql/assert_capabilities.sql:2: ERROR:  "
                        "assertion=query role INSERT expected=42501 actual=" + hostile
                    ),
                    "verifier_parser_assertion_malformed",
                ),
                "whitespace-actual.json": (
                    (
                        "psql:/runtime/sql/assert_capabilities.sql:2: ERROR:  "
                        "assertion=query role INSERT expected=42501 actual=   " + hostile
                    ),
                    "verifier_parser_assertion_malformed",
                ),
                "expected-only.json": (
                    (
                        "psql:/runtime/sql/assert_capabilities.sql:2: ERROR:  "
                        "assertion=query role INSERT expected=42501" + hostile
                    ),
                    "verifier_parser_assertion_malformed",
                ),
                "duplicate-assertion-without-prefix.json": (
                    (
                        f"{capability_record}\n"
                        "assertion=query role INSERT expected SQLSTATE=42501 actual=00000"
                        + hostile
                    ),
                    "verifier_parser_assertion_multiple",
                ),
            }
            for name, (output, expected_code) in parser_state_outputs.items():
                evidence_path = evidence_directory / name
                evidence_path.write_text(
                    json.dumps({"returncode": 0, "stderr": "", "stdout": output}),
                    encoding="utf-8",
                )
                with self.subTest(parser_state=name):
                    code = verify_runtime.classify_verifier_log(evidence_path)
                    self.assertEqual(code, expected_code)
                    for secret in ("hunter2", "db.internal", "/private/tmp", "top-secret"):
                        self.assertNotIn(secret, code)

            for evidence_path in (
                malformed,
                invalid_utf8,
                non_string,
                evidence_directory / "missing.json",
            ):
                with self.subTest(invalid_evidence=evidence_path.name):
                    self.assertEqual(
                        verify_runtime.classify_verifier_log(evidence_path),
                        "verifier_parser_evidence_invalid",
                    )
            self.assertEqual(
                verify_runtime.classify_verifier_log(unknown),
                "verifier_parser_error_missing",
            )

    def test_verifier_assertion_map_exactly_tracks_production_sql(self) -> None:
        """Break caught: a SQL assertion and its closed diagnostic map drift independently."""
        from runtime import verify_runtime

        sql_directory = PROJECT_ROOT / "runtime" / "sql"
        sql_by_script = {
            script: (sql_directory / script).read_text(encoding="utf-8")
            for script in (
                "assert_schema_contract.sql",
                "assert_capabilities.sql",
            )
        }
        diagnostic_map = verify_runtime._VERIFIER_ASSERTION_DIAGNOSTICS
        self._assert_verifier_inventory_matches_map(sql_by_script, diagnostic_map)
        self.assertNotIn("fingerprint", {phase for phase, _ in diagnostic_map.values()})
        self.assertEqual(
            verify_runtime._VERIFIER_PHASE_DIAGNOSTICS,
            {
                "schema": ("assert_schema_contract.sql", "verifier_schema_assertion_unknown"),
                "capability": (
                    "assert_capabilities.sql",
                    "verifier_capability_assertion_unknown",
                ),
                "fingerprint": ("schema_fingerprint.sql", "verifier_fingerprint_error"),
            },
        )
        diagnostic_codes = [code for _, code in diagnostic_map.values()]
        all_closed_codes = [
            *diagnostic_codes,
            *(code for _, code in verify_runtime._VERIFIER_PHASE_DIAGNOSTICS.values()),
            *FINGERPRINT_SQLSTATE_DIAGNOSTICS.values(),
            FINGERPRINT_SQLSTATE_UNMAPPED_DIAGNOSTIC,
            *PARSER_STATE_DIAGNOSTIC_CODES,
            "verifier_diagnostic_unknown",
            "verifier_logs_unavailable",
        ]
        self.assertEqual(len(diagnostic_codes), len(set(diagnostic_codes)))
        self.assertEqual(len(all_closed_codes), len(set(all_closed_codes)))
        self.assertEqual(
            getattr(verify_runtime, "_VERIFIER_PARSER_DIAGNOSTIC_CODES", frozenset()),
            frozenset(PARSER_STATE_DIAGNOSTIC_CODES),
        )
        self.assertEqual(
            getattr(verify_runtime, "_VERIFIER_FINGERPRINT_SQLSTATE_DIAGNOSTICS", {}),
            FINGERPRINT_SQLSTATE_DIAGNOSTICS,
        )
        self.assertEqual(
            getattr(verify_runtime, "_VERIFIER_FINGERPRINT_SQLSTATE_UNMAPPED_DIAGNOSTIC", None),
            FINGERPRINT_SQLSTATE_UNMAPPED_DIAGNOSTIC,
        )
        self.assertEqual(verify_runtime._VERIFIER_DIAGNOSTIC_CODES, frozenset(all_closed_codes))

    def test_fingerprint_sqlstate_classifier_is_line_bound_closed_and_secret_safe(self) -> None:
        """Break caught: dynamic fingerprint SQL errors escape or lose their finite classification."""
        from runtime import verify_runtime

        fingerprint_path = PROJECT_ROOT / "runtime" / "sql" / "schema_fingerprint.sql"
        fingerprint_lines = fingerprint_path.read_text(encoding="utf-8").splitlines()
        # This locks psql's SendQuery statement-end location, not a source section.
        statement_endings = [
            (line_number, line.strip())
            for line_number, line in enumerate(fingerprint_lines, start=1)
            if line.strip() and line.rstrip().endswith(";")
        ]
        self.assertEqual(statement_endings, [(98, "FROM stable_catalog;")])
        self.assertEqual(verify_runtime._VERIFIER_FINGERPRINT_STATEMENT_END_LINE, 98)

        hostile = (
            " password=hunter2 postgresql://user:secret@db.internal/law "
            "/private/tmp/runtime token=top-secret"
        )
        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence_directory = Path(temporary_directory)

            for prefix in ("", "law-verifier-1 | "):
                for sqlstate, expected_code in FINGERPRINT_SQLSTATE_DIAGNOSTICS.items():
                    evidence_path = evidence_directory / f"mapped-{prefix != ''}-{sqlstate}.json"
                    evidence_path.write_text(
                        json.dumps(
                            {
                                "returncode": 0,
                                "stderr": "",
                                "stdout": (
                                    f"{prefix}psql:/runtime/sql/schema_fingerprint.sql:98: "
                                    f"ERROR:  {sqlstate}"
                                ),
                            }
                        ),
                        encoding="utf-8",
                    )
                    with self.subTest(prefix=prefix, sqlstate=sqlstate):
                        diagnostic = verify_runtime.classify_verifier_log(evidence_path)
                        self.assertEqual(diagnostic, expected_code)

            cases = {
                "unmapped": (
                    "psql:/runtime/sql/schema_fingerprint.sql:98: ERROR: ZZZZZ",
                    FINGERPRINT_SQLSTATE_UNMAPPED_DIAGNOSTIC,
                ),
                "wrong-line": (
                    "psql:/runtime/sql/schema_fingerprint.sql:97: ERROR: 42883",
                    "verifier_fingerprint_error",
                ),
                "lowercase": (
                    "psql:/runtime/sql/schema_fingerprint.sql:98: ERROR: 42p18",
                    "verifier_fingerprint_error",
                ),
                "payload": (
                    "psql:/runtime/sql/schema_fingerprint.sql:98: ERROR: 42883" + hostile,
                    "verifier_fingerprint_error",
                ),
                "multiple-records": (
                    "psql:/runtime/sql/schema_fingerprint.sql:98: ERROR: 42883\n"
                    "psql:/runtime/sql/schema_fingerprint.sql:98: ERROR: 42804",
                    "verifier_parser_record_multiple",
                ),
                "assertion-payload": (
                    "psql:/runtime/sql/schema_fingerprint.sql:98: ERROR: 42883\n"
                    "CONTEXT: assertion=forged expected=1 actual=2",
                    "verifier_parser_assertion_malformed",
                ),
                "multiple-assertions": (
                    "psql:/runtime/sql/schema_fingerprint.sql:98: ERROR: 42883\n"
                    "CONTEXT: assertion=first expected=1 actual=2\n"
                    "CONTEXT: assertion=second expected=1 actual=2",
                    "verifier_parser_assertion_multiple",
                ),
            }
            for name, (output, expected_code) in cases.items():
                evidence_path = evidence_directory / f"{name}.json"
                evidence_path.write_text(
                    json.dumps({"returncode": 0, "stderr": "", "stdout": output}),
                    encoding="utf-8",
                )
                with self.subTest(case=name):
                    diagnostic = verify_runtime.classify_verifier_log(evidence_path)
                    self.assertEqual(diagnostic, expected_code)
                    for secret in ("hunter2", "db.internal", "/private/tmp", "top-secret"):
                        self.assertNotIn(secret, diagnostic)

    def test_exact_set_gate_rejects_case_hidden_controlled_mutations(self) -> None:
        """Break caught: reviewer casing variants introduce drift without breaking the exact-set test."""
        from runtime import verify_runtime

        sql_directory = PROJECT_ROOT / "runtime" / "sql"
        schema_sql = (sql_directory / "assert_schema_contract.sql").read_text(encoding="utf-8")
        capability_sql = (sql_directory / "assert_capabilities.sql").read_text(encoding="utf-8")
        function_source = (
            "SELECT pg_temp.expect_sqlstate('42501', "
            "'INSERT INTO lead.lead (tenant_id) SELECT NULL::uuid WHERE false', "
            "'query role INSERT');"
        )
        function_mutation = (
            "select PG_TEMP.ExPeCt_SqLsTaTe('42501', "
            "'INSERT INTO lead.lead (tenant_id) SELECT NULL::uuid WHERE false', "
            "'query role INSERT drift');"
        )
        message_source = (
            "RAISE EXCEPTION 'assertion=query role migration owner "
            "expected=false actual=true';"
        )
        message_mutation = (
            "raise exception USING MESSAGE = 'AsSeRtIoN=query role migration owner drift "
            "ExPeCtEd=false actual=true';"
        )
        mutations = (
            capability_sql.replace(function_source, function_mutation, 1),
            capability_sql.replace(message_source, message_mutation, 1),
        )
        self.assertTrue(all(mutated != capability_sql for mutated in mutations))
        for mutation_name, mutated in zip(("function-call", "using-message"), mutations):
            with self.subTest(mutation=mutation_name):
                with self.assertRaises(AssertionError):
                    self._assert_verifier_inventory_matches_map(
                        {
                            "assert_schema_contract.sql": schema_sql,
                            "assert_capabilities.sql": mutated,
                        },
                        verify_runtime._VERIFIER_ASSERTION_DIAGNOSTICS,
                    )

    def test_exact_set_gate_rejects_generic_assertions_outside_the_helper(self) -> None:
        """Break caught: a generic RAISE or format assertion is mistaken for the reviewed helper."""
        from runtime import verify_runtime

        sql_directory = PROJECT_ROOT / "runtime" / "sql"
        schema_sql = (sql_directory / "assert_schema_contract.sql").read_text(encoding="utf-8")
        capability_sql = (sql_directory / "assert_capabilities.sql").read_text(encoding="utf-8")
        helper_start = capability_sql.index("CREATE FUNCTION pg_temp.expect_sqlstate")
        helper_end = capability_sql.index("$expect_sqlstate$;", helper_start) + len(
            "$expect_sqlstate$;"
        )
        helper_definition = capability_sql[helper_start:helper_end]
        reviewed_raise = (
            "RAISE EXCEPTION 'assertion=% expected SQLSTATE=% actual=success', "
            "assertion_name, expected_state;"
        )
        mutations = {
            "generic-raise-in-do": (
                schema_sql,
                capability_sql
                + """
DO $unreviewed_generic_raise$
BEGIN
    RAISE EXCEPTION 'assertion=% expected SQLSTATE=% actual=success', 'drift', '42501';
END
$unreviewed_generic_raise$;
""",
            ),
            "generic-format-in-do": (
                schema_sql,
                capability_sql
                + """
DO $unreviewed_generic_format$
BEGIN
    PERFORM format(
        'assertion=%s expected SQLSTATE=%s actual=%s',
        'drift',
        '42501',
        '00000'
    );
END
$unreviewed_generic_format$;
""",
            ),
            "generic-in-schema-script": (
                schema_sql
                + """
DO $schema_generic$
BEGIN
    RAISE EXCEPTION 'assertion=% expected SQLSTATE=% actual=%', 'drift', '1', '2';
END
$schema_generic$;
""",
                capability_sql,
            ),
            "duplicate-reviewed-template": (
                schema_sql,
                capability_sql.replace(
                    reviewed_raise,
                    reviewed_raise + "\n        " + reviewed_raise,
                    1,
                ),
            ),
            "duplicate-helper-definition": (
                schema_sql,
                capability_sql + "\n" + helper_definition + "\n",
            ),
        }
        for mutation_name, (mutated_schema, mutated_capability) in mutations.items():
            with self.subTest(mutation=mutation_name):
                with self.assertRaises(AssertionError):
                    self._assert_verifier_inventory_matches_map(
                        {
                            "assert_schema_contract.sql": mutated_schema,
                            "assert_capabilities.sql": mutated_capability,
                        },
                        verify_runtime._VERIFIER_ASSERTION_DIAGNOSTICS,
                    )

    def test_exact_set_gate_rejects_nested_helper_calls(self) -> None:
        """Break caught: a helper call hidden in a PL/pgSQL body escapes the top-level AST inventory."""
        from runtime import verify_runtime

        sql_directory = PROJECT_ROOT / "runtime" / "sql"
        schema_sql = (sql_directory / "assert_schema_contract.sql").read_text(encoding="utf-8")
        capability_sql = (sql_directory / "assert_capabilities.sql").read_text(encoding="utf-8")
        moved_call = (
            "SELECT pg_temp.expect_sqlstate('42501', "
            "'INSERT INTO lead.lead (tenant_id) SELECT NULL::uuid WHERE false', "
            "'query role INSERT');"
        )
        mutations = {
            "direct-do-call": capability_sql
            + """
DO $hidden_helper_call$
BEGIN
    PERFORM pg_temp.expect_sqlstate('42501', 'SELECT 1', 'hidden body assertion');
END
$hidden_helper_call$;
""",
            "dynamic-sql-string": capability_sql
            + """
DO $hidden_dynamic_helper_call$
BEGIN
    EXECUTE 'SELECT pg_temp.expect_sqlstate(''42501'', ''SELECT 1'', ''hidden dynamic assertion'')';
END
$hidden_dynamic_helper_call$;
""",
            "dollar-quoted-dynamic-sql": capability_sql
            + """
DO $hidden_dollar_helper_call$
BEGIN
    EXECUTE $dynamic_sql$
        SELECT pg_temp.expect_sqlstate('42501', 'SELECT 1', 'hidden dollar assertion')
    $dynamic_sql$;
END
$hidden_dollar_helper_call$;
""",
            "quoted-case-and-whitespace": capability_sql
            + """
DO $hidden_quoted_helper_call$
BEGIN
    PERFORM "PG_TEMP" /* hidden */ . "EXPECT_SQLSTATE"(
        '42501', 'SELECT 1', 'hidden quoted assertion'
    );
END
$hidden_quoted_helper_call$;
""",
            "begin-atomic-function-body": capability_sql.replace(
                moved_call,
                """CREATE FUNCTION pg_temp.hidden_verifier_probe()
RETURNS void
LANGUAGE SQL
BEGIN ATOMIC
    SELECT pg_temp.expect_sqlstate(
        '42501',
        'INSERT INTO lead.lead (tenant_id) SELECT NULL::uuid WHERE false',
        'query role INSERT'
    );
END;""",
                1,
            ),
            "unicode-escaped-body-call": capability_sql
            + r"""
DO $hidden_unicode_helper_call$
BEGIN
    PERFORM U&"pg\005Ftemp" . U&"expect\005Fsqlstate"(
        '42501', 'SELECT 1', 'hidden unicode assertion'
    );
END
$hidden_unicode_helper_call$;
""",
            "custom-uescape-qualifier-body-call": capability_sql
            + r"""
DO $hidden_custom_escape_helper_call$
BEGIN
    PERFORM U&"pg!005Ftemp" UESCAPE '!' . expect_sqlstate(
        '42501', 'SELECT 1', 'hidden custom escape assertion'
    );
END
$hidden_custom_escape_helper_call$;
""",
            "custom-uescape-qualifier-recursive-string": capability_sql
            + r"""
DO $hidden_custom_escape_dynamic_call$
BEGIN
    EXECUTE $hidden_custom_escape_sql$
        SELECT U&"pg!005Ftemp" UESCAPE '!' . expect_sqlstate(
            '42501', 'SELECT 1', 'hidden custom escape dynamic assertion'
        )
    $hidden_custom_escape_sql$;
END
$hidden_custom_escape_dynamic_call$;
""",
            "custom-uescape-function-body-call": capability_sql
            + r"""
DO $hidden_custom_function_escape_call$
BEGIN
    PERFORM pg_temp . U&"expect!005Fsqlstate" UESCAPE '!'(
        '42501', 'SELECT 1', 'hidden custom function escape assertion'
    );
END
$hidden_custom_function_escape_call$;
""",
            "unicode-escaped-sql-string-body": capability_sql
            + r"""
DO $hidden_unicode_sql_string$
BEGIN
    EXECUTE U&'SELECT pg!005Ftemp.expect!005Fsqlstate(''42501'', ''SELECT 1'', ''query role INSERT'')' UESCAPE '!';
END
$hidden_unicode_sql_string$;
""",
            "unicode-escaped-sql-string-recursive": capability_sql
            + r"""
DO $hidden_recursive_unicode_sql_string$
BEGIN
    EXECUTE $hidden_outer_sql$
        SELECT U&'SELECT pg!005Ftemp.expect!005Fsqlstate(''42501'', ''SELECT 1'', ''query role INSERT'')' UESCAPE '!'
    $hidden_outer_sql$;
END
$hidden_recursive_unicode_sql_string$;
""",
        }
        self.assertTrue(all(mutated != capability_sql for mutated in mutations.values()))
        for mutation_name, mutated in mutations.items():
            with self.subTest(mutation=mutation_name):
                inventory = _inventory_verifier_assertions(mutated)
                self.assertTrue(inventory.unresolved_expect_calls)
                with self.assertRaises(AssertionError):
                    self._assert_verifier_inventory_matches_map(
                        {
                            "assert_schema_contract.sql": schema_sql,
                            "assert_capabilities.sql": mutated,
                        },
                        verify_runtime._VERIFIER_ASSERTION_DIAGNOSTICS,
                    )

    def test_exact_set_gate_rejects_quoted_helper_identity(self) -> None:
        """Break caught: quoted uppercase names impersonate the exact temporary helper."""
        from runtime import verify_runtime

        sql_directory = PROJECT_ROOT / "runtime" / "sql"
        schema_sql = (sql_directory / "assert_schema_contract.sql").read_text(
            encoding="utf-8"
        )
        capability_sql = (sql_directory / "assert_capabilities.sql").read_text(
            encoding="utf-8"
        )
        existing_call = (
            "SELECT pg_temp.expect_sqlstate('42501', "
            "'INSERT INTO lead.lead (tenant_id) SELECT NULL::uuid WHERE false', "
            "'query role INSERT');"
        )
        quoted_call = existing_call.replace(
            "pg_temp.expect_sqlstate",
            '"PG_TEMP"."EXPECT_SQLSTATE"',
            1,
        )
        mutations = {
            "replaced-call": capability_sql.replace(existing_call, quoted_call, 1),
            "replaced-helper-definition": capability_sql.replace(
                "CREATE FUNCTION pg_temp.expect_sqlstate(",
                'CREATE FUNCTION "PG_TEMP"."EXPECT_SQLSTATE"(',
                1,
            ),
            "additional-call": capability_sql
            + """
SELECT "PG_TEMP" /* hidden */ . "EXPECT_SQLSTATE"(
    '42501', 'SELECT 1', 'query role INSERT'
);
""",
        }
        self.assertTrue(all(mutated != capability_sql for mutated in mutations.values()))
        for mutation_name, mutated in mutations.items():
            with self.subTest(mutation=mutation_name):
                inventory = _inventory_verifier_assertions(mutated)
                self.assertTrue(inventory.unresolved_expect_calls)
                with self.assertRaises(AssertionError):
                    self._assert_verifier_inventory_matches_map(
                        {
                            "assert_schema_contract.sql": schema_sql,
                            "assert_capabilities.sql": mutated,
                        },
                        verify_runtime._VERIFIER_ASSERTION_DIAGNOSTICS,
                    )

    def test_verifier_assertion_inventory_is_case_and_layout_independent(self) -> None:
        """Break caught: case changes or USING MESSAGE hide a new assertion from the exact-set gate."""
        function_inventory = _inventory_verifier_assertions(
            "select PG_TEMP.ExPeCt_SqLsTaTe('42501', 'SELECT 1', 'case-hidden function drift');"
        )
        self.assertEqual(function_inventory.labels, {"case-hidden function drift"})
        self.assertEqual(function_inventory.expect_call_count, 1)
        self.assertEqual(function_inventory.unresolved_expect_calls, ())
        self.assertEqual(function_inventory.unresolved_assertions, ())

        message_inventory = _inventory_verifier_assertions(
            """
            DO $body$
            BEGIN
                raise exception using message =
                    'AsSeRtIoN=case-hidden message drift ExPeCtEd=1 actual=2';
            END
            $body$;
            """
        )
        self.assertEqual(message_inventory.labels, {"case-hidden message drift"})
        self.assertEqual(message_inventory.expect_call_count, 0)
        self.assertEqual(message_inventory.unresolved_expect_calls, ())
        self.assertEqual(message_inventory.unresolved_assertions, ())

    def test_verifier_assertion_inventory_rejects_unresolved_calls_and_literals(self) -> None:
        """Break caught: a dynamic diagnostic source silently escapes the closed assertion inventory."""
        invalid_sql = (
            "SELECT pg_temp.expect_sqlstate('42501', 'SELECT 1');"
            "SELECT pg_temp.expect_sqlstate('42501', 'SELECT 1', 42);"
            "SELECT pg_temp.expect_sqlstate('42501', 'SELECT 1', 'dynamic ' || current_user);"
            "DO $$ BEGIN RAISE EXCEPTION USING MESSAGE = 'assertion=missing expected marker'; END $$;"
        )
        inventory = _inventory_verifier_assertions(invalid_sql)
        self.assertEqual(inventory.expect_call_count, 3)
        self.assertEqual(len(inventory.unresolved_expect_calls), 3)
        self.assertEqual(len(inventory.unresolved_assertions), 1)

        generic_template = _inventory_verifier_assertions(
            "DO $$ BEGIN RAISE EXCEPTION 'assertion=% expected SQLSTATE=% actual=%', 'x', '1', '2'; END $$;"
        )
        self.assertEqual(generic_template.labels, set())
        self.assertEqual(len(generic_template.unresolved_assertions), 1)

    def test_failed_initial_and_noop_verifiers_capture_logs_before_cleanup_without_summary(self) -> None:
        """Break caught: a failed verifier skips its only safe diagnostic capture or parses a false PASS."""
        from runtime import verify_runtime

        for failed_phase in ("initial", "noop"):
            with self.subTest(failed_phase=failed_phase), tempfile.TemporaryDirectory() as temporary_directory:
                calls: list[Path] = []
                output_directory = Path(temporary_directory) / "evidence"

                def failing_verifier_stage(
                    command: list[str],
                    *,
                    evidence_path: Path,
                    cwd: Path,
                    timeout_seconds: float,
                ) -> int:
                    calls.append(evidence_path)
                    return_code = self._successful_runtime_stage(
                        command,
                        evidence_path=evidence_path,
                        cwd=cwd,
                        timeout_seconds=timeout_seconds,
                    )
                    is_noop_wait = evidence_path.name == "verifier-wait.json" and evidence_path.parent.name == "noop"
                    is_initial_wait = evidence_path.name == "verifier-wait.json" and evidence_path.parent.name != "noop"
                    should_fail = is_noop_wait if failed_phase == "noop" else is_initial_wait
                    if should_fail:
                        recorded = json.loads(evidence_path.read_text(encoding="utf-8"))
                        recorded.update({"returncode": 3, "status": "failed", "timedOut": False})
                        evidence_path.write_text(json.dumps(recorded), encoding="utf-8")
                        return 3
                    if "logs" in command and command[-1] == "verifier":
                        is_noop_logs = evidence_path.name == "noop-verifier-logs.json"
                        should_emit_failure = is_noop_logs if failed_phase == "noop" else not is_noop_logs
                        if should_emit_failure:
                            recorded = json.loads(evidence_path.read_text(encoding="utf-8"))
                            recorded["stdout"] = (
                                "verifier | psql:/runtime/sql/assert_capabilities.sql:77: ERROR: "
                                "assertion=query role INSERT expected SQLSTATE=42501 actual=success "
                                "password=hunter2 postgresql://user:secret@db.internal/law "
                                "/private/tmp/runtime token=top-secret"
                            )
                            evidence_path.write_text(json.dumps(recorded), encoding="utf-8")
                    return return_code

                result = verify_runtime.run_runtime_verification(
                    PROJECT_ROOT,
                    output_directory,
                    runs=2,
                    stage_runner=failing_verifier_stage,
                )

                failed_run = result["runs"][0]
                failed_service = failed_run["noopVerifier"] if failed_phase == "noop" else failed_run["verifier"]
                self.assertEqual(result["status"], "FAILED")
                self.assertEqual(result["reason"], "compose_up_failed")
                self.assertEqual(failed_service["waitReturnCode"], 3)
                self.assertEqual(failed_service["logsReturnCode"], 0)
                self.assertIsNone(failed_service["summary"])
                logs_name = "noop-verifier-logs.json" if failed_phase == "noop" else "verifier-logs.json"
                logs_index = next(index for index, path in enumerate(calls) if path.name == logs_name)
                cleanup_index = next(index for index, path in enumerate(calls) if path.name == "compose-down.json")
                self.assertLess(logs_index, cleanup_index)
                diagnostic_path = (
                    "run-01/noop/verifier-wait.json"
                    if failed_phase == "noop"
                    else "run-01/verifier-wait.json"
                )
                self.assertEqual(
                    dict(result.ci_stage_diagnostics)[diagnostic_path],
                    "verifier_capability_query_insert",
                )

    def test_fingerprint_script_failure_binds_to_the_failed_verifier_wait(self) -> None:
        """Break caught: an exact fingerprint-script error remains a generic record-missing code."""
        from runtime import verify_runtime

        hostile = (
            "password=hunter2 postgresql://user:secret@db.internal/law "
            "/private/tmp/runtime token=top-secret"
        )
        with tempfile.TemporaryDirectory() as temporary_directory:
            output_directory = Path(temporary_directory) / "evidence"

            def failing_fingerprint_stage(
                command: list[str],
                *,
                evidence_path: Path,
                cwd: Path,
                timeout_seconds: float,
            ) -> int:
                return_code = self._successful_runtime_stage(
                    command,
                    evidence_path=evidence_path,
                    cwd=cwd,
                    timeout_seconds=timeout_seconds,
                )
                is_initial_wait = (
                    evidence_path.name == "verifier-wait.json"
                    and evidence_path.parent.name == "run-01"
                )
                if is_initial_wait:
                    recorded = json.loads(evidence_path.read_text(encoding="utf-8"))
                    recorded.update({"returncode": 3, "status": "failed", "timedOut": False})
                    evidence_path.write_text(json.dumps(recorded), encoding="utf-8")
                    return 3
                if evidence_path.name == "verifier-logs.json" and evidence_path.parent.name == "run-01":
                    recorded = json.loads(evidence_path.read_text(encoding="utf-8"))
                    recorded["stdout"] = (
                        "psql:/runtime/sql/schema_fingerprint.sql:37: ERROR: "
                        "fingerprint query failed " + hostile
                    )
                    evidence_path.write_text(json.dumps(recorded), encoding="utf-8")
                return return_code

            result = verify_runtime.run_runtime_verification(
                PROJECT_ROOT,
                output_directory,
                runs=2,
                stage_runner=failing_fingerprint_stage,
            )

        self.assertEqual(result["status"], "FAILED")
        self.assertEqual(result["reason"], "compose_up_failed")
        diagnostics = dict(result.ci_stage_diagnostics)
        diagnostic = diagnostics["run-01/verifier-wait.json"]
        self.assertEqual(diagnostic, "verifier_fingerprint_error")
        self.assertEqual(set(diagnostics), {"run-01/verifier-wait.json"})
        for secret in ("hunter2", "db.internal", "/private/tmp", "top-secret"):
            self.assertNotIn(secret, diagnostic)

    def test_typed_producer_distinguishes_exit_124_from_a_real_timeout(self) -> None:
        """Break caught: an ordinary verifier exit 124 is mislabeled as a subprocess timeout."""
        from runtime import verify_runtime

        wait_path = "run-01/verifier-wait.json"
        hostile = (
            "password=hunter2 postgresql://user:secret@db.internal/law "
            "/private/tmp/runtime token=top-secret"
        )
        for timed_out, expected_diagnostic in (
            (False, "verifier_capability_query_insert"),
            (True, None),
        ):
            with self.subTest(timed_out=timed_out), tempfile.TemporaryDirectory() as temporary_directory:
                output_directory = Path(temporary_directory) / "evidence"

                def captured_stage(
                    command: list[str],
                    *,
                    evidence_path: Path,
                    cwd: Path,
                    timeout_seconds: float,
                ) -> verify_runtime.CapturedStageResult:
                    return_code = self._successful_runtime_stage(
                        command,
                        evidence_path=evidence_path,
                        cwd=cwd,
                        timeout_seconds=timeout_seconds,
                    )
                    recorded = json.loads(evidence_path.read_text(encoding="utf-8"))
                    stage_timed_out = False
                    is_failed_wait = (
                        evidence_path.name == "verifier-wait.json"
                        and evidence_path.parent.name == "run-01"
                    )
                    if is_failed_wait:
                        return_code = 124
                        stage_timed_out = timed_out
                        recorded.update(
                            {
                                "returncode": return_code,
                                "status": "timed_out" if timed_out else "failed",
                                "timedOut": timed_out,
                            }
                        )
                    elif (
                        evidence_path.name == "verifier-logs.json"
                        and evidence_path.parent.name == "run-01"
                    ):
                        recorded["stdout"] = (
                            "verifier | psql:/runtime/sql/assert_capabilities.sql:77: ERROR:  "
                            "assertion=query role INSERT expected SQLSTATE=42501 actual=success "
                            + hostile
                        )
                        recorded["timedOut"] = False
                    else:
                        recorded["timedOut"] = False
                    evidence_path.write_text(json.dumps(recorded), encoding="utf-8")
                    stdout_bytes = str(recorded.get("stdout", "")).encode("utf-8")
                    stderr_bytes = str(recorded.get("stderr", "")).encode("utf-8")
                    return verify_runtime.CapturedStageResult(
                        exit_code=return_code,
                        timed_out=stage_timed_out,
                        stdout_sha256=hashlib.sha256(stdout_bytes).hexdigest(),
                        stderr_sha256=hashlib.sha256(stderr_bytes).hexdigest(),
                    )

                with mock.patch.object(
                    verify_runtime,
                    "run_checked_result",
                    side_effect=captured_stage,
                ):
                    result = verify_runtime.run_runtime_verification(
                        PROJECT_ROOT,
                        output_directory,
                        runs=2,
                    )

                diagnostics = dict(result.ci_stage_diagnostics)
                self.assertEqual(diagnostics.get(wait_path), expected_diagnostic)
                summary = verify_runtime.build_ci_runtime_summary(
                    result,
                    git_commit="d" * 40,
                    manifest={},
                )
                wait_stage = next(
                    stage
                    for stage in summary["runs"][0]["stages"]
                    if stage["stageName"] == "verifier-wait"
                )
                self.assertEqual(wait_stage["exitCode"], 124)
                self.assertEqual(wait_stage["timedOut"], timed_out)
                self.assertEqual(
                    wait_stage["diagnosticCode"],
                    "timed_out" if timed_out else "verifier_capability_query_insert",
                )
                safe_output = json.dumps(summary, sort_keys=True)
                for secret in ("hunter2", "db.internal", "/private/tmp", "top-secret"):
                    self.assertNotIn(secret, safe_output)

    def test_injected_stage_fallback_rejects_incomplete_or_inconsistent_timeout_metadata(self) -> None:
        """Break caught: an untyped test runner can forge timeout state from only an exit code."""
        from runtime import verify_runtime

        invalid_records = (
            {},
            {"returncode": 3, "status": "failed"},
            {"returncode": 3, "status": "passed", "timedOut": False},
            {"returncode": 3, "status": "timed_out", "timedOut": True},
            {"returncode": 124, "status": "failed", "timedOut": True},
            {"returncode": 124, "status": "timed_out", "timedOut": False},
            {"returncode": 17, "status": "failed", "timedOut": False},
        )
        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence_path = Path(temporary_directory) / "verifier-wait.json"
            for index, record in enumerate(invalid_records):
                with self.subTest(index=index):
                    evidence_path.write_text(json.dumps(record), encoding="utf-8")
                    self.assertIsNone(
                        verify_runtime._verifier_wait_metadata(
                            None,
                            evidence_path=evidence_path,
                            expected_return_code=3,
                            allow_evidence_fallback=True,
                        )
                    )

            evidence_path.write_text("{not-json", encoding="utf-8")
            self.assertIsNone(
                verify_runtime._verifier_wait_metadata(
                    None,
                    evidence_path=evidence_path,
                    expected_return_code=3,
                    allow_evidence_fallback=True,
                )
            )

            evidence_path.write_text(
                json.dumps({"returncode": 124, "status": "timed_out", "timedOut": True}),
                encoding="utf-8",
            )
            typed_non_timeout = verify_runtime.CapturedStageResult(
                exit_code=124,
                timed_out=False,
                stdout_sha256="a" * 64,
                stderr_sha256="b" * 64,
            )
            self.assertEqual(
                verify_runtime._verifier_wait_metadata(
                    typed_non_timeout,
                    evidence_path=evidence_path,
                    expected_return_code=124,
                    allow_evidence_fallback=True,
                ),
                (124, False),
            )
            self.assertIsNone(
                verify_runtime._verifier_wait_metadata(
                    None,
                    evidence_path=evidence_path,
                    expected_return_code=124,
                    allow_evidence_fallback=False,
                )
            )

    def test_temporary_files_are_outside_the_repository_and_removed_when_cleanup_raises(self) -> None:
        """Break caught: a cleanup/evidence exception leaves a password-bearing temp file in the repository."""
        from runtime import verify_runtime

        temporary_paths: set[Path] = set()
        with tempfile.TemporaryDirectory() as temporary_directory:
            output_directory = Path(temporary_directory) / "evidence"

            def cleanup_exception_stage(
                command: list[str],
                *,
                evidence_path: Path,
                cwd: Path,
                timeout_seconds: float,
            ) -> int:
                temporary_paths.add(Path(command[command.index("--env-file") + 1]))
                temporary_paths.add(Path(command[[index for index, value in enumerate(command) if value == "-f"][-1] + 1]))
                if "down" in command:
                    raise RuntimeError("evidence writer failed during cleanup")
                stdout = json.dumps([{"Service": command[-1], "ExitCode": 0}]) if "ps" in command else ""
                evidence_path.parent.mkdir(parents=True, exist_ok=True)
                evidence_path.write_text(json.dumps({"stdout": stdout}), encoding="utf-8")
                return 0

            with self.assertRaisesRegex(RuntimeError, "evidence writer failed during cleanup"):
                verify_runtime.run_runtime_verification(
                    PROJECT_ROOT,
                    output_directory,
                    runs=2,
                    stage_runner=cleanup_exception_stage,
                )

        self.assertTrue(temporary_paths)
        self.assertTrue(all(not path.is_relative_to(PROJECT_ROOT) and not path.exists() for path in temporary_paths))

    def test_compose_rendering_uses_only_locked_images_and_orders_empty_database_stages(self) -> None:
        """Break caught: an unlocked image or validate-before-migrate bypasses the empty-db chain."""
        from runtime import verify_runtime

        lock = verify_runtime.load_toolchain_lock(PROJECT_ROOT / "runtime" / "toolchain.lock.json")
        rendered = json.loads(verify_runtime.render_compose_override(lock))
        expected_images = {
            "postgres": "postgres@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280",
            "flyway": "redgate/flyway@sha256:c093a247b19ff09a6a72774569171ee355fff2ae44ceba4a4aa4b23235d99c93",
            "verifier": "postgres@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280",
        }
        self.assertEqual(
            {service: config["image"] for service, config in rendered["services"].items()},
            expected_images,
        )
        environment = verify_runtime.render_compose_environment(lock)
        self.assertIn("POSTGRES_IMAGE=postgres@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280", environment)
        self.assertIn("FLYWAY_IMAGE=redgate/flyway@sha256:c093a247b19ff09a6a72774569171ee355fff2ae44ceba4a4aa4b23235d99c93", environment)

        compose_path = PROJECT_ROOT / "runtime" / "compose.yaml"
        compose_text = compose_path.read_text(encoding="utf-8")
        self.assertEqual({"postgres", "flyway", "verifier"}, set(verify_runtime.compose_service_names(compose_text)))
        self.assertIn("../generated/db/migration:/flyway/sql:ro", compose_text)
        self.assertIn("pg_isready", compose_text)
        self.assertIn("condition: service_healthy", compose_text)
        self.assertIn("condition: service_completed_successfully", compose_text)
        self.assertLess(compose_text.index(" migrate\n"), compose_text.index(" validate\n"))
        self.assertIn("-validateOnMigrate=true migrate", compose_text)
        self.assertIn("-ignoreMigrationPatterns= validate", compose_text)
        self.assertIn("/runtime/sql/assert_schema_contract.sql", compose_text)
        self.assertIn("/runtime/sql/assert_capabilities.sql", compose_text)
        self.assertIn("/runtime/sql/schema_fingerprint.sql", compose_text)
        self.assertGreaterEqual(compose_text.count("psql -X -v ON_ERROR_STOP=1"), 3)
        verifier_psql_lines = [
            line.strip() for line in compose_text.splitlines() if "psql -X" in line
        ]
        self.assertEqual(len(verifier_psql_lines), 3)
        self.assertNotIn("VERBOSITY=sqlstate", verifier_psql_lines[0])
        self.assertNotIn("VERBOSITY=sqlstate", verifier_psql_lines[1])
        self.assertIn("-v VERBOSITY=sqlstate", verifier_psql_lines[2])
        self.assertEqual(compose_text.count("-v VERBOSITY=sqlstate"), 1)
        self.assertIn("PGPASSWORD: ${RUNTIME_POSTGRES_PASSWORD", compose_text)
        self.assertIn("PGUSER: postgres", compose_text)
        self.assertIn("postgres-data:/var/lib/postgresql", compose_text)
        self.assertNotIn("postgres-data:/var/lib/postgresql/data", compose_text)
        for option in (
            "-connectRetries=30",
            "-validateMigrationNaming=true",
            "-cleanDisabled=true",
            "-baselineOnMigrate=false",
            "-defaultSchema=platform_meta",
            "-schemas=identity,audit,responsibility,execution,external_action,evidence,party,lead,opportunity,conflict,contract,transfer,platform_meta",
            "-locations=filesystem:/flyway/sql",
            "-placeholders.app_command_role=law_app_command",
            "-placeholders.app_worker_role=law_app_worker",
            "-placeholders.app_query_role=law_app_query",
            "-placeholders.audit_append_role=law_audit_append",
        ):
            with self.subTest(option=option):
                self.assertIn(option, compose_text)

    def test_missing_docker_is_recorded_as_a_blocked_runtime_attempt_and_still_cleans_up(self) -> None:
        """Break caught: unavailable Docker is mislabeled as verifier failure or skips cleanup."""
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            output_directory = Path(temporary_directory) / "evidence"
            result = verify_runtime.run_runtime_verification(
                PROJECT_ROOT,
                output_directory,
                runs=2,
                compose_command=("definitely-not-a-docker-command", "compose"),
            )

            self.assertEqual(result["status"], "BLOCKED")
            self.assertEqual(result["reason"], "docker_compose_unavailable")
            self.assertEqual(len(result["runs"]), 2)
            for index in range(1, 3):
                up = json.loads((output_directory / f"run-{index:02d}" / "postgres-start.json").read_text(encoding="utf-8"))
                down = json.loads((output_directory / f"run-{index:02d}" / "compose-down.json").read_text(encoding="utf-8"))
                self.assertEqual(up["returncode"], 127)
                self.assertEqual(down["returncode"], 127)
                self.assertIn("could not be started", up["stderr"])
                self.assertEqual(down["command"][-3:], ["down", "--volumes", "--remove-orphans"])

    def test_images_are_digest_pinned(self) -> None:
        from runtime import verify_runtime

        lock_path = PROJECT_ROOT / "runtime" / "toolchain.lock.json"
        lock = verify_runtime.load_toolchain_lock(lock_path)
        self.assertEqual({entry["image"] for entry in lock["images"]}, {"postgres", "redgate/flyway"})
        self.assertTrue(all(entry["tag"] != "latest" for entry in lock["images"]))
        self.assertTrue(all(entry["digest"].startswith("sha256:") for entry in lock["images"]))

        invalid_locks = (
            {"images": [{"image": "postgres", "tag": "latest", "digest": "sha256:" + "a" * 64, "resolvedAt": "2026-08-28T00:00:00Z"}]},
            {"images": [{"image": "postgres", "tag": "18", "resolvedAt": "2026-08-28T00:00:00Z"}]},
            {"images": [{"image": "postgres", "tag": "18", "digest": "sha256:" + "a" * 64, "resolvedAt": "2026-08-28T00:00:00.123Z"}]},
            {"images": [{"image": "postgres", "tag": "18", "digest": "sha256:" + "a" * 64, "resolvedAt": "2026-08-28T00:00:00+00:00"}]},
        )
        for invalid_lock in invalid_locks:
            invalid_path = Path(self._temp_dir()) / "invalid-lock.json"
            invalid_path.write_text(json.dumps(invalid_lock), encoding="utf-8")
            with self.assertRaises(ValueError):
                verify_runtime.load_toolchain_lock(invalid_path)

    def test_run_id_rejects_path_traversal(self) -> None:
        from runtime import verify_runtime

        self.assertEqual(verify_runtime.validate_run_id("run-a-02"), "run-a-02")
        for invalid in ("../run", "run/a", "run_a", "Run-A", "", "run a"):
            with self.subTest(invalid=invalid):
                with self.assertRaises(ValueError):
                    verify_runtime.validate_run_id(invalid)

    def test_evidence_path_is_workspace_scoped(self) -> None:
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            repository = Path(temporary_directory)
            allowed_root = repository / ".artifacts" / "schema-runtime"
            allowed_root.mkdir(parents=True)
            self.assertEqual(
                verify_runtime.evidence_dir(repository, "run-a"),
                allowed_root / "run-a",
            )
            for candidate in ("../outside", "/tmp/outside", "run-a/../../outside"):
                with self.subTest(candidate=candidate):
                    with self.assertRaises(ValueError):
                        verify_runtime.evidence_dir(repository, candidate)

            (allowed_root / "escape").symlink_to(repository.parent, target_is_directory=True)
            with self.assertRaises(ValueError):
                verify_runtime.evidence_dir(repository, "escape/outside")

    def test_evidence_path_accepts_the_documented_cli_relative_path_only_inside_the_workspace_root(self) -> None:
        """Break caught: the documented relative evidence path is rejected or escapes containment."""
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            repository = Path(temporary_directory)
            invocation_directory = repository / "database" / "schema-contract-52-plus-2"
            invocation_directory.mkdir(parents=True)
            self.assertEqual(
                verify_runtime.evidence_dir(
                    repository,
                    "../../.artifacts/schema-runtime",
                    current_directory=invocation_directory,
                ),
                repository / ".artifacts" / "schema-runtime",
            )

    def test_normalized_fingerprints_match(self) -> None:
        from runtime import verify_runtime

        snapshot_a = {"tables": [{"name": "orders", "columns": ["id", "total"]}], "version": 1}
        snapshot_b = {"version": 1, "tables": [{"columns": ["id", "total"], "name": "orders"}]}
        self.assertEqual(
            verify_runtime.normalize_snapshot(snapshot_a),
            verify_runtime.normalize_snapshot(snapshot_b),
        )
        with self.assertRaises(ValueError):
            verify_runtime.normalize_snapshot({"tables": [{1: "non-string key"}]})

    def test_runtime_sql_contracts_cover_catalog_privilege_and_transaction_facts(self) -> None:
        """Break caught: a required 52-plus-2 catalog, capability, or SQLSTATE probe silently disappears."""
        sql_directory = PROJECT_ROOT / "runtime" / "sql"
        required = {
            "assert_schema_contract.sql",
            "assert_capabilities.sql",
            "schema_fingerprint.sql",
            "failures/extra_managed_table.sql",
            "failures/forbidden_delete_grant.sql",
            "failures/missing_mutation_guard.sql",
        }
        self.assertTrue(all((sql_directory / relative).is_file() for relative in required))
        schema_contract = (sql_directory / "assert_schema_contract.sql").read_text(encoding="utf-8")
        capabilities = (sql_directory / "assert_capabilities.sql").read_text(encoding="utf-8")
        fingerprint = (sql_directory / "schema_fingerprint.sql").read_text(encoding="utf-8")

        for literal in (
            "13 managed schemas",
            "52 application tables",
            "2 platform_meta tables",
            "public schema table count",
            "19 successful migrations",
            "maximum migration version",
            "206 composite foreign keys",
            "53 mutation guards",
            "NO ACTION",
            "MATCH SIMPLE",
            "tenant_id",
            "four distinct capability roles",
            "NOLOGIN",
            "parent role memberships",
            "PRIMARY",
            "BLOCKED",
            "52-plus-2-v1",
            "revision=0",
            "32 zero bytes",
        ):
            with self.subTest(literal=literal):
                self.assertIn(literal, schema_contract)
        self.assertIn("platform_meta.flyway_schema_history", schema_contract)
        self.assertIn("version::integer = 840", schema_contract)
        self.assertIn("RAISE EXCEPTION", schema_contract)
        self.assertIn("expected=", schema_contract)
        self.assertIn("actual=", schema_contract)

        for role in ("law_app_query", "law_audit_append", "law_app_worker", "law_app_command"):
            self.assertIn(f"SET LOCAL ROLE {role}", capabilities)
        for sqlstate in ("23503", "55000", "40001", "42501"):
            self.assertIn(sqlstate, capabilities)
        self.assertIn("ROLLBACK", capabilities)
        self.assertIn("GET STACKED DIAGNOSTICS", capabilities)
        self.assertIn("pg_get_functiondef", fingerprint)
        self.assertIn("platform_meta.flyway_schema_history", fingerprint)
        self.assertIn("server_version", fingerprint)
        self.assertIn("'postgresVersion', pg_catalog.version()", fingerprint)
        for unstable in ("oid::text", "installed_on", "execution_time", "current_database()"):
            self.assertNotIn(unstable, fingerprint)

    def test_successful_migration_count_includes_only_versioned_sql_migrations(self) -> None:
        """Break caught: Flyway SCHEMA or BASELINE markers inflate the 19-migration count."""
        schema_contract = (PROJECT_ROOT / "runtime" / "sql" / "assert_schema_contract.sql").read_text(
            encoding="utf-8"
        )
        count_query = re.search(
            r"SELECT\s+count\(\*\)\s+INTO\s+actual_count\s+"
            r"FROM\s+platform_meta\.flyway_schema_history\s+WHERE\s+(?P<predicate>[^;]+);",
            schema_contract,
            flags=re.IGNORECASE,
        )
        self.assertIsNotNone(count_query, "the runtime contract must count Flyway history rows")
        predicate = count_query.group("predicate")

        rows = [(f"{version:03d}", "SQL", 1) for version in range(1, 20)]
        rows.extend(
            [
                (None, "SCHEMA", 1),
                ("000", "BASELINE", 1),
                ("999", "SQL", 0),
            ]
        )
        with sqlite3.connect(":memory:") as database:
            database.execute("ATTACH DATABASE ':memory:' AS platform_meta")
            database.execute(
                "CREATE TABLE platform_meta.flyway_schema_history "
                "(version TEXT, type TEXT, success INTEGER)"
            )
            database.executemany(
                "INSERT INTO platform_meta.flyway_schema_history (version, type, success) VALUES (?, ?, ?)",
                rows,
            )
            actual_count = database.execute(
                f"SELECT count(*) FROM platform_meta.flyway_schema_history WHERE {predicate}"
            ).fetchone()[0]

        self.assertEqual(actual_count, 19)

    def test_success_result_captures_runtime_identity_and_normalizes_for_publication(self) -> None:
        """Break caught: a real PASS cannot prove exact images/versions or enter the closed evidence schema."""
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            result = verify_runtime.run_runtime_verification(
                PROJECT_ROOT,
                Path(temporary_directory) / "evidence",
                runs=2,
                stage_runner=self._successful_runtime_stage,
            )

        self.assertEqual(result["status"], "PASSED")
        self.assertEqual(
            result["runtimeIdentity"]["flywayVersion"],
            "Flyway Community Edition 13.4.0 by Redgate",
        )
        self.assertEqual(
            [image["image"] for image in result["runtimeIdentity"]["images"]],
            ["postgres", "redgate/flyway"],
        )
        lock = verify_runtime.load_toolchain_lock(PROJECT_ROOT / "runtime" / "toolchain.lock.json")
        manifest = json.loads((PROJECT_ROOT / "generated" / "schema-contract-manifest.json").read_text(encoding="utf-8"))
        normalized = verify_runtime.normalize_runtime_result_for_publication(
            result,
            lock,
            manifest,
            git_commit="a" * 40,
            verified_at_utc="2026-08-28T16:30:00Z",
        )
        self.assertEqual(set(normalized), set(verify_runtime._PUBLISH_SCHEMA_FIELDS))
        self.assertEqual(normalized["gitCommit"], "a" * 40)
        self.assertEqual([run["id"] for run in normalized["runs"]], ["run-01", "run-02"])
        self.assertEqual(normalized["runs"][0]["fingerprint"], normalized["runs"][1]["fingerprint"])
        self.assertEqual(len(normalized["failureScenarios"]), 5)

        mutations = (
            ("top-level", lambda candidate: candidate.update({"apiValidated": True})),
            ("run", lambda candidate: candidate["runs"][0].update({"unknownClaim": True})),
            ("service", lambda candidate: candidate["runs"][0]["flyway"].update({"unknownClaim": True})),
            ("summary", lambda candidate: candidate["runs"][0]["verifier"]["summary"].update({"unknownClaim": True})),
            ("runtime-identity", lambda candidate: candidate["runtimeIdentity"].update({"unknownClaim": True})),
            ("identity-image", lambda candidate: candidate["runtimeIdentity"]["images"][0].update({"unknownClaim": True})),
            ("failure", lambda candidate: candidate["failureScenarios"][0].update({"unknownClaim": True})),
        )
        for context, mutate in mutations:
            candidate = copy.deepcopy(result)
            mutate(candidate)
            with self.subTest(context=context):
                with self.assertRaises(ValueError):
                    verify_runtime.normalize_runtime_result_for_publication(
                        candidate,
                        lock,
                        manifest,
                        git_commit="a" * 40,
                        verified_at_utc="2026-08-28T16:30:00Z",
                    )

    def test_runtime_evidence_path_io_errors_are_structured(self) -> None:
        """Break caught: a regular file at an evidence directory leaks FileExistsError."""
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            output_path = Path(temporary_directory) / "evidence"
            output_path.write_text("occupied\n", encoding="utf-8")
            with self.assertRaises(verify_runtime.EvidenceIOError):
                verify_runtime.run_runtime_verification(
                    PROJECT_ROOT,
                    output_path,
                    runs=2,
                    stage_runner=self._successful_runtime_stage,
                )

        with tempfile.TemporaryDirectory() as temporary_directory:
            output_path = Path(temporary_directory) / "evidence"
            output_path.mkdir()
            (output_path / "run-01").write_text("occupied\n", encoding="utf-8")
            with self.assertRaises(verify_runtime.EvidenceIOError):
                verify_runtime.run_runtime_verification(
                    PROJECT_ROOT,
                    output_path,
                    runs=2,
                    stage_runner=self._successful_runtime_stage,
                )

    def test_cross_tenant_probe_qualifies_its_deferred_constraint_without_search_path(self) -> None:
        """Break caught: PostgreSQL resolves the deferred probe constraint outside the identity schema."""
        capabilities = (PROJECT_ROOT / "runtime" / "sql" / "assert_capabilities.sql").read_text(encoding="utf-8")

        self.assertIn(
            "SET CONSTRAINTS identity.fk_organization_unit__parent_organization_unit IMMEDIATE;",
            capabilities,
        )
        self.assertNotIn("SET search_path", capabilities)

    def test_failure_scenarios_are_phase_and_message_checked_and_run_a_is_noop_stable(self) -> None:
        """Break caught: expected SQL failures, a checksum drift, or a changed run-A fingerprint pass open."""
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            result = verify_runtime.run_runtime_verification(
                PROJECT_ROOT,
                Path(temporary_directory) / "evidence",
                runs=2,
                stage_runner=self._successful_runtime_stage,
            )

        self.assertEqual("PASSED", result["status"])
        self.assertIn("failureScenarios", result)
        self.assertEqual(5, len(result["failureScenarios"]))
        self.assertEqual(
            [
                "missing-role",
                "extra-managed-table",
                "forbidden-delete-grant",
                "missing-mutation-guard",
                "checksum-mismatch",
            ],
            [scenario["name"] for scenario in result["failureScenarios"]],
        )
        self.assertTrue(all(scenario["status"] == "PASSED" for scenario in result["failureScenarios"]))
        self.assertTrue(all(scenario["messageMatched"] for scenario in result["failureScenarios"]))
        self.assertTrue(all(scenario["actualPhase"] == scenario["expectedPhase"] for scenario in result["failureScenarios"]))
        self.assertEqual(
            result["runs"][0]["initialFingerprint"],
            result["runs"][0]["noopFingerprint"],
        )

    def test_different_valid_ab_fingerprints_fail_after_all_five_scenarios(self) -> None:
        """Break caught: two valid but different empty-database fingerprints claim runtime success."""
        from runtime import verify_runtime

        fingerprints = {
            "run-01": "0123456789abcdef0123456789abcdef",
            "run-02": "fedcba9876543210fedcba9876543210",
        }
        identity_captures: list[Path] = []

        def divergent_fingerprints(
            command: list[str],
            *,
            evidence_path: Path,
            cwd: Path,
            timeout_seconds: float,
        ) -> int:
            return_code = self._successful_runtime_stage(
                command,
                evidence_path=evidence_path,
                cwd=cwd,
                timeout_seconds=timeout_seconds,
            )
            if "runtime-identity" in evidence_path.parts:
                identity_captures.append(evidence_path)
            if evidence_path.name in {"verifier-logs.json", "noop-verifier-logs.json"}:
                run_id = "run-02" if "run-02" in evidence_path.parts else "run-01"
                recorded = json.loads(evidence_path.read_text(encoding="utf-8"))
                summary = json.loads(recorded["stdout"])
                summary["fingerprint"] = fingerprints[run_id]
                recorded["stdout"] = json.dumps(summary) + "\n"
                evidence_path.write_text(json.dumps(recorded), encoding="utf-8")
            return return_code

        repository_root = PROJECT_ROOT.parents[1]
        fixed_success_targets = (
            repository_root / "docs" / "evidence" / "schema-runtime" / "2026-08-28-postgresql-18-summary.json",
            repository_root / "docs" / "evidence" / "schema-runtime" / "2026-08-28-postgresql-18-report.md",
        )
        self.assertFalse(any(path.exists() for path in fixed_success_targets))
        with tempfile.TemporaryDirectory() as temporary_directory:
            result = verify_runtime.run_runtime_verification(
                PROJECT_ROOT,
                Path(temporary_directory) / "evidence",
                runs=2,
                stage_runner=divergent_fingerprints,
            )

        self.assertEqual(result["status"], "FAILED")
        self.assertEqual(result["reason"], "runtime_fingerprint_mismatch")
        self.assertEqual(
            [scenario["name"] for scenario in result["failureScenarios"]],
            [
                "missing-role",
                "extra-managed-table",
                "forbidden-delete-grant",
                "missing-mutation-guard",
                "checksum-mismatch",
            ],
        )
        self.assertTrue(all(scenario["status"] == "PASSED" for scenario in result["failureScenarios"]))
        self.assertNotIn("runtimeIdentity", result)
        self.assertEqual(identity_captures, [])
        self.assertFalse(any(path.exists() for path in fixed_success_targets))

    def test_invalid_or_empty_fingerprints_fail_the_positive_run_gate(self) -> None:
        """Break caught: two equal non-fingerprints are mistaken for a stable A/B schema."""
        from runtime import verify_runtime

        for invalid_fingerprint in ("", "same", "A" * 32, "g" * 32):
            with self.subTest(fingerprint=invalid_fingerprint):
                def invalid_fingerprint_stage(
                    command: list[str],
                    *,
                    evidence_path: Path,
                    cwd: Path,
                    timeout_seconds: float,
                ) -> int:
                    return_code = self._successful_runtime_stage(
                        command,
                        evidence_path=evidence_path,
                        cwd=cwd,
                        timeout_seconds=timeout_seconds,
                    )
                    if evidence_path.name in {"verifier-logs.json", "noop-verifier-logs.json"}:
                        recorded = json.loads(evidence_path.read_text(encoding="utf-8"))
                        summary = json.loads(recorded["stdout"])
                        summary["fingerprint"] = invalid_fingerprint
                        recorded["stdout"] = json.dumps(summary) + "\n"
                        evidence_path.write_text(json.dumps(recorded), encoding="utf-8")
                    return return_code

                with tempfile.TemporaryDirectory() as temporary_directory:
                    result = verify_runtime.run_runtime_verification(
                        PROJECT_ROOT,
                        Path(temporary_directory) / "evidence",
                        runs=2,
                        stage_runner=invalid_fingerprint_stage,
                    )

                self.assertEqual(result["status"], "FAILED")
                self.assertEqual(result["reason"], "compose_up_failed")
                self.assertEqual(result["failureScenarios"], [])
                self.assertNotIn("runtimeIdentity", result)

    def test_unexpected_failure_scenario_success_fails_closed(self) -> None:
        """Break caught: a corruption scenario that unexpectedly succeeds is reported as verification success."""
        from runtime import verify_runtime

        def everything_succeeds(
            command: list[str],
            *,
            evidence_path: Path,
            cwd: Path,
            timeout_seconds: float,
        ) -> int:
            del cwd, timeout_seconds
            stdout = ""
            if "ps" in command:
                stdout = json.dumps([{"Service": command[-1], "ExitCode": 0}])
            elif "logs" in command:
                stdout = '{"fingerprint":"0123456789abcdef0123456789abcdef","status":"PASSED"}\n'
            evidence_path.parent.mkdir(parents=True, exist_ok=True)
            evidence_path.write_text(json.dumps({"returncode": 0, "stderr": "", "stdout": stdout}), encoding="utf-8")
            return 0

        with tempfile.TemporaryDirectory() as temporary_directory:
            result = verify_runtime.run_runtime_verification(
                PROJECT_ROOT,
                Path(temporary_directory) / "evidence",
                runs=2,
                stage_runner=everything_succeeds,
            )

        self.assertEqual("FAILED", result["status"])
        self.assertEqual("failure_scenario_failed", result["reason"])
        self.assertTrue(any(scenario["status"] == "FAILED" for scenario in result["failureScenarios"]))

    def test_failed_stage_is_reported_without_claiming_success(self) -> None:
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence = Path(temporary_directory) / "failed-stage.json"
            return_code = verify_runtime.run_checked(
                [sys.executable, "-c", "import sys; print('stage failed'); sys.exit(17)"],
                evidence_path=evidence,
            )
            self.assertEqual(return_code, 17)
            recorded = json.loads(evidence.read_text(encoding="utf-8"))
            self.assertEqual(recorded["returncode"], 17)
            self.assertEqual(recorded["status"], "failed")
            self.assertIn("stage failed", recorded["stdout"])

    def test_timeout_is_bounded_and_persists_evidence(self) -> None:
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence = Path(temporary_directory) / "timeout-stage.json"
            started = time.monotonic()
            return_code = verify_runtime.run_checked(
                [sys.executable, "-c", "import time; time.sleep(10)"],
                evidence_path=evidence,
                timeout_seconds=0.05,
            )
            self.assertLess(time.monotonic() - started, 2)
            self.assertEqual(return_code, 124)
            recorded = json.loads(evidence.read_text(encoding="utf-8"))
            self.assertEqual(recorded["returncode"], 124)
            self.assertEqual(recorded["status"], "timed_out")
            self.assertTrue(recorded["timedOut"])

        for invalid_timeout in (0, -1, float("nan"), True, 301):
            with self.subTest(invalid_timeout=invalid_timeout):
                with self.assertRaises(ValueError):
                    verify_runtime.run_checked(
                        [sys.executable, "-c", "pass"],
                        evidence_path=Path("unused.json"),
                        timeout_seconds=invalid_timeout,
                    )

    def test_evidence_redacts_sensitive_command_and_output(self) -> None:
        from runtime import verify_runtime

        secrets = (
            "command-secret",
            "api-command-secret",
            "postgres-secret",
            "output-secret",
            "secret-output",
            "token-output",
            "api-output",
            "stderr-secret",
            "client-output",
            "bearer-secret",
            "url-secret",
        )
        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence = Path(temporary_directory) / "redacted-stage.json"
            return_code = verify_runtime.run_checked(
                [
                    sys.executable,
                    "-c",
                    "import sys; print('ordinary output password=output-secret secret: secret-output "
                    "token=token-output api_key=api-output'); "
                    "print('postgres://user:url-secret@database/test'); "
                    "print('token: stderr-secret {\\\"client_secret\\\": \\\"client-output\\\"} "
                    "Authorization: Bearer bearer-secret', file=sys.stderr); sys.exit(17)",
                    "--password",
                    "command-secret",
                    "--api-key=api-command-secret",
                    "PGPASSWORD=postgres-secret",
                ],
                evidence_path=evidence,
            )
            recorded_text = evidence.read_text(encoding="utf-8")
            recorded = json.loads(recorded_text)

        self.assertEqual(return_code, 17)
        self.assertIn("ordinary output", recorded["stdout"])
        self.assertIn("[REDACTED]", recorded_text)
        for secret in secrets:
            self.assertNotIn(secret, recorded_text)

    def test_evidence_redacts_bare_bearer_credentials(self) -> None:
        from runtime import verify_runtime

        secrets = ("command-bare-bearer", "stdout-bare-bearer", "stderr-bare-bearer")
        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence = Path(temporary_directory) / "bare-bearer-stage.json"
            return_code = verify_runtime.run_checked(
                [
                    sys.executable,
                    "-c",
                    "import sys; print('Bearer stdout-bare-bearer'); "
                    "print('Bearer stderr-bare-bearer', file=sys.stderr); sys.exit(17)",
                    "Bearer command-bare-bearer",
                ],
                evidence_path=evidence,
            )
            recorded_text = evidence.read_text(encoding="utf-8")
            recorded = json.loads(recorded_text)

        self.assertEqual(return_code, 17)
        self.assertIn("[REDACTED]", recorded_text)
        for secret in secrets:
            self.assertNotIn(secret, recorded_text)
        self.assertNotIn("command-bare-bearer", " ".join(recorded["command"]))
        self.assertNotIn("stdout-bare-bearer", recorded["stdout"])
        self.assertNotIn("stderr-bare-bearer", recorded["stderr"])

    def test_artifact_paths_are_redacted_by_command_context_not_temp_names(self) -> None:
        """Break caught: path redaction trusts /tmp names instead of exact external Compose inputs."""
        from runtime import verify_runtime

        with (
            tempfile.TemporaryDirectory(prefix="schema-runtime-repository-") as repository_directory,
            tempfile.TemporaryDirectory(prefix="custom-compose-context-") as external_directory,
        ):
            repository = Path(repository_directory)
            repository_compose_path = repository / "runtime" / "compose.yaml"
            repository_compose_path.parent.mkdir(parents=True)
            repository_compose_path.write_text("services: {}\n", encoding="utf-8")
            evidence_path = repository / "evidence" / "reviewer-probe.json"
            external = Path(external_directory)
            environment_path = external / "first.env"
            inline_environment_path = external / "second.env"
            override_path = external / "first-override.yaml"
            inline_override_path = external / "second-override.yaml"
            long_override_path = external / "third-override.yaml"
            arguments = [
                "--env-file",
                str(environment_path),
                f"--env-file={inline_environment_path}",
                "-f",
                str(repository_compose_path),
                "-f",
                str(override_path),
                f"--file={inline_override_path}",
                "--file",
                str(long_override_path),
                "config",
            ]
            expected = {
                "arguments": arguments,
                "cwd": str(repository),
                "evidencePath": str(evidence_path),
                "externalDirectory": str(external),
            }
            probe = (
                "import json,os,sys; "
                "expected=json.loads(sys.argv[1]); "
                "assert sys.argv[2:] == expected['arguments']; "
                "assert os.getcwd() == expected['cwd']; "
                "print(json.dumps(expected))"
            )
            return_code = verify_runtime.run_checked(
                [sys.executable, "-c", probe, json.dumps(expected), *arguments],
                evidence_path=evidence_path,
                cwd=repository,
            )
            artifact_text = evidence_path.read_text(encoding="utf-8")
            recorded = json.loads(artifact_text)

        self.assertEqual(return_code, 0, "the executed command must still receive every original argument")
        self.assertIn(str(repository), artifact_text)
        self.assertIn(str(repository_compose_path), artifact_text)
        self.assertIn(str(evidence_path), artifact_text)
        self.assertNotIn(str(external), artifact_text)
        self.assertIn("[RUNTIME_ENV_FILE]", artifact_text)
        self.assertIn("[RUNTIME_COMPOSE_OVERRIDE]", artifact_text)
        self.assertIn("[RUNTIME_TEMP_PATH]", artifact_text)
        self.assertIn("--env-file=[RUNTIME_ENV_FILE]", recorded["command"])
        self.assertIn("--file=[RUNTIME_COMPOSE_OVERRIDE]", recorded["command"])
        self.assertIn("--env-file", recorded["command"])
        self.assertEqual(recorded["command"][recorded["command"].index("--env-file") + 1], "[RUNTIME_ENV_FILE]")
        self.assertIn("--file", recorded["command"])
        self.assertEqual(
            recorded["command"][recorded["command"].index("--file") + 1],
            "[RUNTIME_COMPOSE_OVERRIDE]",
        )

    def test_parent_path_redaction_requires_a_component_boundary(self) -> None:
        """Break caught: an external parent path erases a repository whose component shares its prefix."""
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            external = root / "runtime"
            repository = root / "runtime-repository"
            external.mkdir()
            repository.mkdir()
            environment_path = external / "runtime.env"
            external_child = external / "logs" / "postgres.log"
            repository_compose = repository / "runtime" / "compose.yaml"
            evidence_path = repository / "evidence" / "path-boundary.json"
            expected = {
                "environmentPath": str(environment_path),
                "externalChild": str(external_child),
                "repositoryCompose": str(repository_compose),
            }
            probe = (
                "import json,sys; expected=json.loads(sys.argv[1]); "
                "assert sys.argv[3] == expected['environmentPath']; print(json.dumps(expected))"
            )
            return_code = verify_runtime.run_checked(
                [
                    sys.executable,
                    "-c",
                    probe,
                    json.dumps(expected),
                    "--env-file",
                    str(environment_path),
                ],
                evidence_path=evidence_path,
                cwd=repository,
            )
            artifact_text = evidence_path.read_text(encoding="utf-8")
            recorded = json.loads(artifact_text)
            decoded_command = json.loads(recorded["command"][3])
            decoded_stdout = json.loads(recorded["stdout"])

        self.assertEqual(return_code, 0)
        self.assertIn(str(repository), artifact_text)
        self.assertIn(str(repository_compose), artifact_text)
        self.assertIn(str(repository_compose), recorded["stdout"])
        for decoded in (decoded_command, decoded_stdout):
            self.assertEqual(decoded["environmentPath"], "[RUNTIME_ENV_FILE]")
            self.assertEqual(decoded["externalChild"], "[RUNTIME_TEMP_PATH]/logs/postgres.log")
            self.assertEqual(decoded["repositoryCompose"], str(repository_compose))
        self.assertIn("[RUNTIME_TEMP_PATH]", artifact_text)

    def test_path_redaction_uses_only_posix_separator_boundaries(self) -> None:
        """Break caught: punctuation after a sensitive component is treated as a path boundary."""
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            external = root / "x" / "runtime"
            repository = root / "x" / "runtime+repository"
            regex_external = root / "x" / "runtime.^$[](){}+?"
            external.mkdir(parents=True)
            repository.mkdir()
            regex_external.mkdir()
            environment_path = external / "runtime.env"
            override_path = regex_external / "override.yaml"
            repository_compose = repository / "runtime" / "compose.yaml"
            evidence_path = repository / "evidence" / "posix-boundary.json"
            suffixes = ("+repository", " repository", r"\repository", "(repository)", "[repository]")
            payload = {
                "environmentPath": str(environment_path),
                "evidencePath": str(evidence_path),
                "externalChild": str(external / "logs" / "postgres.log"),
                "externalRoot": str(external),
                "overridePath": str(override_path),
                "regexExternalChild": str(regex_external / "logs" / "flyway.log"),
                "regexExternalRoot": str(regex_external),
                "repositoryCompose": str(repository_compose),
                "siblings": [str(external) + suffix for suffix in suffixes],
            }
            probe = (
                "import json,sys; expected=json.loads(sys.argv[1]); "
                "assert sys.argv[3] == expected['environmentPath']; "
                "assert sys.argv[5] == expected['overridePath']; "
                "print(json.dumps(expected)); print(json.dumps(expected), file=sys.stderr)"
            )
            return_code = verify_runtime.run_checked(
                [
                    sys.executable,
                    "-c",
                    probe,
                    json.dumps(payload),
                    "--env-file",
                    str(environment_path),
                    "-f",
                    str(override_path),
                ],
                evidence_path=evidence_path,
                cwd=repository,
            )
            artifact_text = evidence_path.read_text(encoding="utf-8")
            recorded = json.loads(artifact_text)
            decoded_command = json.loads(recorded["command"][3])
            decoded_stdout = json.loads(recorded["stdout"])
            decoded_stderr = json.loads(recorded["stderr"])

        self.assertEqual(return_code, 0, "redaction must not change subprocess argv")
        for decoded in (decoded_command, decoded_stdout, decoded_stderr):
            self.assertEqual(decoded["environmentPath"], "[RUNTIME_ENV_FILE]")
            self.assertEqual(decoded["externalRoot"], "[RUNTIME_TEMP_PATH]")
            self.assertEqual(decoded["externalChild"], "[RUNTIME_TEMP_PATH]/logs/postgres.log")
            self.assertEqual(decoded["overridePath"], "[RUNTIME_COMPOSE_OVERRIDE]")
            self.assertEqual(decoded["regexExternalRoot"], "[RUNTIME_TEMP_PATH]")
            self.assertEqual(decoded["regexExternalChild"], "[RUNTIME_TEMP_PATH]/logs/flyway.log")
            self.assertEqual(decoded["repositoryCompose"], str(repository_compose))
            self.assertEqual(decoded["evidencePath"], str(evidence_path))
            self.assertEqual(decoded["siblings"], payload["siblings"])
        self.assertNotIn(str(regex_external), artifact_text)

    def test_sensitive_root_path_fails_closed_without_erasing_protected_context(self) -> None:
        """Break caught: root redaction leaks descendants or erases repository, cwd, and evidence paths."""
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            repository = root / "repository"
            working_directory = repository / "work"
            repository_compose = repository / "runtime" / "compose.yaml"
            evidence_path = root / "evidence" / "root-boundary.json"
            working_directory.mkdir(parents=True)
            repository_compose.parent.mkdir()
            (repository / ".git").mkdir()
            payload = {
                "cwd": str(working_directory),
                "evidencePath": str(evidence_path),
                "repository": str(repository),
                "repositoryCompose": str(repository_compose),
                "root": "/",
                "rootChild": "/outside-review/private/runtime.env",
            }
            probe = (
                "import json,os,sys; expected=json.loads(sys.argv[1]); "
                "assert os.getcwd() == expected['cwd']; assert sys.argv[3] == '/'; "
                "assert sys.argv[5] == expected['repositoryCompose']; "
                "print(json.dumps(expected)); print(json.dumps(expected), file=sys.stderr)"
            )
            return_code = verify_runtime.run_checked(
                [
                    sys.executable,
                    "-c",
                    probe,
                    json.dumps(payload),
                    "--env-file",
                    "/",
                    "-f",
                    str(repository_compose),
                ],
                evidence_path=evidence_path,
                cwd=working_directory,
            )
            artifact_text = evidence_path.read_text(encoding="utf-8")
            recorded = json.loads(artifact_text)
            decoded_command = json.loads(recorded["command"][3])
            decoded_stdout = json.loads(recorded["stdout"])
            decoded_stderr = json.loads(recorded["stderr"])

        self.assertEqual(return_code, 0, "redaction must not change subprocess argv")
        for decoded in (decoded_command, decoded_stdout, decoded_stderr):
            self.assertEqual(decoded["cwd"], str(working_directory))
            self.assertEqual(decoded["evidencePath"], str(evidence_path))
            self.assertEqual(decoded["repository"], str(repository))
            self.assertEqual(decoded["repositoryCompose"], str(repository_compose))
            self.assertEqual(decoded["root"], "[RUNTIME_ENV_FILE]")
            self.assertIn("[RUNTIME_ENV_FILE]", decoded["rootChild"])
            self.assertNotIn("/outside-review/private/runtime.env", decoded["rootChild"])
        self.assertIn(str(repository_compose), artifact_text)
        self.assertIn(str(evidence_path), artifact_text)

    def test_path_tokens_are_lexically_normalized_before_protection_is_classified(self) -> None:
        """Break caught: a protected textual prefix hides an absolute or relative path that escapes via dot-dot."""
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            repository = root / "repository"
            working_directory = repository / "work"
            repository_compose = repository / "runtime" / "compose.yaml"
            evidence_path = root / "evidence" / "lexical-paths.json"
            working_directory.mkdir(parents=True)
            repository_compose.parent.mkdir()
            (repository / ".git").mkdir()
            absolute_escape = f"{repository}/../outside/private.env"
            relative_escape = "../../outside/private.env"
            payload = {
                "absoluteEscape": absolute_escape,
                "cwdDescendant": str(working_directory / "logs" / "diagnostic.log"),
                "ordinaryUrl": "https://example.invalid/repository/../outside/private.env",
                "relativeEscape": relative_escape,
                "repositoryDescendant": str(repository / "logs" / "diagnostic.log"),
            }
            payload_digest = hashlib.sha256(json.dumps(payload).encode("utf-8")).hexdigest()
            probe = (
                "import hashlib,json,sys; raw=sys.argv[1]; "
                "assert hashlib.sha256(raw.encode()).hexdigest() == sys.argv[2]; "
                "assert sys.argv[4] == '/'; print(raw); print(raw, file=sys.stderr)"
            )
            return_code = verify_runtime.run_checked(
                [
                    sys.executable,
                    "-c",
                    probe,
                    json.dumps(payload),
                    payload_digest,
                    "--env-file",
                    "/",
                    "-f",
                    str(repository_compose),
                ],
                evidence_path=evidence_path,
                cwd=working_directory,
            )
            recorded = json.loads(evidence_path.read_text(encoding="utf-8"))
            decoded_command = json.loads(recorded["command"][3])
            decoded_stdout = json.loads(recorded["stdout"])
            decoded_stderr = json.loads(recorded["stderr"])

        self.assertEqual(return_code, 0, "redaction must not change subprocess argv")
        for decoded in (decoded_command, decoded_stdout, decoded_stderr):
            self.assertEqual(decoded["cwdDescendant"], payload["cwdDescendant"])
            self.assertEqual(decoded["repositoryDescendant"], payload["repositoryDescendant"])
            self.assertEqual(decoded["ordinaryUrl"], payload["ordinaryUrl"])
            self.assertIn("[RUNTIME_ENV_FILE]", decoded["absoluteEscape"])
            self.assertIn("[RUNTIME_ENV_FILE]", decoded["relativeEscape"])
            self.assertNotEqual(decoded["absoluteEscape"], absolute_escape)
            self.assertNotEqual(decoded["relativeEscape"], relative_escape)

    def test_exact_protected_files_do_not_protect_child_tokens(self) -> None:
        """Break caught: exact evidence/Compose protection is extended to file/private descendants."""
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            repository = root / "repository"
            repository.mkdir()
            (repository / ".git").mkdir()
            repository_compose = repository / "runtime" / "compose.yaml"
            repository_compose.parent.mkdir()
            evidence_path = root / "evidence" / "exact-file.json"
            evidence_child = f"{evidence_path}/private"
            evidence_sibling = evidence_path.parent / "sibling.log"
            compose_child = f"{repository_compose}/private"
            payload = {
                "composeChild": compose_child,
                "composePath": str(repository_compose),
                "evidenceChild": evidence_child,
                "evidencePath": str(evidence_path),
                "evidenceSibling": str(evidence_sibling),
            }
            probe = (
                "import json,sys; expected=json.loads(sys.argv[1]); "
                "assert sys.argv[3] == '/'; assert sys.argv[5] == expected['composePath']; "
                "print(json.dumps(expected)); "
                "print(json.dumps(expected), file=sys.stderr)"
            )
            return_code = verify_runtime.run_checked(
                [
                    sys.executable,
                    "-c",
                    probe,
                    json.dumps(payload),
                    "--env-file",
                    "/",
                    "-f",
                    str(repository_compose),
                ],
                evidence_path=evidence_path,
                cwd=repository,
            )
            recorded = json.loads(evidence_path.read_text(encoding="utf-8"))
            decoded_command = json.loads(recorded["command"][3])
            decoded_stdout = json.loads(recorded["stdout"])
            decoded_stderr = json.loads(recorded["stderr"])

        self.assertEqual(return_code, 0, "redaction must not change subprocess argv")
        for decoded in (decoded_command, decoded_stdout, decoded_stderr):
            self.assertEqual(decoded["composePath"], str(repository_compose))
            self.assertEqual(decoded["evidencePath"], str(evidence_path))
            self.assertEqual(decoded["evidenceSibling"], str(evidence_sibling))
            self.assertIn("[RUNTIME_ENV_FILE]", decoded["composeChild"])
            self.assertIn("[RUNTIME_ENV_FILE]", decoded["evidenceChild"])
            self.assertNotEqual(decoded["composeChild"], compose_child)
            self.assertNotEqual(decoded["evidenceChild"], evidence_child)

    def test_path_redaction_replaces_only_the_path_token_in_multiline_and_inline_text(self) -> None:
        """Break caught: root handling erases a multiline tail or misses a path between diagnostic headers."""
        from runtime import verify_runtime

        stdout = (
            "/outside-review/path-only.env\r\n"
            "ordinary next line\r\n"
            "HEADER BEFORE /outside-review/inline.env HEADER AFTER\r\n"
        )
        stderr = "ERROR BEFORE /outside-review/error.env ERROR AFTER\nordinary stderr\n"
        argv_digests = [
            hashlib.sha256(value.encode("utf-8")).hexdigest()
            for value in (stdout, stderr, "--env-file", "/")
        ]
        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence_path = Path(temporary_directory) / "line-bounded-paths.json"
            probe = (
                "import hashlib,json,sys; actual=sys.argv[2:]; expected=json.loads(sys.argv[1]); "
                "assert [hashlib.sha256(value.encode()).hexdigest() for value in actual] == expected; "
                "sys.stdout.write(actual[0]); sys.stderr.write(actual[1])"
            )
            command = [sys.executable, "-c", probe, json.dumps(argv_digests), stdout, stderr, "--env-file", "/"]
            return_code = verify_runtime.run_checked(
                command,
                evidence_path=evidence_path,
            )
            recorded = json.loads(evidence_path.read_text(encoding="utf-8"))
            _, path_context = verify_runtime._runtime_path_redactions(command, None, evidence_path)
            direct_redaction = verify_runtime._redact_text(stdout, path_context)

        self.assertEqual(return_code, 0, "redaction must not change subprocess argv")
        self.assertIn("ordinary next line\n", recorded["stdout"])
        self.assertIn("HEADER BEFORE ", recorded["stdout"])
        self.assertIn(" HEADER AFTER\n", recorded["stdout"])
        self.assertIn("ERROR BEFORE ", recorded["stderr"])
        self.assertIn(" ERROR AFTER\nordinary stderr\n", recorded["stderr"])
        self.assertIn("ordinary next line\r\n", direct_redaction)
        self.assertIn(" HEADER AFTER\r\n", direct_redaction)
        self.assertGreaterEqual(recorded["stdout"].count("[RUNTIME_ENV_FILE]"), 2)
        self.assertIn("[RUNTIME_ENV_FILE]", recorded["stderr"])
        for forbidden in (
            "/outside-review/path-only.env",
            "/outside-review/inline.env",
            "/outside-review/error.env",
        ):
            self.assertNotIn(forbidden, json.dumps(recorded, sort_keys=True))

    def test_external_paths_with_quotes_are_redacted_in_raw_and_json_text(self) -> None:
        """Break caught: JSON escaping lets a quote-containing external path survive evidence encoding."""
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            repository = root / "repository"
            external = root / 'runtime-"quoted-secret"'
            repository.mkdir()
            external.mkdir()
            environment_path = external / 'database-"credentials".env'
            external_child = external / "logs" / 'postgres-"private".log'
            payload = {
                "environmentPath": str(environment_path),
                "externalChild": str(external_child),
            }
            probe = (
                "import json,sys; expected=json.loads(sys.argv[1]); "
                "assert sys.argv[3] == expected['environmentPath']; "
                "print(json.dumps(expected)); print(json.dumps(expected), file=sys.stderr)"
            )
            evidence_path = repository / "evidence" / "quoted-path.json"
            return_code = verify_runtime.run_checked(
                [
                    sys.executable,
                    "-c",
                    probe,
                    json.dumps(payload),
                    "--env-file",
                    str(environment_path),
                ],
                evidence_path=evidence_path,
                cwd=repository,
            )
            artifact_text = evidence_path.read_text(encoding="utf-8")
            recorded = json.loads(artifact_text)
            decoded_command = json.loads(recorded["command"][3])
            decoded_stdout = json.loads(recorded["stdout"])
            decoded_stderr = json.loads(recorded["stderr"])

        self.assertEqual(return_code, 0)
        self.assertNotIn(str(external), artifact_text)
        self.assertNotIn("quoted-secret", artifact_text)
        for decoded in (decoded_command, decoded_stdout, decoded_stderr):
            decoded_text = json.dumps(decoded, sort_keys=True)
            self.assertNotIn(str(external), decoded_text)
            self.assertNotIn("quoted-secret", decoded_text)
            self.assertIn("[RUNTIME_TEMP_PATH]", decoded_text)

    def test_sensitive_env_assignment_consumes_escaped_quoted_value(self) -> None:
        """Break caught: an escaped inner quote ends sensitive assignment redaction early."""
        from runtime import verify_runtime

        assignments = [
            r'PGHOST="head \"inner-secret\" tail-secret"',
            r"PGOPTIONS='head \'inner-option-secret\' tail-option-secret'",
            r'LOG_MESSAGE="head \"inner diagnostic\" tail diagnostic"',
        ]
        assignment_digests = [hashlib.sha256(value.encode("utf-8")).hexdigest() for value in assignments]
        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence_path = Path(temporary_directory) / "escaped-environment.json"
            probe = (
                "import hashlib,json,sys; expected=json.loads(sys.argv[1]); actual=sys.argv[2:]; "
                "assert [hashlib.sha256(value.encode()).hexdigest() for value in actual] == expected; "
                "print('\\n'.join(actual)); print('\\n'.join(actual), file=sys.stderr)"
            )
            return_code = verify_runtime.run_checked(
                [sys.executable, "-c", probe, json.dumps(assignment_digests), *assignments],
                evidence_path=evidence_path,
            )
            artifact_text = evidence_path.read_text(encoding="utf-8")
            recorded = json.loads(artifact_text)

        self.assertEqual(return_code, 0)
        self.assertIn(assignments[2], recorded["command"])
        self.assertIn(assignments[2], recorded["stdout"])
        self.assertIn(assignments[2], recorded["stderr"])
        for forbidden in (
            "PGHOST",
            "PGOPTIONS",
            "inner-secret",
            "tail-secret",
            "inner-option-secret",
            "tail-option-secret",
        ):
            self.assertNotIn(forbidden, artifact_text)

    def test_sensitive_env_assignment_consumes_backslash_newline_in_quoted_value(self) -> None:
        """Break caught: a backslash-newline leaves the sensitive quoted assignment unredacted."""
        from runtime import verify_runtime

        assignments = [
            'PGHOST="head \\\ninner-host-secret tail-host-secret"',
            "PGOPTIONS='head \\\ninner-option-secret tail-option-secret'",
            'LOG_MESSAGE="head \\\ninner diagnostic tail diagnostic"',
        ]
        assignment_digests = [hashlib.sha256(value.encode("utf-8")).hexdigest() for value in assignments]
        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence_path = Path(temporary_directory) / "newline-environment.json"
            probe = (
                "import hashlib,json,sys; expected=json.loads(sys.argv[1]); actual=sys.argv[2:]; "
                "assert [hashlib.sha256(value.encode()).hexdigest() for value in actual] == expected; "
                "print('\\n'.join(actual)); print('\\n'.join(actual), file=sys.stderr)"
            )
            return_code = verify_runtime.run_checked(
                [sys.executable, "-c", probe, json.dumps(assignment_digests), *assignments],
                evidence_path=evidence_path,
            )
            artifact_text = evidence_path.read_text(encoding="utf-8")
            recorded = json.loads(artifact_text)

        self.assertEqual(return_code, 0, "redaction must not change subprocess argv")
        self.assertIn(assignments[2], recorded["command"])
        self.assertIn(assignments[2], recorded["stdout"])
        self.assertIn(assignments[2], recorded["stderr"])
        for forbidden in (
            "PGHOST",
            "PGOPTIONS",
            "inner-host-secret",
            "tail-host-secret",
            "inner-option-secret",
            "tail-option-secret",
        ):
            self.assertNotIn(forbidden, artifact_text)

    def test_sensitive_env_assignment_does_not_consume_the_next_assignment_opener(self) -> None:
        """Break caught: a continued unterminated value swallows the next assignment name and leaks its value."""
        from runtime import verify_runtime

        transcript = (
            'PGHOST="first-adjacent-secret \\\n'
            "PGPASSWORD='second-adjacent-secret'\n"
            'LOG_MESSAGE="ordinary adjacent diagnostic"\n'
        )
        transcript_digest = hashlib.sha256(transcript.encode("utf-8")).hexdigest()
        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence_path = Path(temporary_directory) / "adjacent-environment.json"
            probe = (
                "import hashlib,sys; actual=sys.argv[1]; "
                "assert hashlib.sha256(actual.encode()).hexdigest() == sys.argv[2]; "
                "sys.stdout.write(actual); sys.stderr.write(actual)"
            )
            return_code = verify_runtime.run_checked(
                [sys.executable, "-c", probe, transcript, transcript_digest],
                evidence_path=evidence_path,
            )
            artifact_text = evidence_path.read_text(encoding="utf-8")
            recorded = json.loads(artifact_text)

        self.assertEqual(return_code, 0, "redaction must not change subprocess argv")
        self.assertGreaterEqual(recorded["stdout"].count("[RUNTIME_ENV_ASSIGNMENT]"), 2)
        self.assertGreaterEqual(recorded["stderr"].count("[RUNTIME_ENV_ASSIGNMENT]"), 2)
        self.assertIn('LOG_MESSAGE="ordinary adjacent diagnostic"', recorded["stdout"])
        self.assertIn('LOG_MESSAGE="ordinary adjacent diagnostic"', recorded["stderr"])
        for forbidden in (
            "PGHOST",
            "PGPASSWORD",
            "first-adjacent-secret",
            "second-adjacent-secret",
        ):
            self.assertNotIn(forbidden, artifact_text)

    def test_sensitive_env_assignment_state_machine_handles_crlf_and_terminal_backslash(self) -> None:
        """Break caught: CRLF continuations or a final lone backslash leave quoted runtime secrets visible."""
        from runtime import verify_runtime

        transcript = (
            'PGHOST="crlf-host-secret \\\r\ncontinued-host-secret"\r\n'
            "PGOPTIONS='crlf-option-secret \\\r\ncontinued-option-secret'\r\n"
            'LOG_MESSAGE="ordinary crlf \\\r\ncontinued diagnostic"\r\n'
            'PGUSER="terminal-backslash-secret\\'
        )
        transcript_digest = hashlib.sha256(transcript.encode("utf-8")).hexdigest()
        ordinary_crlf = 'LOG_MESSAGE="ordinary crlf \\\r\ncontinued diagnostic"'
        ordinary_subprocess = ordinary_crlf.replace("\r\n", "\n")
        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence_path = Path(temporary_directory) / "crlf-environment.json"
            probe = (
                "import hashlib,sys; actual=sys.argv[1]; "
                "assert hashlib.sha256(actual.encode()).hexdigest() == sys.argv[2]; "
                "sys.stdout.write(actual); sys.stderr.write(actual)"
            )
            return_code = verify_runtime.run_checked(
                [sys.executable, "-c", probe, transcript, transcript_digest],
                evidence_path=evidence_path,
            )
            artifact_text = evidence_path.read_text(encoding="utf-8")
            recorded = json.loads(artifact_text)
            direct_redaction = verify_runtime._redact_text(transcript)

        self.assertEqual(return_code, 0, "redaction must not change subprocess argv")
        self.assertIn(ordinary_subprocess, recorded["stdout"])
        self.assertIn(ordinary_subprocess, recorded["stderr"])
        self.assertIn(ordinary_crlf, direct_redaction)
        self.assertIn("\r\n", direct_redaction)
        for forbidden in (
            "PGHOST",
            "PGOPTIONS",
            "PGUSER",
            "crlf-host-secret",
            "continued-host-secret",
            "crlf-option-secret",
            "continued-option-secret",
            "terminal-backslash-secret",
        ):
            self.assertNotIn(forbidden, artifact_text)
            self.assertNotIn(forbidden, direct_redaction)

    def test_artifact_removes_complete_postgresql_connection_uris(self) -> None:
        """Break caught: IPv6, multi-host, or parameter tails survive PostgreSQL URI redaction."""
        from runtime import verify_runtime

        connections = [
            "JDBC:POSTGRESQL://reviewer:uri-secret@[2001:db8::1]:5432,db-two.internal:5433/law"
            "?sslmode=require&target_session_attrs=read-write",
            "PoStGrEsQl://[2001:db8::2]:5432,db-three.internal/law?application_name=review-probe",
            "POSTGRES://reviewer:second-secret@db-four.internal/law?options=-c%20statement_timeout=10",
        ]
        with tempfile.TemporaryDirectory() as temporary_directory:
            artifact_directory = Path(temporary_directory) / "artifact"
            probe = (
                "import json,sys; expected=json.loads(sys.argv[1]); "
                "assert sys.argv[2:] == expected; print(json.dumps(expected)); "
                "print(expected[1], file=sys.stderr)"
            )
            return_code = verify_runtime.run_checked(
                [sys.executable, "-c", probe, json.dumps(connections), *connections],
                evidence_path=artifact_directory / "run-01" / "connection-probe.json",
            )
            artifact_text = "\n".join(
                path.read_text(encoding="utf-8")
                for path in sorted(artifact_directory.rglob("*"))
                if path.is_file()
            )

        self.assertEqual(return_code, 0)
        self.assertIn("[REDACTED_CONNECTION]", artifact_text)
        for forbidden in (
            "uri-secret",
            "second-secret",
            "2001:db8",
            "db-two.internal",
            "db-three.internal",
            "db-four.internal",
            "sslmode",
            "target_session_attrs",
            "application_name",
            "statement_timeout",
        ):
            self.assertNotIn(forbidden, artifact_text)
        self.assertIsNone(re.search(r"(?i)(?:jdbc:postgresql:|postgres(?:ql)?://)", artifact_text))

    def test_artifact_redacts_sensitive_env_assignments_only_case_insensitively(self) -> None:
        """Break caught: quoted mixed-case runtime env leaks while ordinary diagnostics disappear."""
        from runtime import verify_runtime

        assignments = [
            'Flyway_Password="Flyway Secret"',
            "flyway_password='lower secret'",
            "PGHOST='db internal'",
            "PGOPTIONS='-c statement_timeout=1000 -c search_path=law'",
            "LOG_LEVEL=debug",
            "NORMAL_VALUE='diagnostic value'",
        ]
        with tempfile.TemporaryDirectory() as temporary_directory:
            artifact_directory = Path(temporary_directory) / "artifact"
            probe = (
                "import json,sys; expected=json.loads(sys.argv[1]); "
                "assert sys.argv[2:] == expected; "
                "print(json.dumps({'Config': {'Env': expected}})); "
                "print(' '.join(expected), file=sys.stderr)"
            )
            return_code = verify_runtime.run_checked(
                [sys.executable, "-c", probe, json.dumps(assignments), *assignments],
                evidence_path=artifact_directory / "run-01" / "environment-probe.json",
            )
            artifact_text = "\n".join(
                path.read_text(encoding="utf-8")
                for path in sorted(artifact_directory.rglob("*"))
                if path.is_file()
            )

        self.assertEqual(return_code, 0)
        self.assertIn("[RUNTIME_ENV_ASSIGNMENT]", artifact_text)
        self.assertIn("LOG_LEVEL=debug", artifact_text)
        self.assertIn("NORMAL_VALUE='diagnostic value'", artifact_text)
        for forbidden in (
            "Flyway_Password",
            "flyway_password",
            "PGHOST",
            "PGOPTIONS",
            "Flyway Secret",
            "lower secret",
            "db internal",
            "statement_timeout",
            "search_path",
        ):
            self.assertNotIn(forbidden, artifact_text)

    def test_cli_rejects_a_single_run_before_any_runtime_attempt(self) -> None:
        completed = subprocess.run(
            [sys.executable, "-m", "runtime.verify_runtime", "verify", "--runs", "1", "--evidence-dir", "run-a"],
            cwd=PROJECT_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(completed.returncode, 2)
        self.assertIn("--runs must be at least 2", completed.stderr)

    def _temp_dir(self) -> str:
        temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(temporary_directory.cleanup)
        return temporary_directory.name


if __name__ == "__main__":
    unittest.main()
