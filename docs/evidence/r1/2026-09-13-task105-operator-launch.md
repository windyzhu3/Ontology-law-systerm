# Task10.5：普通桌面 PowerShell 单次启动

> **已停止使用：第二次执行已完成15项管理写入。不要再次运行本文启动命令。** 原journal已存在且15项全部CONFIRMED，当前停在身份核验。后续只能在定位及评审后按原操作编号显式续验，不能换编号或重建管理数据。本文保留作为历史启动记录。

仅用于已经批准的 `task103-stdout-20260913` 隔离合成验收。不是新测试实现，不改变产品或权限。当前自动化进程不能看到桌面已经安装的准确 CA，而用户普通 PowerShell 可以；不通过提权、关闭 TLS 或重复安装解决。

首轮桌面执行失败已保留：测试退出1，未创建业务journal；原始错误上下文和last-run文件已按SHA256原样复制到当前隔离环境的 `acceptance-ad68a0fa-ede3-431d-a99e-664dccfd7275-attempt01-preflight-failure`，仅Jacob/SYSTEM可访问。临时CA删除后，用户独立certutil查询确认不存在。末尾复制Markdown反引号产生的命令错误发生在测试结束后，不是测试失败原因。

定位到启动依赖遗漏：系统及用户PATH均不含pwsh.exe，普通PATH下只读复现环境核验在 `_verify_private_boundary` 启动pwsh时抛FileNotFoundError/WinError2；不涉及业务命令。下面只在专用进程PATH前置现有固定PowerShell运行时，不安装软件、不修改系统PATH、不提权。首次业务journal仍不存在，原编号继续保留；不是对已写入命令的重放。

修订后只读检查已通过（76e138）：只增加进程PATH中的固定pwsh目录，环境摘要仍为 `1c75d98e5db82273ba8bb2b2ab0bfbc42a7a80f66f2eae011375f22273d32515`；该检查没有执行业务命令。

## 执行一次

在新打开的**普通、非管理员** PowerShell 中复制以下代码块内部的命令，**不要复制三个反引号**。仅在Codex确认修订后只读前置检查通过后执行一次；无论成功失败，都不要重跑、改编号或开启 continuation。无需输入账号、密码或令牌。浏览器无头运行，输出经过既有程序脱敏。

范围固定：15次管理命令（3主体、2组织、3任职、7授权），然后1条合成线索接入、1次页面草稿保存及1次有效接通主提交。Keycloak账号已经存在，不创建账号。无旧业务重放、故障注入、历史候选或代办关系写入。

```powershell
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath 'C:/Users/Jacob/.cache/codex-worktrees/ontology-law-r1-business'
$taskPwshDirectory = 'C:/Users/Jacob/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell'
if (-not (Test-Path -LiteralPath (Join-Path $taskPwshDirectory 'pwsh.exe'))) { throw 'PWSH_RUNTIME_MISSING' }
$env:PATH = $taskPwshDirectory + ';' + $env:PATH
$taskThumb = '9AC8D2FA7A721BE1AE903A03F5847620F86643CC'
$taskCa = Join-Path (Get-Location).Path '.artifacts/r1-e2e/task103-stdout-20260913/certs/ca.pem'
if ((Get-FileHash -LiteralPath $taskCa -Algorithm SHA256).Hash -ne '44857749F05B222D70AF1F6933EBA1B97B363278F02AF0073943BA7121E40A5B') { throw 'CA_FILE_MISMATCH' }
if (Test-Path -LiteralPath '.artifacts/r1-e2e/task103-stdout-20260913/acceptance-ad68a0fa-ede3-431d-a99e-664dccfd7275-journal.json') { throw 'EXISTING_OPERATION_DO_NOT_REPLAY' }
$env:R1_ISOLATED_ACCEPTANCE = 'APPROVED_SYNTHETIC_ONLY'
$env:R1_E2E_RUN = 'task103-stdout-20260913'
$env:R1_ISOLATED_OPERATION_ID = 'ad68a0fa-ede3-431d-a99e-664dccfd7275'
$env:NODE_EXTRA_CA_CERTS = $taskCa
$env:NODE_TLS_REJECT_UNAUTHORIZED = '1'
$env:NO_COLOR = $null
$env:FORCE_COLOR = '0'
$taskExit = $null
try {
    # The prior attempt removed this CA. Restore only this approved temporary trust.
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
    # Only remove this run's explicitly approved temporary CurrentUser CA.
    & certutil -user -delstore Root $taskThumb
    Write-Output ('TEMPORARY_CA_DELETE_EXIT=' + $LASTEXITCODE)
}
Write-Output ('R1_GOLDEN_TEST_EXIT=' + $taskExit)
& certutil -user -store Root $taskThumb
```

若Windows要求确认导入或删除根证书，只确认本轮 `R1 E2E isolated CA`，不要操作旧 `Ontology Law Local Login Dev CA`。导入只恢复已批准且上一轮已移除的准确证书，仍不写LocalMachine。最后查询应找不到本轮准确指纹；删除命令退出0本身不替代最后查询。若仍能找到证书，保留输出交给Codex，不再重复删除。

执行结束后把终端结果告知Codex，然后关闭这个专用PowerShell窗口（上述环境变量仅属于该进程）。不要发送运行目录里的密码、JWT、私钥或原始敏感日志。

Codex后续只读核对原journal的确认阶段/PENDING、准确原回执和完成Fact。测试失败或有未知提交时停止新写入，不能换操作编号重试。成功也仅关闭黄金链具名项，不代表R1整体通过；其他矩阵缺口、W09延期及外部容量条件保持原状态。
