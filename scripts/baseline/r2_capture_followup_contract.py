"""R2_CAPTURE_FOLLOWUP_V1: exact optional stable authorized subject reference."""
from copy import deepcopy
import json

def followup_projection(document):
    result = deepcopy(document)
    summary = result["components"]["schemas"]["NextSummary"]
    field = summary["properties"].pop("subjectFactRef", None)
    if field is None:
        return result
    if "subjectFactRef" in summary["required"] or json.dumps(field, sort_keys=True) != json.dumps({"type": "string", "pattern": "^[A-Za-z0-9_-]{43}$"}, sort_keys=True):
        raise ValueError("R2_CAPTURE_FOLLOWUP_V1 requires the exact optional stable reference")
    return result
