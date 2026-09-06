# R1本地实施进度（2026-09-06）

当前位于本地分支`codex/r1-lead-contact-vertical-slice`。本记录区分合同修复、已有组件和真实业务交付；不代表已合并、已部署或R1已验收。

| 范围 | 当前证据与状态 |
|---|---|
| P0-01合同前置修复 | [ADR-0009](../adr/ADR-0009-p0-duplicate-automatic-assignment.md)与[Task V1.1矩阵](../contracts/r1/R1-TASK-COMPLETION-MATRIX.md)将有界自动Assignment指针与解析变更合为一次Lead CAS，保持revision +1、Decision完成事实与既有digest/event含义；基线升级为MVP-2026-09-06.1。Python验证结果见下文。 |
| 当前收口计划Task 1 | 本地合同对齐已完成，最终修订提交`58abbc432a609248c17d85157db799ff8e513737`；SERVICE capture、15个operation、审计披露与投影确认合同已落地。此前报告中曾有测试误报，已在原报告更正，不能引用被撤销的PASS。 |
| 当前收口计划Task 2 | 本地基础授权/运行时围栏已完成并提交`3a2b63a556297e81842421f52d50950695209d64`，真实PostgreSQL回归证据保留在原Task 2报告。它不代表生产Lead业务命令已实现。 |
| 当前收口计划Task 3 | 本地实现及独立评审已完成，代码提交`df9b985a2b371e93016890fd1fc8ffc7e50415f7`。六个生产capture/P0 Handler及必要Owner读写、真实Draft原子确认、保护/规范化、Task后继/等待已实现。四个生产P0 IT类共33个测试，覆盖各分支、准确delta、幂等、权限变更、并发与技术失败回滚。固定运行时最终回归`task-3-resume-22-final-regression.log`：67 unit/architecture +218 IT，0 failures/errors/skips，进程exit0；218包含继承的既有运行时回归，不能当作218个新增业务测试。独立评审：Spec compliant、Task quality Approved，无Critical/Important；不代表已合并、部署或全R1验收。 |
| 当前收口计划Tasks 4–10 | 尚未完成。Draft完整服务、审计Query、contact/recovery、worker、HTTP装配、SPA与整体E2E/容量验收仍待实现或验证；准备brief不等于实施。 |

以上Task编号来自[2026-09-05收口计划](../superpowers/plans/2026-09-05-r1-business-closure-plan.md)，与早期R1计划编号不可混用。Task 1/2/3原始报告和日志保留在本地忽略目录`.superpowers/sdd/2026-09-05-r1-business-closure-plan/`的`task-1-report.md`、`task-2-report.md`、`task-3-report.md`。Task3报告保留原NEEDS_CONTEXT检查点、真实RED/GREEN与失败迭代、最终接口和逐分支证据；本轮只完成该业务实施步骤，不自动进入Task4。以下Python结果仍是前一轮合同修复证据，不冒充本轮重新执行。历史托管与合并证据见[2026-09-05分层验收报告](2026-09-05-r1-contract-closure-acceptance.md)。

## Task 3完成复核与范围

提交后的独立复验使用固定JDK/Maven与迁移后的真实PostgreSQL：`task-3-root-committed-verification.log`实际通过20个unit/architecture及2个授权IT；`task-3-root-duplicate-verification.log`实际通过2个unit及1个包含六种重复确认组合的IT，进程均exit0。初次专项命令的重复分支方法名未匹配，未将其计入通过数，随后用准确方法名完成后一个独立日志。

`task-3-root-final-baseline.log`记录本轮固定Python真实基线CLI的exit0/PASS，仍有6个预期非致命后续门禁阻塞。`3a2b63a..df9b985`的`contracts/`、`database/`和`apps/`字节差异为空；OpenAPI SHA256仍为`a1ab94f96c2e9016c70ca3591cda60695f98526eccb6c2ec4cf09faa53b76c1c`。未新增表、迁移、公共接口、权限、前端或后续业务功能；来源停用仅创建/确认责任，不修改实际来源配置。

本步安全收口补齐共享命令策略的准确Task/Lead/Owner复验，并在Handler最终校验前保存持锁授权结果，避免回滚丢失准确版本的DENY审计证据。代价是增加最终Owner/授权读取；完整容量影响尚未验收。Draft保存/编辑、Query披露审计、首联/recovery、Worker、HTTP安全装配、SPA和E2E仍由Tasks4–10承担。

独立评审留下两项非阻断改进：新编排/持久化方法的长行可读性，以及已有OpenAPI生成器、JAXB和Flyway日志噪声；留给后续整分支复核，不为本次扩大重构范围。测试通过不表示构建无警告。当前完成的是10个交付单元中的Task 1–3，不能据此推算整体工作量完成百分比。

## 历史合同修复验证

前一轮合同验证使用固定镜像`python@sha256:581429e3df12d76e6af4be5ab7d0e7fc2013eb57dc23d2de691411c8efdbb970`及PyYAML 6.0.3，所有进程均读取真实最终退出状态：

- `p0-contract-red-behavior.log`：7 tests，exit 1、1个预期断言失败、0 errors，证明旧解析器拒绝新结构。
- `p0-contract-green-verified.log`：10 tests，3.047秒，exit 0；有界自动分配、旧禁止字段、越界指针/revision、完成/事件/后继、候选不可变及新基线版本校验通过。
- `p0-contract-full-regression.log`：`python -m unittest discover -s scripts/baseline/tests -v`实际运行240 tests，171.889秒，exit 0；覆盖完整baseline/command/closure及本次新增合同测试。
- `p0-contract-baseline-cli-tracked.log`：通过既有`verify-baseline-locked.ps1`运行真实基线CLI，exit 0、baseline consistency PASS；R2仍有6个非致命阻塞（5个既有业务门禁，加新基线尚未合并）。
- `git diff --check`与暂存差异检查通过。物理合同、V001–V850、OpenAPI/事件Schema与前端文件对起始提交无差异。

上述日志保留于前述本地忽略目录。初始测试构造错误、两次fixture迭代失败和新证据文件尚未进入Git索引时的CLI失败日志也保留；不将这些失败命名中的“green”视为通过。最终完整报告为同目录`p0-contract-amendment-report.md`。

用户已推迟完整容量测试。此外，容量向量要求duplicate完成后产生ingress的路径，与duplicate必须已有匹配联系方式、ingress必须原始联系方式和槽全空的规则冲突，仍未解决。本次不改变该向量计数，不伪造容量资格。R1-BACKEND、R1-SPA、R1-E2E-GOLDEN、R1-E2E-FAILURES以及R2/R3门禁不因本次合同修复而推进。
