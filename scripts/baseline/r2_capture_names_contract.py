"""Exact optional R2 name fields; never reinterpret the legacy capturedName."""
from copy import deepcopy
import json

PROFILE = 'R2_LEAD_CAPTURE_NAMES_V1'
NAMES = ('customerName', 'contactName')
FIELD = {'$ref': '#/components/schemas/SafeText200'}


def capture_names_projection(document):
    result = deepcopy(document)
    schema = result['components']['schemas']['CaptureLeadV1']
    properties = schema['properties']
    if not any(name in properties for name in NAMES):
        return result
    for name in NAMES:
        if name in schema.get('required', []) or json.dumps(properties.pop(name, None), sort_keys=True) != json.dumps(FIELD, sort_keys=True):
            raise ValueError(f'{PROFILE}: exact optional {name} required')
    return result
