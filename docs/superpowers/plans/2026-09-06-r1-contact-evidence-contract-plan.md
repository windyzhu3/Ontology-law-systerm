# R1 Contact Reopen and Evidence Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将已批准的主管重开及Evidence只读规则激活为一致、可机械验证的活动合同，为原Task6解除合同前置阻塞，不宣称生产功能已完成。

**Architecture:** 本计划是单一原子合同实施单元，只修改活动文档、baseline验证器与其测试。通过封闭注册表和负向变异测试冻结次数、引用资格、授权及DAG，保持数据库和API字节不变。Java Owner、事件策略、工作卡与实际Handler实现归原Task6，交接要求见下文，不在本计划重复实施。

**Tech Stack:** Markdown contracts、Python unittest/PyYAML、现有baseline verifier；固定Python容器、PowerShell7、Git隔离工作区。没有新依赖。

**Spec:** `docs/superpowers/specs/2026-09-06-r1-contact-reopen-evidence-read-design.md`（APPROVED）。执行者必须完整读取本规格与计划。

## Global Constraints

- 一个SPA、一份OpenAPI、一个模块化单体Jar，API/Worker互斥；11 public＋4 internal operation及既有DTO字段形状不变。
- 13 Schema、52应用表＋2技术表、活动物理能力`52-plus-2-v1.2`保持；V001–V860、manifest、field contract、DB权限及历史证据字节不变，无新迁移。
- Evidence既有数据库SELECT足够；模块读口增加不意味着可扩大数据库角色能力。jOOQ只生成本修订需要的两张Evidence表到所属Owner包，不生成DAO或Active Record。
- 锁顺序、身份授权路径、完成事实、Draft确认、Command幂等与原子Receipt/Audit/Event/Outbox不变。
- 不新增自动重试轮次、Evidence上传/下载/管理界面、Provider发送、AI、ADM-01～07、R2+、通用回放或投影表。
- 不因合同修订提升R1-BACKEND、SPA、E2E、容量或发布门禁。容量环境仍后补；历史容量向量冲突不在此次修订范围内。

本计划额外边界：不修改`backend/`、`apps/`、`contracts/`、`database/`、历史`docs/evidence/`或工作流；无推送、合并、部署、身份配置或GRANT。批准规格中的Java/jOOQ变化仅在随后原Task6实施。使用既有`codex/r1-lead-contact-vertical-slice`隔离工作区，不在主工作区执行。

## 文件职责与接口

| 文件 | 职责 |
|---|---|
| 新增`docs/adr/ADR-0011-r1-contact-reopen-evidence-read.md` | 具名替代，准确目标版本、保留项及分层验收。 |
| `docs/contracts/r1/R1-TASK-COMPLETION-MATRIX.md` | contactNo谓词、复核因果及重开结果，版本`R1-TASK-COMPLETION-V1.2`。 |
| `docs/contracts/r1/R1-COMMAND-POLICY-EVENT-CONTRACT.md` | 封闭注册表与Evidence授权/Owner边，事件集合不变。 |
| `docs/contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md` | 既有可选字段资格、安全NOT_FOUND及准确阶段delta，无新shape/错误。 |
| `docs/contracts/r1/R1-WORKBENCH-PRESENTATION-CONTRACT.md` | 第四次复核因果、Evidence候选回显/不可见卡/ETag与Audit。 |
| `docs/baseline/CURRENT-MVP-BASELINE.md` | 激活`MVP-2026-09-06.3`，物理版本仍v1.2。 |
| `docs/progress/MVP-DELIVERY-LEDGER.md`及`docs/progress/2026-09-06-r1-local-progress.md` | 同步活动合同引用，保持未实现/未验收状态。 |
| `docs/superpowers/specs/2026-09-05-r1-business-closure-alignment-design.md`及`docs/superpowers/plans/2026-09-05-r1-business-closure-plan.md` | 只增加具名替代索引和Task6消费者提示，保留历史正文与完成标记。 |
| 新增`scripts/baseline/r1_contact_evidence_contract.py` | `validate(root: Path) -> list[str]`，只验证合同结构，不伪装运行时检测。 |
| `scripts/baseline/verify_baseline.py`、`scripts/baseline/r1_business_closure_contract.py` | 集成新校验、更新活动ID和旧分支期待值，既有门禁不放宽。 |
| 新增`scripts/baseline/tests/test_r1_contact_evidence_contract.py` | 正向完整注册表、逐项负向变异、缺失/非法UTF8测试。 |
| `scripts/baseline/tests/test_verify_baseline.py`、`scripts/baseline/tests/test_r1_business_closure_contract.py`、`scripts/baseline/tests/test_r1_command_contract.py` | 更新活动夹具并覆盖集成路径，保留历史版本/托管证据隔离断言。 |

