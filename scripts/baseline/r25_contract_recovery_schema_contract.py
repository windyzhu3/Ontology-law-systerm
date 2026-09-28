"""Exact R25 recovery delta; preserves the frozen R1 and reviewed V1050 projections."""
import hashlib
import sys
from pathlib import Path
try:
 from scripts.baseline import r2_transfer_workflow_schema_contract as v19
 from scripts.baseline.r2_schema_successor_contract import canonical_hash
except ModuleNotFoundError:
 import r2_transfer_workflow_schema_contract as v19
 from r2_schema_successor_contract import canonical_hash

PROFILE='R25_CONTRACT_RESPONSIBILITY_RECOVERY_V1'
VERSION='52-plus-2-r2-v20'
CONTRACT_HASH='623ac761849f24e9aed4b05fa2760827010711e2233a6fe36692ea856e3d2d53'
FIELD_HASH='743eb8832f132235d123d123c658fb04a492d4de7c0e618a922db30791d1f485'
MIGRATION='db/migration/V1060__r25_contract_responsibility_recovery.sql'

def historical_projection(generated,manifest):
 if manifest.get('contractVersion')!=VERSION or manifest.get('contractSha256')!=CONTRACT_HASH or canonical_hash(manifest)!=CONTRACT_HASH:
  raise ValueError(PROFILE+': exact reviewed manifest required')
 inventory=manifest['generatedArtifactSha256']
 actual={'db/migration/'+p.name for p in (generated/'db/migration').glob('*.sql')}
 if len(inventory)!=41 or set(inventory)!=actual or MIGRATION not in inventory:
  raise ValueError(PROFILE+': exact V001-V1060 inventory required')
 for path,digest in inventory.items():
  if hashlib.sha256((generated/path).read_bytes()).hexdigest()!=digest:
   raise ValueError(PROFILE+': migration bytes changed')
 if manifest.get('fieldContractSha256')!=FIELD_HASH or hashlib.sha256((generated/'field-contract.md').read_bytes()).hexdigest()!=FIELD_HASH:
  raise ValueError(PROFILE+': field contract changed')
 source=Path(__file__).resolve().parents[2]/'database/schema-contract-52-plus-2'
 sys.path.insert(0,str(source))
 try:
  from contract.schema_contract import BASE_SCHEMAS,EVOLUTIONS
  from contract.render import _manifest
  schemas=BASE_SCHEMAS
  for evolution in EVOLUTIONS:
   if evolution.version>=1060:break
   schemas=evolution.apply(schemas)
  previous=_manifest(schemas,{k:v for k,v in inventory.items() if k!=MIGRATION},v19.FIELD_HASH,v19.VERSION)
 finally:sys.path.pop(0)
 if canonical_hash(previous)!=v19.CONTRACT_HASH:
  raise ValueError(PROFILE+': historical V1050 contract changed')
 return v19.project_validated_v19(previous)
