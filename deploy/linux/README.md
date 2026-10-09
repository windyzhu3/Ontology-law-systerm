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

## 公开 TLS 轮换与到期检查

此路径保留原初始化、账号、原 `certs/public.*` 和 settingsDigest；新材料进入独立封存代次。实现验收状态以 `docs/evidence/public-ip-tls-rotation/report.md` 为准，单元测试通过不代表生产可部署。不得直接覆盖旧证书或重新初始化账号。未完成 restore 不支持嵌入新证书轮换；恢复出的历史证书过期时，原 restore 保持维护状态，`start` 拒绝开放。恢复前应先核对 checkpoint 中证书有效期。

材料目录必须由执行账户拥有且为 `0700`，材料及输入 JSON 为 `0600`、普通文件、无硬链接或符号链接。私钥只从文件读取，不放命令行、环境变量或日志。人工导入无需 CA API、EAB 或付费服务。输入文件包含 `materials`、`proxies`、`probeTargets` 三项；材料示例：

```json
{
  "certificate": "/absolute/private/new/certificate.pem",
  "privateKey": "/absolute/private/new/private.key",
  "intermediates": ["/absolute/private/new/intermediate.pem"],
  "approvedAnchors": ["/absolute/private/new/approved-root.pem"],
  "origins": ["https://original-application-origin", "https://original-identity-origin"],
  "provenance": {"kind": "manual"}
}
```

`origins` 必须与原实例完全一致。候选必须匹配私钥、覆盖原 SAN、具备服务器用途、链至代码审核准入的根，剩余有效期至少 7 天且到期日晚于当前证书。上传的中间证书不能自动成为根信任。`approvedAnchors` 的内容还须通过仓库准入表校验。

`proxies` 的 Docker 登记使用 `{ "version": 1, "services": [...] }`；经过实机资格验证的 systemd 登记必须使用 `{ "version": 2, "qualification": { "mode": "report", "root": "/absolute/private/retained-proof-runtime", "sha256": "EXACT_SEALED_REPORT_FILE_SHA256" }, "services": [...] }`。生产登记不得使用测试专用的 `mode: isolated`。报告必须通过实例签名、当前 implementationDigest、Q01–Q12 全部 PASS、证据文件摘要和实际代理二进制哈希检查。测试单独通过、旧摘要报告或普通 JSON 不能代替资格证明。

每个服务完整提供 `role`（nginx 或 caddy）、`transport`、`name`、`identity`、`image`、`config`、`configSha256`、`tlsPaths`。Docker 配置须已登记在运行目录 `proxy/` 下，原容器须按原路径挂载整个运行目录，身份绑定实际容器 ID 和 image ID。systemd version 2 还须提供完整 `systemd` profile：`unitFile`、`immutableFiles`、`includes`、`mainConfig`、`properties`、`process`、`credentialNames`、`listeners`；绑定实际文件内容及所有权/权限、配置图、manager 属性、固定可执行文件哈希、进程 argv/uid/gid/cgroup、端口归属与已加载凭据。复杂 manager 属性从现有 busctl 类型化读取，Exec 命令为有序 argv 数组。没有自动采用未知代理的注册 CLI；私密登记输入须按实际观测审阅后交给轮换入口。

外层 nginx 必须是获准暂停的独立服务。Docker 维护仅接受准确的 `nginx -c <登记配置> -g "daemon off;"` 启动且不得有运行目录嵌套挂载。systemd 维护仅接受已通过资格门禁的固定配置图、原有 IPv4 HTTPS 监听和严格 TLS Caddy 桥接；保持登记的 HTTP 挑战配置。Caddy LoadCredential 切换由原操作控制 drop-in、daemon-reload 和停止/启动，并证明新进程实际加载了目标凭据，不能用 reload 代替。具体接入、隔离资格及清理边界见 [systemd 资格说明](verification/systemd_qualification/README.md)。

清理资格实例前须私密保留原始报告、`runtime/journal.key` 及报告引用的全部相对路径证据，并核验导出文件的离机摘要。`qualification.root` 指向保持这些相对结构的私密证据目录；不要改写原报告或 instance 标识，也不要只保留报告 JSON。证据目录不是可恢复或可启动的生产 runtime。

首次 legacy 回退从原操作签名记录中的 `probeTargets` 补全旧代次 deployment，再封存进 active selection，并执行完整探测；不从当前配置猜测目标。旧 native 与外部证书可以不同，候选必须晚于两者到期，回退则要求两者仍有效且原文件绑定未漂移。