如`rg`发现其他活动ID消费者，只允许同步`docs/`和`scripts/baseline/`中直接依赖的当前夹具/引用；禁止全库替换历史版本。新增范围必须在实现报告列出理由。

生产代码仍带旧max3约束是本计划结束时的已知待实现项，不得因为合同验证PASS就将其认定修复。实际事件max3和DAG旁路由原Task6运行测试验证；本计划负向测试验证的是合同是否禁止这些行为。

## Task 1: 原子激活两项合同修订并建立机械门禁

**Files:** 上方表格中的全部文件构成本任务唯一变更面；不拆成“改合同但验证器仍旧”或“验证器放行但合同仍旧”的提交。

**Interfaces:**
- Consumes: 已批准规格、`verify_repository(root)`及既有`r1_business_closure_contract.validate(root)`、当前临时仓库夹具`VerifyBaselineTest.create_valid_repository(root, ...)`。
- Produces: `scripts.baseline.r1_contact_evidence_contract.validate(root: Path) -> list[str]`；空列表表示本修订的静态合同完整，否则每条为安全、具名定位的缺失/冲突。不调用网络或Git，不读取运行时凭据。
- Downstream: 原Task6使用下列三个准确注册表与规格完整段落，不自行创建另一份次数或Evidence授权规则。

- [ ] **Step 1: 记录范围基线并检查已有修改。** 保存`git rev-parse HEAD`为本任务BASE；`git status --short`、`git diff --check`；确认APPROVED规格、当前物理v1.2。未完成任务只启动一次；使用本计划自己的忽略工作区记录BASE、日志和评审报告，不清理其他计划目录。

- [ ] **Step 2: 写合同正向断言并实际观察RED。** 新增测试文件；从真实活动文档复制到`TemporaryDirectory`，不改原仓库文档作为测试副作用。以下测试入口及安全导入模式必须保留，缺模块产生明确断言失败而非ImportError：

```python
import importlib.util
import importlib
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]

def validate(root: Path) -> list[str]:
    name = "scripts.baseline.r1_contact_evidence_contract"
    if importlib.util.find_spec(name) is None:
        return ["R1 contact/evidence validator missing"]
    return importlib.import_module(name).validate(root)

class R1ContactEvidenceContractTest(unittest.TestCase):
    def test_current_contract_has_complete_approved_amendment(self):
        self.assertEqual([], validate(ROOT))
```

Run: `python -m unittest scripts.baseline.tests.test_r1_contact_evidence_contract -v`，在下方固定容器中运行。预期exit1，测试失败原因是修订校验缺失；只记录实际输出。不要把容器、依赖或路径失败当业务RED。

- [ ] **Step 3: 写逐项负向测试。** 在临时副本中建立完整的新规则注册表，先断言`validate(root)==[]`；每次只改一个值，断言得到该profile的finding，恢复后再次为空。注册表解析须按准确列数/表头/ID、唯一性及完整键集合，不靠文档中随意出现一个字符串判PASS。重复行、未知行、缺字段、表头改变、正文藏在代码块/历史说明中均拒绝。至少覆盖下面每个突变：

