from __future__ import annotations

from typing import Iterable, Sequence, Tuple

from .model import Column, Constraint, ForeignKey, Index, Table, TypedReference


def col(
    name: str,
    sql_type: str,
    comment: str,
    *,
    nullable: bool = False,
    default: str | None = None,
    byte_length: int | None = None,
) -> Column:
    return Column(name, sql_type, nullable, comment, default, byte_length)


def uuid_col(name: str, comment: str, *, nullable: bool = False) -> Column:
    return col(name, "uuid", comment, nullable=nullable)


def code_col(name: str, comment: str, *, length: int = 64, nullable: bool = False) -> Column:
    return col(name, f"varchar({length})", comment, nullable=nullable)


def text_col(name: str, comment: str, *, nullable: bool = False) -> Column:
    return col(name, "text", comment, nullable=nullable)


def time_col(name: str, comment: str, *, nullable: bool = False) -> Column:
    return col(name, "timestamptz(6)", comment, nullable=nullable)


def digest_col(name: str, comment: str, *, nullable: bool = False) -> Column:
    return col(name, "bytea", comment, nullable=nullable, byte_length=32)


def bigint_col(name: str, comment: str, *, nullable: bool = False, default: str | None = None) -> Column:
    return col(name, "bigint", comment, nullable=nullable, default=default)


def int_col(name: str, comment: str, *, nullable: bool = False, default: str | None = None) -> Column:
    return col(name, "integer", comment, nullable=nullable, default=default)


def bool_col(name: str, comment: str, *, nullable: bool = False, default: str | None = None) -> Column:
    return col(name, "boolean", comment, nullable=nullable, default=default)


def json_col(name: str, comment: str, *, nullable: bool = False) -> Column:
    return col(name, "jsonb", comment, nullable=nullable)


def encrypted_col(name: str, comment: str, *, nullable: bool = False) -> Column:
    return col(name, "bytea", comment, nullable=nullable)


def revision_col() -> Column:
    return bigint_col("revision", "CAS修订号：每次受控更新必须精确递增一，初始为零。", default="0")


def check(name: str, expression: str, comment: str) -> Constraint:
    return Constraint(name, "CHECK", expression, comment)


def unique(name: str, columns: Sequence[str], comment: str) -> Constraint:
    return Constraint(name, "UNIQUE", ", ".join(columns), comment)


def index(name: str, columns: Sequence[str], comment: str, *, unique_: bool = False, where: str | None = None) -> Index:
    return Index(name, tuple(columns), comment, unique_, where)


def fk(
    name: str,
    columns: Sequence[str],
    parent_schema: str,
    parent_table: str,
    parent_columns: Sequence[str],
    comment: str,
    *,
    deferrable: bool = False,
    initially_deferred: bool = False,
) -> ForeignKey:
    return ForeignKey(
        name=name,
        columns=tuple(columns),
        parent_schema=parent_schema,
        parent_table=parent_table,
        parent_columns=tuple(parent_columns),
        comment=comment,
        deferrable=deferrable,
        initially_deferred=initially_deferred,
    )


def tenant_fk(schema: str, table: str) -> ForeignKey:
    return fk(
        f"fk_{table}__tenant",
        ("tenant_id",),
        "identity",
        "tenant",
        ("tenant_id",),
        "租户边界：该记录必须属于一个已存在的租户。",
    )


def entity_fk(
    child_table: str,
    field: str,
    parent_schema: str,
    parent_table: str,
    parent_id: str,
    comment: str,
    *,
    suffix: str | None = None,
    deferrable: bool = False,
    initially_deferred: bool = False,
) -> ForeignKey:
    return fk(
        f"fk_{child_table}__{suffix or parent_table}",
        ("tenant_id", field),
        parent_schema,
        parent_table,
        ("tenant_id", parent_id),
        comment,
        deferrable=deferrable,
        initially_deferred=initially_deferred,
    )


