"""V850 append-only Lead ingress completion slot."""

from __future__ import annotations

from dataclasses import replace

from ..helpers import (
    check,
    code_col,
    digest_checks,
    digest_col,
    encrypted_col,
    entity_fk,
    time_col,
    uuid_col,
)
from ..model import ContractEvolution, Schema, Table


MIGRATION_NAME = "V850__lead_ingress_completion_slot.sql"
CONTRACT_VERSION = "52-plus-2-v1.1"

INGRESS_COMPLETION_COLUMNS = (
    encrypted_col(
        "ingress_completion_phone_ciphertext",
        "补全电话密文：仅在原始电话与邮箱均缺失时由完成接入命令一次写入；缺失时为空。",
        nullable=True,
    ),
    digest_col(
        "ingress_completion_phone_hmac",
        "补全电话HMAC：与补全电话密文配对的32字节受控精确匹配值；缺失时为空。",
        nullable=True,
    ),
    encrypted_col(
        "ingress_completion_email_ciphertext",
        "补全邮箱密文：仅在原始电话与邮箱均缺失时由完成接入命令一次写入；缺失时为空。",
        nullable=True,
    ),
    digest_col(
        "ingress_completion_email_hmac",
        "补全邮箱HMAC：与补全邮箱密文配对的32字节受控精确匹配值；缺失时为空。",
        nullable=True,
    ),
    code_col(
        "ingress_completion_source_code",
        "补全来源代码：标识静态注册的补全来源类型，不保存凭据或自由文本。",
        nullable=True,
    ),
    encrypted_col(
        "ingress_completion_source_summary_ciphertext",
        "补全来源说明密文：保存最小必要的受保护来源说明，不写入审计摘要或事件载荷。",
        nullable=True,
    ),
    uuid_col(
        "ingress_completed_by_appointment_id",
        "补全执行任命：指向同租户执行完成接入命令的准确Appointment。",
        nullable=True,
    ),
    time_col(
        "ingress_completed_at",
        "补全完成时间：完成接入命令写入整槽的带时区微秒精度时间。",
        nullable=True,
    ),
    digest_col(
        "ingress_completion_digest",
        "补全完成摘要：覆盖规范化补全值、来源、执行任命与完成时间的32字节摘要。",
        nullable=True,
    ),
)
INGRESS_COMPLETION_COLUMN_NAMES = tuple(
    column.name for column in INGRESS_COMPLETION_COLUMNS
)
INGRESS_COMPLETION_SLOT_EXPRESSION = (
    "(ingress_completion_phone_ciphertext IS NULL AND "
    "ingress_completion_phone_hmac IS NULL AND "
    "ingress_completion_email_ciphertext IS NULL AND "
    "ingress_completion_email_hmac IS NULL AND "
    "ingress_completion_source_code IS NULL AND "
    "ingress_completion_source_summary_ciphertext IS NULL AND "
    "ingress_completed_by_appointment_id IS NULL AND "
    "ingress_completed_at IS NULL AND ingress_completion_digest IS NULL) OR "
    "(captured_phone_ciphertext IS NULL AND captured_phone_hmac IS NULL AND "
    "captured_email_ciphertext IS NULL AND captured_email_hmac IS NULL AND "
    "(ingress_completion_phone_ciphertext IS NOT NULL OR "
    "ingress_completion_email_ciphertext IS NOT NULL) AND "
    "ingress_completion_source_code IS NOT NULL AND "
    "ingress_completion_source_summary_ciphertext IS NOT NULL AND "
    "ingress_completed_by_appointment_id IS NOT NULL AND "
    "ingress_completed_at IS NOT NULL AND ingress_completion_digest IS NOT NULL)"
)
INGRESS_COMPLETION_CONSTRAINTS = (
    check(
        "ck_lead__ingress_completion_phone_pair",
        "(ingress_completion_phone_ciphertext IS NULL AND ingress_completion_phone_hmac IS NULL) OR "
        "(ingress_completion_phone_ciphertext IS NOT NULL AND ingress_completion_phone_hmac IS NOT NULL)",
        "补全电话配对：电话密文与HMAC必须同时存在或同时为空。",
    ),
    check(
        "ck_lead__ingress_completion_email_pair",
        "(ingress_completion_email_ciphertext IS NULL AND ingress_completion_email_hmac IS NULL) OR "
        "(ingress_completion_email_ciphertext IS NOT NULL AND ingress_completion_email_hmac IS NOT NULL)",
        "补全邮箱配对：邮箱密文与HMAC必须同时存在或同时为空。",
    ),
    check(
        "ck_lead__ingress_completion_slot",
        INGRESS_COMPLETION_SLOT_EXPRESSION,
        "补全槽完整性：整槽必须全空，或在原始电话与邮箱均缺失时一次写入至少一组联系方式及全部来源元数据。",
    ),
    *digest_checks("lead", INGRESS_COMPLETION_COLUMNS),
)
INGRESS_COMPLETION_FOREIGN_KEY = entity_fk(
    "lead",
    "ingress_completed_by_appointment_id",
    "identity",
    "appointment",
    "appointment_id",
    "补全执行任命关系：完成接入的Appointment必须存在于同一租户。",
    suffix="ingress_completed_by_appointment",
)


