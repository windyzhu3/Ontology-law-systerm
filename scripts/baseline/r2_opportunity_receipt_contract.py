"""Exact additive R2_OPPORTUNITY_RECEIPT_V1; preserves the complete old contract."""
from copy import deepcopy
import json
try:
    from scripts.baseline.r2_opportunity_submit_contract import submit_transport_projection
    from scripts.baseline.r2_opportunity_card_contract import card_transport_projection
except ModuleNotFoundError:
    from r2_opportunity_submit_contract import submit_transport_projection
    from r2_opportunity_card_contract import card_transport_projection
PROFILE = 'R2_OPPORTUNITY_RECEIPT_V1'
NAME = 'OpportunityProgressFactRefV1'
REF = {'$ref': '#/components/schemas/' + NAME}
SCHEMA = {'type': 'object', 'additionalProperties': False,
          'required': ['factType', 'factRef', 'digest'],
          'properties': {'factType': {'type': 'string', 'const': 'OPPORTUNITY_PROGRESS'},
                         'factRef': {'$ref': '#/components/schemas/OpaqueRef'},
                         'digest': {'$ref': '#/components/schemas/Digest32'}}}
def receipt_transport_projection(document):
    try:
        from scripts.baseline.r2_opportunity_closure_transport_contract import closure_projection
    except ModuleNotFoundError:
        from r2_opportunity_closure_transport_contract import closure_projection
    result = submit_transport_projection(card_transport_projection(closure_projection(document)))
    schemas = result['components']['schemas']
    refs = schemas['PublicFactRef']['oneOf']
    if NAME not in schemas and REF not in refs:
        return result
    if json.dumps(schemas.pop(NAME, None), sort_keys=True) != json.dumps(SCHEMA, sort_keys=True):
        raise ValueError(f'{PROFILE}: exact immutable opaque progress reference required')
    if refs.count(REF) != 1 or refs.pop() != REF:
        raise ValueError(f'{PROFILE}: exact appended receipt alternative required')
    return result
