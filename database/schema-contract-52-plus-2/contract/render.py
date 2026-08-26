from __future__ import annotations

import json
import hashlib
import re
from pathlib import Path
from typing import Iterable, Sequence

from .model import Column, Constraint, ForeignKey, Index, Schema, Table


IDENTIFIER = re.compile(r"^[a-z][a-z0-9_]*$")
DOMAIN_MIGRATIONS = {
    "identity": "V010__identity_tables.sql",
    "audit": "V020__audit_tables.sql",
    "responsibility": "V030__responsibility_tables.sql",
    "execution": "V040__execution_tables.sql",
    "external_action": "V050__external_action_tables.sql",
    "evidence": "V060__evidence_tables.sql",
    "party": "V070__party_tables.sql",
    "lead": "V080__lead_tables.sql",
    "opportunity": "V090__opportunity_tables.sql",
    "conflict": "V100__conflict_tables.sql",
    "contract": "V110__contract_tables.sql",
    "transfer": "V120__transfer_tables.sql",
}

DOMAIN_OWNERS = {
    "identity": "IdentityRuntime",
    "audit": "AuditAppender",
    "responsibility": "ResponsibilityRuntime",
    "execution": "CommandRuntime",
    "external_action": "ExternalActionRuntime",
    "evidence": "EvidenceRuntime",
    "party": "PartyRuntime",
    "lead": "LeadRuntime",
    "opportunity": "OpportunityRuntime",
    "conflict": "ConflictReviewRuntime",
    "contract": "ContractRuntime",
    "transfer": "TransferRuntime",
    "platform_meta": "DeploymentRuntime",
}

TABLE_OWNER_OVERRIDES = {
    "execution.domain_event_outbox": "OutboxDispatcher",
    "external_action.external_action_outbox": "ExternalActionDispatcher",
    "external_action.provider_inbox": "ProviderIngress",
    "evidence.upload_session": "EvidenceIngress",
    "evidence.received_source_object": "EvidenceIngress",
}


def _table_owner(schema_name: str, table_name: str) -> str:
    return TABLE_OWNER_OVERRIDES.get(
        f"{schema_name}.{table_name}", DOMAIN_OWNERS[schema_name]
    )

# 只有“指针目标必须属于本锚点”这种稳定、单表可查的跨行关系进入延迟守卫。
# 更复杂的业务有效性仍由各Fact Owner和CommandRuntime在提交前重验。
OWNED_POINTER_GUARDS = (
    ("lead", "lead", "current_assignment_id", "lead_id", "lead", "lead_assignment", "lead_assignment_id", "lead_id", "ctrg_lead__current_assignment_owner", "当前Assignment必须属于同一Lead。"),
    ("opportunity", "opportunity", "current_quote_revision_id", "opportunity_id", "opportunity", "quote_revision", "quote_revision_id", "opportunity_id", "ctrg_opportunity__current_quote_owner", "当前QuoteRevision必须属于同一Opportunity。"),
    ("contract", "contract", "current_revision_id", "contract_id", "contract", "contract_revision", "contract_revision_id", "contract_id", "ctrg_contract__current_revision_owner", "当前合同版本必须属于同一Contract。"),
    ("contract", "contract", "approved_revision_id", "contract_id", "contract", "contract_revision", "contract_revision_id", "contract_id", "ctrg_contract__approved_revision_owner", "批准合同版本必须属于同一Contract。"),
    ("contract", "contract", "contract_execution_id", "contract_id", "contract", "contract_execution", "contract_execution_id", "contract_id", "ctrg_contract__execution_owner", "合同执行事实必须属于同一Contract。"),
    ("contract", "contract", "contract_termination_id", "contract_id", "contract", "contract_termination", "contract_termination_id", "contract_id", "ctrg_contract__termination_owner", "合同终止事实必须属于同一Contract。"),
    ("transfer", "transfer_request", "accepted_snapshot_id", "transfer_request_id", "transfer", "transfer_snapshot", "transfer_snapshot_id", "transfer_request_id", "ctrg_transfer_request__accepted_snapshot_owner", "接收Snapshot必须属于同一TransferRequest。"),
)

INITIAL_NULL_GUARDS = (
    (
        "evidence", "evidence_binding",
        ("revoked_at", "revoked_by_appointment_id", "revocation_authorization_digest", "revocation_reason_code"),
        "trg_evidence_binding__initial_active",
        "Binding创建时必须有效，撤回槽只能由后续授权命令一次写入。",
    ),
    (
        "lead", "lead",
        ("current_assignment_id",),
        "trg_lead__initial_unassigned",
        "Lead接入锚点创建时不得预填当前分派；首次Assignment必须在后续同一短事务追加并受控回填指针。",
    ),
    (
        "opportunity", "opportunity",
        ("current_quote_revision_id", "close_outcome_code", "closed_at"),
        "trg_opportunity__initial_open",
        "Opportunity由CONNECTED_VALID结果创建时不得预填报价或关闭槽。",
    ),
    (
        "conflict", "conflict_review",
        ("resolution_code", "resolution_digest", "resolved_at"),
        "trg_conflict_review__initial_unresolved",
        "ConflictReview封存初始结论时不得预填后续WAIVED或BLOCKED解决槽。",
    ),
    (
        "contract", "contract_signature",
        ("revoked_at", "revoked_by_appointment_id", "revocation_authorization_digest", "revocation_reason_code"),
        "trg_contract_signature__initial_active",
        "ContractSignature追加时必须有效，撤回只能由执行前后续授权命令单向写入。",
    ),
)


def _contract_schemas() -> tuple[Schema, ...]:
    from .schema_contract import SCHEMAS
    return SCHEMAS


def _sql_comment(value: str) -> str:
    return value.replace("'", "''")


def _validate_identifier(value: str) -> None:
    if not IDENTIFIER.fullmatch(value) or len(value.encode("utf-8")) > 63:
        raise ValueError(f"非法或过长的PostgreSQL标识符: {value}")


def _validate_contract(schemas: Sequence[Schema]) -> None:
    seen_tables: set[tuple[str, str]] = set()
    for schema in schemas:
        _validate_identifier(schema.name)
        for table in schema.tables:
            _validate_identifier(table.name)
            key = (schema.name, table.name)
            if key in seen_tables:
                raise ValueError(f"重复表: {schema.name}.{table.name}")
            seen_tables.add(key)
            names = [column.name for column in table.columns]
            if len(names) != len(set(names)):
                raise ValueError(f"重复字段: {schema.name}.{table.name}")
            for name in names:
                _validate_identifier(name)
            object_names = [f"pk_{table.name}"]
            object_names += [item.name for item in table.constraints]
            object_names += [item.name for item in table.foreign_keys]
            object_names += [item.name for item in table.indexes]
            for name in object_names:
                _validate_identifier(name)


def _column_sql(column: Column) -> str:
    parts = [column.name, column.sql_type]
    if column.default is not None:
        parts.extend(("DEFAULT", column.default))
    if not column.nullable:
        parts.append("NOT NULL")
    return " ".join(parts)


def _constraint_sql(constraint: Constraint) -> str:
    if constraint.kind == "CHECK":
        return f"CONSTRAINT {constraint.name} CHECK ({constraint.expression})"
    if constraint.kind == "UNIQUE":
        return f"CONSTRAINT {constraint.name} UNIQUE ({constraint.expression})"
    raise ValueError(f"不支持的约束类型: {constraint.kind}")


def _render_table(table: Table) -> str:
    qualified = f"{table.schema}.{table.name}"
    owner = _table_owner(table.schema, table.name)
    definitions = [_column_sql(column) for column in table.columns]
    definitions.append(f"CONSTRAINT pk_{table.name} PRIMARY KEY ({', '.join(table.primary_key)})")
    definitions.extend(_constraint_sql(constraint) for constraint in table.constraints)
    body = ",\n".join(f"    {definition}" for definition in definitions)
    lines = [f"CREATE TABLE {qualified} (\n{body}\n);", ""]
    lines.append(
        f"COMMENT ON TABLE {qualified} IS "
        f"'Fact Owner：{_sql_comment(owner)}；{_sql_comment(table.comment)}';"
    )
    lines.append(
        f"COMMENT ON CONSTRAINT pk_{table.name} ON {qualified} IS "
        f"'{_sql_comment(table.primary_key_comment)}';"
    )
    lines.append(
        f"COMMENT ON INDEX {table.schema}.pk_{table.name} IS "
        f"'{_sql_comment(table.primary_key_comment)}';"
    )
    for column in table.columns:
        lines.append(
            f"COMMENT ON COLUMN {qualified}.{column.name} IS '{_sql_comment(column.comment)}';"
        )
    for constraint in table.constraints:
        lines.append(
            f"COMMENT ON CONSTRAINT {constraint.name} ON {qualified} IS "
            f"'{_sql_comment(constraint.comment)}';"
        )
        if constraint.kind == "UNIQUE":
            lines.append(
                f"COMMENT ON INDEX {table.schema}.{constraint.name} IS "
                f"'{_sql_comment(constraint.comment)}';"
            )
    return "\n".join(lines)


def _render_bootstrap(schemas: Sequence[Schema]) -> str:
    lines = ["-- 由静态52＋2字段合同生成；禁止手工编辑。", ""]
    for schema in schemas:
        lines.append(f"CREATE SCHEMA IF NOT EXISTS {schema.name};")
        lines.append(f"COMMENT ON SCHEMA {schema.name} IS '{_sql_comment(schema.comment)}';")
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def _render_deployment_state(schema: Schema) -> str:
    table = schema.tables[0]
    lines = ["-- Flyway已在执行迁移前创建platform_meta.flyway_schema_history。", ""]
    lines.append(_render_table(table))
    lines.extend((
        "",
        "INSERT INTO platform_meta.deployment_state (",
        "    deployment_state_key, operating_mode, active_release_digest,",
        "    active_manifest_hash, schema_contract_version, revision, changed_at",
        ") VALUES (",
        "    'PRIMARY', 'BLOCKED', decode(repeat('00', 32), 'hex'),",
        "    decode(repeat('00', 32), 'hex'), '52-plus-2-v1', 0, clock_timestamp()",
        ");",
        "",
        "COMMENT ON TABLE platform_meta.flyway_schema_history IS",
        "    'Flyway迁移历史：由固定版本Flyway独占创建和维护，应用不得写入。';",
        "COMMENT ON COLUMN platform_meta.flyway_schema_history.installed_rank IS 'Flyway安装顺序号：由Flyway维护。';",
        "COMMENT ON COLUMN platform_meta.flyway_schema_history.version IS 'Flyway版本号：可重复迁移时可为空。';",
        "COMMENT ON COLUMN platform_meta.flyway_schema_history.description IS 'Flyway迁移说明：来自迁移文件名称。';",
        "COMMENT ON COLUMN platform_meta.flyway_schema_history.type IS 'Flyway迁移类型：由Flyway维护。';",
        "COMMENT ON COLUMN platform_meta.flyway_schema_history.script IS 'Flyway脚本名称：由Flyway维护。';",
        "COMMENT ON COLUMN platform_meta.flyway_schema_history.checksum IS 'Flyway校验和：用于识别已执行迁移漂移。';",
        "COMMENT ON COLUMN platform_meta.flyway_schema_history.installed_by IS 'Flyway执行主体：执行本次迁移的数据库用户。';",
        "COMMENT ON COLUMN platform_meta.flyway_schema_history.installed_on IS 'Flyway安装时间：迁移历史写入时间。';",
        "COMMENT ON COLUMN platform_meta.flyway_schema_history.execution_time IS 'Flyway执行耗时：以毫秒表示。';",
        "COMMENT ON COLUMN platform_meta.flyway_schema_history.success IS 'Flyway执行结果：表示该迁移是否成功。';",
    ))
    return "\n".join(lines).rstrip() + "\n"


def _render_domain(schema: Schema) -> str:
    lines = [f"-- {schema.comment}", ""]
    for table in schema.tables:
        lines.append(_render_table(table))
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def _render_foreign_key(table: Table, fk: ForeignKey) -> str:
    qualified = f"{table.schema}.{table.name}"
    deferrability = ""
    if fk.deferrable:
        deferrability = " DEFERRABLE INITIALLY DEFERRED" if fk.initially_deferred else " DEFERRABLE INITIALLY IMMEDIATE"
    return "\n".join((
        f"ALTER TABLE {qualified}",
        f"    ADD CONSTRAINT {fk.name}",
        f"    FOREIGN KEY ({', '.join(fk.columns)})",
        f"    REFERENCES {fk.parent_schema}.{fk.parent_table} ({', '.join(fk.parent_columns)})",
        f"    ON UPDATE {fk.on_update}",
        f"    ON DELETE {fk.on_delete}{deferrability};",
        f"COMMENT ON CONSTRAINT {fk.name} ON {qualified} IS '{_sql_comment(fk.comment)}';",
    ))


