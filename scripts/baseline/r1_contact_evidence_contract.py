from __future__ import annotations

from pathlib import Path


ORDINAL_PROFILE = "R1_CONTACT_ORDINAL_V1"
EVIDENCE_PROFILE = "R1_CONTACT_EVIDENCE_REF_V1"

ORDINAL_REGISTRY = {
    "ordinal": "LEAD_GLOBAL_MONOTONIC",
    "retry": "contactNo<3",
    "exhausted": "contactNo>=3",
    "automaticBudget": "MAX_INITIAL_CONTACT_NO_3",
    "supervisorReopen": "NEW_OPEN_TASK",
    "connectedValid": "ANY_SAFE_POSITIVE_CONTACT_NO",
    "suspectInvalid": "ANY_SAFE_POSITIVE_CONTACT_NO",
}

EVIDENCE_REGISTRY = {
    "input": "OPTIONAL_SUBMISSION_ID",
    "absent": "ZERO_EVIDENCE_READ",
    "tenant": "ACTOR_TENANT",
    "target": "CURRENT_LEAD_EXACT_REVISION",
    "binding": "ACTIVE_NOT_REVOKED",
    "subjects": "TASK,LEAD,SUBMISSION,BINDING",
    "authority": "ASSIGNMENT_OWNER/SALES_CONTACT_OWNER",
    "paths": "DIRECT,DELEGATED",
    "scope": "TASK_OWNER_ORGANIZATION",
    "submissionSelector": "IMMUTABLE_ROW_JCS_SHA256",
    "bindingSelector": "EXACT_REVISION",
    "capability": "QUERY_ONLY",
    "disclosure": "AUDIT_BEFORE_200_AND_304",
    "cache": "SELECTORS_AND_AUTH_DEPENDENCIES",
    "invalid": "SAFE_NOT_FOUND",
    "hiddenCard": "NEXT_ELIGIBLE_OR_ZERO",
    "bindingWriterFence": "R1_BUSINESS_TENANT_LOCK",
    "writes": "NONE",
    "files": "NO_CONTENT_OR_LOCATOR",
}

OWNER_REGISTRY = {
    "evidence": (
        "evidence_submission,evidence_binding",
        "identity",
        "lead,api",
    )
}


def _read(root: Path, relative: str, findings: list[str]) -> str:
    try:
        return (root / relative).read_text(encoding="utf-8")
    except (OSError, UnicodeError):
        findings.append(
            f"R1 contact/evidence artifact missing or invalid UTF-8: {relative}"
        )
        return ""


def _without_fenced_code(text: str) -> list[str]:
    visible: list[str] = []
    fence_character: str | None = None
    fence_length = 0
    in_html_comment = False
    for source_line in text.splitlines():
        stripped = source_line.lstrip()
        if fence_character is not None:
            closing_length = len(stripped) - len(stripped.lstrip(fence_character))
            if (
                closing_length >= fence_length
                and not stripped[closing_length:].strip()
            ):
                fence_character = None
                fence_length = 0
            continue

        fragments: list[str] = []
        cursor = 0
        while cursor < len(source_line):
            if in_html_comment:
                comment_end = source_line.find("-->", cursor)
                if comment_end < 0:
                    cursor = len(source_line)
                    continue
                in_html_comment = False
                cursor = comment_end + 3
                continue
            comment_start = source_line.find("<!--", cursor)
            if comment_start < 0:
                fragments.append(source_line[cursor:])
                break
            fragments.append(source_line[cursor:comment_start])
            in_html_comment = True
            cursor = comment_start + 4

        line = "".join(fragments)
        stripped = line.lstrip()
        if stripped.startswith("```") or stripped.startswith("~~~"):
            fence_character = stripped[0]
            fence_length = len(stripped) - len(stripped.lstrip(fence_character))
            continue
        visible.append(line)
    return visible


