# Task9.4 浏览器会话与恢复阶段记录

日期：2026-09-09。当前状态：**Task9.4源码实施阶段已验收：生产入口装配`774a301`、正文超时修复`958e190`，最终222项及typecheck／build通过，独立完整评审及同席修复复审通过。没有部署配置，尚未完成真实Keycloak→SPA→API登录领卡验收。** 整体9.4起点`c4aed1401006749b52453ca537335f57a5953d85`；9.3后端已验收，不重做。以下新增“生产入口装配”是当前进度，其余组件章节为历史检查点，不以历史“尚未接线”描述当前源码。

## 生产入口装配（当前增量）

BASE`9dbe530`→源码`774a30181d6ceab2a2436c817665d1babb4105d7`。main已引用真实SessionApplication，单一稳定controller／RecoveryStore／API／provider串联已确认LOGIN、CHOICE、RECOVERY与原App；没有Mock认证或第二SPA。四个固定非秘密构建变量及准确回跳规则见[部署说明](../../deploy/identity/README.md)，缺失／非法配置和无法访问恢复存储时安全关闭。用户明确当前没有实际部署配置，不填样例值或代建真实身份。

本轮保持显式本人／合法代办选择、初始未决回执门禁与业务资格隔离。同Actor续期及临期提示不卸载App，不因当前写入刚保留标记就切走工作台；失效／换身份清除私有内容并隔离旧响应。原顶栏仅补紧凑身份、切换任职、退出，不增加管理导航。

多任职加损坏／过期标记的组合死锁已通过先RED再修复：已验证SELF但尚无Actor时，仅允许当前controller／epoch下双确认清理无效本地线索；替换为有效标记、上下文失效或存储失败仍保护，成功后回未选择的CHOICE。该动作不赋予Actor或读写业务权限。

### 当前证据

- 最终源码全量 **220/220、19文件、7.96秒**，typecheck、build、openapi:check均实际退出0；锁定Node24.20.0/npm11.9.0。日志`task94-assembly-final-{test,typecheck,build,openapi}-01.log`，不与历史197／196等相加。
- 生产构建首次包含真实会话入口和SDK，4580模块、JS323.12kB（gzip99.23kB）；本地实际构建入口缺配置时保持禁用LOGIN，console error/warn为空。构建包含SDK不等于完成真实协议链。
- Root实际基线／拓扑CLI退出0，`task94-assembly-root-{baseline,topology}-01.log`；7项既有非致命R2门禁仍阻断，不提升发布状态。
- 1440／768／360、44px顶栏按钮、键盘焦点、dirty续期、未决只查回执、损坏线索、无资格及退出失败文案通过受控浏览器抽检；详见[视觉QA](../../design-qa.md)。外部边界为合成响应，非真实身份服务。
- 独立装配增量评审通过、无阻塞项；完整9.4评审发现1项Important：SELF响应头已到但正文停滞时，正文读取未覆盖原10秒超时，可能长期停在初始化／任职选择。已由`958e19088db6c22c9b0ebf617a77a511bb5decb2`仅改controller／test修复：fetch、正文读取及解析共享原10秒预算，超时中止HTTP，迟到正文不能恢复身份。首次SELF和任职选择两项真实Response流RED（15通过／2失败）后，相关36项通过；最终**222/222、19文件、7.59秒**，typecheck/build退出0，JS323.17kB（gzip99.24kB）。日志`task94-full-review1-{red,green-01,final-test,final-typecheck,final-build}.log`。OpenAPI未改，原220源码的通过证据保留，不冒称修复后重跑。原评审者复核1/1关闭，无新增问题。

23类命令的证据边界：注册表／Fact回执策略／共享reserveWrite基础门覆盖23类；当前生产SPA实际具备七个Task及一个Draft写入路径，均过共享门。CAPTURE_LEAD和十四个管理写入没有在本次装成页面操作，不能声称23条生产页面路径均实测；9.5管理调用必须接入同一门。

完整评审修复复核：同一评审者确认I1已处理、无新增Critical／Important／Minor；首次SELF／任职选择共享原10秒预算，迟到正文不恢复身份，源码实施门关闭。完整差异覆盖13个实现／组件检查点提交；修复后源码`958e190`另作有界差异复审。该结论不等于本矩阵全部真实环境验收通过。