`probeTargets` 明确登记 bridgeIdentity、bridgeEntry、publicIdentity、publicEntry 四个目标，每项提供 role/connectHost/connectPort/verifyHost；桥接端口可显式增加 `tlsIdentity: "internal"`，仅指原封存的 `certs/server.crt` 与其 localhost 身份。公网及原生端点的 verifyHost 必须保留原 origin 的主机/IP 身份，不能改成 localhost 来通过验证；connectHost 限原主机或本机回环。两个 native 目标取自原身份计划。两段代理 TLS 校验均须保留。

```sh
python3 -B deploy/linux/linux.py --runtime /absolute/private/runtime rotate-public-tls --inputs-file /absolute/private/rotation.json
python3 -B deploy/linux/linux.py --runtime /absolute/private/runtime rotate-public-tls-resume --operation-id ORIGINAL_ID
python3 -B deploy/linux/linux.py --runtime /absolute/private/runtime rotate-public-tls-rollback --operation-id ORIGINAL_ID
python3 -B deploy/linux/linux.py --runtime /absolute/private/runtime public-tls-status
```

轮换使用实例锁与原操作日志，维护先停止外层代理和原生写入者，更新原生身份容器证书及 API/Worker/入口信任配置。激活时复用原监听，只允许本机回环及原 pod 的准确地址访问原 realm 的 GET JWKS 和 POST introspection；其他身份和业务路由均返回 503。两段 TLS 和原客户端认证不变。失败清理仍停止整个外层代理。原生与桥接检查通过后才开放外层，再核对公开入口。发生异常应保留现场并使用返回的原 ID 续跑；不得换输入另开操作。回退仅适用于仍有效且完整的旧代次，结果仍属于原轮换操作。重复完全相同的已部署产物仅在实际探测通过时返回 `UNCHANGED`，保留原 ID；不同代理绑定不会被当作重复成功。

默认到期阈值为 30 天 WARNING、14 天 ACTION_REQUIRED、7 天 CRITICAL；到期或指纹失败为 BLOCKED。退出码：0=OK，2=需关注，1=BLOCKED，4=UNKNOWN。未配置历史检查或超过 26 小时无成功证据时明确返回 UNKNOWN；首次实时探测结果仍单独显示。状态命令不修改运行时，输出的 `checkEvidence` 带实例签名，历史证据须放运行目录之外的私密文件，通过 `--previous-check-file` 传回。未来时间、错误签名及其他实例证据均拒绝。

可在已有宿主每日调度中运行以下 Bash 片段；部署/安装调度不属于仓库修复操作。目录须预先按 `0700` 建好。仅带签名证据的有效结果替换上次检查文件，错误输出单独保留：

```bash
umask 077
check_dir=/absolute/private/tls-monitor
check_tmp=$(mktemp "$check_dir/.check.XXXXXX")
check_args=()
if test -f "$check_dir/last.json"; then
  check_args=(--previous-check-file "$check_dir/last.json")
fi
check_rc=0
python3 -B deploy/linux/linux.py --runtime /absolute/private/runtime public-tls-status "${check_args[@]}" > "$check_tmp" || check_rc=$?
if python3 -c 'import json,sys; sys.exit(0 if "checkEvidence" in json.load(open(sys.argv[1])) else 1)' "$check_tmp"; then
  mv "$check_tmp" "$check_dir/last.json"
else
  mv "$check_tmp" "$check_dir/last-error.json"
fi
exit "$check_rc"
```

本地日志不等于通知已送达；通知渠道须另行配置并验证，不新增收费告警服务。若上次检查文件损坏，保留错误证据后由操作员显式恢复检查记录，不能伪造一次成功检查。

### 签发 hook 的准入边界

生产签发提供者准入表当前为空，状态固定 `MANUAL_REQUIRED`，不承诺 ZeroSSL IP ACME、免费无限续期或自动挑战。未审核提供者、额度未知/耗尽、签发证明失效或未授权维护均在进入维护前拒绝。签发失败不会为了取证而停止仍有效的服务。

受支持的接入边界是“已成功签发 → 固定私密产物 → 正式轮换入口”，而非原先仅 `nginx -t` 与 reload 的 hook。启用具体自动签发提供者前，必须另行核验并审核其客户端二进制、IP 实际签发证据、准确 lineage/archive、账户额度与有效期限和维护授权；当前不提供生产启用配置。测试专用准入仅用于明确 verification 实例，不得转为生产实例。

已准入适配器只允许登记 lineage 的 cert.pem/privkey.pem/chain.pem 链接，目标须落在准确 archive 的同一编号产物；复制期间变化会拒绝。核心轮换仍只读取无链接的固定私密副本。签发成功和部署成功是两个结果；只有完整轮换与实际 TLS 探测成功才算部署成功。
