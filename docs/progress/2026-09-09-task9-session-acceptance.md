# Task9.4 浏览器会话与恢复阶段记录

日期：2026-09-09。状态：**非视觉检查点与LOGIN-01组件已实现；整体未阶段验收，任职／代办选择及恢复视觉待确认，生产入口未装配**。起点`c4aed1401006749b52453ca537335f57a5953d85`，非视觉提交`534e2ecb61144e60536efc1188b177b53a56c2f5`，登录增量`73aeef4d69d48763b5fb15d8b5b1e8b846382fed`；9.3后端已验收，不重做。用户要求继续9.4、保持设计范围及功能完整性。

## 交付范围

依据[批准计划](../superpowers/plans/2026-09-08-task9-real-user-access-plan.md)、[设计§4与§7](../superpowers/specs/2026-09-08-task9-real-user-access-design.md)和[合法代办补充](../superpowers/specs/2026-09-08-task9-delegated-context-amendment-design.md)。仅登录／回跳、内存会话、本人及合法代办选择、续期／退出／失效，以及四字段未决操作恢复；复用当前服务端context、业务卡和原回执接口。

不实现Task9.5四管理页面或完整工作台状态矩阵，不修改后端权限、业务合同、旧迁移、表或业务流程；附件／通知仍R2、语音后置。无实际人员账号／权限变更、GitHub推送、生产部署或容量验收。

## 执行与门禁

1. 已确认现有隔离工作区及干净起点；原75项前端测试通过，实际退出0（20.25秒）。后续复核发现本次启动所用Node附带npm为11.19.0，非项目锁定11.9.0；原结果保留为实际基线，不是锁定工具链最终验收。已定位并执行确认单独缓存的11.9.0，最终稳定测试／构建须使用该准确版本，不升级项目依赖来迁就工具。
2. 先实施不依赖新视觉的会话控制、OIDC适配、当前凭据transport、稳定身份epoch和恢复写入保护；按行为先RED再GREEN。原hook把token变更视为身份切换的逻辑须由新回归覆盖。
3. 设计§7要求新画面先确认视觉。2026-09-09用户选择登录入口三稿中的第3稿，已保存[原图和确认边界](../design/session-access/README.md)：左右分区，保留Logo／律所名称占位。该登录默认态视觉门已通过；本人任职／代办选择新画面仍待确认，不能将登录选稿扩展成全部会话UI批准。生产main不得接成测试身份，9.4整体仍未完成。
4. 最终稳定源码须运行原测试与新增测试、typecheck、build、生成漂移检查及实际基线／拓扑；独立评审问题闭合后才验收。DOM受控OIDC adapter证据不得冒充9.6真实浏览器／管理API建档／人工UAT。

## 验收追踪

本轮代码包含固定Keycloak/OIDC适配、内存会话及单飞续期、稳定身份epoch与当前凭据transport、本人／代办context选择逻辑、退出及跨标签失效、精确四字段恢复标记与共享未决写保护。选择逻辑已写，不等于选择页面已交付；`SessionProvider`与既有首联卡的受控DOM集成已验证，不等于真实浏览器入口已接线。

| 组 | 本轮可执行证据 | 尚待门禁 |
|---|---|---|
| L01、L06 | 固定配置、PKCE回跳处理、实际context接线、刷新重新建立身份；第3稿登录组件及真实控制器调用接点已实现并抽检 | 其余新画面确认、生产入口装配及浏览器协议链；9.6最终同构建重验 |
| L04、D08 | 无任职／多任职显式选择、本人／代办key比对，未选完保留线索且停写 | 选择画面确认及真实浏览器复验 |
| L07 | 首联输入dirty文字→真实controller触发同身份token更新→不重挂App→保留文本／未保存／禁提交，再保存确认只写一次且携新Bearer；受控adapter的DOM回归已通过 | 生产会话入口接线及真实浏览器复验，不能用DOM测试替代 |
| L08～10 | 单飞续期、交互idle／absolute、退出／跨标签信号、故障分类与迟到响应隔离 | 定向生命周期／并发及浏览器复验 |
| L11～12 | 精确四字段、24小时、存储失败不派发、全SPA未决写保护、同scope原回执、无正文禁重放 | 定向恢复／错误键策略测试及9.6整链 |

自动等待刷新30秒、每mounted identity epoch最多6次；回执自动查询最多3次且可见性门控。续期不重置额度，轮询不算真实用户活动。恢复标记不是授权，不按内容猜身份；401、超时、断网、GET404或标记到期均不证明原写未提交。

## 本地证据位置

`.superpowers/sdd/2026-09-08-task9-real-user-access-plan/`：`task94-baseline-frontend-01.log`、`task-9.4-brief.md`、`task-9.4-context.md`、`task-9.4-adapter-preflight.md`及`task-9.4-report.md`。实施报告逐项记录命令、退出码、RED/GREEN和剩余门禁；完整9.4评审报告尚未产生。该执行目录不随Git提交。

