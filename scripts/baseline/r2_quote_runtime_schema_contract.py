"""Exact V950 successor with byte-verified projection to frozen V940 and R1."""
from copy import deepcopy
import hashlib
try:
    from scripts.baseline import r2_quotes_schema_contract as v8
    from scripts.baseline.r2_schema_successor_contract import canonical_hash
except ModuleNotFoundError:
    import r2_quotes_schema_contract as v8
    from r2_schema_successor_contract import canonical_hash

FROZEN_HANDOFF_CONSTRAINTS={'responsibility.task_occurrence': {'ck_task_occurrence__handoff_predecessor': "handoff_predecessor_task_occurrence_id IS NULL OR (business_purpose_code='PROGRESS_OPPORTUNITY' AND handoff_predecessor_task_occurrence_id<>task_occurrence_id AND predecessor_task_occurrence_id IS NULL AND responsibility_basis_type IS NOT NULL AND responsibility_basis_type='opportunity.responsibility_handoff')"}, 'responsibility.wait_receipt': {'ck_wait_receipt__positive_task_revision': "task_revision > 0 OR (wait_contract_code='R2_OPPORTUNITY_HANDOFF_WAIT_V1' AND task_revision=0)", 'ck_wait_receipt__resume_after_entry': "resume_due_at IS NULL OR resume_due_at > entered_waiting_at OR wait_contract_code='R2_OPPORTUNITY_HANDOFF_WAIT_V1'", 'ck_wait_receipt__handoff_shape': "(wait_contract_code='R2_OPPORTUNITY_HANDOFF_WAIT_V1' AND wait_contract_version=1 AND handoff_fact_id IS NOT NULL AND handoff_fact_revision IS NOT NULL AND handoff_fact_revision=0 AND inherited_wait_receipt_id IS NOT NULL AND inherited_wait_hash IS NOT NULL AND origin_progress_id IS NOT NULL AND origin_progress_hash IS NOT NULL AND original_sla_due_at IS NOT NULL) OR (wait_contract_code<>'R2_OPPORTUNITY_HANDOFF_WAIT_V1' AND handoff_fact_id IS NULL AND handoff_fact_revision IS NULL AND inherited_wait_receipt_id IS NULL AND inherited_wait_hash IS NULL AND origin_progress_id IS NULL AND origin_progress_hash IS NULL AND original_sla_due_at IS NULL)"}}

PROFILE='R2_QUOTE_RUNTIME_SCHEMA_V1'
VERSION='52-plus-2-r2-v9'
MIGRATION='db/migration/V950__r2_quote_runtime.sql'
CONTRACT_HASH='c8b7d75ebde89694bb0aa7afa0aa37261f46480d593b32fd539d11e21ac5b8f3'
FIELD_HASH='557d5d0d5649e29ffc89e54b4df80c9d09178ad405816ba9ff35be5d22d31fc0'
MIGRATION_HASH='d466d2e4f1a2c0119a4989708cc9eb61d8e21a5f31acfb6c8cddac8fffbd5b99'
NEW_TABLES={'opportunity.'+name for name in ('quote_approval_policy','quote_approval_policy_signer',
    'quote_approval_request','quote_approval_member','quote_approval_decision','quote_manual_delivery',
    'quote_response_basis','contract_preparation_source','quote_workflow')}

def historical_projection(generated,manifest):
    if manifest.get('contractVersion')!=VERSION or manifest.get('contractSha256')!=CONTRACT_HASH or canonical_hash(manifest)!=CONTRACT_HASH:
        raise ValueError(PROFILE+': exact manifest required')
    inventory=manifest['generatedArtifactSha256']
    actual={'db/migration/'+p.name for p in (generated/'db/migration').glob('*.sql')}
    if len(inventory)!=30 or set(inventory)!=actual or inventory.get(MIGRATION)!=MIGRATION_HASH:
        raise ValueError(PROFILE+': exact V001-V950 inventory required')
    for path,digest in inventory.items():
        if hashlib.sha256((generated/path).read_bytes()).hexdigest()!=digest:
            raise ValueError(PROFILE+': migration bytes changed')
    if manifest.get('fieldContractSha256')!=FIELD_HASH or hashlib.sha256((generated/'field-contract.md').read_bytes()).hexdigest()!=FIELD_HASH:
        raise ValueError(PROFILE+': field contract changed')
    old=v8.project_validated_v8(project_validated_v9(manifest))
    v7=v8.v7
    return v7.v6.v5.v4.v3.project_validated_v3(v7.v6.v5.v4.project_validated_v4(v7.v6.v5.project_validated_v5(v7.v6.project_validated_v6(v7.project_validated_v7(old)))))

def project_validated_v9(manifest):
    result=deepcopy(manifest)
    result['generatedArtifactSha256'].pop(MIGRATION)
    for schema in result['schemas']:
        schema['tables']=[t for t in schema['tables'] if t['qualifiedName'] not in NEW_TABLES]
        schema['tableCount']=len(schema['tables'])
    tables={t['qualifiedName']:t for s in result['schemas'] for t in s['tables']}
    for name, constraints in FROZEN_HANDOFF_CONSTRAINTS.items():
        for constraint in tables[name]['constraints']:
            if constraint['name'] in constraints:
                constraint['expression']=constraints[constraint['name']]
    kept={f['name'] for t in tables.values() for f in t['foreignKeys']}
    result['physicalForeignKeyWhitelist']=[f for f in result['physicalForeignKeyWhitelist'] if f['name'] in kept]
    for table in tables.values():
        for ref in table['typedReferences']:
            ref['allowedTargetTypes']=[t for t in ref['allowedTargetTypes'] if t not in NEW_TABLES]
    for entry in result['typedReferenceRegistry'].values():
        entry['allowedTargetTypes']=[t for t in entry['allowedTargetTypes'] if t not in NEW_TABLES]
    result['applicationTables']=[t for t in result['applicationTables'] if t not in NEW_TABLES]
    result['applicationTableCount']=66
    result['physicalTableCountAfterFlywayBootstrap']=69
    result['contractVersion']=v8.VERSION
    result['fieldContractSha256']=v8.FIELD_HASH
    result['contractSha256']=v8.CONTRACT_HASH
    if canonical_hash(result)!=v8.CONTRACT_HASH:
        raise ValueError(PROFILE+': historical V940 contract changed')
    return result
