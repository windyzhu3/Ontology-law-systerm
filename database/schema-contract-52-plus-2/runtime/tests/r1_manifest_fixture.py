"""Independent frozen R1 runtime tests use the exact validated historical profile."""
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT))
from scripts.baseline.r2_schema_successor_contract import historical_projection, canonical_hash, R1_CONTRACT_HASH


def r1_manifest():
    generated = ROOT / 'database/schema-contract-52-plus-2/generated'
    current = json.loads((generated / 'schema-contract-manifest.json').read_text(encoding='utf-8'))
    projected = historical_projection(generated, current)
    if canonical_hash(projected) != R1_CONTRACT_HASH:
        raise ValueError('Frozen R1 fixture changed')
    return projected
