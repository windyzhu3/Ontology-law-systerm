# PR #2 Baseline And Ledger Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 PR #2 内建立唯一当前 MVP 基线、五态交付台账和机械一致性门禁，消除 WAITING、SPA/OpenAPI、Matter 终点及“52＋2永久上限”的双重解释。

**Architecture:** `CURRENT-MVP-BASELINE.md` 是唯一人工阅读入口；52＋2 Python 合同仍是数据库结构唯一人工维护源。历史规格保留但在文件顶部显式降级为历史证据。一个无第三方依赖的 Python 校验器把权威顺序、冻结不变量、资产计数和台账状态变成 CI 断言。

**Tech Stack:** Markdown、Python 3.12 标准库、`unittest`、GitHub Actions。

**Spec:** `docs/superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md`

**执行状态（2026-08-28）：** 本地实现与本计划要求的本地验证已经完成；分支推送、托管PR检查、合并，以及合并后的PostgreSQL运行时计划仍待控制器执行。运行时计划的未来事项不得在本PR中标记完成。

## Global Constraints

- 本计划只在 `fix/p0-workcard-design-consistency`（PR #2）执行；不添加生产后端、SPA、OpenAPI或新数据库表。
- 不手改 `database/schema-contract-52-plus-2/generated/` 下的任何生成物或19个既有Flyway迁移。
- 当前权威顺序必须固定为：当前基线 → Python合同源 → 生成物 → 运行时验证合同 → 交付台账 → 视觉证据 → 历史规格。
- 当前Task迁移集合必须与已合并52＋2合同一致：`OPEN → WAITING | DONE | CANCELLED`、`WAITING → OPEN | DONE | CANCELLED`；`WAITING → DONE`仅允许准确完成Fact同事务触发，交互行动必须先恢复`OPEN`。
- 状态值只能是 `DRAFT`、`FROZEN`、`MERGED`、`IMPLEMENTED`、`RUNTIME_VERIFIED`；不得用“完成”“已落地”“基本可用”等自由文本代替。
- `MERGED`只依据main提交；`IMPLEMENTED`必须有生产代码与测试证据；`RUNTIME_VERIFIED`必须有真实运行时证据。
- `Files`清单中的路径一律相对仓库根目录；Markdown链接一律相对其所在文件并由校验器验证可解析。CI不得联网获取动态规则。
- [ADR-0001](../../adr/ADR-0001-pr2-runtime-gate-order.md)冻结执行顺序：PR #2完成基线/历史/台账/静态数据库/只读CI收口后可先合并；合并后另建`DB-52P2-PG18-RUNTIME`运行时交付行，R1须等待该行达到`RUNTIME_VERIFIED`。

---

## Task 1: 先建立会失败的基线一致性测试

**Files:**

- Create: `scripts/baseline/tests/test_verify_baseline.py`
- Create: `scripts/baseline/verify_baseline.py`

- [x] 编写测试夹具，在临时目录中构造最小仓库，并逐项断言以下失败：缺少当前基线、规格仍为“待复核”、README没有唯一基线链接、历史规格缺少顶部或已知冲突标题级标记、WAITING入口缺少`SYSTEM_RECOVERY`、Matter终点缺少同事务`MatterCreated`或单一身份约束、台账含非法状态、销售/身份视觉索引的冻结资产数不分别为27和7。

测试方法名固定为`test_missing_canonical_baseline_fails`、`test_unapproved_closure_spec_fails`、`test_readme_must_link_canonical_baseline`、`test_every_historical_spec_has_superseded_banner`、`test_waiting_entry_conditions_are_frozen`、`test_matter_endpoint_is_frozen`、`test_ledger_rejects_unknown_delivery_state`和`test_visual_asset_counts_are_frozen`。

- [x] 运行测试，确认因校验器尚未实现而失败。

```bash
python3 -m unittest scripts.baseline.tests.test_verify_baseline -v
```

预期：非零退出，错误指向缺失的`verify_repository`或未实现断言，而不是测试发现失败。

- [x] 实现最小校验器，公开`verify_repository(root: Path) -> list[str]`和CLI；CLI逐行打印错误并以1退出，无错误时打印`baseline consistency: PASS`并以0退出。

