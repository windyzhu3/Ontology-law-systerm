"""Exact V1000 quote preparation successor; development projection grants no runtime release."""
import hashlib
import sys
from pathlib import Path
try:
 from scripts.baseline import r2_manual_signature_schema_contract as v13
 from scripts.baseline.r2_schema_successor_contract import canonical_hash
except ModuleNotFoundError:
 import r2_manual_signature_schema_contract as v13
 from r2_schema_successor_contract import canonical_hash
PROFILE='R2_QUOTE_PREPARATION_SCHEMA_V1'
VERSION='52-plus-2-r2-v14'
CONTRACT_HASH='e9df49d769671f3b278bb2c133bcd54fec440d4fb9969e8561331162d562146c'
FIELD_HASH='c1a70f425f607eace88e7a8052e80395c2a87030f67fb3120d5037fbfcf27c03'

def historical_projection(generated,manifest):
 if manifest.get('contractVersion')!=VERSION or manifest.get('contractSha256')!=CONTRACT_HASH or canonical_hash(manifest)!=CONTRACT_HASH:
  raise ValueError(PROFILE+': exact reviewed manifest required')
 inventory=manifest['generatedArtifactSha256']
 actual={'db/migration/'+p.name for p in (generated/'db/migration').glob('*.sql')}
 if len(inventory)!=35 or set(inventory)!=actual:
  raise ValueError(PROFILE+': exact V001-V1000 inventory required')
 for path,digest in inventory.items():
  if hashlib.sha256((generated/path).read_bytes()).hexdigest()!=digest:
   raise ValueError(PROFILE+': migration bytes changed')
 if manifest.get('fieldContractSha256')!=FIELD_HASH or hashlib.sha256((generated/'field-contract.md').read_bytes()).hexdigest()!=FIELD_HASH:
  raise ValueError(PROFILE+': field contract changed')
 return project_validated_v14(manifest)

def project_validated_v14(manifest):
 inventory=manifest['generatedArtifactSha256']
 schema_source=Path(__file__).resolve().parents[2]/'database/schema-contract-52-plus-2'
 sys.path.insert(0,str(schema_source))
 try:
  from contract.schema_contract import BASE_SCHEMAS,EVOLUTIONS
  from contract.render import _manifest
  schemas=BASE_SCHEMAS
  for evolution in EVOLUTIONS:
   if evolution.version>=1000:break
   schemas=evolution.apply(schemas)
  old=_manifest(schemas,{k:v for k,v in inventory.items() if k!='db/migration/V1000__r2_quote_preparation_intent.sql'},v13.FIELD_HASH,v13.VERSION)
 finally:sys.path.pop(0)
 if canonical_hash(old)!=v13.CONTRACT_HASH:
  raise ValueError(PROFILE+': historical V990 contract changed')
 return v13.project_validated_v13(old)
