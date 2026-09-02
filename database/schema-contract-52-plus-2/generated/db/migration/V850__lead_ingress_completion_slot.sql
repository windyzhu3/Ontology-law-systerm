-- V850：只向冻结的v1合同追加Lead接入补全槽；V001至V840禁止改写。

ALTER TABLE lead.lead
    ADD COLUMN ingress_completion_phone_ciphertext bytea,
    ADD COLUMN ingress_completion_phone_hmac bytea,
    ADD COLUMN ingress_completion_email_ciphertext bytea,
    ADD COLUMN ingress_completion_email_hmac bytea,
    ADD COLUMN ingress_completion_source_code varchar(64),
    ADD COLUMN ingress_completion_source_summary_ciphertext bytea,
    ADD COLUMN ingress_completed_by_appointment_id uuid,
    ADD COLUMN ingress_completed_at timestamptz(6),
    ADD COLUMN ingress_completion_digest bytea;

COMMENT ON COLUMN lead.lead.ingress_completion_phone_ciphertext IS '补全电话密文：仅在原始电话与邮箱均缺失时由完成接入命令一次写入；缺失时为空。';
COMMENT ON COLUMN lead.lead.ingress_completion_phone_hmac IS '补全电话HMAC：与补全电话密文配对的32字节受控精确匹配值；缺失时为空。';
COMMENT ON COLUMN lead.lead.ingress_completion_email_ciphertext IS '补全邮箱密文：仅在原始电话与邮箱均缺失时由完成接入命令一次写入；缺失时为空。';
COMMENT ON COLUMN lead.lead.ingress_completion_email_hmac IS '补全邮箱HMAC：与补全邮箱密文配对的32字节受控精确匹配值；缺失时为空。';
COMMENT ON COLUMN lead.lead.ingress_completion_source_code IS '补全来源代码：标识静态注册的补全来源类型，不保存凭据或自由文本。';
COMMENT ON COLUMN lead.lead.ingress_completion_source_summary_ciphertext IS '补全来源说明密文：保存最小必要的受保护来源说明，不写入审计摘要或事件载荷。';
COMMENT ON COLUMN lead.lead.ingress_completed_by_appointment_id IS '补全执行任命：指向同租户执行完成接入命令的准确Appointment。';
COMMENT ON COLUMN lead.lead.ingress_completed_at IS '补全完成时间：完成接入命令写入整槽的带时区微秒精度时间。';
COMMENT ON COLUMN lead.lead.ingress_completion_digest IS '补全完成摘要：覆盖规范化补全值、来源、执行任命与完成时间的32字节摘要。';

ALTER TABLE lead.lead
    ADD CONSTRAINT ck_lead__ingress_completion_phone_pair CHECK ((ingress_completion_phone_ciphertext IS NULL AND ingress_completion_phone_hmac IS NULL) OR (ingress_completion_phone_ciphertext IS NOT NULL AND ingress_completion_phone_hmac IS NOT NULL));
COMMENT ON CONSTRAINT ck_lead__ingress_completion_phone_pair ON lead.lead IS '补全电话配对：电话密文与HMAC必须同时存在或同时为空。';

ALTER TABLE lead.lead
    ADD CONSTRAINT ck_lead__ingress_completion_email_pair CHECK ((ingress_completion_email_ciphertext IS NULL AND ingress_completion_email_hmac IS NULL) OR (ingress_completion_email_ciphertext IS NOT NULL AND ingress_completion_email_hmac IS NOT NULL));
COMMENT ON CONSTRAINT ck_lead__ingress_completion_email_pair ON lead.lead IS '补全邮箱配对：邮箱密文与HMAC必须同时存在或同时为空。';

