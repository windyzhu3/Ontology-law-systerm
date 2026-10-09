# Public IP TLS Rotation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 Linux 发布工具中提供可审计、失败关闭、可恢复的公网 IP 跨 CA／同 CA 轮换和持续到期检查，不重建账号或改写业务数据。

**Architecture:** 使用不可变 TLS 代次、同一锚集合生成的 PEM/Java truststore、派生部署封存和原实例 journal/gate CAS。受限代理适配器只管理审核登记的 nginx/Caddy，所有消费者及原生证书指纹通过后才开放。签发与部署分离，首版完整支持手动材料导入，不假定 ZeroSSL IP ACME 或无限免费续期。

**Tech Stack:** Python 3.11+ / unittest、现有 Docker 锁定镜像、OpenSSL、锁定 JDK/keytool、Node 24.20.0、Java/JUnit、现有 Maven 3.9.16；不新增 Python 包、业务框架或常驻服务。

**Spec:** [2026-10-08-public-ip-tls-rotation-design.md](../specs/2026-10-08-public-ip-tls-rotation-design.md)。用户于 2026-10-08 08:56:34 UTC 回复“确认”；绑定该文件 SHA-256 `1979c7a3a1c6a6857d4a47852acb5e6b5c9415bba08cbb91e2d5198c40ea492c`，Library `libfile_d454f3af0ce88191999aafcbd6c6c919`，版本 0。执行者必须同时读取设计与本计划。

## Global Constraints

- 基线 main 为 `34cb7490a6443682c58c72df5ab1334e7e186d76`；执行开始前重查远端、AGENTS.md 和 .agents/skills。main 有相关改动则先差异评估，不盲目重做。
- 当前只批准编写计划；不实施、不提交或推送、不连接生产、不部署。下列代码、测试、提交步骤均待计划审核和执行方式确认后执行。
- 后续执行先按 using-git-worktrees 建立隔离工作区，保留本设计/计划；工作区内实现，不在生产开发。任务提交只在获批实施阶段发生，推送/合并/部署不在本计划授权内。
- 保留原初始化 COMPLETE、settingsDigest、publicTlsHashes 与第零代封存；HMAC 日志不手工改写；无业务 schema、账号、密码、SERVICE 注册、任职或权限变更。
- 默认候选最小剩余有效期 **7 天**；默认 **30 天 WARNING、14 天 ACTION_REQUIRED、7 天 CRITICAL**；证据超过 **26 小时**为陈旧。所有时间 UTC。
- 初次信任集合为内部实例 CA＋ISRG Root X2＋经官方来源与独立可信源核验的准确 R46。未取得准确根指纹不得启用该锚；上传中间 CA 不自动成为锚。
- 不生成 API Key/EAB，不付费、不增加云资源；自动签发默认 MANUAL_REQUIRED。外部免费额度/IP ACME 不作未经验证的可用性承诺。
- 私钥只在私密运行/测试目录，目录 0700、文件 0600；参数和日志不携带秘密值。不得提交真实私钥、生产凭据或内部诊断。
- 首版支持已完成实例的旧公开证书过期前向轮换；**暂不支持未完成 restore 内嵌轮换**；过期 checkpoint 恢复保持关闭且不伪造 COMPLETE。
- 无全栈双活或新 watchdog；短维护窗口、顺序启动。未知停止状态不得报告“已关闭”，未知健康不得报告 PASS。
- 保留原严格 TLS、两段代理验证、内部 TLS 和 SERVICE 精确叶指纹身份规则。源码改动不升级现有锁定依赖。

## Review Focus

1. Certbot live 链接在读取期间换代、硬链接或同文件别名：安装必须使用已验证的固定字节；任务 1/10。
2. 两个 PEM 表示同一根、alias 碰撞、只有中间 CA 的上传：按 DER 身份规范化并拒绝未准入锚；任务 1/3。
3. 公网代理已呈现新证书但原生仍用旧副本：直连原生、保持原身份验证并比对指纹；任务 6/11。
4. OPENING、gate CAS 或失败清理提交后响应丢失：同操作对账，不能新建操作或误报关闭；任务 7/11。
5. 历史 restore 带回过期证书，而当前 registry 仍指新代次：按 checkpoint 还原引用并保持恢复未完成；任务 8/11。

这些是计划自检补入的重点反例；下列对应任务必须写测试，不能只留给人工审阅。

---

## 文件边界、数据约定与执行命令

### 文件责任

| 新文件 | 单一责任 |
| --- | --- |
| `deploy/linux/ols_linux/tls_material.py` | 固定材料字节、路径边界、锚准入和 OpenSSL 验证 |
| `deploy/linux/ols_linux/tls_generation.py` | 第零代兼容、不可变代次/HMAC、有效引用和 Java truststore |
| `deploy/linux/ols_linux/tls_deployment.py` | 只改 TLS 的派生部署封存、容器副本与启动关联 |
| `deploy/linux/ols_linux/tls_proxy.py` | 有限 nginx/Caddy 登记、维护/切换/恢复及观测 |
| `deploy/linux/ols_linux/tls_probe.py` | 受控目标的实际握手、指纹与消费者证据 |
| `deploy/linux/ols_linux/tls_rotation.py` | 原操作状态机、恢复、回退、失败处理 |
| `deploy/linux/ols_linux/tls_status.py` | 只读期限/签发状态与脱敏结果 |
| `deploy/linux/ols_linux/tls_import.py` | 手动与已核验 hook 输入适配，绝不签发或自动准入锚 |
| `deploy/linux/verification/public_tls_rotation.py` | 隔离真实验收入口与故障场景 |

