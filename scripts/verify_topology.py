#!/usr/bin/env python3
"""Fail-closed checks for the R1 single-artifact repository topology."""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

import yaml


IGNORED_GENERATED_DIRECTORIES = {".git", ".venv", "venv", "node_modules", "target", "dist", "build"}
CANONICAL_OPENAPI = Path("contracts/openapi/ontology-law-api.yaml")
EXACT_ACTIONS = {
    "actions/checkout": "11d5960a326750d5838078e36cf38b85af677262",
    "actions/setup-python": "a26af69be951a213d495a4c3e4e4022e16d87065",
    "actions/setup-java": "dd06d9cba3e5552c54d9f8ea23572deb30010f7c",
    "actions/setup-node": "49933ea5288caeca8642d1e84afbd3f7d6820020",
    "actions/upload-artifact": "ea165f8d65b6e75b540449e92b4886f43607fa02",
}
EXACT_MAVEN_PROPERTIES = {
    "java.version": "25",
    "maven.compiler.release": "25",
    "spring-boot.version": "4.1.1",
    "spring-modulith.version": "2.1.1",
    "jooq.version": "3.21.7",
    "flyway.version": "13.4.0",
    "testcontainers.version": "2.0.0",
    "openapi-generator.version": "7.25.0",
}
EXACT_MAVEN_PLUGINS = {
    "openapi-generator-maven-plugin": "${openapi-generator.version}",
    "maven-compiler-plugin": "3.14.1",
    "maven-surefire-plugin": "3.5.4",
    "maven-failsafe-plugin": "3.5.4",
    "maven-enforcer-plugin": "3.6.2",
    "spring-boot-maven-plugin": "4.1.1",
}
EXACT_WRAPPER_PROPERTIES = {
    "wrapperVersion": "3.3.4",
    "distributionUrl": "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip",
    "distributionSha256Sum": "5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce",
    "distributionSha512Sum": "ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3",
}
EXACT_VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?$")
EXACT_SCRIPTS = {
    "root": {
        "openapi:generate": "openapi-typescript contracts/openapi/ontology-law-api.yaml --output apps/workbench/src/generated/api/schema.d.ts --alphabetize",
        "openapi:check": "openapi-typescript contracts/openapi/ontology-law-api.yaml --output apps/workbench/src/generated/api/schema.d.ts --alphabetize --check",
        "typecheck": "npm run typecheck --workspace apps/workbench",
        "test": "npm run test --workspace apps/workbench",
        "build": "npm run build --workspace apps/workbench",
    },
    "workbench": {
        "typecheck": "tsc --noEmit",
        "test": "vitest run --passWithNoTests",
        "build": "tsc --noEmit && vite build",
    },
}
EXACT_OPENAPI_GENERATOR_CONFIGURATION = {
    "inputSpec": "${project.basedir}/../contracts/openapi/ontology-law-api.yaml",
    "generatorName": "spring",
    "library": "spring-boot",
    "output": "${project.build.directory}/generated-sources/openapi",
    "apiPackage": "io.github.windyzhu3.ontologylaw.api.adapter.generated.api",
    "modelPackage": "io.github.windyzhu3.ontologylaw.api.adapter.generated.model",
    "generateApis": "true",
    "generateModels": "true",
    "generateSupportingFiles": "false",
    "generateApiDocumentation": "false",
    "generateModelDocumentation": "false",
    "generateApiTests": "false",
    "generateModelTests": "false",
    "addCompileSourceRoot": "true",
    "skipValidateSpec": "false",
}
EXACT_OPENAPI_GENERATOR_OPTIONS = {
    "interfaceOnly": "true",
    "skipDefaultInterface": "true",
    "useJakartaEe": "true",
    "openApiNullable": "false",
    "documentationProvider": "none",
    "annotationLibrary": "swagger2",
    "useTags": "true",
}


class WorkflowLoader(yaml.BaseLoader):
    """BaseLoader keeps GitHub's `on` key as text instead of YAML 1.1 bool."""


