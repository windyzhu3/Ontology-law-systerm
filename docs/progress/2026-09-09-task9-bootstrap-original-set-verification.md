# Task9 原 bootstrap 集合核验修正

## 范围

用户批准的9.6a前置窄修复：只核验原始 Tenant、根组织、HUMAN、管理员任职、四项管理 Grant 及原 Slot/Receipt/Audit，不要求后续整个租户保持首次数量。新增记录不使原结果失效，也不因此被认定合法。原事实／生命周期／版本／时间或闭包变化仍拒绝，不能修补、补授权或重建管理员。

合同与设计澄清提交 `a90d0c2`。不改变首次创建规则、候选完整性与用途校验、原 manifest 摘要、锁序、OpenAPI、表数、迁移或运行权限。Task9.5、完整9.6、人工UAT和R1发布不因此晋级。

## 已执行的本地原清单对照

保留此前本地合成租户、原 manifest／command／密钥与必要 SERVICE 记录；没有重新发行候选或执行初始化。只调用关闭HTTP的既有 `IdentityBootstrapCommand verify`。

| 检查 | 旧在线Jar中的离线入口 | 修复后的编译生产类 |
| --- | --- | --- |
| 实际核验退出码 | 2，原结果核验被拒绝 | 0，`VERIFIED_ORIGINAL` |
| plannedDelta | 拒绝，无成功结果 | `{}` |
| 前后54张业务／技术表内容摘要 | 全部相等 | 全部相等 |
| 原manifest、operator、部署和应用配置、21份秘密文件、原Jar摘要 | 全部相等 | 全部相等 |

修复版检查于2026-09-09 15:52:56（Asia/Shanghai）实际执行。使用JDK25及Maven测试报告列出的既有依赖、`backend/target/classes`生产类；明确排除test-classes，不注入测试Bean、不启动HTTP或Worker。数据库快照是只读事务内按稳定行顺序计算各表内容摘要，不输出行值或秘密。

这证明原有扩展后本地数据可以由修复版离线入口核验且零变化；**不是新API发布激活**。在线服务仍使用原Jar，发布记录、密钥、数据及服务均保留，未执行重打包／替换／推送。

## 回归与评审

新增记录8项先出现7个生产`BOOTSTRAP_ORIGINAL_STATE_CONFLICT`、原本正确隔离的其他命令闭包1项通过；修复后8项全通过，扩大到原始事实损坏及过期／IdP不可用场景后15项全通过。首轮错误客户端名导致的测试夹具失败不算功能RED。

最终受影响回归于15:53:40完成，实际退出0：Architecture／RuntimeRole／RuntimeWebRole／Candidate／Manifest共36项，IdentityAdminHttp10项、ProductionAssembly21项、原Bootstrap17项、新OriginalSet15项，共99项，零失败／错误／跳过。原损坏闭包测试改用候选准确到期时间与显式到期断言，不再依赖从测试开始固定等待。未运行全后端／全前端／最终E2E套件，不把99项描述为全产品总验收。

独立评审待归档；本修复门尚未据此提前标为完成。

原历史登录检查中的失败观察保留，后续以本记录的修复后证据关闭该限制；不把独立SQL verifier夹具作为真实管理页面开户或最终七卡E2E证据。