修改范围限定现有 `public_runtime.py`、`runtime.py`、`identity.py`、`assembly.py`、`deployment.py`、`initialize.py`、`journal.py`、`release.py`、`checkpoint.py`、`verify.py`、`linux.py` 的对应调用点；不重构无关函数。新增文件因职责区分而存在，不引入插件框架。

### 公共记录约定

延续仓库 dict＋canonical digest＋HMAC，不新增模型库。以下名称是字典合同，不是待实现的独立类型；公开函数注解使用 dict/Path/bytes，均拒绝未知字段和版本。

- `Candidate`：version=1、inputDigest、leafDerSha256、notBefore/notAfter（UTC epoch 秒）、originHosts、files（相对路径→SHA-256）、anchorFingerprints（排序去重 DER SHA-256）、provenanceDigest、directory。无秘密内容。
- `Generation`：version=1、generationId、manifestDigest、parentGenerationId、instanceId、operationId、descriptorDigest、candidate、trust（PEM/PKCS12 路径与摘要）、files、paths。generationId 是 instanceId/operationId/parentGenerationId/inputDigest 四元组的 canonical SHA-256，在生成配置前确定；manifestDigest 是最终完整清单去除自身字段后的摘要，避免路径包含 ID 形成自引用。第零代为显式 version=0 适配对象，不能持久化伪造原历史。
- `paths` 固定键：certificate、privateKey、httpTrust、javaTrustStore；内部 CA 和 SERVICE 材料仍沿原路径。
- `Probe`：status=PASS/BLOCKED/UNKNOWN、observedAt、targets（原生/代理各自证书指纹与链验证结果）、consumers（Java/Node/身份/API/Worker/扫描验证）、generationId。不能用一条公网请求代替所有目标。
- `OperationResult`：operationId、kind=rotate-public-tls、phase、outcome（仅终态 ROTATED/ROLLED_BACK）、generationId（封存前可为 null）；错误结果另外携带脱敏 reasonCode。
- `Status`：status=OK/WARNING/ACTION_REQUIRED/CRITICAL/BLOCKED/UNKNOWN、remainingSeconds、notAfter、generationId、operationId/phase、lastSuccessfulCheckAt、monitoringState、issuanceState、targets、consumers、exitCode。状态优先级为 UNKNOWN/BLOCKED 高于剩余期限警告；UNKNOWN 表示无法得出可信结论，BLOCKED 表示已证实不满足条件。另附独立 HMAC 的 checkEvidence（instanceId、generationId、checkedAt、success、probeDigest）供下一次检查核验，HMAC key 不输出。

运行资产命名：`tls/generations/<generationId>/`、HMAC `tls/active.json`、预登记 HMAC `tls-pending.json`、`operations/<operationId>-tls.json`、派生 `deployments/<descriptorDigest>/tls/<generationId>/`。`tls-pending.json` 是实例控制文件，排除于 checkpoint 资产恢复；有效记录及已提交代次是恢复资产。新文件存在但无效必须拒绝，不得回退猜测第零代。

### 验证环境与命令规则

命令均从仓库根执行，Python 用 `-B` 避免源码 pycache。定点 unittest 固定前缀：`PYTHONPATH=deploy/linux:deploy/linux/tests python3 -B -m unittest`。测试使用 tempfile 创建自身 0700 目录，销毁仅限其所有测试资源。

真实证书夹具采用仓库锁定工具或测试所用锁定镜像中的 OpenSSL/keytool；不信任任意 PATH 工具。若必须通过宿主 OpenSSL 做单元夹具，验证环境要记录其版本，最终以锁定容器集成结论为准。后端 IT 使用现有 Testcontainers 和原锁定 PostgreSQL；缺 Docker/JDK/Maven 是 INCOMPLETE，禁止降低工具链检查。

后端定点命令：`mvn -f backend/pom.xml -Pit -Dit.test=ClientCertificateIT,ActorContextResolverIT verify`。要求 Failsafe 指定测试实际执行、0 failure/error/skip；不能用编译成功代替。Node 命令：`node --test deploy/linux/tests/server.test.mjs`。

测试代码片段仅规定关键断言，测试中的 old/candidate/root 等由各任务注明的测试夹具创建，不是生产路径。每任务的红灯必须来自新增断言或缺少该功能，环境错误不算有效红灯。

## Task 1: 固定材料读取与严格证书/锚准入

**Files:** Create `deploy/linux/ols_linux/tls_material.py`、`deploy/linux/config/public-tls-trust-anchors.json`、`deploy/linux/tests/tls_fixtures.py`、`deploy/linux/tests/test_tls_material.py`；Modify `deploy/linux/ols_linux/public_runtime.py:inputs` 的共享校验调用，保留旧参数合同及初始化语义。

**Interfaces:** Produces `tls_material.stage(root: Path, inputs: dict, *, now: int) -> dict`（Candidate）、`tls_material.verify(directory: Path, expected: dict, *, now: int) -> dict`。输入字段固定 certificate/privateKey/intermediates 路径、approvedAnchors 清单引用、origins、provenance；approvedAnchors 只能引用代码审查过的 public-tls-trust-anchors.json 中的准确根身份，不能让候选自行提交新的允许列表。原内部/旧公网锚来自原封存。stage 不登记操作、不接触服务、失败不改变 active。Create `tls_fixtures.materials(directory: Path, *, expired_old: bool = False) -> dict`：生成 internal/old/new/untrusted 测试根、RSA/ECDSA 叶、IP SAN 及 clientAuth 反例，返回路径/指纹/有效期；仅 verification 实例允许测试锚清单，生产不能通过环境变量打开测试信任。

