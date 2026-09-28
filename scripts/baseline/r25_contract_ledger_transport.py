"""Named R2.5 ledger amendment; restore only its approved additive query shape."""
from copy import deepcopy
from hashlib import sha256
import json

PATH_HASH = "ca1c632614aac714bf9618f9719806d44f462ffd6e3ee51016c97bf66b9f622b"
SCHEMA_HASH = "e12c080df61f59d0b5ef5725fc7b78b1ff83c5eddf97cfe552b98565efc408cc"

def digest(value):
    return sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()

def ledger_projection(document):
    result = deepcopy(document)
    path = result["paths"].get("/api/v1/contracts")
    schema = result["components"]["schemas"].get("ContractLedgerPageV1")
    if path is None: return result
    operation = path["get"]
    present = ("x-ledger-profile" in operation or any(p.get("name") in ("limit", "search", "state") for p in operation.get("parameters", [])) or "maxItems" in schema["properties"]["items"])
    if not present: return result
    if digest(path) != PATH_HASH or digest(schema) != SCHEMA_HASH:
        raise ValueError("Exact R25 bounded contract ledger amendment required")
    operation.pop("x-ledger-profile")
    operation["description"] = "Authorized audited T08 read; minimum conflict disclosure; no-store."
    operation["parameters"] = [p for p in operation["parameters"] if p.get("name") not in ("limit", "search", "state")]
    for parameter in operation["parameters"]:
        if parameter.get("name") == "cursor": parameter["schema"] = {"type": "string", "maxLength": 2048}
    schema["properties"]["items"].pop("maxItems")
    schema["properties"]["nextCursor"]["oneOf"][0] = {"type": "string", "maxLength": 2048}
    return result
