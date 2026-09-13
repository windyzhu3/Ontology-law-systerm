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

## 限定续建实施中（同日后续）

用户随后指示继续，按此前明确提出的限定续建范围推进。再次只读检查可用虚拟内存为452528KiB（约442MiB，741d46），因此仅开展工具实现和定点离线测试，不启动服务、不再次尝试真实引导。原主Tenant成功证据、失败哨兵记录及原状态文件保持不变；单独续建记录绑定原文件摘要，先只读验证主Tenant及哨兵零事实，再仅对未写入哨兵取得新候选。实现后独立评审；实际运行仍待资源恢复。Task10.4应用装配尚未开始，未将实现中状态写成验收通过。

## 限定续建代码与评审完成，真实执行仍未开始

实现`eb31e22`新增隔离哨兵专用续建入口和只读组合核验。独立评审指出失败阶段输出摘要缺失、正常/续建证据规则重复两项Important；修复`a5cdf48`逐阶段保存AVAILABLE/MISSING、原始输出摘要及已知退出码，并提取共用纯校验函数。29项Python-only定点测试通过（14.922s），编译/差异检查通过；同一评审者限定复核确认两项均解决，无新Critical/Important。未重跑JVM probe、环境模块或旧业务链。一个畸形列表成员导致CLI非统一错误类型的Minor留待最终分支评审，不涉及写入授权绕过。

Root最终检查170954：原bootstrap树摘要、原state及record摘要与本轮开始完全一致；旧Keycloak严格系统CA HTTPS discovery返回200；backend/apps/contracts/已摘要环境源文件相对本轮BASE无差异。可用虚拟内存788772KiB（约770MiB），仍不启动新服务。当前仅代码/评审门关闭，哨兵实际续建、Task10.4应用装配及后续R1黄金/失败路径仍未完成。下一步先由用户释放资源，再恢复同一保留环境、执行一次受控哨兵续建；不另建整套run、不重发主Tenant、不把离线测试写成真实验收PASS。本轮未推送仓库。

## Docker清理后，Task10.3真实核验完成

用户明确授权清理本次验证不需要的Docker资源。经标签、停止状态与卷引用核对，ee9b2b删除四套废弃Task10.2环境（reviewed/bounded/loopback/utf8）的24个容器、24个独占卷、12个无连接网络；这些Docker数据库快照不可直接恢复，宿主验收记录未删。当前Task10.3六容器/所有卷、旧local-login账号及数据保留；旧三个容器仅停止。未全局prune，未删无法确认归属的匿名卷或自建镜像。

全部容器停止后vmmemWSL仍占约7067MiB，遂重启Docker Desktop（8f25f7退出0，aae442确认running）。可用虚拟内存一度恢复至约7.7GiB。恢复同一新环境两库、健康后恢复同一Keycloak；4494ed严格CA discovery200，没有重建或重跑一次性初始化。

已评审的continue-isolation实际退出0（981549），main-verify、absence-query、candidate、dry-run、execute、verify、fact-query七阶段全部0（8addab）；随后verify-combined退出0（5ebde4）。原state与bootstrap record摘要仍与初始锚点一致。主Tenant只读核验，未重发主Tenant写入；哨兵新原始命令及证据保存于单独保护目录。Task10.3实际引导前置完成，尚不代表登录/黄金链/R1总验收通过。

后继Task10.4只装配同Jar API/Worker和同SPA。prepare显式选择original或continued引导核验源，记录后禁止fallback或改写。当前新三服务运行，旧三个local-login服务保持停止以留出资源，旧页面登录暂不可用但账号/realm/业务数据完整可恢复。当前可用虚拟内存约4.6GiB；后续仍只执行未完成路径。

后续用户报告Docker Desktop再次退出。78d351确认引擎已重启、原三服务同时退出255且OOM=false，未认定退出原因。e7c89f进一步删除9个无容器使用且当前配置未引用的旧公共基础镜像，可按标签重新拉取；Docker镜像占用16.52GB降至10.31GB，未知归属匿名卷及自建镜像未删。原两库恢复后，Keycloak启动组合命令被执行策略拦截，未采用替代写入路径；用户通过Desktop手动启动原容器。4dc2b0确认三服务running及严格TLS200，d2094d只读verify-combined退出0，原引导结果有效，没有再次执行bootstrap。Task10.4实施继续；SERVICE readiness要求已按既有合同更正为204/空body/无ETag/no-store，不修改业务接口。

## Task10.4应用准备：零写入失败后的限定修复

应用装配代码及首轮独立复核已完成（`5d9b82c`、`d93572b`），首轮修复覆盖准确API监听地址/端口/PID及PID不匹配拒绝。一次真实prepare随后退出1，尚未start：既有`law_app_command`角色不能取得工具请求的`SHARE ROW EXCLUSIVE`表锁，日志为`permission denied for table tenant`。只读查询原保存SERVICE主体、任职和三条授权ID，计数均为0；没有成功写入SERVICE前置，也未重跑租户引导。

