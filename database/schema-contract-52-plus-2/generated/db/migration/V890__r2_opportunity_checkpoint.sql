CREATE TABLE platform_meta.r2_opportunity_checkpoint (
    tenant_id uuid NOT NULL,
    principal_id uuid NOT NULL,
    appointment_id uuid NOT NULL,
    scan_kind varchar(16) NOT NULL,
    checkpoint_body bytea NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    updated_at timestamptz(6) DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT pk_r2_opportunity_checkpoint PRIMARY KEY (tenant_id, principal_id, appointment_id, scan_kind),
    CONSTRAINT ck_r2_opportunity_checkpoint__kind CHECK (scan_kind IN ('INITIAL', 'DUE')),
    CONSTRAINT ck_r2_opportunity_checkpoint__body CHECK (octet_length(checkpoint_body) BETWEEN 1 AND 65536),
    CONSTRAINT ck_r2_opportunity_checkpoint__revision CHECK (revision BETWEEN 0 AND 9007199254740991)
);

COMMENT ON TABLE platform_meta.r2_opportunity_checkpoint IS 'Fact Owner：R2OpportunityCheckpointStore；R2 Worker技术检查点；仅用于跨重启调度恢复，不构成业务事实或通用任务队列。';
COMMENT ON CONSTRAINT pk_r2_opportunity_checkpoint ON platform_meta.r2_opportunity_checkpoint IS '技术检查点身份：每个服务绑定和扫描种类唯一。';
COMMENT ON INDEX platform_meta.pk_r2_opportunity_checkpoint IS '技术检查点身份：每个服务绑定和扫描种类唯一。';
COMMENT ON COLUMN platform_meta.r2_opportunity_checkpoint.tenant_id IS '租户身份：仅技术调度键，不替代应用授权。';
COMMENT ON COLUMN platform_meta.r2_opportunity_checkpoint.principal_id IS '服务主体身份：冻结当前配置的服务绑定。';
COMMENT ON COLUMN platform_meta.r2_opportunity_checkpoint.appointment_id IS '服务任职身份：与主体和租户共同隔离检查点。';
COMMENT ON COLUMN platform_meta.r2_opportunity_checkpoint.scan_kind IS '具名扫描种类：INITIAL承接或DUE到期恢复。';
COMMENT ON COLUMN platform_meta.r2_opportunity_checkpoint.checkpoint_body IS '版本化技术检查点：仅候选准确引用、游标、重试与诊断；不得保存客户正文或业务事实。';
COMMENT ON COLUMN platform_meta.r2_opportunity_checkpoint.revision IS '并发修订号：初始为零，每次写入准确递增一。';
COMMENT ON COLUMN platform_meta.r2_opportunity_checkpoint.updated_at IS '数据库更新时间：每次写入由数据库时钟设置。';
COMMENT ON CONSTRAINT ck_r2_opportunity_checkpoint__kind ON platform_meta.r2_opportunity_checkpoint IS '种类仅允许两种已注册商机维护扫描。';
COMMENT ON CONSTRAINT ck_r2_opportunity_checkpoint__body ON platform_meta.r2_opportunity_checkpoint IS '技术正文不能为空且不得超过64KiB。';
COMMENT ON CONSTRAINT ck_r2_opportunity_checkpoint__revision ON platform_meta.r2_opportunity_checkpoint IS '修订号必须是JSON安全非负整数。';

CREATE FUNCTION platform_meta.fn_guard_r2_opportunity_checkpoint() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path = pg_catalog, pg_temp AS $guard$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'R2 checkpoint deletion is forbidden' USING ERRCODE = '23514';
    ELSIF TG_OP = 'INSERT' THEN
        IF NEW.revision <> 0 THEN
            RAISE EXCEPTION 'R2 checkpoint initial revision must be zero' USING ERRCODE = '23514';
        END IF;
    ELSIF ROW(NEW.tenant_id, NEW.principal_id, NEW.appointment_id, NEW.scan_kind)
          IS DISTINCT FROM ROW(OLD.tenant_id, OLD.principal_id, OLD.appointment_id, OLD.scan_kind)
          OR OLD.revision >= 9007199254740991 OR NEW.revision <> OLD.revision + 1 THEN
        RAISE EXCEPTION 'R2 checkpoint identity or revision changed illegally' USING ERRCODE = '23514';
    END IF;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END;
