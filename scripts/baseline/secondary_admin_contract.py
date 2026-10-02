"""Exact four-code secondary administrator successor; no other transport changes."""
from copy import deepcopy
import hashlib,json
MANAGEMENT=['IDENTITY_PRINCIPAL_MANAGE','IDENTITY_ORGANIZATION_MANAGE','IDENTITY_APPOINTMENT_MANAGE','IDENTITY_AUTHORITY_MANAGE']
OLD_PIN='28f0a1666f4d88e2a89f005395f90855ab5c78e9c561fe95fed510a9d4cc8288'
NEW_PIN='75d6ceab822cde67f05584709aa3a32f0d621ec67656062c6cf7fd6071eaca6f'
def digest(value):return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode()).hexdigest()
def secondary_admin_projection(document):
 result=deepcopy(document);schema=result['components']['schemas']['GrantableAuthorityCodeV1']
 if not any(code in schema['enum'] for code in MANAGEMENT):return result
 if digest(schema)!=NEW_PIN:raise ValueError('Secondary administrator requires exact approved four management codes')
 schema['enum']=[code for code in schema['enum'] if code not in MANAGEMENT]
 if digest(schema)!=OLD_PIN:raise ValueError('Frozen prior grant transport changed')
 return result
