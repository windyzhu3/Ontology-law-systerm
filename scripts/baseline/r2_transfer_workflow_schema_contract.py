"""Exact F10/F11 development delta; does not grant R1/R2 runtime acceptance."""
import hashlib
import sys
from pathlib import Path
try:
 from scripts.baseline import r2_contract_negotiation_schema_contract as v17
 from scripts.baseline.r2_schema_successor_contract import canonical_hash
except ModuleNotFoundError:
 import r2_contract_negotiation_schema_contract as v17
 from r2_schema_successor_contract import canonical_hash

VERSION='52-plus-2-r2-v19'
CONTRACT_HASH='dd83a8939c8a9784099186d15270aad9674cf24505180c8c314123fcf3fa3156'
FIELD_HASH='4f5a7b84848936a2430786df0dc1e62ae2b3fc6d0e11197e2ecd46df1ddd5843'
ADDITIONS={'db/migration/V1040__r2_execution_conditions.sql','db/migration/V1050__r2_transfer_workflow.sql'}

def historical_projection(generated,manifest):
 if manifest.get('contractVersion')!=VERSION or manifest.get('contractSha256')!=CONTRACT_HASH or canonical_hash(manifest)!=CONTRACT_HASH:
  raise ValueError('F10/F11 exact reviewed manifest required')
 inventory=manifest['generatedArtifactSha256']
 actual={'db/migration/'+p.name for p in (generated/'db/migration').glob('*.sql')}
 if len(inventory)!=40 or set(inventory)!=actual:
  raise ValueError('F10/F11 exact V001-V1050 inventory required')
 for path,digest in inventory.items():
  if hashlib.sha256((generated/path).read_bytes()).hexdigest()!=digest:
   raise ValueError('F10/F11 migration bytes changed')
 if manifest.get('fieldContractSha256')!=FIELD_HASH or hashlib.sha256((generated/'field-contract.md').read_bytes()).hexdigest()!=FIELD_HASH:
  raise ValueError('F10/F11 field contract changed')
 return project_validated_v19(manifest)

def project_validated_v19(manifest):
 inventory=manifest['generatedArtifactSha256']
 source=Path(__file__).resolve().parents[2]/'database/schema-contract-52-plus-2'
 sys.path.insert(0,str(source))
 try:
  from contract.schema_contract import BASE_SCHEMAS,EVOLUTIONS
  from contract.render import _manifest
  schemas=BASE_SCHEMAS
  for evolution in EVOLUTIONS:
   if evolution.version>=1040:break
   schemas=evolution.apply(schemas)
  previous=_manifest(schemas,{k:v for k,v in inventory.items() if k not in ADDITIONS},v17.FIELD_HASH,v17.VERSION)
 finally:sys.path.pop(0)
 if canonical_hash(previous)!=v17.CONTRACT_HASH:
  raise ValueError('F10/F11 historical V1030 contract changed')
 return v17.project_validated_v17(previous)
