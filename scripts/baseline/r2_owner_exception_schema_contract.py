"""Exact V900 T01 delta; restore the frozen V890 manifest before older projections."""
from copy import deepcopy
import hashlib
try:
 from scripts.baseline import r2_checkpoint_schema_contract as v3
 from scripts.baseline.r2_schema_successor_contract import canonical_hash
except ModuleNotFoundError:
 import r2_checkpoint_schema_contract as v3
 from r2_schema_successor_contract import canonical_hash
PROFILE='R2_OWNER_EXCEPTION_SCHEMA_V1'
VERSION='52-plus-2-r2-v4'
MIGRATION='db/migration/V900__r2_owner_exception.sql'
CONTRACT_HASH='6d0eec2e6672f882de768502ca25f5b5453e85b9e4cd4d02d538b6949963004d'
FIELD_HASH='bd3518d89a3b35b6f7bdce4f455ed816dcf44c3320c99a3c09ef5a0d1b01c4da'
MIGRATION_HASH='366e370330858db713bcd04a53c4542ba47175e53527f581cff29d14f61a9039'
NEW_TABLES={'opportunity.owner_exception','opportunity.owner_exception_disposition','opportunity.responsibility_handoff'}

def historical_projection(generated,manifest):
 if manifest.get('contractVersion')=='52-plus-2-r2-v5':
  try:
   from scripts.baseline.r2_opportunity_closure_schema_contract import historical_projection as closure_projection
  except ModuleNotFoundError:
   from r2_opportunity_closure_schema_contract import historical_projection as closure_projection
  return closure_projection(generated,manifest)
 if manifest.get('contractVersion')!=VERSION or manifest.get('contractSha256')!=CONTRACT_HASH or canonical_hash(manifest)!=CONTRACT_HASH:
  raise ValueError(PROFILE+': exact manifest required')
 inventory=manifest['generatedArtifactSha256']
 actual={'db/migration/'+p.name for p in (generated/'db/migration').glob('*.sql')}
 if len(inventory)!=25 or set(inventory)!=actual or inventory.get(MIGRATION)!=MIGRATION_HASH:
  raise ValueError(PROFILE+': exact V001-V900 inventory required')
 for path,digest in inventory.items():
  if hashlib.sha256((generated/path).read_bytes()).hexdigest()!=digest:raise ValueError(PROFILE+': migration bytes changed')
 if manifest.get('fieldContractSha256')!=FIELD_HASH or hashlib.sha256((generated/'field-contract.md').read_bytes()).hexdigest()!=FIELD_HASH:
  raise ValueError(PROFILE+': field contract changed')
 return v3.project_validated_v3(project_validated_v4(manifest))

def project_validated_v4(manifest):
 result=deepcopy(manifest)
 result['generatedArtifactSha256'].pop(MIGRATION)
 opportunity=next(s for s in result['schemas'] if s['name']=='opportunity')
 opportunity['tables']=[t for t in opportunity['tables'] if t['qualifiedName'] not in NEW_TABLES]
 opportunity['tableCount']=9
 tables={t['qualifiedName']:t for s in result['schemas'] for t in s['tables']}
 task=tables['responsibility.task_occurrence'];wait=tables['responsibility.wait_receipt']
 task['columns']=task['columns'][:-9];task['constraints']=task['constraints'][:-6]
 task['typedReferences']=task['typedReferences'][:-2]
 task['mutableColumns']=task['mutableColumns'][:-4];task['writeOnceColumns']=task['writeOnceColumns'][:-4]
 task['foreignKeys']=task['foreignKeys'][:-1]
 wait['columns']=wait['columns'][:-7];wait['constraints']=wait['constraints'][:-3];wait['foreignKeys']=wait['foreignKeys'][:-3]
 next(c for c in wait['constraints'] if c['name']=='ck_wait_receipt__resume_after_entry')['expression']='resume_due_at IS NULL OR resume_due_at > entered_waiting_at'
 next(c for c in wait['constraints'] if c['name']=='ck_wait_receipt__positive_task_revision')['expression']='task_revision > 0'
 checkpoint=tables['platform_meta.r2_opportunity_checkpoint']
 next(c for c in checkpoint['constraints'] if c['name']=='ck_r2_opportunity_checkpoint__kind')['expression']="scan_kind IN ('INITIAL', 'DUE')"
 kept={f['name'] for t in tables.values() for f in t['foreignKeys']}
 result['physicalForeignKeyWhitelist']=[f for f in result['physicalForeignKeyWhitelist'] if f['name'] in kept]
 for slot in ('responsibility_basis','cancellation_fact'):
  result['typedReferenceRegistry'].pop('responsibility.task_occurrence.'+slot)
 for table in tables.values():
  for ref in table['typedReferences']:
   ref['allowedTargetTypes']=[target for target in ref['allowedTargetTypes'] if target not in NEW_TABLES]
 for entry in result['typedReferenceRegistry'].values():
  entry['allowedTargetTypes']=[target for target in entry['allowedTargetTypes'] if target not in NEW_TABLES]
 result['applicationTables']=[name for name in result['applicationTables'] if name not in NEW_TABLES]
 result['applicationTableCount']=52;result['physicalTableCountAfterFlywayBootstrap']=55
 result['contractVersion']=v3.VERSION;result['fieldContractSha256']=v3.FIELD_HASH;result['contractSha256']=v3.CONTRACT_HASH
 if canonical_hash(result)!=v3.CONTRACT_HASH:raise ValueError(PROFILE+': historical V890 contract changed')
 return result
