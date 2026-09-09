# Task9.3 受控身份管理后端阶段记录

日期：2026-09-09。状态：**Task9.3受控身份管理后端本地阶段验收通过**。实现`bc637ad`、最终修复`da57aff`；修复后244项受影响回归、实际CLI及独立复审通过，4项重要问题和共享邮箱证据缺口全部关闭。仅后端／真实HTTP完成，不是管理页面、浏览器会话、Task9整体或R1发布完成。

## 范围与起点

用户明确启动[已批准计划](../superpowers/plans/2026-09-08-task9-real-user-access-plan.md)的Task9.3，不扩展其范围。基于`d8ace0d59fb225ddd99df448e51b9ced44a3f2b7`，使用既有隔离分支`codex/r1-lead-contact-vertical-slice`。Task9.2身份认证、动态Actor、SELF及离线引导已独立验收，本轮消费这些接口，不重做它们。

交付目标仅ADM-01～04 HUMAN子集的6个管理查询、14个静态命令、准确Identity回执和当前权限下的原结果恢复。一个SPA／OpenAPI／api|worker Jar、13 Schema／52＋2表、原七卡／事件／Worker权限保持。无管理转授权、委托管理、密码handler、附件、通知、语音或新页面；不得新增表或改旧迁移。实际人员账号和生产授权不在本轮变更范围。

目录边界复用Task9.2既有`IdentityProviderDirectory`，而非按计划旧“Create”字面另建一个；只补齐批准的有界候选能力。在线候选与离线bootstrap仍使用独立用途保护。

## 后端验收映射

| 验收范围 | 本轮必须提供的证据 | 当前状态 |
|---|---|---|
| I02～03 | 真实只读IdP候选、准确HUMAN绑定、重复／跨realm拒绝、候选过期后的原key恢复 | 后端通过；含共享姓名／邮箱不同账号、精确0/1及碰撞安全失败 |
| I04～05 | 组织及任职创建、CAS、冻结字段、终态、组织依赖 | 后端通过；四类创建的不可变绑定与准确关联对象当前授权均覆盖 |
| I06、I09 | 静态岗位／权限allowlist、ROOT及局部scope、拒绝自授权／管理转授／SERVICE披露 | 后端通过；含真实HTTP403和跨scope拒绝，页面拒绝仍在9.5 |
| I07～08 | 当前撤权、挂起／恢复、OPEN／WAITING责任阻止结束任职，不改责任 | 后端通过；含GET／replay当前授权和入槽后生效DENY |
| I10～11 | 自锁／最后可用引导管理员保护、双连接锁序／CAS／撤权竞争、跨Tenant独立推进 | 后端通过；锁序／组织关闭竞争双合法结果，不改业务调度 |
| I12、C03 | 精确Fact／Slot／Receipt／Audit增量、savepoint拒绝、技术回滚、未知提交、原Actor回执完整性 | 后端通过；孤立Slot同key不同scope安全503、零新增，ETag续写／恢复一致 |
| D05 | 所有20管理operation拒绝代办输入且不降级为本人，保持SERVICE／mTLS边界 | 20管理HTTP拒绝通过；SERVICE／mTLS及旧代办回执相关回归通过 |
| C02相关回归 | 架构／角色／OpenAPI、旧业务和回执、实际基线／拓扑CLI | bc637ad完整125＋729；da57aff受影响244及实际CLI、独立复审通过 |

上述对应[总验收矩阵](../acceptance/2026-09-08-task9-real-user-access-acceptance.md)的后端部分，不意味着整项及最终同构建验收已通过。9.4浏览器会话、9.5管理页面／工作台状态、9.6真实使用者及七类卡链路均未晋级。

## 证据与评审

