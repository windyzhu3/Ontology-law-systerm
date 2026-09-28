"""Exact T04 additive transport; restores the entire frozen T03 document before older gates."""
from copy import deepcopy
import hashlib
import json
PROFILE = 'R2_OPPORTUNITY_CLOSURE_V1'
PATH_DIGESTS = {'/api/v1/opportunities/{opportunityId}/closure': '792c3638cfbae804716f06f4cc3bcf54f0ad37a481801ada52f70e99ac51d1dd', '/api/v1/opportunities/{opportunityId}/commands/close': '6546ec28af230d2bbb0d5ed07d419aa320bd714f49433c36a7e44ad240fe6a57'}
SCHEMA_DIGESTS = {'OpportunityCloseReasonV1': '67a99547386549d1a79c943ffc80bfb8aecaef35a98efa0b9c551f9c4dfb62b2', 'OpportunityClosureDetailV1': '9909197db68811a0e246523e379bdb617c187bfbbb4cd9cbe4f34a65cabcfc17', 'OpportunityCloseContextV1': '2a6972209646a14b2a6ba59fd184d22578b1d767f13938f5f48352e640a1a682', 'CloseOpportunityV1': '9709b1b18fb22a9b9833c35a39a1b857de1169e5319d3037c56ad1d5f404b508', 'OpportunityClosureFactRefV1': 'ec2c8c3ebd00827c33cba52262ce3dfc5c984118c7132b60629c93a1c8967312', 'OpportunityCloseCommandReceiptV1': '8766684cdc03e5d3c650190bec404b49f55828fbf36f8a14e0692b98200fa297', 'OpportunityCloseProblemV1': '9279c4dfe0da48420be0b2c8c4f422436f8bcb02b002ec66761de0f1e73f5c8a'}
GRANTABLE_DIGEST = '3da23aeea41bf81493260828922876e647908a2a679ff7a205626ecfb603338e'
AUTHORITY_DIGEST = '84751ee8a8aae3c1d50f079af40f6a2c1660d1795fc7811a14cee3af2d8746e1'
FACT_REFS_DIGEST = '1c9bda87d909e5dcd002f461dc632f90a377628ae977155d3428fd256c71b3a4'
REJECTION_DIGEST = 'cd9930d4fca294291ff2ef596d172341f7852e4200a9bb6cf083cb9a6866e7ea'

FACT_REF = {'$ref': '#/components/schemas/OpportunityClosureFactRefV1'}
REJECTIONS = ['OPPORTUNITY_CLOSED', 'OPPORTUNITY_HAS_DOWNSTREAM_FACTS']
def digest(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode()).hexdigest()
def closure_projection(document):
    try:
        from scripts.baseline.r2_customer_requirements_transport_contract import customer_requirements_projection
    except ModuleNotFoundError:
        from r2_customer_requirements_transport_contract import customer_requirements_projection
    result=customer_requirements_projection(document)
    paths,schemas=result['paths'],result['components']['schemas']
    grants=schemas['GrantableAuthorityCodeV1'];authority=schemas['AuthorityGrantV1']['properties']['authorityCode'];refs=schemas['PublicFactRef'];rejections=schemas['TerminalRejectionCode']
    if not (any(n in paths for n in PATH_DIGESTS) or any(n in schemas for n in SCHEMA_DIGESTS) or 'OPPORTUNITY_CLOSE' in grants['enum'] or 'OPPORTUNITY_CLOSE' in authority['enum'] or FACT_REF in refs['oneOf'] or any(c in rejections['enum'] for c in REJECTIONS)):
        return result
    for actual,pins in ((paths,PATH_DIGESTS),(schemas,SCHEMA_DIGESTS)):
        for name,pin in pins.items():
            if digest(actual.pop(name,None))!=pin:raise ValueError(f'{PROFILE}: exact {name} required')
    for actual,pin in ((grants,GRANTABLE_DIGEST),(authority,AUTHORITY_DIGEST),(refs,FACT_REFS_DIGEST),(rejections,REJECTION_DIGEST)):
        if digest(actual)!=pin:raise ValueError(f'{PROFILE}: exact controlled authority, receipt and rejection additions required')
    for enum in (grants['enum'],authority['enum']):
        if enum.pop()!='OPPORTUNITY_CLOSE':raise ValueError(f'{PROFILE}: exact appended close authority required')
    if refs['oneOf'].pop(0)!=FACT_REF or rejections['enum'][-2:]!=REJECTIONS:raise ValueError(f'{PROFILE}: exact receipt alternatives required')
    del rejections['enum'][-2:]
    return result