最终收尾实际基线／拓扑再次退出0：`task94-assembly-root-baseline-02.log`及`task94-assembly-root-topology-02.log`，仍是相同7项非致命R2门禁。三个冻结图SHA-256未变；`git diff --check`通过。所有源码／文档仅本地检查点，不推送、不部署。

### 后续边界

本轮不实施9.5四管理页面／完整工作台状态矩阵；9.6真实身份建档、登录领卡、七卡整链及人工UAT仍未完成。附件／通知保持R2，语音后置。没有账号／授权变更、部署或GitHub推送。本地源码检查点不是对外发布。

## RECOVERY-01历史增量

用户选择本轮恢复第2稿，BASE `c276900`→源码`b7dc793762439b6be98a536e1ff5f8263dbfdedc`。仅5个前端源码／测试／CSS文件；保留冻结左右分栏、Logo和律所名称占位，没有后端、合同、权限、迁移、依赖或生产配置变更。

复用真实SessionProvider／SessionController、useCurrentCard与现有Receipt transport。重登丢失正文后只查原回执，初始未决不读业务卡；未知／404／错误回执保持线索，不显示重放或制造新命令。确认结果与刷新失败分开；仅重试CURRENT GET。放弃须同页二次确认，只删除本地线索，不撤销原操作。过期／损坏线索也可明确确认后清理，删除实际生效但存储仍抛错时继续安全暂停；身份改变、迟到响应和标签页可见性变化有回归覆盖。

READY Actor快照供所有23类已批准命令的回执查询，Identity-only用户可查自己的管理回执，但不读无资格业务卡、不因此获得工作台权限。后端仍按原Actor和当前授权核对；无管理页面或权限扩展。

### 本轮验证及证据边界

- 起点177项／16文件通过（5.68秒，`task94-recovery-baseline-01.log`）；新增行为先RED，定向最终41项通过（`task94-recovery-green-03.log`）。
- 最终源码196项／17文件通过，5.85秒；typecheck、build、openapi生成漂移均实际退出0。日志为`task94-recovery-final-{test,typecheck,build,openapi}-02.log`，锁定Node24.20.0/npm11.9.0；不将起点和最终项数累加。
- 首轮Identity回执夹具错误使用digest而非revision，按原validator/schema修正，未放宽校验。首轮最终测试虽196通过，类型检查发现测试Storage接口不完整，补齐后重跑上述最终四项；保留真实失败记录，不称一次通过。
- Root实际基线及拓扑CLI均退出0：修复及文档收尾后最终`task94-recovery-root-baseline-02.log`、`task94-recovery-root-topology-02.log`，初轮01日志保留。仍有7项既有非致命R2阻断，不提升发布状态。
- 原图与实际页面同视口联合比较，修复默认态2px溢出；1487/1440/768/360、长名、键盘、确认／取消、查询中、已确认但刷新失败、401及过期线索抽检通过，console error/warn为空。截图及密度说明见[视觉QA](../../design-qa.md)。

增量独立首评发现1项Important：回执已确认后，当前责任读取在隐藏标签页时被中止，返回后loading未复位而无法重试。新增DOM/HTTP回归先真实失败，再由`5a8a9fe02cd4c55409cace299c56bcd9334fcb6b`两行窄修复恢复可重读状态，保留已确认结果及旧generation隔离，不自动追加请求。最终相关42项／3文件（4.30秒）和typecheck退出0，日志`task94-recovery-review1-green-final.log`及`task94-recovery-review1-typecheck-final.log`；同一评审者复审确认1/1 ADDRESSED，无新Critical／Important，规格与质量阻塞项闭合。此前196全量及build/openapi明确属于`b7dc793`，不冒称修复后又跑全量。

build仍44模块，生产main未引用会话页面，不能将构建成功写成真实登录链已通。预览外部边界为合成响应，未连接真实Keycloak/API，没有实际账号／授权变更。本轮仅本地源码提交，没有推送或部署。

下一步仍在Task9.4：装配固定非秘密部署配置、现有三类页面与工作台入口，验证真实controller接线、续期不丢dirty输入、失效清屏和重登恢复，再进行完整9.4评审。实际部署配置尚未提供，不填示例身份；9.5四管理页／完整摘要状态、9.6真实用户整链／人工UAT与Task10容量仍未完成。

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

