# Task10 隔离环境实施与运行记录

本文件只记录已发生的检查，不是R1运行通过报告。

## 当前制品准备

- 工作树：`codex/r1-lead-contact-vertical-slice`，构建来源HEAD `320f917d13e98938d39e4cb0ab546f89363b840a`；构建前后backend/apps/contracts及根依赖清单均无tracked差异。
- 命令：固定`JAVA_HOME`后执行`mvnw.cmd -f backend/pom.xml -DskipTests package`。
- 实际退出0，BUILD SUCCESS，耗时60秒，2026-09-13 09:50:51 +08:00结束。Java25.0.4.1（Eclipse Adoptium），Maven3.9.16。
- Jar SHA256：`6c216bbc46aca573a0ca08c2bcc6d87a2e0cea0286bc44b89a94d5817e9f6675`。同一Jar供后续API/Worker；当前未启动该制品。
- 测试明确跳过，不计为测试通过。保留生成器OpenAPI3.1、mutualTLS识别日志、注解缺失及unchecked/deprecation诊断；不称输出无警告，不因构建成功豁免真实安全验证。

SPA一次构建：固定Node24.20.0/npm11.9.0，`npm run build --workspace apps/workbench`（实际`tsc --noEmit && vite build`）退出0，Vite构建30.53秒。公开配置为issuer `https://localhost:29443/realms/r1-e2e`、client `r1-e2e-spa`、audience `r1-e2e-api`、origin `https://localhost:29444`。不修改源页面或冻结样式；没有运行浏览器场景。

| SPA制品 | SHA256 |
|---|---|
| index.html | 493139296416c4ca0b4853086b7427afc43ae18ea6e8e2ec4b9a3ee942e5ffcf |
| assets/index-DrFrRPp6.css | b2655a9fed6a44d8491d8ce4a3f1476160f006ce45bfba35820935cf253a7d2d |
| assets/index-IiZQqBPh.js | d4d0e4e919a6ae1c68b92247879c2711f094e4dab1ee2366537fd4ee55d24e7e |

## 隔离与执行限制

只读检查时原本地Keycloak、业务PG、身份PG三个容器均运行；Docker总内存8183173120字节。没有停止、重启或修改它们。本轮计划新测试端口29443～29446，与旧19443～19446分离。两个数据库仍独立，身份服务继续用锁定Keycloak26.7.3与PG18镜像。

Task10.2初版`718c99b`及准备run `task102-20260913`完成，10项工具测试与Compose配置解析通过。初评发现启动输入摘要/即时权限复核不完整、工具路径与Java build校验问题，Root补充runtime-logins缺verify-full；三项进入第一轮修复。原准备run保留不改。

第一轮修复`2103a57`完成，17项定向测试通过（21.062秒）。Root执行`D:/soft/python3/python.exe e2e/runtime/r1_environment.py prepare task102-reviewed-20260913`退出0。其来源SHA为`2103a5791e1e6b2e9597603135ab3c8f9422c113`，Jar和SPA摘要与上述新构建相同；`git diff --exit-code 320f917..HEAD -- backend apps/workbench contracts package.json package-lock.json`退出0，证明这些环境工具提交未改变构建输入。manifest仍如实标记准备器本身不证明构建来源。

限定复核后三项均ADDRESSED、无新Critical/Important；保留一个中间目录链接检测Minor供最终评审。随后实际执行`start-infra task102-reviewed-20260913`，退出1，状态原样保留为`START_FAILED / compose-up`：Compose的`--wait`把叶子一次性任务runtime-logins的正常退出0报告为错误。本次并非迁移失败：实际Flyway日志显示PostgreSQL18.6、应用21个迁移到v860、校验22个迁移成功。没有把这些局部成功改写成基础设施READY。

随后宿主执行docker时出现“页面文件太小，无法完成操作”。只读inspect确认新三个长运行容器memory=0（未限制），未报告OOM；这不足以证明页面文件错误的唯一原因。Root核验精确Compose项目标签后仅停止本run Keycloak与两个PG（退出0），未删除容器/卷/证据，原三个本地服务仍Up3days。停止后的主机采样：物理可用2165520KiB、虚拟可用1209968KiB；未修改页面文件、Docker内存或原服务。

第二轮修复`07ae5a9`完成并经限定复核通过：区分常驻就绪与三个一次性任务的退出码，常驻容器总上限3GiB，明确不是容量验收；不新增重放/manifest改写机制。20项定向测试通过（33.444秒），编译/Compose quiet配置/diff检查退出0。Root新建`task102-bounded-20260913`，prepare退出0，复核后才启动。