def _is_relevant(relative: Path) -> bool:
    return (
        relative.name in {"package.json", "package-lock.json", "pom.xml"}
        or relative.suffix.lower() in {".json", ".yaml", ".yml"}
        or relative in {Path(".node-version"), Path(".mvn/wrapper/maven-wrapper.properties")}
    )


def _tracked_relevant_paths(root: Path, errors: list[str]) -> set[Path]:
    result = subprocess.run(
        ["git", "ls-files", "-z"], cwd=root, capture_output=True, check=False
    )
    if result.returncode != 0:
        errors.append(
            "git ls-files failed while reading the index: "
            + result.stderr.decode("utf-8", errors="replace").strip()
        )
        return set()
    raw_paths = [raw for raw in result.stdout.split(b"\0") if raw]
    if not raw_paths:
        errors.append("git ls-files returned no tracked files; index verification cannot continue")
        return set()
    tracked_paths: set[Path] = set()
    for raw_path in raw_paths:
        try:
            relative = Path(raw_path.decode("utf-8"))
        except UnicodeDecodeError as exc:
            errors.append(f"git ls-files returned a non-UTF-8 path: {exc}")
            continue
        if not _is_relevant(relative):
            continue
        if relative.is_absolute() or ".." in relative.parts:
            errors.append(f"git ls-files returned an unsafe path: {relative}")
            continue
        tracked_paths.add(relative)
    return tracked_paths


def _index_snapshot(
    root: Path, tracked_paths: set[Path], errors: list[str]
) -> dict[Path, bytes]:
    snapshot: dict[Path, bytes] = {}
    for relative in sorted(tracked_paths):
        blob = subprocess.run(
            ["git", "show", f":{relative.as_posix()}"],
            cwd=root,
            capture_output=True,
            check=False,
        )
        if blob.returncode != 0:
            errors.append(
                f"git show :{relative.as_posix()} failed while reading the index blob: "
                + blob.stderr.decode("utf-8", errors="replace").strip()
            )
            continue
        snapshot[relative] = blob.stdout
    return snapshot


def _worktree_snapshot(
    root: Path, tracked_paths: set[Path], errors: list[str]
) -> dict[Path, bytes]:
    snapshot: dict[Path, bytes] = {}
    for directory, names, filenames in os.walk(root):
        names[:] = [name for name in names if name not in IGNORED_GENERATED_DIRECTORIES]
        base = Path(directory)
        for name in filenames:
            absolute = base / name
            relative = absolute.relative_to(root)
            if not _is_relevant(relative):
                continue
            try:
                snapshot[relative] = absolute.read_bytes()
            except OSError as exc:
                errors.append(f"failed to read worktree file {relative}: {exc}")
    for relative in sorted(tracked_paths - snapshot.keys()):
        absolute = root / relative
        try:
            snapshot[relative] = absolute.read_bytes()
        except FileNotFoundError:
            if os.path.lexists(absolute):
                errors.append(f"failed to read tracked worktree file {relative}: dangling path")
        except OSError as exc:
            errors.append(f"failed to read tracked worktree file {relative}: {exc}")
    return snapshot


def _read_json(contents: bytes, label: str, errors: list[str]) -> dict:
    try:
        value = json.loads(contents)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        errors.append(f"{label} is not valid JSON: {exc}")
        return {}
    if not isinstance(value, dict):
        errors.append(f"{label} must contain a JSON object")
        return {}
    return value