- [ ] Step 1：在 `test_tls_material.py` 写 `test_ip_chain_key_and_anchor_admission`、`test_snapshot_survives_source_replacement`、`test_link_fifo_permissions_and_intermediate_as_anchor_refused`、`test_validity_and_unknown_fields_refused`。固定时钟验证 7 天边界、两个 origins、错 key、错 SAN、未来/过期、未知根、系统默认根意外回退均拒绝；准入根核对官方来源记录及预批准指纹，不能因上传新 CA 自动准入。关键断言：

```python
assert candidate['leafDerSha256'] == fixture['newLeafFingerprint']
assert candidate['anchorFingerprints'] == sorted(fixture['approvedRootFingerprints'])
assert original_active_bytes == active_path.read_bytes()
# 改源文件后 verify 仍核对 stage 固定字节；重改 stage 文件则 RuntimeError。
```

- [ ] Step 2：运行 `PYTHONPATH=deploy/linux:deploy/linux/tests python3 -B -m unittest test_tls_material -v`，保存缺功能/断言失败的红灯证据。
- [ ] Step 3：实现上述两个函数；安全打开/读取普通文件，固定字节后调用受控 OpenSSL 验证，检查文件属主/mode/路径及链接别名；叶公钥与私钥匹配，IP SAN 与 sslserver purpose，显式根和中间链；保存脱敏来源摘要。实施者在开发环境只读核验 Sectigo 官方公开 R46 根及独立可信根来源，将准确 DER SHA-256、来源 URL、核验日期及证据摘要写入 public-tls-trust-anchors.json 并纳入本任务审阅。取不到可靠证据则该生产锚保持未准入，不填猜测指纹；测试根不得写进生产允许列表。运行时不自动联网拉根或签发。
- [ ] Step 4：同一命令绿灯后运行 `PYTHONPATH=deploy/linux:deploy/linux/tests python3 -B -m unittest test_public_runtime -v`，确认原初始化入口及非公网配置未回归。
- [ ] Step 5：仅提交本任务列出的文件：`git add deploy/linux/ols_linux/tls_material.py deploy/linux/config/public-tls-trust-anchors.json deploy/linux/ols_linux/public_runtime.py deploy/linux/tests/tls_fixtures.py deploy/linux/tests/test_tls_material.py`，然后 `git commit -m 'feat: validate staged public TLS materials'`。

## Task 2: 不可变代次、有效引用与第零代兼容

**Files:** Create `deploy/linux/ols_linux/tls_generation.py`、`deploy/linux/tests/test_tls_generation.py`；Modify `public_runtime.py`、`runtime.py:validate_tls`、`journal.py:begin` 的 kind 允许列表（仅加入 rotate-public-tls；此阶段无 CLI 对外入口）。

**Interfaces:** Consumes Task 1 Candidate。Produces `tls_generation.resolve(root: Path) -> dict`、`tls_generation.layout(root: Path, operation_id: str, candidate: dict) -> dict`（generationId、paths）、`tls_generation.seal(root: Path, operation_id: str, candidate: dict, trust: dict, deployment: dict) -> dict`、`tls_generation.select(root: Path, operation_id: str, expected_parent: str, generation_id: str) -> None`、`tls_generation.paths(root: Path, generation: dict) -> dict`。layout 依据准确原操作和父代预定路径，不选择/激活代次；seal 只发布完整生成目录。select 必须当前原轮换、准确 parent 和已停止切换阶段，不能被普通调用绕过。Task 3/4 先使用 layout 构建 trust/deployment，再 seal，避免先生成清单才能生成配置的循环依赖；本任务测试用显式临时文件夹具提供这两个参数。

- [ ] Step 1：写 `test_legacy_integrity_is_preserved`、`test_invalid_active_never_falls_back`、`test_select_requires_current_operation_and_parent`、`test_partial_generation_is_not_visible`。验证原 publicTlsHashes/settingsDigest/assembly/tls.json 字节不变，HMAC/文件损坏拒绝，单文件 fsync/replace 中断前后只暴露完整旧或新引用：

```python
assert resolve(root)['version'] == 0
assert before_settings_digest == runtime.load(root)['settingsDigest']
assert before_public_hashes == runtime.load(root)['publicTlsHashes']
# 创建无效 tls/active.json 后 resolve 抛错，不返回 version 0。
```

- [ ] Step 2：运行 `PYTHONPATH=deploy/linux:deploy/linux/tests python3 -B -m unittest test_tls_generation -v`，确认新增拒绝/选择测试红灯。
- [ ] Step 3：实现代次 HMAC 清单与唯一有效引用，沿用 journal.safe_root/private_file/_read/_write，不使用 symlink current。原公共封存继续校验完整性，有效材料另外做严格有效性检查；第零代过期只在历史完整性检查允许，不能用于开放健康。
- [ ] Step 4：运行 `PYTHONPATH=deploy/linux:deploy/linux/tests python3 -B -m unittest test_tls_generation test_runtime test_public_runtime -v`，所有原运行防篡改反例保持通过。
- [ ] Step 5：`git add deploy/linux/ols_linux/tls_generation.py deploy/linux/ols_linux/public_runtime.py deploy/linux/ols_linux/runtime.py deploy/linux/ols_linux/journal.py deploy/linux/tests/test_tls_generation.py`，`git commit -m 'feat: seal immutable public TLS generations'`。

