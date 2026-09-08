# Task9.3 受控身份管理后端阶段记录

日期：2026-09-09。状态：用户已确认账号候选精确用户名查询，Task9.3恢复实施，尚未阶段验收；不得作为Task9整体或R1发布完成证据。

## 范围与起点

用户明确启动[已批准计划](../superpowers/plans/2026-09-08-task9-real-user-access-plan.md)的Task9.3，不扩展其范围。基于`d8ace0d59fb225ddd99df448e51b9ced44a3f2b7`，使用既有隔离分支`codex/r1-lead-contact-vertical-slice`。Task9.2身份认证、动态Actor、SELF及离线引导已独立验收，本轮消费这些接口，不重做它们。

交付目标仅ADM-01～04 HUMAN子集的6个管理查询、14个静态命令、准确Identity回执和当前权限下的原结果恢复。一个SPA／OpenAPI／api|worker Jar、13 Schema／52＋2表、原七卡／事件／Worker权限保持。无管理转授权、委托管理、密码handler、附件、通知、语音或新页面；不得新增表或改旧迁移。实际人员账号和生产授权不在本轮变更范围。

目录边界复用Task9.2既有`IdentityProviderDirectory`，而非按计划旧“Create”字面另建一个；只补齐批准的有界候选能力。在线候选与离线bootstrap仍使用独立用途保护。

## 后端验收映射

| 验收范围 | 本轮必须提供的证据 | 当前状态 |
|---|---|---|
| I02～03 | 真实只读IdP候选、准确HUMAN绑定、重复／跨realm拒绝、候选过期后的原key恢复 | 待实施／验证 |
| I04～05 | 组织及任职创建、CAS、冻结字段、终态、组织依赖 | 待实施／验证 |
| I06、I09 | 静态岗位／权限allowlist、ROOT及局部scope、拒绝自授权／管理转授／SERVICE披露 | 待实施／验证；页面拒绝在9.5 |
| I07～08 | 当前撤权、挂起／恢复、OPEN／WAITING责任阻止结束任职，不改责任 | 待实施／验证 |
| I10～11 | 自锁／最后可用引导管理员保护、双连接锁序／CAS／撤权竞争、跨Tenant独立推进 | 待实施／验证 |
| I12、C03 | 精确Fact／Slot／Receipt／Audit增量、savepoint拒绝、技术回滚、未知提交、原Actor回执完整性 | 待实施／验证 |
| D05 | 所有20管理operation拒绝代办输入且不降级为本人，保持SERVICE／mTLS边界 | 待实施／验证 |
| C02相关回归 | 架构／角色／OpenAPI、旧业务和回执、实际基线／拓扑CLI | 仅起点52项通过，最终源码仍需验证 |

上述对应[总验收矩阵](../acceptance/2026-09-08-task9-real-user-access-acceptance.md)的后端部分，不意味着整项及最终同构建验收已通过。9.4浏览器会话、9.5管理页面／工作台状态、9.6真实使用者及七类卡链路均未晋级。

## 证据与评审

预检发现：冻结Identity合同要求provider候选按user-id稳定排序；锁定Keycloak26.7.3的[UsersResource](https://github.com/keycloak/keycloak/blob/26.7.3/services/src/main/java/org/keycloak/services/resources/admin/UsersResource.java#L261-L277)仅提供first/max分页而没有指定排序或id-after参数，[JpaUserProvider](https://github.com/keycloak/keycloak/blob/26.7.3/model/jpa/src/main/java/org/keycloak/models/jpa/JpaUserProvider.java#L900-L918)按username排序后分页。逐页本地按ID重排不能满足跨页顺序；抓取全目录、增加缓存／表或Provider扩展不在当前授权范围。

用户于2026-09-09明确确认最小方案：在线IdP候选仅按完整用户名精确查询，返回0或1个当前有效HUMAN候选，nextCursor为null；不提供姓名／邮箱／模糊搜索，不影响本地用户、组织、任职和Grant的正常有界分页。保留query／DTO形状、limit范围和既有错误结构，本查询从不签发后续cursor，任何所供cursor按无效cursor拒绝。实施代理先同步设计／合同／OpenAPI说明，再执行产品实现与行为测试；无需目录缓存、新表、Provider扩展或额外权限。

本地证据目录：`.superpowers/sdd/2026-09-08-task9-real-user-access-plan/`，仅为本地执行记录，不随Git提交。

- 起点基线：`task93-baseline-java-01.log`；锁定JDK25下执行`./mvnw.cmd -f backend/pom.xml -B '-Dtest=ArchitectureTest,RuntimeRoleTest,RuntimeWebRoleTest,OpenApiContractTest' test`，实际退出0，52测试、0失败／错误／跳过，52.099秒。
- 澄清检查：`check-task93-contract-clarification.py`实际退出0，证明解析后的OpenAPI仅新增两处已批准搜索说明，接口／DTO／security不变；两份既有全量指纹按各自规范化方式更新，未删除或放宽门禁。
- 实际基线／拓扑：更新说明前后首轮指纹拒绝退出1保留；更新后`task93-root-baseline-clarification-green-01.log`及`task93-root-topology-clarification-green-01.log`实际退出0，baseline PASS及topology PASS，保留原7项非阻断R2就绪缺口。这只是澄清检查，最终产品源码仍须验证。
- 实施报告：`task-9.3-report.md`，尚待完成；首批真实Keycloak／PostgreSQL／HTTP RED为2项断言失败、0错误／跳过、实际退出1（管理入口缺失），日志`task93-red-http-01.log`。最终源码提交、GREEN、完整回归和独立评审未取得。
- 主控负责范围／验收与实际CLI；新实施代理负责后端及实库／HTTP测试；实现稳定后再由独立代理评审，重要问题关闭后才能阶段验收。

当前没有Task9.3完成声明、GitHub推送、实际部署、真实人员账号／权限变更或容量验收。