$guard$;
COMMENT ON FUNCTION platform_meta.fn_guard_r2_opportunity_checkpoint() IS '技术检查点守卫：冻结身份、准确CAS修订和数据库时间，不执行跨域业务。';
REVOKE ALL ON FUNCTION platform_meta.fn_guard_r2_opportunity_checkpoint() FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
CREATE TRIGGER trg_r2_opportunity_checkpoint__mutation_guard
BEFORE INSERT OR UPDATE OR DELETE ON platform_meta.r2_opportunity_checkpoint
FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_guard_r2_opportunity_checkpoint();
COMMENT ON TRIGGER trg_r2_opportunity_checkpoint__mutation_guard ON platform_meta.r2_opportunity_checkpoint IS '写入检查点时强制检查身份和修订号，禁止删除。';

REVOKE ALL ON platform_meta.r2_opportunity_checkpoint FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON platform_meta.r2_opportunity_checkpoint TO ${app_worker_role};
GRANT UPDATE (checkpoint_body, revision, updated_at) ON platform_meta.r2_opportunity_checkpoint TO ${app_worker_role};

DO $v890_validation$
DECLARE actual_count bigint; role_name text; column_name text;
BEGIN
    SELECT count(*) INTO actual_count FROM pg_catalog.pg_tables
    WHERE schemaname IN ('identity','audit','responsibility','execution','external_action','evidence','party','lead','opportunity','conflict','contract','transfer','platform_meta');
    IF actual_count <> 55 THEN
        RAISE EXCEPTION 'V890 expected 55 managed tables, found %', actual_count;
    END IF;
    SELECT count(*) INTO actual_count FROM pg_catalog.pg_tables WHERE schemaname = 'platform_meta';
    IF actual_count <> 3 OR EXISTS (SELECT 1 FROM pg_catalog.pg_tables WHERE schemaname='platform_meta'
        AND tablename NOT IN ('deployment_state','flyway_schema_history','r2_opportunity_checkpoint')) THEN
        RAISE EXCEPTION 'V890 requires exactly three named technical tables';
    END IF;
    IF NOT has_table_privilege('${app_worker_role}','platform_meta.r2_opportunity_checkpoint','SELECT')
       OR NOT has_table_privilege('${app_worker_role}','platform_meta.r2_opportunity_checkpoint','INSERT')
       OR has_table_privilege('${app_worker_role}','platform_meta.r2_opportunity_checkpoint','UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') THEN
        RAISE EXCEPTION 'V890 Worker checkpoint table privileges differ';
    END IF;
    FOREACH column_name IN ARRAY ARRAY['tenant_id','principal_id','appointment_id','scan_kind','checkpoint_body','revision','updated_at'] LOOP
        IF has_column_privilege('${app_worker_role}','platform_meta.r2_opportunity_checkpoint',column_name,'UPDATE')
           IS DISTINCT FROM (column_name IN ('checkpoint_body','revision','updated_at')) THEN
            RAISE EXCEPTION 'V890 Worker checkpoint column privilege differs';
        END IF;
        FOREACH role_name IN ARRAY ARRAY['${app_command_role}','${app_query_role}','${audit_append_role}'] LOOP
            IF has_column_privilege(role_name,'platform_meta.r2_opportunity_checkpoint',column_name,'SELECT,INSERT,UPDATE,REFERENCES') THEN
                RAISE EXCEPTION 'V890 non-Worker checkpoint column capability expanded';
            END IF;
        END LOOP;
    END LOOP;
    FOREACH role_name IN ARRAY ARRAY['${app_command_role}','${app_query_role}','${audit_append_role}'] LOOP
        IF has_table_privilege(role_name,'platform_meta.r2_opportunity_checkpoint','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') THEN
            RAISE EXCEPTION 'V890 non-Worker checkpoint table capability expanded';
        END IF;
    END LOOP;
    IF EXISTS (SELECT 1 FROM pg_catalog.pg_class c CROSS JOIN LATERAL aclexplode(c.relacl) acl
        WHERE c.oid='platform_meta.r2_opportunity_checkpoint'::regclass AND (acl.grantee=0 OR acl.is_grantable)) THEN
        RAISE EXCEPTION 'V890 checkpoint PUBLIC or grant-option privilege forbidden';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_trigger WHERE tgrelid='platform_meta.r2_opportunity_checkpoint'::regclass
        AND tgname='trg_r2_opportunity_checkpoint__mutation_guard' AND NOT tgisinternal AND tgenabled='O') THEN
        RAISE EXCEPTION 'V890 checkpoint mutation guard missing';
    END IF;
    UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v3', revision=revision+1, changed_at=clock_timestamp()
    WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v2';
    IF NOT FOUND THEN RAISE EXCEPTION 'V890 requires schema contract 52-plus-2-r2-v2' USING ERRCODE='55000'; END IF;
END;
$v890_validation$;
