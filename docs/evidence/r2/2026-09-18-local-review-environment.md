# R2 本机人工验收环境

2026-09-18 启动。用途为用户功能和页面确认，不授予 R2 正式发布许可；T05 仍待高保真确认。

- 地址：`https://localhost:19444/login`，仅本机回环监听。
- 当前 R2 工作树构建后端与 SPA 成功，独立数据库 `law_r2_review` 由旧合成库复制。原 `law_contract_runtime` 未迁移、未录入本次业务操作。
- 在副本执行 5 条后继迁移，Flyway 验证共 27 条迁移；Schema `52-plus-2-r2-v5`。
- 原 TLS CA/叶证书已过期，更新本机运行服务证书；旧私有证书文件及历史 release 保留。当前用户开发 CA 指纹 `D76D6617D24CA50F090D22D7E9D5DCBF8CFB93B7`，有效至 2026-10-18。未关闭 TLS 校验。
- API、SPA、Worker 使用本次构建，Worker 已记录 READY。INITIAL/DUE/OWNER_EXCEPTION 调度启用。
- `task9-local-contact` 在副本通过真实管理员 API 配置当前全部 15 项可授予业务权限，30 天期限。禁止自授规则保持；身份组织管理仍使用独立 `synthetic-founder`。
- 真实 Code+PKCE 登录通过；业务会话可进入工作台、商机台账和异常管理。录入来源、当前卡、商机列表接口均成功。
- 浏览器实际办理副本中的合成有效首联，生成商机并出现“记录商机实质进展”后续卡。留给用户继续办理，未代替用户做完整 UAT。

账号密码仅在受保护、Git 忽略的 `.superpowers/r2-review-runtime/验收账号.txt`。制品摘要、进程登记、运行日志、授权回执同目录，不纳入 Git。

入口：工作台 `/workbench`；商机 `/management/opportunities`；责任异常 `/management/team-tasks`；运营检查 `/management/team-tasks/operations`；身份组织 `/admin/identity/principals`（管理员账号）。

本次运行支持已实现的 R1 与 R2 T01–T04 页面和流程。T05 客户需求、后续报价合同及转案闭环尚未完整实现，不能通过账号权限补齐。未修改产品页面或冻结设计。

注意：旧 local-login 恢复脚本仍绑定过期证书和历史制品，不应用于本次运行。操作脚本位于 output/prepare-r2-review.py、output/r2-review-account.py、output/r2-review-server.mjs；其中 prepare 和授权阶段有一次性状态，不能无条件重跑。恢复时应核对私有 processes.json 中的 PID/命令及端口，保留验收库后仅重启停止的对应进程。


## 2026-09-19 T05 更新

以上保留首次启动历史。当前已升级 T01–T05 / `52-plus-2-r2-v6`，Flyway V920 验证通过，保留升级前数据库备份。业务账号新增两项 T05 权限，期限仍为 30 天；原密码不变。真实销售链路及二次资料确认已通过，详见[T05 验收记录](2026-09-19-t05-implementation.md)。入口、证书校验和原 R1 库保持；本机 SPA 也支持线索录入页面直接刷新。恢复脚本增加 `output/activate-t05-review.py`，执行前仍须核对当前制品、进程及备份。
