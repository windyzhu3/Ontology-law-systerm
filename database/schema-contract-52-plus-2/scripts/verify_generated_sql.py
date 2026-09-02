#!/usr/bin/env python3
"""Parse every generated migration and every PL/pgSQL function body."""

from __future__ import annotations

import re
import sys
from pathlib import Path

from pglast import parse_plpgsql, parse_sql


ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "generated" / "db" / "migration"
ROLE_PLACEHOLDERS = {
    "${app_command_role}": "law_app_command",
    "${app_worker_role}": "law_app_worker",
    "${app_query_role}": "law_app_query",
    "${audit_append_role}": "law_audit_append",
}
PLPGSQL_FUNCTION = re.compile(
    r"CREATE FUNCTION\s+[^\n]+.*?LANGUAGE plpgsql.*?AS\s+(\$[a-z_]*\$).*?\1;",
    re.IGNORECASE | re.DOTALL,
)


def normalized_sql(path: Path) -> str:
    sql = path.read_text(encoding="utf-8")
    for placeholder, identifier in ROLE_PLACEHOLDERS.items():
        sql = sql.replace(placeholder, identifier)
    return sql


def main() -> int:
    migrations = sorted(MIGRATIONS.glob("*.sql"))
    if len(migrations) != 20:
        print(f"expected 20 migrations, found {len(migrations)}", file=sys.stderr)
        return 1

    failures: list[str] = []
    function_count = 0
    for migration in migrations:
        sql = normalized_sql(migration)
        try:
            parse_sql(sql)
        except Exception as exc:  # pglast exposes parser-specific exception types.
            failures.append(f"{migration.name}: SQL: {exc}")
        for function in PLPGSQL_FUNCTION.finditer(sql):
            function_count += 1
            try:
                parse_plpgsql(function.group(0))
            except Exception as exc:
                heading = function.group(0).splitlines()[0]
                failures.append(f"{migration.name}: {heading}: {exc}")

    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print(
        f"parsed {len(migrations)} PostgreSQL migrations and "
        f"{function_count} PL/pgSQL functions"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
