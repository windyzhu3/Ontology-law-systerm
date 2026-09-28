"""Exact V930 T06 successor; reconstruct the entire unchanged V920 manifest."""
from copy import deepcopy
import hashlib
try:
 from scripts.baseline import r2_customer_requirements_schema_contract as v6
 from scripts.baseline.r2_schema_successor_contract import canonical_hash
except ModuleNotFoundError:
 import r2_customer_requirements_schema_contract as v6
 from r2_schema_successor_contract import canonical_hash

PROFILE='R2_MATERIALS_SCHEMA_V1'
VERSION='52-plus-2-r2-v7'
MIGRATION='db/migration/V930__r2_materials.sql'
CONTRACT_HASH='771ab5c700d20017d2299d99b5dfaac19a5519b6f87fbce057ab138facc38dec'
FIELD_HASH='91afc478e85fc32a26adfd2da4e41c219a7ed83a8315079ebff7e7427f1c9133'
MIGRATION_HASH='eed0d8f532f00deb9af652d9440e7f903bdb0bc1ec8688a57d2b863a137a9424'
NEW_TABLES={'evidence.material_upload_basis','evidence.material_upload_check','opportunity.material_version'}

def historical_projection(generated,manifest):
 if manifest.get('contractVersion')!=VERSION or manifest.get('contractSha256')!=CONTRACT_HASH or canonical_hash(manifest)!=CONTRACT_HASH:raise ValueError(PROFILE+': exact manifest required')
 inventory=manifest['generatedArtifactSha256']
 actual={'db/migration/'+p.name for p in (generated/'db/migration').glob('*.sql')}
 if len(inventory)!=28 or set(inventory)!=actual or inventory.get(MIGRATION)!=MIGRATION_HASH:raise ValueError(PROFILE+': exact V001-V930 inventory required')
 for path,digest in inventory.items():
  if hashlib.sha256((generated/path).read_bytes()).hexdigest()!=digest:raise ValueError(PROFILE+': migration bytes changed')
 if manifest.get('fieldContractSha256')!=FIELD_HASH or hashlib.sha256((generated/'field-contract.md').read_bytes()).hexdigest()!=FIELD_HASH:raise ValueError(PROFILE+': field contract changed')
 return v6.v5.v4.v3.project_validated_v3(v6.v5.v4.project_validated_v4(v6.v5.project_validated_v5(v6.project_validated_v6(project_validated_v7(manifest)))))

def project_validated_v7(manifest):
 result=deepcopy(manifest);result['generatedArtifactSha256'].pop(MIGRATION)
 for schema in result['schemas']:
  schema['tables']=[t for t in schema['tables'] if t['qualifiedName'] not in NEW_TABLES]
  schema['tableCount']=len(schema['tables'])
 tables={t['qualifiedName']:t for s in result['schemas'] for t in s['tables']}
 kept={f['name'] for t in tables.values() for f in t['foreignKeys']}
 result['physicalForeignKeyWhitelist']=[f for f in result['physicalForeignKeyWhitelist'] if f['name'] in kept]
 for table in tables.values():
  for ref in table['typedReferences']:ref['allowedTargetTypes']=[t for t in ref['allowedTargetTypes'] if t not in NEW_TABLES]
 for entry in result['typedReferenceRegistry'].values():entry['allowedTargetTypes']=[t for t in entry['allowedTargetTypes'] if t not in NEW_TABLES]
 result['applicationTables']=[t for t in result['applicationTables'] if t not in NEW_TABLES]
 result['applicationTableCount']=61;result['physicalTableCountAfterFlywayBootstrap']=64
 result['contractVersion']=v6.VERSION;result['fieldContractSha256']=v6.FIELD_HASH;result['contractSha256']=v6.CONTRACT_HASH
 if canonical_hash(result)!=v6.CONTRACT_HASH:raise ValueError(PROFILE+': historical V920 contract changed')
 return result