def _lead_table(schemas: tuple[Schema, ...]) -> Table:
    matches = [
        table
        for schema in schemas
        for table in schema.tables
        if schema.name == "lead" and table.name == "lead"
    ]
    if len(matches) != 1:
        raise ValueError("V850 requires exactly one lead.lead table")
    return matches[0]


def apply_evolution(schemas: tuple[Schema, ...]) -> tuple[Schema, ...]:
    lead = _lead_table(schemas)
    existing_columns = {column.name for column in lead.columns}
    duplicates = existing_columns.intersection(INGRESS_COMPLETION_COLUMN_NAMES)
    if duplicates:
        raise ValueError(f"V850 columns already exist: {sorted(duplicates)}")

    evolved_lead = replace(
        lead,
        columns=(*lead.columns, *INGRESS_COMPLETION_COLUMNS),
        constraints=(*lead.constraints, *INGRESS_COMPLETION_CONSTRAINTS),
        foreign_keys=(*lead.foreign_keys, INGRESS_COMPLETION_FOREIGN_KEY),
        mutable_columns=(*lead.mutable_columns, *INGRESS_COMPLETION_COLUMN_NAMES),
        write_once_columns=(*lead.write_once_columns, *INGRESS_COMPLETION_COLUMN_NAMES),
    )
    return tuple(
        replace(
            schema,
            tables=tuple(
                evolved_lead if table.name == "lead" else table
                for table in schema.tables
            ),
        )
        if schema.name == "lead"
        else schema
        for schema in schemas
    )


def _sql_comment(value: str) -> str:
    return value.replace("'", "''")