## CHOICE-01实施增量（2026-09-09）

用户本轮选择第1稿左右分栏身份选择页，冻结原图及范围见[视觉记录](../design/session-access/README.md)。BASE`2c9988e81aaf74ac75972bf1f8ab15e3f842f92f`→源码`d95f54daaaea486aaec424508725cf8aec57facf`，仅5个前端源码／样式／测试文件。复用LOGIN品牌样式而未修改其字节，保留Logo及律所名称占位，无main/config/router/package/backend/contract变更。

已实现：本人任职明确建立、当前任职的合法代办显式选择、唯一最终确认、无候选说明、同步禁用、安全错误与重试、退出清屏、同页未决风险确认及取消、键盘焦点恢复和长名称全文。新增内部`prepareAppointment`仅区分中间确立本人任职与最终确认身份：中间步骤保留原代办未决标记以便选回原Actor；不从标记猜身份，不读取业务卡／回执，不新增字段、权限或合同。最终跨scope仍先确认“只删除本地线索，不撤销原操作”，存储失败安全拒绝。

本地预览使用真实组件／provider／controller，在OIDC与自身context HTTP外部边界使用合成数据；测试覆盖初始化门、无默认候选、当前候选、epoch及卸载／替换／退出后的迟到响应、存储失败和不发业务／回执请求。Root核对原始RED，包括中间选择误失去代办候选、失效后旧成功文案及焦点／全文缺失，修复后GREEN。全量**174/174、16文件、6.02秒**，typecheck/build/openapi通过，锁定Node24.20.0/npm11.9.0，原始`task94-choice-*-final-01.log`及同一实施报告CHOICE节记录过程。测试数字不与历史累加。

Root完成冻结稿与实际页面联合比较及1487／1440／768／360视口、无候选、明确选择、风险取消／确认、退出、键盘和长名抽检。已修复默认多余滚动、长名不可完整阅读、风险取消焦点丢失，最终无阻塞视觉问题，控制台error/warn为空；细微字宽及原生控件外观为P3余量。详见根目录`design-qa.md`。构建仍44模块，因为生产main未引用新组件；构建通过不能证明真实登录入口已上线。

Root最终实际基线／拓扑CLI均退出0（`task94-choice-root-baseline-final.log`及`task94-choice-root-topology-final.log`），仍原7项非致命R2阻断。本增量首轮独立评审Approved，0 Critical／0 Important、1 Minor：变更办理方式／代办候选后旧成功提示残留，确认门禁仍有效。Root在浏览器复现并交同一实施者最小修复，修复与复审证据另记；没有将Minor冒充阻断性权限问题。

下一步仍是确认完整恢复／异常画面并完成生产入口装配，再做完整Task9.4评审和真实Keycloak/API/SPA链；Task9.5管理页面／状态矩阵、9.6用户整链、Task10及R1不晋级。无实际账号／授权变更、推送、部署或容量验收。

### CHOICE评审提示修复

修复`95b9673521c805058fd1aa1d6cfb01422b6d9da3`仅在三个草稿变更处理处清除旧message，并增加本人→代办、代办→本人、更换代办候选三个真实组件回归。先观察3项旧成功提示断言失败／18项通过，再以chooser、controller、provider、LoginEntry相关**44/44、4文件、4.55秒**及typecheck通过，实际退出0。原始`task94-choice-review1-red.log`、`green-final.log`及`typecheck-final.log`保留；没有将早先174项全量结果冒称为该修复后的重跑结果。

Root浏览器同路径复测status为空、旧成功提示消失；CSS、默认画面和生产入口未改，既有视觉证据仍适用。同一评审者复审`d95f54d..95b9673`确认**1/1已关闭，无新增Critical／Important，无范围外观察**，原始报告`task-9.4-choice-review-01.md`及`02.md`在本地执行目录。CHOICE增量通过；该提示修复不改变身份／恢复准入或合同。基线／拓扑CLI已在修复前的本增量及文档上通过，未为三个提示清理调用重复无关后端／CLI测试。只有本地提交，无推送或部署，完整9.4仍未验收。
