# Task9 外部身份基础设施约束

状态：Task9.1 供应链与配置合同。本文和锁文件不表示 Keycloak 已部署、真实登录已接通或任一实际账号/权限已建立。部署与协议实测由 Task9.2 完成，浏览器依赖在 Task9.4/9.6 按精确版本进入唯一根 package-lock，不在本步安装或创建第二个前端包。

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
