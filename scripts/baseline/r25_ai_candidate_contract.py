"""Exact additive R25_AI_CANDIDATES_V1; projects to the unchanged overview baseline."""
from copy import deepcopy
import hashlib,json
PATHS = {'/api/v1/opportunities/{opportunityId}/ai-candidates/{task}': 'ee94b4b093e5114609c5ffa7afa3cfcb65e5b67d22ad0d9f95d650e7865521ed', '/api/v1/opportunities/{opportunityId}/ai-candidates/{task}/recheck': '0b797bccb79008f620fe451d964143625cdb494d3b7977340df9c363e5eb9788'}
SCHEMAS = {'AiCandidateTaskV1': '3f523aec8a3c3a0095aa9348e1f66be14dccfc9c79e218eabb9769c9c4abb50c', 'AiCandidateRequestV1': '99334726611ccf58a148b0814696bfa6fe08c1b2d027e946beccf5a74331c9aa', 'AiCandidateRecheckV1': 'f811d73aa6c061b46a9d9851b11819d635d04683923acbe51d034fdead5c4abe', 'AiCandidateSourceV1': 'bc0aa0a7c7f7bb0d83b4ec6b81fe6183179e6d737839f9b8e0213e905bce081b', 'AiCandidateCitationV1': '11c31aff6fb324c60e72e5874bff3ec186fd53597eedc5287ae9a4ee7a5cd357', 'AiCandidateItemV1': '2d154782f7c8c7adbda78096e5e33fe45f1df6dec9e5b564932c133777ad0885', 'AiCandidateResultV1': '95c388348af3c8c68a78fd62a567d86f746eac8c3b3fac0d07ec9cba5bb25ae3'}
def digest(v):return hashlib.sha256(json.dumps(v,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode()).hexdigest()
def ai_projection(document):
 result=deepcopy(document);paths=result['paths'];schemas=result['components']['schemas']
 present=any('/ai-candidates' in p for p in paths) or any(k in schemas for k in SCHEMAS)
 if not present:return result
 if any('/ai-candidates' in p and p not in PATHS for p in paths):raise ValueError('Unregistered AI candidate route')
 for section,pins in ((paths,PATHS),(schemas,SCHEMAS)):
  for name,pin in pins.items():
   if digest(section.pop(name,None))!=pin:raise ValueError('Exact R25 AI addition required: '+name)
 return result
