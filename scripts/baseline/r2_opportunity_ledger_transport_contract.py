"""Exact additive T03 ledger contract; restores unmodified T01 before historical gates."""
from copy import deepcopy
import hashlib
import json

PROFILE = "R2_OPPORTUNITY_LEDGER_V1"
PATH_DIGESTS = {'/api/v1/opportunities': '1fa37647c8bb2b292aa98b0931892c8285564b787564f8d8f664173ef9cf4e77', '/api/v1/opportunities/{opportunityId}': '853ce42a60f1062541096853c5b8fdfcf89494bc57d674ec576c23c8d431ab92'}
SCHEMA_DIGESTS = {'OpportunityLedgerSelectorV1': 'f5dd2b064573c37defd43233bfc52f84b1390b9fc68b2d1d8ceb6e25a24281ed', 'OpportunityLedgerItemV1': 'c75b1b336918a67c876e1ac9fb9935ab9ddfeead12a23031fd775dab61160e29', 'OpportunityLedgerTaskV1': '16df601ffaa3c313665b1387fdd5027d4114303ca720cb5b1879b293cdda541d', 'OpportunityLedgerProgressV1': 'e5f492384f8b138af0fbd7d533e78717fd6228c8360d0c61c41b5440b0fb85f3', 'OpportunityLedgerDetailV1': '8912ac168e61b8b66d39689e7502c525c3a2fd0a05ce84fdfd84b927c49bbb6f', 'OpportunityLedgerPageV1': '52d7032e030abb3cd9e54c6d49751c359381c5e9371e9ed6544ee2af0ffe0e91'}
SESSION_DIGEST = '505d142ab16275223bb438eb8bc3b7039f051d23ad8033550506a7bd58fcabcd'
GRANTABLE_DIGEST = '23775c81aa1533f1b62c246be785fc335590a454a833eea389582d90108c4563'
PROJECTED_AUTHORITY_DIGEST = 'e81d3588665eac4f6e64d9067acfa5882957e94e9a3d8e504f36ab361c42cc1b'

def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode()).hexdigest()

def ledger_projection(document):
    try:
        from scripts.baseline.r2_opportunity_closure_transport_contract import closure_projection
    except ModuleNotFoundError:
        from r2_opportunity_closure_transport_contract import closure_projection
    result = closure_projection(document)
    paths, schemas = result['paths'], result['components']['schemas']
    session = schemas['SessionContextV1']
    grants = schemas['GrantableAuthorityCodeV1']
    authority = schemas['AuthorityGrantV1']['properties']['authorityCode']
    if not (any(p in paths for p in PATH_DIGESTS) or any(n in schemas for n in SCHEMA_DIGESTS) or 'canReadOpportunityLedger' in session['properties'] or 'OPPORTUNITY_LEDGER_READ' in grants['enum'] or 'OPPORTUNITY_LEDGER_READ' in authority['enum']):
        return result
    if any(p.startswith('/api/v1/opportunities/') and p not in PATH_DIGESTS for p in paths):
        raise ValueError(f'{PROFILE}: unregistered ledger route')
    for actual, expected in ((paths, PATH_DIGESTS), (schemas, SCHEMA_DIGESTS)):
        for name, pin in expected.items():
            if digest(actual.pop(name, None)) != pin:
                raise ValueError(f'{PROFILE}: exact {name} required')
    if digest(session) != SESSION_DIGEST or digest(grants) != GRANTABLE_DIGEST or digest(authority) != PROJECTED_AUTHORITY_DIGEST:
        raise ValueError(f'{PROFILE}: exact session and single controlled authority addition required')
    session['properties'].pop('canReadOpportunityLedger')
    for i in (0, 1, 3):
        session['allOf'][i]['then']['properties'].pop('canReadOpportunityLedger')
    for enum in (grants['enum'], authority['enum']):
        if enum[-1] != 'OPPORTUNITY_LEDGER_READ' or enum.count('OPPORTUNITY_LEDGER_READ') != 1:
            raise ValueError(f'{PROFILE}: exact single authority addition required')
        enum.pop()
    return result