ALTER TABLE lead.lead
    ADD CONSTRAINT ck_lead__ingress_completion_slot CHECK ((ingress_completion_phone_ciphertext IS NULL AND ingress_completion_phone_hmac IS NULL AND ingress_completion_email_ciphertext IS NULL AND ingress_completion_email_hmac IS NULL AND ingress_completion_source_code IS NULL AND ingress_completion_source_summary_ciphertext IS NULL AND ingress_completed_by_appointment_id IS NULL AND ingress_completed_at IS NULL AND ingress_completion_digest IS NULL) OR (captured_phone_ciphertext IS NULL AND captured_phone_hmac IS NULL AND captured_email_ciphertext IS NULL AND captured_email_hmac IS NULL AND (ingress_completion_phone_ciphertext IS NOT NULL OR ingress_completion_email_ciphertext IS NOT NULL) AND ingress_completion_source_code IS NOT NULL AND ingress_completion_source_summary_ciphertext IS NOT NULL AND ingress_completed_by_appointment_id IS NOT NULL AND ingress_completed_at IS NOT NULL AND ingress_completion_digest IS NOT NULL));
COMMENT ON CONSTRAINT ck_lead__ingress_completion_slot ON lead.lead IS '补全槽完整性：整槽必须全空，或在原始电话与邮箱均缺失时一次写入至少一组联系方式及全部来源元数据。';

ALTER TABLE lead.lead
    ADD CONSTRAINT ck_lead__ingress_completion_phone_hmac_length CHECK (octet_length(ingress_completion_phone_hmac) = 32);
COMMENT ON CONSTRAINT ck_lead__ingress_completion_phone_hmac_length ON lead.lead IS '摘要格式：ingress_completion_phone_hmac必须保存32字节的规范二进制值。';

ALTER TABLE lead.lead
    ADD CONSTRAINT ck_lead__ingress_completion_email_hmac_length CHECK (octet_length(ingress_completion_email_hmac) = 32);
COMMENT ON CONSTRAINT ck_lead__ingress_completion_email_hmac_length ON lead.lead IS '摘要格式：ingress_completion_email_hmac必须保存32字节的规范二进制值。';

ALTER TABLE lead.lead
    ADD CONSTRAINT ck_lead__ingress_completion_digest_length CHECK (octet_length(ingress_completion_digest) = 32);
COMMENT ON CONSTRAINT ck_lead__ingress_completion_digest_length ON lead.lead IS '摘要格式：ingress_completion_digest必须保存32字节的规范二进制值。';

ALTER TABLE lead.lead
    ADD CONSTRAINT fk_lead__ingress_completed_by_appointment
    FOREIGN KEY (tenant_id, ingress_completed_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_lead__ingress_completed_by_appointment ON lead.lead IS '补全执行任命关系：完成接入的Appointment必须存在于同一租户。';

CREATE FUNCTION platform_meta.fn_guard_lead_ingress_completion_slot()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $lead_ingress_completion_slot$
BEGIN
    IF OLD.ingress_completion_digest IS NOT NULL
       AND ROW(
           OLD.ingress_completion_phone_ciphertext,
           OLD.ingress_completion_phone_hmac,
           OLD.ingress_completion_email_ciphertext,
           OLD.ingress_completion_email_hmac,
           OLD.ingress_completion_source_code,
           OLD.ingress_completion_source_summary_ciphertext,
           OLD.ingress_completed_by_appointment_id,
           OLD.ingress_completed_at,
           OLD.ingress_completion_digest
       ) IS DISTINCT FROM ROW(
           NEW.ingress_completion_phone_ciphertext,
           NEW.ingress_completion_phone_hmac,
           NEW.ingress_completion_email_ciphertext,
           NEW.ingress_completion_email_hmac,
           NEW.ingress_completion_source_code,
           NEW.ingress_completion_source_summary_ciphertext,
           NEW.ingress_completed_by_appointment_id,
           NEW.ingress_completed_at,
           NEW.ingress_completion_digest
       ) THEN
        RAISE EXCEPTION 'ingress completion slot is already sealed on %.%',
            TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$lead_ingress_completion_slot$;
COMMENT ON FUNCTION platform_meta.fn_guard_lead_ingress_completion_slot() IS
    'Lead补全槽封存守卫：整槽首次完成后，拒绝覆盖、清空或补写先前为空的联系方式。';

CREATE TRIGGER trg_lead__ingress_completion_slot
BEFORE UPDATE ON lead.lead
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_lead_ingress_completion_slot();
COMMENT ON TRIGGER trg_lead__ingress_completion_slot ON lead.lead IS
    'Lead补全槽封存保护：email-only或phone-only完成后也不得二次补写另一组联系方式。';
REVOKE ALL ON FUNCTION platform_meta.fn_guard_lead_ingress_completion_slot()
    FROM PUBLIC, ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role};

