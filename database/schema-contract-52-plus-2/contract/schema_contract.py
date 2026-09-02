from __future__ import annotations

from .domains.contract_transfer_platform import SCHEMAS as CONTRACT_TRANSFER_PLATFORM
from .domains.identity_audit_responsibility import SCHEMAS as IDENTITY_AUDIT_RESPONSIBILITY
from .domains.runtime_evidence_party import SCHEMAS as RUNTIME_EVIDENCE_PARTY
from .domains.sales_review import SCHEMAS as SALES_REVIEW
from .evolutions import EVOLUTIONS


_ALL = (
    *IDENTITY_AUDIT_RESPONSIBILITY,
    *RUNTIME_EVIDENCE_PARTY,
    *SALES_REVIEW,
    *CONTRACT_TRANSFER_PLATFORM,
)
_BY_NAME = {schema.name: schema for schema in _ALL}

BASE_SCHEMAS = tuple(
    _BY_NAME[name]
    for name in (
        "identity",
        "audit",
        "responsibility",
        "execution",
        "external_action",
        "evidence",
        "party",
        "lead",
        "opportunity",
        "conflict",
        "contract",
        "transfer",
        "platform_meta",
    )
)

SCHEMAS = BASE_SCHEMAS
for evolution in EVOLUTIONS:
    SCHEMAS = evolution.apply(SCHEMAS)

CONTRACT_VERSION = EVOLUTIONS[-1].contract_version if EVOLUTIONS else "52-plus-2-v1"

if len(_BY_NAME) != len(_ALL):
    raise RuntimeError("静态字段合同包含重复Schema")


__all__ = ("BASE_SCHEMAS", "CONTRACT_VERSION", "EVOLUTIONS", "SCHEMAS")
