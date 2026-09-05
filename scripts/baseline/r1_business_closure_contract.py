from __future__ import annotations

import re
from collections import Counter
from pathlib import Path


def _read(root: Path, relative: str, findings: list[str]) -> str:
    path = root / relative
    try:
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeError):
        findings.append(f"R1 closure artifact missing or invalid UTF-8: {relative}")
        return ""


def _require(text: str, value: str, label: str, findings: list[str]) -> None:
    if value not in text:
        findings.append(f"R1 closure contract missing {label}: {value}")


def validate(root: Path) -> list[str]:
    findings: list[str] = []
    command = _read(root, "docs/contracts/r1/R1-COMMAND-POLICY-EVENT-CONTRACT.md", findings)
    adr = _read(root, "docs/adr/ADR-0008-r1-business-closure-alignment.md", findings)
    baseline = _read(root, "docs/baseline/CURRENT-MVP-BASELINE.md", findings)
    api = _read(root, "contracts/openapi/ontology-law-api.yaml", findings)
    for value in (
        "| CAPTURE_LEAD | INTERNAL_ADMIN | HUMAN | DIRECT,DELEGATED |",
        "| CAPTURE_LEAD | SERVICE_ACTOR | SERVICE | SYSTEM | SOURCE_INTAKE_OWNER | LEAD_CAPTURE | sourceIntakeRootCode | existing-lead:LEAD_CAPTURE-DENY | NONE | NONE |",
    ):
        _require(command, value, "capture composite policy", findings)
    capture_rows = re.findall(r"(?m)^\| CAPTURE_LEAD \|.*$", command)
    keys = []
    for row in capture_rows:
        cells = [cell.strip() for cell in row.strip("|").split("|")]
        if len(cells) >= 3:
            keys.append((cells[0], cells[2]))
    if len(keys) != 2 or any(count != 1 for count in Counter(keys).values()):
        findings.append("R1 capture policies must contain exactly one HUMAN and one SERVICE composite row")
    for value in (
        "BaselineId | MVP-2026-09-05.3",
        "ProjectionStorage | NONE",
        "DisclosureReturn | AFTER_AUDIT_COMMIT",
        "SensitiveResponseModes | BODY,CACHE_REVALIDATED",
        "MaxAttempts | 8",
        "CapacityProfile | R1-CAPACITY-V1",
        "EventRouteCount | 14",
        "R1_TRUSTED_SERVICE_SOURCE_BINDING_V1",
        "R1_WORKER_TENANT_BINDING_V1",
        "private, no-cache",
        "Vary: Authorization",
    ):
        _require(adr, value, "ADR decision", findings)
    _require(baseline, "Baseline ID: MVP-2026-09-05.3", "active baseline id", findings)
    _require(api, "version: 1.1.0", "OpenAPI version", findings)
    operations = re.findall(r"(?m)^\s+operationId: (\S+)\s*$", api)
    if len(operations) != 15 or len(set(operations)) != 15:
        findings.append("R1 OpenAPI must expose exactly 15 unique operations")
    for operation in ("listDueR1Tasks", "consumeR1Projection"):
        _require(api, f"operationId: {operation}", "internal operation", findings)
    if api.count("- internalMutualTls: []") != 4 or api.count("- publicBearer: []") != 11:
        findings.append("R1 OpenAPI security split must be exactly 11 Bearer and 4 mutualTLS")
    for value in (
        "DueR1TaskPageV1:", "DueR1TaskCandidateV1:", "ConsumeR1ProjectionV1:",
        "additionalProperties: false", "STALE_OUTBOX_CLAIM", "PROJECTION_EVENT_INVALID",
        "minimum: 1", "maximum: 100", "default: 50",
    ):
        _require(api, value, "internal OpenAPI shape", findings)
    public_prefix = api.split("  /internal/v1/tasks/due:", 1)[0]
    if "STALE_OUTBOX_CLAIM" in public_prefix or "PROJECTION_EVENT_INVALID" in public_prefix:
        findings.append("internal projection errors must not broaden public operation allowlists")
    return findings