def typed_ref(prefix: str, comment: str, *, optional: bool = False) -> TypedReference:
    columns = (
        code_col(f"{prefix}_type", f"{comment}的静态注册类型。", nullable=optional),
        uuid_col(f"{prefix}_id", f"{comment}在所属租户内的准确标识。", nullable=optional),
        bigint_col(f"{prefix}_revision", f"{comment}的准确修订号；按哈希冻结时为空。", nullable=True),
        digest_col(f"{prefix}_hash", f"{comment}的准确规范摘要；按修订冻结时为空。", nullable=True),
    )
    return TypedReference(prefix, columns, True, optional, comment)


def typed_ref_check(table_name: str, ref: TypedReference) -> Constraint:
    p = ref.prefix
    complete = (
        f"{p}_type IS NOT NULL AND {p}_id IS NOT NULL AND "
        f"(({p}_revision IS NOT NULL AND {p}_revision >= 0 AND {p}_hash IS NULL) OR "
        f"({p}_revision IS NULL AND {p}_hash IS NOT NULL))"
    )
    expression = f"(({complete}) OR ({p}_type IS NULL AND {p}_id IS NULL AND {p}_revision IS NULL AND {p}_hash IS NULL))" if ref.optional else f"({complete})"
    return check(
        f"ck_{table_name}__{p}_exact",
        expression,
        f"准确引用：{ref.comment}必须完整给出类型、标识以及修订号或摘要二者之一。",
    )


def enum_check(table_name: str, column: str, values: Sequence[str], comment: str) -> Constraint:
    values_sql = ", ".join(f"'{value}'" for value in values)
    return check(f"ck_{table_name}__{column}", f"{column} IN ({values_sql})", comment)


def nonnegative_check(table_name: str, column: str, comment: str) -> Constraint:
    return check(f"ck_{table_name}__{column}_nonnegative", f"{column} >= 0", comment)


def digest_checks(table_name: str, columns: Iterable[Column]) -> Tuple[Constraint, ...]:
    return tuple(
        check(
            f"ck_{table_name}__{column.name}_length",
            f"octet_length({column.name}) = {column.byte_length}",
            f"摘要格式：{column.name}必须保存{column.byte_length}字节的规范二进制值。",
        )
        for column in columns
        if column.byte_length is not None
    )


def tenant_table(
    schema: str,
    name: str,
    id_column: str,
    comment: str,
    business_columns: Sequence[Column],
    *,
    constraints: Sequence[Constraint] = (),
    indexes: Sequence[Index] = (),
    foreign_keys: Sequence[ForeignKey] = (),
    typed_references: Sequence[TypedReference] = (),
    update_policy: str = "IMMUTABLE",
    mutable_columns: Sequence[str] = (),
    write_once_columns: Sequence[str] = (),
    state_column: str | None = None,
    initial_state: str | None = None,
    state_transitions: Sequence[tuple[str, str]] = (),
) -> Table:
    refs = tuple(typed_references)
    columns = (
        uuid_col("tenant_id", "租户标识：复合主键和所有租户内关联的第一列。"),
        uuid_col(id_column, f"{comment.split('：', 1)[0]}标识：由应用生成的UUIDv7。"),
        *business_columns,
        *(column for ref in refs for column in ref.columns),
    )
    all_constraints = (
        *constraints,
        *(typed_ref_check(name, ref) for ref in refs),
        *digest_checks(name, columns),
    )
    return Table(
        schema=schema,
        name=name,
        id_column=id_column,
        columns=tuple(columns),
        primary_key=("tenant_id", id_column),
        primary_key_comment=f"主键：在租户内唯一标识一条{name}记录。",
        comment=comment,
        constraints=tuple(all_constraints),
        indexes=tuple(indexes),
        foreign_keys=(tenant_fk(schema, name), *foreign_keys),
        typed_references=refs,
        update_policy=update_policy,
        mutable_columns=tuple(mutable_columns),
        write_once_columns=tuple(write_once_columns),
        state_column=state_column,
        initial_state=initial_state,
        state_transitions=tuple(state_transitions),
    )
