# Task9 本地真实登录联通检查

本记录只覆盖用户要求的本地部署及真实登录先行检查，不代表Task9.5、全部Task9.6、人工UAT或R1容量验收完成。

本地检查点已通过独立评审及修复后复审：实现`3f92d0e`，防护修复`5e1be4b`；两项评审问题全部关闭，无新增问题。下述bootstrap扩展后验证限制明确保留为后续门槛。

## 部署范围

- 登录入口：`https://localhost:19444/login`；真实Keycloak issuer：`https://localhost:19443/realms/local-r1`。
- 同一正式API配置装配与Java制品，HTTPS19445；独立业务库通过loopback19446/TLS verify-full访问，身份库独立且不发布宿主端口。所有已发布端口只绑定127.0.0.1。
- 使用仓库锁定Keycloak26.7.3/PostgreSQL18镜像，不升级依赖。开发CA仅按用户确认加入Windows CurrentUser Root，不关闭TLS校验。[证书和移除说明](../../deploy/local-login/CERTIFICATES.md)。
- 合成管理员通过既有离线bootstrap的candidate→dry-run→核对→原manifest execute→verify建立，仅有四项固定身份管理授权。最小SERVICE身份及来源配置满足正式API启动校验，SERVICE业务授权为零；不建立业务责任卡，不运行Worker。
- 密钥、密码、原bootstrap清单、配置、运行日志、浏览器检查材料均保存在工作树被忽略且ACL限制为当前用户及SYSTEM的`.superpowers/sdd/2026-09-08-task9-real-user-access-plan/local-login-runtime/`，不得提交Git。

## 实际浏览器结果

使用正式SPA构建`index-Ck0GpFI7.js`，未注入Mock session/SELF。Codex内置浏览器及独立安装的Edge均使用正常证书验证。

| 检查 | 实际结果 |
| --- | --- |
| 点击登录→Keycloak | 真实Code流、S256、精确回跳；没有密码授权 |
| 合成管理员输入凭据→回跳 | 令牌交换200，SELF200，显示根组织身份管理员任职 |
| 明确确认本人身份 | 提示当前任职不能进入业务工作台，管理入口尚未开放；不伪装成零待办 |
| 整页刷新 | 回到登录入口，不复用旧页面Actor；再次登录通过真实SSO重新读取SELF并要求确认身份 |
| 退出 | 进入Keycloak退出确认，确认后回到登录；再次登录出现凭据表单，不自动恢复原会话 |
| 未映射合成账号 | 真实令牌交换200但SELF401；页面安全拒绝，不出现任职确认或业务卡 |

Edge自动检查的founder、unmapped两次进程均退出0；只输出状态码、固定路径和声明检查布尔值，不输出Token、密码、subject或授权码。未映射SELF请求在401响应后被前端取消，其`ERR_ABORTED`不被误报为业务成功。

截图保存在私有运行目录`output/playwright/`：`founder-identity-only.png`、`signed-out.png`、`edge-founder-confirmed.png`、`edge-unmapped-denied.png`。复核脚本和首次失败证据保存在同一SDD工作目录，不当作人工UAT。

## 本次发现及最小修复

1. 锁定Keycloak实际拒绝部署参数中的`dynamic-scopes`名称；改用该版本支持的`parameterized-scopes`，保留禁用策略，没有修改版本。
2. 首次真实浏览器登录在令牌交换成功后失败，API没有收到SELF请求。原生Edge调试确认`SessionController.loadContext`以Controller作为原生fetch的调用接收者，触发`TypeError: Illegal invocation`。默认fetch改用函数包装，增加严格接收者回归；未改变OIDC、身份、授权或视觉规则。
3. 本地代理的路径防护原先误检查回跳query中的编码issuer；已将路径检查与query分开，保留编码路径穿越拒绝测试。
4. 独立评审发现并修复两处本地工具问题：异常请求目标改为安全404，不终止服务；首次创建密钥与后续读取分离，保留任何初始化状态时整套密钥缺失也会失败关闭，不生成替代密钥。临时夹具回归Python4/4、Node2/2通过，实际异常请求404后登录页仍200，修复后再次真实浏览器复测通过。

曾调查`basic` scope是否缺失，实际导入配置及令牌声明证明并未缺失，故没有基于该假设修改模板或放宽令牌验证。

## 重启及已知限制

本地stop→resume实际执行成功，保留原数据库卷和配置，21份用途密钥的文件摘要比较全部不变。原bootstrap在执行后立即verify通过；但加入正式API必需的SERVICE身份后，再次verify返回`BOOTSTRAP_ORIGINAL_STATE_CONFLICT`。只读定位发现，现有验证器要求整个Tenant的Principal和Appointment总数各为1；增加SERVICE后各为2，即使原管理员未修改也会冲突。这不是重启丢失数据或密钥轮换。

此次未放宽验证器、重新创建管理员、替换原manifest或修补数据库。**原bootstrap的扩展后验证限制仍需后续单独处理**，不能把本地登录通过当作初始化恢复或全部身份管理验收通过。

最终锁定Node24.20.0/npm11.9.0（包括嵌套npm命令）下，Root实际运行前端223/223测试通过；基线一致性和拓扑检查通过，原有7项R2非阻断门槛未改变。早期本地命令的npm查找路径错误已纠正，不用失败命令冒充有效证据。

## 尚未完成的整体范围

- Task9.5：四类受控身份管理页面、完整工作台状态提示及其前后端写入路径验收。
- Task9.6其余项：真实管理操作建立业务人员及责任、七类卡片完整操作、续期/撤销/故障恢复组合测试、真实使用者UAT和最终全量独立验收。
- 原R1参考环境容量验收仍等待用户稍后提供环境；本机约8GiB Docker不能替代已批准的容量环境。
- 附件、通知继续放在R2；语音后置。此次没有实施这些内容，也没有推送远程仓库。

本地复现与停止方式见[本地部署说明](../../deploy/local-login/README.md)。本记录不授权创建真实账号或生产权限。