def _table(
    text: str,
    heading: str,
    header: tuple[str, ...],
    findings: list[str],
) -> list[tuple[str, ...]]:
    lines = _without_fenced_code(text)
    indexes = [index for index, line in enumerate(lines) if line == f"## {heading}"]
    label = f"R1 contact/evidence {heading}"
    if len(indexes) != 1:
        findings.append(f"{label} registry must have exactly one active heading")
        return []
    index = indexes[0] + 1
    while index < len(lines) and not lines[index].strip():
        index += 1
    expected_header = "| " + " | ".join(header) + " |"
    if index >= len(lines) or lines[index] != expected_header:
        findings.append(f"{label} registry header must be exact: {expected_header}")
        return []
    index += 1
    expected_separator = "|" + "|".join("---" for _ in header) + "|"
    if index >= len(lines) or lines[index].replace(" ", "") != expected_separator:
        findings.append(f"{label} registry separator must match its header")
        return []
    index += 1
    rows: list[tuple[str, ...]] = []
    while index < len(lines) and lines[index].startswith("|"):
        cells = tuple(cell.strip() for cell in lines[index].strip("|").split("|"))
        if len(cells) != len(header) or any(not cell for cell in cells):
            findings.append(f"{label} registry row must have {len(header)} nonempty columns")
            return []
        rows.append(cells)
        index += 1
    return rows


def _profile_registry(
    text: str,
    heading: str,
    profile: str,
    expected: dict[str, str],
    findings: list[str],
) -> None:
    rows = _table(text, heading, ("Profile", "Key", "Value"), findings)
    actual: dict[str, str] = {}
    invalid = False
    for row_profile, key, value in rows:
        if row_profile != profile:
            findings.append(
                f"R1 contact/evidence {heading} registry contains unknown profile: {row_profile}"
            )
            invalid = True
            continue
        if key not in expected:
            findings.append(
                f"R1 contact/evidence {heading} registry contains unknown key: {key}"
            )
            invalid = True
            continue
        if key in actual:
            findings.append(
                f"R1 contact/evidence {heading} registry contains duplicate key: {key}"
            )
            invalid = True
            continue
        actual[key] = value
    if invalid or actual != expected:
        findings.append(
            f"R1 contact/evidence {heading} registry must equal the approved complete key/value set"
        )


def _owner_registry(text: str, findings: list[str]) -> None:
    heading = "R1 evidence owner registry"
    rows = _table(
        text,
        heading,
        ("Owner", "Tables", "Dependencies", "Consumers"),
        findings,
    )
    actual: dict[str, tuple[str, str, str]] = {}
    invalid = False
    for owner, tables, dependencies, consumers in rows:
        if owner not in OWNER_REGISTRY:
            findings.append(
                f"R1 contact/evidence {heading} registry contains unknown owner: {owner}"
            )
            invalid = True
            continue
        if owner in actual:
            findings.append(
                f"R1 contact/evidence {heading} registry contains duplicate owner: {owner}"
            )
            invalid = True
            continue
        actual[owner] = (tables, dependencies, consumers)
    if invalid or actual != OWNER_REGISTRY:
        findings.append(
            f"R1 contact/evidence {heading} registry must equal the approved complete owner boundary"
        )


def _require(
    text: str,
    marker: str,
    label: str,
    findings: list[str],
) -> None:
    if marker not in text:
        findings.append(f"R1 contact/evidence contract missing {label}: {marker}")