def _render_foreign_keys(schemas: Sequence[Schema]) -> str:
    lines = ["-- 所有物理关系均来自冻结白名单；多态准确引用不在此伪造外键。", ""]
    for schema in schemas:
        for table in schema.tables:
            for fk in table.foreign_keys:
                lines.append(_render_foreign_key(table, fk))
                lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def _render_update_guards(schemas: Sequence[Schema]) -> str:
    lines = [
        "-- 不可变事实与受控CAS更新守卫。",
        "",
        "CREATE FUNCTION platform_meta.fn_reject_fact_mutation()",
        "RETURNS trigger",
        "LANGUAGE plpgsql",
        "SET search_path = pg_catalog",
        "AS $$",
        "BEGIN",
        "    RAISE EXCEPTION 'immutable fact %.% rejects %', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP",
        "        USING ERRCODE = '55000';",
        "END;",
        "$$;",
        "COMMENT ON FUNCTION platform_meta.fn_reject_fact_mutation() IS",
        "    '不可变事实守卫：拒绝对已追加事实执行UPDATE或DELETE。';",
        "",
        "CREATE FUNCTION platform_meta.fn_guard_controlled_update()",
        "RETURNS trigger",
        "LANGUAGE plpgsql",
        "SET search_path = pg_catalog",
        "AS $$",
        "DECLARE",
        "    allowed_columns text[] := string_to_array(TG_ARGV[0], ',');",
        "    once_columns text[] := CASE WHEN TG_ARGV[1] = '' THEN ARRAY[]::text[] ELSE string_to_array(TG_ARGV[1], ',') END;",
        "    state_column text := TG_ARGV[2];",
        "    allowed_transitions text[] := CASE WHEN TG_ARGV[3] = '' THEN ARRAY[]::text[] ELSE string_to_array(TG_ARGV[3], ',') END;",
        "    queue_mode boolean := TG_ARGV[4] = 'QUEUE';",
        "    old_row jsonb := to_jsonb(OLD);",
        "    new_row jsonb := to_jsonb(NEW);",
        "    changed_column text;",
        "    once_column text;",
        "    transition text;",
        "    semantic_change boolean := false;",
        "    state_has_exit boolean;",
        "BEGIN",
        "    IF TG_OP = 'DELETE' THEN",
        "        RAISE EXCEPTION 'controlled table %.% rejects DELETE', TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '55000';",
        "    END IF;",
        "",
        "    FOR changed_column IN",
        "        SELECT changed.key",
        "        FROM pg_catalog.jsonb_object_keys(old_row || new_row) AS changed(key)",
        "        WHERE old_row -> changed.key IS DISTINCT FROM new_row -> changed.key",
        "    LOOP",
        "        IF NOT changed_column = ANY(allowed_columns) THEN",
        "            RAISE EXCEPTION 'column % is immutable on %.%', changed_column, TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '55000';",
        "        END IF;",
        "        IF changed_column NOT IN ('revision', 'changed_at', 'last_edited_at') THEN",
        "            semantic_change := true;",
        "        END IF;",
        "    END LOOP;",
        "",
        "    IF NOT semantic_change THEN",
        "        RAISE EXCEPTION 'controlled update requires a semantic column change on %.%', TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '55000';",
        "    END IF;",
        "",
        "    IF (new_row ->> 'revision')::bigint <> (old_row ->> 'revision')::bigint + 1 THEN",
        "        RAISE EXCEPTION 'revision must increment exactly once on %.%', TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '40001';",
        "    END IF;",
        "",
        "    FOREACH once_column IN ARRAY once_columns LOOP",
        "        IF old_row -> once_column <> 'null'::jsonb AND old_row -> once_column IS DISTINCT FROM new_row -> once_column THEN",
        "            RAISE EXCEPTION 'write-once column % cannot change on %.%', once_column, TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '55000';",
        "        END IF;",
        "    END LOOP;",
        "",
        "    IF state_column <> '' AND old_row -> state_column IS DISTINCT FROM new_row -> state_column THEN",
        "        transition := (old_row ->> state_column) || '>' || (new_row ->> state_column);",
        "        IF NOT transition = ANY(allowed_transitions) THEN",
        "            RAISE EXCEPTION 'transition % is forbidden on %.%', transition, TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '55000';",
        "        END IF;",
        "    END IF;",
        "",
        "    IF state_column <> '' AND old_row -> state_column IS NOT DISTINCT FROM new_row -> state_column THEN",
        "        SELECT EXISTS (",
        "            SELECT 1 FROM pg_catalog.unnest(allowed_transitions) AS candidate(transition_text)",
        "            WHERE candidate.transition_text LIKE (old_row ->> state_column) || '>%'",
        "        ) INTO state_has_exit;",
        "        IF NOT state_has_exit THEN",
        "            RAISE EXCEPTION 'terminal state % rejects further updates on %.%', old_row ->> state_column, TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '55000';",
        "        END IF;",
        "    END IF;",
        "",
        "    IF queue_mode THEN",
        "        IF old_row ->> state_column = 'EXHAUSTED'",
        "           AND new_row ->> state_column = 'EXHAUSTED' THEN",
        "            RAISE EXCEPTION 'exhausted queue row rejects in-place mutation without authorized redrive on %.%', TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '55000';",
        "        END IF;",
        "        IF (new_row ->> 'fencing_token')::bigint < (old_row ->> 'fencing_token')::bigint",
        "           OR (new_row ->> 'attempt_count')::bigint < (old_row ->> 'attempt_count')::bigint THEN",
        "            RAISE EXCEPTION 'queue counters cannot decrease on %.%', TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '55000';",
        "        END IF;",
        "        IF old_row ->> state_column <> 'CLAIMED' AND new_row ->> state_column = 'CLAIMED'",
        "           AND ((new_row ->> 'fencing_token')::bigint <> (old_row ->> 'fencing_token')::bigint + 1",
        "             OR (new_row ->> 'attempt_count')::bigint <> (old_row ->> 'attempt_count')::bigint + 1) THEN",
        "            RAISE EXCEPTION 'queue fencing_token must increment on claim together with attempt_count on %.%', TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '40001';",
        "        END IF;",
        "        IF NOT (old_row ->> state_column <> 'CLAIMED' AND new_row ->> state_column = 'CLAIMED')",
        "           AND ((new_row ->> 'fencing_token')::bigint <> (old_row ->> 'fencing_token')::bigint",
        "             OR (new_row ->> 'attempt_count')::bigint <> (old_row ->> 'attempt_count')::bigint) THEN",
        "            RAISE EXCEPTION 'queue counters can change only on claim on %.%', TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '55000';",
        "        END IF;",
        "        IF old_row ->> state_column = 'CLAIMED' AND new_row ->> state_column = 'CLAIMED'",
        "           AND (old_row ->> 'lease_owner') IS DISTINCT FROM (new_row ->> 'lease_owner') THEN",
        "            RAISE EXCEPTION 'claimed queue lease_owner cannot change in place on %.%', TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '55000';",
        "        END IF;",
        "    END IF;",
        "",
        "    RETURN NEW;",
        "END;",
        "$$;",
        "COMMENT ON FUNCTION platform_meta.fn_guard_controlled_update() IS",
        "    '受控更新守卫：要求语义变化、CAS精确递增、write-once单向写入、终态封存、白名单状态转换及队列计数和围栏单调。';",
        "",
        "CREATE FUNCTION platform_meta.fn_guard_initial_state()",
        "RETURNS trigger",
        "LANGUAGE plpgsql",
        "SECURITY DEFINER",
        "SET search_path = pg_catalog",
        "AS $$",
        "BEGIN",
        "    IF (to_jsonb(NEW) ->> 'revision')::bigint <> 0 THEN",
        "        RAISE EXCEPTION 'initial revision must be zero on %.%', TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '23514';",
        "    END IF;",
        "    IF (to_jsonb(NEW) ->> TG_ARGV[0]) IS DISTINCT FROM TG_ARGV[1] THEN",
        "        RAISE EXCEPTION 'initial state must be % on %.%', TG_ARGV[1], TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '23514';",
        "    END IF;",
        "    IF TG_ARGV[2] = 'QUEUE'",
        "       AND (((to_jsonb(NEW) ->> 'fencing_token')::bigint <> 0)",
        "         OR ((to_jsonb(NEW) ->> 'attempt_count')::bigint <> 0)) THEN",
        "        RAISE EXCEPTION 'initial queue counters must be zero on %.%', TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '23514';",
        "    END IF;",
        "    RETURN NEW;",
        "END;",
        "$$;",
        "COMMENT ON FUNCTION platform_meta.fn_guard_initial_state() IS",
        "    '初态守卫：受控状态机必须以revision零、静态唯一初态创建；队列表围栏与尝试计数也必须从零开始。';",
        "",
        "CREATE FUNCTION platform_meta.fn_guard_initial_revision()",
        "RETURNS trigger",
        "LANGUAGE plpgsql",
        "SET search_path = pg_catalog",
        "AS $$",
        "BEGIN",
        "    IF (to_jsonb(NEW) ->> 'revision')::bigint <> 0 THEN",
        "        RAISE EXCEPTION 'initial revision must be zero on %.%', TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '23514';",
        "    END IF;",
        "    RETURN NEW;",
        "END;",
        "$$;",
        "COMMENT ON FUNCTION platform_meta.fn_guard_initial_revision() IS",
        "    '初始CAS守卫：没有状态字段的当前态锚点和单向槽位表也必须以revision零创建。';",
        "",
        "CREATE FUNCTION platform_meta.fn_guard_initial_nulls()",
        "RETURNS trigger",
        "LANGUAGE plpgsql",
        "SET search_path = pg_catalog",
        "AS $$",
        "DECLARE",
        "    required_null_columns text[] := string_to_array(TG_ARGV[0], ',');",
        "    required_null_column text;",
        "BEGIN",
        "    FOREACH required_null_column IN ARRAY required_null_columns LOOP",
        "        IF to_jsonb(NEW) -> required_null_column IS DISTINCT FROM 'null'::jsonb THEN",
        "            RAISE EXCEPTION 'initial column % must be null on %.%', required_null_column, TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '23514';",
        "        END IF;",
        "    END LOOP;",
        "    RETURN NEW;",
        "END;",
        "$$;",
        "COMMENT ON FUNCTION platform_meta.fn_guard_initial_nulls() IS",
        "    '初始空槽守卫：冻结的后续单向结论或撤回槽不得在锚点事实创建时预填。';",
        "",
    ]
    for schema in schemas:
        for table in schema.tables:
            qualified = f"{schema.name}.{table.name}"
            trigger_name = f"trg_{table.name}__mutation_guard"
            _validate_identifier(trigger_name)
            if table.update_policy == "IMMUTABLE":
                function = "platform_meta.fn_reject_fact_mutation()"
                comment = "不可变性保护：拒绝更新或删除已写入事实。"
            else:
                mutable = ",".join(table.mutable_columns)
                once = ",".join(table.write_once_columns)
                state = table.state_column or ""
                transitions = ",".join(f"{old}>{new}" for old, new in table.state_transitions)
                function = (
                    "platform_meta.fn_guard_controlled_update("
                    f"'{_sql_comment(mutable)}', '{_sql_comment(once)}', "
                    f"'{_sql_comment(state)}', '{_sql_comment(transitions)}', "
                    f"'{table.update_policy}')"
                )
                comment = "受控更新保护：仅允许冻结白名单字段、CAS和单向转换，始终拒绝删除。"
            lines.extend((
                f"CREATE TRIGGER {trigger_name}",
                f"BEFORE UPDATE OR DELETE ON {qualified}",
                "FOR EACH ROW",
                f"EXECUTE FUNCTION {function};",
                f"COMMENT ON TRIGGER {trigger_name} ON {qualified} IS '{comment}';",
                "",
            ))
            if table.initial_state is not None:
                initial_trigger_name = f"trg_{table.name}__initial_state"
                _validate_identifier(initial_trigger_name)
                lines.extend((
                    f"CREATE TRIGGER {initial_trigger_name}",
                    f"BEFORE INSERT ON {qualified}",
                    "FOR EACH ROW",
                    "EXECUTE FUNCTION platform_meta.fn_guard_initial_state(",
                    f"    '{table.state_column}', '{_sql_comment(table.initial_state)}', '{table.update_policy}'",
                    ");",
                    f"COMMENT ON TRIGGER {initial_trigger_name} ON {qualified} IS '初态保护：新行必须以{_sql_comment(table.initial_state)}创建。';",
                    "",
                ))
            elif table.update_policy != "IMMUTABLE":
                initial_trigger_name = f"trg_{table.name}__initial_revision"
                _validate_identifier(initial_trigger_name)
                lines.extend((
                    f"CREATE TRIGGER {initial_trigger_name}",
                    f"BEFORE INSERT ON {qualified}",
                    "FOR EACH ROW",
                    "EXECUTE FUNCTION platform_meta.fn_guard_initial_revision();",
                    f"COMMENT ON TRIGGER {initial_trigger_name} ON {qualified} IS '初始CAS保护：新行revision必须为零。';",
                    "",
                ))
    for schema_name, table_name, columns, trigger_name, comment in INITIAL_NULL_GUARDS:
        _validate_identifier(trigger_name)
        lines.extend((
            f"CREATE TRIGGER {trigger_name}",
            f"BEFORE INSERT ON {schema_name}.{table_name}",
            "FOR EACH ROW",
            "EXECUTE FUNCTION platform_meta.fn_guard_initial_nulls(",
            f"    '{','.join(columns)}'",
            ");",
            f"COMMENT ON TRIGGER {trigger_name} ON {schema_name}.{table_name} IS '{_sql_comment(comment)}';",
            "",
        ))

    lines.extend((
        "CREATE FUNCTION platform_meta.fn_assert_owned_pointer()",
        "RETURNS trigger",
        "LANGUAGE plpgsql",
        "SECURITY DEFINER",
        "SET search_path = pg_catalog",
        "AS $$",
        "DECLARE",
        "    pointer_value uuid;",
        "    owner_value uuid;",
        "    tenant_value uuid;",
        "    relation_matches boolean;",
        "BEGIN",
        "    pointer_value := nullif(to_jsonb(NEW) ->> TG_ARGV[0], '')::uuid;",
        "    IF pointer_value IS NULL THEN",
        "        RETURN NEW;",
        "    END IF;",
        "    tenant_value := (to_jsonb(NEW) ->> 'tenant_id')::uuid;",
        "    owner_value := (to_jsonb(NEW) ->> TG_ARGV[5])::uuid;",
        "    EXECUTE pg_catalog.format(",
        "        'SELECT EXISTS (SELECT 1 FROM %I.%I WHERE tenant_id = $1 AND %I = $2 AND %I = $3)',",
        "        TG_ARGV[1], TG_ARGV[2], TG_ARGV[3], TG_ARGV[4]",
        "    ) INTO relation_matches USING tenant_value, pointer_value, owner_value;",
        "    IF NOT relation_matches THEN",
        "        RAISE EXCEPTION 'owned pointer % on %.% does not belong to its anchor', TG_ARGV[0], TG_TABLE_SCHEMA, TG_TABLE_NAME",
        "            USING ERRCODE = '23514';",
        "    END IF;",
        "    RETURN NEW;",
        "END;",
        "$$;",
        "COMMENT ON FUNCTION platform_meta.fn_assert_owned_pointer() IS",
        "    '延迟归属守卫：提交时证明冻结当前指针或单向槽位所指事实属于同租户同一业务锚点。';",
        "",
        "CREATE FUNCTION platform_meta.fn_assert_evidence_finalization()",
        "RETURNS trigger",
        "LANGUAGE plpgsql",
        "SECURITY DEFINER",
        "SET search_path = pg_catalog",
        "AS $$",
        "DECLARE",
        "    matching_count integer;",
        "BEGIN",
        "    IF TG_OP = 'INSERT' THEN",
        "        IF NEW.status <> 'OPEN' THEN",
        "            RAISE EXCEPTION 'upload session must be created OPEN' USING ERRCODE = '23514';",
        "        END IF;",
        "        RETURN NEW;",
        "    END IF;",
        "",
        "    IF NEW.status = 'OBJECT_RECEIVED' THEN",
        "        SELECT count(*) INTO matching_count",
        "        FROM evidence.received_source_object source_object",
        "        WHERE source_object.tenant_id = NEW.tenant_id",
        "          AND source_object.upload_session_id = NEW.upload_session_id",
        "          AND source_object.object_store_code = NEW.object_store_code",
        "          AND source_object.object_key = NEW.object_key;",
        "        IF matching_count <> 1 THEN",
        "            RAISE EXCEPTION 'OBJECT_RECEIVED requires one exact source object' USING ERRCODE = '23514';",
        "        END IF;",
        "    ELSIF NEW.status = 'FINALIZED' THEN",
        "        SELECT count(*) INTO matching_count",
        "        FROM evidence.received_source_object source_object",
        "        JOIN evidence.evidence_submission submission",
        "          ON submission.tenant_id = source_object.tenant_id",
        "         AND submission.received_source_object_id = source_object.received_source_object_id",
        "        JOIN evidence.evidence_binding binding",
        "          ON binding.tenant_id = submission.tenant_id",
        "         AND binding.evidence_submission_id = submission.evidence_submission_id",
        "        WHERE source_object.tenant_id = NEW.tenant_id",
        "          AND source_object.upload_session_id = NEW.upload_session_id",
        "          AND source_object.object_store_code = NEW.object_store_code",
        "          AND source_object.object_key = NEW.object_key",
        "          AND source_object.scan_result = 'PASSED'",
        "          AND binding.purpose_code = NEW.purpose_code",
        "          AND binding.target_type = NEW.target_type",
        "          AND binding.target_id = NEW.target_id",
        "          AND binding.target_revision IS NOT DISTINCT FROM NEW.target_revision",
        "          AND binding.target_hash IS NOT DISTINCT FROM NEW.target_hash",
        "          AND binding.revoked_at IS NULL;",
        "        IF matching_count <> 1 THEN",
        "            RAISE EXCEPTION 'FINALIZED requires one passed source, immutable submission and exact active binding' USING ERRCODE = '23514';",
        "        END IF;",
        "    END IF;",
        "    RETURN NEW;",
        "END;",
        "$$;",
        "COMMENT ON FUNCTION platform_meta.fn_assert_evidence_finalization() IS",
        "    '证据晋级守卫：会话创建必须为OPEN；接收与最终晋级在提交时分别证明准确来源对象及PASSED、Submission和同目标同用途有效Binding链。';",
        "",
        "CREATE FUNCTION platform_meta.fn_assert_evidence_promotion_member()",
        "RETURNS trigger",
        "LANGUAGE plpgsql",
        "SECURITY DEFINER",
        "SET search_path = pg_catalog",
        "AS $$",
        "DECLARE",
        "    matching_count integer;",
        "BEGIN",
        "    IF TG_TABLE_NAME = 'received_source_object' THEN",
        "        SELECT count(*) INTO matching_count",
        "        FROM evidence.upload_session session",
        "        WHERE session.tenant_id = NEW.tenant_id",
        "          AND session.upload_session_id = NEW.upload_session_id",
        "          AND session.object_store_code = NEW.object_store_code",
        "          AND session.object_key = NEW.object_key",
        "          AND session.status IN ('OBJECT_RECEIVED', 'FINALIZED');",
        "        IF matching_count <> 1 THEN",
        "            RAISE EXCEPTION 'source object requires its exact received upload session' USING ERRCODE = '23514';",
        "        END IF;",
        "        RETURN NEW;",
        "    END IF;",
        "",
        "    SELECT count(*) INTO matching_count",
        "    FROM evidence.received_source_object source_object",
        "    JOIN evidence.upload_session session",
        "      ON session.tenant_id = source_object.tenant_id",
        "     AND session.upload_session_id = source_object.upload_session_id",
        "     AND session.object_store_code = source_object.object_store_code",
        "     AND session.object_key = source_object.object_key",
        "    JOIN evidence.evidence_submission submission",
        "      ON submission.tenant_id = source_object.tenant_id",
        "     AND submission.received_source_object_id = source_object.received_source_object_id",
        "    JOIN evidence.evidence_binding binding",
        "      ON binding.tenant_id = submission.tenant_id",
        "     AND binding.evidence_submission_id = submission.evidence_submission_id",
        "    WHERE source_object.tenant_id = NEW.tenant_id",
        "      AND source_object.scan_result = 'PASSED'",
        "      AND session.status = 'FINALIZED'",
        "      AND binding.revoked_at IS NULL",
        "      AND binding.purpose_code = session.purpose_code",
        "      AND binding.target_type = session.target_type",
        "      AND binding.target_id = session.target_id",
        "      AND binding.target_revision IS NOT DISTINCT FROM session.target_revision",
        "      AND binding.target_hash IS NOT DISTINCT FROM session.target_hash",
        "      AND submission.evidence_submission_id = NEW.evidence_submission_id;",
        "    IF matching_count <> 1 THEN",
        "        RAISE EXCEPTION 'submission and binding must be members of one exact finalized evidence promotion' USING ERRCODE = '23514';",
        "    END IF;",
        "    RETURN NEW;",
        "END;",
        "$$;",
        "COMMENT ON FUNCTION platform_meta.fn_assert_evidence_promotion_member() IS",
        "    '证据晋级成员守卫：反向证明SourceObject、Submission和Binding只能属于同一准确FINALIZED会话，防止绕过最终晋级命令单独插入。';",
        "",
        "CREATE FUNCTION platform_meta.fn_assert_lead_assignment_chain()",
        "RETURNS trigger",
        "LANGUAGE plpgsql",
        "SECURITY DEFINER",
        "SET search_path = pg_catalog",
        "AS $$",
        "DECLARE",
        "    relation_matches boolean;",
        "    current_assignment uuid;",
        "BEGIN",
        "    SELECT lead_root.current_assignment_id INTO current_assignment",
        "    FROM lead.lead lead_root",
        "    WHERE lead_root.tenant_id = NEW.tenant_id AND lead_root.lead_id = NEW.lead_id",
        "    FOR UPDATE;",
        "    IF NOT FOUND THEN",
        "        RAISE EXCEPTION 'lead assignment requires its exact lead root' USING ERRCODE = '23503';",
        "    END IF;",
        "    IF TG_OP = 'INSERT' AND NEW.assignment_no > 1 THEN",
        "        SELECT EXISTS (",
        "            SELECT 1 FROM lead.lead_assignment predecessor",
        "            WHERE predecessor.tenant_id = NEW.tenant_id",
        "              AND predecessor.lead_assignment_id = NEW.previous_assignment_id",
        "              AND predecessor.lead_id = NEW.lead_id",
        "              AND predecessor.assignment_no + 1 = NEW.assignment_no",
        "        ) INTO relation_matches;",
        "        IF NOT relation_matches THEN",
        "            RAISE EXCEPTION 'lead assignment must follow the direct predecessor of the same lead' USING ERRCODE = '23514';",
        "        END IF;",
        "    END IF;",
        "    IF NEW.assignment_status_code = 'OPEN' THEN",
        "        IF current_assignment IS DISTINCT FROM NEW.lead_assignment_id THEN",
        "            RAISE EXCEPTION 'open assignment must be the lead current assignment in the same transaction' USING ERRCODE = '23514';",
        "        END IF;",
        "    ELSIF current_assignment IS DISTINCT FROM NEW.lead_assignment_id",
        "          AND NOT EXISTS (",
        "        SELECT 1 FROM lead.lead_assignment successor",
        "        WHERE successor.tenant_id = NEW.tenant_id",
        "          AND successor.lead_assignment_id = current_assignment",
        "          AND successor.lead_id = NEW.lead_id",
        "          AND successor.previous_assignment_id = NEW.lead_assignment_id",
        "          AND successor.assignment_no = NEW.assignment_no + 1",
        "          AND successor.assignment_status_code = 'OPEN'",
        "    ) THEN",
        "        RAISE EXCEPTION 'closed assignment may remain the terminal current leaf or require its direct open successor to be current' USING ERRCODE = '23514';",
        "    END IF;",
        "    RETURN NEW;",
        "END;",
        "$$;",
        "COMMENT ON FUNCTION platform_meta.fn_assert_lead_assignment_chain() IS",
        "    '销售分配链守卫：锁定Lead根，证明Assignment直接追加；OPEN项必须是当前指针，关闭项可作为终局当前叶，改派时必须由直接OPEN后继原子替代。';",
        "",
        "CREATE FUNCTION platform_meta.fn_assert_lead_current_assignment()",
        "RETURNS trigger",
        "LANGUAGE plpgsql",
        "SECURITY DEFINER",
        "SET search_path = pg_catalog",
        "AS $$",
        "BEGIN",
        "    IF TG_OP = 'INSERT' THEN",
        "        IF NEW.current_assignment_id IS NOT NULL THEN",
        "            RAISE EXCEPTION 'lead must be inserted without a current assignment' USING ERRCODE = '23514';",
        "        END IF;",
        "        RETURN NEW;",
        "    END IF;",
        "    IF NEW.current_assignment_id IS NOT DISTINCT FROM OLD.current_assignment_id THEN",
        "        RETURN NEW;",
        "    END IF;",
        "    IF NEW.current_assignment_id IS NULL THEN",
        "        RAISE EXCEPTION 'current assignment cannot be cleared or rolled back' USING ERRCODE = '23514';",
        "    END IF;",
        "    IF OLD.current_assignment_id IS NULL THEN",
        "        IF NOT EXISTS (",
        "            SELECT 1 FROM lead.lead_assignment first_assignment",
        "            WHERE first_assignment.tenant_id = NEW.tenant_id",
        "              AND first_assignment.lead_assignment_id = NEW.current_assignment_id",
        "              AND first_assignment.lead_id = NEW.lead_id",
        "              AND first_assignment.assignment_no = 1",
        "              AND first_assignment.previous_assignment_id IS NULL",
        "              AND first_assignment.assignment_status_code = 'OPEN'",
        "        ) THEN",
        "            RAISE EXCEPTION 'first current assignment must select the open chain head' USING ERRCODE = '23514';",
        "        END IF;",
        "    ELSIF NOT EXISTS (",
        "        SELECT 1",
        "        FROM lead.lead_assignment successor",
        "        JOIN lead.lead_assignment predecessor",
        "          ON predecessor.tenant_id = successor.tenant_id",
        "         AND predecessor.lead_assignment_id = successor.previous_assignment_id",
        "        WHERE successor.tenant_id = NEW.tenant_id",
        "          AND successor.lead_assignment_id = NEW.current_assignment_id",
        "          AND successor.lead_id = NEW.lead_id",
        "          AND successor.assignment_status_code = 'OPEN'",
        "          AND predecessor.lead_assignment_id = OLD.current_assignment_id",
        "          AND predecessor.lead_id = NEW.lead_id",
        "          AND predecessor.assignment_status_code = 'CLOSED'",
        "          AND successor.assignment_no = predecessor.assignment_no + 1",
        "    ) THEN",
        "        RAISE EXCEPTION 'current assignment must advance to the direct open successor' USING ERRCODE = '23514';",
        "    END IF;",
        "    RETURN NEW;",
        "END;",
        "$$;",
        "COMMENT ON FUNCTION platform_meta.fn_assert_lead_current_assignment() IS",
        "    'Lead当前分派守卫：初建无指针，首次只指OPEN链首，后续只能在旧Assignment已关闭时沿直接OPEN后继前移，禁止清空、回拨或跳段。';",
        "",
        "CREATE FUNCTION platform_meta.fn_assert_opportunity_qualified_source()",
        "RETURNS trigger",
        "LANGUAGE plpgsql",
        "SECURITY DEFINER",
        "SET search_path = pg_catalog",
        "AS $$",
        "BEGIN",
        "    IF NOT EXISTS (",
        "        SELECT 1 FROM lead.lead_contact_result contact_result",
        "        WHERE contact_result.tenant_id = NEW.tenant_id",
        "          AND contact_result.lead_contact_result_id = NEW.source_contact_result_id",
        "          AND contact_result.lead_id = NEW.source_lead_id",
        "          AND contact_result.lead_assignment_id = NEW.source_assignment_id",
        "          AND contact_result.result_code = 'CONNECTED_VALID'",
        "    ) THEN",
        "        RAISE EXCEPTION 'opportunity requires its exact CONNECTED_VALID contact result' USING ERRCODE = '23514';",
        "    END IF;",
        "    RETURN NEW;",
        "END;",
        "$$;",
        "COMMENT ON FUNCTION platform_meta.fn_assert_opportunity_qualified_source() IS",
        "    '商机来源守卫：Opportunity只能由同Lead、同Assignment的准确CONNECTED_VALID联系结果形成。';",
        "",
        "CREATE FUNCTION platform_meta.fn_assert_quote_revision_package()",
        "RETURNS trigger",
        "LANGUAGE plpgsql",
        "SECURITY DEFINER",
        "SET search_path = pg_catalog",
        "AS $$",
        "DECLARE",
        "    relation_matches boolean;",
        "BEGIN",
        "    PERFORM 1 FROM opportunity.opportunity opportunity_root",
        "    WHERE opportunity_root.tenant_id = NEW.tenant_id",
        "      AND opportunity_root.opportunity_id = NEW.opportunity_id",
        "      AND opportunity_root.closed_at IS NULL",
        "    FOR UPDATE;",
        "    IF NOT FOUND THEN",
        "        RAISE EXCEPTION 'quote revision requires its exact open opportunity root' USING ERRCODE = '23514';",
        "    END IF;",
        "    IF NOT EXISTS (",
        "        SELECT 1 FROM opportunity.opportunity opportunity_root",
        "        WHERE opportunity_root.tenant_id = NEW.tenant_id",
        "          AND opportunity_root.opportunity_id = NEW.opportunity_id",
        "          AND opportunity_root.current_quote_revision_id = NEW.quote_revision_id",
        "    ) THEN",
        "        RAISE EXCEPTION 'quote revision must become the current revision in the same transaction' USING ERRCODE = '23514';",
        "    END IF;",
        "    IF NEW.quote_revision_no > 1 THEN",
        "        SELECT EXISTS (",
        "            SELECT 1 FROM opportunity.quote_revision predecessor",
        "            WHERE predecessor.tenant_id = NEW.tenant_id",
        "              AND predecessor.quote_revision_id = NEW.predecessor_quote_revision_id",
        "              AND predecessor.opportunity_id = NEW.opportunity_id",
        "              AND predecessor.quote_revision_no + 1 = NEW.quote_revision_no",
        "        ) INTO relation_matches;",
        "        IF NOT relation_matches THEN",
        "            RAISE EXCEPTION 'quote revision must follow the direct predecessor of the same opportunity' USING ERRCODE = '23514';",
        "        END IF;",
        "    END IF;",
        "    SELECT EXISTS (",
        "        SELECT 1",
        "        FROM opportunity.opportunity_participation participation",
        "        WHERE participation.tenant_id = NEW.tenant_id",
        "          AND participation.opportunity_id = NEW.opportunity_id",
        "          AND participation.participation_set_revision = NEW.participation_set_revision",
        "        GROUP BY participation.opportunity_id, participation.participation_set_revision",
        "        HAVING count(*) = max(participation.participation_set_size)",
        "           AND min(participation.participation_set_size) = max(participation.participation_set_size)",
        "           AND min(participation.participation_no) = 1",
        "           AND max(participation.participation_no) = max(participation.participation_set_size)",
        "           AND pg_catalog.bool_and(participation.participation_set_digest = NEW.participation_set_digest)",
        "    ) INTO relation_matches;",
        "    IF NOT relation_matches THEN",
        "        RAISE EXCEPTION 'quote revision requires one complete frozen participation set' USING ERRCODE = '23514';",
        "    END IF;",
        "    RETURN NEW;",
        "END;",
        "$$;",
        "COMMENT ON FUNCTION platform_meta.fn_assert_quote_revision_package() IS",
        "    '报价版本包守卫：锁定Opportunity根，证明直接版本后继、同事务当前指针及完整连续Participation集合与摘要。';",
        "",
        "CREATE FUNCTION platform_meta.fn_assert_participation_set_quoted()",
        "RETURNS trigger",
        "LANGUAGE plpgsql",
        "SECURITY DEFINER",
        "SET search_path = pg_catalog",
        "AS $$",
        "BEGIN",
        "    IF NOT EXISTS (",
        "        SELECT 1 FROM opportunity.quote_revision quote_revision",
        "        WHERE quote_revision.tenant_id = NEW.tenant_id",
        "          AND quote_revision.opportunity_id = NEW.opportunity_id",
        "          AND quote_revision.participation_set_revision = NEW.participation_set_revision",
        "          AND quote_revision.participation_set_digest = NEW.participation_set_digest",
        "    ) THEN",
        "        RAISE EXCEPTION 'participation set must be sealed by its quote revision in the same transaction' USING ERRCODE = '23514';",
        "    END IF;",
        "    RETURN NEW;",
        "END;",
        "$$;",
        "COMMENT ON FUNCTION platform_meta.fn_assert_participation_set_quoted() IS",
        "    '参与集合成员守卫：每个不可变OpportunityParticipation必须被同版本同摘要QuoteRevision反向封存。';",
        "",
        "CREATE FUNCTION platform_meta.fn_assert_opportunity_lifecycle()",
        "RETURNS trigger",
        "LANGUAGE plpgsql",
        "SECURITY DEFINER",
        "SET search_path = pg_catalog",
        "AS $$",
        "BEGIN",
        "    IF OLD.closed_at IS NOT NULL THEN",
        "        RAISE EXCEPTION 'closed opportunity is sealed' USING ERRCODE = '55000';",
        "    END IF;",
        "    IF NEW.current_quote_revision_id IS DISTINCT FROM OLD.current_quote_revision_id THEN",
        "        IF NEW.current_quote_revision_id IS NULL THEN",
        "            RAISE EXCEPTION 'current quote revision cannot be cleared' USING ERRCODE = '23514';",
        "        END IF;",
        "        IF OLD.current_quote_revision_id IS NULL THEN",
        "            IF NOT EXISTS (",
        "                SELECT 1 FROM opportunity.quote_revision first_revision",
        "                WHERE first_revision.tenant_id = NEW.tenant_id",
        "                  AND first_revision.quote_revision_id = NEW.current_quote_revision_id",
        "                  AND first_revision.opportunity_id = NEW.opportunity_id",
        "                  AND first_revision.quote_revision_no = 1",
        "                  AND first_revision.predecessor_quote_revision_id IS NULL",
        "            ) THEN",
        "                RAISE EXCEPTION 'first current quote pointer must select revision one' USING ERRCODE = '23514';",
        "            END IF;",
        "        ELSIF NOT EXISTS (",
        "            SELECT 1",
        "            FROM opportunity.quote_revision successor",
        "            JOIN opportunity.quote_revision predecessor",
        "              ON predecessor.tenant_id = successor.tenant_id",
        "             AND predecessor.quote_revision_id = successor.predecessor_quote_revision_id",
        "            WHERE successor.tenant_id = NEW.tenant_id",
        "              AND successor.quote_revision_id = NEW.current_quote_revision_id",
        "              AND successor.opportunity_id = NEW.opportunity_id",
        "              AND predecessor.quote_revision_id = OLD.current_quote_revision_id",
        "              AND predecessor.opportunity_id = NEW.opportunity_id",
        "              AND successor.quote_revision_no = predecessor.quote_revision_no + 1",
        "        ) THEN",
        "            RAISE EXCEPTION 'current quote pointer must advance to the direct successor' USING ERRCODE = '23514';",
        "        END IF;",
        "    END IF;",
        "    RETURN NEW;",
        "END;",
        "$$;",
        "COMMENT ON FUNCTION platform_meta.fn_assert_opportunity_lifecycle() IS",
        "    '商机生命周期守卫：当前Quote指针只能从空指向首版或沿直接后继前移；关闭事实形成后整行封存，不得回拨、跳版或重开。';",
        "",
        "CREATE FUNCTION platform_meta.fn_assert_contract_revision_package()",
        "RETURNS trigger",
        "LANGUAGE plpgsql",
        "SECURITY DEFINER",
        "SET search_path = pg_catalog",
        "AS $$",
        "DECLARE",
        "    source_matches boolean;",
        "BEGIN",
        "    PERFORM 1 FROM contract.contract contract_root",
        "    WHERE contract_root.tenant_id = NEW.tenant_id",
        "      AND contract_root.contract_id = NEW.contract_id",
        "      AND contract_root.current_revision_id = NEW.contract_revision_id",
        "    FOR UPDATE;",
        "    IF NOT FOUND THEN",
        "        RAISE EXCEPTION 'contract revision must become current in the same transaction' USING ERRCODE = '23514';",
        "    END IF;",
        "    PERFORM 1",
        "    FROM opportunity.quote_response response",
        "    JOIN opportunity.quote_issue issue",
        "      ON issue.tenant_id = response.tenant_id",
        "     AND issue.quote_issue_id = response.quote_issue_id",
        "    WHERE response.tenant_id = NEW.tenant_id",
        "      AND response.quote_response_id = NEW.source_quote_response_id",
        "    FOR UPDATE OF issue;",
        "    IF NOT FOUND THEN",
        "        RAISE EXCEPTION 'contract revision requires its exact issued quote response' USING ERRCODE = '23503';",
        "    END IF;",
        "    SELECT EXISTS (",
        "        SELECT 1",
        "        FROM contract.contract contract_root",
        "        JOIN opportunity.quote_response response",
        "          ON response.tenant_id = contract_root.tenant_id",
        "         AND response.quote_response_id = contract_root.accepted_quote_response_id",
        "         AND response.quote_response_id = NEW.source_quote_response_id",
        "        JOIN opportunity.quote_issue issue",
        "          ON issue.tenant_id = response.tenant_id",
        "         AND issue.quote_issue_id = response.quote_issue_id",
        "        JOIN opportunity.quote_revision quote_revision",
        "          ON quote_revision.tenant_id = issue.tenant_id",
        "         AND quote_revision.quote_revision_id = issue.quote_revision_id",
        "         AND quote_revision.quote_revision_id = NEW.source_quote_revision_id",
        "        WHERE contract_root.tenant_id = NEW.tenant_id",
        "          AND contract_root.contract_id = NEW.contract_id",
        "          AND response.response_code = 'ACCEPTED'",
        "          AND quote_revision.opportunity_id = contract_root.opportunity_id",
        "    ) INTO source_matches;",
        "    IF NOT source_matches THEN",
        "        RAISE EXCEPTION 'contract revision quote and accepted response must be the anchor consumed source chain' USING ERRCODE = '23514';",
        "    END IF;",
        "    RETURN NEW;",
        "END;",
        "$$;",
        "COMMENT ON FUNCTION platform_meta.fn_assert_contract_revision_package() IS",
        "    '合同版本来源守卫：锁定Contract和QuoteIssue，证明版本在同事务成为当前版本，且来源报价、Issue及ACCEPTED回应属于锚点已经消费的同一历史销售链；自然期限只在锚点创建时判断。';",
        "",
        "CREATE FUNCTION platform_meta.fn_assert_contract_lifecycle()",
        "RETURNS trigger",
        "LANGUAGE plpgsql",
        "SECURITY DEFINER",
        "SET search_path = pg_catalog",
        "AS $$",
        "DECLARE",
        "    relation_matches boolean;",
        "BEGIN",
        "    IF TG_OP = 'INSERT' THEN",
        "        PERFORM 1",
        "        FROM opportunity.quote_response response",
        "        JOIN opportunity.quote_issue issue",
        "          ON issue.tenant_id = response.tenant_id",
        "         AND issue.quote_issue_id = response.quote_issue_id",
        "        WHERE response.tenant_id = NEW.tenant_id",
        "          AND response.quote_response_id = NEW.accepted_quote_response_id",
        "        FOR UPDATE OF issue;",
        "        IF NOT FOUND THEN",
        "            RAISE EXCEPTION 'contract requires its exact issued quote response' USING ERRCODE = '23503';",
        "        END IF;",
        "        SELECT EXISTS (",
        "            SELECT 1",
        "            FROM opportunity.quote_response response",
        "            JOIN opportunity.quote_issue issue",
        "              ON issue.tenant_id = response.tenant_id",
        "             AND issue.quote_issue_id = response.quote_issue_id",
        "            JOIN opportunity.quote_revision quote_revision",
        "              ON quote_revision.tenant_id = issue.tenant_id",
        "             AND quote_revision.quote_revision_id = issue.quote_revision_id",
        "            WHERE response.tenant_id = NEW.tenant_id",
        "              AND response.quote_response_id = NEW.accepted_quote_response_id",
        "              AND response.response_code = 'ACCEPTED'",
        "              AND issue.issue_status_code = 'ACTIVE'",
        "              AND quote_revision.opportunity_id = NEW.opportunity_id",
        "              AND (quote_revision.valid_until IS NULL",
        "                   OR quote_revision.valid_until > pg_catalog.clock_timestamp())",
        "              AND NOT EXISTS (",
        "                  SELECT 1 FROM opportunity.quote_issue replacement",
        "                  WHERE replacement.tenant_id = issue.tenant_id",
        "                    AND replacement.replaces_quote_issue_id = issue.quote_issue_id",
        "              )",
        "              AND NOT EXISTS (",
        "                  SELECT 1 FROM opportunity.quote_response later_response",
        "                  WHERE later_response.tenant_id = response.tenant_id",
        "                    AND later_response.quote_issue_id = response.quote_issue_id",
        "                    AND later_response.response_no > response.response_no",
        "              )",
        "        ) INTO relation_matches;",
        "        IF NOT relation_matches THEN",
        "            RAISE EXCEPTION 'contract requires an active accepted quote response from the same opportunity' USING ERRCODE = '23514';",
        "        END IF;",
        "        IF NEW.current_revision_id IS NOT NULL",
        "           OR NEW.approved_revision_id IS NOT NULL",
        "           OR NEW.contract_execution_id IS NOT NULL",
        "           OR NEW.deal_activated_at IS NOT NULL",
        "           OR NEW.contract_termination_id IS NOT NULL THEN",
        "            RAISE EXCEPTION 'contract lifecycle slots must be empty on anchor insert' USING ERRCODE = '23514';",
        "        END IF;",
        "        RETURN NEW;",
        "    END IF;",
        "",
        "    IF OLD.contract_termination_id IS NOT NULL THEN",
        "        RAISE EXCEPTION 'terminated contract is sealed' USING ERRCODE = '55000';",
        "    END IF;",
        "",
        "    IF NEW.current_revision_id IS DISTINCT FROM OLD.current_revision_id THEN",
        "        IF OLD.contract_execution_id IS NOT NULL THEN",
        "            RAISE EXCEPTION 'executed contract cannot advance revision' USING ERRCODE = '55000';",
        "        END IF;",
        "        SELECT EXISTS (",
        "            SELECT 1",
        "            FROM contract.contract_revision next_revision",
        "            LEFT JOIN contract.contract_revision old_revision",
        "              ON old_revision.tenant_id = NEW.tenant_id",
        "             AND old_revision.contract_revision_id = OLD.current_revision_id",
        "            WHERE next_revision.tenant_id = NEW.tenant_id",
        "              AND next_revision.contract_revision_id = NEW.current_revision_id",
        "              AND next_revision.contract_id = NEW.contract_id",
        "              AND ((OLD.current_revision_id IS NULL",
        "                    AND next_revision.revision_no = 1",
        "                    AND next_revision.predecessor_revision_id IS NULL)",
        "                OR (OLD.current_revision_id IS NOT NULL",
        "                    AND next_revision.predecessor_revision_id = OLD.current_revision_id",
        "                    AND next_revision.revision_no = old_revision.revision_no + 1))",
        "        ) INTO relation_matches;",
        "        IF NOT relation_matches THEN",
        "            RAISE EXCEPTION 'current contract revision must advance by one direct successor' USING ERRCODE = '23514';",
        "        END IF;",
        "    ELSIF OLD.approved_revision_id IS NOT NULL",
        "          AND NEW.approved_revision_id IS DISTINCT FROM OLD.approved_revision_id THEN",
        "        RAISE EXCEPTION 'approved revision can change only while advancing the current revision' USING ERRCODE = '55000';",
        "    END IF;",
        "",
        "    IF NEW.approved_revision_id IS NOT NULL",
        "       AND NEW.approved_revision_id IS DISTINCT FROM NEW.current_revision_id THEN",
        "        RAISE EXCEPTION 'approved revision must equal current revision' USING ERRCODE = '23514';",
        "    END IF;",
        "",
        "    IF NEW.contract_execution_id IS NOT NULL THEN",
        "        SELECT EXISTS (",
        "            SELECT 1 FROM contract.contract_execution execution",
        "            WHERE execution.tenant_id = NEW.tenant_id",
        "              AND execution.contract_execution_id = NEW.contract_execution_id",
        "              AND execution.contract_id = NEW.contract_id",
        "              AND execution.contract_revision_id = NEW.current_revision_id",
        "              AND execution.contract_revision_id = NEW.approved_revision_id",
        "        ) INTO relation_matches;",
        "        IF NOT relation_matches THEN",
        "            RAISE EXCEPTION 'execution must bind the current approved contract revision' USING ERRCODE = '23514';",
        "        END IF;",
        "    END IF;",
        "",
        "    IF NEW.deal_activated_at IS NOT NULL AND NEW.contract_execution_id IS NULL THEN",
        "        RAISE EXCEPTION 'deal activation requires contract execution' USING ERRCODE = '23514';",
        "    END IF;",
        "    IF OLD.deal_activated_at IS NULL",
        "       AND NEW.deal_activated_at IS NOT NULL",
        "       AND NEW.contract_termination_id IS NOT NULL THEN",
        "        RAISE EXCEPTION 'deal activation cannot be formed together with cancellation or termination' USING ERRCODE = '23514';",
        "    END IF;",
        "",
        "    IF NEW.contract_termination_id IS NOT NULL THEN",
        "        SELECT EXISTS (",
        "            SELECT 1 FROM contract.contract_termination termination",
        "            WHERE termination.tenant_id = NEW.tenant_id",
        "              AND termination.contract_termination_id = NEW.contract_termination_id",
        "              AND termination.contract_id = NEW.contract_id",
        "              AND termination.contract_revision_id = NEW.current_revision_id",
        "              AND termination.contract_execution_id IS NOT DISTINCT FROM NEW.contract_execution_id",
        "        ) INTO relation_matches;",
        "        IF NOT relation_matches THEN",
        "            RAISE EXCEPTION 'termination must bind the current contract lifecycle' USING ERRCODE = '23514';",
        "        END IF;",
        "    END IF;",
        "    RETURN NEW;",
        "END;",
        "$$;",
        "COMMENT ON FUNCTION platform_meta.fn_assert_contract_lifecycle() IS",
        "    '合同生命周期守卫：锚点只由当前有效销售接受链形成且初始槽为空，版本仅沿直接后继前移，批准必须等于当前版本，执行和终止必须绑定同一准确版本，执行后版本冻结且终止后整行封存。';",
        "",
        "CREATE FUNCTION platform_meta.fn_guard_contract_signature_lifecycle()",
        "RETURNS trigger",
        "LANGUAGE plpgsql",
        "SECURITY DEFINER",
        "SET search_path = pg_catalog",
        "AS $$",
        "BEGIN",
        "    PERFORM 1",
        "    FROM contract.signature_plan plan",
        "    JOIN contract.contract_revision contract_revision",
        "      ON contract_revision.tenant_id = plan.tenant_id",
        "     AND contract_revision.contract_revision_id = plan.contract_revision_id",
        "    JOIN contract.contract contract_root",
        "      ON contract_root.tenant_id = contract_revision.tenant_id",
        "     AND contract_root.contract_id = contract_revision.contract_id",
        "    WHERE plan.tenant_id = NEW.tenant_id",
        "      AND plan.signature_plan_id = NEW.signature_plan_id",
        "      AND plan.contract_revision_id = NEW.contract_revision_id",
        "      AND contract_root.contract_execution_id IS NULL",
        "      AND contract_root.contract_termination_id IS NULL",
        "      AND (TG_OP = 'UPDATE' OR contract_root.current_revision_id = NEW.contract_revision_id)",
        "    FOR UPDATE OF contract_root;",
        "    IF NOT FOUND THEN",
        "        RAISE EXCEPTION 'signature creation or revocation requires the exact unexecuted and unterminated contract' USING ERRCODE = '55000';",
        "    END IF;",
        "    RETURN NEW;",
        "END;",
        "$$;",
        "COMMENT ON FUNCTION platform_meta.fn_guard_contract_signature_lifecycle() IS",
        "    '签署生命周期守卫：新签署只能进入当前版本；签署新增或单向撤回都锁定Contract根并拒绝在执行、取消或终止后发生。';",
        "",
        "CREATE FUNCTION platform_meta.fn_assert_contract_execution_package()",
        "RETURNS trigger",
        "LANGUAGE plpgsql",
        "SECURITY DEFINER",
        "SET search_path = pg_catalog",
        "AS $$",
        "BEGIN",
        "    PERFORM 1 FROM contract.contract contract_root",
        "    WHERE contract_root.tenant_id = NEW.tenant_id",
        "      AND contract_root.contract_id = NEW.contract_id",
        "      AND contract_root.current_revision_id = NEW.contract_revision_id",
        "      AND contract_root.approved_revision_id = NEW.contract_revision_id",
        "      AND contract_root.contract_execution_id = NEW.contract_execution_id",
        "    FOR UPDATE;",
        "    IF NOT FOUND THEN",
        "        RAISE EXCEPTION 'contract execution must fill the exact current approved anchor slot in the same transaction' USING ERRCODE = '23514';",
        "    END IF;",
        "    IF EXISTS (",
        "        SELECT 1 FROM contract.signature_plan plan",
        "        WHERE plan.tenant_id = NEW.tenant_id",
        "          AND plan.contract_revision_id = NEW.contract_revision_id",
        "          AND plan.required",
        "          AND NOT EXISTS (",
        "              SELECT 1",
        "              FROM contract.contract_signature signature",
        "              JOIN contract.contract_revision contract_revision",
        "                ON contract_revision.tenant_id = signature.tenant_id",
        "               AND contract_revision.contract_revision_id = signature.contract_revision_id",
        "              WHERE signature.tenant_id = plan.tenant_id",
        "                AND signature.signature_plan_id = plan.signature_plan_id",
        "                AND signature.contract_revision_id = plan.contract_revision_id",
        "                AND signature.revoked_at IS NULL",
        "                AND signature.signed_content_digest = contract_revision.content_digest",
        "          )",
        "    ) THEN",
        "        RAISE EXCEPTION 'contract execution requires one active exact-content signature for every required plan' USING ERRCODE = '23514';",
        "    END IF;",
        "    RETURN NEW;",
        "END;",
        "$$;",
        "COMMENT ON FUNCTION platform_meta.fn_assert_contract_execution_package() IS",
        "    '合同执行反向守卫：Execution必须在同事务回填当前批准槽，并证明每个必需SignaturePlan存在未撤回且内容摘要准确的签署；审批、审查、印章与归档仍由ContractRuntime复验。';",
        "",
        "CREATE FUNCTION platform_meta.fn_assert_contract_termination_anchor()",
        "RETURNS trigger",
        "LANGUAGE plpgsql",
        "SECURITY DEFINER",
        "SET search_path = pg_catalog",
        "AS $$",
        "BEGIN",
        "    PERFORM 1 FROM contract.contract contract_root",
        "    WHERE contract_root.tenant_id = NEW.tenant_id",
        "      AND contract_root.contract_id = NEW.contract_id",
        "      AND contract_root.current_revision_id = NEW.contract_revision_id",
        "      AND contract_root.contract_execution_id IS NOT DISTINCT FROM NEW.contract_execution_id",
        "      AND contract_root.contract_termination_id = NEW.contract_termination_id",
        "    FOR UPDATE;",
        "    IF NOT FOUND THEN",
        "        RAISE EXCEPTION 'contract termination must fill the exact current anchor slot in the same transaction' USING ERRCODE = '23514';",
        "    END IF;",
        "    RETURN NEW;",
        "END;",
        "$$;",
        "COMMENT ON FUNCTION platform_meta.fn_assert_contract_termination_anchor() IS",
        "    '合同终止反向守卫：CANCELLED或TERMINATED事实必须在同事务回填准确Contract当前版本、Execution选择器和单向终止槽，禁止孤立终止事实。';",
        "",
        "CREATE FUNCTION platform_meta.fn_assert_transfer_snapshot_chain()",
        "RETURNS trigger",
        "LANGUAGE plpgsql",
        "SECURITY DEFINER",
        "SET search_path = pg_catalog",
        "AS $$",
        "DECLARE",
        "    relation_matches boolean;",
        "    accepted_snapshot uuid;",
        "BEGIN",
        "    SELECT request.accepted_snapshot_id INTO accepted_snapshot",
        "    FROM transfer.transfer_request request",
        "    WHERE request.tenant_id = NEW.tenant_id",
        "      AND request.transfer_request_id = NEW.transfer_request_id",
        "    FOR UPDATE;",
        "    IF NOT FOUND THEN",
        "        RAISE EXCEPTION 'transfer snapshot requires its exact request' USING ERRCODE = '23503';",
        "    END IF;",
        "    IF accepted_snapshot IS NOT NULL THEN",
        "        RAISE EXCEPTION 'accepted transfer rejects new snapshots' USING ERRCODE = '55000';",
        "    END IF;",
        "    IF NEW.snapshot_no = 1 THEN",
        "        RETURN NEW;",
        "    END IF;",
        "    SELECT EXISTS (",
        "        SELECT 1 FROM transfer.transfer_snapshot predecessor",
        "        WHERE predecessor.tenant_id = NEW.tenant_id",
        "          AND predecessor.transfer_snapshot_id = NEW.predecessor_snapshot_id",
        "          AND predecessor.transfer_request_id = NEW.transfer_request_id",
        "          AND predecessor.snapshot_no + 1 = NEW.snapshot_no",
        "    ) INTO relation_matches;",
        "    IF NOT relation_matches THEN",
        "        RAISE EXCEPTION 'transfer snapshot must follow the direct predecessor of the same request' USING ERRCODE = '23514';",
        "    END IF;",
        "    RETURN NEW;",
        "END;",
        "$$;",
        "COMMENT ON FUNCTION platform_meta.fn_assert_transfer_snapshot_chain() IS",
        "    '转案快照链守卫：锁定请求根后证明补正只接在同请求直接前序，串行化ACCEPT并禁止接收后新增Snapshot。';",
        "",
        "CREATE FUNCTION platform_meta.fn_assert_transfer_return_open()",
        "RETURNS trigger",
        "LANGUAGE plpgsql",
        "SECURITY DEFINER",
        "SET search_path = pg_catalog",
        "AS $$",
        "DECLARE",
        "    accepted_snapshot uuid;",
        "BEGIN",
        "    SELECT request.accepted_snapshot_id INTO accepted_snapshot",
        "    FROM transfer.transfer_request request",
        "    WHERE request.tenant_id = NEW.tenant_id",
        "      AND request.transfer_request_id = NEW.transfer_request_id",
        "    FOR UPDATE;",
        "    IF NOT FOUND THEN",
        "        RAISE EXCEPTION 'return item requires its exact transfer request' USING ERRCODE = '23503';",
        "    END IF;",
        "    IF accepted_snapshot IS NOT NULL THEN",
        "        RAISE EXCEPTION 'accepted transfer rejects new return items' USING ERRCODE = '55000';",
        "    END IF;",
        "    RETURN NEW;",
        "END;",
        "$$;",
        "COMMENT ON FUNCTION platform_meta.fn_assert_transfer_return_open() IS",
        "    '转案退回项守卫：锁定同一请求根并禁止ACCEPT之后追加ReturnItem。';",
        "",
        "CREATE FUNCTION platform_meta.fn_assert_transfer_acceptance_leaf()",
        "RETURNS trigger",
        "LANGUAGE plpgsql",
        "SECURITY DEFINER",
        "SET search_path = pg_catalog",
        "AS $$",
        "DECLARE",
        "    relation_matches boolean;",
        "BEGIN",
        "    IF TG_OP = 'INSERT' THEN",
        "        IF NEW.accepted_snapshot_id IS NOT NULL THEN",
        "            RAISE EXCEPTION 'transfer acceptance slots must be empty on request insert' USING ERRCODE = '23514';",
        "        END IF;",
        "        RETURN NEW;",
        "    END IF;",
        "    IF NEW.accepted_snapshot_id IS NULL THEN",
        "        RETURN NEW;",
        "    END IF;",
        "    PERFORM 1 FROM contract.contract source_contract",
        "    WHERE source_contract.tenant_id = NEW.tenant_id",
        "      AND source_contract.contract_id = NEW.contract_id",
        "      AND source_contract.contract_execution_id = NEW.contract_execution_id",
        "      AND source_contract.deal_activated_at = NEW.deal_activated_at",
        "      AND source_contract.contract_termination_id IS NULL",
        "    FOR UPDATE;",
        "    IF NOT FOUND THEN",
        "        RAISE EXCEPTION 'transfer acceptance requires the exact executed active and unterminated contract' USING ERRCODE = '23514';",
        "    END IF;",
        "    SELECT EXISTS (",
        "        SELECT 1 FROM transfer.transfer_snapshot accepted",
        "        WHERE accepted.tenant_id = NEW.tenant_id",
        "          AND accepted.transfer_snapshot_id = NEW.accepted_snapshot_id",
        "          AND accepted.transfer_request_id = NEW.transfer_request_id",
        "          AND NOT EXISTS (",
        "              SELECT 1 FROM transfer.transfer_return_item returned_item",
        "              WHERE returned_item.tenant_id = accepted.tenant_id",
        "                AND returned_item.reviewed_snapshot_id = accepted.transfer_snapshot_id",
        "          )",
        "          AND NOT EXISTS (",
        "              SELECT 1 FROM transfer.transfer_snapshot successor",
        "              WHERE successor.tenant_id = accepted.tenant_id",
        "                AND successor.predecessor_snapshot_id = accepted.transfer_snapshot_id",
        "          )",
        "    ) INTO relation_matches;",
        "    IF NOT relation_matches THEN",
        "        RAISE EXCEPTION 'accepted snapshot must be the current leaf of its transfer request' USING ERRCODE = '23514';",
        "    END IF;",
        "    RETURN NEW;",
        "END;",
        "$$;",
        "COMMENT ON FUNCTION platform_meta.fn_assert_transfer_acceptance_leaf() IS",
        "    '转案接收守卫：接收槽只能在请求创建后一次写入；锁定来源Contract并要求仍已执行、已激活且未终止，acceptedSnapshot必须是同请求当前叶且未形成任何RETURN项。';",
        "",
    ))

    for (
        parent_schema, parent_table, pointer_column, parent_owner_column,
        child_schema, child_table, child_id_column, child_owner_column,
        trigger_name, comment,
    ) in OWNED_POINTER_GUARDS:
        _validate_identifier(trigger_name)
        lines.extend((
            f"CREATE CONSTRAINT TRIGGER {trigger_name}",
            f"AFTER INSERT OR UPDATE OF {pointer_column} ON {parent_schema}.{parent_table}",
            "DEFERRABLE INITIALLY DEFERRED",
            "FOR EACH ROW",
            "EXECUTE FUNCTION platform_meta.fn_assert_owned_pointer(",
            f"    '{pointer_column}', '{child_schema}', '{child_table}',",
            f"    '{child_id_column}', '{child_owner_column}', '{parent_owner_column}'",
            ");",
            f"COMMENT ON TRIGGER {trigger_name} ON {parent_schema}.{parent_table} IS '{_sql_comment(comment)}';",
            "",
        ))

    lines.extend((
        "CREATE CONSTRAINT TRIGGER ctrg_upload_session__finalization",
        "AFTER INSERT OR UPDATE OF status ON evidence.upload_session",
        "DEFERRABLE INITIALLY DEFERRED",
        "FOR EACH ROW",
        "EXECUTE FUNCTION platform_meta.fn_assert_evidence_finalization();",
        "COMMENT ON TRIGGER ctrg_upload_session__finalization ON evidence.upload_session IS",
        "    '证据晋级延迟守卫：在短事务提交时验证一会话一文件及Session、SourceObject、Submission、Binding准确晋级链。';",
        "",
        "CREATE CONSTRAINT TRIGGER ctrg_received_source_object__session_member",
        "AFTER INSERT ON evidence.received_source_object",
        "DEFERRABLE INITIALLY DEFERRED",
        "FOR EACH ROW",
        "EXECUTE FUNCTION platform_meta.fn_assert_evidence_promotion_member();",
        "COMMENT ON TRIGGER ctrg_received_source_object__session_member ON evidence.received_source_object IS",
        "    '来源对象反向守卫：只允许属于同租户、同Opaque Key且已进入接收态的准确UploadSession。';",
        "",
        "CREATE CONSTRAINT TRIGGER ctrg_evidence_submission__promotion_member",
        "AFTER INSERT ON evidence.evidence_submission",
        "DEFERRABLE INITIALLY DEFERRED",
        "FOR EACH ROW",
        "EXECUTE FUNCTION platform_meta.fn_assert_evidence_promotion_member();",
        "COMMENT ON TRIGGER ctrg_evidence_submission__promotion_member ON evidence.evidence_submission IS",
        "    '提交事实反向守卫：Submission只能与唯一Binding及FINALIZED会话在同一完整晋级链中存在。';",
        "",
        "CREATE CONSTRAINT TRIGGER ctrg_evidence_binding__promotion_member",
        "AFTER INSERT ON evidence.evidence_binding",
        "DEFERRABLE INITIALLY DEFERRED",
        "FOR EACH ROW",
        "EXECUTE FUNCTION platform_meta.fn_assert_evidence_promotion_member();",
        "COMMENT ON TRIGGER ctrg_evidence_binding__promotion_member ON evidence.evidence_binding IS",
        "    '绑定事实反向守卫：Binding必须准确复现会话冻结目标与用途并属于同一完整晋级链。';",
        "",
        "CREATE CONSTRAINT TRIGGER ctrg_lead_assignment__chain",
        "AFTER INSERT OR UPDATE OF assignment_status_code ON lead.lead_assignment",
        "DEFERRABLE INITIALLY DEFERRED",
        "FOR EACH ROW",
        "EXECUTE FUNCTION platform_meta.fn_assert_lead_assignment_chain();",
        "COMMENT ON TRIGGER ctrg_lead_assignment__chain ON lead.lead_assignment IS",
        "    '销售分配延迟守卫：Assignment必须属于同一Lead、按直接前序追加，并与唯一当前OPEN指针原子一致。';",
        "",
        "CREATE CONSTRAINT TRIGGER ctrg_lead__current_assignment",
        "AFTER INSERT OR UPDATE OF current_assignment_id ON lead.lead",
        "DEFERRABLE INITIALLY DEFERRED",
        "FOR EACH ROW",
        "EXECUTE FUNCTION platform_meta.fn_assert_lead_current_assignment();",
        "COMMENT ON TRIGGER ctrg_lead__current_assignment ON lead.lead IS",
        "    'Lead当前指针延迟守卫：首次只回填OPEN链首，改派只沿已关闭前序的直接OPEN后继前移。';",
        "",
        "CREATE CONSTRAINT TRIGGER ctrg_opportunity__qualified_source",
        "AFTER INSERT ON opportunity.opportunity",
        "DEFERRABLE INITIALLY DEFERRED",
        "FOR EACH ROW",
        "EXECUTE FUNCTION platform_meta.fn_assert_opportunity_qualified_source();",
        "COMMENT ON TRIGGER ctrg_opportunity__qualified_source ON opportunity.opportunity IS",
        "    '商机来源延迟守卫：只接受准确CONNECTED_VALID联系结果形成Opportunity。';",
        "",
        "CREATE CONSTRAINT TRIGGER ctrg_quote_revision__complete_package",
        "AFTER INSERT ON opportunity.quote_revision",
        "DEFERRABLE INITIALLY DEFERRED",
        "FOR EACH ROW",
        "EXECUTE FUNCTION platform_meta.fn_assert_quote_revision_package();",
        "COMMENT ON TRIGGER ctrg_quote_revision__complete_package ON opportunity.quote_revision IS",
        "    '报价版本延迟守卫：提交时证明连续版本链、当前指针和完整Participation集合。';",
        "",
        "CREATE CONSTRAINT TRIGGER ctrg_opportunity_participation__quoted_set",
        "AFTER INSERT ON opportunity.opportunity_participation",
        "DEFERRABLE INITIALLY DEFERRED",
        "FOR EACH ROW",
        "EXECUTE FUNCTION platform_meta.fn_assert_participation_set_quoted();",
        "COMMENT ON TRIGGER ctrg_opportunity_participation__quoted_set ON opportunity.opportunity_participation IS",
        "    '参与集合反向守卫：每一项都必须由同事务准确QuoteRevision封存。';",
        "",
        "CREATE CONSTRAINT TRIGGER ctrg_opportunity__lifecycle",
        "AFTER UPDATE ON opportunity.opportunity",
        "DEFERRABLE INITIALLY DEFERRED",
        "FOR EACH ROW",
        "EXECUTE FUNCTION platform_meta.fn_assert_opportunity_lifecycle();",
        "COMMENT ON TRIGGER ctrg_opportunity__lifecycle ON opportunity.opportunity IS",
        "    '商机生命周期延迟守卫：当前Quote指针仅沿直接后继前移，关闭后任何原位更新均被拒绝。';",
        "",
        "CREATE CONSTRAINT TRIGGER ctrg_contract_revision__complete_package",
        "AFTER INSERT ON contract.contract_revision",
        "DEFERRABLE INITIALLY DEFERRED",
        "FOR EACH ROW",
        "EXECUTE FUNCTION platform_meta.fn_assert_contract_revision_package();",
        "COMMENT ON TRIGGER ctrg_contract_revision__complete_package ON contract.contract_revision IS",
        "    '合同版本延迟守卫：提交时证明当前指针及报价Issue、QuoteRevision、ACCEPTED Response属于锚点已经消费的同一历史来源链。';",
        "",
        "CREATE CONSTRAINT TRIGGER ctrg_contract__lifecycle",
        "AFTER INSERT OR UPDATE ON contract.contract",
        "DEFERRABLE INITIALLY DEFERRED",
        "FOR EACH ROW",
        "EXECUTE FUNCTION platform_meta.fn_assert_contract_lifecycle();",
        "COMMENT ON TRIGGER ctrg_contract__lifecycle ON contract.contract IS",
        "    '合同生命周期延迟守卫：提交时验证版本直接后继、当前批准一致、准确执行和终止封存。';",
        "",
        "CREATE TRIGGER trg_contract_signature__lifecycle",
        "BEFORE INSERT OR UPDATE ON contract.contract_signature",
        "FOR EACH ROW",
        "EXECUTE FUNCTION platform_meta.fn_guard_contract_signature_lifecycle();",
        "COMMENT ON TRIGGER trg_contract_signature__lifecycle ON contract.contract_signature IS",
        "    '签署生命周期保护：新增签署及授权单向撤回均锁定准确Contract根，执行、取消或终止后拒绝任何签署变动。';",
        "",
        "CREATE CONSTRAINT TRIGGER ctrg_contract_execution__complete_package",
        "AFTER INSERT ON contract.contract_execution",
        "DEFERRABLE INITIALLY DEFERRED",
        "FOR EACH ROW",
        "EXECUTE FUNCTION platform_meta.fn_assert_contract_execution_package();",
        "COMMENT ON TRIGGER ctrg_contract_execution__complete_package ON contract.contract_execution IS",
        "    '合同执行反向延迟守卫：Execution必须原子回填锚点并保有全部必需、未撤回、准确内容签署。';",
        "",
        "CREATE CONSTRAINT TRIGGER ctrg_contract_termination__anchor",
        "AFTER INSERT ON contract.contract_termination",
        "DEFERRABLE INITIALLY DEFERRED",
        "FOR EACH ROW",
        "EXECUTE FUNCTION platform_meta.fn_assert_contract_termination_anchor();",
        "COMMENT ON TRIGGER ctrg_contract_termination__anchor ON contract.contract_termination IS",
        "    '合同终止反向延迟守卫：终止事实必须与锚点单向终止槽在同一短事务封存。';",
        "",
        "CREATE CONSTRAINT TRIGGER ctrg_transfer_snapshot__chain",
        "AFTER INSERT ON transfer.transfer_snapshot",
        "DEFERRABLE INITIALLY DEFERRED",
        "FOR EACH ROW",
        "EXECUTE FUNCTION platform_meta.fn_assert_transfer_snapshot_chain();",
        "COMMENT ON TRIGGER ctrg_transfer_snapshot__chain ON transfer.transfer_snapshot IS",
        "    '转案快照延迟守卫：提交时验证同请求直接前序并拒绝接收后的新增快照。';",
        "",
        "CREATE CONSTRAINT TRIGGER ctrg_transfer_return_item__unaccepted",
        "AFTER INSERT ON transfer.transfer_return_item",
        "DEFERRABLE INITIALLY DEFERRED",
        "FOR EACH ROW",
        "EXECUTE FUNCTION platform_meta.fn_assert_transfer_return_open();",
        "COMMENT ON TRIGGER ctrg_transfer_return_item__unaccepted ON transfer.transfer_return_item IS",
        "    '转案退回项延迟守卫：与ACCEPT串行化并拒绝接收后追加任何ReturnItem。';",
        "",
        "CREATE CONSTRAINT TRIGGER ctrg_transfer_request__acceptance_leaf",
        "AFTER INSERT OR UPDATE ON transfer.transfer_request",
        "DEFERRABLE INITIALLY DEFERRED",
        "FOR EACH ROW",
        "EXECUTE FUNCTION platform_meta.fn_assert_transfer_acceptance_leaf();",
        "COMMENT ON TRIGGER ctrg_transfer_request__acceptance_leaf ON transfer.transfer_request IS",
        "    '转案接收延迟守卫：请求初建无接收槽，ACCEPT只能冻结同请求当前叶Snapshot。';",
        "",
        "REVOKE ALL ON FUNCTION platform_meta.fn_reject_fact_mutation() FROM PUBLIC;",
        "REVOKE ALL ON FUNCTION platform_meta.fn_guard_controlled_update() FROM PUBLIC;",
        "REVOKE ALL ON FUNCTION platform_meta.fn_guard_initial_state() FROM PUBLIC;",
        "REVOKE ALL ON FUNCTION platform_meta.fn_guard_initial_revision() FROM PUBLIC;",
        "REVOKE ALL ON FUNCTION platform_meta.fn_guard_initial_nulls() FROM PUBLIC;",
        "REVOKE ALL ON FUNCTION platform_meta.fn_assert_owned_pointer() FROM PUBLIC;",
        "REVOKE ALL ON FUNCTION platform_meta.fn_assert_evidence_finalization() FROM PUBLIC;",
        "REVOKE ALL ON FUNCTION platform_meta.fn_assert_evidence_promotion_member() FROM PUBLIC;",
        "REVOKE ALL ON FUNCTION platform_meta.fn_assert_lead_assignment_chain() FROM PUBLIC;",
        "REVOKE ALL ON FUNCTION platform_meta.fn_assert_lead_current_assignment() FROM PUBLIC;",
        "REVOKE ALL ON FUNCTION platform_meta.fn_assert_opportunity_qualified_source() FROM PUBLIC;",
        "REVOKE ALL ON FUNCTION platform_meta.fn_assert_quote_revision_package() FROM PUBLIC;",
        "REVOKE ALL ON FUNCTION platform_meta.fn_assert_participation_set_quoted() FROM PUBLIC;",
        "REVOKE ALL ON FUNCTION platform_meta.fn_assert_opportunity_lifecycle() FROM PUBLIC;",
        "REVOKE ALL ON FUNCTION platform_meta.fn_assert_contract_revision_package() FROM PUBLIC;",
        "REVOKE ALL ON FUNCTION platform_meta.fn_assert_contract_lifecycle() FROM PUBLIC;",
        "REVOKE ALL ON FUNCTION platform_meta.fn_guard_contract_signature_lifecycle() FROM PUBLIC;",
        "REVOKE ALL ON FUNCTION platform_meta.fn_assert_contract_execution_package() FROM PUBLIC;",
        "REVOKE ALL ON FUNCTION platform_meta.fn_assert_contract_termination_anchor() FROM PUBLIC;",
        "REVOKE ALL ON FUNCTION platform_meta.fn_assert_transfer_snapshot_chain() FROM PUBLIC;",
        "REVOKE ALL ON FUNCTION platform_meta.fn_assert_transfer_return_open() FROM PUBLIC;",
        "REVOKE ALL ON FUNCTION platform_meta.fn_assert_transfer_acceptance_leaf() FROM PUBLIC;",
    ))
    return "\n".join(lines).rstrip() + "\n"


