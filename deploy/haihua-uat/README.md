# 海华独立 MVP 测试环境

这是已确认计划的隔离测试工具与去秘密入口；结论见 [测试报告](../../docs/evidence/haihua-uat/HH-UAT-20261001-01/report.md)。业务默认入口为 `https://localhost:20544/workbench`，管理员入口为 `https://localhost:20544/admin/identity/principals`。

本机实际运行目录属于保留的执行工作树：

```powershell
Set-Location 'C:\Users\Jacob\.cache\codex-worktrees\ontology-law-r2-sales'
```

运行配置、凭据、检查点与原始证据在该工作树的 `.superpowers/haihua-uat-runtime`。即使源码合并到 main，也须从这个工作树操作已保留实例；主工作区的同名相对目录不是这个运行目录。

## 账号

销售一部：sales01、sales02、sales03，主管 sales_manager01。销售二部：sales04、sales05，主管 sales_manager02。财务 finance01，案管 case_admin01，系统管理员 sys_manager01。个人密码在本地 `browser-credentials.json`，只供本机用户读取；任职、组织和授权实际结果见公共证据目录。

## 启停

实例容器前缀为 `ontology-law-haihua-uat`；身份 20543、SPA 20544、API 20545、业务库 20546、扫描器 20547。既有 R2 演示/评审实例的 19443—19447 未被本轮工具接管。

当前原业务实例已运行，不需要再次初始化或重新建立账号。需要停止本轮应用进程时：

```powershell
D:/soft/python3/python.exe -X utf8 deploy/haihua-uat/apps.py stop worker api spa
```

确认本实例应用已经停止、依赖容器仍运行后，可启动保留制品：

```powershell
D:/soft/python3/python.exe -X utf8 deploy/haihua-uat/apps.py start api spa worker
```

工具按进程登记与实际可执行路径/运行目录核对所属；启动只说明创建进程，实际 READY/登录与 Worker 健康须另验。停止不删除数据库、材料、密钥、身份、证据或制品。

E1、G_COMPLETED、PERF_E1 派生实例当前停止且保留。原实例承载全部业务与负向记录；恢复与容量实例有各自目录和检查点。不要用恢复脚本覆盖原运行目录，也不要以删事实的方式重跑本轮。

## 工具边界

`prepare.py` 是本轮首次基础设施准备；`admin-setup.mjs` 是已执行的实际管理界面操作。二者不是日常启动命令。初始化、造数、业务办理和故障注入工具使用一次性案例或原键记录；现有未知/失败裁定应先核对原回执与事实，不按成功假设用新键重做。

日常只读定位可使用角色检查、概览、团队、材料读取与相应 `reconcile-*.py`。恢复、性能和新业务工具仅应对其明确记录的计划/实例使用；性能只在独立 PERF_E1 运行，不混入业务验收计数。密码、Token、私钥、原材料与配置保持在忽略目录。
