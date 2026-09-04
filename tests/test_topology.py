from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
VERIFIER_PATH = REPOSITORY_ROOT / "scripts" / "verify_topology.py"
ADR_PATH = REPOSITORY_ROOT / "docs" / "adr" / "ADR-0004-r1-scaffold-and-http-contract.md"


VALID_WORKFLOW = """
name: Scaffold gate
on:
  pull_request:
  push:
    branches: [main]
permissions:
  contents: read
jobs:
  topology:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262
      - uses: actions/setup-python@a26af69be951a213d495a4c3e4e4022e16d87065
        with:
          python-version: "3.12.14"
      - run: python -m pip install PyYAML==6.0.3
      - run: python -m unittest tests.test_topology -v
      - run: python scripts/verify_topology.py
  backend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262
      - uses: actions/setup-java@dd06d9cba3e5552c54d9f8ea23572deb30010f7c
        with:
          distribution: temurin
          java-version: "25.0.4.1"
      - run: java -version 2>&1 | grep -F 'Temurin-25.0.4.1+1'
      - run: ./mvnw -f backend/pom.xml package
  workbench:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262
      - uses: actions/setup-node@49933ea5288caeca8642d1e84afbd3f7d6820020
        with:
          node-version: "24.20.0"
          cache: npm
      - run: npm install --global npm@11.9.0
      - run: test "$(npm --version)" = "11.9.0"
      - run: npm ci
      - run: npm run typecheck
      - run: npm test
      - run: npm run build
  scaffold-gate:
    if: ${{ always() }}
    needs: [topology, backend, workbench]
    runs-on: ubuntu-latest
    steps:
      - env:
          TOPOLOGY_RESULT: ${{ needs.topology.result }}
          BACKEND_RESULT: ${{ needs.backend.result }}
          WORKBENCH_RESULT: ${{ needs.workbench.result }}
        run: |
          test "$TOPOLOGY_RESULT" = success
          test "$BACKEND_RESULT" = success
          test "$WORKBENCH_RESULT" = success
"""


VALID_POM = """
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.github.windyzhu3</groupId>
  <artifactId>ontology-law-system</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <properties>
    <java.version>25</java.version>
    <maven.compiler.release>25</maven.compiler.release>
    <spring-boot.version>4.1.1</spring-boot.version>
    <spring-modulith.version>2.1.1</spring-modulith.version>
    <jooq.version>3.21.7</jooq.version>
    <flyway.version>13.4.0</flyway.version>
    <testcontainers.version>2.0.0</testcontainers.version>
    <openapi-generator.version>7.25.0</openapi-generator.version>
  </properties>
  <build><plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId><version>3.14.1</version>
    </plugin>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-surefire-plugin</artifactId><version>3.5.4</version>
    </plugin>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-failsafe-plugin</artifactId><version>3.5.4</version>
    </plugin>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-enforcer-plugin</artifactId><version>3.6.2</version>
      <dependencies><dependency>
        <groupId>org.codehaus.mojo</groupId><artifactId>extra-enforcer-rules</artifactId><version>1.11.0</version>
      </dependency></dependencies>
      <executions><execution><goals><goal>enforce</goal></goals><configuration><rules>
        <requireJavaVersion><version>[25.0.4-1]</version></requireJavaVersion>
        <requireMavenVersion><version>[3.9.16,3.9.17)</version></requireMavenVersion>
        <requireReleaseDeps><onlyWhenRelease>true</onlyWhenRelease></requireReleaseDeps>
        <requireUpperBoundDeps/>
        <banDynamicVersions/>
      </rules></configuration></execution></executions>
    </plugin>
    <plugin>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-maven-plugin</artifactId><version>4.1.1</version>
      <executions><execution><goals><goal>repackage</goal></goals></execution></executions>
    </plugin>
  </plugins></build>
</project>
"""


class TopologyVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self._write_valid_project()
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)
        subprocess.run(["git", "add", "-f", "."], cwd=self.root, check=True)

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def _load_verifier(self):
        self.assertTrue(VERIFIER_PATH.is_file(), "topology verifier is not implemented")
        spec = importlib.util.spec_from_file_location("verify_topology", VERIFIER_PATH)
        assert spec and spec.loader
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def _verify(self) -> list[str]:
        return self._load_verifier().verify_repository(self.root)

    def _write(self, relative_path: str, contents: str) -> None:
        destination = self.root / relative_path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(textwrap.dedent(contents).lstrip(), encoding="utf-8")

    def _write_valid_project(self) -> None:
        root_package = {
            "name": "ontology-law-system",
            "private": True,
            "packageManager": "npm@11.9.0",
            "engines": {"node": "24.20.0", "npm": "11.9.0"},
            "workspaces": ["apps/workbench"],
            "scripts": {
                "typecheck": "npm run typecheck --workspace apps/workbench",
                "test": "npm run test --workspace apps/workbench",
                "build": "npm run build --workspace apps/workbench",
            },
        }
        workbench_package = {
            "name": "@ontology-law/workbench",
            "private": True,
            "version": "0.1.0",
            "scripts": {
                "typecheck": "tsc --noEmit",
                "test": "vitest run --passWithNoTests",
                "build": "tsc --noEmit && vite build",
            },
            "dependencies": {"react": "19.2.8", "react-dom": "19.2.8"},
            "devDependencies": {
                "@types/react": "19.2.8",
                "@types/react-dom": "19.2.3",
                "@vitejs/plugin-react": "5.1.4",
                "typescript": "7.0.2",
                "vite": "8.2.2",
                "vitest": "4.1.11",
            },
        }
        self._write("package.json", json.dumps(root_package))
        self._write(
            "package-lock.json",
            json.dumps({"name": "ontology-law-system", "lockfileVersion": 3, "packages": {}}),
        )
        self._write("apps/workbench/package.json", json.dumps(workbench_package))
        self._write("backend/pom.xml", VALID_POM)
        self._write(".node-version", "24.20.0\n")
        self._write(
            ".mvn/wrapper/maven-wrapper.properties",
            """
            wrapperVersion=3.3.4
            distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip
            distributionSha256Sum=5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce
            distributionSha512Sum=ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3
            """,
        )
        self._write(".github/workflows/scaffold-gate.yml", VALID_WORKFLOW)

    def _git_track(self, relative_path: str) -> None:
        subprocess.run(
            ["git", "add", "-f", "--", relative_path], cwd=self.root, check=True
        )

    def test_valid_single_artifact_layout_passes(self) -> None:
        self.assertEqual([], self._verify())

    def test_adr_closes_party_ownership_and_query_jooq_boundaries(self) -> None:
        adr = ADR_PATH.read_text(encoding="utf-8")

        self.assertIn(
            "`actions/setup-java` | `dd06d9cba3e5552c54d9f8ea23572deb30010f7c`",
            adr,
        )
        self.assertIn("audit、execution、identity、lead、opportunity、party、query、responsibility", adr)
        self.assertIn("| `party` | 无 R1 业务包 |", adr)
        self.assertIn("`lead` | `identity`、`audit`、`execution`、`responsibility`、`opportunity`、`party`", adr)
        self.assertIn(
            "Query 通过各 Fact Owner 的具名 read port 组合读取模型，不拥有生成记录。",
            adr,
        )
        self.assertIn("`party.internal.persistence.jooq`", adr)

    def test_openapi_is_optional_but_only_the_canonical_source_is_allowed(self) -> None:
        self.assertEqual([], self._verify())

        self._write(
            "contracts/openapi/ontology-law-api.yaml",
            """
            ? openapi
            : 3.1.0
            ? info
            : {title: Ontology Law, version: 1.0.0}
            ? paths
            : {}
            """,
        )
        self.assertEqual([], self._verify())

        self._write(
            "contracts/openapi/ontology-law-api.yaml",
            """
            {
              openapi: 3.1.0,
              info: {
                title: Ontology Law,
                version: 1.0.0
              },
              paths: {}
            }
            """,
        )
        self.assertEqual([], self._verify())

        self._write("elsewhere/second.yaml", "openapi: 3.1.0\ninfo: {}\npaths: {}\n")
        errors = self._verify()
        self.assertTrue(any("second OpenAPI" in error for error in errors), errors)

        self._write(
            "elsewhere/second.yaml",
            "kind: metadata\n---\nopenapi: 3.1.0\ninfo: {}\npaths: {}\n",
        )
        errors = self._verify()
        self.assertTrue(
            any("second OpenAPI" in error and "elsewhere/second.yaml" in error for error in errors),
            errors,
        )

    def test_openapi_json_is_rejected_in_worktree_and_staged_index(self) -> None:
        path = "elsewhere/openapi.json"
        self._write(path, '{"openapi":"3.1.0","info":{},"paths":{}}')
        errors = self._verify()
        self.assertTrue(
            any("second OpenAPI" in error and "worktree" in error and path in error for error in errors),
            errors,
        )

        (self.root / path).unlink()
        path = "build/tracked/openapi.json"
        self._write(path, '{"openapi":"3.1.0","info":{},"paths":{}}')
        self._git_track(path)
        self._write(path, '{"kind":"metadata"}')
        errors = self._verify()
        self.assertTrue(
            any("second OpenAPI" in error and "index" in error and path in error for error in errors),
            errors,
        )

    def test_tracked_sources_cannot_hide_in_generated_directories(self) -> None:
        hidden_sources = {
            "target/hidden/pom.xml": VALID_POM,
            "dist/hidden/package.json": '{"name":"hidden","scripts":{"build":"vite build"}}',
            "node_modules/hidden/openapi.yaml": "openapi: 3.1.0\ninfo: {}\npaths: {}\n",
        }
        for path, content in hidden_sources.items():
            with self.subTest(path=path):
                self._write(path, content)
                self._git_track(path)
                self.assertNotEqual([], self._verify())
                subprocess.run(["git", "rm", "--cached", "-q", "--", path], cwd=self.root, check=True)
                (self.root / path).unlink()

    def test_staged_hidden_sources_are_checked_after_worktree_overwrite_or_delete(self) -> None:
        hidden_sources = {
            "target/hidden/pom.xml": VALID_POM,
            "dist/hidden/package.json": '{"name":"hidden","scripts":{"build":"vite build"}}',
            "node_modules/hidden/package-lock.json": '{"name":"hidden","lockfileVersion":3}',
            "build/hidden/openapi.yaml": "openapi: 3.1.0\ninfo: {}\npaths: {}\n",
        }
        for path, staged_content in hidden_sources.items():
            for worktree_state in ("overwrite", "delete"):
                with self.subTest(path=path, worktree_state=worktree_state):
                    self._write(path, staged_content)
                    self._git_track(path)
                    if worktree_state == "overwrite":
                        self._write(path, "not a topology source\n")
                    else:
                        (self.root / path).unlink()
                    self.assertNotEqual([], self._verify())
                    subprocess.run(
                        ["git", "reset", "-q", "HEAD", "--", path],
                        cwd=self.root,
                        check=False,
                    )
                    subprocess.run(
                        ["git", "rm", "--cached", "-q", "--ignore-unmatch", "--", path],
                        cwd=self.root,
                        check=False,
                    )
                    candidate = self.root / path
                    if candidate.exists():
                        candidate.unlink()

    def test_tracked_generated_yaml_uses_current_worktree_content(self) -> None:
        path = "build/tracked/metadata.yaml"
        self._write(path, "kind: metadata\n")
        self._git_track(path)
        self.assertEqual([], self._verify())

        self._write(path, "openapi: 3.1.0\ninfo: {}\npaths: {}\n")
        errors = self._verify()
        self.assertTrue(
            any("second OpenAPI" in error and "worktree" in error for error in errors),
            errors,
        )

    def test_tracked_generated_package_uses_current_worktree_content(self) -> None:
        path = "dist/tracked/package.json"
        self._write(path, '{"name":"metadata","private":true}')
        self._git_track(path)
        self.assertEqual([], self._verify())

        self._write(path, '{"name":"rogue","scripts":{"build":"vite build"}}')
        errors = self._verify()
        self.assertTrue(
            any("deployable frontend topology" in error and path in error for error in errors),
            errors,
        )

    def test_tracked_generated_pom_worktree_parse_errors_fail_closed(self) -> None:
        path = "target/tracked/pom.xml"
        self._write(path, VALID_POM)
        self._git_track(path)
        index_errors = self._verify()
        self.assertTrue(
            any("index found" in error and path in error for error in index_errors),
            index_errors,
        )
        self.assertFalse(any("invalid XML" in error for error in index_errors), index_errors)

        self._write(path, "<project>")
        errors = self._verify()
        self.assertTrue(
            any(f"worktree:{path} is invalid XML" in error for error in errors),
            errors,
        )

    def test_tracked_generated_lockfile_worktree_parse_errors_fail_closed(self) -> None:
        path = "node_modules/tracked/package-lock.json"
        self._write(path, '{"name":"metadata","lockfileVersion":3}')
        self._git_track(path)
        index_errors = self._verify()
        self.assertTrue(
            any("index:" in error and path in error for error in index_errors),
            index_errors,
        )
        self.assertFalse(
            any(f"index:{path} is not valid JSON" in error for error in index_errors),
            index_errors,
        )

        self._write(path, "{")
        errors = self._verify()
        self.assertTrue(
            any(f"worktree:{path} is not valid JSON" in error for error in errors),
            errors,
        )

    def test_tracked_generated_worktree_path_errors_fail_closed(self) -> None:
        path = "build/tracked/metadata.yaml"
        self._write(path, "kind: metadata\n")
        self._git_track(path)
        (self.root / path).unlink()
        os.symlink("missing.yaml", self.root / path)

        errors = self._verify()
        self.assertTrue(
            any("failed to read tracked worktree file" in error and path in error for error in errors),
            errors,
        )

    def test_git_index_enumeration_and_blob_read_fail_closed(self) -> None:
        (self.root / ".git/index").unlink()
        errors = self._verify()
        self.assertTrue(any("git ls-files" in error for error in errors), errors)

        subprocess.run(["git", "add", "-f", "."], cwd=self.root, check=True)
        subprocess.run(
            [
                "git",
                "update-index",
                "--add",
                "--info-only",
                "--cacheinfo",
                "100644,1111111111111111111111111111111111111111,node_modules/missing/package.json",
            ],
            cwd=self.root,
            check=True,
        )
        errors = self._verify()
        self.assertTrue(any("git show" in error for error in errors), errors)

    def test_untracked_generated_directories_are_ignored(self) -> None:
        self._write("target/hidden/pom.xml", VALID_POM)
        self._write("dist/hidden/package.json", '{"name":"hidden","scripts":{"build":"vite build"}}')
        self._write("node_modules/hidden/openapi.yaml", "openapi: 3.1.0\n")
        self.assertEqual([], self._verify())

    def test_required_root_and_workbench_scripts_cannot_fail_open(self) -> None:
        root_package = json.loads((self.root / "package.json").read_text(encoding="utf-8"))
        del root_package["scripts"]["typecheck"]
        root_package["scripts"]["test"] += " --if-present"
        self._write("package.json", json.dumps(root_package))
        errors = self._verify()
        self.assertTrue(any("root script 'typecheck'" in error for error in errors), errors)
        self.assertTrue(any("--if-present" in error for error in errors), errors)

        self._write_valid_project()
        workbench = json.loads((self.root / "apps/workbench/package.json").read_text(encoding="utf-8"))
        del workbench["scripts"]["build"]
        self._write("apps/workbench/package.json", json.dumps(workbench))
        errors = self._verify()
        self.assertTrue(any("workbench script 'build'" in error for error in errors), errors)

        self._write_valid_project()
        root_package = json.loads((self.root / "package.json").read_text(encoding="utf-8"))
        root_package["scripts"]["typecheck"] = "true"
        self._write("package.json", json.dumps(root_package))
        errors = self._verify()
        self.assertTrue(any("root script 'typecheck'" in error for error in errors), errors)

    def test_workflow_has_no_path_filter_and_has_unskippable_aggregate(self) -> None:
        self._write(
            ".github/workflows/scaffold-gate.yml",
            VALID_WORKFLOW.replace("  pull_request:\n", "  pull_request:\n    paths: [backend/**]\n")
            .replace("if: ${{ always() }}", "if: ${{ success() }}")
            .replace(
                "actions/setup-node@49933ea5288caeca8642d1e84afbd3f7d6820020",
                "actions/setup-node@v4",
            ),
        )
        errors = self._verify()
        self.assertTrue(any("path filter" in error for error in errors), errors)
        self.assertTrue(any("always()" in error for error in errors), errors)
        self.assertTrue(any("setup-node" in error for error in errors), errors)

        self._write(
            ".github/workflows/scaffold-gate.yml",
            VALID_WORKFLOW.replace('test "$TOPOLOGY_RESULT" = success', "true"),
        )
        errors = self._verify()
        self.assertTrue(any("topology result" in error for error in errors), errors)

    def test_workflow_installs_the_pinned_yaml_parser(self) -> None:
        self._write(
            ".github/workflows/scaffold-gate.yml",
            VALID_WORKFLOW.replace("PyYAML==6.0.3", "PyYAML"),
        )
        errors = self._verify()
        self.assertTrue(any("PyYAML==6.0.3" in error for error in errors), errors)

    def test_workflow_activates_and_checks_exact_npm_before_ci(self) -> None:
        activation = "      - run: npm install --global npm@11.9.0\n"
        assertion = '      - run: test "$(npm --version)" = "11.9.0"\n'
        npm_ci = "      - run: npm ci\n"
        mutations = {
            "missing activation": VALID_WORKFLOW.replace(activation, ""),
            "activation version drift": VALID_WORKFLOW.replace("npm@11.9.0", "npm@11.9.1"),
            "missing assertion": VALID_WORKFLOW.replace(assertion, ""),
            "assertion version drift": VALID_WORKFLOW.replace('= "11.9.0"', '= "11.9.1"'),
            "activation after ci": VALID_WORKFLOW.replace(
                activation + assertion + npm_ci,
                npm_ci + activation + assertion,
            ),
        }
        for name, workflow in mutations.items():
            with self.subTest(name=name):
                self._write(".github/workflows/scaffold-gate.yml", workflow)
                errors = self._verify()
                self.assertTrue(
                    any("npm 11.9.0" in error and "before npm ci" in error for error in errors),
                    errors,
                )

    def test_workflow_packages_backend_instead_of_only_testing(self) -> None:
        self._write(
            ".github/workflows/scaffold-gate.yml",
            VALID_WORKFLOW.replace(
                "./mvnw -f backend/pom.xml package",
                "./mvnw -f backend/pom.xml test",
            ),
        )
        errors = self._verify()
        self.assertTrue(
            any("backend job must verify" in error and "package" in error for error in errors),
            errors,
        )

    def test_enforcer_fails_closed_for_toolchain_dependencies_and_dynamic_versions(self) -> None:
        rule_elements = {
            "requireJavaVersion": "<requireJavaVersion><version>[25.0.4-1]</version></requireJavaVersion>",
            "requireMavenVersion": "<requireMavenVersion><version>[3.9.16,3.9.17)</version></requireMavenVersion>",
            "requireReleaseDeps": "<requireReleaseDeps><onlyWhenRelease>true</onlyWhenRelease></requireReleaseDeps>",
            "requireUpperBoundDeps": "<requireUpperBoundDeps/>",
            "banDynamicVersions": "<banDynamicVersions/>",
        }
        for rule, element in rule_elements.items():
            with self.subTest(rule=rule):
                self._write("backend/pom.xml", VALID_POM.replace(element, ""))
                errors = self._verify()
                self.assertTrue(any(rule in error for error in errors), errors)

        self._write(
            "backend/pom.xml",
            VALID_POM.replace("[25.0.4-1]", "[17,99)").replace("[3.9.16,3.9.17)", "[3.0,4.0)"),
        )
        errors = self._verify()
        self.assertTrue(any("Java 25" in error for error in errors), errors)
        self.assertTrue(any("Maven 3.9.16" in error for error in errors), errors)

        self._write(
            "backend/pom.xml",
            VALID_POM.replace("<banDynamicVersions/>", "<disabled-banDynamicVersions/>")
        )
        errors = self._verify()
        self.assertTrue(any("banDynamicVersions" in error for error in errors), errors)

    def test_second_backend_or_frontend_project_is_rejected(self) -> None:
        self._write("other/pom.xml", VALID_POM)
        self._write("apps/other/package.json", '{"name":"other","scripts":{"build":"vite build"}}')
        errors = self._verify()
        self.assertTrue(any("backend project" in error for error in errors), errors)
        self.assertTrue(any("deployable frontend" in error for error in errors), errors)

    def test_backend_build_binds_the_single_executable_jar(self) -> None:
        self._write(
            "backend/pom.xml",
            VALID_POM.replace("<goal>repackage</goal>", "<goal>help</goal>"),
        )
        errors = self._verify()
        self.assertTrue(any("repackage" in error for error in errors), errors)


if __name__ == "__main__":
    unittest.main()
