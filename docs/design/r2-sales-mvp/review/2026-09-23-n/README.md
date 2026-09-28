# N · 人工签署证据、授权核验与归档

Design-Status: APPROVED

本包承接 M 已实现的签署准备边界，按用户“缺失高保真先确认”的要求补齐下一任务页面。历史 T09 签署部分尚未实现；上一轮 T09 名称用于合同生成完整性补齐。本次使用 R2-SIGN 作为任务引用，详见 [任务设计与拆分](../../../../superpowers/specs/2026-09-23-r2-manual-signature-design.md)。

[打开 N 审阅页](index.html)。范围包含签署安排、人工签字件、授权核验、同版本补证、内容变化退回、部分完成、归档及后续交接。电子签、到账确认、合同执行条件判断、转案和建案不在本包范围。

## 与当前产品样式一致

审阅服务不公开 apps 源码路径，因此将四个现有产品 CSS 按原字节复制到本包，不编写替代规则：workbench.css、ledgerMaster.css、workbenchControls.css、contractControls.css。来源及 SHA-256 记录于 shared-style-sources.json，可直接比对。

工作台头部、刷新/我的待办控件、双栏工作卡、复选框与合同台账导航按现有实现映射；按钮间距和高度来自现有公用规则。正式实现仍调用现有组件，不能复制本稿模拟导航与静态数据。审阅容器 index.html 的工具栏只用于设计查看，不属于产品页面。

## 审阅重点

1. 场景 01—05：按准确批准版本准备签署材料，提交后等待有权核验人；上传不代表签署完成。
2. 场景 06—08：同版本补证与正文/主体变化分开；部分签署完成仍有下一责任。核验人的处理结果不会切换为销售操作权限。
3. 场景 09—10：全部必要签署和归档完成后交接执行条件核验，不显示已经到账、执行或建案。
4. 场景 11—15：原结果未知、无负责人、权限变化、台账入口和只读记录。

## 验证与限制

45 个布局检查（15 场景 × 1440/390/360）、8 个交互检查通过；无横向溢出、每张工作卡最多一个主操作，无页面脚本或资源错误。检查数据见 design-qa.json；已查看桌面核验及窄屏准备截图，截图位于 output/playwright/r2-sign-n。

所有角色、文件名、客户和状态均为合成展示。文件选择只用于原型校验，未上传或持久化；场景切换会重置。没有真实认证、扫描、签署或数据库操作，不能把原型结果作为业务验收。R1 PAUSED / R2 NOT_GRANTED 保持。本轮未修改产品代码或运行系统。

2026-09-23: User approved N and the manual-signature scope. This is original R2 T09 signature work (R2-SIGN); design approved, implementation and business acceptance pending. Prior T09-named generation completion is not signature completion.