- [x] 将冻结常量写在校验器中，避免新增第二份业务配置源：

```python
ALLOWED_STATES = {"DRAFT", "FROZEN", "MERGED", "IMPLEMENTED", "RUNTIME_VERIFIED"}
HISTORICAL_BANNER = "历史规格（HISTORICAL_SUPERSEDED）"
EXPECTED_VISUAL_ASSETS = {
    "docs/design/sales-mvp-workcards": 27,
    "docs/design/identity-admin-mvp": 7,
}
```

- [x] 校验器只扫描受控路径，排除`.git`、`__pycache__`和生成缓存；检查Markdown相对链接所指文件存在。

- [x] 再次运行测试，确认测试通过；随后运行CLI，确认它对当前仓库仍失败，因为基线、台账和历史标记尚未建立。

```bash
python3 -m unittest scripts.baseline.tests.test_verify_baseline -v
python3 scripts/baseline/verify_baseline.py
```

- [x] Commit:

```bash
git add scripts/baseline
git commit -m "test: add baseline consistency gate"
```

## Task 2: 建立唯一当前MVP基线

**Files:**

- Create: `docs/baseline/CURRENT-MVP-BASELINE.md`
- Create: `docs/adr/ADR-0001-pr2-runtime-gate-order.md`
- Modify: `docs/superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md`
- Modify: `README.md`
- Modify: `docs/specs/2026-08-17-law-firm-ontology-todo-chatbot-design.md`
- Modify: `docs/specs/2026-08-17-law-firm-sales-mvp-workcard-dialogue-design.md`
- Modify: `docs/specs/2026-08-18-law-firm-overall-architecture-ontology-design.md`
- Modify: `docs/specs/2026-08-18-minimal-matter-identity-post-mvp-extension-contract-v1.0.md`
- Modify: `docs/specs/2026-08-18-ontology-law-system-foundation-architecture-v1.0.md`
- Modify: `docs/specs/2026-08-18-ontology-law-system-project-module-build-contract-v1.0.md`
- Modify: `docs/specs/2026-08-19-ontology-law-system-postgresql-physical-model-guideline-v1.0.md`

- [x] 在`CURRENT-MVP-BASELINE.md`写入版本头：`Baseline ID: MVP-2026-08-28.1`、状态`FROZEN`、确认日期、当前合同摘要`a9c53d0126b7997e0aac511d3a4baf1da02a5f10d829ca5113458be51813034a`及字段合同摘要`be79d991fa9e13e3f0af1c682333b6a063201387b78f7c9ec32a03bad51096ed`；以ADR-0001记录PR #2与运行时门禁顺序，并保留R1 Ingress Completion决策编号ADR-0002。

- [x] 按固定章节写入且不引入新语义：权威顺序、七份历史规格与七个P0文末补充的显式替代表、产品不变量、一个SPA/一份OpenAPI/一个模块化单体/API与Worker互斥角色、WAITING/WaitReceipt/WaitingProjection、TransferAccepted＋MatterRef、52＋2版本边界、P0-01至P0-15验收映射、五态定义、R1→R2→R3门禁。基线未提及的旧语义不得自动复活；未决事项只能通过新ADR/基线版本处理。

- [x] 在WAITING章节明确以下可机械搜索的句子：

```text
所有TaskOccurrence先以OPEN、revision=0创建；禁止直接插入初始WAITING。
同Owner定时等待在同一事务内执行OPEN创建、CAS到WAITING、追加绑定revision=1的WaitReceipt。
WaitReceipt只记录一次真实OPEN→WAITING迁移，永久不可变；WaitingProjection只读计算且不新增表。
现有OPEN只有在原Owner仍承担同一责任且暂时不能安全行动，或进入SYSTEM_RECOVERY时，才可CAS到WAITING；每次迁移都增加revision并追加新的WaitReceipt。
等待其他Owner、客户、Provider或下游系统时，原人工Task完成为DONE，由准确下游责任或事实表达等待。
允许的完整转换集合为OPEN→WAITING|DONE|CANCELLED以及WAITING→OPEN|DONE|CANCELLED；WAITING→DONE只能由准确完成Fact原子触发。
```