```python
mutations = (
    ("contactNo>=3", "contactNo=3"),
    ("LEAD_GLOBAL_MONOTONIC", "RESET_ON_REVIEW"),
    ("MAX_INITIAL_CONTACT_NO_3", "THREE_PER_REVIEW_CYCLE"),
    ("NEW_OPEN_TASK", "REOPEN_TERMINAL_TASK"),
    ("CURRENT_LEAD_EXACT_REVISION", "SAME_LEAD_ID_ONLY"),
    ("ACTIVE_NOT_REVOKED", "BINDING_OPTIONAL"),
    ("TASK,LEAD,SUBMISSION,BINDING", "TASK,LEAD"),
    ("DIRECT,DELEGATED", "DIRECT,DELEGATED,OBJECT"),
    ("QUERY_ONLY", "COMMAND_FALLBACK"),
    ("AUDIT_BEFORE_200_AND_304", "AUDIT_ONLY_200"),
    ("R1_BUSINESS_TENANT_LOCK", "IDENTITY_LOCK_ONLY"),
    ("evidence_submission,evidence_binding", "evidence.*"),
)
```

用`subTest`循环这些独立变异不是12个新测试方法；报告分别给出JUnit/unittest实际数及情景数。额外覆盖缺ADR、基线或Task版本错误、允许第四次自动重试、Binding/Submission DENY漏项、额外DAG边、下载字段、物理版本被推进以及历史证据被当成新功能完成的门禁回归。

- [ ] **Step 4: 建立具名ADR及准确活动规则。** ADR文件必须写明本次两个替代点、原字段/事件/物理权限保持、生产待实现、未来Evidence绑定写者接入业务围栏。更新Task行的`attemptNo<3`→`contactNo<3`、`attemptNo=3`→`contactNo>=3`及复核因果`contact_no>=3`，同步早期疑似无效后重开的例子；不得修改序号唯一性或原自动日历时点。

在命令/事件合同末尾追加以下三个封闭注册表（属性值大小写/顺序准确保留），旁边链接批准规格并写入其§2–3的完整语义：

```markdown
## R1 contact ordinal registry

| Profile | Key | Value |
|---|---|---|
| R1_CONTACT_ORDINAL_V1 | ordinal | LEAD_GLOBAL_MONOTONIC |
| R1_CONTACT_ORDINAL_V1 | retry | contactNo<3 |
| R1_CONTACT_ORDINAL_V1 | exhausted | contactNo>=3 |
| R1_CONTACT_ORDINAL_V1 | automaticBudget | MAX_INITIAL_CONTACT_NO_3 |
| R1_CONTACT_ORDINAL_V1 | supervisorReopen | NEW_OPEN_TASK |
| R1_CONTACT_ORDINAL_V1 | connectedValid | ANY_SAFE_POSITIVE_CONTACT_NO |
| R1_CONTACT_ORDINAL_V1 | suspectInvalid | ANY_SAFE_POSITIVE_CONTACT_NO |

## R1 evidence reference registry

| Profile | Key | Value |
|---|---|---|
| R1_CONTACT_EVIDENCE_REF_V1 | input | OPTIONAL_SUBMISSION_ID |
| R1_CONTACT_EVIDENCE_REF_V1 | absent | ZERO_EVIDENCE_READ |
| R1_CONTACT_EVIDENCE_REF_V1 | tenant | ACTOR_TENANT |
| R1_CONTACT_EVIDENCE_REF_V1 | target | CURRENT_LEAD_EXACT_REVISION |
| R1_CONTACT_EVIDENCE_REF_V1 | binding | ACTIVE_NOT_REVOKED |
| R1_CONTACT_EVIDENCE_REF_V1 | subjects | TASK,LEAD,SUBMISSION,BINDING |
| R1_CONTACT_EVIDENCE_REF_V1 | authority | ASSIGNMENT_OWNER/SALES_CONTACT_OWNER |
| R1_CONTACT_EVIDENCE_REF_V1 | paths | DIRECT,DELEGATED |
| R1_CONTACT_EVIDENCE_REF_V1 | scope | TASK_OWNER_ORGANIZATION |
| R1_CONTACT_EVIDENCE_REF_V1 | submissionSelector | IMMUTABLE_ROW_JCS_SHA256 |
| R1_CONTACT_EVIDENCE_REF_V1 | bindingSelector | EXACT_REVISION |
| R1_CONTACT_EVIDENCE_REF_V1 | capability | QUERY_ONLY |
| R1_CONTACT_EVIDENCE_REF_V1 | disclosure | AUDIT_BEFORE_200_AND_304 |
| R1_CONTACT_EVIDENCE_REF_V1 | cache | SELECTORS_AND_AUTH_DEPENDENCIES |
| R1_CONTACT_EVIDENCE_REF_V1 | invalid | SAFE_NOT_FOUND |
| R1_CONTACT_EVIDENCE_REF_V1 | hiddenCard | NEXT_ELIGIBLE_OR_ZERO |
| R1_CONTACT_EVIDENCE_REF_V1 | bindingWriterFence | R1_BUSINESS_TENANT_LOCK |
| R1_CONTACT_EVIDENCE_REF_V1 | writes | NONE |
| R1_CONTACT_EVIDENCE_REF_V1 | files | NO_CONTENT_OR_LOCATOR |

## R1 evidence owner registry

| Owner | Tables | Dependencies | Consumers |
|---|---|---|---|
| evidence | evidence_submission,evidence_binding | identity | lead,api |
```