def _render_indexes(schemas: Sequence[Schema]) -> str:
    lines = ["-- 查询、自然幂等和单当前事实索引。", ""]
    for schema in schemas:
        for table in schema.tables:
            qualified = f"{schema.name}.{table.name}"
            for item in table.indexes:
                unique_sql = "UNIQUE " if item.unique else ""
                where_sql = f" WHERE {item.where}" if item.where else ""
                lines.append(
                    f"CREATE {unique_sql}INDEX {item.name} ON {qualified} ({', '.join(item.columns)}){where_sql};"
                )
                lines.append(f"COMMENT ON INDEX {schema.name}.{item.name} IS '{_sql_comment(item.comment)}';")
                lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def _render_privileges(schemas: Sequence[Schema]) -> str:
    application_schemas = [schema.name for schema in schemas if schema.name != "platform_meta"]
    managed_schemas = application_schemas + ["platform_meta"]
    allowed_user_schema_array = ", ".join(
        f"'{name}'" for name in managed_schemas + ["public"]
    )
    roles = "${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}"
    audit_table = next(
        table
        for schema in schemas if schema.name == "audit"
        for table in schema.tables if table.name == "audit_entry"
    )
    classified_audit_columns = tuple(
        column
        for column in audit_table.columns
        if column.name not in {"session_id_hmac", "client_ip_ciphertext", "execution_node_code"}
    )
    lines = [
        "-- Flyway placeholders必须映射到由IaC预创建、不可作为对象Owner的数据库角色。",
        "",
        "DO $role_contract$",
        "DECLARE",
        "    configured_roles text[] := ARRAY[",
        "        '${app_command_role}', '${app_worker_role}',",
        "        '${app_query_role}', '${audit_append_role}'",
        "    ];",
        "    role_name text;",
        "BEGIN",
        "    IF (SELECT count(DISTINCT item) FROM pg_catalog.unnest(configured_roles) AS item) <> 4 THEN",
        "        RAISE EXCEPTION 'application database roles must be four distinct roles';",
        "    END IF;",
        "    FOREACH role_name IN ARRAY configured_roles LOOP",
        "        IF role_name !~ '^[a-z][a-z0-9_]*$' THEN",
        "            RAISE EXCEPTION 'application database role is not unquoted snake_case: %', role_name;",
        "        END IF;",
        "        IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = role_name) THEN",
        "            RAISE EXCEPTION 'configured application database role does not exist: %', role_name;",
        "        END IF;",
        "        IF EXISTS (",
        "            SELECT 1 FROM pg_catalog.pg_roles",
        "            WHERE rolname = role_name",
        "              AND (rolcanlogin OR rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls)",
        "        ) THEN",
        "            RAISE EXCEPTION 'application database capability role has LOGIN or forbidden cluster capability: %', role_name;",
        "        END IF;",
        "    END LOOP;",
        "    IF EXISTS (",
        "        SELECT 1 FROM pg_catalog.pg_auth_members membership",
        "        JOIN pg_catalog.pg_roles member_role ON member_role.oid = membership.member",
        "        WHERE member_role.rolname = ANY(configured_roles)",
        "    ) THEN",
        "        RAISE EXCEPTION 'application database roles must not be members of any parent role';",
        "    END IF;",
        "END;",
        "$role_contract$;",
        "",
        "DO $dedicated_database_contract$",
        "BEGIN",
        "    IF EXISTS (",
        "        SELECT 1 FROM pg_catalog.pg_namespace namespace",
        "        WHERE namespace.nspname !~ '^pg_'",
        "          AND namespace.nspname <> 'information_schema'",
        f"          AND namespace.nspname NOT IN ({allowed_user_schema_array})",
        "    ) THEN",
        "        RAISE EXCEPTION 'schema contract requires a dedicated database without unexpected user schemas';",
        "    END IF;",
        "    IF EXISTS (",
        "        SELECT 1 FROM pg_catalog.pg_class relation",
        "        JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace",
        "        WHERE namespace.nspname = 'public'",
        "          AND relation.relkind IN ('r', 'p')",
        "    ) THEN",
        "        RAISE EXCEPTION 'schema contract requires no user tables in public schema';",
        "    END IF;",
        "END;",
        "$dedicated_database_contract$;",
        "",
        "DO $database_privileges$",
        "DECLARE",
        "    role_name text;",
        "BEGIN",
        "    FOREACH role_name IN ARRAY ARRAY[",
        "        '${app_command_role}', '${app_worker_role}',",
        "        '${app_query_role}', '${audit_append_role}'",
        "    ] LOOP",
        "        EXECUTE pg_catalog.format(",
        "            'REVOKE ALL PRIVILEGES ON DATABASE %I FROM %I',",
        "            current_database(), role_name",
        "        );",
        "    END LOOP;",
        "    EXECUTE pg_catalog.format(",
        "        'REVOKE CONNECT, CREATE, TEMPORARY ON DATABASE %I FROM PUBLIC',",
        "        current_database()",
        "    );",
        "END;",
        "$database_privileges$;",
        "REVOKE ALL ON SCHEMA public FROM PUBLIC, " + roles + ";",
        "",
        f"REVOKE ALL ON SCHEMA {', '.join(managed_schemas)} FROM {roles}, PUBLIC;",
    ]
    for schema in managed_schemas:
        lines.append(f"REVOKE ALL ON ALL TABLES IN SCHEMA {schema} FROM {roles}, PUBLIC;")
        lines.append(f"REVOKE ALL ON ALL SEQUENCES IN SCHEMA {schema} FROM {roles}, PUBLIC;")
        lines.append(f"REVOKE ALL ON ALL FUNCTIONS IN SCHEMA {schema} FROM {roles}, PUBLIC;")
        lines.append(f"ALTER DEFAULT PRIVILEGES IN SCHEMA {schema} REVOKE ALL ON TABLES FROM {roles}, PUBLIC;")
        lines.append(f"ALTER DEFAULT PRIVILEGES IN SCHEMA {schema} REVOKE ALL ON FUNCTIONS FROM {roles}, PUBLIC;")
    for schema in application_schemas:
        if schema == "audit":
            lines.append(f"GRANT USAGE ON SCHEMA {schema} TO ${{app_query_role}}, ${{audit_append_role}};")
        else:
            usage_roles = "${app_command_role}, ${app_query_role}"
            if schema in {"execution", "external_action"}:
                usage_roles += ", ${app_worker_role}"
            lines.append(f"GRANT USAGE ON SCHEMA {schema} TO {usage_roles};")
    lines.append("GRANT USAGE ON SCHEMA platform_meta TO ${app_command_role}, ${app_worker_role}, ${app_query_role};")
    lines.append("")
    for schema in schemas:
        if schema.name == "platform_meta":
            continue
        for table in schema.tables:
            qualified = f"{schema.name}.{table.name}"
            if qualified == "audit.audit_entry":
                lines.append(f"GRANT INSERT ON {qualified} TO ${{audit_append_role}};")
                continue
            lines.append(f"GRANT SELECT, INSERT ON {qualified} TO ${{app_command_role}};")
            lines.append(f"GRANT SELECT ON {qualified} TO ${{app_query_role}};")
            if table.update_policy != "IMMUTABLE":
                columns = ", ".join(table.mutable_columns)
                worker_target = table.update_policy == "QUEUE"
                role = "${app_worker_role}" if worker_target else "${app_command_role}"
                lines.append(f"GRANT UPDATE ({columns}) ON {qualified} TO {role};")
                if worker_target:
                    lines.append(f"GRANT SELECT ON {qualified} TO ${{app_worker_role}};")
    lines.extend((
        "GRANT SELECT ON execution.domain_event, external_action.external_action TO ${app_worker_role};",
        "GRANT UPDATE (status, available_at, revision)",
        "    ON execution.domain_event_outbox TO ${app_command_role};",
        "",
        "CREATE VIEW audit.audit_entry_classified_v",
        "WITH (security_barrier = true)",
        "AS",
        "SELECT",
        "    " + ",\n    ".join(column.name for column in classified_audit_columns),
        "FROM audit.audit_entry;",
        "COMMENT ON VIEW audit.audit_entry_classified_v IS",
        "    '受控审计分类视图：仅供Query Facade在实时四轴重鉴权且先写查询审计后读取；排除会话HMAC、客户端IP密文和执行节点。';",
    ))
    for column in classified_audit_columns:
        lines.append(
            "COMMENT ON COLUMN audit.audit_entry_classified_v."
            f"{column.name} IS '分类审计字段：{_sql_comment(column.comment)}';"
        )
    lines.extend((
        "REVOKE ALL ON audit.audit_entry_classified_v FROM PUBLIC, " + roles + ";",
        "GRANT SELECT ON audit.audit_entry_classified_v TO ${app_query_role};",
        "",
        "CREATE FUNCTION platform_meta.fn_guard_domain_event_redrive()",
        "RETURNS trigger",
        "LANGUAGE plpgsql",
        "SET search_path = pg_catalog",
        "AS $redrive$",
        "DECLARE",
        "    is_command_path boolean;",
        "BEGIN",
        "    is_command_path := pg_catalog.pg_has_role(current_user, TG_ARGV[0], 'MEMBER');",
        "    IF OLD.status = 'EXHAUSTED' AND NEW.status = 'PENDING' THEN",
        "        IF NOT is_command_path THEN",
        "            RAISE EXCEPTION 'only the command role may redrive an exhausted domain outbox' USING ERRCODE = '42501';",
        "        END IF;",
        "        IF (to_jsonb(NEW) - ARRAY['status', 'available_at', 'revision']::text[])",
        "           IS DISTINCT FROM",
        "           (to_jsonb(OLD) - ARRAY['status', 'available_at', 'revision']::text[]) THEN",
        "            RAISE EXCEPTION 'redrive may change only status, available_at and revision' USING ERRCODE = '55000';",
        "        END IF;",
        "    ELSIF is_command_path THEN",
        "        RAISE EXCEPTION 'command role may update this outbox only for exhausted redrive' USING ERRCODE = '42501';",
        "    END IF;",
        "    RETURN NEW;",
        "END;",
        "$redrive$;",
        "COMMENT ON FUNCTION platform_meta.fn_guard_domain_event_redrive() IS",
        "    '授权重驱门禁：普通Worker不能把EXHAUSTED领域事件投递恢复为PENDING，只有CommandRuntime角色可在审计命令中原位重驱。';",
        "CREATE TRIGGER trg_domain_event_outbox__authorized_redrive",
        "BEFORE UPDATE ON execution.domain_event_outbox",
        "FOR EACH ROW",
        "EXECUTE FUNCTION platform_meta.fn_guard_domain_event_redrive('${app_command_role}');",
        "COMMENT ON TRIGGER trg_domain_event_outbox__authorized_redrive ON execution.domain_event_outbox IS",
        "    '重驱权限保护：EXHAUSTED到PENDING只接受CommandRuntime数据库角色。';",
        "REVOKE ALL ON FUNCTION platform_meta.fn_guard_domain_event_redrive() FROM PUBLIC, " + roles + ";",
        "",
        "GRANT SELECT ON platform_meta.deployment_state TO ${app_command_role}, ${app_worker_role}, ${app_query_role};",
        "REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON platform_meta.deployment_state FROM " + roles + ";",
    ))
    return "\n".join(lines).rstrip() + "\n"


