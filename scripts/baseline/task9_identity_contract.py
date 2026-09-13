"""Fail-closed Task9.2a static successor guard. This is not runtime acceptance."""
from __future__ import annotations
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import yaml
try:
    from scripts.baseline.r1_business_closure_contract import _StrictSafeLoader
except ModuleNotFoundError:
    from r1_business_closure_contract import _StrictSafeLoader

METHODS = {'get', 'post', 'put', 'patch', 'delete', 'head', 'options', 'trace'}
OPERATIONS = {
    ('get', '/api/v1/session/context'): 'getSessionContext',
    ('get', '/api/v1/admin/identity/provider-users'): 'listIdentityProviderUsers',
    ('get', '/api/v1/admin/identity/options'): 'getIdentityAdminOptions',
    ('get', '/api/v1/admin/identity/principals'): 'listIdentityPrincipals',
    ('post', '/api/v1/admin/identity/principals'): 'createIdentityPrincipal',
    ('patch', '/api/v1/admin/identity/principals/{id}/display-name'): 'renameIdentityPrincipal',
    ('post', '/api/v1/admin/identity/principals/{id}/suspend'): 'suspendIdentityPrincipal',
    ('post', '/api/v1/admin/identity/principals/{id}/resume'): 'resumeIdentityPrincipal',
    ('post', '/api/v1/admin/identity/principals/{id}/disable'): 'disableIdentityPrincipal',
    ('get', '/api/v1/admin/identity/organizations'): 'listOrganizationUnits',
    ('post', '/api/v1/admin/identity/organizations'): 'createOrganizationUnit',
    ('patch', '/api/v1/admin/identity/organizations/{id}/display-name'): 'renameOrganizationUnit',
    ('post', '/api/v1/admin/identity/organizations/{id}/close'): 'closeOrganizationUnit',
    ('get', '/api/v1/admin/identity/appointments'): 'listAppointments',
    ('post', '/api/v1/admin/identity/appointments'): 'createAppointment',
    ('post', '/api/v1/admin/identity/appointments/{id}/suspend'): 'suspendAppointment',
    ('post', '/api/v1/admin/identity/appointments/{id}/resume'): 'resumeAppointment',
    ('post', '/api/v1/admin/identity/appointments/{id}/end'): 'endAppointment',
    ('get', '/api/v1/admin/identity/authority-grants'): 'listAuthorityGrants',
    ('post', '/api/v1/admin/identity/authority-grants'): 'createAuthorityGrant',
    ('post', '/api/v1/admin/identity/authority-grants/{id}/revoke'): 'revokeAuthorityGrant',
}
COMMANDS = {
    'createIdentityPrincipal': 'CREATE_IDENTITY_PRINCIPAL',
    'renameIdentityPrincipal': 'RENAME_IDENTITY_PRINCIPAL',
    'suspendIdentityPrincipal': 'SUSPEND_IDENTITY_PRINCIPAL',
    'resumeIdentityPrincipal': 'RESUME_IDENTITY_PRINCIPAL',
    'disableIdentityPrincipal': 'DISABLE_IDENTITY_PRINCIPAL',
    'createOrganizationUnit': 'CREATE_ORGANIZATION_UNIT',
    'renameOrganizationUnit': 'RENAME_ORGANIZATION_UNIT',
    'closeOrganizationUnit': 'CLOSE_ORGANIZATION_UNIT',
    'createAppointment': 'CREATE_APPOINTMENT',
    'suspendAppointment': 'SUSPEND_APPOINTMENT',
    'resumeAppointment': 'RESUME_APPOINTMENT',
    'endAppointment': 'END_APPOINTMENT',
    'createAuthorityGrant': 'CREATE_AUTHORITY_GRANT',
    'revokeAuthorityGrant': 'REVOKE_AUTHORITY_GRANT',
}
GRANTABLE = {'LEAD_CAPTURE', 'LEAD_INGRESS_RESOLVE', 'LEAD_INGRESS_COMPLETE',
             'LEAD_ASSIGN', 'LEAD_ROUTING_DECIDE', 'SOURCE_INTAKE_REQUEST_ACK',
             'SALES_CONTACT_OWNER', 'LEAD_VALIDITY_REVIEW'}