- [x] 在拓扑章节列出历史“三SPA＋四OpenAPI”已被替代；在Matter章节列出MVP不建Matter业务表、页面或办理责任，并冻结同一事务写完整MatterRef与发布`MatterCreated`、Post-MVP不得生成第二Matter身份或反向改写销售历史；在52＋2章节列出未来扩展必须通过新ADR、合同版本和前向迁移。

- [x] 在R1门禁章节登记已确认的P0-02方案：保持52＋2表数，在Lead上增加类型化一次写入Ingress Completion槽，V850以前的迁移不可改写；ADR、合同v1.1、V850及v1.1真实PostgreSQL证据完成前，R1生产代码不得开始。P0-04的`REQUEST_SOURCE_INTAKE_STOP`只表示请求，不证明来源已停用。

- [x] 将已获用户确认的收口规格状态改为`已确认（FROZEN）`并记录`2026-08-28`；不得把它标为`MERGED`。

- [x] 在同一提交把README第一权威入口切到当前基线，并给全部七份历史规格标题后加入统一`HISTORICAL_SUPERSEDED` banner。权威切换必须原子完成，不能产生“新基线已存在、旧规格仍自称当前”的中间提交。

- [x] 运行校验器，确认权威入口和历史降级类错误已经消失，只剩Task 3尚未建立台账所产生的预期错误。

```bash
python3 scripts/baseline/verify_baseline.py
```

- [x] Commit:

```bash
git add README.md docs/baseline docs/specs docs/superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md
git commit -m "docs: establish canonical MVP authority"
```

## Task 3: 建立五态交付台账

**Files:**

- Create: `docs/progress/MVP-DELIVERY-LEDGER.md`
- Modify: `scripts/baseline/tests/test_verify_baseline.py`
- Modify: `scripts/baseline/verify_baseline.py`

- [x] 先增加失败测试：每一行必须含`ID、Release、Capability、Layer、Artifact、Owner、Version、Target gate、State、Evidence、Blocker/next gate、Superseded by`；ID唯一；证据必须为存在的相对链接；`IMPLEMENTED`行必须链接生产源码与测试，`RUNTIME_VERIFIED`行必须链接带版本/命令/退出码的证据。

- [x] 运行单测并确认新测试失败。

```bash
python3 -m unittest scripts.baseline.tests.test_verify_baseline -v
```

- [x] 创建台账；明确状态表示最高已验收门、不得跳级，旧版本通过`Superseded by`退出当前门而不是状态回退；设计和视觉可把`MERGED`作为目标终态。

- [x] 为销售12张基础稿、15张P0稿和身份7张稿逐文件建行，ID分别为`VIS-SALES-BASE-01..12`、`VIS-SALES-P0-01..15`、`VIS-IDENTITY-ADM-01..07`，每行记录同一视觉Bundle版本、Owner、确认日期和准确PNG链接。

- [x] 记录以下非视觉行的初始真值：

| ID | 初始状态 | 证据与解释 |
|---|---|---|
| `DB-52P2-CONTRACT` | `MERGED` | main提交`a080f45`中的Python合同源；尚非实库验证 |
| `DB-52P2-MIGRATIONS` | `MERGED` | main提交`a080f45`中的19个生成迁移；尚非实库验证 |
| `BASE-CLOSURE-DESIGN` | `FROZEN` | 本PR已确认规格；待合并 |
| `BASE-PR2-CLOSURE-PLAN` | `DRAFT` | 本实施计划；尚未执行，不是当前基线本身 |
| `BASE-CURRENT-MVP` | `FROZEN` | 由2026-08-28用户确认的收口规格派生；待合并 |
| `R1-IMPLEMENTATION-PLAN` | `DRAFT` | 已存在实施计划；不是生产代码 |
| `DB-52P2-PG18-RUNTIME-PLAN` | `DRAFT` | 已存在实库验证计划；不是运行证据 |

- [x] 在台账中单列“状态不是百分比”，并注明`FROZEN`视觉不等于`IMPLEMENTED`、静态SQL验证不等于`RUNTIME_VERIFIED`。

