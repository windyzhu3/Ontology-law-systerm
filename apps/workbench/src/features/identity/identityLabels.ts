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

export const roleLabels: Record<S["AppointmentV1"]["roleCode"], string> = {
  INTAKE_OPERATOR: "线索接入经办",
  ROUTING_SUPERVISOR: "路由主管",
  CONTACT_OPERATOR: "首联经办",
  IDENTITY_ADMIN: "身份管理员",
};

export const authorityLabels: Record<S["AuthorityGrantV1"]["authorityCode"], string> = {
  LEAD_CAPTURE: "线索接入",
  LEAD_INGRESS_RESOLVE: "线索重复处置",
  LEAD_INGRESS_COMPLETE: "线索信息补全",
  LEAD_ASSIGN: "线索分配",
  LEAD_ROUTING_DECIDE: "路由处置",
  SOURCE_INTAKE_REQUEST_ACK: "来源接入请求确认",
  SALES_CONTACT_OWNER: "首联处置",
  LEAD_VALIDITY_REVIEW: "线索有效性复核",
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
