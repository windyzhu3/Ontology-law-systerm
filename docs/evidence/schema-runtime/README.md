# PostgreSQL Schema 运行证据

本目录目前没有可提交的 PostgreSQL 18 成功运行证据。

当前执行器没有 Docker/Compose。指定命令使用 `--runs 2` 发起了两次真实运行尝试，控制器如实返回 `status=BLOCKED`、`reason=docker_compose_unavailable` 和进程退出码 `5`；两次尝试都记录 PostgreSQL 启动返回码 `127`，并仍然执行清理阶段。被忽略的 `.artifacts/schema-runtime/` 只保存本次原始诊断，不会复制到本目录。

以下固定文件仅在同一个干净的 40 位 Git HEAD 上完成两次空库运行、run A no-op、五个失败关闭场景、实际镜像 RepoDigest 与完整版本采集，并通过发布前规范化、合同校验和敏感信息扫描后才会成对产生：

- `2026-08-28-postgresql-18-summary.json`
- `2026-08-28-postgresql-18-report.md`

当前两文件均刻意不存在。`BLOCKED`、`FAILED`、不完整输入、Git/输入漂移或任一文件系统错误都不得发布其中任何一个文件。

空库 Flyway 门禁顺序保持为 `migrate` → strict `validate` → `info`。数据库合同 README 和验证记录继续保留“尚未在真实 PostgreSQL 执行”的准确结论，直到上述成功门禁真实完成。