- [x] 实现台账解析校验，允许计划自身作为`DRAFT`交付物；尚无可定位源码的`R1-OPENAPI`、`R1-BACKEND`、`R1-SPA`、`R1-E2E-GOLDEN`和`R1-E2E-FAILURES`不得伪造DRAFT行，由门禁矩阵把“缺少必需行”报告为未满足。错误信息必须明确“计划不是生产代码”。

- [x] 运行测试和CLI，确认台账结构通过。

```bash
python3 -m unittest scripts.baseline.tests.test_verify_baseline -v
python3 scripts/baseline/verify_baseline.py
```

- [x] Commit:

```bash
git add docs/progress scripts/baseline
git commit -m "docs: add five-state delivery ledger"
```

## Task 4: 标记冲突条款并收窄从属权威

**Files:**

- Modify: `README.md`
- Modify: `docs/specs/2026-08-17-law-firm-ontology-todo-chatbot-design.md`
- Modify: `docs/specs/2026-08-17-law-firm-sales-mvp-workcard-dialogue-design.md`
- Modify: `docs/specs/2026-08-18-law-firm-overall-architecture-ontology-design.md`
- Modify: `docs/specs/2026-08-18-minimal-matter-identity-post-mvp-extension-contract-v1.0.md`
- Modify: `docs/specs/2026-08-18-ontology-law-system-foundation-architecture-v1.0.md`
- Modify: `docs/specs/2026-08-18-ontology-law-system-project-module-build-contract-v1.0.md`
- Modify: `docs/specs/2026-08-19-ontology-law-system-postgresql-physical-model-guideline-v1.0.md`
- Modify: `docs/design/sales-mvp-workcards/README.md`
- Modify: `docs/design/identity-admin-mvp/README.md`
- Modify: `database/schema-contract-52-plus-2/README.md`
- Modify: `database/schema-contract-52-plus-2/docs/runtime-validation-contract.md`
- Modify: `database/schema-contract-52-plus-2/VERIFICATION.md`

- [x] 保持Task 2已原子加入的统一警告块，并把原版本/状态明确标为“历史元数据”；不删除历史正文：

```markdown
> [!WARNING]
> 历史规格（HISTORICAL_SUPERSEDED）。本文仅保留设计演进证据；与[当前MVP基线](../baseline/CURRENT-MVP-BASELINE.md)冲突时，当前基线及52＋2合同优先。本文不得作为新实现或DDL生成依据。
```

- [x] 把七个`2026-08-27 P0一致性补充`标题改为“历史修订记录（已被当前基线替代）”，删除其中“当前权威、优先于、以本补充为准”等活动权威措辞；仍有效的P0-01至P0-15映射完整写入当前基线。

- [x] 在已知冲突标题后增加可机器识别的`superseded-by: docs/baseline/CURRENT-MVP-BASELINE.md`与准确`replacement-section`标记：WAITING类用`task-waiting-contract`，拓扑类用`application-topology`，Matter类用`matter-endpoint`。覆盖overall的§3.2、§7.2、§9、§17–18；Matter合同的§3.1、§4–7、§13–14；foundation的§3–6、§12.1、§12.4、§28.2、§31；build合同的§2、§7.3 Matter内容、§12、§16.1相关门禁、§18；physical guideline的§1.2、§1.4、§5.2、§6.7、§11。不逐段重写历史正文以伪造历史一致性。

- [x] 重写README的“最新权威交付物”和“历史阅读顺序”：第一链接必须是`docs/baseline/CURRENT-MVP-BASELINE.md`，不得继续依赖“各规格末尾补丁优先”的隐式规则。

- [x] 在两份视觉索引中写明视觉状态`FROZEN`而非`IMPLEMENTED`，补充准确Bundle版本、Owner和确认日期，并保持销售27张、身份7张PNG的显式资产清单和P0映射；视觉证据不得产生领域、拓扑或生命周期规则。

