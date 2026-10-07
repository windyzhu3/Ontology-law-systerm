# LinuxBusinessResponsibilityV1

具名增量：2026-10-07 Linux v22 首次初始化与受信部署配置。无新增表、字段、权限代码或 HTTP 可写路由配置；不覆盖旧任务责任，也不代替任职授权。

| 现有阶段 | 既有办理权限 | 新责任默认任职 |
| --- | --- | --- |
| AWAIT_REVIEW | CONTRACT_REVIEW | 杨胜 |
| AWAIT_VERIFICATION / ARCHIVE | CONTRACT_SIGNATURE_VERIFY | 杨胜 |
| CHECK_RECEIPT | PAYMENT_CONFIRM | 销售一部孙荣慧、销售二部焦玮琦 |
| REVIEW_TRANSFER | TRANSFER_REVIEW | 杨胜 |
| INTAKE | TRANSFER_ACCEPT | 杨胜 |
| CLASSIFY | MATTER_CLASSIFY | 杨胜 |

可信配置键为 tenant UUID + 来源组织 UUID + 上表阶段，值为准确 appointment UUID。重复键、其他阶段和未知配置租户均拒绝。已配置租户缺少某来源组织/阶段时关闭该新责任选择，不能降级成任意候选。

先保留仍有效获权的既有责任人，再在新责任处选择配置任职。配置目标必须通过原有效任职、授权范围、对象拒绝、组织及已有独立性校验；目标停任、撤权或跨租户时进入原异常/等待链。主任的全所权限不使其自动成为 TaskOwner。转案按 from 组织查目标，但在 to 组织核验权限和隶属，审查/接收继续排除提交人的同一 principal。

合同准备、签署材料采集、收款补件、转案 PREPARE/SUPPLEMENT 等销售阶段保持原销售责任。归档复用现有签署核验权限，不增加权限。无配置实例保留原选择规则：合同审查须唯一合法候选；签署核验按原 UUID 文本排序选择首个合法任职（既有回归已冻结该行为）。签署核验至归档的有效当前经办保留。已配置租户始终只核验准确配置目标，不使用该旧签署兜底。

报价/合同审批仍使用现有带版本的策略和成员表。一部万和峰、二部耿唐琪；受控初始化只登记策略事实和原 UUID，不插入批准、签署、到账、接收或分类结论。冻结审批成员不从新路由改写。三类模板继续是待审核候选，默认丁启明可替换，默认人名不构成签署授权。
