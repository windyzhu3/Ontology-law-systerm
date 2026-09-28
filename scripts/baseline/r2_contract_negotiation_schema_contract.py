"""Exact V1030 contract negotiation successor; development projection grants no runtime release."""
import hashlib
import sys
from pathlib import Path
try:
 from scripts.baseline import r2_quote_termination_schema_contract as v16
 from scripts.baseline.r2_schema_successor_contract import canonical_hash
except ModuleNotFoundError:
 import r2_quote_termination_schema_contract as v16
 from r2_schema_successor_contract import canonical_hash
PROFILE='R2_CONTRACT_NEGOTIATION_SCHEMA_V1'
VERSION='52-plus-2-r2-v17'
CONTRACT_HASH='c6c46e37b118608471c796629325ab4ab06e8d02121fa315cd8a31664546e3f3'
FIELD_HASH='1f22c14cc922567f5ecc11cfef16ff0e651e0b86f1a80a180beae8d0a526a41d'

def historical_projection(generated,manifest):
 if manifest.get('contractVersion')!=VERSION or manifest.get('contractSha256')!=CONTRACT_HASH or canonical_hash(manifest)!=CONTRACT_HASH:
  raise ValueError(PROFILE+': exact reviewed manifest required')
 inventory=manifest['generatedArtifactSha256']
 actual={'db/migration/'+p.name for p in (generated/'db/migration').glob('*.sql')}
 if len(inventory)!=38 or set(inventory)!=actual:
  raise ValueError(PROFILE+': exact V001-V1030 inventory required')
 for path,digest in inventory.items():
  if hashlib.sha256((generated/path).read_bytes()).hexdigest()!=digest:
   raise ValueError(PROFILE+': migration bytes changed')
 if manifest.get('fieldContractSha256')!=FIELD_HASH or hashlib.sha256((generated/'field-contract.md').read_bytes()).hexdigest()!=FIELD_HASH:
  raise ValueError(PROFILE+': field contract changed')
 return project_validated_v17(manifest)

def project_validated_v17(manifest):
 inventory=manifest['generatedArtifactSha256']
 schema_source=Path(__file__).resolve().parents[2]/'database/schema-contract-52-plus-2'
 sys.path.insert(0,str(schema_source))
 try:
  from contract.schema_contract import BASE_SCHEMAS,EVOLUTIONS
  from contract.render import _manifest
  schemas=BASE_SCHEMAS
  for evolution in EVOLUTIONS:
   if evolution.version>=1030:break
   schemas=evolution.apply(schemas)
  old=_manifest(schemas,{k:v for k,v in inventory.items() if k!='db/migration/V1030__r2_contract_negotiation.sql'},v16.FIELD_HASH,v16.VERSION)
 finally:sys.path.pop(0)
 if canonical_hash(old)!=v16.CONTRACT_HASH:
  raise ValueError(PROFILE+': historical V1020 contract changed')
 return v16.project_validated_v16(old)