官方适配器接线核对采用[Keycloak JavaScript文档](https://www.keycloak.org/securing-apps/javascript-adapter)及[锁定26.2.4源代码](https://github.com/keycloak/keycloak-js/blob/26.2.4/lib/keycloak.js)；依赖版本仍按仓库锁定，不因本轮研究升级。源级核对不代表实际浏览器验收或不存在安全公告。

## 检查点验证

最终源码前端全量145项／13文件通过（`task94-full-frontend-final-02.log`，3.01秒）；类型检查`task94-typecheck-final-03.log`、构建`task94-build-final-02.log`及生成漂移`task94-openapi-final-02.log`通过。最终使用Node24.20.0和独立缓存的npm11.9.0；依赖仅接入批准的`keycloak-js`26.2.4及对应根锁记录，没有升级其余依赖。原75项与最终145项不累加为220项。

初轮144项通过后类型检查发现`auth_time`可空值问题，修正并补充SDK异常清理回归后才运行上述最终145项。此前失败轮和早期npm11.19.0执行记录保留，不冒充全程一次通过。关键DOM覆盖同身份续期保留未保存输入、重新确认携新Bearer且只写一次，以及未知提交重登后仅查原回执。

SDK清理首轮诊断中的一个无效、未签名合成测试Token值已脱敏，该日志不是原始验收证据；改用不输出凭据值的布尔断言后，`task94-sdk-cleanup-red-02.log`保留未经编辑的真实RED，最终145项GREEN覆盖修复。没有使用真实用户凭据。

Root独立运行实际基线与拓扑CLI，并在阶段文档更新后再次验证，均退出0：最终`task94-root-baseline-02.log`、`task94-root-topology-02.log`，初轮01日志保留。仍有7项既有非致命R2准入阻断；没有提升R2或R1交付状态。Git变更核对未触及后端、合同或部署配置；本轮没有重复Java全量测试，前端结果不能替代9.6最终同构建整链验证。

## 尚未交付的部分

LOGIN-01组件与真实会话调用接点已实现；本人任职／合法代办选择及完整会话恢复页尚无用户确认视觉稿，未实施。生产`main.tsx`仍使用原先无登录会话的`App`入口，新`SessionProvider`尚未装配到生产入口。因此当前不能称为真实用户已经能从浏览器登录领卡，也不能把组件测试或构建通过等同于OIDC入口已打通。上述145项保留为非视觉检查点证据，最新登录增量为下述153项，不累加。

完整Task9.4独立评审须待视觉确认、新页面及生产接线完成后进行。本轮非视觉检查点不是阶段验收；9.5四管理页面／完整状态提示、9.6真实用户整链和人工UAT、Task10及R1整体均不晋级。

## LOGIN-01实施增量（2026-09-09）

BASE`cc33676`→源码`73aeef4`，仅六个前端源码／测试文件，无依赖、合同、后端或部署配置变更。复用冻结颜色、字体及Phosphor，保留Logo／律所名称占位；唯一登录按钮、同步防重复、跳转中禁用、固定中文失败和重试。`LoginEntry`使用真实`SessionProvider`及controller；缺controller安全拒绝，READY／SELECTING不擅自导航或选择身份。

新增真实生命周期回归先观察到卸载后迟到的登录拒绝将SIGNED_OUT改为UNAVAILABLE，再以既有generation隔离修复。页面4项、入口4项和控制器14项定向22项通过；最终全量**153/153、15文件**（4.69秒），typecheck/build/openapi各退出0，均锁定Node24.20.0/npm11.9.0。原始日志`task94-login-*-final-01.log`及RED/GREEN见本地执行目录；build仍44模块，因为新组件尚未被生产main引用，不能当作部署入口证明。

Root以真实组件的忽略预览检查四个视口、键盘和跳转中状态，长名称仅在隔离验收夹具替换文字；无横向溢出，视觉结论仅适用于LOGIN-01。预览不连接身份服务。独立增量评审结论另记，完整9.4评审仍待后续装配。

本增量Root实际基线与拓扑CLI均退出0（`task94-login-root-baseline-01.log`、`task94-login-root-topology-01.log`），仍有原7项非致命R2准入阻断。未重复无关Java或Python全量测试，未修改准入行状态。

### 独立评审修复

初评发现1项Important：首次DOM提交时controller初始SIGNED_OUT被放行，而provider的初始化尚未由被动effect启动。新增父layout-effect真实点击测试复现“按钮可用、login=1、initialize=0”；修复`4ebc485`在provider内增加仅内存、绑定controller对象的就绪信号，attach及初始化同步启动后才允许登录，替换controller不能继承前一实例就绪状态。没有新增业务会话字段、持久化内容或视觉画面。

修复后的LoginEntry、LoginPage、SessionProvider及sessionController定向**26/26、4文件通过**（3.45秒），typecheck退出0。首次153项全量结果绑定`73aeef4`，修复覆盖绑定`4ebc485`，不累加为179项或声称修复后又跑了全量。未重跑build/openapi：修复未触及生产入口、CSS、打包配置或接口，生产main仍不引用新入口。

同一独立评审者仅复核`73aeef4..4ebc485`，结论为**1/1问题已关闭，无新增Critical／Important，无剩余Minor或范围外发现**。本地证据`task-9.4-login-review-01.md`／`02.md`。修复与文档更新后Root实际基线、拓扑再次退出0（`task94-login-root-baseline-final.log`、`task94-login-root-topology-final.log`），仍原7项非致命R2阻断。LOGIN-01增量通过，完整Task9.4及真实登录领卡仍未验收；只有本地提交，无推送或部署。
