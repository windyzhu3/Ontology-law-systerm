# M02 首批实现记录

状态：S 高保真已由用户于2026-09-28确认；M02实施中，尚未整体验收，未替换M01验收环境。

## 本批代码

- TeamResponsibilityReader：责任域持有的租户内UUID有界扫描，TASKS/WAITING/HISTORY，最大100；复用CurrentTaskReader准确任务映射，不循环冒充负责人读取工作卡。
- R2TeamManagementReadService：团队元数据查询内部服务（尚未暴露HTTP）；独立TEAM_TASK_READ、逐事实授权、标签按授权读取、600秒且绑定身份/查询的游标、同步披露审计与最终重验。现阶段仅tasks/waiting/history列表，未实现异常聚合和详情，不应提前接入页面。
- AuditAppender：TEAM_TASK_READ仅允许既有四个具名责任槽、本人直接HUMAN、准确subject及allowed证据；M01两项管理读取仍只允许原OPPORTUNITY_OWNER，不扩大其范围。未给任何账号授予新权限。
- TeamLedger/TeamManagementPage/teamTransport：S冻结布局、只读传输、按需详情、进入原卡/异常处置前重读、过期游标回首页、401失效/403局部拒绝、跨身份清空搜索和数据。组件尚未挂到运行路由。
- BusinessNavigation仅增加可选onTeam与team激活态；旧调用者不出现新入口。沿用四份共用样式，不改CSS。

## 实测与发现

- `output/m02-reader-red.log`：缺失读取端口导致编译失败（接口RED，不冒充业务断言RED）。
- `output/m02-reader-green.log` / `green2`：历史未命中，进一步查原状态约束和命令确认实际完成态为DONE、正常态为OPEN，不存在COMPLETED或CLAIMED。已改代码及计划，未改原业务状态机。最初“仅夹具缺少完成记录”的判断不完整，以此记录为准。
- `output/m02-reader-green3.log`：真实PostgreSQL读取测试1通过，租户隔离、边界、分页、原期限不变及真实收款确认后历史记录可见。
- `output/m02-service-red.log`：缺失服务编译RED。
- `output/m02-service-green.log`：原M01审计白名单拒绝新TEAM授权；按明确代码/槽分支扩展，保持旧授权限制。
- `output/m02-service-green2.log`：测试审计数量使用了受限底表，被数据库拒绝；改测试为既有classified视图，未放宽数据库授权。
- `output/m02-service-green3.log`：团队独立读取/撤权/审计故障、审计负向约束、原等待真实接续共3项通过。
- `output/m02-ui-final2.log`：6文件28项通过（团队组件/传输/页面、共用导航、原M01组件/页面）。新增跨身份查询词泄漏测试先失败于`m02-identity-red.log`，按身份及epoch隔离组件后通过。
- 前端类型检查/共享后端回归/开发基线最终结果另补；不得把旧结果视为全部现代码的整体验收。

## 必须继续完成

1. 所有36种任务逐域解析及权限范围测试，而非只证明一般任务元数据可读；当前已验证财务和跟进等待代表样本。
2. 真实异常候选聚合：含没有taskId的原工作流OWNER_EXCEPTION、到期缺后继判定、原T01处置复用和运营最小摘要。不得从WAIT_DUE直接推断衔接失败。
3. 详情及准确业务历史（当时确认人、准确版本、退回/交接原因），独立源授权和失败关闭。当前负责人不能替代历史确认人。
4. OpenAPI/身份授权配置入口/会话具名资格/路由及原卡完整接线；新TEAM权限尚不可在UI授权，测试夹具通过已有低层fixture授权仅用于集成验证。
5. 真实角色/三宽浏览器/性能及整链验收，确认后再部署。M02、原修复总验收和R2发布准入均未标记完成。

## 最终核对

- `output/m02-shared-regression.log`：原M01独立财务读取/审计阻断1项及架构13项通过，未放宽既有管理读权限。
- `output/m02-ui-final3.log`：6文件29项通过；`output/m02-tsc-final2.log`：TypeScript检查通过。
- `output/m02-baseline.log`：R2开发准入/基线通过，原7项非致命限制保留，R1 PAUSED / R2 NOT_GRANTED。
- 独立只读复核发现P2：办理重读期间切换视图后，旧请求可能迟到导航。`output/m02-unmount-red.log`复现失败；新增组件存活检查和卸载取消请求，29项回归通过。复核未发现其他具体基础读取安全问题，但不代表尚未实施的接口/详情已通过。
- S确认已写入approval.json；没有部署本批未接完的M02，运行环境仍为已验收M01。本批不要求用户再次确认S。
