-- 主体域：保存跨业务流程共享的当前态主体锚点、受保护主标识与一跳合并关系。

CREATE TABLE party.party (
    tenant_id uuid NOT NULL,
    party_id uuid NOT NULL,
    party_type varchar(32) NOT NULL,
    canonical_name text NOT NULL,
    primary_identifier_type varchar(64),
    primary_identifier_ciphertext bytea,
    primary_identifier_hmac bytea,
    status varchar(32) NOT NULL,
    merged_into_party_id uuid,
    merged_at timestamptz(6),
    revision bigint DEFAULT 0 NOT NULL,
    CONSTRAINT pk_party PRIMARY KEY (tenant_id, party_id),
    CONSTRAINT ck_party__party_type CHECK (party_type IN ('PERSON', 'ORGANIZATION')),
    CONSTRAINT ck_party__status CHECK (status IN ('ACTIVE', 'MERGED')),
    CONSTRAINT ck_party__primary_identifier_shape CHECK (((primary_identifier_type IS NULL AND primary_identifier_ciphertext IS NULL AND primary_identifier_hmac IS NULL) OR (primary_identifier_type IS NOT NULL AND primary_identifier_ciphertext IS NOT NULL AND primary_identifier_hmac IS NOT NULL))),
    CONSTRAINT ck_party__merge_shape CHECK (((status = 'ACTIVE' AND merged_into_party_id IS NULL AND merged_at IS NULL) OR (status = 'MERGED' AND merged_into_party_id IS NOT NULL AND merged_at IS NOT NULL))),
    CONSTRAINT ck_party__not_self_merge CHECK (merged_into_party_id IS NULL OR merged_into_party_id <> party_id),
    CONSTRAINT ck_party__revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT ck_party__primary_identifier_hmac_length CHECK (octet_length(primary_identifier_hmac) = 32)
);

COMMENT ON TABLE party.party IS 'Fact Owner：PartyRuntime；主体锚点：一行保存自然人或组织当前规范名、至多一个受保护主标识及一跳合并指向，事实Owner为PartyRuntime；仅允许受控当前态更新，不是案件、客户关系或历史版本表。';
COMMENT ON CONSTRAINT pk_party ON party.party IS '主键：在租户内唯一标识一条party记录。';
COMMENT ON INDEX party.pk_party IS '主键：在租户内唯一标识一条party记录。';
COMMENT ON COLUMN party.party.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN party.party.party_id IS '主体锚点标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN party.party.party_type IS '主体类型：PERSON或ORGANIZATION，创建后不可变。';
COMMENT ON COLUMN party.party.canonical_name IS '规范名：当前用于检索和展示的名称，可受控更新；不得混入受保护主标识。';
COMMENT ON COLUMN party.party.primary_identifier_type IS '主标识类型：静态类型代码；空值表示主体没有受保护主标识。';
COMMENT ON COLUMN party.party.primary_identifier_ciphertext IS '主标识密文：应用层加密的唯一主标识；空值表示未设置，数据库不可解密。';
COMMENT ON COLUMN party.party.primary_identifier_hmac IS '主标识HMAC：用于租户内精确匹配的32字节受保护摘要；空值表示未设置。';
COMMENT ON COLUMN party.party.status IS '主体状态：ACTIVE或MERGED，合并后不得恢复。';
COMMENT ON COLUMN party.party.merged_into_party_id IS '合并目标主体标识：仅MERGED时存在且直接指向最终活动主体，禁止多跳链。';
COMMENT ON COLUMN party.party.merged_at IS '合并时间：仅MERGED时存在，首次写入后不得清空或改写。';
COMMENT ON COLUMN party.party.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON CONSTRAINT ck_party__party_type ON party.party IS '主体类型域：只允许自然人或组织两种静态类型。';
COMMENT ON CONSTRAINT ck_party__status ON party.party IS '主体状态域：只允许活动或已合并，且状态转换只能单向发生。';
COMMENT ON CONSTRAINT ck_party__primary_identifier_shape ON party.party IS '主标识形态：一行至多容纳一个受保护主标识，其类型、密文和HMAC必须同时为空或同时存在。';
COMMENT ON CONSTRAINT ck_party__merge_shape ON party.party IS '合并形态：活动主体没有合并指向；已合并主体必须同时记录直接目标和合并时间。';
COMMENT ON CONSTRAINT ck_party__not_self_merge ON party.party IS '合并目标：主体不得合并到自身；目标必须由运行时复验为未合并的最终活动主体。';
COMMENT ON CONSTRAINT ck_party__revision_nonnegative ON party.party IS 'CAS修订号不得为负。';
COMMENT ON CONSTRAINT ck_party__primary_identifier_hmac_length ON party.party IS '摘要格式：primary_identifier_hmac必须保存32字节的规范二进制值。';
