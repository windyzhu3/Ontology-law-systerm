"""Closed additive mTLS Opportunity maintenance transport; original R1 stays frozen."""
from copy import deepcopy
import json

PROFILE = 'R2_INTERNAL_OPPORTUNITY_TASKS_V1'
TAG = {'name': 'InternalOpportunityTasks'}
def ref(name): return {'$ref': '#/components/schemas/' + name}
def obj(properties, required=None):
    return {'type': 'object', 'additionalProperties': False, 'required': list(properties) if required is None else required, 'properties': properties}

BASE = {'opportunityId': ref('Uuid'), 'expectedOpportunityRevision': ref('Revision')}
DUE = dict(BASE, taskId=ref('Uuid'), expectedTaskRevision=ref('Revision'), waitReceiptId=ref('Uuid'), waitReceiptHash=ref('Digest32'), progressId=ref('Uuid'), progressHash=ref('Digest32'), dueCutoff=ref('Instant'))
COMMON_ERRORS = {'VALIDATION_FAILED': (400, 'SAME_KEY_AFTER_FIX'), 'UNAUTHENTICATED': (401, 'SAME_KEY_AFTER_REAUTH'), 'NOT_AUTHORIZED': (403, 'NO'), 'RATE_LIMITED': (429, 'SAME_KEY_AFTER_BACKOFF'), 'INTERNAL_ERROR': (500, 'SAME_KEY_AFTER_BACKOFF'), 'SERVICE_UNAVAILABLE': (503, 'SAME_KEY_AFTER_BACKOFF')}
WRITE_ERRORS = dict(COMMON_ERRORS, IDEMPOTENCY_KEY_REQUIRED=(400, 'SAME_KEY_AFTER_FIX'), IDEMPOTENCY_KEY_INVALID=(400, 'SAME_KEY_AFTER_FIX'), COMMAND_PAYLOAD_CONFLICT=(409, 'NO'), NOT_FOUND=(404, 'NO'), STALE_TASK=(412, 'NEW_KEY_AFTER_REFRESH'), STALE_SUBJECT=(412, 'NEW_KEY_AFTER_REFRESH'), STALE_PROGRESS=(412, 'NEW_KEY_AFTER_REFRESH'), OPPORTUNITY_OPENING_SOURCE_INVALID=(422, 'NEW_KEY_AFTER_ADMIN_FIX'), OPPORTUNITY_NOT_FOUND=(404, 'NO'), STALE_OPPORTUNITY=(412, 'NEW_KEY_AFTER_REFRESH'), OPPORTUNITY_CLOSED=(409, 'NO'))
SCHEMAS = {
    'R2OpportunityTaskKindV1': {'type': 'string', 'enum': ['INITIAL', 'DUE']},
    'ActivateInitialOpportunityTaskV1': obj(BASE),
    'ReopenDueOpportunityTaskV1': obj(DUE),
}
for kind, fields in [('INITIAL', BASE), ('DUE', DUE)]:
    SCHEMAS['R2' + kind.title() + 'OpportunityTaskCandidateV1'] = obj(dict(kind={'type': 'string', 'pattern': '^' + kind + '$'}, idempotencyKey={'type': 'string', 'format': 'uuid', 'pattern': '^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'}, **fields))