用户已明确批准保留失败目录及日志，修复和独立评审后在同一验收环境进行一次新的应用准备。修复仅移除不必要的强表锁，保留SERIALIZABLE、既有角色、原前置及部署摘要检查、五行写入闭合；不增加数据库权限、不改业务接口。此刻失败证据仍原样保留，新的准备与应用启动尚未执行，不能记Task10.4实际就绪或R1总验收通过。

## Task10.4实际应用基础设施就绪

限定修复`8b78f50`只删除两行强表锁并增加定点回归测试；14项Python测试通过（3.952s），编译/差异检查通过，同一独立评审者确认规格及质量通过。Root再次只读确认失败准备的主体/任职/授权均为0（bb5c0a），按用户授权将原目录归档为同run内`applications-failed-lock-20260913`，全部文件内容及文件ACL前后完全一致（6632a2）。失败记录未覆盖或删除。

在同一环境一次新prepare退出0（58660f），一次start退出0（511163），随后verify退出0并返回`APPLICATION_INFRASTRUCTURE_READY`（3fd71a）。本次实际执行证明既有运行时角色下SERVICE五行前置及只读闭包、同Jar API/Worker、同SPA、进程/监听边界及严格TLS就绪链通过；没有增加数据库权限或重发租户引导。

Task10.4装配门现已关闭。该状态不是新环境真实用户登录、责任卡黄金/失败链或R1整体PASS；后续继续Task10剩余受控管理fixture与业务验收。Task9人工确认关闭、W09按用户要求暂缓的状态保持不变，不据此重跑已通过项目。

## Task10.5最终交接：用户要求停止验收

用户明确要求R1验收到此为止，仅提交并推送现有修改。以下各节为历史记录，不构成继续执行授权。当前R1整体验收未通过，后续测试与修复停止；旧启动及续验说明均停用。

最新源码提交为 `bfa4150`（含 `ee915e4` 的原线索补齐衔接）。最后一次真实执行累计18项CONFIRMED、0项PENDING：16项管理、1项接入、1项信息补齐草稿保存；补齐提交及首联草稿/提交未完成。原操作及记录保留，本轮失败证据归档于受保护运行目录的 `attempt04-ingress-draft`，不纳入Git。最新journal SHA256为 `F40706632523314AC8E6E7F6B1F06F7CE04F2C84B3219E9A0A3B7805FE857DF2`。

已定位但未修复：验收工具整页刷新后没有重新执行OIDC登录及任职确认，生产入口因此不发出当前责任查询；信息补齐和首联两处刷新均受影响。现有离线测试替代了刷新适配器，没有覆盖该页面生命周期，离线通过不能算真实黄金链通过。不修改产品登录安全约束，不重放已成功命令。最后一次桌面临时CA清理结果仍待用户确认，不能沿用上一次的清理结论。

Task9人工确认关闭与W09用户延期保持原状态，参考容量验收仍未完成，附件/通知及语音继续排除于本轮之外。

## Task10.5范围与临时浏览器信任批准

用户已确认新隔离入口方案及本轮临时CurrentUser Root信任。新入口只配置三名已导入合成用户、两个子组织、三任职和七项DIRECT授权，并验证一条有效接通黄金链；不重建IdP账号，不变更旧环境、业务页面、接口或数据库权限。接入使用既有HUMAN API，责任办理走真实页面。

新CA当前不在Windows CurrentUser/LocalMachine Root（0d77d9）。仅允许Root在代码独立评审通过后、实际运行前临时导入准确CA，验收结束或失败后移除准确指纹并验证；源码不负责信任安装，不关闭TLS。此记录是批准及执行边界，不代表已经导入证书或业务验收通过。

## Task10.5代码门关闭，真实浏览器仍受信任差异阻断

新入口及限定修复已提交至 `45891ea`，独立复核通过；定点 Python 9项、离线 TS 8项、类型检查通过。该结果仅关闭工具实现门，不是黄金链或R1整体通过；不重复执行未变化的离线测试。

临时CA导入后，用户刷新桌面证书管理器并以本机 `certutil` 查询，均确认准确指纹 `9AC8D2FA7A721BE1AE903A03F5847620F86643CC` 存在。此前自动化进程据自身查询声称“清理完成”的结论撤回；临时信任清理义务仍保留。自动化查询仅见46张证书，而桌面见47张；同一固定Chromium只读访问SPA返回 `net::ERR_CERT_AUTHORITY_INVALID`，没有登录或业务写入。

只读令牌对照显示同一Jacob用户下，自动化进程为未提权令牌，用户查询成功的PowerShell为管理员令牌；尚不能据此确认底层根因。下一步比较普通非管理员PowerShell的只读查询，不将整个验收提权、不关闭TLS、不反复导入/删除证书。原操作编号 `ad68a0fa-ede3-431d-a99e-664dccfd7275` 尚未启动黄金链，不创建第二个业务尝试。