def _verify_package_topology(snapshot: dict[Path, bytes], source: str, errors: list[str]) -> None:
    package_files = sorted(path for path in snapshot if path.name == "package.json")
    deployable: list[Path] = []
    packages: dict[Path, dict] = {}
    for relative in package_files:
        package = _read_json(snapshot[relative], f"{source}:{relative}", errors)
        packages[relative] = package
        if relative != Path("package.json") and "build" in package.get("scripts", {}):
            deployable.append(relative)

    if deployable != [Path("apps/workbench/package.json")]:
        errors.append(
            "deployable frontend topology must contain only apps/workbench/package.json; "
            f"found {[str(path) for path in deployable]}"
        )

    root_package = packages.get(Path("package.json"))
    if root_package is None:
        errors.append("root package.json is required")
    else:
        if root_package.get("private") is not True:
            errors.append("root package.json must be private")
        if root_package.get("workspaces") != ["apps/workbench"]:
            errors.append("root workspaces must be exactly ['apps/workbench']")
        if root_package.get("packageManager") != "npm@11.9.0":
            errors.append("root packageManager must be npm@11.9.0")
        if root_package.get("engines") != {"node": "24.20.0", "npm": "11.9.0"}:
            errors.append("root engines must pin Node 24.20.0 and npm 11.9.0")
        _verify_scripts(root_package, "root", errors)

    workbench = packages.get(Path("apps/workbench/package.json"))
    if workbench is None:
        errors.append("apps/workbench/package.json is required")
    else:
        if workbench.get("private") is not True:
            errors.append("workbench package must be private")
        _verify_scripts(workbench, "workbench", errors)

    for path, package in packages.items():
        for section in ("dependencies", "devDependencies", "peerDependencies", "optionalDependencies"):
            for dependency, version in package.get(section, {}).items():
                if not isinstance(version, str) or not EXACT_VERSION.fullmatch(version):
                    errors.append(f"{source}:{path}: {dependency} must use an exact version, found {version!r}")

    lockfiles = sorted(path for path in snapshot if path.name == "package-lock.json")
    for relative in lockfiles:
        _read_json(snapshot[relative], f"{source}:{relative}", errors)
    if lockfiles != [Path("package-lock.json")]:
        errors.append(
            f"{source}: npm lockfile topology must contain only package-lock.json; "
            f"found {[str(path) for path in lockfiles]}"
        )


def _verify_scripts(package: dict, label: str, errors: list[str]) -> None:
    scripts = package.get("scripts")
    if not isinstance(scripts, dict):
        scripts = {}
    for name, expected in EXACT_SCRIPTS[label].items():
        command = scripts.get(name)
        if not isinstance(command, str) or not command.strip():
            errors.append(f"required {label} script '{name}' is missing")
        elif "--if-present" in command:
            errors.append(f"{label} script '{name}' must not use --if-present")
        elif command != expected:
            errors.append(
                f"required {label} script '{name}' must be exactly {expected!r}"
            )


def _verify_openapi(snapshot: dict[Path, bytes], source: str, errors: list[str]) -> None:
    sources: list[Path] = []
    for relative in sorted(snapshot):
        suffix = relative.suffix.lower()
        if suffix not in {".json", ".yaml", ".yml"}:
            continue
        try:
            documents = (
                [json.loads(snapshot[relative])]
                if suffix == ".json"
                else list(yaml.safe_load_all(snapshot[relative]))
            )
        except (UnicodeDecodeError, json.JSONDecodeError, yaml.YAMLError):
            continue
        if any(isinstance(document, dict) and "openapi" in document for document in documents):
            sources.append(relative)
    unexpected = [path for path in sources if path != CANONICAL_OPENAPI]
    if unexpected or len(sources) > 1:
        errors.append(
            "second OpenAPI source is forbidden; only contracts/openapi/ontology-law-api.yaml is allowed; "
            f"{source} found {[str(path) for path in sources]}"
        )


def _verify_exact_xml_values(
    parent: ET.Element,
    expected: dict[str, str],
    label: str,
    errors: list[str],
    allowed_nested: set[str] | None = None,
) -> None:
    allowed_nested = set() if allowed_nested is None else allowed_nested
    expected_names = set(expected) | allowed_nested
    children = list(parent)
    actual_names = [child.tag.rsplit("}", 1)[-1] for child in children]
    if len(actual_names) != len(expected_names) or set(actual_names) != expected_names:
        errors.append(f"{label} fields must be exactly {sorted(expected_names)}")
    for name, expected_value in expected.items():
        matches = [
            child for child in children
            if child.tag.rsplit("}", 1)[-1] == name
        ]
        actual_value = None if len(matches) != 1 else (matches[0].text or "").strip()
        if actual_value != expected_value:
            errors.append(f"{label} {name} must be exactly {expected_value}")