## Task 3: 过渡 PEM/Java 信任一致性与 mTLS 身份不扩大

**Files:** Modify `tls_generation.py`、`assembly.py:certificate_der/ensure_trust_anchor/_tls` 的复用边界；Create `deploy/linux/tests/test_tls_trust.py`；Modify `backend/src/test/java/io/github/windyzhu3/ontologylaw/testing/TlsFixture.java`、`backend/src/test/java/io/github/windyzhu3/ontologylaw/api/security/ClientCertificateIT.java`。

**Interfaces:** Produces `tls_generation.build_trust(root: Path, candidate: dict, output: Path) -> dict`（httpTrust/javaTrustStore 路径与哈希、anchorFingerprints）、`tls_generation.verify_trust(root: Path, trust: dict) -> None`。输入内含原内部锚、旧公网锚、新准入锚；不得改旧文件。测试夹具新增 `TlsFixture.ca(String alias)` 与 `TlsFixture.signedKey(String alias, Key ca, String eku)`，返回原 Key 类型，真实签名链；不改生产身份解析。

- [ ] Step 1：Python 写 `test_pem_and_store_exact_der_anchor_set`、`test_alias_collision_and_unauthorized_anchor_refused`；Java 写 `newly_trusted_ca_does_not_register_client_identity`，服务端只导入根、绝不导入测试叶使其绕过链。新 CA 签发 clientAuth 叶 TLS 可接受，但精确未登记叶请求拒绝：

```java
assertEquals(401, unregisteredResponse.statusCode());
assertEquals(200, registeredServiceResponse.statusCode());
assertThrows(BadCredentialsException.class, () -> resolver.certificate(newClientChain));
```

测试还需验证无证书和伪造 X-SSL-Client-Cert 头不能获得 SERVICE；公网上游 trust 扩展不改变 certificates 配置或数据库注册。

- [ ] Step 2：运行 `PYTHONPATH=deploy/linux:deploy/linux/tests python3 -B -m unittest test_tls_trust -v` 与上述 Maven 定点命令，确认新增 trust 生成/真实 CA 链夹具测试的有效红灯。
- [ ] Step 3：生成新 PEM/PKCS12，alias 绑定规范 DER 指纹，导出逐项比对准确集合，使用原密码文件和锁定 keytool；封存实际 store 字节。原 assembly._tls 验证原件，不将新 store 写回旧 assembly/tls.json。
- [ ] Step 4：重复两个定点命令，并运行 `PYTHONPATH=deploy/linux:deploy/linux/tests python3 -B -m unittest test_trust_resume test_assembly -v`。新 clientAuth 负例必须实际完成到应用鉴权层，不能只以 TLS 握手失败充数。
- [ ] Step 5：精确 add 本任务五个文件，`git commit -m 'feat: seal transition trust without widening service identities'`。

## Task 4: 全消费者 TLS 派生部署与安全重启

**Files:** Create `deploy/linux/ols_linux/tls_deployment.py`、`deploy/linux/tests/test_tls_deployment.py`；Modify `identity.py:_start/http`、`deployment.py:bind_release/verify_ready`、`assembly.py:properties`、`runtime.py:start_internal`、`initialize.py:bind_release` 的有效路径接入。

**Interfaces:** Consumes resolve/paths/build_trust。Produces `tls_deployment.prepare(root: Path, operation_id: str, candidate: dict, trust: dict) -> dict`（派生 config/binding/launch 文件路径与摘要，除 TLS 引用外应与原一致）、`tls_deployment.switch(root: Path, operation_id: str, generation: dict) -> None`、`tls_deployment.verify_copies(root: Path, generation: dict) -> dict`。switch 只在原操作 QUIESCED 后登记并处理已停止资源；新容器名按 operationId/role 生成，副作用前加入 registry，保留旧容器；身份原容器不重建。

- [ ] Step 1：写 `test_derived_config_changes_only_tls_paths`、`test_stopped_identity_receives_only_tls_files`、`test_running_or_foreign_container_refused`、`test_future_release_uses_selected_generation`、`test_partial_copy_cannot_start_identity`。测试中的 original/derived 是解析后的配置 dict：

```python
assert derived['serviceCertificateFingerprint'] == original['serviceCertificateFingerprint']
assert derived['issuer'] == original['issuer']
assert recorded_bootstrap_calls == []
assert copied_names == {'server.crt', 'server.key'}
```

- [ ] Step 2：运行 `PYTHONPATH=deploy/linux:deploy/linux/tests python3 -B -m unittest test_tls_deployment -v`，确认缺少有效代次及 TLS-only 复制行为的红灯。
- [ ] Step 3：实现派生 sealed binding/launch，API 入站与身份出站、Worker、Node helper/入口全部引用同代次；保持内部密钥库/CA/服务凭据路径。身份已有 realm/数据库必须核验，仅复制公开 TLS；不重跑 realm import/初始化启动分支。旧 operator.json 只读消费场景用显式有效路径，禁止顺带 bootstrap。发布/启动路径支持代次而不篡改旧 binding。
- [ ] Step 4：运行 `PYTHONPATH=deploy/linux:deploy/linux/tests python3 -B -m unittest test_tls_deployment test_deployment test_identity test_assembly test_runtime -v` 和 Node 入口测试；Node 文件无需为路径切换修改，除非测试证明必需。
- [ ] Step 5：精确 add 本任务七个文件，`git commit -m 'feat: bind native consumers to sealed TLS generations'`。

