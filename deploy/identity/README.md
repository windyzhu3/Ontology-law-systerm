# Task9 外部身份基础设施约束

状态：Task9.1 供应链与配置合同。本文和锁文件不表示 Keycloak 已部署、真实登录已接通或任一实际账号/权限已建立。部署与协议实测由 Task9.2 完成，浏览器依赖在 Task9.4/9.6 按精确版本进入唯一根 package-lock，不在本步安装或创建第二个前端包。

Task9.2 增补：已提供生产配置装配、离线入口和隔离真实 IdP/数据库协议测试；具体通过记录以 Task9.2 报告为准。这不是实际生产部署、浏览器/UAT、密码策略容量或灾备验收。下面保留 Task9.1 的历史核对记录；它的“尚未完成”列描述该阶段，不代替 Task9.2 结果。

## 锁定制品与验证边界

以 [identity-toolchain.lock.json](identity-toolchain.lock.json) 为唯一新增依赖清单。2026-09-08 核对官方发布、npm 注册表元数据和远程 OCI manifest：

| 制品 | 固定选择 | 本步核对 | 尚未完成 |
|---|---|---|---|
| Keycloak | 26.7.3，linux/amd64 manifest digest | 官方发布日期、Quay index 与平台 digest | 启动、配置、协议安全和容量验收 |
| 身份数据库 | 复用业务工具链已锁定的 PostgreSQL 18 镜像字节，但独立数据库/凭据/存储 | 与原 toolchain lock digest 相同；官方支持 PostgreSQL 18 | 实例隔离及真实运行 |
| keycloak-js | 26.2.4＋npm SHA-512 integrity | 官方 adapter 版本、注册表 dist 及实际 tarball SHA-512 匹配 | SPA 接线与运行测试 |
| Playwright | 1.63.0＋npm integrity＋官方 noble 容器平台 digest | 官方版本、实际 tarball SHA-512、Chromium 1243/153.0.8010.12 与镜像 manifest | 七卡浏览器 E2E 与真实 UAT |

生产部署按平台 digest 拉取，不按可漂移 tag；跨平台必须重新记录目标 manifest，不能拿 amd64 验收代表 arm64。版本号只用于识别，index/platform digest 和 npm integrity 用于制品一致性。没有在本步宣称已下载全部制品、验证全部签名或不存在 CVE；实施部署前须复核上游安全公告，发现影响本配置的未修复问题即停止部署并受控更新锁文件，不静默升级。

