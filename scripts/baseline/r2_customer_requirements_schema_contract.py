"""Exact V920 T05 successor; reconstruct the entire unchanged V910 manifest."""
from copy import deepcopy
import hashlib
try:
 from scripts.baseline import r2_opportunity_closure_schema_contract as v5
 from scripts.baseline.r2_schema_successor_contract import canonical_hash
except ModuleNotFoundError:
 import r2_opportunity_closure_schema_contract as v5
 from r2_schema_successor_contract import canonical_hash

PROFILE='R2_CUSTOMER_REQUIREMENTS_SCHEMA_V1'
VERSION='52-plus-2-r2-v6'
MIGRATION='db/migration/V920__r2_customer_requirements.sql'
CONTRACT_HASH='090857740cef27f4d9c3bef2e3baab90d48c011463e9ffdecce756b1c0e33a2b'
FIELD_HASH='7420f3e0d2bac3f4e9a2f6702d48dea61c11b891d83e7d2e3f74044388853b1e'
MIGRATION_HASH='0fd24123899c4d3f3a0a1b12bf6e491bec181c0db9d56c55e424fbd958704b05'
NEW_TABLES={'party.profile_version','opportunity.customer_requirement_draft','opportunity.customer_requirement_confirmation','opportunity.customer_requirement_participant','opportunity.customer_requirement_draft_party'}

def historical_projection(generated,manifest):
 if manifest.get('contractVersion')!=VERSION or manifest.get('contractSha256')!=CONTRACT_HASH or canonical_hash(manifest)!=CONTRACT_HASH:raise ValueError(PROFILE+': exact manifest required')
 inventory=manifest['generatedArtifactSha256']
 actual={'db/migration/'+p.name for p in (generated/'db/migration').glob('*.sql')}
 if len(inventory)!=27 or set(inventory)!=actual or inventory.get(MIGRATION)!=MIGRATION_HASH:raise ValueError(PROFILE+': exact V001-V920 inventory required')
 for path,digest in inventory.items():
  if hashlib.sha256((generated/path).read_bytes()).hexdigest()!=digest:raise ValueError(PROFILE+': migration bytes changed')
 if manifest.get('fieldContractSha256')!=FIELD_HASH or hashlib.sha256((generated/'field-contract.md').read_bytes()).hexdigest()!=FIELD_HASH:raise ValueError(PROFILE+': field contract changed')
 return v5.v4.v3.project_validated_v3(v5.v4.project_validated_v4(v5.project_validated_v5(project_validated_v6(manifest))))

def project_validated_v6(manifest):
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
 result['applicationTableCount']=56;result['physicalTableCountAfterFlywayBootstrap']=59
 result['contractVersion']=v5.VERSION;result['fieldContractSha256']=v5.FIELD_HASH;result['contractSha256']=v5.CONTRACT_HASH
 if canonical_hash(result)!=v5.CONTRACT_HASH:raise ValueError(PROFILE+': historical V910 contract changed')
 return result
