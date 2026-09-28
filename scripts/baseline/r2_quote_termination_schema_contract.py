"""Exact V1020 followup attempt successor; development projection grants no runtime release."""
import hashlib
import sys
from pathlib import Path
try:
 from scripts.baseline import r2_followup_attempt_schema_contract as v15
 from scripts.baseline.r2_schema_successor_contract import canonical_hash
except ModuleNotFoundError:
 import r2_followup_attempt_schema_contract as v15
 from r2_schema_successor_contract import canonical_hash
PROFILE='R2_QUOTE_TERMINATION_SCHEMA_V1'
VERSION='52-plus-2-r2-v16'
CONTRACT_HASH='fcfabcb8c0a569df1bc0df1aa436d786595ca7fafa1ae40044c468ec860a0e81'
FIELD_HASH='f3d4e4a34a1bff4647a23269aa0a32cdedeffb5d592c607a6d39d96e97fca6c4'

def historical_projection(generated,manifest):
 if manifest.get('contractVersion')!=VERSION or manifest.get('contractSha256')!=CONTRACT_HASH or canonical_hash(manifest)!=CONTRACT_HASH:
  raise ValueError(PROFILE+': exact reviewed manifest required')
 inventory=manifest['generatedArtifactSha256']
 actual={'db/migration/'+p.name for p in (generated/'db/migration').glob('*.sql')}
 if len(inventory)!=37 or set(inventory)!=actual:
  raise ValueError(PROFILE+': exact V001-V1020 inventory required')
 for path,digest in inventory.items():
  if hashlib.sha256((generated/path).read_bytes()).hexdigest()!=digest:
   raise ValueError(PROFILE+': migration bytes changed')
 if manifest.get('fieldContractSha256')!=FIELD_HASH or hashlib.sha256((generated/'field-contract.md').read_bytes()).hexdigest()!=FIELD_HASH:
  raise ValueError(PROFILE+': field contract changed')
 return project_validated_v16(manifest)

def project_validated_v16(manifest):
 inventory=manifest['generatedArtifactSha256']
 schema_source=Path(__file__).resolve().parents[2]/'database/schema-contract-52-plus-2'
 sys.path.insert(0,str(schema_source))
 try:
  from contract.schema_contract import BASE_SCHEMAS,EVOLUTIONS
  from contract.render import _manifest
  schemas=BASE_SCHEMAS
  for evolution in EVOLUTIONS:
   if evolution.version>=1020:break
   schemas=evolution.apply(schemas)
  old=_manifest(schemas,{k:v for k,v in inventory.items() if k!='db/migration/V1020__r2_quote_termination.sql'},v15.FIELD_HASH,v15.VERSION)
 finally:sys.path.pop(0)
 if canonical_hash(old)!=v15.CONTRACT_HASH:
  raise ValueError(PROFILE+': historical V1010 contract changed')
 return v15.project_validated_v15(old)