`QUERY_ONLY`只约束Evidence引用读取，不能理解为整个Contact命令不使用COMMAND；读取安排在Runtime现有QUERY阶段，执行阶段只消费已验证selector，最终QUERY阶段再复验。不能在Owner方法内部切角色。注册表没有改变默认事件数、scope、输入shape或Evidence用途，不允许从中推导额外写权限。

- [ ] **Step 5: 实现最小静态校验并集成。** 独立模块用标准库`Path`读取及受控Markdown表解析，失败返回finding；拒绝缺失/非法UTF8、重复键和未知行。常量表就是上方准确键值；检查Task矩阵的两个新分支、复核因果、ADR与活动版本，并检查HTTP/Workbench有准确引用/安全错误/审计链接和条款。通过现有总验证入口调用它，测试不能只调用子模块而漏掉集成：

```python
try:
    from scripts.baseline.r1_contact_evidence_contract import validate as validate_r1_contact_evidence_contract
except ModuleNotFoundError:  # 保持直接脚本入口兼容。
    from r1_contact_evidence_contract import validate as validate_r1_contact_evidence_contract

# 在现有收集findings的仓库验证阶段追加，不跳过任何旧检查。
structural_findings.extend(validate_r1_contact_evidence_contract(root))
```

更新总验证器及临时仓库生成器的活动ID/Task分支期待值。复用现有安全读取/解析模式，但不跨模块导入私有实现导致循环。缺失新合同不能被旧v1.1/物理v1.2报告豁免。当前代码max3仍未修复，不把静态契约校验写成必须提前改Java才能通过的混合门禁。

- [ ] **Step 6: 同步全部活动文档与夹具。** 当前基线改`MVP-2026-09-06.3`；Task合同改`R1-TASK-COMPLETION-V1.2`；其余合同注明新语义基线及ADR，不暗改历史ID。HTTP明确无效引用统一NOT_FOUND和阶段delta，Workbench明确Evidence selector/DENY/回显/缓存/审计与第四次因果资格。原收口规格/计划只新增替代索引，台账只登记合同修订，不设置业务IMPLEMENTED或RUNTIME_VERIFIED。用`rg -n 'MVP-2026-09-06.2|R1-TASK-COMPLETION-V1.1|attemptNo|contact_no=3' docs scripts/baseline`逐项区分活动与历史，不批量替换。

