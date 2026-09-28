# T02 正常商机周期总装：实施与验收

日期：2026-09-15

状态：IMPLEMENTED / DEVELOPMENT-ACCEPTANCE-PASSED。T02整体开发验收完成。R1 PAUSED / R2 NOT_GRANTED；生产开关默认关闭。

## 已实现

T02将已有INITIAL首次任务发现与DUE到期恢复接入实际Worker。R1有效联系形成商机后，合格销售能自动获得唯一初始待办；真实进展形成等待后，按约定恢复同一合法任务。历史商机同样扫描，不依赖原R1投影消费位置。

生产变更限定为R1WorkerDeployment和WorkerRuntimeHealth：新增默认false的`ols.worker.opportunity-task-scheduling-enabled`，显式选择INITIAL/DUE，强制数据库持久检查点，支持准确v3/v4检查点Schema并保留实际运行门禁。正常周期与T01异常观察的开关、状态及检查点独立。健康逐项包含启用状态、首次任务扫描和到期恢复，纳入启停生命周期。

没有新增页面、业务API、权限或Schema。E/F冻结母版与共用卡片没有修改；没有报价、合同、案管或AI扩展。本轮不需要新增高保真，也未把静态原型当作业务实现。

## 当前验证

- 先运行新增测试，确认缺少配置/健康接口：`output/t02-unit-red.log`，预期编译失败；没有把该次计作通过。
- 配置、调度、检查点、健康及架构专项46项通过：`output/t02-unit-green.log`。
- 后端全量单元204项通过；相关12个集成类94项全部通过，零失败/错误/跳过，Maven verify退出0（10:19:59，耗时7分06秒）：`output/t02-integration-verification.log`。
- `OpportunityWorkerAssemblyIT`真实闭环1项已通过：实际打包API、TLS、PostgreSQL与Keycloak；正常Worker自动建卡及恢复，无手动调用scheduler或SQL造卡。进展经正式草稿/确认CommandRuntime产生，不声称该提交经过生产HTTP。
- 断言唯一初始卡、真实完成事实、后继WAITING到同一张OPEN卡，保留SLA/等待回执、完整Opportunity/Assignment；进展只有一条。关闭后用同一检查点启动新Worker，健康恢复且不重复卡；撤销RECOVER权限后DUE及整体不健康。INITIAL/DUE检查点各一个，OWNER_EXCEPTION始终不存在。
- 发展门禁PASS，原7项非致命发布阻塞保留：`output/t02-development-gate.log`。
- 独立规格及代码审查无待处理项。既有close异步停止语义通过实际重启重新获得检查点锁核验；未另造关闭框架。

前端未修改，沿用T01已记录的623项测试及E/F视觉证据，本轮不把旧测试冒充新跑。相关集成回归覆盖R1生产组合、原Worker健康、R2发现/内部HTTP/正式激活恢复、Worker客户端、数据库检查点、运行配置、T01观察组合和运行角色隔离。

## 复现与交付边界

使用仓库Maven wrapper及Java25，串行执行：

```text
./mvnw.cmd -f backend/pom.xml -Pit verify -Dit.test=OpportunityWorkerAssemblyIT,OwnerExceptionWorkerAssemblyIT,R1ProductionAssemblyIT,RuntimeRoleIT,R1WorkerLoopHealthIT,R2OpportunityDiscoveryIT,R2OpportunityInternalHttpIT,R2OpportunityActivationCommandIT,R2OpportunityRecoveryCommandIT,R2WorkerClientHttpIT,R2OpportunityCheckpointIT,RuntimeDatabaseIT
```

Maven堆384MB，测试JVM堆512MB，ActiveProcessorCount=2，SerialGC；子API堆384MB。测试中的SERVICE授权及运行开关仅位于隔离夹具，未向真实身份授权。

[实施计划](../../superpowers/plans/2026-09-15-r2-t02-opportunity-worker.md)；[组合合同](../../contracts/r2-opportunity-worker-assembly-v1.md)。未提交、推送或部署。生产启用仍走既有发布门禁；T02完成不等于整个销售MVP或R2发布验收完成。
