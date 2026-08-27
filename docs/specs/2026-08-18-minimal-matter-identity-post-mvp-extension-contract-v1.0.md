# 最小Matter身份与后MVP扩展契约 v1.0

版本：1.0  
日期：2026-08-18  
状态：FROZEN  
方案：B——最小Matter身份 + 不可变TransferSnapshot采纳引用

## 1. 契约目的

本契约固定销售MVP终点与后续案件管理模块的接缝，保证：

1. 销售闭环可以独立交付并产生正式Matter身份。
2. 不在MVP提前建设案管登记、分类、分案和案件办理。
3. 后续接入综法、非诉、诉讼、执行等能力包时，不需要回改销售至转案六聚合。
4. Matter身份、移交语境和后续办理事实各有唯一Owner，不形成第二套客户、合同或案件快照。

本契约是领域与运行边界，不是数据库表、API传输格式或实施计划。

本契约与《待办驱动律所管理系统：总体架构与本体完整设计》§17–18保持同一语义；两者必须同步演进。任何不一致都属于规格缺陷，不能选择较宽松文本继续实施。

## 2. 已冻结的方案B

正式Matter只保存：

- Tenant内唯一身份。
- 不可变MatterRef。
- 创建来源。
- 被采纳TransferSnapshot的准确版本和摘要引用。
- 接收决定及TransferAccepted的因果引用。

Matter不复制：

- 客户或参与方快照。
- 法律需求快照。
- 合同正文、收费、付款或成交事实。
- 材料清单及冲突详情。
- 销售Owner、案管Owner、承办团队或案件状态。

方案A“只有matterId/matterRef、不保留准确移交来源”被否决，因为无法证明Matter采纳了哪一版移交事实。方案C“把客户、合同、需求和材料复制进Matter”被否决，因为会产生双写、版本漂移和提前扩张。

## 3. MVP边界

### 3.1 MVP终点

```text
DecisionRecorded(TRANSFER_REVIEW, ACCEPT)
+ TransferAccepted
+ MatterCreated
+ TransferRequest.MatterLink(write-once)
+ 案管接收Task DONE
+ 销售结果回执
```

以上结果必须在同一本地事务中原子提交。

### 3.2 MVP明确不做

- Matter登记资料补充。
- 案件类型、法域和管辖确认。
- 案管分配、主办律师接受和团队建立。
- Matter状态机。
- 综法、非诉、诉讼、执行等能力包。
- 法律期限、程序节点、工作产品和结案。

`MatterCreated`之后，销售MVP不得生成任何登记、分配或办理Task。

## 4. 最小Matter本体

```text
Matter
├── tenantId
├── matterId
├── matterRef
├── origin
│   ├── originKind = ACCEPTED_TRANSFER
│   ├── sourceTransferRequestId
│   ├── sourceSnapshotVersion
│   ├── sourceSnapshotDigest
│   ├── acceptanceDecisionId
│   ├── transferAcceptedEventId
│   └── acceptedAt
├── createdAt
└── aggregateVersion = 1
```

### 4.1 身份规则

- `matterId`是内部不透明主键。
- `matterRef`只由Matter Identity Core签发，Tenant内唯一、不可修改、不可复用。
- `origin`创建后不可改写；来源事实补正形成后续显式事实，不覆盖原始采纳语境。
- Matter在MVP没有`status、matterType、jurisdiction、owner、team、handlingStatus`。
- 禁止以`metadata/extensions JSON`、EAV或预留空字段承载未来业务。

### 4.2 来源引用

`sourceTransferRequestId + sourceSnapshotVersion + sourceSnapshotDigest`共同指向TransferRequest拥有的不可变快照。三者缺一不可。

被Matter引用的TransferSnapshot必须持续可读取和校验，Transfer模块不得物理删除或原位覆盖。未来模块只能通过稳定的`AcceptedTransferSnapshotReadPort`按Tenant和准确摘要读取，不能跨模块直查私有表。

```text
getAcceptedSnapshot(
  tenantId,
  transferRequestId,
  snapshotVersion,
  snapshotDigest,
  purpose,
  actorContext
) -> immutable snapshot view
```