def validate(root: Path) -> list[str]:
    findings: list[str] = []
    paths = {
        "command": "docs/contracts/r1/R1-COMMAND-POLICY-EVENT-CONTRACT.md",
        "task": "docs/contracts/r1/R1-TASK-COMPLETION-MATRIX.md",
        "http": "docs/contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md",
        "workbench": "docs/contracts/r1/R1-WORKBENCH-PRESENTATION-CONTRACT.md",
        "baseline": "docs/baseline/CURRENT-MVP-BASELINE.md",
        "adr": "docs/adr/ADR-0011-r1-contact-reopen-evidence-read.md",
        "spec": "docs/superpowers/specs/2026-09-06-r1-contact-reopen-evidence-read-design.md",
        "design": "docs/superpowers/specs/2026-09-05-r1-business-closure-alignment-design.md",
        "plan": "docs/superpowers/plans/2026-09-05-r1-business-closure-plan.md",
        "ledger": "docs/progress/MVP-DELIVERY-LEDGER.md",
        "progress": "docs/progress/2026-09-06-r1-local-progress.md",
    }
    documents = {name: _read(root, path, findings) for name, path in paths.items()}

    command = documents["command"]
    _profile_registry(
        command,
        "R1 contact ordinal registry",
        ORDINAL_PROFILE,
        ORDINAL_REGISTRY,
        findings,
    )
    _profile_registry(
        command,
        "R1 evidence reference registry",
        EVIDENCE_PROFILE,
        EVIDENCE_REGISTRY,
        findings,
    )
    _owner_registry(command, findings)

    requirements = {
        "command": (
            ("Semantic baseline: MVP-2026-09-06.3", "command semantic baseline"),
            ("`lead→evidence`、`api→evidence`、`evidence→identity`；读取", "dependency DAG"),
            ("不得以`contactNo<=3`限制事件合法性", "event ordinal"),
            ("最终QUERY阶段", "final QUERY revalidation"),
            ("Owner方法不得切换角色", "no role switch inside Owner"),
        ),
        "task": (
            ("Contract ID: R1-TASK-COMPLETION-V1.2", "Task contract version"),
            ("contactResult@hash; contactNo<3", "Task retry branch"),
            ("contactResult@hash; contactNo>=3", "Task exhausted branch"),
            ("NOT_CONNECTED且contact_no>=3", "Task review causality"),
            ("第3次及以后不得自动创建CONTACT重试", "automatic retry boundary"),
            ("第1次`SUSPECT_INVALID`后重开所得第2次`NOT_CONNECTED`", "early suspect reopen example"),
        ),
        "http": (
            ("Task、Lead、Submission、Binding四个准确Subject的DENY", "four-subject DENY"),
            ("返回同一既有`NOT_FOUND`", "safe Evidence error"),
            ("post-slot `REJECTED Slot:+1, Receipt:+1, Audit:+1`", "phase-specific rejection delta"),
            ("Runtime现有QUERY阶段", "HTTP QUERY placement"),
        ),
        "workbench": (
            ("不得返回文件内容、文件名、对象位置或下载URL", "no Evidence content or locator"),
            ("200 BODY和304 CACHE_REVALIDATED", "200/304 Evidence audit"),
            ("旧ETag不得绕过撤权或Binding撤回", "Evidence cache revalidation"),
            ("选择下一张合格卡或返回安全零态", "hidden card fallback"),
        ),
        "baseline": (
            ("Baseline ID: MVP-2026-09-06.3", "active baseline"),
            ("Task contract `R1-TASK-COMPLETION-V1.2`", "active Task contract"),
            ("physical capability `52-plus-2-v1.2` remain unchanged", "physical capability"),
            ("no production Handler, Evidence port, Workbench or R1 business status is advanced", "contract-only baseline"),
        ),
        "adr": (
            ("Status: Accepted", "ADR-0011 accepted status"),
            ("Semantic baseline: MVP-2026-09-06.3", "ADR-0011 baseline"),
            ("Task contract: R1-TASK-COMPLETION-V1.2", "ADR-0011 Task contract"),
            ("Physical capability: 52-plus-2-v1.2", "ADR-0011 physical capability"),
            ("本次只激活文档与静态验证器", "ADR-0011 delivery boundary"),
        ),
        "spec": (
            ("状态：APPROVED", "approved specification"),
            ("## 2. 联系总序号与自动重试", "approved contact semantics"),
            ("## 3. Evidence引用的最小读口", "approved Evidence semantics"),
        ),
        "design": (
            ("ADR-0011-r1-contact-reopen-evidence-read.md", "named design supersession"),
            ("原Task 6生产实现仍未完成", "design Task 6 handoff"),
        ),
        "plan": (
            ("ADR-0011-r1-contact-reopen-evidence-read.md", "named plan supersession"),
            ("历史Task 6正文/checkbox不改写且仍未完成", "plan Task 6 handoff"),
        ),
        "ledger": (
            ("| R1-CONTACT-EVIDENCE-CONTRACT | R1 | Contact ordinal and Evidence reference contract | Docs |", "contract-only delivery"),
            ("| Engineering | r1-contact-evidence-v1 | R1 implementation | FROZEN |", "contract FROZEN state"),
            ("original Task 6 must implement", "ledger Task 6 handoff"),
        ),
        "progress": (
            ("原Task 6仍未完成", "Task 6 remains incomplete"),
            ("本次只激活`MVP-2026-09-06.3`静态合同与验证器", "static activation only"),
            ("物理能力仍为`52-plus-2-v1.2`", "progress physical capability"),
        ),
    }
    for name, markers in requirements.items():
        for marker, label in markers:
            _require(documents[name], marker, label, findings)

    task = documents["task"]
    for obsolete in (
        "contactResult@hash; attemptNo<3",
        "contactResult@hash; attemptNo=3",
        "NOT_CONNECTED且contact_no=3",
    ):
        if obsolete in task:
            findings.append(
                f"R1 contact/evidence Task contract retains obsolete counter rule: {obsolete}"
            )

    return findings
