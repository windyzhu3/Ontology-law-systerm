# R25-01 合同读取与本地资源条件

本次达到的是具名资源配置下的热运行 API 门槛；浏览器可操作时点和正式试点尚未通过。没有降低逐事实授权、披露审计或失败回滚要求。

所有采样均为同一 v20 制品 `5737e861d682351a5980b14a38519a18763b1b31e2e2f91db1378cd604d36457`、100 合同背景、默认 20 行、5 并发、100 次计量，另有 5 次预热。通过真实 Keycloak 登录，经本地 HTTPS SPA/API 和业务 PostgreSQL，真实 Worker 保持运行；没有并行 Maven、数据准备或恢复服务。数据库内存均为 768 MiB，原 shared_buffers 未变。

| 数据库 CPU 配额 | P50 | P95 | HTTP 错误 | Worker 前/后 | 结论 |
| --- | --- | --- | --- | --- | --- |
| 1 | 7.767 秒 | 9.879 秒 | 0 | READY / UNAVAILABLE | 未通过 |
| 4 | 1.641 秒 | 2.095 秒 | 0 | READY / READY | 未通过 |
| 6 | 1.613 秒 | 1.969 秒 | 0 | READY / READY | 本次热 API 采样通过 |

1 CPU 测试期间观察到容器 CPU 102.16%；Docker 可用 16 个 CPU。此次对照支持 CPU 配额是主要瓶颈，不能推断增加内存即可解决。4 CPU 对照结束已恢复原配额；6 CPU 仅在 API 门槛和前后 Worker 健康同时满足后保留。

当前本地评审业务数据库配额为 6 CPU / 768 MiB；这不是正式部署资源批准。容器重启保留配额，重新创建容器时应按保存的容量配置重建，不能拿旧默认 1 CPU 的结果冒充本次环境。可复核操作为 `docker update --cpus 6 ontology-law-local-login-business-db`，仅针对本工作区的已知本地合成评审容器；准确容器 ID、镜像和制品绑定保存在私有运行目录 `capacity.json`。

证据位于 `.superpowers/sdd/2026-09-28-r2.5-mvp-replan/`：`r25-01-exact-task-load.json`、`r25-01-four-cpu-load.json`、`r25-01-six-cpu-load.json` 与两份 capacity-adjustment 记录。6 CPU 记录还保存配额前后的 cgroup CPU 计数。此前 2 GiB 内存对照仍作为失败证据保留，没有删改数据或将通知标成已投递。

限制：6 CPU P95 距离门槛余量较小，只能作为本轮规定规模的通过证据；当前卡/报价卡选卡至可操作、合同页面可操作时点、持续负载、冷启动、更大数据规模和正式目标主机仍需分别验证。未据此把 R25-01 整项或 R2 发布状态改为完成。

## 历史 PENDING 的消费归属

当前唯一 outbox 消费口 `JooqR1ProjectionOutboxPort` 的 claim/ack/reap/count 都准确限定 `R1_PROJECTION`。`CommandHandler.EventType.queueOwners()` 将 R2 事件登记为 `R2_PROJECTION`，但当前没有对应 claim/ack 消费者；因此不能把 R2 的 PENDING 当成 R1 dispatcher 投递失败，也不能声称 R2 通知已投递。

现行 R2 业务接续由 `R1WorkerDeployment` 注册 INITIAL、DUE、CONTRACT_PREPARATION 与 OWNER_EXCEPTION 四类候选循环，通过 `R2OpportunityTaskScheduler` 的正式命令和持久技术检查点推进，不以 R2 outbox 消费位置为前提。工作台和管理页读取领域事实。现有真实等待恢复、合同接续及规定负载证据验证的是这些路径。

20:47:32 与 21:30:29 两次只读计数相同：R1 为 554 条 DELIVERED、累计尝试 554；R2 为 270494 条 PENDING、累计尝试 0。后者没有继续无变化追加，最新 Worker 状态为 READY。outbox 表和索引合计 90,030,080 字节。证据 `r25-01-outbox-ownership.json`。这 43 分钟区间包含隔离恢复和测试活动，属于增长观测，不是持续 API 压测。

本次完成归属与接续影响核查，保留历史事实。将来启用 R2 投影消费或保留期处理，仍须具名设计覆盖历史、重复、乱序和重放；当前不新增通知平台、不改 queue owner、不删除或强改投递状态。