- [ ] **Step 7: 实跑定向GREEN及完整baseline测试。** 定向命令包含新模块、`test_r1_business_closure_contract`及`test_r1_command_contract`；然后运行`python -m unittest discover -s scripts/baseline/tests -v`。所有执行需exit0、非零测试、无跳过必需场景；保留失败迭代日志。验证旧物理v1.2托管运行证据缺项和后端/SPA/E2E门禁继续未满足。

- [ ] **Step 8: 运行真实baseline CLI和冻结字节检查。** 对Git感知CLI使用下方独立Git挂载，不能将其GIT_*覆盖传给临时Git夹具单测。确认baseline consistency PASS，R2仍BLOCKED，记录实际条目而非硬编码7。检查`git diff BASE -- backend apps contracts database docs/evidence`为空；文档相对链接存在、`git diff --check`通过；所有改动均位于上方文件职责范围。不要把静态PASS当成已修复事件策略/证据读口。

- [ ] **Step 9: 自审、提交与独立评审。** 自审逐行检查规格§2–5有合同落点及负向测试；将命令、退出码、实际计数、版本、冻结字节检查和原Task6交接写入本计划报告。只暂存已检查文件，提交`docs: activate R1 contact reopen and evidence reference contract`。独立评审完整`BASE..HEAD`的规格符合性与质量，修复Important/Critical后再交接；原Task6不得在本任务未评审时启动。

## 固定验证命令

本计划沿用已用于本地合同验证的固定Python镜像`python@sha256:581429e3df12d76e6af4be5ab7d0e7fc2013eb57dc23d2de691411c8efdbb970`及PyYAML6.0.3；运行日志记录实际Python版本及镜像摘要，不自行用latest替换。数据库runtime lock记录的是PostgreSQL/Flyway，不能将其误称为Python锁文件。

在PowerShell7中运行普通测试，工作区只读挂载；将每次输出重定向到本计划忽略工作区的唯一日志，并捕获`$LASTEXITCODE`。以下完整回归命令不注入Git覆盖环境：

```powershell
$taskRoot=(Get-Location).Path
docker run --rm --mount "type=bind,source=$taskRoot,target=/workspace,readonly" -w /workspace python@sha256:581429e3df12d76e6af4be5ab7d0e7fc2013eb57dc23d2de691411c8efdbb970 sh -c 'pip install --disable-pip-version-check PyYAML==6.0.3 && python -B -m unittest discover -s scripts/baseline/tests -v'
$taskExit=$LASTEXITCODE
if ($taskExit -ne 0) { throw "Baseline suite failed: $taskExit" }
```

定向RED/GREEN只将上述`python -B -m unittest discover ...`替换成准确模块列表，不改变运行时/挂载：

```text
python -B -m unittest scripts.baseline.tests.test_r1_contact_evidence_contract -v
python -B -m unittest scripts.baseline.tests.test_r1_contact_evidence_contract scripts.baseline.tests.test_r1_business_closure_contract scripts.baseline.tests.test_r1_command_contract -v
```

真实Git感知CLI：

```powershell
$taskRoot=(Get-Location).Path
$taskGitCommon=[IO.Path]::GetFullPath((Join-Path $taskRoot (git rev-parse --git-common-dir)))
$taskGitLeaf=Split-Path (git rev-parse --git-dir) -Leaf
docker run --rm --mount "type=bind,source=$taskRoot,target=/workspace,readonly" --mount "type=bind,source=$taskGitCommon,target=/repo-git,readonly" -e "GIT_DIR=/repo-git/worktrees/$taskGitLeaf" -e GIT_COMMON_DIR=/repo-git -e GIT_WORK_TREE=/workspace -w /workspace python@sha256:581429e3df12d76e6af4be5ab7d0e7fc2013eb57dc23d2de691411c8efdbb970 sh -c 'pip install --disable-pip-version-check PyYAML==6.0.3 && git config --global --add safe.directory /workspace && python -B scripts/baseline/verify_baseline.py'
$taskExit=$LASTEXITCODE
if ($taskExit -ne 0) { throw "Baseline CLI failed: $taskExit" }
```

