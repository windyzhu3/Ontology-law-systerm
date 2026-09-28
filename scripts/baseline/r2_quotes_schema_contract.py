"""Exact V940 T07 successor; reconstruct the entire unchanged V930 manifest."""
from copy import deepcopy
import hashlib
try:
 from scripts.baseline import r2_materials_schema_contract as v7
 from scripts.baseline.r2_schema_successor_contract import canonical_hash
except ModuleNotFoundError:
 import r2_materials_schema_contract as v7
 from r2_schema_successor_contract import canonical_hash

PROFILE='R2_QUOTES_SCHEMA_V1'
VERSION='52-plus-2-r2-v8'
MIGRATION='db/migration/V940__r2_quotes.sql'
CONTRACT_HASH='c14b075ea9d49db212712cfbce28abaf7cb168e663d84c7c2175b8d72ccc6db9'
FIELD_HASH='b8b1e7f935c6c78076e5dfc1114590cb5eb698eefd9431ad207039e52a14d0a2'
MIGRATION_HASH='f8bc721fa9df8a9c3383e2e56427fbe0b2c743c2898c24edfe4e78ad15533650'
NEW_TABLES={'opportunity.quote_draft','opportunity.quote_package_basis'}

def historical_projection(generated,manifest):
 if manifest.get('contractVersion')!=VERSION or manifest.get('contractSha256')!=CONTRACT_HASH or canonical_hash(manifest)!=CONTRACT_HASH:raise ValueError(PROFILE+': exact manifest required')
 inventory=manifest['generatedArtifactSha256']
 actual={'db/migration/'+p.name for p in (generated/'db/migration').glob('*.sql')}
 if len(inventory)!=29 or set(inventory)!=actual or inventory.get(MIGRATION)!=MIGRATION_HASH:raise ValueError(PROFILE+': exact V001-V940 inventory required')
 for path,digest in inventory.items():
  if hashlib.sha256((generated/path).read_bytes()).hexdigest()!=digest:raise ValueError(PROFILE+': migration bytes changed')
 if manifest.get('fieldContractSha256')!=FIELD_HASH or hashlib.sha256((generated/'field-contract.md').read_bytes()).hexdigest()!=FIELD_HASH:raise ValueError(PROFILE+': field contract changed')
 return v7.v6.v5.v4.v3.project_validated_v3(v7.v6.v5.v4.project_validated_v4(v7.v6.v5.project_validated_v5(v7.v6.project_validated_v6(v7.project_validated_v7(project_validated_v8(manifest))))))

def project_validated_v8(manifest):
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
 result['applicationTableCount']=64;result['physicalTableCountAfterFlywayBootstrap']=67
 result['contractVersion']=v7.VERSION;result['fieldContractSha256']=v7.FIELD_HASH;result['contractSha256']=v7.CONTRACT_HASH
 if canonical_hash(result)!=v7.CONTRACT_HASH:raise ValueError(PROFILE+': historical V930 contract changed')
 return result
