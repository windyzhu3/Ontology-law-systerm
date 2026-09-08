"""Fail-closed Task9.1 static successor guard. This is not runtime acceptance."""
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
ADR = 'docs/adr/ADR-0014-task9-real-user-access.md'
# Closed reviewed transport successor, including exact schemas, conditions,
# DTO/response bindings and metadata; independent inventory checks below give
# actionable diagnostics and count actual security declarations.
OPENAPI_SHA256 = '8a6ca6677cc058dd10244f840c3e347a247cff668f29af07ba709d6ce616c9cc'

def canonical_hash(document):
    return hashlib.sha256(json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest()

def validate_document(document):
    findings = []
    if not isinstance(document, dict) or not isinstance(document.get('paths'), dict):
        return ['Task9 OpenAPI must have real paths']
    found = [(method, path, operation) for path, item in document['paths'].items()
             if isinstance(item, dict) for method, operation in item.items() if method in METHODS]
    if len(found) != 37 or len({op.get('operationId') for _, _, op in found}) != 37:
        findings.append('Task9 requires exactly 37 unique HTTP operations')
    security = Counter(tuple((op.get('security') or [{}])[0]) for _, _, op in found)
    if security != Counter({('publicBearer',): 32, ('internalMutualTls',): 5}):
        findings.append('Task9 requires actual 32 public Bearer / 5 internal mTLS security')
    actual = {(m, p): op.get('operationId') for m, p, op in found
              if p.startswith('/api/v1/admin/identity/') or p == '/api/v1/session/context'}
    if actual != OPERATIONS:
        findings.append('Task9 exact 21 method/path/operationId additions differ')
    schemas = document.get('components', {}).get('schemas', {})
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
        identity = (root / IDENTITY).read_text(encoding='utf-8')
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
        if 'Contract ID: R1-IDENTITY-ACCESS-V1.0' not in identity or 'Status: FROZEN' not in identity:
            findings.append('Task9 Identity successor must be FROZEN V1.0')
        if 'Status: Accepted' not in adr or 'Semantic baseline: MVP-2026-09-08.2' not in adr:
            findings.append('Task9 ADR-0014 must activate exact successor')
        for name in ('CURRENT-MVP-BASELINE.md',):
            baseline = (root / 'docs/baseline' / name).read_text(encoding='utf-8')
            if 'Baseline ID: MVP-2026-09-08.2' not in baseline or '生产CRUD不计入R1' in baseline:
                findings.append('Task9 baseline must replace prior identity exclusion')
        workbench = (root / 'docs/contracts/r1/R1-WORKBENCH-PRESENTATION-CONTRACT.md').read_text(encoding='utf-8')
        if '身份管理生产能力不属于 R1' in workbench or 'Contract ID: R1-WORKBENCH-V1.2' not in workbench:
            findings.append('Task9 Workbench successor must replace prior exclusion')
    except (OSError, UnicodeError, yaml.YAMLError, TypeError, ValueError) as error:
        findings.append('Task9 required contract artifact invalid or missing')
    return findings
