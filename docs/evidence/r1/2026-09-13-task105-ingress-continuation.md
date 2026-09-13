# Task10.5：原线索补齐后的受控续验

> 已执行并停用：本次草稿已保存，累计18项CONFIRMED、0项PENDING；测试在刷新校验前后未推进，最终失败。下文17项检查点已过期，禁止重跑或替换摘要自行续跑。需先完成原因诊断及受控处理。

状态：独立评审及定点复审通过，可按本文单次续验。实现提交 `ee915e4`，恢复边界修复提交 `bfa4150`；最终16项定向TS、13项Python（Python未变，沿用本轮已验证结果）和严格类型检查通过。复审无未关闭的重要问题；尚未实际续验。

用户已批准只对原接入线索，通过sourceOwner工作台保存并提交合成联系信息，再继续同一线索的自动分配和首联。原17条成功命令不改、不重发；仅追加补齐草稿、补齐提交、首联草稿、首联提交，共4条。全链21条写入，其中管理16条。无新线索、无外呼或消息、无产品/权限/数据库结构变更。

此链标记 `CAPTURE_INGRESS_AUTOASSIGN_CONTACT_V1`，不能作为“接入即自动分配”场景通过的证据。W09仍按用户延期，U01～U03保持用户关闭；附件和通知属R2，语音后置。此链通过不代表R1整体验收通过。

## 固定检查点

- 环境：`task103-stdout-20260913`
- 操作：`ad68a0fa-ede3-431d-a99e-664dccfd7275`
- 原journal SHA256：`723BC89171A97A868FB953AC876E49E32CC2CE26D0DC28889A407B383A22D057`
- 状态：17项CONFIRMED、0项PENDING；已完成管理、销售授权、身份验证及接入。
- 原证据备份：同环境 `acceptance-ad68a0fa-ede3-431d-a99e-664dccfd7275-attempt03-capture-complete`，禁止覆盖。
- 上次临时CurrentUser CA删除退出0、最终查询NTE_NOT_FOUND，已由用户确认。

## 单次执行

使用专用、普通桌面PowerShell，完整复制代码块内部，不复制反引号。不提权，不改系统PATH，不改LocalMachine证书库；仅本进程补齐现有pwsh路径，并临时信任准确的本轮CA。任何失败停止，不换操作编号，不修改摘要自行重跑。

```powershell
& {
    $ErrorActionPreference = 'Stop'
    Set-Location -LiteralPath 'C:/Users/Jacob/.cache/codex-worktrees/ontology-law-r1-business'
    $taskPwshDirectory = 'C:/Users/Jacob/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell'
    if (-not (Test-Path -LiteralPath (Join-Path $taskPwshDirectory 'pwsh.exe'))) { throw 'PWSH_RUNTIME_MISSING' }
    $env:PATH = $taskPwshDirectory + ';' + $env:PATH
    $taskOperation = 'ad68a0fa-ede3-431d-a99e-664dccfd7275'
    $taskJournal = '.artifacts/r1-e2e/task103-stdout-20260913/acceptance-' + $taskOperation + '-journal.json'
    if ((Get-FileHash -LiteralPath $taskJournal -Algorithm SHA256).Hash -ne '723BC89171A97A868FB953AC876E49E32CC2CE26D0DC28889A407B383A22D057') { throw 'CHECKPOINT_CHANGED_DO_NOT_REPLAY' }
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

Windows导入或删除确认仅针对 `R1 E2E isolated CA`，指纹必须与脚本一致。结束后提供测试结果、临时CA删除退出码和最终证书查询结果；最终查询应为找不到对象。成功或失败均不要重复执行，关闭专用PowerShell丢弃进程环境。后续由Codex只读核验原journal、Receipt和完成Fact并保存本次证据。