API = 'contracts/openapi/ontology-law-api.yaml'
IDENTITY = 'docs/contracts/r1/R1-IDENTITY-ACCESS-CONTRACT.md'
ADR = 'docs/adr/ADR-0015-task9-delegated-context.md'
# Closed reviewed transport successor, including exact schemas, conditions,
# DTO/response bindings and metadata; independent inventory checks below give
# actionable diagnostics and count actual security declarations.
OPENAPI_SHA256 = '988d8676e0955956d32ef4a33ee65756acb0d6ab221ec023e1be3eeb4650b251'

def canonical_hash(document):
    return hashlib.sha256(json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest()

def validate_identity_text(identity):
    required = (
        '| createAppointment | CREATE_APPOINTMENT | IDENTITY_APPOINTMENT_MANAGE | CreateAppointmentV1 | AppointmentCommandReceiptV1 | ROOT |',
        '`R1_IDENTITY_BOOTSTRAP_CANDIDATE_V1`',
        'restricted offline execution environment', 'exactly one real account',
        'There is no Actor, browser endpoint, persistent operator session or candidate table.',
        'different-purpose envelopes cannot cross online/bootstrap boundaries',
        'NEW bootstrap requires an unexpired envelope and fresh verification',
        'envelope integrity is always checked',
        'Original-key completed-manifest verification precedes candidate freshness/IdP availability checks only after exact original key, canonical digest, operator/target bindings and complete stored Slot/Receipt/Audit/initial Fact set agree.',
        'requires explicit paired X-Appointment-Id',
        'SELF disclosedSources has maximum101',
        'both on-behalf Audit Actor columns remain null',
        'More than50 distinct eligible candidates is safe503 configuration failure',
        'Initial context establishment after relogin is not a confirmed identity switch.',
        'block new writes',
        'deleting local information does not cancel the original operation',
    )
    return ['Task9 offline bootstrap profile / appointment root scope differs'] if any(value not in identity for value in required) else []

def validate_document(document):
    try:
        return _validate_document(document)
    except (AttributeError, KeyError, TypeError, ValueError):
        # Malformed transport is a finding, never a CLI traceback or acceptance.
        return ['Task9 OpenAPI transport shape is invalid']

def _validate_document(document):
    findings = []
    if not isinstance(document, dict) or not isinstance(document.get('paths'), dict):
        return ['Task9 OpenAPI must have real paths']
    if any(not isinstance(path, str) or not isinstance(item, dict)
           for path, item in document['paths'].items()):
        return ['Task9 OpenAPI path items must be mappings']
    found = [(method, path, operation) for path, item in document['paths'].items()
             if isinstance(item, dict) for method, operation in item.items() if method in METHODS]
    if any(not isinstance(op, dict) or not isinstance(op.get('operationId'), str)
           for _, _, op in found):
        return ['Task9 OpenAPI operations must be named mappings']
    if len(found) != 37 or len({op.get('operationId') for _, _, op in found}) != 37:
        findings.append('Task9 requires exactly 37 unique HTTP operations')
    if any(op.get('security') not in ([{'publicBearer': []}], [{'internalMutualTls': []}])
           for _, _, op in found):
        return ['Task9 OpenAPI security must be an exact single named requirement']
    security = Counter(tuple(op['security'][0]) for _, _, op in found)
    if security != Counter({('publicBearer',): 32, ('internalMutualTls',): 5}):
        findings.append('Task9 requires actual 32 public Bearer / 5 internal mTLS security')
    actual = {(m, p): op.get('operationId') for m, p, op in found
              if p.startswith('/api/v1/admin/identity/') or p == '/api/v1/session/context'}
    if actual != OPERATIONS:
        findings.append('Task9 exact 21 method/path/operationId additions differ')
    components = document.get('components')
    if not isinstance(components, dict) or not isinstance(components.get('schemas'), dict):
        return ['Task9 OpenAPI component schemas must be a mapping']
    schemas = components['schemas']
    if any(not isinstance(schema, dict) for schema in schemas.values()):
        return ['Task9 OpenAPI named schemas must be mappings']
    def well_shaped(node):
        if isinstance(node, dict):
            if 'properties' in node and not isinstance(node['properties'], dict):
                return False
            if 'enum' in node and (not isinstance(node['enum'], list)
                                   or any(isinstance(value, (dict, list)) for value in node['enum'])):
                return False
            return all(well_shaped(value) for value in node.values())
        if isinstance(node, list):
            return all(well_shaped(value) for value in node)
        return True
    if not well_shaped(document):
        return ['Task9 OpenAPI properties and enum shapes are invalid']
    if document.get('info', {}).get('version') != '1.4.0':
        findings.append('Task9 delegated successor must activate OpenAPI 1.4.0')
    parameters = components.get('parameters')
    if not isinstance(parameters, dict):
        return ['Task9 OpenAPI parameters must be a mapping']
    selector = parameters.get('OnBehalfAppointmentSelection')
    if not isinstance(selector, dict) or any(selector.get(key) != value for key, value in {
            'name': 'X-On-Behalf-Appointment-Id', 'in': 'header', 'required': False,
            'schema': {'$ref': '#/components/schemas/Uuid'},
            'x-requires-header': 'X-Appointment-Id'}.items()):
        findings.append('Task9 delegated selector must be optional single UUID with explicit paired own header')
    selector_ref = {'$ref': '#/components/parameters/OnBehalfAppointmentSelection'}
    for _, path, operation in found:
        operation_parameters = operation.get('parameters', [])
        if not isinstance(operation_parameters, list):
            return ['Task9 operation parameters must be an array']
        if operation['security'] == [{'publicBearer': []}]:
            expected_mode = 'REJECT' if path.startswith('/api/v1/admin/identity/') else 'ACCEPT_VALID_HUMAN'
            if operation_parameters.count(selector_ref) != 1 or operation.get('x-on-behalf-selection') != expected_mode:
                findings.append('Task9 public delegated selector binding differs: ' + operation['operationId'])
            bad_request = 'Identity400Problem' if path.startswith('/api/v1/admin/identity/') or path == '/api/v1/session/context' else 'BadRequestProblem'
            if 'VALIDATION_FAILED' not in operation.get('x-error-codes', []) or operation.get('responses', {}).get('400') != {'$ref': '#/components/responses/' + bad_request}:
                findings.append('Task9 public selector requires exact validation400 binding: ' + operation['operationId'])
        elif selector_ref in operation_parameters or 'x-on-behalf-selection' in operation:
            findings.append('Task9 internal mTLS must not consume delegated selector')
    context = schemas.get('SessionContextV1', {})
    properties = context.get('properties', {})
    expected_fields = {'displayName', 'state', 'appointmentChoices', 'selectedAppointmentId',
                       'actorScopeKey', 'canEnterWorkbench', 'canEnterIdentityAdmin',
                       'delegatedAppointmentChoices', 'selectedOnBehalfAppointmentId'}
    if set(properties) != expected_fields or set(context.get('required', [])) != expected_fields or context.get('additionalProperties') is not False:
        findings.append('Task9 self context must have exactly nine closed required fields')
    if properties.get('delegatedAppointmentChoices') != {'type': 'array', 'maxItems': 50, 'items': {'$ref': '#/components/schemas/IdentityChoiceV1'}}:
        findings.append('Task9 delegated choices must be bounded50 safe IdentityChoiceV1')
    if properties.get('selectedOnBehalfAppointmentId') != {'type': ['string', 'null'], 'format': 'uuid'}:
        findings.append('Task9 delegated selection must be a nullable UUID')
    choice = schemas.get('IdentityChoiceV1', {})
    if choice != {'type': 'object', 'additionalProperties': False, 'required': ['id', 'label'],
                  'properties': {'id': {'$ref': '#/components/schemas/Uuid'}, 'label': {'$ref': '#/components/schemas/SafeText200'}}}:
        findings.append('Task9 choices disclose only UUID and safe label, never Principal/Grant/authority')
    if 'NOT_FOUND' not in schemas.get('TerminalRejectionCode', {}).get('enum', []):
        findings.append('Task9 terminal rejection must include NOT_FOUND')
    for method, path, operation in found:
        if (method, path) not in OPERATIONS:
            continue
        if operation.get('security') != [{'publicBearer': []}]:
            findings.append('Task9 operation must require exact publicBearer')
        self_query = path == '/api/v1/session/context'
        expected_source = 'AUTHENTICATED_IDENTITY' if self_query else 'ACTOR_CONTEXT'
        if operation.get('x-tenant-source') != expected_source:
            findings.append('Task9 self identity must not impersonate business Actor')
        if not self_query and operation.get('x-authority-path') != 'DIRECT':
            findings.append('Task9 management requires DIRECT only')
        if operation.get('operationId') == 'createAppointment' and operation.get('x-subject-binding') != 'IDENTITY_ROOT':
            findings.append('Task9 new appointment requires Tenant-root scope')
        if method != 'get' and operation.get('x-command-type') != COMMANDS.get(operation.get('operationId')):
            findings.append('Task9 mutation requires exact static command')
    if set(schemas.get('GrantableAuthorityCodeV1', {}).get('enum', [])) != GRANTABLE:
        findings.append('Task9 grantable authority allowlist is closed')
    selected = schemas.get('SessionContextV1', {}).get('properties', {}).get('selectedAppointmentId', {})
    if selected.get('type') != ['string', 'null']:
        findings.append('Task9 no-appointment self context must allow null without fake Actor')
    def inspect(node):
        if isinstance(node, dict):
            for name in node.get('properties', {}):
                if re.sub('[^a-z]', '', name.lower()).startswith('tenant'):
                    findings.append('Task9 caller/response Tenant field forbidden: ' + name)
            if node.get('in') in {'query', 'header', 'path'} and 'tenant' in node.get('name', '').lower():
                findings.append('Task9 caller Tenant parameter forbidden')
            for value in node.values():
                inspect(value)
        elif isinstance(node, list):
            for value in node:
                inspect(value)
    inspect(document)
    if canonical_hash(document) != OPENAPI_SHA256:
        findings.append('Task9 exact approved transport DTO/security/condition contract differs')
    return findings

def validate(root: Path):
    findings = []
    try:
        document = yaml.load((root / API).read_text(encoding='utf-8'), Loader=_StrictSafeLoader)
        findings.extend(validate_document(document))
        if findings:
            return findings
        identity = (root / IDENTITY).read_text(encoding='utf-8')
        findings.extend(validate_identity_text(identity))
        adr = (root / ADR).read_text(encoding='utf-8')
        http = (root / 'docs/contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md').read_text(encoding='utf-8')
        rows = [tuple(cell.strip() for cell in line.strip('|').split('|')) for line in http.splitlines()
                if line.startswith('| ') and ('/api/v1/admin/identity/' in line or '/api/v1/session/context' in line)]
        expected = []
        for (method, path), operation_id in OPERATIONS.items():
            operation = document['paths'][path][method]
            expected.append((operation_id, method.upper(), path, operation['x-tenant-source'],
                             'NONE' if method == 'get' else 'REQUIRED', 'IDENTITY_ETAG' if '{id}' in path else 'NONE',
                             operation['x-subject-binding'], '201' if operation_id.startswith('create') else '200',
                             ','.join(operation['x-error-codes'])))
        if Counter(rows) != Counter(expected):
            findings.append('Task9 HTTP registry must match every exact Identity operation and safe error binding')
        if 'Contract ID: R1-IDENTITY-ACCESS-V1.1' not in identity or 'Status: FROZEN' not in identity:
            findings.append('Task9 Identity successor must be FROZEN V1.1')
        if 'Status: Accepted' not in adr or 'Semantic baseline: MVP-2026-09-08.3' not in adr:
            findings.append('Task9 ADR-0015 must activate exact successor')
        if 'Contract ID: R1-HTTP-V1.5' not in http or 'requires explicit paired X-Appointment-Id' not in http:
            findings.append('Task9 HTTP V1.5 must preserve paired delegated selector rule')
        for name in ('CURRENT-MVP-BASELINE.md',):
            baseline = (root / 'docs/baseline' / name).read_text(encoding='utf-8')
            if 'Baseline ID: MVP-2026-09-08.3' not in baseline or '生产CRUD不计入R1' in baseline:
                findings.append('Task9 baseline must replace prior identity exclusion')
        workbench = (root / 'docs/contracts/r1/R1-WORKBENCH-PRESENTATION-CONTRACT.md').read_text(encoding='utf-8')
        if '身份管理生产能力不属于 R1' in workbench or 'Contract ID: R1-WORKBENCH-V1.3' not in workbench:
            findings.append('Task9 Workbench successor must replace prior exclusion')
        if any(rule not in workbench for rule in (
                'Initial context establishment after relogin is not a confirmed identity switch.',
                'retain the valid pending four-field recovery marker and block new writes',
                'deleting the local clue does not cancel the original operation')):
            findings.append('Task9 Workbench must preserve pending delegated recovery until explicit identity choice')
    except (OSError, UnicodeError, yaml.YAMLError, TypeError, ValueError) as error:
        findings.append('Task9 required contract artifact invalid or missing')
    return findings
