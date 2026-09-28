# T07 第一批领域规则实现证据

2026-09-20。K2 已确认；本批不改变产品 UI，不部署服务，不修改既有数据。

完成：具名规则修订；QuotePackage 不可变报价输入、精确金额/折扣/条件费/摘要；QuoteReply 四态回复及后续责任类型、版本和时间校验。

TDD：QuotePackageTest 因缺失类编译失败后实现，再通过；QuoteReplyTest 同样先观察缺失类失败后实现。最终 Maven 命令：

`./mvnw.cmd -q -f backend/pom.xml -Dtest=QuotePackageTest,QuoteReplyTest,OpportunityProgressInputTest,CustomerRequirementDocumentTest -Dcodegen.skip=true test`

退出码 0，18 项测试全部通过：6 + 4 + 3 + 5。命令使用本机 JDK 25。基线 `python scripts/baseline/verify_baseline.py --r2-development .` 一致性和开发准入通过，旧 R1/R2 发布门禁状态未提升。

未完成：事务持久化、命令幂等/权限、报价文件、审批和全部后继待办、API、K2 页面接入、真实运行时与全流程验收。领域输入中的 UUID 存在性、租户归属、证据可用性必须由后续事务服务验证，不能只凭 UUID 当作权限或事实。T07-03/04/05/06/07 均不得据此标为完成。

独立只读审查发现中间累计金额边界造成折扣顺序依赖；已先以回归测试复现失败，再将安全范围校验移至最终合计，最终18项测试通过。摘要测试已扩展至商业条款及身份依据。补充极值转换、零报价、微秒精度、交付同刻回复的单独边界测试建议留待后续服务接入时增加；不据当前测试宣称全边界或运行时验收完成。
