"""Exact V1010 followup attempt successor; development projection grants no runtime release."""
import hashlib
import sys
from pathlib import Path
try:
 from scripts.baseline import r2_quote_preparation_schema_contract as v14
 from scripts.baseline.r2_schema_successor_contract import canonical_hash
except ModuleNotFoundError:
 import r2_quote_preparation_schema_contract as v14
 from r2_schema_successor_contract import canonical_hash
PROFILE='R2_FOLLOWUP_ATTEMPT_SCHEMA_V1'
VERSION='52-plus-2-r2-v15'
CONTRACT_HASH='146c58c536f4934ddf7b1981bff4f8a122643f6d3339a4403381dc0b939b7412'
FIELD_HASH='681ea7e199f20e7cb715b0091ae032b71235213504274751fd5fbd99ab2c12ba'

def historical_projection(generated,manifest):
 if manifest.get('contractVersion')!=VERSION or manifest.get('contractSha256')!=CONTRACT_HASH or canonical_hash(manifest)!=CONTRACT_HASH:
  raise ValueError(PROFILE+': exact reviewed manifest required')
 inventory=manifest['generatedArtifactSha256']
 actual={'db/migration/'+p.name for p in (generated/'db/migration').glob('*.sql')}
 if len(inventory)!=36 or set(inventory)!=actual:
  raise ValueError(PROFILE+': exact V001-V1010 inventory required')
 for path,digest in inventory.items():
  if hashlib.sha256((generated/path).read_bytes()).hexdigest()!=digest:
   raise ValueError(PROFILE+': migration bytes changed')
 if manifest.get('fieldContractSha256')!=FIELD_HASH or hashlib.sha256((generated/'field-contract.md').read_bytes()).hexdigest()!=FIELD_HASH:
  raise ValueError(PROFILE+': field contract changed')
 return project_validated_v15(manifest)

def project_validated_v15(manifest):
 inventory=manifest['generatedArtifactSha256']
 schema_source=Path(__file__).resolve().parents[2]/'database/schema-contract-52-plus-2'
 sys.path.insert(0,str(schema_source))
 try:
  from contract.schema_contract import BASE_SCHEMAS,EVOLUTIONS
  from contract.render import _manifest
  schemas=BASE_SCHEMAS
  for evolution in EVOLUTIONS:
   if evolution.version>=1010:break
   schemas=evolution.apply(schemas)
  old=_manifest(schemas,{k:v for k,v in inventory.items() if k!='db/migration/V1010__r2_followup_attempt.sql'},v14.FIELD_HASH,v14.VERSION)
 finally:sys.path.pop(0)
 if canonical_hash(old)!=v14.CONTRACT_HASH:
  raise ValueError(PROFILE+': historical V1000 contract changed')
 return v14.project_validated_v14(old)
