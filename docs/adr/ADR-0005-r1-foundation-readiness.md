# ADR-0005：R1 foundation contract readiness

Status: Accepted

Date: 2026-09-05

## Context and decision

R1在业务客户端部署前发现两项跨层缺口。第一，routing review等待责任不能借用contact恢复命令。因此增加静态具名的`reopenDueRoutingReviewTasks`，只接受`RESOLVE_LEAD_ROUTING_GAP`及最新`R1_ROUTING_REVIEW_WAIT_V1` WaitReceipt；既有contact命令仍只接受`CONTACT_LEAD`及`CONTACT_RETRY_V1`。两者一次只恢复一张准确Task，使用mTLS、相同selector/CAS/replay/Receipt/TaskETag和错误集合，且`R1_REOPEN_SCOPE_V1`包含`commandType`防止跨命令scope复用。浏览器不得调用内部operation。

第二，wire `Revision`限定为JSON安全整数`0..9007199254740991`。OpenAPI中央schema、请求、响应、数字revision ETag语义、scope和Draft canonicalization都采用该界限；非安全整数在API边界拒绝，不得舍入或转换。旧导入超界值不能向客户端发出。需要递增而当前revision已达上限时，在任何持久化前返回安全的`INTERNAL_ERROR`，不创建durable slot或Receipt。

## Physical contract and compatibility

52＋2表、V001至V850字节及数据库`bigint`均不改变；恢复类型映射是版本化代码中的静态allowlist，不新增scheduler、通用command或动态registry。R1尚无已部署业务客户端，因此新增operation和收紧未部署wire范围没有兼容迁移负担。既有12项operation行为保持不变；当前合同冻结13项operation和14个逐项挂载example。

## Consequences

Task C/D必须在HTTP、canonicalization和revision递增处实现边界与原子overflow guard。OpenAPI生成物、Java合同测试、前端真实fetch round-trip测试、baseline verifier和文档registry必须保持一致。本文不声称业务service、SPA流程或E2E已经实现。
