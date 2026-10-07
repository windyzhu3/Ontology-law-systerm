"""Exact optional source-mode extension; the frozen intake validator remains unchanged."""
from copy import deepcopy
import json

PROFILE='LINUX_HUMAN_INTAKE_BINDING_V1'
FIELD={'type':'string','enum':['BOUND_TO_PRINCIPAL','SELECTABLE']}

def human_intake_projection(document):
    result=deepcopy(document)
    schema=result.get('components',{}).get('schemas',{}).get('LeadIntakeSourcesV1',{})
    if 'sourceSelection' not in schema.get('properties',{}) and 'x-contract-extension' not in schema:return result
    try:
        from scripts.baseline.r2_intake_sources_contract import SCHEMAS
    except ModuleNotFoundError:
        from r2_intake_sources_contract import SCHEMAS
    expected=deepcopy(SCHEMAS['LeadIntakeSourcesV1'])
    expected['properties']['sourceSelection']=FIELD;expected['x-contract-extension']=PROFILE
    if json.dumps(schema,sort_keys=True)!=json.dumps(expected,sort_keys=True):raise ValueError(PROFILE+': exact named source mode required')
    result['components']['schemas']['LeadIntakeSourcesV1']=deepcopy(SCHEMAS['LeadIntakeSourcesV1'])
    return result
