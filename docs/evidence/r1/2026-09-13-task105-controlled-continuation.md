# Task10.5：原检查点受控续验

> **此版已停用。** 本次已追加销售授权并接入线索，累计17项确认，原日志摘要已变化。当前生成的是信息补齐责任，不是首联责任。不要重跑本文命令或修改摘要自行续跑；原成功数据保持不变，需先批准并评审新的最小衔接。

历史状态：实现 `ff34b15` 曾完成独立评审，13项离线TS、9项Python及严格类型检查通过。该版实际续验已执行，停在接入完成后的信息补齐责任；下文仅保留历史操作记录，禁止再次执行。新的补齐衔接已获用户批准，实施与评审尚未完成。本文不授权重建或重跑已成功命令。

用户已批准仅向本轮合成sales任职追加一条DIRECT `SALES_CONTACT_OWNER`，范围为原 `OWNED_ROOT`。此前15条管理命令与MANAGEMENT_COMPLETED历史阶段原样保留；后继单独确认销售授权，再验证身份、接入一条合成线索、保存/刷新草稿、提交有效接通并核对原Receipt/完成Fact。无ROOT级销售授权、无IdP账号创建、无产品/权限模型变更。

## 固定检查点

- 环境：`task103-stdout-20260913`
- 操作：`ad68a0fa-ede3-431d-a99e-664dccfd7275`
- 原journal SHA256：`989CB8CA1B6C6B2E5660AF133470AFAC9F36051A67CCDCA63855EC2AA658A270`
- 原环境摘要：`1c75d98e5db82273ba8bb2b2ab0bfbc42a7a80f66f2eae011375f22273d32515`
- 已确认：15命令，无PENDING；只读数据库对应ACTIVE主体3/组织2/任职3/授权7。
- 原journal及失败结果备份：当前隔离环境 `acceptance-ad68a0fa-ede3-431d-a99e-664dccfd7275-attempt02-management-complete`，不得覆盖。

## 启动约束

独立评审已确认规格和质量通过、无新Critical/Important。使用能看到本轮CurrentUser CA的普通桌面PowerShell，进程PATH前置现有固定pwsh运行时；严格TLS，准确CA临时恢复和结束后清理；不提权、不改系统PATH/LocalMachine。新增授权通过原founder和现有管理页面，不由SQL写入。

启动时核对上述原journal摘要，显式指定同一 `R1_ISOLATED_CONTINUE_OPERATION_ID`，禁止换编号。源程序必须验证原身份/环境并拒绝PENDING或不兼容命令序列，原15项不重发。任何失败后立即停下，保留结果交由只读核验；不再次粘贴执行。19次累计写操作仅包含16管理+接入+草稿+首联提交。

本续验成功仅关闭黄金链具名项，不代表R1整体通过。其余真实分支/会话/代办/故障与参考容量缺口继续按矩阵和各自授权处理；W09延期、U01～U03用户关闭、R2排除保持不变。

## 单次续验命令

在专用普通PowerShell中只复制代码块内部整段，不要复制反引号。整个脚本块内任何前置失败都会停止，不会继续运行测试。命令已通过PowerShell语法解析，原journal摘要核对一致；源程序仍会在执行前重新验证环境。无需提供凭据。

```powershell
& {
    $ErrorActionPreference = 'Stop'
    Set-Location -LiteralPath 'C:/Users/Jacob/.cache/codex-worktrees/ontology-law-r1-business'
    $taskPwshDirectory = 'C:/Users/Jacob/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell'
    if (-not (Test-Path -LiteralPath (Join-Path $taskPwshDirectory 'pwsh.exe'))) { throw 'PWSH_RUNTIME_MISSING' }
    $env:PATH = $taskPwshDirectory + ';' + $env:PATH
    $taskOperation = 'ad68a0fa-ede3-431d-a99e-664dccfd7275'
    $taskJournal = '.artifacts/r1-e2e/task103-stdout-20260913/acceptance-' + $taskOperation + '-journal.json'
    if ((Get-FileHash -LiteralPath $taskJournal -Algorithm SHA256).Hash -ne '989CB8CA1B6C6B2E5660AF133470AFAC9F36051A67CCDCA63855EC2AA658A270') { throw 'CHECKPOINT_CHANGED_DO_NOT_REPLAY' }
    $taskThumb = '9AC8D2FA7A721BE1AE903A03F5847620F86643CC'
    $taskCa = Join-Path (Get-Location).Path '.artifacts/r1-e2e/task103-stdout-20260913/certs/ca.pem'
    if ((Get-FileHash -LiteralPath $taskCa -Algorithm SHA256).Hash -ne '44857749F05B222D70AF1F6933EBA1B97B363278F02AF0073943BA7121E40A5B') { throw 'CA_FILE_MISMATCH' }
    $env:R1_ISOLATED_ACCEPTANCE = 'APPROVED_SYNTHETIC_ONLY'
    $env:R1_E2E_RUN = 'task103-stdout-20260913'
    $env:R1_ISOLATED_OPERATION_ID = $taskOperation
    $env:R1_ISOLATED_CONTINUE_OPERATION_ID = $taskOperation
    $env:NODE_EXTRA_CA_CERTS = $taskCa
    $env:NODE_TLS_REJECT_UNAUTHORIZED = '1'
    $env:NO_COLOR = $null
    $env:FORCE_COLOR = '0'
    $taskExit = $null
    try {
        & certutil -user -store Root $taskThumb
        if ($LASTEXITCODE -ne 0) {
            & certutil -user -addstore Root $taskCa
            if ($LASTEXITCODE -ne 0) { throw 'CA_IMPORT_FAILED' }
        }
        & certutil -user -store Root $taskThumb
        if ($LASTEXITCODE -ne 0) { throw 'CA_NOT_VERIFIED' }
        & 'C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/node-v24.20.0-win-x64/node.exe' node_modules/@playwright/test/cli.js test --config e2e/r1-isolated.config.ts --project approved-r1-isolated
        $taskExit = $LASTEXITCODE
    } finally {
        & certutil -user -delstore Root $taskThumb
        Write-Output ('TEMPORARY_CA_DELETE_EXIT=' + $LASTEXITCODE)
    }
    Write-Output ('R1_GOLDEN_TEST_EXIT=' + $taskExit)
    & certutil -user -store Root $taskThumb
}
```

Windows如提示导入/删除，只确认准确的本轮R1 E2E isolated CA。末尾查询应找不到该指纹；若仍存在，不重复操作，直接提供结果。运行一次后无论结果如何都不要重跑本段，关闭专用PowerShell以丢弃进程环境变量。由Codex继续只读检查journal、原Receipt与完成Fact，成功前不标R1通过。
