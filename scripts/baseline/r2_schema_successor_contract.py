"""Exact R2 development schema delta; project back into the frozen R1 validator."""
from copy import deepcopy
import hashlib
import json

PROFILE = "R2_SCHEMA_SUCCESSOR_V1"
VERSION = "52-plus-2-r2-v1"
CONTRACT_HASH = "a946864d2dbedc78f62171e08ef5123cb36d5a6b98a52f938299087742a822be"
FIELD_HASH = "b0de9450e0ada02a435f200ac62c729895de1093ab4a090c75cc797a8305b8f9"
MIGRATION = "db/migration/V870__r2_lead_independent_names.sql"
MIGRATION_HASH = "36799c6888c4b19b2338a1a884d046191e873ade0af4257c5fafe8c0bd94e548"
R1_CONTRACT_HASH = "a4beeb91ed93be455736eafa3abb829f6a94fed3a263be5996832e458b7c4b39"
R1_FIELD_HASH = "f4c17c4c0a8697820b30adb61b8cdb209666a4672393d4f8fc9d73a5f169addf"

def canonical_hash(manifest):
    return hashlib.sha256(json.dumps({k:v for k,v in manifest.items() if k!='contractSha256'},
        ensure_ascii=False,sort_keys=True,separators=(',',':')).encode('utf-8')).hexdigest()