def _render_sql(
    base_schemas: tuple[Schema, ...], evolved_schemas: tuple[Schema, ...]
) -> str:
    base_lead = _lead_table(base_schemas)
    lead = _lead_table(evolved_schemas)
    base_column_names = {column.name for column in base_lead.columns}
    new_columns = tuple(
        column for column in lead.columns if column.name not in base_column_names
    )
    if tuple(column.name for column in new_columns) != INGRESS_COMPLETION_COLUMN_NAMES:
        raise ValueError("V850 current model differs from its frozen column delta")

    base_constraint_names = {constraint.name for constraint in base_lead.constraints}
    new_constraints = tuple(
        constraint
        for constraint in lead.constraints
        if constraint.name not in base_constraint_names
    )
    base_foreign_key_names = {foreign_key.name for foreign_key in base_lead.foreign_keys}
    new_foreign_keys = tuple(
        foreign_key
        for foreign_key in lead.foreign_keys
        if foreign_key.name not in base_foreign_key_names
    )
    if new_constraints != INGRESS_COMPLETION_CONSTRAINTS or new_foreign_keys != (
        INGRESS_COMPLETION_FOREIGN_KEY,
    ):
        raise ValueError("V850 current model differs from its frozen constraint delta")

    lines = [
        "-- V850：只向冻结的v1合同追加Lead接入补全槽；V001至V840禁止改写。",
        "",
        "ALTER TABLE lead.lead",
    ]
    for index, column in enumerate(new_columns):
        suffix = ";" if index == len(new_columns) - 1 else ","
        lines.append(f"    ADD COLUMN {column.name} {column.sql_type}{suffix}")
    lines.append("")
    for column in new_columns:
        lines.append(
            f"COMMENT ON COLUMN lead.lead.{column.name} IS "
            f"'{_sql_comment(column.comment)}';"
        )

    for constraint in new_constraints:
        lines.extend(
            (
                "",
                "ALTER TABLE lead.lead",
                f"    ADD CONSTRAINT {constraint.name} CHECK ({constraint.expression});",
                f"COMMENT ON CONSTRAINT {constraint.name} ON lead.lead IS "
                f"'{_sql_comment(constraint.comment)}';",
            )
        )
    foreign_key = new_foreign_keys[0]
    lines.extend(
        (
            "",
            "ALTER TABLE lead.lead",
            f"    ADD CONSTRAINT {foreign_key.name}",
            f"    FOREIGN KEY ({', '.join(foreign_key.columns)})",
            f"    REFERENCES {foreign_key.parent_schema}.{foreign_key.parent_table} "
            f"({', '.join(foreign_key.parent_columns)})",
            f"    ON UPDATE {foreign_key.on_update}",
            f"    ON DELETE {foreign_key.on_delete};",
            f"COMMENT ON CONSTRAINT {foreign_key.name} ON lead.lead IS "
            f"'{_sql_comment(foreign_key.comment)}';",
            "",
            "CREATE FUNCTION platform_meta.fn_guard_lead_ingress_completion_slot()",
            "RETURNS trigger",
            "LANGUAGE plpgsql",
            "SET search_path = pg_catalog",
            "AS $lead_ingress_completion_slot$",
            "BEGIN",
            "    IF OLD.ingress_completion_digest IS NOT NULL",
            "       AND ROW(",
            "           OLD.ingress_completion_phone_ciphertext,",
            "           OLD.ingress_completion_phone_hmac,",
            "           OLD.ingress_completion_email_ciphertext,",
            "           OLD.ingress_completion_email_hmac,",
            "           OLD.ingress_completion_source_code,",
            "           OLD.ingress_completion_source_summary_ciphertext,",
            "           OLD.ingress_completed_by_appointment_id,",
            "           OLD.ingress_completed_at,",
            "           OLD.ingress_completion_digest",
            "       ) IS DISTINCT FROM ROW(",
            "           NEW.ingress_completion_phone_ciphertext,",
            "           NEW.ingress_completion_phone_hmac,",
            "           NEW.ingress_completion_email_ciphertext,",
            "           NEW.ingress_completion_email_hmac,",
            "           NEW.ingress_completion_source_code,",
            "           NEW.ingress_completion_source_summary_ciphertext,",
            "           NEW.ingress_completed_by_appointment_id,",
            "           NEW.ingress_completed_at,",
            "           NEW.ingress_completion_digest",
            "       ) THEN",
            "        RAISE EXCEPTION 'ingress completion slot is already sealed on %.%',",
            "            TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '55000';",
            "    END IF;",
            "    RETURN NEW;",
            "END;",
            "$lead_ingress_completion_slot$;",
            "COMMENT ON FUNCTION platform_meta.fn_guard_lead_ingress_completion_slot() IS",
            "    'Lead补全槽封存守卫：整槽首次完成后，拒绝覆盖、清空或补写先前为空的联系方式。';",
            "",
            "CREATE TRIGGER trg_lead__ingress_completion_slot",
            "BEFORE UPDATE ON lead.lead",
            "FOR EACH ROW",
            "EXECUTE FUNCTION platform_meta.fn_guard_lead_ingress_completion_slot();",
            "COMMENT ON TRIGGER trg_lead__ingress_completion_slot ON lead.lead IS",
            "    'Lead补全槽封存保护：email-only或phone-only完成后也不得二次补写另一组联系方式。';",
            "REVOKE ALL ON FUNCTION platform_meta.fn_guard_lead_ingress_completion_slot()",
            "    FROM PUBLIC, ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role};",
            "",
            "DROP TRIGGER trg_lead__mutation_guard ON lead.lead;",
            "CREATE TRIGGER trg_lead__mutation_guard",
            "BEFORE UPDATE OR DELETE ON lead.lead",
            "FOR EACH ROW",
            "EXECUTE FUNCTION platform_meta.fn_guard_controlled_update(",
            f"    '{_sql_comment(','.join(lead.mutable_columns))}',",
            f"    '{_sql_comment(','.join(lead.write_once_columns))}', '', '', 'CONTROLLED'",
            ");",
            "COMMENT ON TRIGGER trg_lead__mutation_guard ON lead.lead IS",
            "    '受控更新保护：补全槽只允许从全空一次写入完整值，之后禁止覆盖或清空；删除始终拒绝。';",
            "",
            "DROP TRIGGER trg_lead__initial_unassigned ON lead.lead;",
            "CREATE TRIGGER trg_lead__initial_unassigned",
            "BEFORE INSERT ON lead.lead",
            "FOR EACH ROW",
            "EXECUTE FUNCTION platform_meta.fn_guard_initial_nulls(",
            f"    '{_sql_comment(','.join(('current_assignment_id', *INGRESS_COMPLETION_COLUMN_NAMES)))}'",
            ");",
            "COMMENT ON TRIGGER trg_lead__initial_unassigned ON lead.lead IS",
            "    'Lead创建时不得预填当前分派或补全槽；补全只接受后续准确Task命令的一次写入。';",
            "",
            "GRANT UPDATE (",
        )
    )
    for index, column_name in enumerate(INGRESS_COMPLETION_COLUMN_NAMES):
        suffix = "" if index == len(INGRESS_COMPLETION_COLUMN_NAMES) - 1 else ","
        lines.append(f"    {column_name}{suffix}")
    lines.extend(
        (
            ") ON lead.lead TO ${app_command_role};",
            "",
            "REVOKE SELECT ON lead.lead FROM ${app_query_role};",
            "GRANT SELECT (",
        )
    )
    for index, column in enumerate(base_lead.columns):
        suffix = "" if index == len(base_lead.columns) - 1 else ","
        lines.append(f"    {column.name}{suffix}")
    lines.extend(
        (
            ") ON lead.lead TO ${app_query_role};",
            "",
            "DO $v850_contract_version$",
            "BEGIN",
            "    UPDATE platform_meta.deployment_state",
            "    SET schema_contract_version = '52-plus-2-v1.1',",
            "        revision = revision + 1,",
            "        changed_at = clock_timestamp()",
            "    WHERE deployment_state_key = 'PRIMARY'",
            "      AND schema_contract_version = '52-plus-2-v1';",
            "    IF NOT FOUND THEN",
            "        RAISE EXCEPTION 'V850 requires deployment_state at schema contract 52-plus-2-v1'",
            "            USING ERRCODE = '55000';",
            "    END IF;",
            "END;",
            "$v850_contract_version$;",
            "",
            "DO $v850_validation$",
            "DECLARE",
            "    actual_count bigint;",
            "    completion_column text;",
            "BEGIN",
            "    SELECT count(*) INTO actual_count",
            "    FROM pg_catalog.pg_class relation",
            "    JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace",
            "    WHERE relation.relkind IN ('r', 'p')",
            "      AND namespace.nspname IN (",
            "          'identity', 'audit', 'responsibility', 'execution',",
            "          'external_action', 'evidence', 'party', 'lead',",
            "          'opportunity', 'conflict', 'contract', 'transfer', 'platform_meta'",
            "      );",
            "    IF actual_count <> 54 THEN",
            "        RAISE EXCEPTION 'V850 expected 54 managed tables, found %', actual_count;",
            "    END IF;",
            "",
            "    SELECT count(*) INTO actual_count",
            "    FROM pg_catalog.pg_constraint constraint_row",
            "    JOIN pg_catalog.pg_class relation ON relation.oid = constraint_row.conrelid",
            "    JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace",
            "    WHERE constraint_row.contype = 'f'",
            "      AND namespace.nspname IN (",
            "          'identity', 'audit', 'responsibility', 'execution',",
            "          'external_action', 'evidence', 'party', 'lead',",
            "          'opportunity', 'conflict', 'contract', 'transfer'",
            "      );",
            "    IF actual_count <> 207 THEN",
            "        RAISE EXCEPTION 'V850 expected 207 tenant-safe foreign keys, found %', actual_count;",
            "    END IF;",
            "",
            "    SELECT count(*) INTO actual_count",
            "    FROM pg_catalog.pg_trigger trigger_row",
            "    JOIN pg_catalog.pg_class relation ON relation.oid = trigger_row.tgrelid",
            "    JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace",
            "    WHERE NOT trigger_row.tgisinternal",
            "      AND trigger_row.tgname ~ '^trg_[a-z0-9_]+__mutation_guard$'",
            "      AND namespace.nspname IN (",
            "          'identity', 'audit', 'responsibility', 'execution',",
            "          'external_action', 'evidence', 'party', 'lead',",
            "          'opportunity', 'conflict', 'contract', 'transfer', 'platform_meta'",
            "      );",
            "    IF actual_count <> 53 THEN",
            "        RAISE EXCEPTION 'V850 expected 53 mutation guards, found %', actual_count;",
            "    END IF;",
            "",
            "    FOREACH completion_column IN ARRAY ARRAY[",
        )
    )
    for index, column_name in enumerate(INGRESS_COMPLETION_COLUMN_NAMES):
        suffix = "" if index == len(INGRESS_COMPLETION_COLUMN_NAMES) - 1 else ","
        lines.append(f"        '{column_name}'{suffix}")
    lines.extend(
        (
            "    ]::text[] LOOP",
            "        IF NOT pg_catalog.has_column_privilege(",
            "            '${app_command_role}', 'lead.lead', completion_column, 'UPDATE'",
            "        ) THEN",
            "            RAISE EXCEPTION 'V850 command role lacks UPDATE on lead.lead.%', completion_column;",
            "        END IF;",
            "        IF pg_catalog.has_column_privilege(",
            "            '${app_worker_role}', 'lead.lead', completion_column, 'UPDATE'",
            "        ) OR pg_catalog.has_column_privilege(",
            "            '${app_query_role}', 'lead.lead', completion_column, 'UPDATE'",
            "        ) OR pg_catalog.has_column_privilege(",
            "            '${app_query_role}', 'lead.lead', completion_column, 'SELECT'",
            "        ) OR pg_catalog.has_column_privilege(",
            "            '${audit_append_role}', 'lead.lead', completion_column, 'UPDATE'",
            "        ) THEN",
            "            RAISE EXCEPTION 'V850 expanded a non-command role on lead.lead.%', completion_column;",
            "        END IF;",
            "    END LOOP;",
            "END;",
            "$v850_validation$;",
        )
    )
    return "\n".join(lines).rstrip() + "\n"


EVOLUTION = ContractEvolution(
    version=850,
    migration_name=MIGRATION_NAME,
    contract_version=CONTRACT_VERSION,
    apply=apply_evolution,
    render_sql=_render_sql,
)

__all__ = ("EVOLUTION",)