读取仍须经过当前用途、Matter关系和字段级权限校验；Matter引用不等于任意用户获得源数据访问权。

## 5. MatterOpeningPort

```text
openFromAcceptedTransfer(
  tenantId,
  transferRequestId,
  snapshotVersion,
  snapshotDigest,
  acceptanceDecisionId,
  transferAcceptedEventId,
  acceptedAt,
  correlationId,
  causationId,
  idempotencyKey
) -> { matterId, matterRef, aggregateVersion }
```

规则：

- 调用方不能指定matterId或matterRef。
- `tenantId + sourceTransferRequestId`是Matter创建业务幂等键。
- 相同输入重放返回同一Matter。
- 相同来源携带不同snapshotVersion、digest或acceptanceDecisionId必须拒绝并进入SYSTEM_RECOVERY。
- MVP端口是模块化单体内、参与同一UnitOfWork的本地事务端口，不是网络调用。

## 6. 接收事务与因果顺序

```text
校验当前案管Task、唯一completionContract和TRANSFER_REVIEW Authority
→ 校验TransferRequest、snapshotVersion/digest及materialManifestVersion/hash
→ 校验DealActivated与ContractExecuted绑定同一当前ContractRevision/contentDigest
→ 校验MaterialManifest AcceptReady
→ 校验当前conflictReviewId/scopeHash的PRE_TRANSFER已解决
→ 校验不存在EngagementTerminated或其他接收阻断
→ DecisionRecorded(ACCEPT，绑定taskId和全部准确Subject)
→ TransferAccepted并取得transferAcceptedEventId
→ 调用MatterOpeningPort
→ MatterCreated，origin引用transferAcceptedEventId
→ TransferRequest写入MatterLink
→ 案管Task DONE、销售回执、Audit和CommandReceipt
→ 原子提交
```

中间写入在提交前对其他事务不可见；任一步失败全部回滚。系统不得出现：

- 已接收但没有Matter。
- 已创建Matter但转案未接收。
- MatterLink指向错误或更新后的Matter。
- 旧Task、旧快照、旧材料Manifest或旧冲突范围推进当前接收。

案管Task的唯一完成事实是与当前Task和completionContract匹配的`DecisionRecorded(TRANSFER_REVIEW)`；`TransferAccepted`、`MatterCreated`和`MatterLink`是同事务派生结果，不能反向代替Task完成事实。

## 7. MatterLink

TransferRequest拥有write-once MatterLink：

```text
MatterLink {
  tenantId,
  transferRequestId,
  matterId,
  matterRef,
  linkedAt,
  matterCreatedEventId
}
```

- 一个TransferRequest最多一个MatterLink。
- 一个Matter只能有一个`originKind=ACCEPTED_TRANSFER`来源。
- MatterLink不能改指、删除或复用。
- 重复接收命令返回原CommandReceipt和MatterLink，不创建第二个Matter。

## 8. 事件与扩展消费

`MatterCreated`经同事务Outbox在提交后发布。未来模块只消费已提交事件；消费失败不得撤销已合法完成的转案接收。

后MVP责任链固定为：

```text
MatterCreated
→ MatterRegistered
→ MatterClassified
→ MatterCapabilitiesSelected
→ MatterTeamAssigned
→ MatterAssignmentAccepted
→ MatterHandlingActivated
→ 能力包创建第一张办理Task
```

每一步是不同模块拥有的权威事实，不得合并成一个全局`matterStatus`。

## 9. 后MVP模块边界

| 模块 | 权威事实 |
|---|---|
| Matter Identity Core | Matter身份、MatterRef、不可变origin、MatterCreated |
| Matter Intake & Registration | 名称、参与方角色、法域、管辖、保密等级、MatterRegistered |
| Classification | 业务分类、选择依据、MatterClassified |
| Capability Binding | 主/辅能力包、包版本、兼容性、MatterCapabilitiesSelected |
| Allocation & Team | 团队成员、责任范围、分配、接受和teamVersion |
| Handling Kernel | Matter范围责任、法律事件、工作包和稳定端口 |
| Legal Deadline | 法律事实、规则版本、日历、期限和调整决定 |
| Work Product | 成果类型、版本、作者、审阅、提交和Evidence |
| Capability Packages | 综法、非诉、诉讼、执行等具体命令、事实、Task和规则 |

