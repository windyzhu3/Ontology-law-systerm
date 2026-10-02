import type { components } from "../../generated/api/schema";

type S = components["schemas"];

export const principalStateLabels: Record<S["IdentityPrincipalV1"]["state"], string> = {
  ACTIVE: "正常使用",
  SUSPENDED: "已暂停",
  DISABLED: "已禁用",
};

export const organizationStateLabels: Record<S["OrganizationUnitV1"]["state"], string> = {
  ACTIVE: "正常使用",
  CLOSED: "已关闭",
};

export const appointmentStateLabels: Record<S["AppointmentV1"]["state"], string> = {
  ACTIVE: "正常任职",
  SUSPENDED: "已暂停",
  ENDED: "已结束",
};

export const authorityStateLabels: Record<S["AuthorityGrantV1"]["state"], string> = {
  ACTIVE: "未撤销",
  REVOKED: "已撤销",
};


export const authorityLabels: Record<S["AuthorityGrantV1"]["authorityCode"] | S["GrantableAuthorityCodeV1"] | "QUOTE_READ" | "QUOTE_PREPARE" | "QUOTE_APPROVE" | "QUOTE_SELF_AUTHORIZE" | "QUOTE_DELIVER" | "QUOTE_RESPONSE", string> = {
  CONTRACT_TERMINATION_REVIEW:'终止签约请求核对', PAYMENT_SUBMIT:'收款凭证提交与补正', PAYMENT_CONFIRM:'逐笔到账核对', TRANSFER_SUBMIT:'转案资料提交与补正', TRANSFER_REVIEW:'转案前独立冲突审查', TRANSFER_ACCEPT:'案管审核接收', MATTER_CLASSIFY:'案件分类及承接确认', MATTER_RECEIVE:'案件承接',
  LEAD_MANAGEMENT_READ:'客户线索与来源查询', TEAM_TASK_READ:'团队待办与处理记录查询', PAYMENT_LEDGER_READ:'收款管理查询', TRANSFER_LEDGER_READ:'转案与案件管理查询', CONTRACT_READ:'合同查看', CONTRACT_PREPARE:'合同准备', CONTRACT_PREPARATION_DECIDE:'直接合同准备授权', CONTRACT_REVIEW:'签约前合同审查', CONTRACT_SIGNATURE_VERIFY: '合同签署核验与归档', CONTRACT_EXECUTION_VERIFY: '合同执行条件核验', CONTRACT_APPROVE:'合同审批',
  QUOTE_READ: '报价查看',
  QUOTE_PREPARE: '收费方案与报价准备',
  QUOTE_APPROVE: '报价审批',
  QUOTE_SELF_AUTHORIZE: '本版权限内报价授权',
  QUOTE_DELIVER: '人工报价交付留证',
  QUOTE_RESPONSE: '客户报价回复记录',
  MATERIALS_READ: "业务材料查看",
  MATERIALS_MANAGE: "业务材料接收",
  CUSTOMER_REQUIREMENTS_MANAGE: "客户与需求确认",
  PARTY_PROFILE_MANAGE: "共用主体资料维护",
  LEAD_CAPTURE: "线索接入",
  LEAD_INGRESS_RESOLVE: "线索重复处置",
  LEAD_INGRESS_COMPLETE: "线索信息补全",
  LEAD_ASSIGN: "线索分配",
  LEAD_ROUTING_DECIDE: "路由处置",
  SOURCE_INTAKE_REQUEST_ACK: "来源接入请求确认",
  SALES_CONTACT_OWNER: "首联处置",
  LEAD_VALIDITY_REVIEW: "线索有效性复核",
  OPPORTUNITY_CLOSE: "结束商机",
  OPPORTUNITY_LEDGER_READ: "商机台账查看",
  SALES_OPPORTUNITY_OWNER: "商机跟进办理",
  OPPORTUNITY_OWNER_EXCEPTION_DISCOVER: "负责人异常发现（服务）",
  OPPORTUNITY_OWNER_EXCEPTION_READ: "负责人异常查看",
  OPPORTUNITY_OWNER_EXCEPTION_RESOLVE: "负责人异常处置",
  OPPORTUNITY_OWNER_EXCEPTION_OPERATIONS_READ: "负责人异常运营摘要查看",
  IDENTITY_PRINCIPAL_MANAGE: "身份主体管理",
  IDENTITY_ORGANIZATION_MANAGE: "组织管理",
  IDENTITY_APPOINTMENT_MANAGE: "任职管理",
  IDENTITY_AUTHORITY_MANAGE: "直接授权管理",
};

export function formatIdentityInstant(value: string): string {
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(new Date(value));
}

export function formatIdentityDate(value: string): string {
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date(value));
}

