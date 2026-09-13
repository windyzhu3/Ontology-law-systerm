# 本次本地登录证书

用户于2026-09-09明确批准，仅向Windows **CurrentUser Root** 添加本次开发CA。未修改LocalMachine、系统JDK信任库或hosts；应用使用自己的信任库，不关闭TLS校验。

| 用途 | SHA-1证书指纹（Windows定位用） |
| --- | --- |
| 当前用户受信任开发CA | `B71CA891CD8C8F4132CB00D873BF94DF8D516DD5` |
| localhost服务端 | `FBC3FE413E2176F1D36B3BD80D18AFCF18F7905B` |
| identity-db/business-db数据库 | `AE59667CE17B69316DB6BDAF90CE081733962B34` |

证书采用SHA-256签名；有效期截至约 **2026-09-16 14:18（北京时间）**，准确值在私有运行目录`certs/metadata.json`。到期后本地TLS将失败；不要用跳过验证继续运行。证书续期需明确操作，不自动轮换业务HMAC或bootstrap密钥。

私有材料位于工作树`.superpowers/sdd/2026-09-08-task9-real-user-access-plan/local-login-runtime/certs/`，已被Git忽略，ACL只允许当前用户与SYSTEM。CA私钥在CurrentUser My；CurrentUser Root中的CA仅含公钥。不得复制私钥、PFX密码或运行凭据到仓库、截图或日志。

## 撤销本次信任（用户决定停止使用时执行）

先按本目录README停止本次本地服务。以下PowerShell命令仅定位本次已记录的证书，**尚未执行**；不会影响其他CA。删除受信任CA后，本地浏览器将不再信任其签发的服务证书。

```powershell
Remove-Item -LiteralPath 'Cert:\CurrentUser\Root\B71CA891CD8C8F4132CB00D873BF94DF8D516DD5'
```

如果同时退役本次证书及Windows中的私钥，可继续执行以下精确删除命令；私钥删除不可恢复，除非另有备份。运行目录中的导出材料和数据库卷不会被这些命令删除。

```powershell
Remove-Item -LiteralPath 'Cert:\CurrentUser\My\FBC3FE413E2176F1D36B3BD80D18AFCF18F7905B' -DeleteKey
Remove-Item -LiteralPath 'Cert:\CurrentUser\My\AE59667CE17B69316DB6BDAF90CE081733962B34' -DeleteKey
Remove-Item -LiteralPath 'Cert:\CurrentUser\My\B71CA891CD8C8F4132CB00D873BF94DF8D516DD5' -DeleteKey
```

不自动删除运行目录或卷；保留原bootstrap清单与密钥，避免把清理误用成重新引导。
