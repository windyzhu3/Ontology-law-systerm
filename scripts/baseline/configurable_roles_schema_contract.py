"""Exact approved configurable-role successor; preserve all frozen projections."""
import hashlib,sys
from pathlib import Path
try:
 from scripts.baseline import r25_contract_recovery_schema_contract as v20
 from scripts.baseline import r2_transfer_workflow_schema_contract as v19
 from scripts.baseline.r2_schema_successor_contract import canonical_hash
except ModuleNotFoundError:
 import r25_contract_recovery_schema_contract as v20
 import r2_transfer_workflow_schema_contract as v19
 from r2_schema_successor_contract import canonical_hash
VERSION='52-plus-2-r2-v21'
CONTRACT_HASH='c73031d295efa105a17fdaf114ce35ef393f7746efa67234eec014c425b38508'
FIELD_HASH='d1cad52f5e7fe312c2355fc192e3420bbb6142610cfcccb21bf64573827d7538'
MIGRATION='db/migration/V1070__configurable_appointment_roles.sql'
def historical_projection(generated,manifest):
 if manifest.get('contractVersion')!=VERSION or manifest.get('contractSha256')!=CONTRACT_HASH or canonical_hash(manifest)!=CONTRACT_HASH:
  raise ValueError('CONFIGURABLE_ROLES_V1: exact reviewed manifest required')
 inventory=manifest['generatedArtifactSha256']
 actual={'db/migration/'+p.name for p in (generated/'db/migration').glob('*.sql')}
 if len(inventory)!=42 or set(inventory)!=actual or MIGRATION not in inventory:
  raise ValueError('CONFIGURABLE_ROLES_V1: exact V001-V1070 inventory required')
 for path,digest in inventory.items():
  if hashlib.sha256((generated/path).read_bytes()).hexdigest()!=digest:raise ValueError('CONFIGURABLE_ROLES_V1: migration bytes changed')
 if manifest.get('fieldContractSha256')!=FIELD_HASH or hashlib.sha256((generated/'field-contract.md').read_bytes()).hexdigest()!=FIELD_HASH:
  raise ValueError('CONFIGURABLE_ROLES_V1: field contract changed')
 return project_validated_v21(manifest)

def project_validated_v21(manifest):
 """Historical continuation after the caller has validated all current bytes."""
 if manifest.get('contractSha256')!=CONTRACT_HASH or canonical_hash(manifest)!=CONTRACT_HASH:
  raise ValueError('CONFIGURABLE_ROLES_V1: exact historical manifest required')
 inventory=manifest['generatedArtifactSha256']
 source=Path(__file__).resolve().parents[2]/'database/schema-contract-52-plus-2';sys.path.insert(0,str(source))
 try:
  from contract.schema_contract import BASE_SCHEMAS,EVOLUTIONS
  from contract.render import _manifest
  schemas=BASE_SCHEMAS
  for evolution in EVOLUTIONS:
   if evolution.version>=1070:break
   schemas=evolution.apply(schemas)
  previous=_manifest(schemas,{k:v for k,v in inventory.items() if k!=MIGRATION},v20.FIELD_HASH,v20.VERSION)
  if canonical_hash(previous)!=v20.CONTRACT_HASH:raise ValueError('CONFIGURABLE_ROLES_V1: historical V1060 contract changed')
  schemas=BASE_SCHEMAS
  for evolution in EVOLUTIONS:
   if evolution.version>=1060:break
   schemas=evolution.apply(schemas)
  older=_manifest(schemas,{k:v for k,v in inventory.items() if k not in (MIGRATION,v20.MIGRATION)},v19.FIELD_HASH,v19.VERSION)
  if canonical_hash(older)!=v19.CONTRACT_HASH:raise ValueError('CONFIGURABLE_ROLES_V1: historical V1050 contract changed')
  return v19.project_validated_v19(older)
 finally:sys.path.pop(0)
