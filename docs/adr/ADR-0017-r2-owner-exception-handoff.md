# ADR-0017：R2 T01负责人异常与责任交接schema后继

状态：2026-09-15，R2开发实施决定；F已确认。不授予部署、生产自动派发或R2验收资格。

## 问题

失效Owner不能被有效任职过滤器静默排除。主管协调和交接需要可授权、保留准确历史版本的业务事实；Worker检查点只是重启恢复状态，不能承担异常台账。旧Task取消/等待守卫也不表达交接后继与原等待继承。

## 决定

追加V900 / `52-plus-2-r2-v4`，新增Opportunity-owned `owner_exception`、`owner_exception_disposition`、`responsibility_handoff`。55张业务表加3张技术表共58张物理表，25个迁移；历史V001–V890字节和旧52＋2基线不变。后继登记详见 `database/schema-contract-52-plus-2/R2-SCHEMA-SUCCESSOR.md`。

异常以tenant/id/revision版本行保留历史。只有is_current可true→false；新业务版本插入revision+1，终态不能回开。数据库保证一个当前版本和一个活动责任槽周期。处置和交接是revision0不可变事实，双向引用使用tenant-first延迟外键，在事务末核验决定、准确异常basis、旧任务取消、新任务receiver/basis/SLA及原等待。责任前任必须已存在且只容一个后继，禁止分叉/循环。

Task仅新增具名basis、交接前序和取消准确Fact。OPEN/WAITING旧卡以`R2_OPPORTUNITY_HANDOFF_V1`取消，不产生DONE/进展；新卡保留期限。`R2_OPPORTUNITY_HANDOFF_WAIT_V1`允许创建即WAITING的revision0与继承已到期resumeDue，其他WaitReceipt仍要求正任务修订和resumeDue晚于进入时刻。等待事实用准确摘要，交接事实用revision0。

COMMAND仅获新事实SELECT/INSERT及异常is_current列UPDATE；QUERY只获列SELECT；WORKER/AUDIT/PUBLIC不获业务写入。任职有效性、当前授权、准确来源与摘要仍由Owner和CommandRuntime复验，不以数据库结构取代授权。没有自动grant、普通流程引擎或外部消息发送。

## 发展门禁及验证边界

`R2_OWNER_EXCEPTION_SCHEMA_V1`精确pin完整manifest、字段文档、25个迁移及全部字节。仅在`--r2-development`显式模式中接受该后继；只移除具名V900增量，重建并核验V890原manifest hash，再经原V890/V880/V870投影回冻结R1。未知版本、改名、旧迁移漂移、缺失/额外文件仍失败关闭；默认与strict门禁不放宽。

Schema生成和Python合同测试属于开发证据。2026-09-15用户重启Docker后，真实PostgreSQL 18.6已完成25个迁移，jOOQ由真实V900结构重新生成27个POJO；业务/HTTP/Worker与浏览器验收仍按各自证据记录，生产开关与R2验收继续独立记录。

## 同一未发布后继中的准确验证依据

`OWNER_VALIDATED`使用真实`audit.audit_entry`的id及32字节摘要，新增`resolution_hash`，不得使用商机revision代替验证记录。终态约束逐类限定：TRANSFER为handoff revision0且无hash；OWNER_VALIDATED为audit hash且无revision；OPPORTUNITY_CLOSED为商机准确revision且无hash；活动状态不得带解决依据。命令在身份/业务锁下QUERY复验，AUDIT写入具名验证记录，再执行COMMAND；健康NO_CHANGE保留该审计证据，最终QUERY重读准确记录后返回。QUERY仅使用已有classified view，不新增原始审计表访问权限。

关闭商机只允许已封存根对象的下一revision继续解析原交接链，链内所有opportunity_revision保持一致；不把任意商机修订当作交接迁移，也不改写旧handoff。运行数据库期望明确登记v4，仍要求真实ACTIVE部署状态及准确release/manifest摘要，登记版本不等于启用。

观察检查点升级为R2C2，保留R2C1 INITIAL/DUE读取，旧格式不得解释为OWNER_EXCEPTION。`ols.worker.owner-exception-observation-enabled`默认false；显式启用时只注册异常观察并强制数据库持久检查点，健康、启动和停止纳入Worker生命周期。生产启用继续受独立门禁约束。
