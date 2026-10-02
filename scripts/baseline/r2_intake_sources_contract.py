"""Exact additive metadata transport successor: R2_LEAD_INTAKE_SOURCES_V1."""
from copy import deepcopy
import json
try:
    from scripts.baseline.r2_owner_exception_transport_contract import owner_exception_projection
    from scripts.baseline.r2_internal_opportunity_contract import internal_opportunity_projection
    from scripts.baseline.r2_capture_names_contract import capture_names_projection
    from scripts.baseline.r2_opportunity_receipt_contract import receipt_transport_projection
except ModuleNotFoundError:
    from r2_owner_exception_transport_contract import owner_exception_projection
    from r2_internal_opportunity_contract import internal_opportunity_projection
    from r2_capture_names_contract import capture_names_projection
    from r2_opportunity_receipt_contract import receipt_transport_projection

PROFILE = 'R2_LEAD_INTAKE_SOURCES_V1'
PATH = '/api/v1/leads/intake-sources'
PATH_ITEM = {'get': {'tags': ['Leads'],
         'operationId': 'getLeadIntakeSources',
         'summary': 'Read authorized own human Lead intake source metadata',
         'description': 'R2_LEAD_INTAKE_SOURCES_V1: requires an active own HUMAN appointment and '
                        'fresh LEAD_CAPTURE authorization for each source. Delegated and SERVICE '
                        'actors are rejected. Returns metadata only; no Lead facts. All responses '
                        'use Cache-Control: no-store.',
         'security': [{'publicBearer': []}],
         'x-on-behalf-selection': 'REJECT',
         'x-tenant-source': 'ACTOR_CONTEXT',
         'x-subject-binding': 'ACTOR_SCOPE',
         'x-error-codes': ['VALIDATION_FAILED',
                           'UNAUTHENTICATED',
                           'NOT_AUTHORIZED',
                           'APPOINTMENT_INACTIVE',
                           'SERVICE_UNAVAILABLE'],
         'parameters': [{'$ref': '#/components/parameters/AppointmentSelection'},
                        {'$ref': '#/components/parameters/OnBehalfAppointmentSelection'}],
         'responses': {'200': {'description': 'Authorized configured intake sources.',
                               'headers': {'Cache-Control': {'$ref': '#/components/headers/IdentityNoStore'}},
                               'content': {'application/json': {'schema': {'$ref': '#/components/schemas/LeadIntakeSourcesV1'}}}},
                       '401': {'$ref': '#/components/responses/PublicUnauthorizedProblem'},
                       '403': {'$ref': '#/components/responses/ForbiddenProblem'},
                       '503': {'$ref': '#/components/responses/UnavailableProblem'},
                       '400': {'$ref': '#/components/responses/BadRequestProblem'}}}}
SCHEMAS = {'LeadIntakeSourceV1': {'type': 'object',
                        'additionalProperties': False,
                        'required': ['sourceAccountCode',
                                     'displayName',
                                     'sourceChannelCode',
                                     'serviceCategoryCode',
                                     'jurisdictionCode',
                                     'urgencyCode'],
                        'properties': {'sourceAccountCode': {'type': 'string',
                                                             'pattern': '^[A-Za-z][A-Za-z0-9_]{0,63}$'},
                                       'displayName': {'$ref': '#/components/schemas/SafeText200'},
                                       'sourceChannelCode': {'$ref': '#/components/schemas/Code64'},
                                       'serviceCategoryCode': {'$ref': '#/components/schemas/Code64'},
                                       'jurisdictionCode': {'$ref': '#/components/schemas/Code64'},
                                       'urgencyCode': {'$ref': '#/components/schemas/Code64'}}},
 'LeadIntakeSourcesV1': {'type': 'object',
                         'additionalProperties': False,
                         'required': ['sources'],
                         'properties': {'sources': {'type': 'array',
                                                    'maxItems': 50,
                                                    'items': {'$ref': '#/components/schemas/LeadIntakeSourceV1'}}}}}


def exact(actual, expected):
    return json.dumps(actual, sort_keys=True) == json.dumps(expected, sort_keys=True)


def intake_transport_projection(document):
    try:
        from scripts.baseline.adm07_audit_query_contract import audit_query_projection
    except ModuleNotFoundError:
        from adm07_audit_query_contract import audit_query_projection
    document = audit_query_projection(document)
    try:
        from scripts.baseline.configurable_roles_transport_contract import configurable_roles_projection
    except ModuleNotFoundError:
        from configurable_roles_transport_contract import configurable_roles_projection
    document = configurable_roles_projection(document)
    try:
        from scripts.baseline.haihua_roles_transport_contract import haihua_roles_projection
    except ModuleNotFoundError:
        from haihua_roles_transport_contract import haihua_roles_projection
    document = haihua_roles_projection(document)
    try:
        from scripts.baseline.personal_waiting_transport_contract import personal_waiting_projection
    except ModuleNotFoundError:
        from personal_waiting_transport_contract import personal_waiting_projection
    document = personal_waiting_projection(document)
    """Remove only the complete, exactly approved intake metadata addition."""
    result = capture_names_projection(receipt_transport_projection(internal_opportunity_projection(owner_exception_projection(document))))
    schemas = result['components']['schemas']
    if PATH not in result['paths'] and not any(name in schemas for name in SCHEMAS):
        return result
    if not exact(result['paths'].pop(PATH, None), PATH_ITEM):
        raise ValueError(f'{PROFILE}: exact intake metadata path required')
    for name, expected in SCHEMAS.items():
        if not exact(schemas.pop(name, None), expected):
            raise ValueError(f'{PROFILE}: exact {name} required')
    return result