def _render_validation(schemas: Sequence[Schema]) -> str:
    app_schemas = [schema.name for schema in schemas if schema.name != "platform_meta"]
    expected_tables = [f"{schema.name}.{table.name}" for schema in schemas for table in schema.tables if schema.name != "platform_meta"]
    values = ",\n            ".join(f"('{item.split('.')[0]}', '{item.split('.')[1]}')" for item in expected_tables)
    foreign_key_values = ",\n            ".join(
        "(" + ", ".join((
            f"'{schema.name}'",
            f"'{table.name}'",
            f"'{fk.name}'",
            "ARRAY[" + ", ".join(f"'{column}'" for column in fk.columns) + "]::text[]",
            f"'{fk.parent_schema}'",
            f"'{fk.parent_table}'",
            "ARRAY[" + ", ".join(f"'{column}'" for column in fk.parent_columns) + "]::text[]",
            "true" if fk.deferrable else "false",
            "true" if fk.initially_deferred else "false",
        )) + ")"
        for schema in schemas if schema.name != "platform_meta"
        for table in schema.tables
        for fk in table.foreign_keys
    )
    update_grants: list[tuple[str, str, str, str]] = []
    for schema in schemas:
        if schema.name == "platform_meta":
            continue
        for table in schema.tables:
            if table.update_policy == "QUEUE":
                update_grants.extend(
                    ("${app_worker_role}", schema.name, table.name, column)
                    for column in table.mutable_columns
                )
            elif table.update_policy != "IMMUTABLE":
                update_grants.extend(
                    ("${app_command_role}", schema.name, table.name, column)
                    for column in table.mutable_columns
                )
    update_grants.extend(
        ("${app_command_role}", "execution", "domain_event_outbox", column)
        for column in ("status", "available_at", "revision")
    )
    update_values = ",\n            ".join(
        f"('{role}', '{schema}', '{table}', '{column}')"
        for role, schema, table, column in sorted(update_grants)
    )
    schema_usage_grants = {
        *(('${app_command_role}', name) for name in app_schemas if name != 'audit'),
        ('${app_command_role}', 'platform_meta'),
        ('${app_worker_role}', 'execution'),
        ('${app_worker_role}', 'external_action'),
        ('${app_worker_role}', 'platform_meta'),
        *(( '${app_query_role}', name) for name in app_schemas),
        ('${app_query_role}', 'platform_meta'),
        ('${audit_append_role}', 'audit'),
    }
    schema_usage_values = ",\n            ".join(
        f"('{role}', '{schema}')" for role, schema in sorted(schema_usage_grants)
    )
    worker_select_tables = {
        "execution.domain_event",
        "execution.domain_event_outbox",
        "external_action.external_action",
        "external_action.external_action_outbox",
    }
    table_privilege_grants: list[tuple[str, str, str, bool, bool]] = []
    for schema in schemas:
        if schema.name == "platform_meta":
            continue
        for table in schema.tables:
            if f"{schema.name}.{table.name}" == "audit.audit_entry":
                table_privilege_grants.append(
                    ("${audit_append_role}", schema.name, table.name, False, True)
                )
                continue
            table_privilege_grants.extend((
                ("${app_command_role}", schema.name, table.name, True, True),
                ("${app_query_role}", schema.name, table.name, True, False),
            ))
            if f"{schema.name}.{table.name}" in worker_select_tables:
                table_privilege_grants.append(
                    ("${app_worker_role}", schema.name, table.name, True, False)
                )
    table_privilege_grants.extend((
        ("${app_command_role}", "platform_meta", "deployment_state", True, False),
        ("${app_worker_role}", "platform_meta", "deployment_state", True, False),
        ("${app_query_role}", "platform_meta", "deployment_state", True, False),
        ("${app_query_role}", "audit", "audit_entry_classified_v", True, False),
    ))
    table_privilege_values = ",\n            ".join(
        f"('{role}', '{schema}', '{table}', {'true' if can_select else 'false'}, {'true' if can_insert else 'false'})"
        for role, schema, table, can_select, can_insert in sorted(table_privilege_grants)
    )
    schema_array = ", ".join(f"'{name}'" for name in app_schemas)
    managed_schema_array = schema_array + ", 'platform_meta'"
    lines = [
        "-- 安装时结构合同断言；任一偏离都会令迁移失败。",
        "DO $$",
        "DECLARE",
        "    actual_count integer;",
        "    missing_count integer;",
        "BEGIN",
        "    IF EXISTS (",
        "        SELECT 1 FROM pg_catalog.pg_namespace namespace",
        "        WHERE namespace.nspname !~ '^pg_'",
        "          AND namespace.nspname <> 'information_schema'",
        "          AND namespace.nspname NOT IN (" + managed_schema_array + ", 'public')",
        "    ) THEN",
        "        RAISE EXCEPTION 'unexpected user schema violates the dedicated database contract';",
        "    END IF;",
        "    IF EXISTS (",
        "        SELECT 1 FROM pg_catalog.pg_class relation",
        "        JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace",
        "        WHERE namespace.nspname = 'public'",
        "          AND relation.relkind IN ('r', 'p')",
        "    ) THEN",
        "        RAISE EXCEPTION 'unexpected user table exists outside the 52 plus 2 ledger';",
        "    END IF;",
        "",
        f"    SELECT count(*) INTO actual_count FROM pg_catalog.pg_tables WHERE schemaname IN ({schema_array});",
        "    IF actual_count <> 52 THEN",
        "        RAISE EXCEPTION 'expected 52 application tables, found %', actual_count;",
        "    END IF;",
        "",
        "    WITH expected(schema_name, table_name) AS (VALUES",
        f"            {values}",
        "    )",
        "    SELECT count(*) INTO missing_count",
        "    FROM expected e",
        "    LEFT JOIN pg_catalog.pg_tables t ON t.schemaname = e.schema_name AND t.tablename = e.table_name",
        "    WHERE t.tablename IS NULL;",
        "    IF missing_count <> 0 THEN",
        "        RAISE EXCEPTION 'frozen application table ledger is incomplete: % missing', missing_count;",
        "    END IF;",
        "",
        "    SELECT count(*) INTO actual_count",
        "    FROM pg_catalog.pg_tables",
        "    WHERE schemaname = 'platform_meta';",
        "    IF actual_count <> 2 OR EXISTS (",
        "        SELECT 1 FROM pg_catalog.pg_tables",
        "        WHERE schemaname = 'platform_meta'",
        "          AND tablename NOT IN ('deployment_state', 'flyway_schema_history')",
        "    ) THEN",
        "        RAISE EXCEPTION 'expected 2 platform_meta tables (deployment_state and flyway_schema_history), found %', actual_count;",
        "    END IF;",
        "",
        "    SELECT count(*) INTO actual_count",
        "    FROM pg_catalog.pg_tables",
        "    WHERE schemaname IN (" + managed_schema_array + ");",
        "    IF actual_count <> 54 THEN",
        "        RAISE EXCEPTION 'expected 54 managed tables, found %', actual_count;",
        "    END IF;",
        "",
        "    SELECT count(*) INTO missing_count",
        "    FROM pg_catalog.pg_namespace n",
        "    WHERE n.nspname IN (" + managed_schema_array + ")",
        "      AND coalesce(pg_catalog.obj_description(n.oid, 'pg_namespace'), '') = '';",
        "    IF missing_count <> 0 THEN",
        "        RAISE EXCEPTION 'schema comments missing: %', missing_count;",
        "    END IF;",
        "",
        "    SELECT count(*) INTO missing_count",
        "    FROM pg_catalog.pg_class c",
        "    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace",
        "    WHERE n.nspname IN (" + managed_schema_array + ")",
        "      AND c.relkind IN ('r', 'p')",
        "      AND coalesce(pg_catalog.obj_description(c.oid, 'pg_class'), '') = '';",
        "    IF missing_count <> 0 THEN",
        "        RAISE EXCEPTION 'table comments missing: %', missing_count;",
        "    END IF;",
        "",
        "    SELECT count(*) INTO missing_count",
        "    FROM pg_catalog.pg_class c",
        "    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace",
        "    JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid",
        "    WHERE n.nspname IN (" + managed_schema_array + ")",
        "      AND c.relkind IN ('r', 'p') AND a.attnum > 0 AND NOT a.attisdropped",
        "      AND coalesce(pg_catalog.col_description(c.oid, a.attnum), '') = '';",
        "    IF missing_count <> 0 THEN",
        "        RAISE EXCEPTION 'column comments missing: %', missing_count;",
        "    END IF;",
        "",
        "    SELECT count(*) INTO missing_count",
        "    FROM pg_catalog.pg_constraint con",
        "    JOIN pg_catalog.pg_class c ON c.oid = con.conrelid",
        "    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace",
        "    WHERE n.nspname IN (" + managed_schema_array + ")",
        "      AND c.relname <> 'flyway_schema_history'",
        "      AND con.contype IN ('c', 'f', 'p', 'u', 'x')",
        "      AND coalesce(pg_catalog.obj_description(con.oid, 'pg_constraint'), '') = '';",
        "    IF missing_count <> 0 THEN",
        "        RAISE EXCEPTION 'constraint comments missing: %', missing_count;",
        "    END IF;",
        "",
        "    SELECT count(*) INTO missing_count",
        "    FROM pg_catalog.pg_class i",
        "    JOIN pg_catalog.pg_namespace n ON n.oid = i.relnamespace",
        "    JOIN pg_catalog.pg_index ix ON ix.indexrelid = i.oid",
        "    JOIN pg_catalog.pg_class c ON c.oid = ix.indrelid",
        "    WHERE n.nspname IN (" + managed_schema_array + ")",
        "      AND c.relname <> 'flyway_schema_history'",
        "      AND coalesce(pg_catalog.obj_description(i.oid, 'pg_class'), '') = '';",
        "    IF missing_count <> 0 THEN",
        "        RAISE EXCEPTION 'index comments missing: %', missing_count;",
        "    END IF;",
        "",
        "    SELECT count(*) INTO missing_count",
        "    FROM pg_catalog.pg_proc p",
        "    JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace",
        "    WHERE n.nspname = 'platform_meta' AND p.proname LIKE 'fn_%'",
        "      AND coalesce(pg_catalog.obj_description(p.oid, 'pg_proc'), '') = '';",
        "    IF missing_count <> 0 THEN",
        "        RAISE EXCEPTION 'function comments missing: %', missing_count;",
        "    END IF;",
        "",
        "    SELECT count(*) INTO missing_count",
        "    FROM pg_catalog.pg_trigger t",
        "    JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid",
        "    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace",
        "    WHERE n.nspname IN (" + managed_schema_array + ")",
        "      AND NOT t.tgisinternal",
        "      AND coalesce(pg_catalog.obj_description(t.oid, 'pg_trigger'), '') = '';",
        "    IF missing_count <> 0 THEN",
        "        RAISE EXCEPTION 'trigger comments missing: %', missing_count;",
        "    END IF;",
        "",
        "    IF EXISTS (",
        "        SELECT 1 FROM pg_catalog.pg_constraint con",
        "        JOIN pg_catalog.pg_class c ON c.oid = con.conrelid",
        "        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace",
        "        WHERE n.nspname IN (" + schema_array + ") AND con.contype = 'f'",
        "          AND (con.confupdtype <> 'a' OR con.confdeltype <> 'a')",
        "    ) THEN",
        "        RAISE EXCEPTION 'all application foreign keys must use NO ACTION';",
        "    END IF;",
        "",
        "    IF EXISTS (",
        "        SELECT 1 FROM pg_catalog.pg_constraint con",
        "        JOIN pg_catalog.pg_class child ON child.oid = con.conrelid",
        "        JOIN pg_catalog.pg_namespace namespace ON namespace.oid = child.relnamespace",
        "        WHERE namespace.nspname IN (" + schema_array + ")",
        "          AND con.contype = 'f'",
        "          AND (NOT con.convalidated OR con.confmatchtype <> 's')",
        "    ) THEN",
        "        RAISE EXCEPTION 'foreign key must be validated with MATCH SIMPLE';",
        "    END IF;",
        "",
        "    IF EXISTS (",
        "        SELECT 1",
        "        FROM (VALUES",
        "            ('${app_command_role}'), ('${app_worker_role}'),",
        "            ('${app_query_role}'), ('${audit_append_role}')",
        "        ) AS application_role(role_name)",
        "        JOIN pg_catalog.pg_roles role ON role.rolname = application_role.role_name",
        "        WHERE role.rolcanlogin",
        "    ) THEN",
        "        RAISE EXCEPTION 'application capability role must be NOLOGIN';",
        "    END IF;",
        "",
        "    IF EXISTS (",
        "        SELECT 1",
        "        FROM pg_catalog.pg_constraint con",
        "        JOIN pg_catalog.pg_class child_table ON child_table.oid = con.conrelid",
        "        JOIN pg_catalog.pg_namespace child_ns ON child_ns.oid = child_table.relnamespace",
        "        JOIN pg_catalog.pg_attribute child_column",
        "          ON child_column.attrelid = child_table.oid AND child_column.attnum = con.conkey[1]",
        "        JOIN pg_catalog.pg_class parent_table ON parent_table.oid = con.confrelid",
        "        JOIN pg_catalog.pg_attribute parent_column",
        "          ON parent_column.attrelid = parent_table.oid AND parent_column.attnum = con.confkey[1]",
        "        WHERE child_ns.nspname IN (" + schema_array + ")",
        "          AND con.contype = 'f'",
        "          AND (child_column.attname <> 'tenant_id' OR parent_column.attname <> 'tenant_id')",
        "    ) THEN",
        "        RAISE EXCEPTION 'tenant_id must be the first column of every tenant foreign key';",
        "    END IF;",
        "",
        "    WITH expected(",
        "        child_schema, child_table, constraint_name, child_columns,",
        "        parent_schema, parent_table, parent_columns, is_deferrable, is_deferred",
        "    ) AS (VALUES",
        f"            {foreign_key_values}",
        "    ), actual AS (",
        "        SELECT child_ns.nspname::text, child.relname::text, con.conname::text,",
        "               ARRAY(",
        "                   SELECT attribute.attname::text",
        "                   FROM pg_catalog.unnest(con.conkey) WITH ORDINALITY AS key(attnum, ordinal_no)",
        "                   JOIN pg_catalog.pg_attribute attribute",
        "                     ON attribute.attrelid = child.oid AND attribute.attnum = key.attnum",
        "                   ORDER BY key.ordinal_no",
        "               ),",
        "               parent_ns.nspname::text, parent.relname::text,",
        "               ARRAY(",
        "                   SELECT attribute.attname::text",
        "                   FROM pg_catalog.unnest(con.confkey) WITH ORDINALITY AS key(attnum, ordinal_no)",
        "                   JOIN pg_catalog.pg_attribute attribute",
        "                     ON attribute.attrelid = parent.oid AND attribute.attnum = key.attnum",
        "                   ORDER BY key.ordinal_no",
        "               ),",
        "               con.condeferrable, con.condeferred",
        "        FROM pg_catalog.pg_constraint con",
        "        JOIN pg_catalog.pg_class child ON child.oid = con.conrelid",
        "        JOIN pg_catalog.pg_namespace child_ns ON child_ns.oid = child.relnamespace",
        "        JOIN pg_catalog.pg_class parent ON parent.oid = con.confrelid",
        "        JOIN pg_catalog.pg_namespace parent_ns ON parent_ns.oid = parent.relnamespace",
        "        WHERE con.contype = 'f' AND child_ns.nspname IN (" + schema_array + ")",
        "    ), missing_expected AS (",
        "        SELECT * FROM expected EXCEPT SELECT * FROM actual",
        "    ), unexpected_actual AS (",
        "        SELECT * FROM actual EXCEPT SELECT * FROM expected",
        "    )",
        "    SELECT count(*) INTO missing_count FROM (",
        "        SELECT * FROM missing_expected",
        "        UNION ALL",
        "        SELECT * FROM unexpected_actual",
        "    ) AS foreign_key_drift;",
        "    IF missing_count <> 0 THEN",
        "        RAISE EXCEPTION 'physical foreign key whitelist mismatch: % differences', missing_count;",
        "    END IF;",
        "",
        "    IF EXISTS (",
        "        SELECT 1",
        "        FROM (VALUES",
        "            ('${app_command_role}'), ('${app_worker_role}'),",
        "            ('${app_query_role}'), ('${audit_append_role}')",
        "        ) AS application_role(role_name)",
        "        JOIN pg_catalog.pg_roles member_role",
        "          ON member_role.rolname = application_role.role_name",
        "        JOIN pg_catalog.pg_auth_members membership",
        "          ON membership.member = member_role.oid",
        "    ) THEN",
        "        RAISE EXCEPTION 'application role has a forbidden parent role membership';",
        "    END IF;",
        "",
        "    IF EXISTS (",
        "        SELECT 1",
        "        FROM (VALUES",
        "            ('${app_command_role}'), ('${app_worker_role}'),",
        "            ('${app_query_role}'), ('${audit_append_role}')",
        "        ) AS application_role(role_name)",
        "        CROSS JOIN pg_catalog.pg_roles privileged_role",
        "        WHERE (privileged_role.rolsuper OR privileged_role.rolcreaterole",
        "            OR privileged_role.rolcreatedb OR privileged_role.rolreplication",
        "            OR privileged_role.rolbypassrls)",
        "          AND pg_catalog.pg_has_role(application_role.role_name, privileged_role.oid, 'MEMBER')",
        "    ) THEN",
        "        RAISE EXCEPTION 'application role inherits a forbidden cluster capability';",
        "    END IF;",
        "",
        "    WITH expected(role_name, schema_name) AS (VALUES",
        f"            {schema_usage_values}",
        "    ), actual AS (",
        "        SELECT application_role.role_name, namespace.nspname::text",
        "        FROM (VALUES",
        "            ('${app_command_role}'), ('${app_worker_role}'),",
        "            ('${app_query_role}'), ('${audit_append_role}')",
        "        ) AS application_role(role_name)",
        "        CROSS JOIN pg_catalog.pg_namespace namespace",
        "        WHERE namespace.nspname IN (" + managed_schema_array + ")",
        "          AND pg_catalog.has_schema_privilege(application_role.role_name, namespace.oid, 'USAGE')",
        "    ), missing_expected AS (",
        "        SELECT * FROM expected EXCEPT SELECT * FROM actual",
        "    ), unexpected_actual AS (",
        "        SELECT * FROM actual EXCEPT SELECT * FROM expected",
        "    )",
        "    SELECT count(*) INTO missing_count FROM (",
        "        SELECT * FROM missing_expected",
        "        UNION ALL",
        "        SELECT * FROM unexpected_actual",
        "    ) AS schema_usage_drift;",
        "    IF missing_count <> 0 THEN",
        "        RAISE EXCEPTION 'schema USAGE privilege matrix mismatch: % differences', missing_count;",
        "    END IF;",
        "",
        "    IF EXISTS (",
        "        SELECT 1",
        "        FROM (VALUES",
        "            ('${app_command_role}'), ('${app_worker_role}'),",
        "            ('${app_query_role}'), ('${audit_append_role}')",
        "        ) AS application_role(role_name)",
        "        CROSS JOIN pg_catalog.pg_namespace namespace",
        "        WHERE namespace.nspname IN (" + managed_schema_array + ", 'public')",
        "          AND (pg_catalog.has_schema_privilege(application_role.role_name, namespace.oid, 'CREATE')",
        "               OR (namespace.nspname = 'public'",
        "                   AND pg_catalog.has_schema_privilege(application_role.role_name, namespace.oid, 'USAGE')))",
        "    ) OR EXISTS (",
        "        SELECT 1",
        "        FROM (VALUES",
        "            ('${app_command_role}'), ('${app_worker_role}'),",
        "            ('${app_query_role}'), ('${audit_append_role}')",
        "        ) AS application_role(role_name)",
        "        WHERE pg_catalog.has_database_privilege(application_role.role_name, current_database(), 'CONNECT')",
        "           OR pg_catalog.has_database_privilege(application_role.role_name, current_database(), 'CREATE')",
        "           OR pg_catalog.has_database_privilege(application_role.role_name, current_database(), 'TEMPORARY')",
        "    ) THEN",
        "        RAISE EXCEPTION 'application capability role has forbidden database CONNECT/CREATE/TEMPORARY, schema CREATE, or public USAGE';",
        "    END IF;",
        "",
        "    IF EXISTS (",
        "        SELECT 1",
        "        FROM (VALUES",
        "            ('${app_command_role}'), ('${app_worker_role}'),",
        "            ('${app_query_role}'), ('${audit_append_role}')",
        "        ) AS application_role(role_name)",
        "        CROSS JOIN (",
        "            SELECT object.relowner AS owner_oid",
        "            FROM pg_catalog.pg_class object",
        "            JOIN pg_catalog.pg_namespace namespace ON namespace.oid = object.relnamespace",
        "            WHERE namespace.nspname IN (" + managed_schema_array + ")",
        "              AND object.relkind IN ('r', 'p', 'v', 'm', 'S', 'i')",
        "            UNION",
        "            SELECT namespace.nspowner",
        "            FROM pg_catalog.pg_namespace namespace",
        "            WHERE namespace.nspname IN (" + managed_schema_array + ")",
        "            UNION",
        "            SELECT routine.proowner",
        "            FROM pg_catalog.pg_proc routine",
        "            JOIN pg_catalog.pg_namespace namespace ON namespace.oid = routine.pronamespace",
        "            WHERE namespace.nspname = 'platform_meta'",
        "            UNION",
        "            SELECT database.datdba",
        "            FROM pg_catalog.pg_database database",
        "            WHERE database.datname = current_database()",
        "        ) AS managed_owner",
        "        WHERE pg_catalog.pg_has_role(application_role.role_name, managed_owner.owner_oid, 'MEMBER')",
        "    ) THEN",
        "        RAISE EXCEPTION 'application role must not own or inherit managed owner';",
        "    END IF;",
        "",
        "    WITH allowed(role_name, schema_name, object_name, can_select, can_insert) AS (VALUES",
        f"            {table_privilege_values}",
        "    ), candidates AS (",
        "        SELECT application_role.role_name, namespace.nspname::text AS schema_name,",
        "               object.relname::text AS object_name, object.oid AS object_oid",
        "        FROM (VALUES",
        "            ('${app_command_role}'), ('${app_worker_role}'),",
        "            ('${app_query_role}'), ('${audit_append_role}')",
        "        ) AS application_role(role_name)",
        "        CROSS JOIN pg_catalog.pg_class object",
        "        JOIN pg_catalog.pg_namespace namespace ON namespace.oid = object.relnamespace",
        "        WHERE namespace.nspname IN (" + managed_schema_array + ")",
        "          AND object.relkind IN ('r', 'p', 'v')",
        "    )",
        "    SELECT count(*) INTO missing_count",
        "    FROM candidates candidate",
        "    LEFT JOIN allowed",
        "      ON allowed.role_name = candidate.role_name",
        "     AND allowed.schema_name = candidate.schema_name",
        "     AND allowed.object_name = candidate.object_name",
        "    WHERE pg_catalog.has_table_privilege(candidate.role_name, candidate.object_oid, 'SELECT')",
        "              IS DISTINCT FROM coalesce(allowed.can_select, false)",
        "       OR pg_catalog.has_any_column_privilege(candidate.role_name, candidate.object_oid, 'SELECT')",
        "              IS DISTINCT FROM coalesce(allowed.can_select, false)",
        "       OR pg_catalog.has_table_privilege(candidate.role_name, candidate.object_oid, 'INSERT')",
        "              IS DISTINCT FROM coalesce(allowed.can_insert, false)",
        "       OR pg_catalog.has_any_column_privilege(candidate.role_name, candidate.object_oid, 'INSERT')",
        "              IS DISTINCT FROM coalesce(allowed.can_insert, false);",
        "    IF missing_count <> 0 THEN",
        "        RAISE EXCEPTION 'table SELECT/INSERT privilege matrix mismatch: % objects', missing_count;",
        "    END IF;",
        "",
        "    IF EXISTS (",
        "        SELECT 1",
        "        FROM (VALUES",
        "            ('${app_command_role}'), ('${app_worker_role}'),",
        "            ('${app_query_role}'), ('${audit_append_role}')",
        "        ) AS application_role(role_name)",
        "        CROSS JOIN pg_catalog.pg_class object",
        "        JOIN pg_catalog.pg_namespace namespace ON namespace.oid = object.relnamespace",
        "        WHERE namespace.nspname IN (" + managed_schema_array + ")",
        "          AND object.relkind IN ('r', 'p')",
        "          AND (pg_catalog.has_table_privilege(application_role.role_name, object.oid, 'DELETE')",
        "            OR pg_catalog.has_table_privilege(application_role.role_name, object.oid, 'TRUNCATE'))",
        "    ) THEN",
        "        RAISE EXCEPTION 'application role has forbidden DELETE or TRUNCATE';",
        "    END IF;",
        "",
        "    IF EXISTS (",
        "        SELECT 1",
        "        FROM (VALUES",
        "            ('${app_command_role}'), ('${app_worker_role}'),",
        "            ('${app_query_role}'), ('${audit_append_role}')",
        "        ) AS application_role(role_name)",
        "        CROSS JOIN pg_catalog.pg_class object",
        "        JOIN pg_catalog.pg_namespace namespace ON namespace.oid = object.relnamespace",
        "        WHERE namespace.nspname IN (" + managed_schema_array + ")",
        "          AND object.relkind IN ('r', 'p')",
        "          AND (pg_catalog.has_table_privilege(application_role.role_name, object.oid, 'TRIGGER')",
        "            OR pg_catalog.has_table_privilege(application_role.role_name, object.oid, 'REFERENCES')",
        "            OR pg_catalog.has_any_column_privilege(application_role.role_name, object.oid, 'REFERENCES'))",
        "    ) THEN",
        "        RAISE EXCEPTION 'application role has forbidden TRIGGER or REFERENCES';",
        "    END IF;",
        "",
        "    IF EXISTS (",
        "        SELECT 1",
        "        FROM (VALUES",
        "            ('${app_command_role}'), ('${app_worker_role}'),",
        "            ('${app_query_role}'), ('${audit_append_role}')",
        "        ) AS application_role(role_name)",
        "        CROSS JOIN pg_catalog.pg_class object",
        "        JOIN pg_catalog.pg_namespace namespace ON namespace.oid = object.relnamespace",
        "        WHERE namespace.nspname IN (" + managed_schema_array + ")",
        "          AND object.relkind IN ('r', 'p')",
        "          AND pg_catalog.has_table_privilege(application_role.role_name, object.oid, 'UPDATE')",
        "    ) THEN",
        "        RAISE EXCEPTION 'application role must use only column-level UPDATE grants';",
        "    END IF;",
        "",
        "    IF EXISTS (",
        "        SELECT 1",
        "        FROM (VALUES",
        "            ('${app_command_role}'), ('${app_worker_role}'),",
        "            ('${app_query_role}'), ('${audit_append_role}')",
        "        ) AS application_role(role_name)",
        "        CROSS JOIN pg_catalog.pg_proc routine",
        "        JOIN pg_catalog.pg_namespace namespace ON namespace.oid = routine.pronamespace",
        "        WHERE namespace.nspname = 'platform_meta'",
        "          AND routine.proname LIKE 'fn_%'",
        "          AND pg_catalog.has_function_privilege(application_role.role_name, routine.oid, 'EXECUTE')",
        "    ) THEN",
        "        RAISE EXCEPTION 'application role has forbidden direct function EXECUTE';",
        "    END IF;",
        "",
        "    WITH expected(role_name, schema_name, table_name, column_name) AS (VALUES",
        f"            {update_values}",
        "    ), candidates AS (",
        "        SELECT application_role.role_name, namespace.nspname::text, object.relname::text, attribute.attname::text",
        "        FROM (VALUES",
        "            ('${app_command_role}'), ('${app_worker_role}'),",
        "            ('${app_query_role}'), ('${audit_append_role}')",
        "        ) AS application_role(role_name)",
        "        CROSS JOIN pg_catalog.pg_class object",
        "        JOIN pg_catalog.pg_namespace namespace ON namespace.oid = object.relnamespace",
        "        JOIN pg_catalog.pg_attribute attribute ON attribute.attrelid = object.oid",
        "        WHERE namespace.nspname IN (" + managed_schema_array + ")",
        "          AND object.relkind IN ('r', 'p')",
        "          AND attribute.attnum > 0 AND NOT attribute.attisdropped",
        "    ), actual AS (",
        "        SELECT candidate.* FROM candidates candidate",
        "        WHERE pg_catalog.has_column_privilege(",
        "            candidate.role_name,",
        "            pg_catalog.format('%I.%I', candidate.nspname, candidate.relname),",
        "            candidate.attname, 'UPDATE'",
        "        )",
        "    ), missing_expected AS (",
        "        SELECT * FROM expected EXCEPT SELECT * FROM actual",
        "    ), unexpected_actual AS (",
        "        SELECT * FROM actual EXCEPT SELECT * FROM expected",
        "    )",
        "    SELECT count(*) INTO missing_count FROM (",
        "        SELECT * FROM missing_expected",
        "        UNION ALL",
        "        SELECT * FROM unexpected_actual",
        "    ) AS update_drift;",
        "    IF missing_count <> 0 THEN",
        "        RAISE EXCEPTION 'column UPDATE whitelist mismatch: % differences', missing_count;",
        "    END IF;",
        "",
        "    IF pg_catalog.has_table_privilege('${app_query_role}', 'audit.audit_entry', 'SELECT')",
        "       OR NOT pg_catalog.has_table_privilege('${app_query_role}', 'audit.audit_entry_classified_v', 'SELECT') THEN",
        "        RAISE EXCEPTION 'audit query role must use only the classified audit view';",
        "    END IF;",
        "",
        "    SELECT count(*) INTO missing_count",
        "    FROM pg_catalog.pg_class c",
        "    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace",
        "    WHERE n.nspname IN (" + managed_schema_array + ")",
        "      AND c.relkind IN ('r', 'p')",
        "      AND c.relname <> 'flyway_schema_history'",
        "      AND NOT EXISTS (",
        "          SELECT 1 FROM pg_catalog.pg_trigger t",
        "          WHERE t.tgrelid = c.oid AND NOT t.tgisinternal",
        "            AND t.tgname = 'trg_' || c.relname || '__mutation_guard'",
        "      );",
        "    IF missing_count <> 0 THEN",
        "        RAISE EXCEPTION 'mutation guard coverage mismatch: % tables missing', missing_count;",
        "    END IF;",
        "END;",
        "$$;",
    ]
    return "\n".join(lines).rstrip() + "\n"