- [x] 收窄从属文档权威：数据库README只对结构/物理合同负责并链接当前语义基线；运行时验证合同声明为从属运行时补充且冲突失败关闭；`VERIFICATION.md`把“已完成验证/冻结摘要”改为“静态校验/非规范证据”，继续明确真实PostgreSQL尚未运行。

- [x] 运行测试和校验器，确认所有历史规格顶部标记、链接与资产计数通过。

```bash
python3 -m unittest scripts.baseline.tests.test_verify_baseline -v
python3 scripts/baseline/verify_baseline.py
```

- [x] Commit:

```bash
git add README.md docs/specs docs/design database/schema-contract-52-plus-2/README.md database/schema-contract-52-plus-2/docs/runtime-validation-contract.md database/schema-contract-52-plus-2/VERIFICATION.md
git commit -m "docs: make baseline precedence explicit"
```

## Task 5: 接入CI并完成PR #2收口验收

**Files:**

- Create: `.github/workflows/baseline-consistency.yml`
- Modify: `docs/progress/MVP-DELIVERY-LEDGER.md`
- Modify: `database/schema-contract-52-plus-2/tests/test_domain_semantics.py`

- [x] 新建只读CI，触发路径覆盖`README.md`、`docs/**`、`database/schema-contract-52-plus-2/**`、`scripts/baseline/**`和工作流本身；使用`actions/checkout@v4`与`actions/setup-python@v5`、Python 3.12。

- [x] CI依次执行：

```bash
python3 -m unittest scripts.baseline.tests.test_verify_baseline -v
python3 scripts/baseline/verify_baseline.py
cd database/schema-contract-52-plus-2
python3 generate.py --check
python3 -m unittest discover -s tests -v
```

- [x] 在数据库语义测试中精确断言：Task初始`OPEN`；完整迁移集合与当前基线一致；WaitReceipt无更新策略且`(task,revision)`唯一、revision为正；不存在`matter_core` Schema/Table；TransferRequest含完整MatterRef all-or-none/write-once槽。基线校验器另须断言Matter章节明确同事务发布`MatterCreated`、禁止第二Matter身份和反向改写销售历史；数据库结构测试不得伪称能证明事件已在运行时发布。

- [x] 本地执行同一套验证；如`pglast==7.10`已安装，再执行`python3 scripts/verify_generated_sql.py`，否则在隔离虚拟环境安装`requirements-dev.txt`后执行。

- [x] 扫描仓库，确认计划和基线没有占位符或矛盾词：

```bash
rg -n "TODO|TBD|待定|待用户复核" docs/baseline docs/progress docs/superpowers/specs
rg -n "三个SPA|三份SPA|四份OpenAPI|WaitReceipt是可变|新建WAITING" README.md docs/baseline
```

预期：第一条无输出；第二条只允许出现在明确写有“历史方案已被替代”的上下文。

- [x] 更新台账的证据链接和校验日期，但保持本PR资产为`FROZEN`；只有PR合并后由独立提交将其推进为`MERGED`。保持`DB-52P2-CONTRACT`和`DB-52P2-MIGRATIONS`为`MERGED`，且不预造尚无运行证据的`DB-52P2-PG18-RUNTIME`行。

- [x] 检查差异只含本计划授权范围，并提交：

```bash
git diff --check
git status --short
git add .github/workflows/baseline-consistency.yml docs/progress/MVP-DELIVERY-LEDGER.md
git commit -m "ci: enforce MVP baseline consistency"
```

- [ ] 推送PR #2分支并更新PR说明，列出：已确认基线、五态台账、历史降级、静态验证结果、尚未完成的真实PostgreSQL验证及R1实现。不得把计划项描述为已经实现。

## Exit Gate

- [ ] PR #2全部检查通过，且Changed Files不含生产应用代码或手改生成SQL。
- [x] 用户确认的规格状态为`FROZEN`，当前基线成为README第一权威入口。
- [x] 七份历史规格均有顶部`HISTORICAL_SUPERSEDED`标记。
- [x] 五态台账准确显示“设计已冻结、数据库仅静态验证、生产代码尚未开始”。
- [ ] 完成后按顺序执行`2026-08-28-postgresql-runtime-verification-plan.md`；该门禁通过前不得执行R1生产实现。