当前顺序：先完成已批准的15次管理命令与单条接通黄金链，再补矩阵中的重复确认、路由/主管重开与耗尽分支及会话/权限/恢复缺口。历史候选、代办关系、身份禁用和故障注入按各自精确授权边界实施，不从“继续验收”推导额外权限。历史六卡不整体重放，U01～U03维持用户关闭，W09延期与参考容量环境待提供分别列示；附件/通知及语音不进入本轮。

## 首轮桌面启动失败与启动说明修正

用户实际启动固定Playwright黄金链，退出1且只报告 `R1_GOLDEN_CLOSED_FAILURE`。本轮业务journal尚未创建；按源码顺序尚未进入管理/业务命令调度。原last-run及error-context已原样归档，SHA256核对一致，目录仅Jacob/SYSTEM可访问。用户最后的独立certutil查询返回NTE_NOT_FOUND，确认本轮临时CA已经移除；末尾误粘贴Markdown反引号与此前测试失败无关。

启动说明漏列 `pwsh.exe` 依赖：机器/用户PATH没有该程序，自动化环境却注入了固定运行时目录。使用普通PATH只读复现得到FileNotFoundError/WinError2，位置为环境保护核验启动pwsh；仅在进程PATH前置现有运行时后，只读完整前置检查退出0，原环境摘要不变。未修改业务源码、系统PATH或权限，也未再次执行业务测试。

修订的[普通桌面单次启动说明](2026-09-13-task105-operator-launch.md)已补齐本次进程依赖及准确临时CA恢复/核验/清理。下一次启动沿用尚未写入的原操作编号；首轮失败证据保留，不设置重放/continuation开关。黄金链和R1总验收仍未通过。

## 第二次桌面执行：管理链成功，授权前置冲突阻断黄金链

本次15项管理命令全部返回201且journal为CONFIRMED，阶段为MANAGEMENT_COMPLETED，无PENDING。只读真实数据库按journal准确资源ID核对：ACTIVE主体3、组织2、任职3、授权7。原journal及失败文件已按摘要归档，成功命令不重跑。用户执行记录证实临时CA已删除且独立查询不存在。

源码诊断定位到验收方案冲突：固定fixture不给sales任何授权，却要求其canEnterWorkbench=true并被自动分配。冻结服务端要求显式SALES_CONTACT_OWNER，CONTACT_OPERATOR任职不隐式授予该权限；缺权会同时阻断工作台与自动候选。不能放宽测试断言或修改产品授权规则来凑通过。

下一步需明确批准仅为本轮合成sales追加一条SALES_CONTACT_OWNER DIRECT授权，最小范围限定OWNED_ROOT；随后修订固定操作清单与受控续验逻辑，保留已成功15条原命令及失败证据，独立评审后从未完成部分继续。未批准前不增加授权、不续写、不重建环境。当前R1仍未整体通过，但管理建档真实链已经取得成功证据。

## 最小销售授权修订已实现并独立评审

用户批准仅补本轮sales的OWNED_ROOT范围SALES_CONTACT_OWNER后，验收工具修订提交 `ff34b15`。原15项与MANAGEMENT_COMPLETED历史含义不变，新增独立SALES_AUTHORITY_COMPLETED；累计16项管理命令、19次写操作。现有检查点续验仅追加新授权和剩余接入/草稿/首联提交，未知结果、错误阶段/编号/环境或旧capture位置均拒绝。

13项离线TS测试（2.4s）、9项Python测试（0.148s）、严格类型检查及diff检查通过；独立评审确认规格及质量通过，无新Critical/Important。Root另核对原journal摘要不变、产品与数据库合同无差异、续验命令语法正确。尚未执行新增实际授权或黄金链，不标R1通过。

下一步按[原检查点受控续验](2026-09-13-task105-controlled-continuation.md)在普通桌面PowerShell执行一次；固定原操作编号和原journal摘要，不重放15项。证书仍仅临时CurrentUser作用域，结束后准确清理。失败保留证据并停止新写入，不自行重试或更换编号。

## 上游核对

2026-09-13复核[官方26.7.3发布页](https://github.com/keycloak/keycloak/releases/tag/26.7.3)与[官方安全公告目录](https://github.com/keycloak/keycloak/security/advisories)。当前发布页列出26.7.3及其安全修复；检查的[DCR角色伪造公告](https://github.com/keycloak/keycloak/security/advisories/GHSA-95cx-vmr5-3cmr)列26.7.1为修复版本。另核对[reset-credentials问题记录](https://github.com/keycloak/keycloak/issues/51833)，已关闭并标注26.7.2等版本。此为部署前具名上游核对，不是全量漏洞扫描或“无CVE”保证；不升级锁定制品，不开启重置密码、动态客户端注册或扩大目录权限。
