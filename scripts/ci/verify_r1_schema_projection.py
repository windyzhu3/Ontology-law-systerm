"""Run the unchanged R1 runtime gate against its exact historical schema.

The current R2 source is validated in full before isolation. This is R1
projection verification only; the full R2 migrations are tested by Maven -Pit.
No source bytes, runtime contracts, or durable acceptance records are changed.
"""
import hashlib
import importlib
import json
from pathlib import Path
import shutil
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from scripts.baseline.r2_schema_successor_contract import historical_projection, canonical_hash, R1_CONTRACT_HASH, R1_FIELD_HASH
from scripts.baseline.verify_baseline import verify_r2_development_admission


def prepare_schema(repository, destination):
    if verify_r2_development_admission(repository):
        raise ValueError('Approved R2 development admission required')
    source = repository / 'database/schema-contract-52-plus-2'
    generated = source / 'generated'
    manifest = json.loads((generated / 'schema-contract-manifest.json').read_text(encoding='utf-8'))
    projected = historical_projection(generated, manifest)
    if projected.get('contractVersion') != '52-plus-2-v1.2' or canonical_hash(projected) != R1_CONTRACT_HASH:
        raise ValueError('Exact frozen R1 schema required')
    schema = destination / 'schema'
    shutil.copytree(source / 'runtime', schema / 'runtime', ignore=shutil.ignore_patterns('__pycache__'))
    (schema / 'generated/db/migration').mkdir(parents=True)
    for name, digest in projected['generatedArtifactSha256'].items():
        contents = (generated / name).read_bytes()
        if hashlib.sha256(contents).hexdigest() != digest:
            raise ValueError('Frozen R1 migration bytes changed')
        (schema / 'generated' / name).write_bytes(contents)
    sys.path.insert(0, str(source))
    try:
        from contract.schema_contract import BASE_SCHEMAS, EVOLUTIONS
        from contract.render import _render_markdown
        schemas = BASE_SCHEMAS
        for evolution in EVOLUTIONS:
            if evolution.version > 860:
                break
            schemas = evolution.apply(schemas)
        field = _render_markdown(schemas).encode('utf-8')
    finally:
        sys.path.pop(0)
    if hashlib.sha256(field).hexdigest() != R1_FIELD_HASH:
        raise ValueError('Frozen R1 field contract changed')
    (schema / 'generated/field-contract.md').write_bytes(field)
    (schema / 'generated/schema-contract-manifest.json').write_text(json.dumps(projected, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    return schema, projected


def main():
    source = ROOT / 'database/schema-contract-52-plus-2'
    sys.path.insert(0, str(source))
    runtime = importlib.import_module('runtime.verify_runtime')
    try:
        before = runtime.capture_repository_snapshot(ROOT)
        with tempfile.TemporaryDirectory(prefix='r1-schema-projection-') as directory:
            schema, manifest = prepare_schema(ROOT, Path(directory))
            result = runtime.run_runtime_verification(schema, ROOT / '.artifacts/schema-runtime', runs=2)
        if before != runtime.capture_repository_snapshot(ROOT):
            raise ValueError('Checkout changed during verification')
        summary = runtime.build_ci_runtime_summary(result, git_commit=before.head, manifest=manifest)
        output = ROOT / '.artifacts/schema-runtime-ci'
        runtime.export_ci_runtime_artifact(summary, output)
        runtime.validate_ci_runtime_artifact(output)
        print('R1 historical projection runtime: ' + summary['workflowOutcome'])
        print('R2 full migration verification: separate Maven -Pit gate; release acceptance remains NOT_GRANTED')
        return {'PASSED': 0, 'FAILED': 4, 'BLOCKED': 5}[summary['workflowOutcome']]
    except Exception:
        runtime._remove_failed_ci_artifact(ROOT)
        print('R1 schema projection verification failed', file=sys.stderr)
        return 4
    finally:
        sys.path.pop(0)


if __name__ == '__main__':
    raise SystemExit(main())