来源：[Keycloak 官方下载](https://www.keycloak.org/downloads)、[26.7.3 发布](https://github.com/keycloak/keycloak/releases/tag/26.7.3)、[数据库支持](https://www.keycloak.org/server/db)、[adapter 发布](https://www.keycloak.org/2026/04/keycloak-js-2624-released)、[Playwright 发布](https://github.com/microsoft/playwright/releases/tag/v1.63.0)。远程 digest 通过 `docker buildx imagetools inspect` 仅读 manifest 获取，没有启动身份服务。

## 拓扑和信任配置

- 一个业务 SPA、一个业务 OpenAPI、同一个 `api|worker` Jar 保持；Keycloak 是具名外部身份服务，不是第三业务角色。
- 身份数据库只由 Keycloak 拥有，独立于业务数据库的 13 Schema/54 表、Flyway、能力角色和备份。复用镜像不等于共用库、账号或存储卷。API 不直连身份数据库，Worker 不持有 OIDC/目录管理凭据。
- 每个受信 issuer/provider 唯一映射一个业务 Tenant；部署 Origin 选择固定 realm。配置冲突、缺少映射或随请求指定 issuer/Tenant 均失败关闭。
- SPA public client 仅 Authorization Code + PKCE S256，固定准确 redirect URI 与登出回跳，固定 Web Origin；关闭 Implicit、Direct Access Grants、自助注册、remember-me 和 offline token。不配置通配符回跳。
- API 固定 audience 与算法 allowlist，可信 issuer/JWKS 地址来自配置，禁止 token 的 jku/x5u 指定任意网络地址。签名/issuer/audience/exp/nbf 通过后还须当前 token 活动性复核，权限始终来自业务 Identity 当前事实。
- introspection endpoint 及目录 endpoint 绑定该 realm；超时 2 秒，禁止重定向到其他主机，不缓存“active=true”权限结果、不 fail-open。证书校验不可关闭。
- 后端 introspection confidential client 与只读目录 service client 使用分离凭据；目录客户端的 realm-management 角色 allowlist 仅 `query-users` 和 `view-users`，不授予 manage-users、realm-admin 或跨 realm 管理权。角色与实际请求在 Task9.2 实测，不以能读取任意 realm 作为成功；若上游需要额外权限，停止并修订合同，不能直接赋予 realm-admin。

## 会话和凭据策略

项目参数为 access token 300 秒、SSO/client idle 1800 秒、absolute max 28800 秒、提前 60 秒提示、开启 refresh token rotation。前端依据真实交互控制续期，后台等待轮询不能无限延长交互会话；Keycloak idle 实现窗口、续期竞争和旧 token 撤销须实测。API 远程活动性检查与当前业务撤权重验是独立边界。

密码仅在 Keycloak 处理；项目配置密码长度至少 15、不得等于用户名或邮箱，采用该锁定版本的非 FIPS Argon2 默认安全参数，不降低 hash 成本，不自建密码摘要算法。初次凭据为临时密码并要求本人更新；不定期强制改密来代替风险处置。开启登录暴力破解保护，采用失败阈值 5、等待增量 60 秒、最长等待 900 秒、失败计数重置窗口 1800 秒、不开启永久锁死；Task9.2 将这些值写入可复验 realm 配置并测试，不能仅显示“已启用保护”。账号创建/初始凭据通过受控运维路径交付，业务系统不为此新建邮件/短信服务。

access/refresh token 仅内存，退出先清屏再执行 RP-initiated logout；IdP 不可用时不宣称统一会话已撤销。四字段非凭据恢复标记按已批准设计保留在 sessionStorage，不保存原请求/草稿/ETag/密码/Token，不能覆盖未决写请求或绕过当前 Actor 授权。

## TLS、Origin、CSP 和秘密

- 生产浏览器、OIDC、API 及身份数据库连接使用经过验证的 TLS；反向代理仅信任明确来源，不接受任意 forwarded host 改写 issuer。仅隔离本地开发可使用明确 loopback 配置，不得复用生产凭据或称作生产 TLS 验收。
- SPA CSP 基础为 `default-src 'self'`、`object-src 'none'`、`base-uri 'self'`、`frame-ancestors 'none'`；`connect-src` 只允许本 API 与准确配置的 issuer Origin，禁止 `*`、`unsafe-eval`。样式按现有打包方案处理，不为 OIDC 放宽脚本策略；不依赖第三方 iframe 静默登录。
- Keycloak 登录主题只沿用品牌样式，不重写凭据表单或削弱 Keycloak 自身 CSP。登录/任职新增画面仍在实现前请求用户确认高保真。
- 秘密从受控秘密文件/平台 secret 注入，配置模板只放名称和非敏感地址；禁止命令行明文参数、Git、聊天、截图、完整环境转储及认证请求日志。包括 Keycloak DB 密码、introspection/directory client secret、Tenant subject-HMAC 和独立 actorScopeKey 密钥。
- 审计和排障只保留允许的结果码、脱敏请求关联及制品/配置摘要；不记录密码、Token、原始 subject 或可逆身份。未映射账号不伪造业务 Principal 以写审计。

## Task9.2 前置清单

在创建任何实际环境前核对已冻结后继合同、精确镜像/依赖摘要、受信域名/realm/Tenant、密钥提供方式和隔离资源；缺少配置即停止启动，不带默认超级管理员或演示账号。准备 bootstrap dry-run，明确展示目标 Tenant/根组织/管理员及四项管理授权，但不打印秘密；真实离线执行仍为单独受控操作。

若需改变已批准的单业务制品、应用表数、身份管理子集或安全授权边界，停止并提交具名修订；不得以“部署需要”为理由擅自扩展。

## Task9.2 配置装配与受控运维

`compose.yaml` 固定 linux/amd64 镜像字节，身份库使用独立内部网络、账号和持久卷，不发布数据库端口。只发布明确 loopback HTTPS 端口；生产入口由受控的 HTTPS 网络层提供。`IDENTITY_HTTPS_ORIGIN` 必须是准确 HTTPS Origin，不含 realm 路径。数据库证书 SAN 必须覆盖容器网络名 `identity-db`，Keycloak 通过 `sslmode=verify-full` 与指定 CA 校验它。私钥必须按 PostgreSQL/Keycloak 运行 UID 设置可读且不对其他用户开放；Docker Compose 本地 file-secret 不会可靠替操作者修复主机权限。缺证书、权限或秘密文件时停止，不能改为明文/跳过 TLS。

先由运维准备证书、受控秘密文件及容量/备份，再离线受控地配置 realm。模板不含实际用户、秘密或演示管理员，Compose 也不自动建立管理员或导入未经填充的模板。`realm-template.json` 的每个占位符必须由部署选择固定值：准确 realm、SPA client/回跳/Origin、API audience、目录 client。不要给回跳或 Origin 使用通配符。API introspection 的 confidential client ID **必须等于 `${API_AUDIENCE}`**，这是固定版本的 recipient/audience 校验要求；不能开启 without-audience-check。目录 client 使用不同 ID、不同 secret，只授予本 realm 的 `query-users`、`view-users` 两个角色，核对没有继承 `realm-admin`、manage-users 或其他组合管理角色。模板不创建 service-account 用户及角色授予，需受控运维完成并复核。

模板关闭注册、隐式流、密码授权与 offline scope，保留 Code+PKCE S256。不配置 LDAP、SAML client、动态客户端注册入口、外部 broker 或 impersonation；不得把“未配置”说成已证明上游所有功能没有漏洞。真实用户初次密码必须通过 Keycloak 临时凭据 (`temporary=true`) 交付，要求 `UPDATE_PASSWORD`；模板没有用户，因此不能替运维为每个实际账号完成该操作。测试账号仅在隔离 fixture 中使用已完成初次改密的合成凭据，不是生产账号初始化的替代品。

API/Worker 均使用 `MVP-2026-09-08.3`，物理 schema/release/manifest 校验保持原值。API 的 `ols.api.human-trusts` 每项固定 `issuer`、`audience`、`identity-provider-code`、`tenant-id`、`introspection-client-id` 和受控绝对 `introspection-secret-path`；缺项、重复 issuer/映射、错误 recipient、TLS 错证书/错主机名都失败关闭。`identity-trust-store-path` 与 `identity-trust-store-password-path` 必须成对提供（应用私有 PKCS12），或同时省略使用 JDK 正常受信 CA；不修改系统 truststore。API 上线前业务 Tenant 必须已初始化且 ACTIVE。

原 `registrations` 仅保留 SERVICE 的精确主体/任职/来源账号绑定；生产装配拒绝 HUMAN registration 及静态 on-behalf。动态 HUMAN 不列逐人配置，固定 issuer/provider/Tenant 下按 subject-HMAC 唯一映射。每个 Tenant 追加独立、持久的 32-byte Base64 `actor-scope-hmac`，不得复用其他用途密钥或 cursor key，也不得每次启动随机产生。它绑定完整 actual/on-behalf Actor；替代合法授权证据、重新登录与进程重启不能改变 key。轮换会影响旧 Actor 回执恢复域，必须受控处理，不默默轮换。

## 独立离线 bootstrap 入口

在线受控管理另外要求每个 `ols.api.human-trusts` 项提供 `directory-client-id` 和受控绝对 `directory-secret-path`。客户端及 secret 必须与 introspection 分离，仍只使用固定 realm 的只读 `query-users`、`view-users`，不创建账号或修改凭据。在线只接受完整用户名精确查询，返回零或一个有效 HUMAN，无后续 cursor；精确账号读取最多 2 条，独立 HUMAN 证明最多 50 条。证明已饱和且不包含目标时失败关闭为依赖不可用，不把不确定误报成空结果，也不继续遍历目录。

`ols.api.identity-administration` 必须配置 `active-candidate-key-id`、`candidate-keys`（key ID → 规范 Base64 非零 32-byte key）、`etag-key`、`cursor-key`。这是部署秘密配置，不得从 HTTP 提供或输出到日志。三种在线目的与所有既有 Tenant/业务 cursor 密钥不同；在线 candidate key 也必须由运维与离线 bootstrap key 分离配置，应用不读取离线 key。在线 candidate 最长 5 分钟，绑定完整实际 Actor、固定 provider/issuer 和经过验证的账号。候选 AES-GCM 密文与分页 cursor 不暴露原始 subject；本地列表仍按 createdAt/UUID 有界翻页并绑定当前权限。

这些 key 必须持久化，不能每次启动随机生成。轮换 candidate key 时保留原 key ID 的解密材料，以便识别已提交原命令；部署只改变 active key，不覆盖旧 ID 的材料。ETag/cursor 轮换使旧资源标签/分页失效，需受控安排重新读取；不能据此重建原 Slot/Receipt/Audit。在线权限、scope、原 Actor 或不可变创建绑定不满足时，即使知道 commandId 也不披露原回执。

`io.github.windyzhu3.ontologylaw.api.IdentityBootstrapCommand` 是同一 Java 制品内的独立 main，不启动 Spring、HTTP 或 Worker。通过发布包的 runtime classpath 启动该 main；不将它注册成 API Bean，不开 bootstrap HTTP 路由。参数只接受模式和受控文件绝对路径：

已打包 Jar 的入口（接下列参数；命令行不放凭据值）：

```text
java -Dloader.main=io.github.windyzhu3.ontologylaw.api.IdentityBootstrapCommand -cp ontology-law-system-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher <mode> <config-file> <input-file> [--confirm-bootstrap]
```

```text
candidate <operator-config.json> <exact-account-identifier-file>
dry-run   <operator-config.json> <original-manifest.json>
execute   <operator-config.json> <original-manifest.json> --confirm-bootstrap
verify    <operator-config.json> <original-manifest.json>
```

封闭 operator JSON 字段：`semanticBaseline`, `tenantId`, `tenantCode`, `identityProviderCode`, `issuer`, `apiAudience`, `directoryClientId`, `directorySecretPath`, `operatorAssertion`, `node`, `activeBootstrapKeyId`, `bootstrapKeyPaths`（key ID→绝对文件路径）, `subjectHmacPath`, `identityTrustStorePath`, `identityTrustStorePasswordPath`, `database`。database 仅含 `url`, `username`, `passwordPath`, `schemaVersion`, `releaseDigest`, `manifestHash`。所有 key 文件是规范 Base64 编码的非零 32-byte 独立密钥；candidate AES-GCM key 与 subject-HMAC 不能共用。operator/tenant/provider/issuer 是固定运维绑定，不接受 HTTP 或 token 来决定。

`candidate` 通过受限目录客户端精确匹配一个 enabled HUMAN 账号，输出保密的短期 `providerUserSelector`；将 stdout 直接保存在受控文件，禁止写入构建日志/聊天/截图。它不是原始 subject，不是身份授予，不写业务库。候选有效期最多 5 分钟，绑定操作者、Tenant code、provider、issuer 和经过验证的 subject。目录账号缺失、禁用、非唯一、服务账号或依赖不可用均不产生候选。

manifest 只允许批准合同的 12 字段：`profile=R1_IDENTITY_BOOTSTRAP_V1`, `commandId`, `tenantCode`, `tenantDisplayName`, `rootCode`, `rootDisplayName`, `identityProviderCode`, `issuer`, `providerUserSelector`, `principalDisplayName`, `effectiveFrom`, `operatorAssertion`。不允许自行指定 Principal/Appointment/Grant ID 或权限集。操作者核对 manifest 中的名称、固定配置目标及 dry-run 输出的创建集合后，才允许显式确认 execute。dry-run 与执行都验证候选、目录及现有事实，dry-run 不写数据库。

一次事务原子创建 Tenant、根组织、创始 HUMAN、根 IDENTITY_ADMIN 任职、四项固定 ROOT DIRECT 管理授权和 Slot/Receipt/Audit；不创建 Task、Draft、领域事件或 Outbox。结果不确定或进程失败时退出码 2，只提示保留原 manifest/command，不据此推断回滚。成功模式退出 0；`verify` 从不首次创建。

保留 **原 commandId、完整原 manifest（含原候选密文）、原 candidate key 与 subject-HMAC key**。原键已完整提交时，完整 Slot/Receipt/Audit/初始事实验证优先于候选过期或目录网络复查，可在 IdP 暂时不可用时确认原结果。部分存在、原事实被修改、不同 manifest/新 command 冲突均不补建、不修复。未完整提交且候选过期时不能首次初始化；排除原键不确定结果后，才由运维按受控流程重新准备候选。任何实际生产执行仍须单独批准。
