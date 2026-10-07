# Linux 发布与海华初始化

唯一操作入口为 `python3 deploy/linux/linux.py --runtime /绝对/私密目录 <命令>`。适用于 Linux amd64、已确认的海华上线验收配置，以及冻结 v20 到具名 v22 的升级。首次初始化只接受 v22；v21 仅是原升级操作中的中间状态。运行状态、秘密文件、制品、数据库备份和材料必须保留在 Git 之外。

## 前置与执行参数

在 Linux 上准备 Python 3.11+、Git、Docker Engine 和可用的磁盘空间。应用运行镜像由仓库锁定的 Linux JDK、Node、npm 构建；PostgreSQL、Keycloak、ClamAV 镜像按仓库摘要拉取。不要用任意已存在的同名容器、数据卷或旧库冒充新实例。

操作系统用户必须拥有运行目录；目录权限 0700，参数和秘密文件 0600。首次 `prepare` 的运行目录应不存在或为空。构建目标必须是运行目录之外的新私密目录：构建依赖有符号链接，不能混入要求精确保全的运行目录。已安装的实际 JAR、SPA、配置、证书、密钥、材料和两个数据库都纳入联动备份。

执行时建立私密 `settings.json`，填写下列结构；仓库不收集云服务器密码或密钥：

```json
{
  "name": "ols-haihua-acceptance",
  "repo": "/opt/ontology-law-source",
  "ports": {"identity": 24843, "entry": 24844, "api": 24845},
  "publicOrigin": "https://workbench.example.invalid",
  "identityOrigin": "https://identity.example.invalid",
  "publicTlsFiles": {
    "certificate": "/secure/tls/fullchain.pem",
    "privateKey": "/secure/tls/private.key",
    "certificateAuthority": "/secure/tls/root-ca.pem"
  }
}
```

替换为实际域名和证书。两个域名使用的证书必须覆盖相应主机名；CA 文件提供一个明确的信任证书。外部入口由服务器已有的 HTTPS 代理转发到对应的本机回环端口，保留原 Host。数据库和 API 继续使用实例独立的内部 TLS；数据库拒绝非 TLS 连接。不要将数据库或内部 API 直接暴露到公网。运行参数和原证书摘要在首次准备时锁定；原续跑不能换域名、证书或密码文件。

仅在本机隔离验收时，可省略 `publicOrigin`、`identityOrigin`、`publicTlsFiles`，使用工具生成的 localhost 证书。浏览器需信任实例 CA，不能关闭 TLS 校验。

## 空库初始化

以下命令均在同一个确定的源码提交上执行，`RUNTIME` 与 `BUNDLE` 使用绝对路径：

```bash
umask 077
RUNTIME=/var/lib/ontology-law/haihua-acceptance
BUNDLE=/var/lib/ontology-law-build/haihua-v22
python3 deploy/linux/linux.py --runtime "$RUNTIME" prepare \
  --settings-file /secure/settings.json --config deploy/linux/config/haihua.json
```

准备私密 `oidc-build.json`，其四个公开构建参数必须与 settings 一致。realm 等于实例 `name`，SPA client 为 `name-spa`，audience 为 `name-api`：

```json
{
  "VITE_APP_ORIGIN": "https://workbench.example.invalid",
  "VITE_OIDC_ISSUER": "https://identity.example.invalid/realms/ols-haihua-acceptance",
  "VITE_OIDC_CLIENT_ID": "ols-haihua-acceptance-spa",
  "VITE_OIDC_AUDIENCE": "ols-haihua-acceptance-api"
}
```

```bash
python3 deploy/linux/linux.py --runtime "$RUNTIME" describe-bundle \
  --repo /opt/ontology-law-source --build-directory "$BUNDLE" \
  --oidc-settings-file /secure/oidc-build.json
python3 deploy/linux/linux.py --runtime "$RUNTIME" initialize \
  --config deploy/linux/config/haihua.json --bundle "$BUNDLE" \
  --initial-password-file /secure/initial-password.txt
```

初始化密码放在上述私密文件中，使用已确认的统一临时密码；不要将值写入 argv、Git 或日志。17 名 HUMAN 用户必须首次登录改密。初始化会先导入真实 IdP 账号、验证离线 bootstrap，随后返回 `HUMAN_SESSIONS_REQUIRED`（退出码 3）。此时业务入口保持关闭，尚未完成初始化。

按返回的原 `operationId`，由丁启明、黄雪雪分别完成自己真实的浏览器登录和改密：

```bash
python3 deploy/linux/linux.py --runtime "$RUNTIME" initialize-resume \
  --operation-id ORIGINAL_ID --capture-admin dingqiming
python3 deploy/linux/linux.py --runtime "$RUNTIME" initialize-resume \
  --operation-id ORIGINAL_ID --capture-admin huangxuexue
```

每条命令显示原 PKCE 授权地址，并通过隐藏输入收取本次浏览器的完整回调 URL。业务入口关闭期间回调页面可能打不开；复制本次 `/auth/callback` 地址即可。该地址包含一次性授权码，不能发到聊天、工单或日志。工具只使用原 client、redirect URI、state、nonce 与 PKCE，拒绝密码授权、代登录和其他身份。

两人的会话写入 0600 私密文件，工具返回 `sessionsFile`。也可由已获准的真实 PKCE 登录工具提供同格式的私密会话文件；会话过期后应重新真实认证，不得重置账号或替换未知的原命令。

