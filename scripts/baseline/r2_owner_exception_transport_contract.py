"""Exact registered T01 transport successor. Removes only the named reviewed addition.

The literal SHA-256 pins below freeze canonical parsed request/response/security
contracts. They are not learned from the document under validation. The complete
remaining document still runs through every original R1/Task9 exact check.
"""
from copy import deepcopy
import hashlib
import json

PROFILE = 'R2_OWNER_EXCEPTION_TRANSPORT_V1'
PATH_DIGESTS = {'/api/v1/opportunity-owner-exceptions': 'f50d0777cbae4ab6fa04ab4a5ef2c0d0e1401aac88ae2b375682f29475093aaa',
 '/api/v1/opportunity-owner-exceptions/operations': 'fd6c6ce37c4941a1bf3f1a3afea218869b321d0782cd7280f18f5d02a186b5f2',
 '/api/v1/opportunity-owner-exceptions/{exceptionId}': '7e0975d6880b0f7fc58b822f5678d6be75ecf58ae6de3fcb49a6391e4a34653e',
 '/api/v1/opportunity-owner-exceptions/{exceptionId}/candidates': '7ceb78be095046e9fb1dec48a574354988f8fb0b1548fd657e256d64ea0f7e66',
 '/api/v1/opportunity-owner-exceptions/commands/transfer': '2b2a1c2bcea82b39e60ffb1eec34bab3e20f87266946c2f999e7412264cff789',
 '/api/v1/opportunity-owner-exceptions/commands/coordinate': '3ea676ba801452b0e19ee0d206f791a16ebfcaf134cc628e69f3ca2bdb4c5072',
 '/internal/v1/opportunity-owner-exceptions/candidates': 'ad391fabdccfc025a244199ed5619e90f43c173f63e6aa5562651726d4fd6f44',
 '/internal/v1/opportunity-owner-exceptions/commands/observe': 'affeaea18aace817b4a2095af814b1b8a5c754db07663e21dc494a0d600fda7e'}
SCHEMA_DIGESTS = {'OwnerExceptionSelectorV1': 'e3a895929c5ef92033c71ee3c73e9081bf4139c8dcf716d973a1d4145fdacece',
 'OwnerExceptionRevisionSelectorV1': 'c7e5f0b1ea846a2ce33993e1a4e2bdce0a505dca4ce148b8a95c49141a2d9994',
 'OwnerExceptionDigestSelectorV1': '1a6f8214243ec1377dd23e3d28e3aee7aa33c5e776d8fa13b6a481cbb2f1e83e',
 'OwnerExceptionStateV1': '2f8fcee2236a3f202848fcbc48949502ca84b8777d8155246e27d483e79c4d4e',
 'OwnerExceptionReasonV1': '26e6c59d0c3d542ac98c24ca8f4b9b87b8ef34da6375b73494d14feca3f48a4f',
 'OwnerExceptionDetailV1': 'df3573f073158d74f1b00435f8cd3ab08cee309ba89e18e9c20ba89660c93410',
 'OwnerExceptionPageV1': 'c478421622c0199f947658113f0672a39857d6ec7316a42dc8601f5226d44c69',
 'OwnerExceptionReceiverV1': '7acfbeb17a28268ca16d05bfca7533c97ae8890fa5bc5ed43da459801876c047',
 'OwnerExceptionReceiverPageV1': '3405e1bdb8b4c30161eb254c8b7ba4bd44a0684ca6394901a89ee3202870337c',
 'OwnerExceptionOperationsSummaryV1': '405dcd8f079d3a326e6abd6d88fd7aabb9507526ce2899f456144df72d35576b',
 'OwnerExceptionOperationsPageV1': '4f6b7578d17ca31ef66aed318a407a866caa49c5f22ea2dd8f1b2f779f8a538c',
 'OwnerExceptionObservationCandidateV1': '02eeaf001081a2f01e94203cd2f74c1dc5e077d69185c2d02757095cbe4c30cc',
 'OwnerExceptionObservationPageV1': '097728d353a41ada27b9931dd41162908f413ba4536a55aa5d1dd086220d15bd',
 'ObserveOpportunityOwnerExceptionV1': '8864a6c1cfdd6a969ecf09cc7c29cf2af528d290956a8474d6ef53e1260458df',
 'TransferOpportunityResponsibilityV1': '49d5f001e7570233eb3ce8209b097d9ca82dd47d07065286164aa3179deef75f',
 'RecordOpportunityOwnerCoordinationV1': '939d3393418bae5c375ab55241dfb617da1065f123a2e267dfa1d52b889cb9a3',
 'OwnerExceptionFactRefV1': 'ac27fdede9f044f817af4228867bb28a3cc006c84d7bde2b59ec0e0dba3f9d4b',
 'OwnerExceptionValidationFactRefV1': '12e79e1af6ceac00cefc58f31b93b0b4aa123d28708d4ad551dfaf24a75ab19b',
 'OwnerExceptionCommandReceiptV1': '094ffdd0a060111dacd157b69d6092005f7967f1a02a3663381c160f1102b7f4'}