def _verify_openapi_generator(
    pom: ET.Element,
    namespace: dict[str, str],
    errors: list[str],
) -> None:
    for profile in pom.findall("m:profiles/m:profile", namespace):
        profile_id = profile.findtext("m:id", default="<unnamed>", namespaces=namespace)
        profile_properties = profile.find("m:properties", namespace)
        if profile_properties is not None and any(
            child.tag.rsplit("}", 1)[-1] == "openapi-generator.version"
            for child in profile_properties
        ):
            errors.append(
                f"Maven profile {profile_id} must not override openapi-generator.version"
            )
        if any(
            plugin.findtext("m:artifactId", namespaces=namespace)
            == "openapi-generator-maven-plugin"
            for plugin in profile.findall(".//m:plugin", namespace)
        ):
            errors.append(
                f"Maven profile {profile_id} must not declare openapi-generator-maven-plugin"
            )

    generator_plugins = [
        plugin
        for plugin in pom.findall("m:build/m:plugins/m:plugin", namespace)
        if plugin.findtext("m:artifactId", namespaces=namespace) == "openapi-generator-maven-plugin"
    ]
    if len(generator_plugins) != 1:
        errors.append("OpenAPI Generator plugin must be declared exactly once in the main build")
        return

    plugin = generator_plugins[0]
    _verify_exact_xml_values(
        plugin,
        {
            "groupId": "org.openapitools",
            "artifactId": "openapi-generator-maven-plugin",
            "version": "${openapi-generator.version}",
        },
        "OpenAPI Generator plugin",
        errors,
        {"executions"},
    )

    executions = plugin.findall("m:executions/m:execution", namespace)
    if len(executions) != 1:
        errors.append("OpenAPI Generator plugin must declare exactly one execution")
        return
    executions_container = plugin.find("m:executions", namespace)
    if executions_container is not None:
        _verify_exact_xml_values(
            executions_container,
            {},
            "OpenAPI Generator executions",
            errors,
            {"execution"},
        )
    execution = executions[0]
    _verify_exact_xml_values(
        execution,
        {
            "id": "generate-r1-openapi-contract",
            "phase": "generate-sources",
        },
        "OpenAPI Generator execution",
        errors,
        {"goals", "configuration"},
    )
    goals = execution.find("m:goals", namespace)
    if goals is None:
        errors.append("OpenAPI Generator execution goals are required")
    else:
        _verify_exact_xml_values(
            goals,
            {"goal": "generate"},
            "OpenAPI Generator goals",
            errors,
        )

    configuration = execution.find("m:configuration", namespace)
    if configuration is None:
        errors.append("OpenAPI Generator execution configuration is required")
        return
    _verify_exact_xml_values(
        configuration,
        EXACT_OPENAPI_GENERATOR_CONFIGURATION,
        "OpenAPI Generator configuration",
        errors,
        {"configOptions"},
    )
    config_options = configuration.find("m:configOptions", namespace)
    if config_options is None:
        errors.append("OpenAPI Generator configOptions are required")
        return
    _verify_exact_xml_values(
        config_options,
        EXACT_OPENAPI_GENERATOR_OPTIONS,
        "OpenAPI Generator configOptions",
        errors,
    )


def _uses_default_success_condition(container: dict) -> bool:
    if "if" not in container:
        return True
    condition = container.get("if")
    if not isinstance(condition, str):
        return False
    expression = condition.strip()
    if expression.startswith("${{") and expression.endswith("}}"):
        expression = expression[3:-2].strip()
    return expression == "success()"