```bash
python3 deploy/linux/linux.py --runtime "$RUNTIME" initialize-resume \
  --operation-id ORIGINAL_ID --sessions-file /absolute/private/sessions.json
python3 deploy/linux/linux.py --runtime "$RUNTIME" verify-initialization \
  --config deploy/linux/config/haihua.json
python3 deploy/linux/linux.py --runtime "$RUNTIME" health
```

完成后应为 7 个组织、17 名 HUMAN、20 个 HUMAN 任职、280 项明确 HUMAN 授权，另有独立 SERVICE；15 名人员仍待首次改密。初始化库无历史数据、线索、商机、合同、款项和转案交易。主任律师具有已确认业务及审计查询权限；不提供审计导出。线索分配权限不随主管岗位自动授予，须由合格管理员以后独立指派。其他案管人员本阶段不分配业务职责。

三类合同文件保持 `CANDIDATE_NOT_APPROVED`，不能因此办理真实签约。正式模板需业务审核并建立准确模板及律所签署主体绑定。默认代表为丁启明，可替换；每份合同仍须核验代表权限。收款显示为“海华律师事务所验收收款账户”，并非真实银行到账证明。

## v20 升级与同版本发布

现有实例须先登记、核验其真实原制品和运行资源；此入口不会自动收养陌生容器或旧库。保持原 IdP、账号、任职、配置、材料和秘密。准备实际 v22 构建制品后执行：

```bash
python3 deploy/linux/linux.py --runtime "$RUNTIME" release-status
python3 deploy/linux/linux.py --runtime "$RUNTIME" upgrade --bundle "$BUNDLE"
python3 deploy/linux/linux.py --runtime "$RUNTIME" upgrade-resume --operation-id ORIGINAL_UPGRADE_ID
python3 deploy/linux/linux.py --runtime "$RUNTIME" health
```

升级先以准确 revision/摘要关闭 gate，观察入口和全部写入进程停止，备份两数据库、角色 globals 与原运行资产，并在隔离目标实际恢复验证。仅在备份验证通过后执行 V1070 和 V1080；冻结的前 41 项 SQL 不变。中断时只能续跑同一原操作，已提交的迁移通过实际 Flyway 历史重验，不重跑 DDL。gate 冲突、缺少有效备份、停止失败、来源或制品变化、并发操作均拒绝推进。

v20 内或 v22 内仅发布制品使用 `publish-bytes --bundle "$BUNDLE"`。它明确拒绝 schema 变化。无论升级还是仅发布，API、Worker、IdP、扫描服务及实际 HTTPS 页面都通过验证后才开放入口。中间状态、未知结果、BLOCKED 返回非零，保留原 operationId 与核对入口；不要新建操作或新幂等键“补成功”。

## 停止、联动恢复与重新开放

```bash
python3 deploy/linux/linux.py --runtime "$RUNTIME" stop
python3 deploy/linux/linux.py --runtime "$RUNTIME" start
python3 deploy/linux/linux.py --runtime "$RUNTIME" restore-checkpoint --operation-id SOURCE_OPERATION_ID
python3 deploy/linux/linux.py --runtime "$RUNTIME" start
python3 deploy/linux/linux.py --runtime "$RUNTIME" health
```

`stop` 只停止已登记的当前入口及写入进程，不删除数据、不重置密码。待处理发布应使用原 `upgrade-resume`，不允许 `start` 绕过未完成阶段。恢复使用完整已验证 checkpoint，保留新库隔离副本，并联动还原数据库、角色、材料、身份及旧版制品/配置/密钥。返回 `RESTORED_MAINTENANCE` 后仍关闭入口；显式 `start` 再验证旧版全部服务才开放。禁止仅回退旧 JAR 配新 schema。

两库恢复分别核对原数据、角色和迁移历史，已完成的库只验证；角色导入和单库归档恢复分别使用 PostgreSQL 单事务。角色提交或第一库完成后丢失响应，继续原命令即可核对已提交部分、完成剩余库，不创建新操作或重复导入已确认角色。与原事实冲突的部分恢复仍保留现场并拒绝推进，不自动清空或修复数据库。

激活失败处理使用 `ACTIVATION_FAILING` 阶段；停写后退出或 BLOCKED gate 已提交但响应丢失，原续跑先完成失败处理，再核对原 activation 记录并尝试完整激活。证书目录在 Docker 副作用前检查真实路径、类型、归属及私密权限，符号链接目录不能用于生成实例密钥。

原操作锁和 HMAC 日志不可手工改写。部分备份没有完整验证记录时不能用于恢复或迁移；保全原目录并审查失败原因。运行目录中的新增链接、未知资产和磁盘写入错误会阻断保全。

## 验证范围

运行 `python3 -B -m unittest discover -s deploy/linux/tests -v` 检查工具。真实验收入口为 `deploy/linux/verification/acceptance.py --run-id <唯一ID> --scenario all --inputs-file <私密映射>`；映射指向分别登记的空库、真实 v20 升级和独立合成业务实例。缺失阶段及 SKIP 都返回 `INCOMPLETE`，不能当作成功。

合成测试单独授予分配权限、审核明确标记的测试模板，通过真实页面办理四条销售链。它不向初始化库写入测试业务、测试授权或已审核模板。公开结果见仓库 `docs/evidence/linux-v22-initialization/`；令牌、回调、材料正文、证书私钥和数据库备份始终私密。上线验收不等于正式模板已经审核，也不等于已执行腾讯云部署。