def _render_markdown(schemas: Sequence[Schema]) -> str:
    from .reference_registry import TYPED_REFERENCE_ALLOWED_TARGETS

    lines = [
        "# 待办驱动律所系统 52＋2 完整字段合同",
        "",
        "本文件由静态合同机械生成。`flyway_schema_history`由Flyway管理，因此仅记录管理边界，不重复描述其版本相关物理结构。",
        "",
    ]
    for schema in schemas:
        lines.extend((f"## `{schema.name}`", "", schema.comment, ""))
        lines.extend((f"- Fact Owner：`{DOMAIN_OWNERS[schema.name]}`", ""))
        for table in schema.tables:
            lines.extend((f"### `{schema.name}.{table.name}`", "", table.comment, ""))
            lines.append(f"- Fact Owner：`{_table_owner(schema.name, table.name)}`")
            lines.append(f"- 更新策略：`{table.update_policy}`")
            lines.append(f"- 主键：`({', '.join(table.primary_key)})`")
            if table.mutable_columns:
                lines.append(f"- 允许更新字段：`{', '.join(table.mutable_columns)}`")
            if table.write_once_columns:
                lines.append(f"- Write-once字段：`{', '.join(table.write_once_columns)}`")
            if table.state_column:
                lines.append(f"- 状态字段与初态：`{table.state_column} = {table.initial_state}`")
                lines.append(
                    "- 允许状态转换：" + ", ".join(
                        f"`{old} → {new}`" for old, new in table.state_transitions
                    )
                )
            lines.extend(("", "| 字段 | PostgreSQL类型 | 可空 | 默认值 | 说明 |", "|---|---|---:|---|---|"))
            for column in table.columns:
                lines.append(
                    f"| `{column.name}` | `{column.sql_type}` | {'是' if column.nullable else '否'} | "
                    f"`{column.default if column.default is not None else '—'}` | {column.comment} |"
                )
            lines.append("")
            if table.constraints:
                lines.extend(("约束：", ""))
                for item in table.constraints:
                    lines.append(f"- `{item.name}`（`{item.kind}`：`{item.expression}`）：{item.comment}")
                lines.append("")
            if table.foreign_keys:
                lines.extend(("物理外键：", ""))
                for item in table.foreign_keys:
                    lines.append(
                        f"- `{item.name}`：`({', '.join(item.columns)}) → "
                        f"{item.parent_schema}.{item.parent_table}({', '.join(item.parent_columns)})`。{item.comment}"
                    )
                lines.append("")
            if table.typed_references:
                lines.extend(("类型化准确引用：", ""))
                for item in table.typed_references:
                    lines.append(f"- `{item.prefix}`：{item.comment}；由静态允许列表、同租户Resolver和提交前复验保证。")
                lines.append("")
            if table.indexes:
                lines.extend(("索引：", ""))
                for item in table.indexes:
                    lines.append(
                        f"- `{item.name}`：列`({', '.join(item.columns)})`；"
                        f"唯一=`{'是' if item.unique else '否'}`；谓词=`{item.where}`。{item.comment}"
                    )
                lines.append("")

    lines.extend((
        "## 跨域复合外键矩阵",
        "",
        "只列出Schema之间的稳定单表关系；所有租户内关系都以`tenant_id`为第一列并使用`NO ACTION`，不存在级联删除。",
        "",
        "| 子表 | 外键 | 子列 | 父表 | 父列 | 延迟 |",
        "|---|---|---|---|---|---:|",
    ))
    for schema in schemas:
        for table in schema.tables:
            for fk in table.foreign_keys:
                if fk.parent_schema == schema.name:
                    continue
                lines.append(
                    f"| `{schema.name}.{table.name}` | `{fk.name}` | `({', '.join(fk.columns)})` | "
                    f"`{fk.parent_schema}.{fk.parent_table}` | `({', '.join(fk.parent_columns)})` | "
                    f"{'是' if fk.deferrable else '否'} |"
                )

    lines.extend((
        "",
        "## 类型化准确引用矩阵",
        "",
        "这些关系不伪造物理外键；类型、标识与revision/hash选择器由静态允许列表、同租户Resolver和命令提交前重验共同保证。",
        "",
        "| 表 | 引用槽 | 可空 | 物理列 | 允许目标类型 |",
        "|---|---|---:|---|---|",
    ))
    for schema in schemas:
        for table in schema.tables:
            for ref in table.typed_references:
                lines.append(
                    f"| `{schema.name}.{table.name}` | `{ref.prefix}` | {'是' if ref.optional else '否'} | "
                    f"`{', '.join(column.name for column in ref.columns)}` | "
                    f"`{', '.join(TYPED_REFERENCE_ALLOWED_TARGETS[f'{schema.name}.{table.name}.{ref.prefix}'])}` |"
                )

    lines.extend((
        "",
        "## 跨行守卫与运行时重验边界",
        "",
        "| 不变量 | 数据库可证明部分 | 提交前必须由Owner/CommandRuntime重验部分 |",
        "|---|---|---|",
        "| 当前指针与单向槽位归属 | 七个归属trigger证明目标同租户同根；Lead Assignment和Opportunity Quote指针另以根锁守卫证明只沿直接后继前移，禁止清空、回拨或跳版 | 目标业务版本、当前有效性、授权和命令预期revision |",
        "| Evidence晋级 | 根与三个成员反向延迟守卫共同证明Session、准确Opaque Key、PASSED SourceObject、唯一Submission及同目标同用途有效Binding只形成一条完整FINALIZED链 | 私有对象存储准确ObjectVersion、服务端Hash/真实类型/扫描可信性、最终四轴授权和Subject版本；下载仍须网关重验并先审计 |",
        "| 类型化准确引用 | 行内CHECK证明type/id完整且revision或hash二选一 | 静态类型允许列表、同租户实体存在性、准确版本或摘要匹配 |",
        "| 组织树与授权Scope | 复合FK和局部防自环证明直接邻接 | 当前组织树无长环、先限后允、同一Appointment路径及提交前四轴复验 |",
        "| Task完成 | 行内单向槽位、物理FK与不可变结果事实 | 完成Fact类型属于Task冻结合同，且由准确Fact Owner同事务写入 |",
        "| 命令原子结果 | Slot/Receipt/Event/Outbox的唯一性、终态和更新守卫 | SUCCEEDED新事实分支同事务写Slot、Fact/CAS、Audit、Event、Owner Outbox、Receipt；NO_CHANGE只写Slot、Audit及引用既有Fact的Receipt；REJECTED只写Slot、拒绝Audit及无结果Fact的Receipt；技术失败整体回滚 |",
        "| Audit与披露 | AuditEntry不可变、CORRECTION单链、原始审计表与分类视图权限分离 | 业务写同事务追加；拒绝、敏感读取和导出先提交Audit再返回；分类视图之外仍实时四轴授权 |",
        "| 不可变版本包 | 子项强引用准确QuoteRevision、ConflictReview或ContractRevision，旧版本不可更新 | 同一短事务写齐参与方、Scope/Line/PaymentTerm、Finding或合同子项并按规范算法复算集合与contentDigest |",
        "| 外部效果 | Action/Outbox/Inbox的状态、唯一键、Nonce和不可变消息指纹 | Provider调用前先CAS持久化DISPATCHED；网络后UNKNOWN；下一attempt与旧UNKNOWN→FAILED在同一根锁事务；Outbox兼容、异Hash隔离、可信Provider、无副作用PROBE或授权Decision收敛 |",
        "| 合同生命周期 | 锚点创建时证明销售来源Quote/Issue/最新ACCEPTED Response同链且有效；后续Revision只证明沿同一已消费历史来源和直接后继前移；approved=current，Execution回填当前批准版本并具备每个必需Plan的有效准确内容签署；Termination反向回填单向槽且禁止与首次激活同时形成；签署变动锁根且执行、取消或终止后封存 | 审查/审批槽完整，签署身份授权、印章、归档条件与最终四轴授权 |",
        "| 付款与激活 | PaymentGate冻结准确Confirmation UUID集合和摘要，PaymentConfirmation保存正绝对金额、币种及可信来源 | Resolver逐项证明合同版本，按RECEIPT/REVERSAL/REFUND方向和单一币种聚合；Gate满足与DealActivated同事务 |",
        "| 转案链与接收 | 复合FK和根锁延迟守卫证明直接前序、ReturnItem归属、当前叶无ReturnItem、来源Contract仍已执行/已激活/未终止，接收后禁止新增Snapshot/ReturnItem | RETURN/ACCEPT Decision须绑定准确叶、唯一REVIEW_TRANSFER Task及固定主命令；ReturnItem全集摘要、独立PRE_TRANSFER Review、材料/Evidence和接收授权全部仍有效 |",
        "| 权限与部署 | 四个能力角色必须NOLOGIN且无父角色；物理ACL断言精确比对Schema USAGE、SELECT/INSERT、列UPDATE，禁止Owner/DDL/Delete/Truncate/Trigger/References/函数执行 | IaC只向API/Worker LOGIN NOINHERIT角色直授目标库CONNECT，并以SET LOCAL ROLE选择单一能力；迁移Owner仅由发布作业使用；制品与manifest匹配后才CAS切ACTIVE |",
        "",
        "## 更新白名单",
        "",
        "未列出的应用事实表均由触发器拒绝`UPDATE`和`DELETE`；列出的表仍必须通过CAS、write-once和状态转换守卫。",
        "",
        "| 表 | 策略 | 允许更新列 |",
        "|---|---|---|",
    ))
    for schema in schemas:
        for table in schema.tables:
            if table.update_policy == "IMMUTABLE":
                continue
            lines.append(
                f"| `{schema.name}.{table.name}` | `{table.update_policy}` | "
                f"`{', '.join(table.mutable_columns)}` |"
            )

    lines.extend((
        "",
        "## Platform Meta边界",
        "",
        "- `platform_meta.deployment_state`是唯一自建技术表，由受控发布作业使用迁移Owner维护且四个应用角色只读；迁移Owner不是应用启动角色，凭据边界由IaC验证。",
        "- `platform_meta.flyway_schema_history`是第二张技术表，由固定版本Flyway独占创建和维护；本合同只补中文注释，不创建、修改或授权应用写入。",
        "- 结构验证迁移要求52张应用事实表加上述2张技术表恰好等于54张；任何额外第55张表都会使迁移失败。",
        "",
    ))
    return "\n".join(lines).rstrip() + "\n"