## Task 5: 已登记 nginx/Caddy 的维护和切换边界

**Files:** Create `deploy/linux/ols_linux/tls_proxy.py`、`deploy/linux/tests/test_tls_proxy.py`。不在仓库存生产配置或改系统服务。

**Interfaces:** Produces `tls_proxy.validate_registration(root: Path, registration: dict) -> dict`、`tls_proxy.prepare(root: Path, operation_id: str, registration: dict, generation_paths: dict) -> dict`、`tls_proxy.apply(root: Path, operation_id: str, action: str) -> dict`、`tls_proxy.observe(root: Path, operation_id: str) -> dict`。action 仅 close/switch/open/rollback；登记只允许 nginx/Caddy 及 docker/systemd 两种明确已审核服务归属，固定 argv 模板，不允许用户命令字段。registration 是私密审核输入，首次复制进原操作 HMAC；新运行注册表只能在准确已有服务/配置证据匹配时建立，不能按名称自动收养。

- [ ] Step 1：写 `test_unregistered_service_or_command_refused_before_effect`、`test_config_check_precedes_reload`、`test_close_is_observed_not_assumed`、`test_reload_response_loss_reconciles_generation`、`test_maintenance_preserves_only_registered_probe_path`。mock 仅替代 OS transport，验证执行次序及反例：

```python
assert actions.index('validate-config') < actions.index('reload')
assert result['closed'] is False  # 停止/维护命令退出 0，但实际转发仍开放
assert effects == []  # 不匹配登记、包含命令字段或未知宿主路径
```

- [ ] Step 2：运行 `PYTHONPATH=deploy/linux:deploy/linux/tests python3 -B -m unittest test_tls_proxy -v`，确认无实现/错误状态判定红灯。
- [ ] Step 3：实现有限动作适配，保存前后配置及摘要，配置验证先于加载。维护阻断公网身份与业务转发；探测只允许审核的回环控制入口，仍使用原 Host/SNI/IP SAN。代理系统根不需变化时保存实际新链验证证据。实际服务无法符合登记/关闭/探测边界时 BLOCKED，不能增加第三种管理平台或绕过 TLS；需修订设计才扩展。
- [ ] Step 4：同一命令通过；此任务仅证明适配逻辑，真实 nginx/Caddy 证据留任务 11，报告中不得称代理端到端已通过。
- [ ] Step 5：`git add deploy/linux/ols_linux/tls_proxy.py deploy/linux/tests/test_tls_proxy.py`，`git commit -m 'feat: control registered proxy TLS transitions'`。

## Task 6: 原生指纹与所有消费者的独立证明

**Files:** Create `deploy/linux/ols_linux/tls_probe.py`、`deploy/linux/tests/test_tls_probe.py`；Modify `verify.py:runtime_ready/ingress_ready`，必要时只增加受控 read-only probe 参数。

**Interfaces:** Produces `tls_probe.handshake(target: dict, trust_path: Path, expected_leaf_sha256: str, *, now: int) -> dict`、`tls_probe.collect(root: Path, generation: dict, *, scope: str, now: int) -> dict`（Probe）。target 固定 connectHost/connectPort/verifyHost/role，来自登记，不接受任意 URL；scope 仅 native/proxy/all。允许直连回环 connectHost，但 verifyHost 必须原 origin IP/DNS，TLS 验证禁止 localhost 替代。

- [ ] Step 1：写 `test_proxy_new_native_old_is_blocked`、`test_connect_address_does_not_replace_verified_ip`、`test_missing_consumer_evidence_is_unknown`、`test_rejected_chain_never_emits_pass`：

```python
assert probe['status'] == 'BLOCKED'  # 外部 new、原生 old
assert probe['targets']['nativeIdentity']['leafDerSha256'] == old_fingerprint
assert partial_probe['status'] == 'UNKNOWN'
```

- [ ] Step 2：运行 `PYTHONPATH=deploy/linux:deploy/linux/tests python3 -B -m unittest test_tls_probe -v`，确认缺少独立原生校验的红灯。
- [ ] Step 3：实现经验证握手提取 DER 指纹，证书链/IP 验证在指纹判定前完成。分别采集身份 24843、入口 24844、桥接两跳和外部入口；内部通过 Java 身份出站实际调用、Node helper、API 上游、Worker mTLS、扫描及身份发现健康取得证据。scope native 不依赖尚关闭的外部转发，不复用旧缓存 PASS。
- [ ] Step 4：运行 `PYTHONPATH=deploy/linux:deploy/linux/tests python3 -B -m unittest test_tls_probe test_verify test_deployment -v`；真实各消费者测试由任务 11 完成。
- [ ] Step 5：`git add deploy/linux/ols_linux/tls_probe.py deploy/linux/ols_linux/verify.py deploy/linux/tests/test_tls_probe.py`，`git commit -m 'feat: verify native and proxy TLS fingerprints'`。

## Task 7: 具名轮换、崩溃对账与显式回退

