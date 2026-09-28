"""Exact V990 manual signature successor; development projection grants no runtime release."""
import hashlib
import sys
from pathlib import Path
try:
 from scripts.baseline import r2_contract_versions_schema_contract as v12
 from scripts.baseline.r2_schema_successor_contract import canonical_hash
except ModuleNotFoundError:
 import r2_contract_versions_schema_contract as v12
 from r2_schema_successor_contract import canonical_hash
PROFILE='R2_MANUAL_SIGNATURE_SCHEMA_V1'
VERSION='52-plus-2-r2-v13'
CONTRACT_HASH='4e1341ebd8509249c8bcbe563077fa47519b1f38337e6797b991729e29110698'
FIELD_HASH='84be6ac6cf40031725d2bdf2914409971661aba617bcfbf337aeb97ea13c5cb0'

def historical_projection(generated,manifest):
 if manifest.get('contractVersion')!=VERSION or manifest.get('contractSha256')!=CONTRACT_HASH or canonical_hash(manifest)!=CONTRACT_HASH:
  raise ValueError(PROFILE+': exact reviewed manifest required')
 inventory=manifest['generatedArtifactSha256']
 actual={'db/migration/'+p.name for p in (generated/'db/migration').glob('*.sql')}
 if len(inventory)!=34 or set(inventory)!=actual:
  raise ValueError(PROFILE+': exact V001-V990 inventory required')
 for path,digest in inventory.items():
  if hashlib.sha256((generated/path).read_bytes()).hexdigest()!=digest:
   raise ValueError(PROFILE+': migration bytes changed')
 if manifest.get('fieldContractSha256')!=FIELD_HASH or hashlib.sha256((generated/'field-contract.md').read_bytes()).hexdigest()!=FIELD_HASH:
  raise ValueError(PROFILE+': field contract changed')
 return project_validated_v13(manifest)

def project_validated_v13(manifest):
 inventory=manifest['generatedArtifactSha256']
 schema_source=Path(__file__).resolve().parents[2]/'database/schema-contract-52-plus-2'
 sys.path.insert(0,str(schema_source))
 try:
  from contract.schema_contract import BASE_SCHEMAS,EVOLUTIONS
  from contract.render import _manifest
  schemas=BASE_SCHEMAS
  for evolution in EVOLUTIONS:
   if evolution.version>=990:break
   schemas=evolution.apply(schemas)
  old=_manifest(schemas,{k:v for k,v in inventory.items() if k!='db/migration/V990__r2_manual_signature.sql'},v12.FIELD_HASH,v12.VERSION)
 finally:sys.path.pop(0)
 if canonical_hash(old)!=v12.CONTRACT_HASH:
  raise ValueError(PROFILE+': historical V980 contract changed')
 return v12.project_validated_v12(old)
