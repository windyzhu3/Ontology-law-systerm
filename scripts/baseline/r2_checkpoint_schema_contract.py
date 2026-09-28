"""Exact V890 technical-table successor; reconstruct frozen V880 before R1 projection."""
from copy import deepcopy
import hashlib
try:
    from scripts.baseline import r2_progress_schema_contract as v2
    from scripts.baseline.r2_schema_successor_contract import canonical_hash
except ModuleNotFoundError:
    import r2_progress_schema_contract as v2
    from r2_schema_successor_contract import canonical_hash

PROFILE='R2_CHECKPOINT_SCHEMA_V1'
VERSION='52-plus-2-r2-v3'
MIGRATION='db/migration/V890__r2_opportunity_checkpoint.sql'
CONTRACT_HASH='f453e9f2c8b86d19848c18365dd1b2164949cc4724262cdf1913e9b03b0b0773'
FIELD_HASH='7dd99c41ae4adf6be1f15524ff9d94d60a54604c07d10c9ae5cdbde94550c1f5'
MIGRATION_HASH='a17f65cc8d9e365f841fa4134f8152330ccb83ad8849d5a698602c887bf9a4fa'

def historical_projection(generated,manifest):
    if manifest.get('contractVersion')!=VERSION or manifest.get('contractSha256')!=CONTRACT_HASH or canonical_hash(manifest)!=CONTRACT_HASH:
        raise ValueError(PROFILE+': exact manifest required')
    inventory=manifest['generatedArtifactSha256']
    actual={'db/migration/'+p.name for p in (generated/'db/migration').glob('*.sql')}
    if len(inventory)!=24 or set(inventory)!=actual or inventory.get(MIGRATION)!=MIGRATION_HASH:
        raise ValueError(PROFILE+': exact V001-V890 inventory required')
    for path,digest in inventory.items():
        if hashlib.sha256((generated/path).read_bytes()).hexdigest()!=digest: raise ValueError(PROFILE+': migration bytes changed')
    if manifest.get('fieldContractSha256')!=FIELD_HASH or hashlib.sha256((generated/'field-contract.md').read_bytes()).hexdigest()!=FIELD_HASH:
        raise ValueError(PROFILE+': field contract changed')
    return project_validated_v3(manifest)

def project_validated_v3(manifest):
    result=deepcopy(manifest)
    result['generatedArtifactSha256'].pop(MIGRATION)
    platform=next(s for s in result['schemas'] if s['name']=='platform_meta')
    if [t['name'] for t in platform['tables']]!=['deployment_state','r2_opportunity_checkpoint']:
        raise ValueError(PROFILE+': exact technical table identity required')
    platform['tables'].pop();platform['tableCount']=1
    result['selfManagedPlatformTableCount']=1
    result['physicalTableCountAfterFlywayBootstrap']=54
    result['contractVersion']=v2.VERSION
    result['fieldContractSha256']=v2.FIELD_HASH
    result['contractSha256']=v2.CONTRACT_HASH
    if canonical_hash(result)!=v2.CONTRACT_HASH: raise ValueError(PROFILE+': historical V880 contract changed')
    return v2.project_validated_v2(result)
