# Task9.6 本地整链验收进度

日期：2026-09-09。状态：**进行中，Task9未完成**。范围依据：[Task9扩展计划](../superpowers/plans/2026-09-08-task9-real-user-access-plan.md)与[验收矩阵](../acceptance/2026-09-08-task9-real-user-access-acceptance.md)。

## 当前处于什么阶段

Task9.5的管理页面、十四项管理写入、工作台状态提示已完成源码及受控界面验证。本轮不是新增MVP业务，也不是重新设计页面，而是把已有前后端装配到同一本地真实运行环境，验证真实登录、受控建档、责任卡和Worker恢复的完整链条。

| 环节 | 当前状态 | 不能据此宣称的结果 |
| --- | --- | --- |
| 已有前端源码 | 本轮基线445项／27文件通过，退出0 | 不代表运行中SPA已更新 |
| 原本地登录环境 | Keycloak TLS与准确issuer通过；原闭包核验通过 | 不代表新管理界面、业务资格和七卡已实测 |
| 原bootstrap保护 | 当前生产类核验`VERIFIED_ORIGINAL`；54表及原文件摘要不变 | 旧运行Jar仍含旧核验逻辑，不冒充新构建 |
| 9.6b制品切换 | 首版`281a37d`已提交，独立评审中；子代理报告24项Python／4项Node通过 | 未执行本轮API／SPA激活 |
| 9.6c Worker | 已核实原SERVICE一身份／一任职／零授权；原证书有效，DB角色最小 | Worker尚未启动，不代表等待恢复已通过 |
| 真实账号与受控管理链 | 已批准本轮本地临时Keycloak管理操作；尚未执行 | 目录只读账号不升级；不会直写HUMAN业务身份 |
| 七卡、撤权与故障恢复 | 待同构建真实验收 | 既有分层测试不替代全部整链用例 |
| U01～U03人工验收 | 未执行；[模板](../acceptance/task9-real-user-uat-template.md)已建立 | 自动化和合成账号不算使用者签认 |

## 本轮已核实证据

- 起点`c3083de`，原隔离工作树与分支不变；计划检查点`01213b1`。没有推送GitHub。
- 前端基线：2026-09-09 21:11:51开始，445项／27文件，11.93秒，退出0；Node24.20.0／npm11.9.0。
- 后端单元基线：`mvnw.cmd -B -f backend/pom.xml test`，125项，失败／错误／跳过均0，退出0，21:38:44完成，用时1分30秒，JDK25.0.4.1／Maven3.9.16。未执行package，不替换运行Jar；这不是实库IT／最终E2E总验收。原OpenAPI生成器的3.1／注解默认值／组合Schema警告保留，不声称输出无警告。
- 本地helper基线：Python4项与Node2项全部通过，退出0；后续新增测试需另记，不覆盖历史证据。
- Keycloak discovery返回200，issuer为`https://localhost:19443/realms/local-r1`，TLS正常校验。
- 本轮切换前真实SPA入口诊断：`/login`为200，四条`/admin/identity/`管理导航均为404，TLS校验开启。这是部署接线缺口的现场证据，不是管理页面源代码缺失；修复后须对同路径复验。
- 当前生产类执行原引导核验返回`VERIFIED_ORIGINAL`与空delta；54张表及原manifest、operator、密钥、配置、Jar内容摘要不变。此核验不载入测试类，也不替换旧运行Jar。
- Worker数据库登录为NOINHERIT，仅属于`law_app_worker`，无SUPERUSER／CREATEDB／CREATEROLE／REPLICATION／BYPASSRLS标志；现有SERVICE证书检查时有效，到期为2026-09-16 06:29:24 UTC。
- `scripts/verify_topology.py`退出0；`scripts/baseline/verify_baseline.py .`退出0，基线一致性PASS，仍保留原7项非致命R2发布门阻断。没有为使检查变绿而提前修改R1／R2交付状态。

## 授权与保持不变的边界

仅原本地测试环境、专用合成账号和最小资格；不生产、不真实业务资料、不Git推送。临时Keycloak管理权限只用于本轮测试开户，完成立即移除并验证，应用目录client继续只读。原bootstrap、密钥、数据库卷和事实保留。

本轮不新增表、产品角色、业务接口、动态策略或调度平台。附件上传与通知留在R2，语音后置；原冻结前端风格与四页布局保持。容量参考环境和Task10总验收不因本地功能验证而降级或提前通过。

真实时间边界：生产`RetryPolicy`按下一工作日10:00／15:00安排前两次首联自动重试；`R1BusinessTime.nextWindow`安排路由等待恢复。真实部署验收不修改系统时钟、冻结策略或数据库到期字段。任务建立后记录实际due时间，到期再收Worker恢复证据；原使用业务时钟夹具的分层测试不替代本项。

## 下一证据检查点

1. 9.6b独立spec／quality评审，真实新旧制品／gate切换、TLS／管理页直达、原闭包与回退证据。
2. 9.6c原SERVICE准确三项固定权限、同Jar独立Worker、三loop就绪及停启恢复证据。
3. Keycloak账号→真实管理API／页面→动态登录资格→七类责任卡与故障／代办恢复，用最终同构建逐项填写矩阵。
4. 指定使用者执行并确认U01～U03，再进行Task9整范围独立评审和完成判定。
