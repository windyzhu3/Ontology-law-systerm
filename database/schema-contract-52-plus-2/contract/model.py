from __future__ import annotations

from dataclasses import dataclass
from typing import Optional, Tuple


@dataclass(frozen=True)
class Column:
    name: str
    sql_type: str
    nullable: bool
    comment: str
    default: Optional[str] = None
    byte_length: Optional[int] = None


@dataclass(frozen=True)
class Constraint:
    name: str
    kind: str
    expression: str
    comment: str


@dataclass(frozen=True)
class Index:
    name: str
    columns: Tuple[str, ...]
    comment: str
    unique: bool = False
    where: Optional[str] = None


@dataclass(frozen=True)
class ForeignKey:
    name: str
    columns: Tuple[str, ...]
    parent_schema: str
    parent_table: str
    parent_columns: Tuple[str, ...]
    comment: str
    on_update: str = "NO ACTION"
    on_delete: str = "NO ACTION"
    deferrable: bool = False
    initially_deferred: bool = False


@dataclass(frozen=True)
class TypedReference:
    prefix: str
    columns: Tuple[Column, ...]
    exact_selector_check: bool
    optional: bool
    comment: str


@dataclass(frozen=True)
class Table:
    schema: str
    name: str
    id_column: str
    columns: Tuple[Column, ...]
    primary_key: Tuple[str, ...]
    primary_key_comment: str
    comment: str
    constraints: Tuple[Constraint, ...] = ()
    indexes: Tuple[Index, ...] = ()
    foreign_keys: Tuple[ForeignKey, ...] = ()
    typed_references: Tuple[TypedReference, ...] = ()
    update_policy: str = "IMMUTABLE"
    mutable_columns: Tuple[str, ...] = ()
    write_once_columns: Tuple[str, ...] = ()
    state_column: Optional[str] = None
    initial_state: Optional[str] = None
    state_transitions: Tuple[Tuple[str, str], ...] = ()


@dataclass(frozen=True)
class Schema:
    name: str
    comment: str
    tables: Tuple[Table, ...]