真实CLI若要求新增文档已跟踪，先审查并精确暂存后再运行；不要提交伪造验收记录来满足门禁。容器内临时safe.directory配置不修改宿主Git设置。宿主是已存在linked worktree，若路径解析不符合此结构则停止并诊断，不猜Git目录。

## 原Task6交接：后续实施，不是本计划第二次实现

本计划完成仅意味着活动合同与验证器对齐。交接到原`2026-09-05-r1-business-closure-plan.md` Task6，记录本次合同提交为其新BASE前置；原Task6仍为未完成。用原Task6的独立实施/评审门禁一次性完成下列代码，不在两个计划间重复派发：

| 规格要求 | 原Task6具体消费者与验收 |
|---|---|
| §2序号、自动上限、主管三结果 | `lead/ContactResultService.java`、`lead/RetryPolicy.java`、`lead/internal/persistence/JooqContactResultRepository.java`；`ContactResultIT`、`LeadValidityReviewIT`覆盖第4/5次和早期疑似无效重开，不重置序号。 |
| §2准确事件与复核因果 | 修改`execution/R1EventPolicy.java`、`api/CurrentWorkCardSources.java`及生产Review重验；回归`R1ContractClosureIT`、`R1EventPolicyTest`、`CurrentWorkCardCausalIT`，禁止旧第三次selector冒充第四次。 |
| §2Opportunity与WAITING | 原Task6的`opportunity/OpportunityOpeningService.java`及Owner持久化、`responsibility/WaitLifecycleService.java`与`WaitLifecycleIT`；两种单Task恢复均准确CAS，业务事件/回滚计数按规格§6。 |
| §3EvidenceOwner | 新`evidence/EvidenceReferenceReader.java`、`evidence/internal/persistence/JooqEvidenceReferenceReader.java`，只读两表；返回Submission hash与Binding revision及关系，拒绝写接口。必须独立实际QUERY角色IT及hash固定向量，不用回调假实现替代。 |
| §3授权与披露 | Lead主命令和API CurrentCard组合Evidence读口，复用同一资格和四Subject授权；新增证据专项IT，覆盖NOT_FOUND阶段delta、直接/委托/DENY/绑定撤回、200/304Audit/ETag，不改变纯Query依赖。 |
| §3模块/生成边界 | `ArchitectureTest.java`仅增加批准的三条边和Evidence Owner；`testing/JooqGenerationIT.java`只加两表（POJO总数21→23），运行`backend/scripts/generate-jooq.sh --write`生成，随后`--check`，不手写生成类型。 |
| §4–6边界与有限验收 | 原Task6完整实库/架构/事件回归，规格§6八组逐项映射；独立评审完成后才更新Task6本地业务进度。HTTP/Worker/SPA/E2E与容量仍缺，不提升整体门禁。 |

这里使用Java相对目录均以`backend/src/main/java/io/github/windyzhu3/ontologylaw/`为根，测试以对应`backend/src/test/java/io/github/windyzhu3/ontologylaw/`为根。接口的具体Java签名由原Task6预检在固定DAG内形成，不由合同任务虚构或抢先实现。

## 计划自审

- 覆盖：规格§5活动合同同步全部归本计划Task1；规格§2/3/6生产验收明确交接原Task6，不将“暂未实现”当作本计划验收缺失或完成证据。
- 原子性：只有一个可独立测试和评审的合同交付单元，没有单独未验证版本推进提交。
- 类型一致：新Python入口始终`validate(root: Path) -> list[str]`；注册表值、Task contactNo谓词与目标版本唯一。
- 范围：本计划不改Java生成源/架构白名单；后续原Task6是唯一生产实现Owner。没有Tasks7–10、上传平台、权限扩展或容量修改。
- 测试真实性：先断言RED再GREEN，真实CLI与临时Git单测环境分离；静态检查与生产失败测试不会混淆。
