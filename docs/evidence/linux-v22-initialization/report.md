# Linux v22 发布与海华初始化验收报告

执行日期：2026-10-07。实际 Linux 三实例全场景运行 `linux-v22-final-a7ef88313cc7`：PASS；四条实际页面业务链均完成到案管接收分类。全分支独立代码审查作为交付最后一关，尚待完成。

## 初始化与业务准备

生产形态空库实测为 7 个组织、17 名 HUMAN、20 个 HUMAN 任职、280 项 HUMAN 授权，技术 SERVICE 单独核对；业务事实为 0。两位主任真实页面登录、原 PKCE 回调会话捕获及 CLI 停止/启动/健康/只读初始化核对通过，另外 15 人仍需首次登录改密。

配置 SHA-256（规范 JSON）：`eecd0411311c9913c625b292e8708769668be4eac35a4aa32a51ed868b548685`。主任保留全所业务、身份管理及审计查询；不做审计导出。初始没有 LEAD_ASSIGN 任职授权，需管理员独立指派。其他五位案管人员本阶段没有业务职责；陈路负责监督，不确认付款。

正式三类模板未获审核，默认代表丁启明仍需逐份核验授权；电话待补，收款显示仅为验收信息。模板摘要：

- `consulting` PDF：`b122637e63606c1da65a7489d333df4a5dd513695ac9bed99168a49905600415`（`CANDIDATE_NOT_APPROVED`）。
- `civil-litigation` PDF：`54fa9270104827ad5be1cdad32075b10094de5f2f4be70fb74629031de70b7b8`（`CANDIDATE_NOT_APPROVED`）。
- `enforcement` PDF：`1ca9d21b7d4fd0a73ac1eaadc8005a97fba2118d0f838a04157e84d24fbb1232`（`CANDIDATE_NOT_APPROVED`）。

## 真实原生制品与迁移恢复

实际最终 JAR/SPA 来源提交：`0ba4d8f091cbe3bcb4bc56e66d35067dff90bbbc`。之后的提交仅调整验收编排和交付文档，封装的运行负载不变。两个实际原 issuer 的 SPA 分别构建；共享真实 Linux 构建的 JAR `8d1712f7d49c67eec2f59089bdb3b8e8870d0756778474fca000c6ced6914a11`。

- 空库最终 descriptor：`4e187e489efa7450cd6ee6d448059fe5421e7d8354f0af071bedb7dd9777a4cc`。
- 合成业务最终 descriptor：`3c7fa11ce1da67b6ba043f011884d4d3710363fc7ae60a683734111f9752bcf0`。
- 实际运行镜像：`sha256:beb0276a4ab09100c6205ec84d6cb517684a88dc5b615464945730980052e733`；其他锁定镜像和 JDK/Node/npm 下载摘要见仓库的 `deploy/identity/identity-toolchain.lock.json`、`deploy/linux/runtime/toolchain.lock.json`、`deploy/linux/runtime/scanner.lock.json`，没有使用浮动版本代替锁定引用。

v20 原生源码 `5eded7f71a26bf24cfbb7f14b548d418eeb96c0e`，实际原 JAR `74866d6a5d667b079527e715344835a0c2c5dc17728f7933ff61b92a367ece5c`。真实迁移候选源码 `0523c7e90684fa331fce2c4ea531b951f5812ae5`，descriptor `c4bb797a99f605dda7a8ba0fceab82510a706f3fd146d9184b38dc380ecbea50`；其两次迁移中断、续跑及旧版联动恢复已完成。随后最终协议修复在上述最终发布的两个 v22 实例实际验证，不把旧场景候选冒称最终提交。

原操作 `af18eba4faa8414ba61981ad91540b1c` 在 V1070、V1080 各提交后模拟响应丢失，续跑核对实际 history，不重跑 DDL。最后一次已提交未知结果之后，日志的两个 validate 分别来自同一次原续跑前后核对，不能解释成再次执行两次迁移。43 份 SQL、41 份历史前缀保持原字节，没有新增迁移或修改数据库合同源。

实际双库、角色、DATABASE ACL、配置、材料、身份、证书/密钥和旧制品联动恢复；启动前全业务事实摘要 `73c42f7ffdedcaa187d709f6d4fc501573edd8aba6f7ab9d58c96f7a68635da6` 精确相等。原 1 条真实页面录入的旧线索保留；恢复后完整服务健康、两主任真实 PKCE 登录通过。重新运行只允许既有 `platform_meta.r2_opportunity_checkpoint` 正常轮询变化，120 张业务事实表中其他摘要仍与原检查点相等。

## 四条真实页面业务链

合成业务使用独立登记的实例、独立管理员分配授权及另行审核的明显合成模板。正式初始化实例没有接收这些授权、材料或交易。全部业务通过真实页面办理；数据库仅用于只读核对实际关联与经办事实。

