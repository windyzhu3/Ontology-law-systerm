# R1本地实施进度（2026-09-06）

当前位于本地分支`codex/r1-lead-contact-vertical-slice`。本记录区分合同修复、已有组件和真实业务交付；不代表已合并、已部署或R1已验收。

| 范围 | 当前证据与状态 |
|---|---|
| P0-01合同前置修复 | [ADR-0009](../adr/ADR-0009-p0-duplicate-automatic-assignment.md)与[Task V1.1矩阵](../contracts/r1/R1-TASK-COMPLETION-MATRIX.md)将有界自动Assignment指针与解析变更合为一次Lead CAS，保持revision +1、Decision完成事实与既有digest/event含义；基线升级为MVP-2026-09-06.1。Python验证结果见下文。 |
| 当前收口计划Task 1 | 本地合同对齐已完成，最终修订提交`58abbc432a609248c17d85157db799ff8e513737`；SERVICE capture、15个operation、审计披露与投影确认合同已落地。此前报告中曾有测试误报，已在原报告更正，不能引用被撤销的PASS。 |
| 当前收口计划Task 2 | 本地基础授权/运行时围栏已完成并提交`3a2b63a556297e81842421f52d50950695209d64`，真实PostgreSQL回归证据保留在原Task 2报告。它不代表生产Lead业务命令已实现。 |
| 当前收口计划Task 3 | 六个生产capture/P0 Handler及必要Owner读写、真实Draft原子确认、保护/规范化、Task后继/等待已实现。四个生产P0 IT类共33个测试，覆盖各分支、准确delta、幂等、权限变更、并发与技术失败回滚。固定运行时最终回归`task-3-resume-22-final-regression.log`：67 unit/architecture +218 IT，0 failures/errors/skips，进程exit0；218包含继承的既有运行时回归，不能当作218个新增业务测试。本地实现进入独立评审交接，尚不代表已评审通过、合并、部署或全R1验收。 |
| 当前收口计划Tasks 4–10 | 尚未完成。Draft完整服务、审计Query、contact/recovery、worker、HTTP装配、SPA与整体E2E/容量验收仍待实现或验证；准备brief不等于实施。 |

以上Task编号来自[2026-09-05收口计划](../superpowers/plans/2026-09-05-r1-business-closure-plan.md)，与早期R1计划编号不可混用。Task 1/2/3原始报告和日志保留在本地忽略目录`.superpowers/sdd/2026-09-05-r1-business-closure-plan/`的`task-1-report.md`、`task-2-report.md`、`task-3-report.md`。Task3报告保留原NEEDS_CONTEXT检查点、真实RED/GREEN与失败迭代、最终接口和逐分支证据；本轮只完成该业务实施步骤，不自动进入Task4。以下Python结果仍是前一轮合同修复证据，不冒充本轮重新执行。历史托管与合并证据见[2026-09-05分层验收报告](2026-09-05-r1-contract-closure-acceptance.md)。

本轮合同验证使用固定镜像`python@sha256:581429e3df12d76e6af4be5ab7d0e7fc2013eb57dc23d2de691411c8efdbb970`及PyYAML 6.0.3，所有进程均读取真实最终退出状态：

- `p0-contract-red-behavior.log`：7 tests，exit 1、1个预期断言失败、0 errors，证明旧解析器拒绝新结构。
- `p0-contract-green-verified.log`：10 tests，3.047秒，exit 0；有界自动分配、旧禁止字段、越界指针/revision、完成/事件/后继、候选不可变及新基线版本校验通过。
- `p0-contract-full-regression.log`：`python -m unittest discover -s scripts/baseline/tests -v`实际运行240 tests，171.889秒，exit 0；覆盖完整baseline/command/closure及本次新增合同测试。
- `p0-contract-baseline-cli-tracked.log`：通过既有`verify-baseline-locked.ps1`运行真实基线CLI，exit 0、baseline consistency PASS；R2仍有6个非致命阻塞（5个既有业务门禁，加新基线尚未合并）。
- `git diff --check`与暂存差异检查通过。物理合同、V001–V850、OpenAPI/事件Schema与前端文件对起始提交无差异。

上述日志保留于前述本地忽略目录。初始测试构造错误、两次fixture迭代失败和新证据文件尚未进入Git索引时的CLI失败日志也保留；不将这些失败命名中的“green”视为通过。最终完整报告为同目录`p0-contract-amendment-report.md`。

用户已推迟完整容量测试。此外，容量向量要求duplicate完成后产生ingress的路径，与duplicate必须已有匹配联系方式、ingress必须原始联系方式和槽全空的规则冲突，仍未解决。本次不改变该向量计数，不伪造容量资格。R1-BACKEND、R1-SPA、R1-E2E-GOLDEN、R1-E2E-FAILURES以及R2/R3门禁不因本次合同修复而推进。
