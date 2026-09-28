"""Exact V880 delta; reconstruct the previously reviewed V870 manifest before R1 projection."""
from copy import deepcopy
import hashlib
try:
    from scripts.baseline import r2_schema_successor_contract as v1
except ModuleNotFoundError:
    import r2_schema_successor_contract as v1

VERSION='52-plus-2-r2-v2'
PROFILE='R2_PROGRESS_SCHEMA_V1'
MIGRATION='db/migration/V880__r2_opportunity_progress.sql'
CONTRACT_HASH='34ad45bd0ecc35cf35fed7c758e1a8140a73102b3beb0ff40763736dbba376ce'
FIELD_HASH='aae1917586261b51dfce993e08f2898a85af741b9faa1dc05396d9c426a96c5b'
MIGRATION_HASH='d08ee562613682a52ab5708b8b28da88b3586d3a7fe18c15dfe05383f2737de7'

def historical_projection(generated,manifest):
    if manifest.get('contractVersion')!=VERSION or manifest.get('contractSha256')!=CONTRACT_HASH or v1.canonical_hash(manifest)!=CONTRACT_HASH:
        raise ValueError(PROFILE+': exact manifest required')
    inventory=manifest['generatedArtifactSha256']
    actual={'db/migration/'+p.name for p in (generated/'db/migration').glob('*.sql')}
    if len(inventory)!=23 or set(inventory)!=actual or inventory.get(MIGRATION)!=MIGRATION_HASH:
        raise ValueError(PROFILE+': exact V001-V880 inventory required')
    for path,digest in inventory.items():
        if hashlib.sha256((generated/path).read_bytes()).hexdigest()!=digest: raise ValueError(PROFILE+': migration bytes changed')
    if manifest.get('fieldContractSha256')!=FIELD_HASH or hashlib.sha256((generated/'field-contract.md').read_bytes()).hexdigest()!=FIELD_HASH:
        raise ValueError(PROFILE+': field contract changed')
    return project_validated_v2(manifest)

def project_validated_v2(manifest):
    result=deepcopy(manifest)
    result['generatedArtifactSha256'].pop(MIGRATION)
    tables={f"{s['name']}.{t['name']}":t for s in result['schemas'] for t in s['tables']}
    progress=tables['opportunity.opportunity_progress']
    progress['columns']=progress['columns'][:-1]
    progress['constraints']=[c for c in progress['constraints'] if c['name']!='ck_opportunity_progress__protected_body']
    next(c for c in progress['columns'] if c['name']=='progress_digest')['comment']='进展事实摘要：覆盖类型、合同版本和准确来源Fact，不复制来源正文。'
    task=tables['responsibility.task_occurrence']
    task['columns']=task['columns'][:-1]
    task['constraints']=[c for c in task['constraints'] if c['name'] not in ('uq_task_occurrence__progress_successor','ck_task_occurrence__progress_predecessor')]
    task['foreignKeys']=[f for f in task['foreignKeys'] if f['name']!='fk_task_occurrence__progress_predecessor']
    result['physicalForeignKeyWhitelist']=[f for f in result['physicalForeignKeyWhitelist'] if f['name']!='fk_task_occurrence__progress_predecessor']
    result['contractVersion']=v1.VERSION
    result['fieldContractSha256']=v1.FIELD_HASH
    result['contractSha256']=v1.CONTRACT_HASH
    if v1.canonical_hash(result)!=v1.CONTRACT_HASH: raise ValueError(PROFILE+': historical R2 V1 contract changed')
    return v1.project_validated_v1(result)