SESSION_DIGEST = 'ed6127ef800c92bb49ea5522343573113eb299b1376f362dd0ca4ada4818fb06'
GRANTABLE_ADDITIONS = ['SALES_OPPORTUNITY_OWNER', 'OPPORTUNITY_OWNER_EXCEPTION_DISCOVER', 'OPPORTUNITY_OWNER_EXCEPTION_READ', 'OPPORTUNITY_OWNER_EXCEPTION_RESOLVE', 'OPPORTUNITY_OWNER_EXCEPTION_OPERATIONS_READ']
GRANTABLE_DIGEST = '849b7093ec2cdcd0ef3825dac86c265f85dbc660cfcd2a767d63b5298c754978'
PROJECTED_AUTHORITY_DIGEST = '882e5439c0b2c1f3138a559f2b6dc3995bb7d2e32fe8d6ccc7a9897405aa5595'
FACT_REFS = [{'$ref': '#/components/schemas/' + name} for name in ('OwnerExceptionFactRefV1', 'OwnerExceptionValidationFactRefV1')]

def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode()).hexdigest()

def owner_exception_projection(document):
    try:
        from scripts.baseline.r2_opportunity_ledger_transport_contract import ledger_projection
    except ModuleNotFoundError:
        from r2_opportunity_ledger_transport_contract import ledger_projection
    result = ledger_projection(document)
    paths = result['paths']
    schemas = result['components']['schemas']
    session = schemas['SessionContextV1']
    if not any(name in paths for name in PATH_DIGESTS) and not any(name in schemas for name in SCHEMA_DIGESTS) and 'canManageOwnerExceptions' not in session['properties'] and not any(ref in schemas['PublicFactRef']['oneOf'] for ref in FACT_REFS) and not any(code in schemas['GrantableAuthorityCodeV1']['enum'] for code in GRANTABLE_ADDITIONS):
        return result
    unknown = [p for p in paths if 'opportunity-owner-exceptions' in p and p not in PATH_DIGESTS]
    if unknown:
        raise ValueError(f'{PROFILE}: unknown exception route')
    for actual, expected in ((paths, PATH_DIGESTS), (schemas, SCHEMA_DIGESTS)):
        for name, pin in expected.items():
            if digest(actual.pop(name, None)) != pin:
                raise ValueError(f'{PROFILE}: exact {name} required')
    if digest(session) != SESSION_DIGEST:
        raise ValueError(f'{PROFILE}: exact bounded session entry hint required')
    session['properties'].pop('canManageOwnerExceptions')
    for i in (0, 1, 3):
        session['allOf'][i]['then']['properties'].pop('canManageOwnerExceptions')
    grants = schemas['GrantableAuthorityCodeV1']
    if digest(grants) != GRANTABLE_DIGEST or grants['enum'][-5:] != GRANTABLE_ADDITIONS:
        raise ValueError(f'{PROFILE}: exact five controlled authority additions required')
    del grants['enum'][-5:]
    projected = schemas['AuthorityGrantV1']['properties']['authorityCode']
    if digest(projected) != PROJECTED_AUTHORITY_DIGEST or projected['enum'][-5:] != GRANTABLE_ADDITIONS:
        raise ValueError(f'{PROFILE}: exact five projected authority additions required')
    del projected['enum'][-5:]
    refs = schemas['PublicFactRef']['oneOf']
    if refs[:2] != FACT_REFS or any(refs.count(ref) != 1 for ref in FACT_REFS):
        raise ValueError(f'{PROFILE}: exact two result alternatives required')
    del refs[:2]
    return result
