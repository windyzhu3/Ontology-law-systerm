"""Exact role seeds approved for the Haihua business test; no authority changes."""
from copy import deepcopy
import json

ADDED = ['SALES_REPRESENTATIVE', 'SALES_MANAGER', 'FINANCE_OPERATOR', 'CASE_ADMINISTRATOR']
PREVIOUS_ROLE = ['INTAKE_OPERATOR', 'ROUTING_SUPERVISOR', 'CONTACT_OPERATOR']
PREVIOUS_APPOINTMENT = [*PREVIOUS_ROLE, 'IDENTITY_ADMIN']


def haihua_roles_projection(document):
    result = deepcopy(document)
    schemas = result['components']['schemas']
    values = [schemas['IdentityRoleCodeV1'].get('enum'), schemas['AppointmentV1']['properties']['roleCode'].get('enum')]
    if not any(isinstance(value, list) and any(code in value for code in ADDED) for value in values):
        return result
    for actual, previous in zip(values, [PREVIOUS_ROLE, PREVIOUS_APPOINTMENT]):
        if json.dumps(actual) != json.dumps(previous + ADDED):
            raise ValueError('HAIHUA_ROLES_V1: exact four approved role seeds required')
        del actual[-4:]
    return result