DROP TRIGGER trg_lead__mutation_guard ON lead.lead;
CREATE TRIGGER trg_lead__mutation_guard
BEFORE UPDATE OR DELETE ON lead.lead
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_controlled_update(
    'parsed_party_id,party_resolution_code,disposition_code,current_assignment_id,revision,ingress_completion_phone_ciphertext,ingress_completion_phone_hmac,ingress_completion_email_ciphertext,ingress_completion_email_hmac,ingress_completion_source_code,ingress_completion_source_summary_ciphertext,ingress_completed_by_appointment_id,ingress_completed_at,ingress_completion_digest',
    'ingress_completion_phone_ciphertext,ingress_completion_phone_hmac,ingress_completion_email_ciphertext,ingress_completion_email_hmac,ingress_completion_source_code,ingress_completion_source_summary_ciphertext,ingress_completed_by_appointment_id,ingress_completed_at,ingress_completion_digest', '', '', 'CONTROLLED'
);
COMMENT ON TRIGGER trg_lead__mutation_guard ON lead.lead IS
    '受控更新保护：补全槽只允许从全空一次写入完整值，之后禁止覆盖或清空；删除始终拒绝。';

DROP TRIGGER trg_lead__initial_unassigned ON lead.lead;
CREATE TRIGGER trg_lead__initial_unassigned
BEFORE INSERT ON lead.lead
FOR EACH ROW
EXECUTE FUNCTION platform_meta.fn_guard_initial_nulls(
    'current_assignment_id,ingress_completion_phone_ciphertext,ingress_completion_phone_hmac,ingress_completion_email_ciphertext,ingress_completion_email_hmac,ingress_completion_source_code,ingress_completion_source_summary_ciphertext,ingress_completed_by_appointment_id,ingress_completed_at,ingress_completion_digest'
);
COMMENT ON TRIGGER trg_lead__initial_unassigned ON lead.lead IS
    'Lead创建时不得预填当前分派或补全槽；补全只接受后续准确Task命令的一次写入。';

GRANT UPDATE (
    ingress_completion_phone_ciphertext,
    ingress_completion_phone_hmac,
    ingress_completion_email_ciphertext,
    ingress_completion_email_hmac,
    ingress_completion_source_code,
    ingress_completion_source_summary_ciphertext,
    ingress_completed_by_appointment_id,
    ingress_completed_at,
    ingress_completion_digest
) ON lead.lead TO ${app_command_role};

REVOKE SELECT ON lead.lead FROM ${app_query_role};
GRANT SELECT (
    tenant_id,
    lead_id,
    source_channel_code,
    source_account_code,
    source_record_key_digest,
    captured_at,
    captured_name_ciphertext,
    captured_phone_ciphertext,
    captured_phone_hmac,
    captured_email_ciphertext,
    captured_email_hmac,
    city_code,
    service_category_code,
    jurisdiction_code,
    urgency_code,
    legal_need_summary_ciphertext,
    captured_content_digest,
    parsed_party_id,
    party_resolution_code,
    disposition_code,
    current_assignment_id,
    revision,
    created_at
) ON lead.lead TO ${app_query_role};

