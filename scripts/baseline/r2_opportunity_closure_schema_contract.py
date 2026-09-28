"""Exact V910 terminal-fact successor; prove the complete V900 projection before older gates."""
from copy import deepcopy
import hashlib
try:
 from scripts.baseline import r2_owner_exception_schema_contract as v4
 from scripts.baseline.r2_schema_successor_contract import canonical_hash
except ModuleNotFoundError:
 import r2_owner_exception_schema_contract as v4
 from r2_schema_successor_contract import canonical_hash
PROFILE='R2_OPPORTUNITY_CLOSURE_SCHEMA_V1'
VERSION='52-plus-2-r2-v5'
MIGRATION='db/migration/V910__r2_opportunity_closure.sql'
CONTRACT_HASH='699de563fe9fe1d9da6891c65ddd37dcd8ef34fe3b035eb7b53e792551b7f47a'
FIELD_HASH='dae4cb75eaca6ea50278c6ebf98a091889ab8f9ef5d9c3c7e929cb3148e4997b'
MIGRATION_HASH='4764dbbc02dcb94d33150852d55b671e6c4535c5d1b5883b01cae5ef4f1d70da'

def historical_projection(generated,manifest):
 if manifest.get('contractVersion')!=VERSION or manifest.get('contractSha256')!=CONTRACT_HASH or canonical_hash(manifest)!=CONTRACT_HASH:raise ValueError(PROFILE+': exact manifest required')
 inventory=manifest['generatedArtifactSha256']
 actual={'db/migration/'+p.name for p in (generated/'db/migration').glob('*.sql')}
 if len(inventory)!=26 or set(inventory)!=actual or inventory.get(MIGRATION)!=MIGRATION_HASH:raise ValueError(PROFILE+': exact V001-V910 inventory required')
 for path,digest in inventory.items():
  if hashlib.sha256((generated/path).read_bytes()).hexdigest()!=digest:raise ValueError(PROFILE+': migration bytes changed')
 if manifest.get('fieldContractSha256')!=FIELD_HASH or hashlib.sha256((generated/'field-contract.md').read_bytes()).hexdigest()!=FIELD_HASH:raise ValueError(PROFILE+': field contract changed')
 return v4.v3.project_validated_v3(v4.project_validated_v4(project_validated_v5(manifest)))

def project_validated_v5(manifest):
 result=deepcopy(manifest);result['generatedArtifactSha256'].pop(MIGRATION)
 opportunity=next(s for s in result['schemas'] if s['name']=='opportunity');opportunity['tables']=[t for t in opportunity['tables'] if t['qualifiedName']!='opportunity.closure'];opportunity['tableCount']-=1
 tables={t['qualifiedName']:t for s in result['schemas'] for t in s['tables']}
 next(c for c in tables['responsibility.task_occurrence']['constraints'] if c['name']=='ck_task_occurrence__handoff_cancellation')['expression']="cancellation_fact_type IS NULL OR (state='CANCELLED' AND cancellation_reason_code='R2_OPPORTUNITY_HANDOFF_V1' AND cancellation_fact_type='opportunity.responsibility_handoff' AND cancellation_fact_revision IS NOT NULL AND cancellation_fact_revision=0)"
 kept={f['name'] for t in tables.values() for f in t['foreignKeys']};result['physicalForeignKeyWhitelist']=[f for f in result['physicalForeignKeyWhitelist'] if f['name'] in kept]
 for table in tables.values():
  for ref in table['typedReferences']:ref['allowedTargetTypes']=[t for t in ref['allowedTargetTypes'] if t!='opportunity.closure']
 for entry in result['typedReferenceRegistry'].values():entry['allowedTargetTypes']=[t for t in entry['allowedTargetTypes'] if t!='opportunity.closure']
 result['applicationTables'].remove('opportunity.closure');result['applicationTableCount']=55;result['physicalTableCountAfterFlywayBootstrap']=58
 result['contractVersion']=v4.VERSION;result['fieldContractSha256']=v4.FIELD_HASH;result['contractSha256']=v4.CONTRACT_HASH
 if canonical_hash(result)!=v4.CONTRACT_HASH:raise ValueError(PROFILE+': historical V900 contract changed')
 return result