**Files:** Create `deploy/linux/ols_linux/tls_rotation.py`、`deploy/linux/tests/test_tls_rotation.py`、`deploy/linux/tests/test_tls_recovery.py`；Modify `journal.py:begin/record` 的具名类型支持、`runtime.py:open_ingress/stop_writers` 的受限轮换阶段支持、`release.py:start/stop` 的互斥。

**Interfaces:** Consumes Tasks 1–6。Produces `tls_rotation.begin(root: Path, inputs: dict, *, now: int) -> dict`、`tls_rotation.resume(root: Path, operation_id: str, *, now: int) -> dict`、`tls_rotation.rollback(root: Path, operation_id: str, *, now: int) -> dict`、`tls_rotation.reconcile_pending(root: Path) -> dict | None`（OperationResult）。begin 内先冻结候选并写 tls-pending.json（预分配 ID/父 current ID/候选摘要），再登记 current operation；reconcile_pending 仅修复该准确预登记三者，不扫描收养任意孤立日志。对原 journal begin 新增可选 operation_id 参数时必须只允许准确预登记轮换使用，保持其他调用语义不变。

- [ ] Step 1：写 `test_expired_old_certificate_can_rotate_forward`、`test_old_expired_rollback_cannot_open`、`test_pending_registration_recovers_same_id`、`test_other_operation_cannot_start_or_publish`、`test_candidate_expired_on_resume_stays_blocked`。另在 `test_tls_recovery.py` 用命名 failpoint 枚举每个阶段动作前/已提交后响应丢失，包含 FAILING 和 OPENING：

```python
assert resumed['operationId'] == original_id
assert len(created_operation_ids) == 1
assert original_initialization_bytes == initialization_path.read_bytes()
assert failure['phase'] != 'COMPLETE'
assert reopened_with_expired_old is False
```

- [ ] Step 2：运行 `PYTHONPATH=deploy/linux:deploy/linux/tests python3 -B -m unittest test_tls_rotation test_tls_recovery -v`，每个新增恢复分支先有有效红灯。
- [ ] Step 3：按设计阶段表实现顺序状态机，实例锁覆盖读取/决策/副作用，gate 完整 CAS；在 QUIESCED 后才 switch，在 native/proxy 证据齐全后才 open。复用原 FAILING 处理原则，但不能直接调用会重写 current-release 或原 binding 的发布逻辑。回退完成仍为原 operationId，COMPLETE＋outcome=ROLLED_BACK；到期旧证书不回退，未知停止返回 UNKNOWN。通过观测对账，不通过重复命令伪造成功。
- [ ] Step 4：运行 `PYTHONPATH=deploy/linux:deploy/linux/tests python3 -B -m unittest test_tls_rotation test_tls_recovery test_journal test_release test_review_recovery -v`，校验所有 gate revision 单调、无账号/SQL业务写入调用、未完成操作互斥。不得将 kill 后仍运行的已验证服务谎报为已关闭。
- [ ] Step 5：精确 add 本任务六个文件，`git commit -m 'feat: reconcile original TLS rotation operations'`。

## Task 8: checkpoint 代次恢复与过期恢复阻断

**Files:** Modify `checkpoint.py:INSTANCE_CONTROLS/capture/verify_restore`、`release.py:_restore_assets/_restore_runtime_registry/_assert_restored/start`、`tls_generation.py`；Create `deploy/linux/tests/test_tls_checkpoint.py`。

**Interfaces:** Produces `tls_generation.restore_binding(root: Path, checkpoint_value: dict, *, now: int) -> dict`（generationId、canActivate、reasonCode）；只能验证恢复资产/重建当前引用，不创建轮换或把原 restore 标 COMPLETE。旧 checkpoint 无代次明确采用 version 0；新 checkpoint 包含清单及材料、派生配置和代理期望证据。

- [ ] Step 1：写 `test_checkpoint_restores_exact_generation_not_current_registry`、`test_legacy_checkpoint_selects_zero`、`test_expired_restored_certificate_keeps_original_restore_pending`、`test_newer_tls_audit_is_preserved_in_quarantine`：

```python
assert binding['generationId'] == checkpoint_generation_id
assert binding['canActivate'] is False  # 过期历史证书
assert journal.current(root)['operationId'] == restore_id
assert journal.current(root)['phase'] != 'COMPLETE'
```

- [ ] Step 2：运行 `PYTHONPATH=deploy/linux:deploy/linux/tests python3 -B -m unittest test_tls_checkpoint -v`，确认错代/过期恢复红灯。
- [ ] Step 3：把 tls-pending.json 作为实例控制排除；tls/active、代次及派生配置作为恢复资产核对。恢复准确引用时保留资源 ownership，不从新 resources 推断旧证书。对有效历史证书恢复同步代理期望态再允许原 start；过期证书保持 RESTORED_MAINTENANCE/BLOCKED，拒绝嵌套 rotate。较新材料只进准确 quarantine，引用按摘要定位。
- [ ] Step 4：运行 `PYTHONPATH=deploy/linux:deploy/linux/tests python3 -B -m unittest test_tls_checkpoint test_checkpoint test_durable_proof test_review_recovery -v`，验证旧联动恢复完整性反例仍成立。
- [ ] Step 5：精确 add 本任务四个文件，`git commit -m 'fix: restore TLS generation with checkpoint assets'`。

## Task 9: 状态、到期边界与 CLI

**Files:** Create `deploy/linux/ols_linux/tls_status.py`、`deploy/linux/tests/test_tls_status.py`；Modify `linux.py:parser/dispatch/main`、`deploy/linux/tests/test_cli.py`。