def _verify_workflow_failure_behavior(jobs: dict, errors: list[str]) -> None:
    for job_name in ("topology", "backend", "workbench", "scaffold-gate"):
        job = jobs.get(job_name)
        if not isinstance(job, dict):
            continue
        if "continue-on-error" in job:
            errors.append(f"workflow {job_name} job must not declare continue-on-error")
        if job_name != "scaffold-gate" and not _uses_default_success_condition(job):
            errors.append(f"workflow {job_name} job must use the default success() condition")
        for index, step in enumerate(job.get("steps", []), start=1):
            if not isinstance(step, dict):
                continue
            if "continue-on-error" in step:
                errors.append(
                    f"workflow {job_name} step {index} must not declare continue-on-error"
                )
            if not _uses_default_success_condition(step):
                errors.append(
                    f"workflow {job_name} step {index} must use the default success() condition"
                )


def _verify_maven(snapshot: dict[Path, bytes], source: str, errors: list[str]) -> None:
    poms = sorted(path for path in snapshot if path.name == "pom.xml")
    parsed_poms: dict[Path, ET.Element] = {}
    for relative in poms:
        try:
            parsed_poms[relative] = ET.fromstring(snapshot[relative])
        except ET.ParseError as exc:
            errors.append(f"{source}:{relative} is invalid XML: {exc}")
    if poms != [Path("backend/pom.xml")]:
        errors.append(
            "backend project topology must contain only backend/pom.xml; "
            f"{source} found {[str(path) for path in poms]}"
        )
        return
    pom = parsed_poms.get(Path("backend/pom.xml"))
    if pom is None:
        return
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    group_id = pom.findtext("m:groupId", namespaces=namespace)
    artifact_id = pom.findtext("m:artifactId", namespaces=namespace)
    if group_id != "io.github.windyzhu3" or artifact_id != "ontology-law-system":
        errors.append("backend coordinates must be io.github.windyzhu3:ontology-law-system")

    properties = pom.find("m:properties", namespace)
    property_values = {} if properties is None else {
        child.tag.rsplit("}", 1)[-1]: (child.text or "").strip() for child in properties
    }
    for name, expected in EXACT_MAVEN_PROPERTIES.items():
        if property_values.get(name) != expected:
            errors.append(f"Maven property {name} must be exactly {expected}")

    plugins = {}
    for plugin in pom.findall(".//m:build/m:plugins/m:plugin", namespace):
        artifact = plugin.findtext("m:artifactId", namespaces=namespace)
        if artifact:
            plugins[artifact] = plugin
    for artifact, expected_version in EXACT_MAVEN_PLUGINS.items():
        plugin = plugins.get(artifact)
        actual_version = None if plugin is None else plugin.findtext("m:version", namespaces=namespace)
        if actual_version != expected_version:
            errors.append(f"Maven plugin {artifact} must be exactly {expected_version}")

    _verify_openapi_generator(pom, namespace, errors)

    enforcer = plugins.get("maven-enforcer-plugin")
    enforcer_elements = [] if enforcer is None else list(enforcer.iter())
    enforcer_names = {element.tag.rsplit("}", 1)[-1] for element in enforcer_elements}
    for rule in (
        "requireJavaVersion",
        "requireMavenVersion",
        "requireReleaseDeps",
        "requireUpperBoundDeps",
        "banDynamicVersions",
    ):
        if rule not in enforcer_names:
            errors.append(f"Maven Enforcer must configure {rule}")
    java_rule = next(
        (element for element in enforcer_elements if element.tag.rsplit("}", 1)[-1] == "requireJavaVersion"),
        None,
    )
    java_range = None if java_rule is None else java_rule.findtext("m:version", namespaces=namespace)
    if java_range != "[25.0.4-1]":
        errors.append("Maven Enforcer must require exact Java 25.0.4.1")
    maven_rule = next(
        (element for element in enforcer_elements if element.tag.rsplit("}", 1)[-1] == "requireMavenVersion"),
        None,
    )
    maven_range = None if maven_rule is None else maven_rule.findtext("m:version", namespaces=namespace)
    if maven_range != "[3.9.16,3.9.17)":
        errors.append("Maven Enforcer must require exact Maven 3.9.16")
    enforcer_xml = "" if enforcer is None else ET.tostring(enforcer, encoding="unicode")
    if "extra-enforcer-rules" not in enforcer_xml or "1.11.0" not in enforcer_xml:
        errors.append("Maven Enforcer must pin extra-enforcer-rules 1.11.0")

    boot_plugin = plugins.get("spring-boot-maven-plugin")
    boot_goals = set() if boot_plugin is None else {
        (element.text or "").strip()
        for element in boot_plugin.iter()
        if element.tag.rsplit("}", 1)[-1] == "goal"
    }
    if "repackage" not in boot_goals:
        errors.append("spring-boot-maven-plugin must bind the repackage goal for the single executable Jar")