SCHEMAS['R2OpportunityTaskCandidateV1'] = {'oneOf': [ref('R2InitialOpportunityTaskCandidateV1'), ref('R2DueOpportunityTaskCandidateV1')], 'discriminator': {'propertyName': 'kind', 'mapping': {'INITIAL': '#/components/schemas/R2InitialOpportunityTaskCandidateV1', 'DUE': '#/components/schemas/R2DueOpportunityTaskCandidateV1'}}}
SCHEMAS['R2OpportunityTaskPageV1'] = obj({'candidates': {'type': 'array', 'maxItems': 100, 'items': ref('R2OpportunityTaskCandidateV1')}, 'nextCursor': {'type': 'string', 'minLength': 1, 'maxLength': 2048}}, ['candidates'])
SCHEMAS['R2OpportunityTaskProblemV1'] = obj({'type': {'type': 'string', 'format': 'uri', 'maxLength': 512}, 'title': ref('SafeText200'), 'status': {'type': 'integer', 'minimum': 400, 'maximum': 599}, 'code': {'type': 'string', 'enum': list(WRITE_ERRORS)}, 'detail': ref('SafeText500'), 'instance': {'type': 'string', 'format': 'uri-reference', 'pattern': '^/', 'maxLength': 512}, 'retryPolicy': {'type': 'string', 'enum': sorted({retry for _, retry in WRITE_ERRORS.values()})}})
SCHEMAS['R2OpportunityTaskProblemV1']['properties']['fieldErrors'] = {'type': 'array', 'minItems': 1, 'maxItems': 64, 'items': ref('FieldError')}
SCHEMAS['R2OpportunityTaskProblemV1']['allOf'] = [{'if': {'properties': {'code': {'const': code}}, 'required': ['code']}, 'then': {'properties': {'status': {'const': status}, 'retryPolicy': {'const': retry}}}} for code, (status, retry) in WRITE_ERRORS.items()]

ROOT = '/internal/v1/opportunity-tasks'
PATHS = {}
for suffix, method, operation, request, output, errors in [('/candidates', 'get', 'listR2OpportunityTaskCandidates', None, 'R2OpportunityTaskPageV1', COMMON_ERRORS), ('/commands/activate-initial', 'post', 'activateInitialOpportunityTask', 'ActivateInitialOpportunityTaskV1', 'TaskOccurrenceCommandReceipt', WRITE_ERRORS), ('/commands/reopen-due', 'post', 'reopenDueOpportunityTask', 'ReopenDueOpportunityTaskV1', 'TaskOccurrenceCommandReceipt', WRITE_ERRORS)]:
    parameters = [{'name': 'kind', 'in': 'query', 'required': True, 'schema': ref('R2OpportunityTaskKindV1')}, {'name': 'limit', 'in': 'query', 'required': False, 'schema': {'type': 'integer', 'minimum': 1, 'maximum': 100, 'default': 50}}, {'name': 'cursor', 'in': 'query', 'required': False, 'schema': {'type': 'string', 'minLength': 1, 'maxLength': 2048}}] if request is None else [{'$ref': '#/components/parameters/IdempotencyKey'}]
    responses = {'200': {'description': 'Authorized bounded discovery.' if request is None else 'Committed command receipt or authorized same-key replay.', 'content': {'application/json': {'schema': ref(output)}}}}
    for status in sorted({status for status, _ in errors.values()}):
        responses[str(status)] = {'description': 'Closed R2 maintenance problem; no human receipt endpoint is disclosed.', 'content': {'application/problem+json': {'schema': ref('R2OpportunityTaskProblemV1')}}}
    entry = {'tags': ['InternalOpportunityTasks'], 'operationId': operation, 'security': [{'internalMutualTls': []}], 'x-tenant-source': 'ACTOR_CONTEXT', 'x-error-codes': list(errors), 'parameters': parameters, 'responses': responses}
    if request: entry['requestBody'] = {'required': True, 'content': {'application/json': {'schema': ref(request)}}}
    PATHS[ROOT + suffix] = {method: entry}

def internal_opportunity_projection(document):
    result = deepcopy(document)
    schemas = result['components']['schemas']
    if not any(p in result['paths'] for p in PATHS) and not any(n in schemas for n in SCHEMAS) and TAG not in result.get('tags', []): return result
    if result.get('tags', []).count(TAG) != 1: raise ValueError(f'{PROFILE}: exact tag required')
    result['tags'].remove(TAG)
    for actual, expected in ((result['paths'], PATHS), (schemas, SCHEMAS)):
        for name, value in expected.items():
            if json.dumps(actual.pop(name, None), sort_keys=True) != json.dumps(value, sort_keys=True): raise ValueError(f'{PROFILE}: exact {name} required')
    return result