def _manifest(
    schemas: Sequence[Schema],
    generated_artifact_hashes: dict[str, str],
    field_contract_sha256: str,
) -> dict:
    from .reference_registry import TYPED_REFERENCE_ALLOWED_TARGETS

    application_tables = [
        f"{schema.name}.{table.name}"
        for schema in schemas if schema.name != "platform_meta"
        for table in schema.tables
    ]
    manifest = {
        "contractVersion": "52-plus-2-v1",
        "applicationTableCount": len(application_tables),
        "selfManagedPlatformTableCount": 1,
        "flywayManagedTable": "platform_meta.flyway_schema_history",
        "physicalTableCountAfterFlywayBootstrap": len(application_tables) + 2,
        "generatedArtifactSha256": dict(sorted(generated_artifact_hashes.items())),
        "fieldContractSha256": field_contract_sha256,
        "schemas": [
            {
                "name": schema.name,
                "comment": schema.comment,
                "owner": DOMAIN_OWNERS[schema.name],
                "tableCount": len(schema.tables),
                "tables": [
                    {
                        "qualifiedName": f"{schema.name}.{table.name}",
                        "name": table.name,
                        "owner": _table_owner(schema.name, table.name),
                        "comment": table.comment,
                        "idColumn": table.id_column,
                        "primaryKey": list(table.primary_key),
                        "primaryKeyComment": table.primary_key_comment,
                        "updatePolicy": table.update_policy,
                        "mutableColumns": list(table.mutable_columns),
                        "writeOnceColumns": list(table.write_once_columns),
                        "stateColumn": table.state_column,
                        "initialState": table.initial_state,
                        "stateTransitions": [list(item) for item in table.state_transitions],
                        "columns": [
                            {
                                "name": column.name,
                                "sqlType": column.sql_type,
                                "nullable": column.nullable,
                                "default": column.default,
                                "byteLength": column.byte_length,
                                "comment": column.comment,
                            }
                            for column in table.columns
                        ],
                        "constraints": [
                            {
                                "name": constraint.name,
                                "kind": constraint.kind,
                                "expression": constraint.expression,
                                "comment": constraint.comment,
                            }
                            for constraint in table.constraints
                        ],
                        "foreignKeys": [
                            {
                                "name": fk.name,
                                "columns": list(fk.columns),
                                "parentTable": f"{fk.parent_schema}.{fk.parent_table}",
                                "parentColumns": list(fk.parent_columns),
                                "onUpdate": fk.on_update,
                                "onDelete": fk.on_delete,
                                "deferrable": fk.deferrable,
                                "initiallyDeferred": fk.initially_deferred,
                                "comment": fk.comment,
                            }
                            for fk in table.foreign_keys
                        ],
                        "typedReferences": [
                            {
                                "slot": ref.prefix,
                                "optional": ref.optional,
                                "columns": [column.name for column in ref.columns],
                                "allowedTargetTypes": list(
                                    TYPED_REFERENCE_ALLOWED_TARGETS[
                                        f"{schema.name}.{table.name}.{ref.prefix}"
                                    ]
                                ),
                                "comment": ref.comment,
                            }
                            for ref in table.typed_references
                        ],
                        "indexes": [
                            {
                                "name": item.name,
                                "columns": list(item.columns),
                                "unique": item.unique,
                                "where": item.where,
                                "comment": item.comment,
                            }
                            for item in table.indexes
                        ],
                    }
                    for table in schema.tables
                ],
            }
            for schema in schemas
        ],
        "applicationTables": application_tables,
        "physicalForeignKeyWhitelist": [
            {
                "name": fk.name,
                "childTable": f"{schema.name}.{table.name}",
                "childColumns": list(fk.columns),
                "parentTable": f"{fk.parent_schema}.{fk.parent_table}",
                "parentColumns": list(fk.parent_columns),
                "onUpdate": fk.on_update,
                "onDelete": fk.on_delete,
                "deferrable": fk.deferrable,
                "initiallyDeferred": fk.initially_deferred,
            }
            for schema in schemas
            for table in schema.tables
            for fk in table.foreign_keys
        ],
        "typedReferenceRegistry": {
            slot: {"allowedTargetTypes": list(targets)}
            for slot, targets in sorted(TYPED_REFERENCE_ALLOWED_TARGETS.items())
        },
        "crossRowGuards": [
            {
                "trigger": trigger_name,
                "anchorTable": f"{parent_schema}.{parent_table}",
                "pointerColumn": pointer_column,
                "targetTable": f"{child_schema}.{child_table}",
                "targetIdColumn": child_id_column,
                "targetOwnerColumn": child_owner_column,
                "anchorOwnerColumn": parent_owner_column,
            }
            for (
                parent_schema, parent_table, pointer_column, parent_owner_column,
                child_schema, child_table, child_id_column, child_owner_column,
                trigger_name, _comment,
            ) in OWNED_POINTER_GUARDS
        ] + [
            {
                "trigger": "ctrg_upload_session__finalization",
                "anchorTable": "evidence.upload_session",
                "proof": "OPEN insert; exact SourceObject at OBJECT_RECEIVED; PASSED SourceObject plus unique Submission and exact active Binding at FINALIZED",
            },
            {
                "trigger": "ctrg_received_source_object__session_member",
                "anchorTable": "evidence.received_source_object",
                "proof": "exact same-tenant received Session and Opaque object key",
            },
            {
                "trigger": "ctrg_evidence_submission__promotion_member",
                "anchorTable": "evidence.evidence_submission",
                "proof": "Submission is a member of one complete FINALIZED promotion chain",
            },
            {
                "trigger": "ctrg_evidence_binding__promotion_member",
                "anchorTable": "evidence.evidence_binding",
                "proof": "Binding target and purpose exactly reproduce its FINALIZED Session",
            },
            {
                "trigger": "ctrg_lead_assignment__chain",
                "anchorTable": "lead.lead_assignment",
                "proof": "root-locked same-Lead direct predecessor with exact OPEN/current or terminal CLOSED leaf semantics",
            },
            {
                "trigger": "ctrg_lead__current_assignment",
                "anchorTable": "lead.lead",
                "proof": "current assignment starts at the OPEN head and advances only to the direct OPEN successor",
            },
            {
                "trigger": "ctrg_opportunity__qualified_source",
                "anchorTable": "opportunity.opportunity",
                "proof": "exact same-path CONNECTED_VALID LeadContactResult source",
            },
            {
                "trigger": "ctrg_quote_revision__complete_package",
                "anchorTable": "opportunity.quote_revision",
                "proof": "direct quote successor, current pointer and complete frozen participation set",
            },
            {
                "trigger": "ctrg_opportunity_participation__quoted_set",
                "anchorTable": "opportunity.opportunity_participation",
                "proof": "each participation member is reverse-sealed by its exact QuoteRevision",
            },
            {
                "trigger": "ctrg_opportunity__lifecycle",
                "anchorTable": "opportunity.opportunity",
                "proof": "current quote pointer advances only to a direct successor and closed Opportunity rejects all further mutation",
            },
            {
                "trigger": "ctrg_contract_revision__complete_package",
                "anchorTable": "contract.contract_revision",
                "proof": "revision becomes current and retains the anchor-consumed QuoteIssue, QuoteRevision and ACCEPTED Response history without reapplying natural expiry",
            },
            {
                "trigger": "ctrg_contract__lifecycle",
                "anchorTable": "contract.contract",
                "proof": "direct revision successor, current approval, exact execution and termination sealing",
            },
            {
                "trigger": "trg_contract_signature__lifecycle",
                "anchorTable": "contract.contract_signature",
                "proof": "signature insert or revocation locks the Contract root and is rejected after execution, cancellation or termination",
            },
            {
                "trigger": "ctrg_contract_execution__complete_package",
                "anchorTable": "contract.contract_execution",
                "proof": "execution reverse-fills the current approved anchor and every required plan has an active exact-content signature",
            },
            {
                "trigger": "ctrg_contract_termination__anchor",
                "anchorTable": "contract.contract_termination",
                "proof": "cancellation or termination reverse-fills the exact current Contract lifecycle slot in the same transaction",
            },
            {
                "trigger": "ctrg_transfer_snapshot__chain",
                "anchorTable": "transfer.transfer_snapshot",
                "proof": "same-request direct predecessor under root lock and no post-accept insertion",
            },
            {
                "trigger": "ctrg_transfer_return_item__unaccepted",
                "anchorTable": "transfer.transfer_return_item",
                "proof": "root-locked rejection of ReturnItem insertion after acceptance",
            },
            {
                "trigger": "ctrg_transfer_request__acceptance_leaf",
                "anchorTable": "transfer.transfer_request",
                "proof": "atomic acceptance locks an executed, activated, unterminated Contract and points to a non-returned current leaf Snapshot",
            },
        ],
        "runtimeObligations": [
            "typed reference static allowlist and same-tenant exact revision/hash resolution",
            "four-axis authorization on one Appointment path with deny-before-allow and pre-commit recheck",
            "AuditAppender same-transaction append for writes and committed audit before rejection or sensitive disclosure",
            "CommandRuntime outcome branches: SUCCEEDED new fact atomically writes Slot, Fact/CAS, Audit, DomainEvent, owner-directed Outbox and Receipt; NO_CHANGE writes Slot, Audit and Receipt referencing an existing Fact without event/outbox; REJECTED writes Slot, rejection Audit and no-result Receipt only",
            "Quote, ConflictReview and Contract immutable package children sealed in the same short transaction",
            "ConflictReview complete party and Finding counts plus all static authority slots before resolution",
            "Evidence private ObjectVersion, server hash, detected type, scan, final authorization and gateway download recheck",
            "ExternalAction durable DISPATCHED before provider call; post-network UNKNOWN; old UNKNOWN to proven FAILED and next attempt under one root-lock transaction; outbox/action compatibility and no blind resend",
            "ProviderInbox account binding, signature, nonce, same-key same-hash replay and different-hash isolation",
            "PaymentGate exact confirmation set, contract revision, currency and signed amount aggregation",
            "ContractSignature exact SignaturePlan slot, signer authority, signed content and trusted external result",
            "Transfer current leaf has no prior RETURN, ACCEPT/RETURN binds the exact leaf and unique REVIEW_TRANSFER task/command, complete return-item digest, review validity and no post-accept Snapshot or ReturnItem",
            "Fact Owner business conclusion and Task completion validation",
            "Query Facade tenant predicate, classified projection, four-axis authorization and disclosure-before-return audit",
            "two NOINHERIT startup LOGIN roles receive only direct target-database CONNECT from IaC: API selects one exact NOLOGIN Command, Query or Audit capability with SET LOCAL ROLE per statement path; Worker selects Worker capability only",
            "IaC-protected migration owner is used only by the release job as DeploymentRuntime; artifact, manifest and schema contract digests match before CAS to ACTIVE, while V840 validates only the four application roles",
        ],
    }
    canonical = json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    manifest["contractSha256"] = hashlib.sha256(canonical).hexdigest()
    return manifest


