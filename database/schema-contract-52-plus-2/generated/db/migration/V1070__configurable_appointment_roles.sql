CREATE TABLE identity.appointment_role (
    tenant_id uuid NOT NULL,
    appointment_role_id uuid NOT NULL,
    role_code varchar(64) NOT NULL,
    display_name text NOT NULL,
    state varchar(64) NOT NULL,
    created_at timestamptz(6) NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    CONSTRAINT pk_appointment_role PRIMARY KEY (tenant_id, appointment_role_id),
    CONSTRAINT uq_appointment_role__code UNIQUE (tenant_id, role_code),
    CONSTRAINT ck_appointment_role__state CHECK (state IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_appointment_role__code_format CHECK (role_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_appointment_role__name CHECK (char_length(btrim(display_name)) BETWEEN 1 AND 200 AND display_name !~ '[[:cntrl:]]'),
    CONSTRAINT ck_appointment_role__revision CHECK (revision BETWEEN 0 AND 9007199254740991)
);

COMMENT ON TABLE identity.appointment_role IS 'Fact Owner：IdentityRuntime；租户岗位目录；名称和状态可CAS修改，代码不变，不自动授予权限。';
COMMENT ON CONSTRAINT pk_appointment_role ON identity.appointment_role IS '主键：在租户内唯一标识一条appointment_role记录。';
COMMENT ON INDEX identity.pk_appointment_role IS '主键：在租户内唯一标识一条appointment_role记录。';
COMMENT ON COLUMN identity.appointment_role.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN identity.appointment_role.appointment_role_id IS '租户岗位目录；名称和状态可CAS修改，代码不变，不自动授予权限。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN identity.appointment_role.role_code IS '租户内唯一稳定岗位代码。';
COMMENT ON COLUMN identity.appointment_role.display_name IS '岗位显示名称。';
COMMENT ON COLUMN identity.appointment_role.state IS 'ACTIVE或INACTIVE；停用仅禁止新增任职。';
COMMENT ON COLUMN identity.appointment_role.created_at IS '目录创建时间。';
COMMENT ON COLUMN identity.appointment_role.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON CONSTRAINT uq_appointment_role__code ON identity.appointment_role IS '代码在租户内唯一。';
COMMENT ON INDEX identity.uq_appointment_role__code IS '代码在租户内唯一。';
COMMENT ON CONSTRAINT ck_appointment_role__state ON identity.appointment_role IS '岗位可停用和恢复。';
COMMENT ON CONSTRAINT ck_appointment_role__code_format ON identity.appointment_role IS '受约束岗位代码。';
COMMENT ON CONSTRAINT ck_appointment_role__name ON identity.appointment_role IS '安全显示名称。';
COMMENT ON CONSTRAINT ck_appointment_role__revision ON identity.appointment_role IS 'JSON安全修订号。';
ALTER TABLE identity.appointment_role
    ADD CONSTRAINT fk_appointment_role__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_appointment_role__tenant ON identity.appointment_role IS '租户边界：该记录必须属于一个已存在的租户。';

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
ALTER TABLE identity.appointment
    ADD CONSTRAINT fk_appointment__role
    FOREIGN KEY (tenant_id, role_code)
    REFERENCES identity.appointment_role (tenant_id, role_code)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_appointment__role ON identity.appointment IS '任职关联同租户岗位；停用不改变旧任职。';
COMMENT ON COLUMN identity.appointment.role_code IS '岗位代码：关联同租户可配置目录，创建后不变，不授予权限。';

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
DO $v1070$ BEGIN
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v21',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v20';
 IF NOT FOUND THEN RAISE EXCEPTION 'V1070 requires 52-plus-2-r2-v20' USING ERRCODE='55000'; END IF;
 END;$v1070$;
