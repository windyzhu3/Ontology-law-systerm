"""Tenant role catalogue. Default codes are seed data, never permission templates."""
from dataclasses import replace
from ..helpers import *
from ..model import ContractEvolution
from ..render import _render_table, _render_foreign_key

ROLE=tenant_table('identity','appointment_role','appointment_role_id','租户岗位目录；名称和状态可CAS修改，代码不变，不自动授予权限。',(
 code_col('role_code','租户内唯一稳定岗位代码。'),text_col('display_name','岗位显示名称。'),
 code_col('state','ACTIVE或INACTIVE；停用仅禁止新增任职。'),time_col('created_at','目录创建时间。'),revision_col()),
 constraints=(unique('uq_appointment_role__code',('tenant_id','role_code'),'代码在租户内唯一。'),
 enum_check('appointment_role','state',('ACTIVE','INACTIVE'),'岗位可停用和恢复。'),
 check('ck_appointment_role__code_format',"role_code ~ '^[A-Z][A-Z0-9_]{0,63}$'",'受约束岗位代码。'),
 check('ck_appointment_role__name',"char_length(btrim(display_name)) BETWEEN 1 AND 200 AND display_name !~ '[[:cntrl:]]'",'安全显示名称。'),
 check('ck_appointment_role__revision','revision BETWEEN 0 AND 9007199254740991','JSON安全修订号。')),
 update_policy='CONTROLLED',mutable_columns=('display_name','state','revision'),state_column='state',initial_state='ACTIVE',state_transitions=(('ACTIVE','INACTIVE'),('INACTIVE','ACTIVE')))
LINK=fk('fk_appointment__role',('tenant_id','role_code'),'identity','appointment_role',('tenant_id','role_code'),'任职关联同租户岗位；停用不改变旧任职。')
def apply_evolution(schemas):
 return tuple(replace(s,tables=(*tuple(replace(t,foreign_keys=(*t.foreign_keys,LINK),columns=tuple(replace(c,comment='岗位代码：关联同租户可配置目录，创建后不变，不授予权限。') if c.name=='role_code' else c for c in t.columns)) if t.schema=='identity' and t.name=='appointment' else t for t in s.tables),*((ROLE,) if s.name=='identity' else ()))) for s in schemas)

def render_sql(before,after):
 sql=_render_table(ROLE)+'\n'+ '\n'.join(_render_foreign_key(ROLE,f) for f in ROLE.foreign_keys)+'\n'+SEED
 appointment=next(t for s in after for t in s.tables if t.schema=='identity' and t.name=='appointment')
 sql+=_render_foreign_key(appointment,LINK)+'\n'
 sql+="COMMENT ON COLUMN identity.appointment.role_code IS '岗位代码：关联同租户可配置目录，创建后不变，不授予权限。';\n"
 sql+=GUARD
 sql+="""DO $v1070$ BEGIN
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v21',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v20';
 IF NOT FOUND THEN RAISE EXCEPTION 'V1070 requires 52-plus-2-r2-v20' USING ERRCODE='55000'; END IF;
 END;$v1070$;
"""
 return sql

SEED="""
CREATE FUNCTION identity.seed_appointment_roles(target_tenant uuid) RETURNS void
LANGUAGE sql SECURITY INVOKER SET search_path=pg_catalog AS $seed$
 INSERT INTO identity.appointment_role(tenant_id,appointment_role_id,role_code,display_name,state,created_at,revision)
 SELECT target_tenant,uuidv7(),code,label,'ACTIVE',clock_timestamp(),0 FROM (VALUES
 ('IDENTITY_ADMIN','身份管理员'),('INTAKE_OPERATOR','线索接入经办'),('ROUTING_SUPERVISOR','路由主管'),('CONTACT_OPERATOR','首联经办'),
 ('SALES_REPRESENTATIVE','销售'),('SALES_MANAGER','销售主管'),('FINANCE_OPERATOR','财务人员'),('CASE_ADMINISTRATOR','案管员')) defaults(code,label)
 ON CONFLICT (tenant_id,role_code) DO NOTHING;
$seed$;
COMMENT ON FUNCTION identity.seed_appointment_roles(uuid) IS '初始化默认岗位数据；不改变既有名称或状态，不建立任何授权。';
REVOKE ALL ON FUNCTION identity.seed_appointment_roles(uuid) FROM PUBLIC;
SELECT identity.seed_appointment_roles(tenant_id) FROM identity.tenant;
INSERT INTO identity.appointment_role(tenant_id,appointment_role_id,role_code,display_name,state,created_at,revision)
 SELECT tenant_id,uuidv7(),role_code,role_code,'ACTIVE',clock_timestamp(),0 FROM (SELECT DISTINCT tenant_id,role_code FROM identity.appointment) old
 ON CONFLICT (tenant_id,role_code) DO NOTHING;
"""
GUARD="""
CREATE TRIGGER trg_appointment_role__mutation_guard BEFORE UPDATE OR DELETE ON identity.appointment_role
 FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('display_name,state,revision','','state','ACTIVE>INACTIVE,INACTIVE>ACTIVE','CONTROLLED');
COMMENT ON TRIGGER trg_appointment_role__mutation_guard ON identity.appointment_role IS '冻结代码和租户；准确CAS修订；禁止删除；停用可恢复。';
CREATE FUNCTION identity.fn_check_new_appointment_role() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $role$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM identity.appointment_role WHERE tenant_id=NEW.tenant_id AND role_code=NEW.role_code AND state='ACTIVE') THEN
  RAISE EXCEPTION 'new appointment requires an active tenant role' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;$role$;
COMMENT ON FUNCTION identity.fn_check_new_appointment_role() IS '新增任职核对同租户启用岗位，不影响已有任职。';
REVOKE ALL ON FUNCTION identity.fn_check_new_appointment_role() FROM PUBLIC;
CREATE TRIGGER trg_appointment__active_role BEFORE INSERT ON identity.appointment FOR EACH ROW EXECUTE FUNCTION identity.fn_check_new_appointment_role();
COMMENT ON TRIGGER trg_appointment__active_role ON identity.appointment IS '仅新增任职核对启用岗位，已有任职状态推进不受目录停用影响。';
REVOKE ALL ON identity.appointment_role FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT,INSERT ON identity.appointment_role TO ${app_command_role};
GRANT UPDATE(display_name,state,revision) ON identity.appointment_role TO ${app_command_role};
GRANT SELECT ON identity.appointment_role TO ${app_query_role};
GRANT EXECUTE ON FUNCTION identity.seed_appointment_roles(uuid) TO ${app_command_role};
"""
EVOLUTION=ContractEvolution(version=1070,migration_name='V1070__configurable_appointment_roles.sql',contract_version='52-plus-2-r2-v21',apply=apply_evolution,render_sql=render_sql)