def generate_all(root: Path) -> None:
    schemas = _contract_schemas()
    _validate_contract(schemas)
    root.mkdir(parents=True, exist_ok=True)
    migration_dir = root / "db" / "migration"
    migration_dir.mkdir(parents=True, exist_ok=True)

    platform = next(schema for schema in schemas if schema.name == "platform_meta")
    application = tuple(schema for schema in schemas if schema.name != "platform_meta")

    files = {
        "V001__bootstrap_schemas.sql": _render_bootstrap(schemas),
        "V002__deployment_state.sql": _render_deployment_state(platform),
        "V800__cross_domain_foreign_keys.sql": _render_foreign_keys(schemas),
        "V810__update_guards.sql": _render_update_guards(schemas),
        "V820__indexes.sql": _render_indexes(schemas),
        "V830__application_privileges.sql": _render_privileges(schemas),
        "V840__schema_contract_validation.sql": _render_validation(schemas),
    }
    for schema in application:
        files[DOMAIN_MIGRATIONS[schema.name]] = _render_domain(schema)

    expected_paths = {migration_dir / name for name in files}
    for old in migration_dir.glob("*.sql"):
        if old not in expected_paths:
            old.unlink()
    for name, content in sorted(files.items()):
        (migration_dir / name).write_text(content, encoding="utf-8", newline="\n")

    field_contract = _render_markdown(schemas)
    (root / "field-contract.md").write_text(field_contract, encoding="utf-8", newline="\n")
    artifact_hashes = {
        f"db/migration/{name}": hashlib.sha256(content.encode("utf-8")).hexdigest()
        for name, content in files.items()
    }
    (root / "schema-contract-manifest.json").write_text(
        json.dumps(
            _manifest(
                schemas,
                artifact_hashes,
                hashlib.sha256(field_contract.encode("utf-8")).hexdigest(),
            ),
            ensure_ascii=False,
            indent=2,
        ) + "\n",
        encoding="utf-8",
        newline="\n",
    )
