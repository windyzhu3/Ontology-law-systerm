"""Exact V960 successor; preserves deployed V950 and the complete R1 projection."""
from copy import deepcopy
import hashlib
try:
    from scripts.baseline import r2_quote_runtime_schema_contract as v9
    from scripts.baseline.r2_schema_successor_contract import canonical_hash
except ModuleNotFoundError:
    import r2_quote_runtime_schema_contract as v9
    from r2_schema_successor_contract import canonical_hash
PROFILE='R2_QUOTE_TRANSACTION_SCHEMA_V1'
VERSION='52-plus-2-r2-v10'
MIGRATION='db/migration/V960__r2_quote_transaction.sql'
CONTRACT_HASH='3c38e51c27e9617c7273799b34f92573f30e732ea3715f55fda77e95b4d2b1db'
FIELD_HASH='7057e0f4f4776b031fdfe3b7da3facef6601df33c93d4fd19ee588d29edcc736'
MIGRATION_HASH='1f1385ef7564bf03ad7ccea132118b33a013770a80041e3447ee23fb64cb7681'
TARGETS={'opportunity.quote_approval_decision','opportunity.quote_issue','opportunity.quote_response'}

def historical_projection(generated,manifest):
    if manifest.get('contractVersion')!=VERSION or manifest.get('contractSha256')!=CONTRACT_HASH or canonical_hash(manifest)!=CONTRACT_HASH:
        raise ValueError(PROFILE+': exact manifest required')
    inventory=manifest['generatedArtifactSha256']
    actual={'db/migration/'+p.name for p in (generated/'db/migration').glob('*.sql')}
    if len(inventory)!=31 or set(inventory)!=actual or inventory.get(MIGRATION)!=MIGRATION_HASH:
        raise ValueError(PROFILE+': exact V001-V960 inventory required')
    for path,digest in inventory.items():
        if hashlib.sha256((generated/path).read_bytes()).hexdigest()!=digest:
            raise ValueError(PROFILE+': migration bytes changed')
    if manifest.get('fieldContractSha256')!=FIELD_HASH or hashlib.sha256((generated/'field-contract.md').read_bytes()).hexdigest()!=FIELD_HASH:
        raise ValueError(PROFILE+': field contract changed')
    old=v9.v8.project_validated_v8(v9.project_validated_v9(project_validated_v10(manifest)))
    v7=v9.v8.v7
    return v7.v6.v5.v4.v3.project_validated_v3(v7.v6.v5.v4.project_validated_v4(v7.v6.v5.project_validated_v5(v7.v6.project_validated_v6(v7.project_validated_v7(old)))))

def project_validated_v10(manifest):
    result=deepcopy(manifest)
    result['generatedArtifactSha256'].pop(MIGRATION)
    for schema in result['schemas']:
        for table in schema['tables']:
            if table['qualifiedName'] in TARGETS:
                table['columns']=[c for c in table['columns'] if c['name']!='created_in_transaction']
    result['contractVersion']=v9.VERSION
    result['fieldContractSha256']=v9.FIELD_HASH
    result['contractSha256']=v9.CONTRACT_HASH
    if canonical_hash(result)!=v9.CONTRACT_HASH:
        raise ValueError(PROFILE+': historical V950 contract changed')
    return result