def historical_projection(generated, manifest):
    if manifest.get('contractVersion') == '52-plus-2-r2-v22':
        try:
            from scripts.baseline.metadata_comments_schema_contract import historical_projection as comments_projection
        except ModuleNotFoundError:
            from metadata_comments_schema_contract import historical_projection as comments_projection
        return comments_projection(generated, manifest)
    if manifest.get('contractVersion') == '52-plus-2-r2-v21':
        try:
            from scripts.baseline.configurable_roles_schema_contract import historical_projection as roles_projection
        except ModuleNotFoundError:
            from configurable_roles_schema_contract import historical_projection as roles_projection
        return roles_projection(generated, manifest)
    if manifest.get("contractVersion")=="52-plus-2-r2-v20":
        try:
            from scripts.baseline.r25_contract_recovery_schema_contract import historical_projection as recovery_projection
        except ModuleNotFoundError:
            from r25_contract_recovery_schema_contract import historical_projection as recovery_projection
        return recovery_projection(generated,manifest)
    if manifest.get("contractVersion")=="52-plus-2-r2-v19":
        try:
            from scripts.baseline.r2_transfer_workflow_schema_contract import historical_projection as transfer_projection
        except ModuleNotFoundError:
            from r2_transfer_workflow_schema_contract import historical_projection as transfer_projection
        return transfer_projection(generated,manifest)
    if manifest.get("contractVersion")=="52-plus-2-r2-v17":
        try:
            from scripts.baseline.r2_contract_negotiation_schema_contract import historical_projection as negotiation_projection
        except ModuleNotFoundError:
            from r2_contract_negotiation_schema_contract import historical_projection as negotiation_projection
        return negotiation_projection(generated,manifest)

    if manifest.get("contractVersion")=="52-plus-2-r2-v16":
        try:
            from scripts.baseline.r2_quote_termination_schema_contract import historical_projection as termination_projection
        except ModuleNotFoundError:
            from r2_quote_termination_schema_contract import historical_projection as termination_projection
        return termination_projection(generated,manifest)

    if manifest.get("contractVersion")=="52-plus-2-r2-v15":
        try:
            from scripts.baseline.r2_followup_attempt_schema_contract import historical_projection as attempt_projection
        except ModuleNotFoundError:
            from r2_followup_attempt_schema_contract import historical_projection as attempt_projection
        return attempt_projection(generated,manifest)
    if manifest.get("contractVersion")=="52-plus-2-r2-v14":
        try:
            from scripts.baseline.r2_quote_preparation_schema_contract import historical_projection as preparation_projection
        except ModuleNotFoundError:
            from r2_quote_preparation_schema_contract import historical_projection as preparation_projection
        return preparation_projection(generated,manifest)
    if manifest.get("contractVersion")=="52-plus-2-r2-v13":
        try:
            from scripts.baseline.r2_manual_signature_schema_contract import historical_projection as signing_projection
        except ModuleNotFoundError:
            from r2_manual_signature_schema_contract import historical_projection as signing_projection
        return signing_projection(generated,manifest)
    if manifest.get("contractVersion")=="52-plus-2-r2-v12":
        try:
            from scripts.baseline.r2_contract_versions_schema_contract import historical_projection as contracts_projection
        except ModuleNotFoundError:
            from r2_contract_versions_schema_contract import historical_projection as contracts_projection
        return contracts_projection(generated,manifest)
    if manifest.get("contractVersion")=="52-plus-2-r2-v10":
        try:
            from scripts.baseline.r2_quote_transaction_schema_contract import historical_projection as quote_transaction_projection
        except ModuleNotFoundError:
            from r2_quote_transaction_schema_contract import historical_projection as quote_transaction_projection
        return quote_transaction_projection(generated,manifest)
    if manifest.get("contractVersion")=="52-plus-2-r2-v9":
        try:
            from scripts.baseline.r2_quote_runtime_schema_contract import historical_projection as quote_runtime_projection
        except ModuleNotFoundError:
            from r2_quote_runtime_schema_contract import historical_projection as quote_runtime_projection
        return quote_runtime_projection(generated,manifest)
    if manifest.get("contractVersion")=="52-plus-2-r2-v8":
        try:
            from scripts.baseline.r2_quotes_schema_contract import historical_projection as quotes_projection
        except ModuleNotFoundError:
            from r2_quotes_schema_contract import historical_projection as quotes_projection
        return quotes_projection(generated,manifest)
    if manifest.get("contractVersion")=="52-plus-2-r2-v7":
        try:
            from scripts.baseline.r2_materials_schema_contract import historical_projection as materials_projection
        except ModuleNotFoundError:
            from r2_materials_schema_contract import historical_projection as materials_projection
        return materials_projection(generated,manifest)
    if manifest.get("contractVersion")=="52-plus-2-r2-v6":
        try:
            from scripts.baseline.r2_customer_requirements_schema_contract import historical_projection as customer_projection
        except ModuleNotFoundError:
            from r2_customer_requirements_schema_contract import historical_projection as customer_projection
        return customer_projection(generated,manifest)
    if manifest.get("contractVersion")=="52-plus-2-r2-v5":
        try:
            from scripts.baseline.r2_opportunity_closure_schema_contract import historical_projection as closure_projection
        except ModuleNotFoundError:
            from r2_opportunity_closure_schema_contract import historical_projection as closure_projection
        return closure_projection(generated,manifest)
    if manifest.get("contractVersion")=="52-plus-2-r2-v4":
        try:
            from scripts.baseline.r2_owner_exception_schema_contract import historical_projection as owner_exception_projection
        except ModuleNotFoundError:
            from r2_owner_exception_schema_contract import historical_projection as owner_exception_projection
        return owner_exception_projection(generated,manifest)
    if manifest.get("contractVersion")=="52-plus-2-r2-v3":
        try:
            from scripts.baseline.r2_checkpoint_schema_contract import historical_projection as checkpoint_projection
        except ModuleNotFoundError:
            from r2_checkpoint_schema_contract import historical_projection as checkpoint_projection
        return checkpoint_projection(generated,manifest)
    if manifest.get('contractVersion')=='52-plus-2-r2-v2':
        try:
            from scripts.baseline.r2_progress_schema_contract import historical_projection as progress_projection
        except ModuleNotFoundError:
            from r2_progress_schema_contract import historical_projection as progress_projection
        return progress_projection(generated,manifest)
    if manifest.get('contractVersion')!=VERSION or manifest.get('contractSha256')!=CONTRACT_HASH or canonical_hash(manifest)!=CONTRACT_HASH:
        raise ValueError(PROFILE+': exact reviewed manifest required')
    inventory=manifest['generatedArtifactSha256']
    actual={'db/migration/'+p.name for p in (generated/'db/migration').glob('*.sql')}
    if len(inventory)!=22 or set(inventory)!=actual or inventory.get(MIGRATION)!=MIGRATION_HASH:
        raise ValueError(PROFILE+': exact V001-V870 inventory required')
    for path,digest in inventory.items():
        if hashlib.sha256((generated/path).read_bytes()).hexdigest()!=digest:
            raise ValueError(PROFILE+': migration bytes changed')
    if manifest.get('fieldContractSha256')!=FIELD_HASH or hashlib.sha256((generated/'field-contract.md').read_bytes()).hexdigest()!=FIELD_HASH:
        raise ValueError(PROFILE+': field contract bytes changed')
    return project_validated_v1(manifest)

def project_validated_v1(manifest):
    result=deepcopy(manifest)
    result['generatedArtifactSha256'].pop(MIGRATION)
    lead=next(t for s in result['schemas'] if s['name']=='lead' for t in s['tables'] if t['name']=='lead')
    if tuple(c['name'] for c in lead['columns'][-2:])!=('customer_name_ciphertext','contact_name_ciphertext'):
        raise ValueError(PROFILE+': exact independent names required')
    lead['columns']=lead['columns'][:-2]
    result['contractVersion']='52-plus-2-v1.2'
    result['fieldContractSha256']=R1_FIELD_HASH
    result['contractSha256']=R1_CONTRACT_HASH
    if canonical_hash(result)!=R1_CONTRACT_HASH:
        raise ValueError(PROFILE+': historical contract changed')
    return result
