"""Exact approved ADM-07 query-only successor; preserve all preceding contracts."""
from copy import deepcopy
import hashlib,json
from pathlib import Path
ADDED=['AuditRecordV1', 'AuditRecordPageV1']
CHANGED=['GrantableAuthorityCodeV1', 'SessionContextV1', 'AuthorityGrantV1']
PATHS=['/api/v1/admin/audit-records', '/api/v1/admin/audit-records/{auditRecordId}', '/api/v1/admin/audit-records/{auditRecordId}/related']
PIN='42e056e371ae7f0709afd06410db6267f10bd12a236351493c69015506d7c37b'
PREVIOUS_PIN='38287abef581fd622c793d6875c04669daeee60f11bf929a535f267042797ff0'
def digest(value):
 return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode()).hexdigest()
def audit_query_projection(document):
 try:
  from scripts.baseline.secondary_admin_contract import secondary_admin_projection
 except ModuleNotFoundError:
  from secondary_admin_contract import secondary_admin_projection
 document=secondary_admin_projection(document)
 schemas=document['components']['schemas'];paths=document['paths']
 if not any(k in schemas for k in ADDED) and not any(k in paths for k in PATHS):return deepcopy(document)
 fragment={'paths':{k:paths.get(k) for k in PATHS},'schemas':{k:schemas.get(k) for k in ADDED+CHANGED}}
 if digest(fragment)!=PIN:raise ValueError('ADM-07 requires the exact approved query-only transport delta')
 previous=json.loads(Path(__file__).with_name('adm07_audit_previous_transport.json').read_text(encoding='utf-8'))
 if digest(previous)!=PREVIOUS_PIN:raise ValueError('Frozen prior audit transport changed')
 result=deepcopy(document)
 for key in PATHS:del result['paths'][key]
 for key in ADDED:del result['components']['schemas'][key]
 result['components']['schemas'].update(previous)
 return result