def _read_properties(contents: bytes) -> dict[str, str]:
    properties: dict[str, str] = {}
    for line in contents.decode("utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        properties[key.strip()] = value.strip()
    return properties


def _verify_toolchain_files(snapshot: dict[Path, bytes], source: str, errors: list[str]) -> None:
    node_version = snapshot.get(Path(".node-version"))
    if node_version is None or node_version.decode("utf-8", errors="replace").strip() != "24.20.0":
        errors.append(".node-version must be exactly 24.20.0")
    wrapper = snapshot.get(Path(".mvn/wrapper/maven-wrapper.properties"))
    if wrapper is None:
        errors.append("Maven wrapper properties are required")
        return
    try:
        properties = _read_properties(wrapper)
    except UnicodeDecodeError as exc:
        errors.append(f"{source}: Maven wrapper properties are not UTF-8: {exc}")
        return
    for name, expected in EXACT_WRAPPER_PROPERTIES.items():
        if properties.get(name) != expected:
            errors.append(f"Maven wrapper {name} must be exactly {expected}")


def _verify_workflow(snapshot: dict[Path, bytes], source: str, errors: list[str]) -> None:
    workflow_contents = snapshot.get(Path(".github/workflows/scaffold-gate.yml"))
    if workflow_contents is None:
        errors.append(f"{source}: scaffold workflow is missing")
        return
    try:
        workflow = yaml.load(workflow_contents, Loader=WorkflowLoader)
    except (UnicodeDecodeError, yaml.YAMLError) as exc:
        errors.append(f"{source}: scaffold workflow is invalid: {exc}")
        return
    if not isinstance(workflow, dict):
        errors.append("scaffold workflow must be a YAML mapping")
        return
    triggers = workflow.get("on")
    if not isinstance(triggers, dict):
        errors.append("scaffold workflow must define push and pull_request triggers")
    else:
        for trigger in ("push", "pull_request"):
            if trigger not in triggers:
                errors.append(f"scaffold workflow must run on {trigger}")
            settings = triggers.get(trigger)
            if isinstance(settings, dict) and ({"paths", "paths-ignore"} & settings.keys()):
                errors.append(f"scaffold workflow {trigger} must not use a path filter")

    jobs = workflow.get("jobs")
    if not isinstance(jobs, dict):
        errors.append("scaffold workflow jobs are required")
        return
    required_jobs = {"topology", "backend", "workbench", "scaffold-gate"}
    if set(jobs) != required_jobs:
        errors.append(f"scaffold workflow jobs must be exactly {sorted(required_jobs)}")
    _verify_workflow_failure_behavior(jobs, errors)

    for job_name, job in jobs.items():
        if not isinstance(job, dict):
            continue
        for step in job.get("steps", []):
            if not isinstance(step, dict) or "uses" not in step:
                continue
            action = step["uses"]
            if "@" not in action:
                errors.append(f"workflow action {action} is not pinned")
                continue
            repository, revision = action.rsplit("@", 1)
            expected = EXACT_ACTIONS.get(repository)
            if expected is None or revision != expected:
                errors.append(f"workflow action {repository} must use the exact approved commit")

    _verify_setup_version(jobs.get("topology"), "actions/setup-python", "python-version", "3.12.14", errors)
    _verify_setup_version(jobs.get("backend"), "actions/setup-java", "java-version", "25.0.4.1", errors)
    _verify_setup_version(jobs.get("workbench"), "actions/setup-node", "node-version", "24.20.0", errors)

    backend_commands = _job_run_steps(jobs.get("backend"))
    if backend_commands != [
        "java -version 2>&1 | grep -F 'Temurin-25.0.4.1+1'",
        "./mvnw -f backend/pom.xml package",
    ]:
        errors.append("backend job must verify exact Temurin 25.0.4.1+1 and run Maven package")

    expected_workbench_commands = [
        "npm install --global npm@11.9.0",
        'test "$(npm --version)" = "11.9.0"',
        "npm ci",
        "npm run openapi:check",
        "npm run typecheck",
        "npm test",
        "npm run build",
    ]
    if _job_run_steps(jobs.get("workbench")) != expected_workbench_commands:
        errors.append(
            "workbench job must activate and verify npm 11.9.0 before npm ci, "
            "then check OpenAPI generated types before typecheck"
        )

    topology_commands = _job_commands(jobs.get("topology"))
    if "PyYAML==6.0.3" not in topology_commands:
        errors.append("topology job must install PyYAML==6.0.3")

    aggregate = jobs.get("scaffold-gate")
    if not isinstance(aggregate, dict):
        errors.append("scaffold-gate aggregate job is required")
        return
    if aggregate.get("if") != "${{ always() }}":
        errors.append("scaffold-gate must use job-level if: ${{ always() }}")
    needs = aggregate.get("needs")
    if not isinstance(needs, list) or set(needs) != {"topology", "backend", "workbench"}:
        errors.append("scaffold-gate must aggregate topology, backend, and workbench")
    aggregate_commands = _job_commands(aggregate)
    for job_name in ("topology", "backend", "workbench"):
        required_check = f'test "${job_name.upper()}_RESULT" = success'
        if f"needs.{job_name}.result" not in str(aggregate) or required_check not in aggregate_commands:
            errors.append(f"scaffold-gate must fail closed on {job_name} result")


def _job_commands(job: object) -> str:
    return "\n".join(_job_run_steps(job))


def _job_run_steps(job: object) -> list[str]:
    if not isinstance(job, dict):
        return []
    return [
        str(step.get("run", ""))
        for step in job.get("steps", [])
        if isinstance(step, dict) and "run" in step
    ]


def _verify_setup_version(
    job: object,
    action_name: str,
    field: str,
    expected: str,
    errors: list[str],
) -> None:
    if not isinstance(job, dict):
        errors.append(f"job for {action_name} is missing")
        return
    for step in job.get("steps", []):
        if isinstance(step, dict) and str(step.get("uses", "")).startswith(action_name + "@"):
            settings = step.get("with", {})
            if not isinstance(settings, dict) or settings.get(field) != expected:
                errors.append(f"{action_name} must pin {field} {expected}")
            return
    errors.append(f"job must use {action_name}")


def verify_repository(root: Path) -> list[str]:
    root = root.resolve()
    errors: list[str] = []
    tracked_paths = _tracked_relevant_paths(root, errors)
    snapshots = {
        "worktree": _worktree_snapshot(root, tracked_paths, errors),
        "index": _index_snapshot(root, tracked_paths, errors),
    }
    for source, snapshot in snapshots.items():
        _verify_package_topology(snapshot, source, errors)
        _verify_openapi(snapshot, source, errors)
        _verify_maven(snapshot, source, errors)
        _verify_toolchain_files(snapshot, source, errors)
        _verify_workflow(snapshot, source, errors)
    return errors


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    errors = verify_repository(root)
    if errors:
        print("R1 topology verification failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("R1 topology verification passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
