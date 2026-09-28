"""Exact additive transport successor: docs/baseline/R2-MY-TASK-SELECTION-V1.md.

Validate every approved addition before comparing the remaining full document
against the unchanged R1 frozen hashes. Partial activation fails closed.
"""
from copy import deepcopy
import json

try:
    from scripts.baseline.r2_capture_followup_contract import followup_projection
    from scripts.baseline.r2_intake_sources_contract import intake_transport_projection
except ModuleNotFoundError:
    from r2_capture_followup_contract import followup_projection
    from r2_intake_sources_contract import intake_transport_projection

PROFILE = 'R2_MY_TASK_SELECTION_V1'
PARAMETER = {
    'name': 'taskId', 'in': 'query', 'required': False,
    'description': 'R2_MY_TASK_SELECTION_V1 selects an authorized OPEN owned task without changing recommendation or task facts.',
    'schema': {'type': 'string', 'format': 'uuid'},
}
ENVELOPE_PROPERTIES = {
    'selectionNotice': {'$ref': '#/components/schemas/SafeText500'},
    'myTasks': {'type': 'array', 'items': {'$ref': '#/components/schemas/NextSummary'}},
    'recommendedTaskId': {'type': ['string', 'null'], 'format': 'uuid'},
}
SUBJECT_TITLE = {'$ref': '#/components/schemas/SafeText200'}
PARAMETER_REF = {'$ref': '#/components/parameters/SelectedTaskId'}


def exact(actual, expected):
    # Python equality aliases False and 0; contract scalar types must stay exact.
    return json.dumps(actual, sort_keys=True) == json.dumps(expected, sort_keys=True)


def r1_transport_projection(document):
    """Return the original R1 transport only after exact successor validation."""
    result = intake_transport_projection(followup_projection(document))
    components = result['components']
    parameters = result['paths']['/api/v1/workcards/current']['get']['parameters']
    envelope = components['schemas']['CurrentWorkCardEnvelope']['properties']
    summary = components['schemas']['NextSummary']['properties']
    active = ('SelectedTaskId' in components['parameters'] or PARAMETER_REF in parameters
              or any(key in envelope for key in ENVELOPE_PROPERTIES) or 'subjectTitle' in summary)
    if not active:
        return result
    if not exact(components['parameters'].pop('SelectedTaskId', None), PARAMETER) or parameters.count(PARAMETER_REF) != 1:
        raise ValueError(f'{PROFILE}: exact optional task selection parameter required')
    # The approved delta appends the parameter; moving/replacing old parameters is not authorized.
    if parameters.pop() != PARAMETER_REF:
        raise ValueError(f'{PROFILE}: selected parameter must follow existing parameters')
    for key, expected in ENVELOPE_PROPERTIES.items():
        if not exact(envelope.pop(key, None), expected):
            raise ValueError(f'{PROFILE}: exact {key} projection required')
    if not exact(summary.pop('subjectTitle', None), SUBJECT_TITLE):
        raise ValueError(f'{PROFILE}: exact authorized subject title required')
    return result