该run实际数据库就绪、keycloak-files、Flyway、runtime-logins和Keycloak启动阶段均退出0；Keycloak26.7.3在03:04:10 UTC容器内监听HTTPS，memory=1536MiB、OOM=false。然而宿主ConnectionRefused，`docker port`为空：`NetworkSettings.Ports`的8443为空数组，尽管`HostConfig.PortBindings`有127.0.0.1:29443；业务数据库同样无实际映射。Docker版本29.4.3、网络internal=true。就绪工具最终退出1，保留discovery失败，不计READY。确认拒绝连接后Root仅停止本run三个已核验项目标签的服务，资源/证据未删除、原三个服务仍运行。停止发生在只读discovery超时结束前，拒绝连接及空映射证据均在停止前取得。

网络原因按[Docker官方多网络说明](https://docs.docker.com/engine/network/#connecting-to-multiple-networks)结合实际inspect判断；[Moby同类报告](https://github.com/moby/moby/discussions/53256)提供相同症状旁证，不替代本机事实。用户已批准仅为新环境Keycloak与业务数据库各加独立普通bridge，仍仅localhost发布，身份数据库保留internal，不改原环境或宿主防火墙。第三轮限定修复执行中，并将实际端口映射加入fail-fast。Root另以`openssl verify -x509_strict -purpose sslserver -verify_hostname localhost -CAfile <本runCA> <Keycloak证书> <业务DB证书>`确认两个证书均OK/退出0；未关闭TLS或安装全局信任。

第三轮修复`0da973f`及23项测试（46.912秒）通过限定复核后，Root实际启动新run `task102-loopback-20260913`：回环映射、各初始化阶段和严格CA discovery均通过。然而最后Python默认GBK解码Docker输出中的U+2026时发生reader-thread异常，进程仍退出0并写出READY，`infrastructure-processes.json`却为`[]`。不采信该不完整的工具收口结果。Root另以bytes读取同一只读`compose ps -a`，UTF-8解码19733字节成功，实际6条记录为两库running/healthy、Keycloak running、三个oneshot exited/0，准确属于本project；问题是捕获编码及缺少清单完整性门，而非服务未启动。

同run另一次只读服务协议检查：用受保护directory secret取得仅内存持有的服务token，再精确查询本run合成founder，HTTP200且唯一准确匹配；未输出凭据、未使用HUMAN password grant、未写业务数据。此前默认scope引用WARN没有阻断此次目录读取，但仍不替代完整浏览器登录验收。取证后Root核验项目标签，仅停止本run三个长服务（退出0），保留容器/卷/原manifest和缺陷snapshot；原三个服务仍Up3days。

当前第四轮限定修复：改为明确字节捕获/UTF-8解码，异常不吞掉；验证实际6服务完整清单才允许READY。按既定评审规则由新实现者处理，并补真实子进程Unicode输出、坏字节/清单失败的定点测试。不会修改旧run的空snapshot冒充原始成功，也不新增恢复框架。Task10.3暂挂在此前置修复后；尚未执行bootstrap、API/Worker/SPA启动或黄金/失败链。身份引导仅复用现有IdentityBootstrapCommand，不增加产品身份能力或SQL HUMAN捷径。

后续浏览器准备：已拉取锁文件中Playwright linux/amd64 digest `sha256:bc6ab0d6d44ff4826e4cb8c1e6d801e185bfc42bb0753f8e2a30efc70db054c7`，退出0。一次无网络/只读临时容器检查显示Node24.20.0，但`command -v certutil`退出1；容器随检查退出移除，未挂载业务材料或启动浏览器。新CA的隔离浏览器信任配置尚待补齐，禁止为赶进度开启ignoreHTTPS或安装宿主全局信任。Linux Chromium支持用户级NSS库，见[官方证书管理说明](https://chromium.googlesource.com/chromium/src.git/+/refs/heads/main/docs/linux/cert_management.md)；后续实现须绑定工具版本及实际TLS正反例，不能只据文档宣称登录通过。

辅助工具已定点核对：镜像现有`libnss3=2:3.98-1ubuntu0.2`、`libnspr4=2:4.35-1.1build1`。从Ubuntu签名APT索引读取同版本`libnss3-tools`元数据，并从官方archive下载615170字节deb，SHA256实测`e5b42390b02c21851bc04e9557ec48539a913f9b09637220c4b1db0d1386b3fb`与索引一致。在一次性无网络、只读根文件系统容器中解包并创建/读取空NSS数据库退出0，未安装/升级镜像库或宿主软件。首个探测因/tmp被设noexec而退出1，改为仅该临时容器的可执行tmpfs后通过；失败不涉及产品。此证据只证明辅助工具可用，尚未导入环境CA、启动浏览器或验证TLS登录。

用于重复确认的历史Party/已解析Lead候选、DelegationGrant测试关系、合成账号禁用/恢复和故障注入，已向用户请求仅新隔离环境的具名授权；未收到回复前不执行这些操作。该授权不影响环境源代码装配，不能扩展到旧环境或真实数据。

## Task10.2实际收口

第四轮修复`c63c849`完成：bytes捕获、严格UTF-8调用线程解码、保护原始输出、六服务快照精确校验；Windows CRLF返回文本规范化及keytool源头UTF-8均有真实子进程覆盖。最终30项本单元测试通过（48.538秒），编译及diff检查退出0；两次中间失败保留在实现报告，没有把其改写为通过。限定独立复核三项均ADDRESSED、无新Critical/Important。

Root随后使用全新`task102-utf8-20260913`：prepare退出0（b5b5aa），复核后start-infra退出0（d3b494）。实际回环映射、三个一次性任务退出0、严格CA discovery及六服务完整快照全部通过，状态`INFRASTRUCTURE_READY`，`applicationReady=false`。旧三个本地服务仍Up3days（623dd8）。先前失败run及不完整snapshot不覆盖、不删除；本次不重跑旧六卡或等待刷新。Task10.2基础设施前置关闭，继续Task10.3，不等于新环境业务黄金链或R1总验收通过。

## Task10.3资源约束

后继实现者的Task10.3合成10测已通过；额外运行未改的环境测试模块时，宿主JVM出现native memory/G1 mmap分配失败。Root仅提取固定故障原因行，没有输出原始崩溃日志；实现者确认两次环境模块尝试产生7个日志，已逐一移除继承并限制当前用户/SYSTEM，未提交或删除。该失败不代表Task10.3真实bootstrap或业务验收失败，二者尚未执行；不再重跑该未改模块。

宿主采样freeVirtualMemory=185420KiB、freePhysicalMemory=796828KiB。Root逐个核验精确新Compose标签后停止新run三个原容器（Keycloak b0b955、两库d28358，均退出0），保留所有卷/数据/原state及snapshot，不改旧环境或宿主页文件。此时新环境实时状态为暂时停止；Task10.2此前实际通过证据仍保留，但不能将当前停止状态称为就绪。实际bootstrap之前须恢复同一原容器并重新只读验证端口、健康和严格TLS，不重跑初始化oneshot或伪造READY记录。

停止新三个服务后再次采样freeVirtualMemory=873172KiB（约853MiB）。为避免盲目恢复后再次触发资源失败，已请求用户仅临时停止旧`ontology-law-local-login-keycloak`并在验收后恢复同一原容器的具名授权；未获批准前不操作。期间旧登录/续期将不可用，旧DB、账号、realm、卷、配置保持不变。该授权与此前新网络批准不同，不能混用；在此文更新时仍待回复。

## Task10.3代码评审收口（非真实初始化通过）

实现`1bf8708`独立评审发现两项Important：保护输出写失败可能漏记已知退出码，原stage stdout/stderr未全部纳入摘要校验。限定修复`cb92f197581a873b8a794b141a37c0b644e0291e`完成：输出持久化异常携带已知退出码并停下；两个Tenant全部stage原始输出的准确路径/摘要在任何original verify调用前校验。最终14项本单元测试通过（4.011秒），编译及diff检查退出0；独立复核两项均ADDRESSED、无新缺陷，没有重跑环境模块。

代码/评审门已通过，真实bootstrap仍未运行，Task10.3不能整体记为运行验收通过。下一步为资源授权后恢复同一新容器并完成实际bootstrap，再实施Task10.4同Jar API/Worker及同SPA装配，之后补黄金/失败路径。旧环境、W09延期、UAT关闭、R2排除及其他待授权测试范围不变；本轮未推送仓库。

## 2026-09-13授权后的首次真实bootstrap

用户确认仅临时停止旧Keycloak以释放资源。Root停用唯一原容器`e2486c1bca2b`，未操作历史failed-feature容器；恢复新run原两库和Keycloak，不重跑初始化oneshot。实际两库健康、准确回环发布和新CA严格discovery通过（5958f8）。

随后唯一一次`bootstrap task102-utf8-20260913`非零退出（40eb2a），原state/record保留失败。candidate、dry-run均退出0；dry-run stdout为2362字节，含jOOQ INFO logo/tips/version日志及末尾一个准确`DRY_RUN` JSON，整个stdout不是单JSON；execute未被调用且无execute输出。原候选有效期300秒，不能事后续用或静默更新原manifest。

Root另发现同一工具后继Fact查询直接读取`audit.audit_entry`，在既定`law_app_query`角色下被拒绝；既有授权仅允许`audit.audit_entry_classified_v`，生产bootstrap原始核验同样使用该视图。没有增加GRANT或改权限。改用既有分类视图进行独立只读核对（95626c）：本原Tenant在tenant、principal、organization、appointment、grant、slot、receipt、audit八项计数均0，证实未产生初始化事实。

实现者限定修复标准输出日志分流和分类视图目标，不改业务Jar/DDL/权限，不增加恢复框架。原失败run及命令全部保留；确认零副作用后，后续仅建立新的独立合成run承接验证，不修改或重试原命令。成本是一次额外的隔离准备/迁移，不重放旧业务验收。

修复期间Root停止新三个原容器，恢复同一旧Keycloak。原容器完整ID、配置摘要及挂载摘要与停用后基准一致；旧容器挂载列表为空，因此必须保留其原可写层，不得删除/重建。初次停用前的挂载摘要命令因空数组序列化报错，没有获得停用前摘要，不将后取摘要冒充此前证据。恢复后旧`local-r1`通过系统信任的严格TLS发现200（847dac），旧数据库未停用、未修改账号/realm/配置；未读取旧私密runtime文件。

## 第二轮真实引导：主Tenant成功，哨兵受资源阻断

限定修复`bc66d1f31a107fdd842d062fdf53cbe65c4d881b`完成：受保护并绑定摘要的Logback配置将日志定向stderr，保持完整stdout单JSON；Fact查询只用既有分类视图。17项定点测试通过（5.830秒），包含真实固定JDK及原Jar日志依赖的无数据库探测；独立复核两项均ADDRESSED，无新Critical/Important。原保留dry-run预览另只读通过现有语义校验（014024），不据此更改生产解析器。

全新`task103-stdout-20260913` prepare退出0（31681a），经复核后start-infra退出0（ca6089）；仅初始化本新隔离环境。随后一次bootstrap（03e692/d7f51b）整体非零：主Tenant的candidate/dry-run/execute/verify/fact-query全部退出0，模式依次为DRY_RUN、CREATED、VERIFIED_ORIGINAL，原Fact查询成功。主Tenant是真实已创建及原始核验通过，不能重发或换原命令。

哨兵Tenantcandidate退出0，dry-run退出1、stdout572字节为JVM原生内存不足诊断，未调用execute（dd2c4e/ae5e95）。此时freeVirtualMemory仅21520KiB、freePhysicalMemory985140KiB。不是新的JSON/业务规则故障，也不能将整组记PASS。原run state和record保持失败，成功主Tenant原settings、命令、HMAC、全部stage输出及Fact记录保留；哨兵失败记录同样保留，不补造成功。

Root核验精确新项目标签后停止新KC `6c84bc51d725`和两库`215ad4b9530c`、`5f7092e200dd`，保留容器、卷和数据；恢复旧原Keycloak `e2486c1bca2b`（df9df4）。18718e确认旧配置/挂载摘要不变、旧两库仍Up3days，85b0d3确认旧local-r1严格TLS发现200。新增`hs_err_pid42168.log`与`replay_pid42168.log`已按精确文件保护，仅当前用户/SYSTEM可访问，未提交、未删除，未输出原始日志；前7份崩溃诊断也保留。

恢复旧服务后本机freeVirtualMemory仅525960KiB。已请求用户释放资源（建议开始前至少3GiB可用虚拟内存）或提供独立环境，并另行批准仅续建未写入哨兵的最小入口：先验证原主Tenant完整闭包及哨兵零写入，保留全部失败证据，只对未写入哨兵重新取得候选，不重发主Tenant。此方案在批准前不实施；不通过继续新建整套run丢弃已成功主Tenant。Task10.4保持未实施，R1/R2门禁不推进。

## 上游核对

2026-09-13复核[官方26.7.3发布页](https://github.com/keycloak/keycloak/releases/tag/26.7.3)与[官方安全公告目录](https://github.com/keycloak/keycloak/security/advisories)。当前发布页列出26.7.3及其安全修复；检查的[DCR角色伪造公告](https://github.com/keycloak/keycloak/security/advisories/GHSA-95cx-vmr5-3cmr)列26.7.1为修复版本。另核对[reset-credentials问题记录](https://github.com/keycloak/keycloak/issues/51833)，已关闭并标注26.7.2等版本。此为部署前具名上游核对，不是全量漏洞扫描或“无CVE”保证；不升级锁定制品，不开启重置密码、动态客户端注册或扩大目录权限。
