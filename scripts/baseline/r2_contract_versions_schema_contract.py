"""Exact named V970/V980 development successor; never promotes runtime readiness."""
import hashlib
import sys
from pathlib import Path
try:
    from scripts.baseline import r2_quote_transaction_schema_contract as v10
    from scripts.baseline.r2_schema_successor_contract import canonical_hash
except ModuleNotFoundError:
    import r2_quote_transaction_schema_contract as v10
    from r2_schema_successor_contract import canonical_hash
PROFILE='R2_CONTRACT_VERSIONS_SCHEMA_V1'
VERSION='52-plus-2-r2-v12'
CONTRACT_HASH='6553af6980fbe3da6b2c737b81080120d31b40a9d8c1255de3719cc466acbe04'
FIELD_HASH='4d2459866c9e4a6cd3f6d60556f5ff039a4604e70895fbcebdebe8ce15b0d404'

def historical_projection(generated,manifest):
    if manifest.get('contractVersion')!=VERSION or manifest.get('contractSha256')!=CONTRACT_HASH or canonical_hash(manifest)!=CONTRACT_HASH:
        raise ValueError(PROFILE+': exact reviewed manifest required')
    inventory=manifest['generatedArtifactSha256']
    actual={'db/migration/'+p.name for p in (generated/'db/migration').glob('*.sql')}
    if len(inventory)!=33 or set(inventory)!=actual:
        raise ValueError(PROFILE+': exact V001-V980 inventory required')
    for path,digest in inventory.items():
        if hashlib.sha256((generated/path).read_bytes()).hexdigest()!=digest:
            raise ValueError(PROFILE+': migration bytes changed')
    if manifest.get('fieldContractSha256')!=FIELD_HASH or hashlib.sha256((generated/'field-contract.md').read_bytes()).hexdigest()!=FIELD_HASH:
        raise ValueError(PROFILE+': field contract changed')
    return project_validated_v12(manifest)

def project_validated_v12(manifest):
    inventory=manifest['generatedArtifactSha256']
    # Pure historical rendering avoids a second mutable copy of the frozen table definitions.
    # The existing hard-coded V960 hash independently binds every reconstructed field.
    schema_source=Path(__file__).resolve().parents[2]/'database/schema-contract-52-plus-2'
    sys.path.insert(0,str(schema_source))
    try:
        from contract.schema_contract import BASE_SCHEMAS,EVOLUTIONS
        from contract.render import _manifest
        schemas=BASE_SCHEMAS
        for evolution in EVOLUTIONS:
            if evolution.contract_version== '52-plus-2-r2-v11':break
            schemas=evolution.apply(schemas)
        historical_inventory={k:v for k,v in inventory.items() if k not in (
            'db/migration/V970__r2_contract_preparation.sql','db/migration/V980__r2_contract_versions.sql')}
        old=_manifest(schemas,historical_inventory,v10.FIELD_HASH,v10.VERSION)
    finally:
        sys.path.pop(0)
    if canonical_hash(old)!=v10.CONTRACT_HASH:
        raise ValueError(PROFILE+': historical V960 contract changed')
    old=v10.v9.v8.project_validated_v8(v10.v9.project_validated_v9(v10.project_validated_v10(old)))
    v7=v10.v9.v8.v7
    return v7.v6.v5.v4.v3.project_validated_v3(v7.v6.v5.v4.project_validated_v4(v7.v6.v5.project_validated_v5(v7.v6.project_validated_v6(v7.project_validated_v7(old)))))
