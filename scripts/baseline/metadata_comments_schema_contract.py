"""Exact comments-only successor; never relax the frozen historical contract."""
import hashlib
import sys
from pathlib import Path

try:
    from scripts.baseline import configurable_roles_schema_contract as v21
    from scripts.baseline.r2_schema_successor_contract import canonical_hash
except ModuleNotFoundError:
    import configurable_roles_schema_contract as v21
    from r2_schema_successor_contract import canonical_hash

VERSION = '52-plus-2-r2-v22'
CONTRACT_HASH = '4d9be0583bc73d9fab24ef1f98f08a5059574cbc68923958299cd4da89fe237a'
FIELD_HASH = '54da0227b662aa7767ed4ebb2d8eddf7ef023fa651024a46259556db5a95cc32'
MIGRATION = 'db/migration/V1080__metadata_comments.sql'


def historical_projection(generated, manifest):
    if manifest.get('contractVersion') != VERSION or manifest.get('contractSha256') != CONTRACT_HASH or canonical_hash(manifest) != CONTRACT_HASH:
        raise ValueError('METADATA_COMMENTS_V1: exact reviewed manifest required')
    inventory = manifest['generatedArtifactSha256']
    actual = {'db/migration/' + path.name for path in (generated / 'db/migration').glob('*.sql')}
    if len(inventory) != 43 or set(inventory) != actual or MIGRATION not in inventory:
        raise ValueError('METADATA_COMMENTS_V1: exact V001-V1080 inventory required')
    for path, digest in inventory.items():
        if hashlib.sha256((generated / path).read_bytes()).hexdigest() != digest:
            raise ValueError('METADATA_COMMENTS_V1: migration bytes changed')
    if manifest.get('fieldContractSha256') != FIELD_HASH or hashlib.sha256((generated / 'field-contract.md').read_bytes()).hexdigest() != FIELD_HASH:
        raise ValueError('METADATA_COMMENTS_V1: field contract changed')
    source = Path(__file__).resolve().parents[2] / 'database/schema-contract-52-plus-2'
    sys.path.insert(0, str(source))
    try:
        from contract.schema_contract import BASE_SCHEMAS, EVOLUTIONS
        from contract.render import _manifest
        schemas = BASE_SCHEMAS
        for evolution in EVOLUTIONS:
            if evolution.version >= 1080:
                break
            schemas = evolution.apply(schemas)
        previous = _manifest(schemas, {key: digest for key, digest in inventory.items() if key != MIGRATION}, v21.FIELD_HASH, v21.VERSION)
        return v21.project_validated_v21(previous)
    finally:
        sys.path.pop(0)