预检发现：冻结Identity合同要求provider候选按user-id稳定排序；锁定Keycloak26.7.3的[UsersResource](https://github.com/keycloak/keycloak/blob/26.7.3/services/src/main/java/org/keycloak/services/resources/admin/UsersResource.java#L261-L277)仅提供first/max分页而没有指定排序或id-after参数，[JpaUserProvider](https://github.com/keycloak/keycloak/blob/26.7.3/model/jpa/src/main/java/org/keycloak/models/jpa/JpaUserProvider.java#L900-L918)按username排序后分页。逐页本地按ID重排不能满足跨页顺序；抓取全目录、增加缓存／表或Provider扩展不在当前授权范围。

用户于2026-09-09明确确认最小方案：在线IdP候选仅按完整用户名精确查询，返回0或1个当前有效HUMAN候选，nextCursor为null；不提供姓名／邮箱／模糊搜索，不影响本地用户、组织、任职和Grant的正常有界分页。保留query／DTO形状、limit范围和既有错误结构，本查询从不签发后续cursor，任何所供cursor按无效cursor拒绝。实施代理先同步设计／合同／OpenAPI说明，再执行产品实现与行为测试；无需目录缓存、新表、Provider扩展或额外权限。

目录适配边界：Keycloak普通quoted search同时匹配姓名等字段，而精确username路径可能含SERVICE。后端先解析唯一精确账号，再用最多50条、服务端排除SERVICE的结果证明同一subject；其他账号不披露。若结果达到上限仍无法证明，安全503，不把截断当作不存在，不继续遍历全目录。碰撞与上限分支已有真实夹具测试及独立评审；仍保留可用性限制：极多同名资料可能使一次核验暂不可用。不得以扩大遍历、权限或存储来静默消除该限制。

本地证据目录：`.superpowers/sdd/2026-09-08-task9-real-user-access-plan/`，仅为本地执行记录，不随Git提交。

- 起点基线：`task93-baseline-java-01.log`；锁定JDK25下执行`./mvnw.cmd -f backend/pom.xml -B '-Dtest=ArchitectureTest,RuntimeRoleTest,RuntimeWebRoleTest,OpenApiContractTest' test`，实际退出0，52测试、0失败／错误／跳过，52.099秒。
- 澄清检查：`check-task93-contract-clarification.py`实际退出0，证明解析后的OpenAPI仅新增两处已批准搜索说明，接口／DTO／security不变；两份既有全量指纹按各自规范化方式更新，未删除或放宽门禁。
- 实际基线／拓扑：更新说明前后首轮指纹拒绝退出1保留；更新后`task93-root-baseline-clarification-green-01.log`及`task93-root-topology-clarification-green-01.log`实际退出0，baseline PASS及topology PASS，保留原7项非阻断R2就绪缺口。这只是澄清检查，最终产品源码仍须验证。
- 澄清静态回归：`task93-root-clarification-mutations-01.log`实际退出0；Identity及回执合同两组mutation共20项通过（112.349秒），只证明说明及指纹变更未放宽既有静态门。
- 实施中间检查：`task93-build-http-02.log`首次编译失败，不作为RED；修正后`task93-focused-03.log`已完成编译，其中2项真实HTTP及13项架构测试通过，但整个运行仍退出1（28项、1断言失败、10夹具错误、0跳过）。旧命令分类测试假设及夹具对冻结任职字段的更新均需修正；该运行不是阶段GREEN。五组领域测试继续验证中，生产装配及完整管理拒绝／回执证据仍待补齐。
- 后续领域／装配GREEN：`task93-domain-assembly-green-07.log`实际退出0，57项、0失败／错误／跳过，2分33秒；涵盖组织6、用户6、任职4、授权10、并发7、命令分类4及生产装配20项。原组织关闭回执、返回ETag续写和缺失生产装配均已有单独断言RED及对应GREEN；失效局部scope仍拒绝恢复。HTTP及更深查询／恢复边界、最终全量回归和独立评审尚待完成，这不是阶段验收。
- 稳定候选：实现提交`bc637ad2b31f2a70b1ed240aa837e7759125de47`（49个实施所属文件）；`task93-focused-green-16.log`实际退出0，98项、0失败／错误／跳过，3分36秒。包含本轮领域／查询／并发／真实HTTP、生产HTTPS原回执及架构／命令分类；不是最终完整回归。主控验证393个已跟踪backend文件聚合SHA256为`9d6895b8965f7f26d168eca38d0126d69441bf5f39d70d5371b9bcb8a2b950b6`，所查源码与提交一致。
- 稳定候选实际CLI：`task93-root-baseline-stable-01.log`及`task93-root-topology-stable-01.log`实际退出0，baseline及topology通过，原7项非阻断R2缺口保留。生成文件按正常生成／检查流程更新，前端仅两处已批准说明注释，不是页面实现。
- 实施报告：`task-9.3-report.md`已记录逐项映射、实际RED／GREEN及夹具／环境错误。首批2项路由RED不能证明所有验收项都曾在实现前观测失败；部分领域断言是在初始生产框架之后补充，不能冒充完整逐项预实现TDD证据。后续产品缺陷有具名单独断言RED及修正测试；历史构建和夹具错误不作为RED或GREEN。
- 独立评审：范围为`d8ace0d..bc637ad`完整两提交，不是HEAD~1；结论为需修复，0项Critical、4项Important：现有Grant自身scope的授权；创建原回执的关联对象当前授权；创建Grant对受权任职精确DENY的检查；孤立Slot情况下完整commandId防重扫描。主控已核对具名源码，均进入同一修复轮；另补真实共享邮箱夹具证据。98项通过不能替代这些缺口关闭。
- 完整基线：`task93-full-verify-17.log`实际退出0，125项单元＋729项集成测试全部0失败／错误／跳过，22分58秒；主控读取最终汇总，并在结束后独立重算393文件指纹一致。该完整结果准确绑定`bc637ad`，不能冒充后续修复提交的同一构建结果。
- 修复第1轮定向证据：`task93-fix1-red-18.log`与`task93-fix1-red-19.log`均实际退出1、各9项／8断言失败／0错误，记录真实缺陷；`task93-fix1-green-20.log`实际退出0，55项通过，2分11秒。`task93-fix1-consistency-21.log`实际退出0，另2项通过，41.881秒，覆盖合法兄弟组织scope、ETag续写／原回执一致性及入槽后当前任职DENY；这2项不冒充修复前RED。真实不同用户名共享邮箱夹具已运行。
- 修复候选实际CLI：`task93-root-baseline-fix1-01.log`及`task93-root-topology-fix1-01.log`实际退出0，baseline与topology通过，保留7项非阻断R2缺口。主控独立核对393文件源码指纹`42abad4a6128173ac31bf320536eb3de8eec59d5165f6f9dc508941b04f72dd4`；最终受影响回归与提交绑定仍待收口。
- 最终修复：`da57aff4201e7cb4370ee180770121aaebcfc2c2`，仅9个后端实施／测试文件。`task93-fix1-affected-22.log`实际退出0，244项、0失败／错误／跳过，6分28秒；覆盖全部本轮领域／HTTP／并发以及关联旧命令、业务回执、架构、角色和原生HTTPS恢复。主控读取最终汇总并在提交后独立重算393文件指纹，与上述修复候选一致；完整125＋729仍单独绑定修复前`bc637ad`，不混算为修复后全量。
- 独立复审：`task-9.3-review-02.md`，同一评审检查`bc637ad..da57aff`全部9文件及18～22测试日志，四项Important和共享邮箱证据缺口均ADDRESSED，无新增Critical／Important。初次全量及最终修复的实际退出、源码指纹与CLI均已由主控独立闭合；本阶段无剩余阻断问题。
- 非阻断保留：格式可读性与在线／离线候选密钥留存说明两个Minor转入既定9.6／整分支／部署前检查，不冒充已解决。初始逐项预实现RED历史不足仍如实保留；生成器mutualTLS／schema诊断、编译及负向Worker警告、7项R2门禁均未隐藏或放宽。
- 主控负责范围／验收与实际CLI；新实施代理负责后端及实库／HTTP测试；实现稳定后再由独立代理评审，重要问题关闭后才能阶段验收。

## 阶段结论与下一步

按已批准Task9.3范围完成用户、组织、任职和直接授权的14命令、6查询及Identity回执／恢复后端；合成账号通过真实Keycloak／PostgreSQL／HTTP验证，不等于实际人员开户或前后端整链验收。测试轮次有重叠，不累加为独立总用例数。

下一单元为Task9.4浏览器登录、会话、任职／代办选择及安全恢复；9.5再完成四管理页面与工作台状态，新增画面须视觉确认；9.6执行真实管理API建档至七卡链路和人工UAT。附件／通知仍R2、语音后置，容量环境仍待提供。本轮仅本地提交，没有GitHub推送、实际部署、真实人员账号／权限变更或容量验收；Task9整体和R1仍未完成。
