-- Flyway已在执行迁移前创建platform_meta.flyway_schema_history。

CREATE TABLE platform_meta.deployment_state (
    deployment_state_key varchar(16) NOT NULL,
    operating_mode varchar(16) NOT NULL,
    active_release_digest bytea NOT NULL,
    active_manifest_hash bytea NOT NULL,
    schema_contract_version varchar(40) NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    changed_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_deployment_state PRIMARY KEY (deployment_state_key),
    CONSTRAINT ck_deployment_state__singleton CHECK (deployment_state_key = 'PRIMARY'),
    CONSTRAINT ck_deployment_state__operating_mode CHECK (operating_mode IN ('ACTIVE', 'MAINTENANCE', 'BLOCKED')),
    CONSTRAINT ck_deployment_state__revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT ck_deployment_state__active_release_digest_length CHECK (octet_length(active_release_digest) = 32),
    CONSTRAINT ck_deployment_state__active_manifest_hash_length CHECK (octet_length(active_manifest_hash) = 32)
);

COMMENT ON TABLE platform_meta.deployment_state IS 'Fact Owner：DeploymentRuntime；部署门禁：唯一一行记录当前运行模式、发布摘要和Schema合同版本，不保存业务事实。';
COMMENT ON CONSTRAINT pk_deployment_state ON platform_meta.deployment_state IS '主键：固定PRIMARY确保部署门禁始终只有一行。';
COMMENT ON INDEX platform_meta.pk_deployment_state IS '主键：固定PRIMARY确保部署门禁始终只有一行。';
COMMENT ON COLUMN platform_meta.deployment_state.deployment_state_key IS '单行主键：固定为PRIMARY，用于定位唯一部署门禁。';
COMMENT ON COLUMN platform_meta.deployment_state.operating_mode IS '运行模式：ACTIVE、MAINTENANCE或BLOCKED。';
COMMENT ON COLUMN platform_meta.deployment_state.active_release_digest IS '当前发布摘要：运行中应用发布物的32字节规范摘要。';
COMMENT ON COLUMN platform_meta.deployment_state.active_manifest_hash IS '当前部署清单摘要：类型、路由、Schema和策略清单的32字节摘要。';
COMMENT ON COLUMN platform_meta.deployment_state.schema_contract_version IS 'Schema合同版本：与本次52＋2字段合同对应的静态版本。';
COMMENT ON COLUMN platform_meta.deployment_state.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN platform_meta.deployment_state.changed_at IS '变更时间：部署门禁最近一次受控切换的可信时间。';
COMMENT ON CONSTRAINT ck_deployment_state__singleton ON platform_meta.deployment_state IS '单行约束：门禁主键只能为PRIMARY。';
COMMENT ON CONSTRAINT ck_deployment_state__operating_mode ON platform_meta.deployment_state IS '运行模式只允许正常、维护或阻断。';
COMMENT ON CONSTRAINT ck_deployment_state__revision_nonnegative ON platform_meta.deployment_state IS 'CAS修订号不得为负数。';
COMMENT ON CONSTRAINT ck_deployment_state__active_release_digest_length ON platform_meta.deployment_state IS '摘要格式：active_release_digest必须为32字节规范摘要。';
COMMENT ON CONSTRAINT ck_deployment_state__active_manifest_hash_length ON platform_meta.deployment_state IS '摘要格式：active_manifest_hash必须为32字节规范摘要。';

INSERT INTO platform_meta.deployment_state (
    deployment_state_key, operating_mode, active_release_digest,
    active_manifest_hash, schema_contract_version, revision, changed_at
) VALUES (
    'PRIMARY', 'BLOCKED', decode(repeat('00', 32), 'hex'),
    decode(repeat('00', 32), 'hex'), '52-plus-2-v1', 0, clock_timestamp()
);

COMMENT ON TABLE platform_meta.flyway_schema_history IS
    'Flyway迁移历史：由固定版本Flyway独占创建和维护，应用不得写入。';
COMMENT ON COLUMN platform_meta.flyway_schema_history.installed_rank IS 'Flyway安装顺序号：由Flyway维护。';
COMMENT ON COLUMN platform_meta.flyway_schema_history.version IS 'Flyway版本号：可重复迁移时可为空。';
COMMENT ON COLUMN platform_meta.flyway_schema_history.description IS 'Flyway迁移说明：来自迁移文件名称。';
COMMENT ON COLUMN platform_meta.flyway_schema_history.type IS 'Flyway迁移类型：由Flyway维护。';
COMMENT ON COLUMN platform_meta.flyway_schema_history.script IS 'Flyway脚本名称：由Flyway维护。';
COMMENT ON COLUMN platform_meta.flyway_schema_history.checksum IS 'Flyway校验和：用于识别已执行迁移漂移。';
COMMENT ON COLUMN platform_meta.flyway_schema_history.installed_by IS 'Flyway执行主体：执行本次迁移的数据库用户。';
COMMENT ON COLUMN platform_meta.flyway_schema_history.installed_on IS 'Flyway安装时间：迁移历史写入时间。';
COMMENT ON COLUMN platform_meta.flyway_schema_history.execution_time IS 'Flyway执行耗时：以毫秒表示。';
COMMENT ON COLUMN platform_meta.flyway_schema_history.success IS 'Flyway执行结果：表示该迁移是否成功。';
