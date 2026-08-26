from __future__ import annotations

import argparse
import filecmp
import tempfile
from pathlib import Path

from contract.render import generate_all


ROOT = Path(__file__).resolve().parent
GENERATED = ROOT / "generated"


def _snapshot(root: Path) -> dict[str, bytes]:
    return {
        str(path.relative_to(root)): path.read_bytes()
        for path in sorted(root.rglob("*"))
        if path.is_file()
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="生成或校验52＋2数据库合同")
    parser.add_argument("--check", action="store_true", help="仅检查generated目录是否与静态合同一致")
    args = parser.parse_args()

    if not args.check:
        generate_all(GENERATED)
        print(f"generated: {GENERATED}")
        return 0

    with tempfile.TemporaryDirectory() as directory:
        candidate = Path(directory)
        generate_all(candidate)
        expected = _snapshot(candidate)
        actual = _snapshot(GENERATED) if GENERATED.exists() else {}
        if expected != actual:
            missing = sorted(set(expected) - set(actual))
            extra = sorted(set(actual) - set(expected))
            changed = sorted(key for key in set(expected) & set(actual) if expected[key] != actual[key])
            print(f"schema drift: missing={missing}, extra={extra}, changed={changed}")
            return 1
    print("schema contract is deterministic and generated files are current")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