**Interfaces:** Produces `tls_status.evaluate(not_after: int, now: int, last_success: int | None, *, verified: bool | None) -> dict`、`tls_status.status(root: Path, *, now: int, previous_check: Path | None = None) -> dict`（Status）。CLI public-tls-status 增加可选 `--previous-check-file`，只读探测，不写 journal/有效代次；历史证据只接受同实例、签名正确的 checkEvidence，checkedAt 大于 now 即拒绝，不以时钟容差掩盖回拨。调度器将本次输出原子保存到 runtime 外的私密检查文件，下一次传回；首次无历史记录时 monitoringState=UNCONFIGURED、聚合 UNKNOWN，同时保留本次真实握手结果。若本次成功，新的 checkEvidence 可成为下一次的 lastSuccessfulCheckAt；不可把旧失败响应中的时间当成功检查。

- [ ] Step 1：写 `test_exact_expiry_thresholds_and_stale_evidence`、`test_status_does_not_mutate_runtime`、`test_cli_requires_original_resume_and_private_input_paths`、`test_check_evidence_rejects_wrong_instance_mac_and_future_time`，时钟覆盖恰好 30/14/7 天及 >26 小时陈旧；无历史仍保存本次真实 Probe，下一次只采用成功且 HMAC 正确的证据；状态优先证书未生效/过期/指纹失败/未知：

```python
assert evaluate(now + 7*86400, now, now, verified=True)['status'] == 'CRITICAL'
assert evaluate(now, now, now, verified=True)['status'] == 'BLOCKED'
assert evaluate(now + 90*86400, now, now-26*3600-1, verified=True)['status'] == 'UNKNOWN'
assert mutation_calls == []
```

- [ ] Step 2：运行 `PYTHONPATH=deploy/linux:deploy/linux/tests python3 -B -m unittest test_tls_status test_cli -v`，确认缺命令/阈值红灯。
- [ ] Step 3：接入设计中的四个命令；Status 退出码固定 0=OK、2=需关注（含 WARNING/ACTION_REQUIRED/CRITICAL）、1=BLOCKED、4=UNKNOWN；现有初始化退出码 3 不变。rotate/resume/rollback 成功终态 0，拒绝/未知非零且带原 ID。状态输出实际指纹与消费者证据、MANUAL_REQUIRED，检查未配置/证据缺失要明确；当前成功探测与历史调度陈旧分别展示，不能用历史缺失抹去本次实时观测事实。
- [ ] Step 4：重复定点命令，新增 CLI --help 检查：`python3 -B deploy/linux/linux.py --runtime /tmp/ols-help-only --help`；不得创建该 runtime，输出不能要求秘密值参数。
- [ ] Step 5：精确 add 本任务四个文件，`git commit -m 'feat: expose TLS rotation and expiry status commands'`。

## Task 10: 手动导入与可验证签发 hook 边界

**Files:** Create `deploy/linux/ols_linux/tls_import.py`、`deploy/linux/tests/test_tls_import.py`；Modify `tls_rotation.py:begin` 的来源适配调用、`tls_status.py:status` 的签发状态读取；Modify `deploy/linux/README.md` 增加手动导入和只读调度说明。

**Interfaces:** Produces `tls_import.normalize(root: Path, source: dict, *, now: int) -> dict`（Task 1 inputs）、`tls_import.issuance_status(root: Path, *, now: int) -> dict`。source kind 仅 manual 或已审核的 certbot-hook；后者必须准确 lineage、准入提供者/客户端及 IP 签发证据绑定。不提供 ZeroSSL API/ACME 调用，不自动获取凭据或额度；未审核提供者返回 MANUAL_REQUIRED/ISSUANCE_BLOCKED。证书值仍来自私密文件，不从环境变量拼 shell。

- [ ] Step 1：写 `test_manual_import_needs_no_remote_issuer`、`test_unverified_ip_acme_and_unknown_quota_are_blocked`、`test_lineage_change_during_snapshot_refused`、`test_duplicate_delivery_does_not_create_operation`、`test_new_candidate_cannot_replace_pending_rotation`：

```python
assert remote_requests == []
assert issuer['state'] == 'MANUAL_REQUIRED'
assert duplicate_result['operationId'] == completed_id
assert new_operations == []  # 不同候选遇到未完成操作
```

- [ ] Step 2：运行 `PYTHONPATH=deploy/linux:deploy/linux/tests python3 -B -m unittest test_tls_import -v`，确认来源/重复交付反例红灯。
- [ ] Step 3：实现受控 archive 路径解析并固定复制，核心 stage 继续拒绝链接；原 lineage/叶 hash/操作绑定用于幂等，收到不同候选不自动续跑。README 给出私密参数结构、默认阈值、现有宿主每日调度范例和原子结果保存方式，提醒“日志不等于送达”；通知渠道需单独验证，不新增收费服务。Certbot hook 仅成功签发后导入，发行成功与部署成功分开记录。
- [ ] Step 4：运行 `PYTHONPATH=deploy/linux:deploy/linux/tests python3 -B -m unittest test_tls_import test_tls_status test_tls_rotation -v`，证明签发失败不进入维护/停机，旧证书仍有效可继续服务；额度耗尽/未知、外部故障、再次人工签发均是明确阻断而非假成功。
- [ ] Step 5：精确 add 本任务五个文件，`git commit -m 'feat: bound manual and verified-hook TLS imports'`。