DO $v850_contract_version$
BEGIN
    UPDATE platform_meta.deployment_state
    SET schema_contract_version = '52-plus-2-v1.1',
        revision = revision + 1,
        changed_at = clock_timestamp()
    WHERE deployment_state_key = 'PRIMARY'
      AND schema_contract_version = '52-plus-2-v1';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'V850 requires deployment_state at schema contract 52-plus-2-v1'
            USING ERRCODE = '55000';
    END IF;
END;
$v850_contract_version$;

DO $v850_validation$
DECLARE
    actual_count bigint;
    completion_column text;
BEGIN
    SELECT count(*) INTO actual_count
    FROM pg_catalog.pg_class relation
    JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
    WHERE relation.relkind IN ('r', 'p')
      AND namespace.nspname IN (
          'identity', 'audit', 'responsibility', 'execution',
          'external_action', 'evidence', 'party', 'lead',
          'opportunity', 'conflict', 'contract', 'transfer', 'platform_meta'
      );
    IF actual_count <> 54 THEN
        RAISE EXCEPTION 'V850 expected 54 managed tables, found %', actual_count;
    END IF;

    SELECT count(*) INTO actual_count
    FROM pg_catalog.pg_constraint constraint_row
    JOIN pg_catalog.pg_class relation ON relation.oid = constraint_row.conrelid
    JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
    WHERE constraint_row.contype = 'f'
      AND namespace.nspname IN (
          'identity', 'audit', 'responsibility', 'execution',
          'external_action', 'evidence', 'party', 'lead',
          'opportunity', 'conflict', 'contract', 'transfer'
      );
    IF actual_count <> 207 THEN
        RAISE EXCEPTION 'V850 expected 207 tenant-safe foreign keys, found %', actual_count;
    END IF;

    SELECT count(*) INTO actual_count
    FROM pg_catalog.pg_trigger trigger_row
    JOIN pg_catalog.pg_class relation ON relation.oid = trigger_row.tgrelid
    JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
    WHERE NOT trigger_row.tgisinternal
      AND trigger_row.tgname ~ '^trg_[a-z0-9_]+__mutation_guard$'
      AND namespace.nspname IN (
          'identity', 'audit', 'responsibility', 'execution',
          'external_action', 'evidence', 'party', 'lead',
          'opportunity', 'conflict', 'contract', 'transfer', 'platform_meta'
      );
    IF actual_count <> 53 THEN
        RAISE EXCEPTION 'V850 expected 53 mutation guards, found %', actual_count;
    END IF;

    FOREACH completion_column IN ARRAY ARRAY[
        'ingress_completion_phone_ciphertext',
        'ingress_completion_phone_hmac',
        'ingress_completion_email_ciphertext',
        'ingress_completion_email_hmac',
        'ingress_completion_source_code',
        'ingress_completion_source_summary_ciphertext',
        'ingress_completed_by_appointment_id',
        'ingress_completed_at',
        'ingress_completion_digest'
    ]::text[] LOOP
        IF NOT pg_catalog.has_column_privilege(
            '${app_command_role}', 'lead.lead', completion_column, 'UPDATE'
        ) THEN
            RAISE EXCEPTION 'V850 command role lacks UPDATE on lead.lead.%', completion_column;
        END IF;
        IF pg_catalog.has_column_privilege(
            '${app_worker_role}', 'lead.lead', completion_column, 'UPDATE'
        ) OR pg_catalog.has_column_privilege(
            '${app_query_role}', 'lead.lead', completion_column, 'UPDATE'
        ) OR pg_catalog.has_column_privilege(
            '${app_query_role}', 'lead.lead', completion_column, 'SELECT'
        ) OR pg_catalog.has_column_privilege(
            '${audit_append_role}', 'lead.lead', completion_column, 'UPDATE'
        ) THEN
            RAISE EXCEPTION 'V850 expanded a non-command role on lead.lead.%', completion_column;
        END IF;
    END LOOP;
END;
$v850_validation$;