| 案例 | 部门 | 入口 | 原成功回执数 | 最终案件 |
| --- | --- | --- | --- | --- |
| HH-G01-20261001-R2 | 销售一部 | 引用报价 | 35 | `01a115e9-fe9c-7c07-955a-db7331217ad3` |
| HH-G02-20261001-R2 | 销售一部 | 直接合同 | 26 | `01a115f7-1f61-77bf-9dda-d540a970f935` |
| HH-G03-20261001-R2 | 销售二部 | 引用报价 | 31 | `01a115f9-2187-7c99-b7f9-7db9abcff272` |
| HH-G04-20261001-R2 | 销售二部 | 直接合同 | 27 | `01a115fa-c6af-7908-84ee-83d7c4b3ba2d` |

四条链包含线索录入/来源绑定、独立分配、联系、客户确认、报价或直接合同、案管签前审查、主管审批、准确合同正文签署核验与归档、独立财务确认 10000、起效、转案审查、接收、分类。销售一部由万和峰审批、孙荣慧确认付款；销售二部由耿唐琪审批、焦玮琦确认付款；案管各环节由杨胜办理。真实回执均与 command slot/receipt 匹配，分类关联本次准确接收与案件，核对实际经办任职。

直接合同测试条款要求首款确认后起效，实际等待状态正确，按原条件先财务后起效；报价案例按其原条件先起效后财务。没有修改已批准条款、版本、签署或人为清除等待。

最终字节的 9 业务身份真实 PKCE 登录及 provider 退出通过；同一浏览器退出何龙、登录殷樱后，旧未保存草稿清除、新 HUMAN 来源正确、零线索 POST。G03 丢弃真实已提交录入响应后，页面保留未知状态，查询原回执恢复，没有第二次 POST。手工及实际 CSV 批量导入均跟随本人来源。撤权/失效/缺配置/主任多候选阻断反例来自真实 PostgreSQL/HTTP 的 5 项路由测试，不冒称原生浏览器负向场景。

三实例 all 通过原验收 main 执行；私密外层编排仅在真实原锁之外通过正式 CLI 顺序启动/停止各登记实例，完整健康和事实断言未替换。顺序运行用于避免本机 Docker 内存耗尽，原海华环境一直独立保留。

## 失败与验证边界

Linux 工具最终 133/133、零跳过；架构 13/13、零跳过；跨部门财务入口 PostgreSQL 定向 1/1、零跳过；既有会话 HTTP 28/28、零跳过。较早 245 项集成运行是 242 通过、3 失败，三项修复后独立 3/3 通过；原失败整轮没有改称整体通过。502 项后端单元测试有 4 项既有平台跳过；Windows 对应 Linux Python 回归也有 4 项平台跳过，不能替代零跳过的 Linux 实测。

Windows 发布 Python 90/90、Node 4/4；海华工具 Python 3/3、Node 9/9；schema 生成检查与 SQL 解析 43 SQL/119 函数通过。实际 Linux 完成锁定 npm ci、OpenAPI 检查、类型检查、SPA build、Maven package，以及精确源码提交和嵌入 metadata 核对。

未密封的备份、原构建缓存符号链接、缺失原来源目录、IdP 原登录锚、扫描服务部署名、ACL/owner 次序、配置经办失效以及未知原命令均曾触发拒绝；失败日志与原操作保留，按原操作明确核对/继续。UI 材料已上传但接收未完成时继续原上传，没有重传；管理员任职键和回调监听的验收脚本错误单独修正，没有改权限或重置账号。详细对应日志见验证矩阵；真实令牌、秘密、合同正文和备份不进入 Git。

Docker 数据盘在关闭状态复制、独立摘要核对后迁至 D，原三数据卷和账号保留；最终原环境文件摘要相等、IdP TLS 与扫描服务正常、11 原账号重新真实登录通过，Java 原 PID 20380/30172 保持。三个 Linux 测试实例已显式停止，保留全部数据及私密证据。

## 去秘密证据摘要

以下 SHA-256 对应私密 HMAC 封装证据，供持有该实例日志密钥的人复核；摘要本身不替代完整原记录。

- `ols-l06-linux-20261007-d-r_szdvtm/native-all.json`：`452d496c1bbe31e6f68f6467f22e286b9704104c224c603bd72c4ff41ba7a7eb`
- `ols-l06-linux-20261007-d-r_szdvtm/final-native-publication.json`：`a282b8d18e910407566d339b775ef9a0dc2496a772bc383f247c6bbe82ec0488`
- `ols-l09-business-e5d30cbe2361/native-business.json`：`cec9121b2bb73663be5c54d649bbf508c6ff8cd1ee43fe0486cf166115f27f53`
- `ols-l09-business-e5d30cbe2361/final-native-publication.json`：`d0d7c1e0e9b1f9bbcbfaf10c28e899ce34df00e26883c97ab9b7da26e264bc0a`
- `ols-l09-upgrade-d5fee2a6bd76/native-upgrade.json`：`dd044d0e30210a61bb3cd49f873f5ec7d56108938901ec992dd3318f5b6d7680`

本报告不表示已推送、合并 main 或部署腾讯云；这些操作不属于本轮确认范围。上线验收可按 `deploy/linux/README.md` 使用仓库配置与唯一 CLI，正式模板审核、独立线索分配授权及其余人员改密仍为业务使用前准备条件。