依赖方向：

```text
Matter Identity Core <── Registration / Classification / Allocation
          ▲
          └──── Handling Kernel <── Capability Packages
                     ▲
                     ├── Legal Deadline
                     └── Work Product
```

- Identity Core不导入或枚举具体能力包。
- 能力包通过Core和Handling Kernel稳定端口协作，不能改Core私有表。
- 包内责任继续使用静态Task注册，不建设通用流程引擎。
- 包之间只通过显式Event或端口协作。

## 10. CapabilitySet扩展契约

后续模块采用：

```text
MatterCapabilitySet {
  matterId,
  capabilitySetRevision,
  primaryPackageKey,
  supportingPackageKeys[],
  packageVersions,
  selectionBasis,
  policyVersion
}
```

- 初期只选择一个主能力包。
- 辅助包必须显式声明兼容性。
- 包版本在已激活Matter上冻结。
- 升级必须显式迁移，不能静默改变在途Task或已计算期限。
- `SalesOfferingClass`只能作为分类候选，不能直接成为Matter类型。

## 11. Matter团队与责任

```text
全局Capability/Authority
+ MatterTeamMembership
= 能否在当前Matter执行某类命令
```

MatterTeamMembership至少包含：

```text
tenantId
matterId
memberId
teamRoleKey
responsibilityScope
effectiveFrom/effectiveTo
assignmentDecisionRef
teamVersion
```

团队角色不等于具体Task Owner。每个后续Task创建时仍须解析并冻结一个具体内部人员；Owner、Capability和Authority分别校验。

## 12. 启用与迁移

- MVP不预建登记、分类、团队、能力包或办理Task。
- 新模块启用后，新Matter由该模块消费MatterCreated并创建第一张登记责任。
- 已有最小Matter通过一次性、幂等产品迁移创建登记责任。
- 历史Matter不能被默认标记为已登记、已分类、已分配或已开始办理。
- 迁移不能修改Matter origin、MatterRef或原TransferSnapshot引用。

## 13. 验收级不变量

1. MatterRef只能由Matter Identity Core签发且永不复用。
2. Matter必须准确引用被采纳TransferSnapshot的版本和摘要。
3. Matter不复制销售域客户、合同、需求、材料和冲突快照。
4. Matter创建、TransferAccepted、MatterLink、案管Task和销售回执原子一致。
5. 旧Task、旧Decision、旧Snapshot或旧scopeHash不能完成当前接收。
6. Matter在MVP没有状态、类型、Owner、团队或办理字段。
7. MatterCreated后MVP不生成登记、分配或办理Task。
8. 后MVP模块不能直接修改Matter Core私有数据。
9. 新增能力包不需要修改Matter Core。
10. 所有Matter及来源引用都在同一Tenant边界内。
11. 被引用TransferSnapshot不可覆盖、不可删除且可按摘要复核。
12. 关闭或未部署任何后MVP消费者不影响销售MVP完成转案。

## 14. 版本治理

以下变化必须升级本契约主版本，不能作为实现细节处理：

- 改变MatterRef签发Owner或唯一性。
- 删除或弱化TransferSnapshot准确版本/摘要引用。
- 把客户、合同、材料或办理状态复制进Matter Core。
- 把Matter创建移出接收原子事务。
- 改变Matter创建幂等键或允许一个TransferRequest创建多个Matter。
- 让后MVP模块回写、覆盖Matter origin。
- 让能力包直接依赖或修改Matter Core私有表。

新增后MVP模块、能力包或只读投影，只要遵守本契约，可在各自版本中独立演进，无需修改v1.0。

## 2026-08-27 P0一致性补充

当前MVP以TransferAccepted＋MatterRef闭环，不提前创建Matter页面或Matter业务表。每个新TransferSnapshot必须创建独立PRE_TRANSFER Review；不得复用旧scopeHash对应的Review。等待由Query Facade只读投影，不更新销售侧WaitReceipt或已完成Task。Matter扩展只能消费已接受转案事实，不得反向改写销售链历史。
