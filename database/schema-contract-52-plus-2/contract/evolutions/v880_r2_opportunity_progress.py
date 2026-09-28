"""Protected R2 progress and one causally distinct successor per completed responsibility."""
from dataclasses import replace
from ..helpers import encrypted_col, uuid_col, check, unique, entity_fk
from ..model import ContractEvolution
from ..render import _column_sql, _constraint_sql, _render_foreign_key, _sql_comment

BODY=encrypted_col('progress_body_ciphertext','R2进展正文密文：绑定租户、商机与进展身份；历史无正文记录为空，创建后不可变。',nullable=True)
PREDECESSOR=uuid_col('predecessor_task_occurrence_id','前序责任身份：R2商机后继跟进绑定已完成责任；初始责任与历史记录为空，创建后不可变。',nullable=True)
CONSTRAINTS=(
    unique('uq_task_occurrence__progress_successor',('tenant_id','predecessor_task_occurrence_id'),'商机跟进因果幂等：同一前序责任最多生成一个后继责任。'),
    check('ck_task_occurrence__progress_predecessor',"predecessor_task_occurrence_id IS NULL OR (business_purpose_code = 'PROGRESS_OPPORTUNITY' AND predecessor_task_occurrence_id <> task_occurrence_id)",'后继关系限定为商机推进且不得自指。'),
)
PREDECESSOR_FK=entity_fk('task_occurrence','predecessor_task_occurrence_id','responsibility','task_occurrence','task_occurrence_id','前序责任必须存在于同一租户。',suffix='progress_predecessor')
PROGRESS_CHECK=check('ck_opportunity_progress__protected_body',"(progress_contract_code = 'R2_OPPORTUNITY_PROGRESS_V1' AND progress_contract_version = 1 AND progress_body_ciphertext IS NOT NULL AND octet_length(progress_body_ciphertext) >= 29) OR (progress_contract_code <> 'R2_OPPORTUNITY_PROGRESS_V1' AND progress_body_ciphertext IS NULL)",'R2受保护正文仅由准确进展合同写入；历史合同不混用此正文。')

def apply_evolution(schemas):
    def evolve(table):
        if (table.schema,table.name)==('opportunity','opportunity_progress'):
            columns=tuple(replace(c,comment='进展事实摘要：历史合同覆盖类型与准确来源；R2_OPPORTUNITY_PROGRESS_V1覆盖规范受保护正文及准确业务身份。') if c.name=='progress_digest' else c for c in table.columns)
            return replace(table,columns=(*columns,BODY),constraints=(*table.constraints,PROGRESS_CHECK))
        if (table.schema,table.name)==('responsibility','task_occurrence'):
            return replace(table,columns=(*table.columns,PREDECESSOR),constraints=(*table.constraints,*CONSTRAINTS),foreign_keys=(*table.foreign_keys,PREDECESSOR_FK))
        return table
    return tuple(replace(s,tables=tuple(evolve(t) for t in s.tables)) for s in schemas)

def render_sql(base_schemas,evolved_schemas):
    tables={f'{s.name}.{t.name}':t for s in evolved_schemas for t in s.tables}
    lines=['-- R2 protected progress; historical V001-V870 remain byte-identical.']
    for name,column in [('opportunity.opportunity_progress',BODY),('responsibility.task_occurrence',PREDECESSOR)]:
        lines += [f'ALTER TABLE {name} ADD COLUMN {_column_sql(column)};',f"COMMENT ON COLUMN {name}.{column.name} IS '{_sql_comment(column.comment)}';",f'GRANT SELECT ({column.name}) ON {name} TO ${{app_query_role}};']
    for name,cs in [('opportunity.opportunity_progress',(PROGRESS_CHECK,)),('responsibility.task_occurrence',CONSTRAINTS)]:
        for c in cs:
            lines += [f'ALTER TABLE {name} ADD {_constraint_sql(c)};',f"COMMENT ON CONSTRAINT {c.name} ON {name} IS '{_sql_comment(c.comment)}';"]
    lines.append(f"COMMENT ON INDEX responsibility.{CONSTRAINTS[0].name} IS '{_sql_comment(CONSTRAINTS[0].comment)}';")
    lines.append(_render_foreign_key(tables['responsibility.task_occurrence'],PREDECESSOR_FK))
    digest=next(c for c in tables['opportunity.opportunity_progress'].columns if c.name=='progress_digest')
    lines.append(f"COMMENT ON COLUMN opportunity.opportunity_progress.progress_digest IS '{_sql_comment(digest.comment)}';")
    lines.append("""DO $v880_contract_version$
BEGIN
    UPDATE platform_meta.deployment_state
    SET schema_contract_version = '52-plus-2-r2-v2', revision = revision + 1, changed_at = clock_timestamp()
    WHERE deployment_state_key = 'PRIMARY' AND schema_contract_version = '52-plus-2-r2-v1';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'V880 requires schema contract 52-plus-2-r2-v1' USING ERRCODE = '55000';
    END IF;
END;
$v880_contract_version$;
""")
    return '\n'.join(lines)

EVOLUTION=ContractEvolution(version=880,migration_name='V880__r2_opportunity_progress.sql',contract_version='52-plus-2-r2-v2',apply=apply_evolution,render_sql=render_sql)