## Task 11: 真实隔离验收、完整回归与操作交付

**Files:** Create `deploy/linux/verification/public_tls_rotation.py`、`deploy/linux/tests/test_tls_acceptance.py`、`docs/evidence/public-ip-tls-rotation/verification-matrix.md`、`docs/evidence/public-ip-tls-rotation/report.md`；Modify `deploy/linux/README.md`；代理验证若需额外镜像，只在 `deploy/linux/verification/toolchain.lock.json` 增加经核验的测试工具摘要，不改生产依赖版本。

**Interfaces:** Produces `public_tls_rotation.run(inputs: dict, *, run_id: str, scenarios: list[str]) -> dict` 和 CLI `--run-id <id> --inputs-file <private-file> --scenario all`。输入仅允许显式 verification=true 的独立实例、测试材料、审核代理夹具和证据目录。场景固定 same-ca/cross-ca/expired-old/native-stale/unregistered-client/crash-recovery/checkpoint/issuer-blocked；all 缺一项、跳过、未知均 INCOMPLETE/非零。不把生产 IP/路径默认写进可执行夹具。

- [ ] Step 1：写 `test_missing_or_skipped_scenario_is_incomplete`、`test_production_or_unowned_fixture_refused_before_effects`、`test_proxy_only_success_does_not_satisfy_acceptance`：

```python
assert result['status'] == 'INCOMPLETE'
assert result['exitCode'] != 0
assert touched_production_resources == []
```

- [ ] Step 2：运行 `PYTHONPATH=deploy/linux:deploy/linux/tests python3 -B -m unittest test_tls_acceptance -v`，确认缺场景也返回成功的行为被拒绝。
- [ ] Step 3：实现顺序验收驱动，复用现有 verification/harness、登记资源和测试 CA；不新建通用 runner。真实 OpenSSL/keytool、Java/Node/Keycloak、nginx/Caddy、双库和原材料健康证明记录脱敏摘要；首次失败保留原验证操作 ID 和现场，不自动清理后换 ID“通过”。故障注入仅 verification 标记实例可用。
- [ ] Step 4：运行 `python3 -B -m unittest discover -s deploy/linux/tests -v`、Node 入口测试及 Maven 定点 IT，要求全部实际执行且 0 failure/error/skip。首次完整回归前先确认系统资源，顺序运行，避免并行完整应用栈。
- [ ] Step 5：准备独立本地测试输入后运行 `python3 -B deploy/linux/verification/public_tls_rotation.py --run-id tls-rotation-review-01 --inputs-file /absolute/private/tls-verification-inputs.json --scenario all`。路径为执行者本地创建的私密输入，不能指生产；输出必须每场景 PASS，且原生两个端口指纹、所有消费者、mTLS 未登记客户端拒绝、账号/权限事实保持、故障恢复和过期 checkpoint 阻断证据齐全。若某代理 transport 没有真实环境验证，明确未支持该生产 profile，不宣布部署就绪。
- [ ] Step 6：完成 README 的预检/维护/续跑/回退/status/过期恢复限制与私密材料流程；verification-matrix 逐项链接实际脱敏结果，report 区分测试通过、现场未知和未部署。更新既有 acceptance 说明引用此专门入口，不将本轮测试重新初始化17个生产账号。写入证据前检查真实密钥/令牌未泄露。
- [ ] Step 7：`git diff --check`；精确 add 本任务实际文件，`git commit -m 'test: verify public TLS rotation and recovery end to end'`。整分支独立复核通过后才报告实现完成；不自动推送、合并、部署或开启付费签发。

## 覆盖、自检与执行交接

| 设计章节 | 落地任务 |
| --- | --- |
| 1–3 授权/基线/读取依赖 | 全局约束、任务 1/2/4/11 |
| 4 代次与不变量 | 任务 2/4/7/8 |
| 5 根准入/候选 | 任务 1/3 |
| 6 原操作与过期前向轮换 | 任务 7/9 |
| 7 全消费者/代理 | 任务 3–6/11 |
| 8 崩溃/回退/审计 | 任务 7/11 |
| 9 checkpoint | 任务 8/11 |
| 10 持续可用/手动/自动边界 | 任务 9/10/11 |
| 11–12 验收与交付 | 每任务红绿测试、任务 11 |

自检要求：逐任务函数签名与字典字段一致；没有任务调用未定义接口；五项 Review Focus 已分配反例；准确 R46 指纹、私钥匹配、代理现场登记仍是部署前证据，不能用计划补造；日常监测不等于续期已自动化。计划只新增文档，所有复选框未执行，不能引用拟定测试命令作为测试通过证据。

依赖顺序为 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 11。虽然部分测试可独立编写，本计划不授权并行改共享文件。出现需新签发服务、任意代理平台、恢复内嵌轮换或业务身份合同变化时停止，重新提交设计。

请先审核本计划并选择执行方式：

- **逐任务独立复核**：每任务由新执行者实现，再由独立审阅者检查，最后整分支复核。能较早发现代次/恢复接口偏差，但上下文交接与审阅成本较高。
- **单执行者整体复核**：一名执行者按计划逐项 TDD，完成后由独立审阅者做整分支检查。交接少、通常更快，但独立发现跨任务问题更晚，返工可能集中。

建议逐任务独立复核，因为本计划涉及信任准入、多个消费者和失败恢复的连续边界；此为建议，不替用户选择。没有执行方式确认前，不启动实现或审阅代理。
