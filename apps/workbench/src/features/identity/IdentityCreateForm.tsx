import { useEffect, useRef, useState } from "react";
import type { components } from "../../generated/api/schema";
import type { WorkbenchSession } from "../../lib/sessionTransport";
import { safeText } from "../workcard/contract";
import type { IdentityApi } from "./identityApi";
import type { IdentityCommand, IdentityCreatePage } from "./useIdentityCommand";
import { useIdentityOptions, useIdentityProviderUser } from "./useIdentityOptions";
import { IdentityField, IdentityOptionField } from "./IdentityFormFields";
import { authorityLabels, roleLabels } from "./identityLabels";

export function IdentityCreateForm({ page, session, api, command }: { page: IdentityCreatePage; session: WorkbenchSession; api: IdentityApi; command: IdentityCommand }) {
  const [name, setName] = useState(""), [code, setCode] = useState(""), [role, setRole] = useState(""), [authority, setAuthority] = useState(""), [start, setStart] = useState(""), [end, setEnd] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const provider = useIdentityProviderUser(session, api);
  const organization = useIdentityOptions(session, api, page, page === "PRINCIPALS" ? null : "ORGANIZATION");
  const person = useIdentityOptions(session, api, page, page === "APPOINTMENTS" ? "PRINCIPAL" : page === "AUTHORITY_GRANTS" ? "APPOINTMENT" : null);
  const form = useRef<HTMLFormElement>(null);
  useEffect(() => { form.current?.querySelector<HTMLInputElement | HTMLSelectElement>("input,select")?.focus(); }, []);
  const change = (set: (value: string) => void) => (value: string) => { set(value); command.setDirty(true); };
  const onDirty = () => command.setDirty(true);
  function submit() {
    const next: Record<string, string> = {};
    if ((page === "PRINCIPALS" || page === "ORGANIZATIONS") && !safeText(name.trim(), 200)) next.name = "请输入 1～200 个字符的安全显示名称。";
    if (page === "PRINCIPALS" && !provider.selected) next.provider = "请精确查询并选择当前有效候选。";
    if (page !== "PRINCIPALS" && !organization.selected) next.organization = "请选择本页获权组织。";
    if (page === "ORGANIZATIONS" && !/^[A-Z][A-Z0-9_]{0,63}$/.test(code)) next.code = "代码须以大写字母开头，仅含大写字母、数字、下划线，最多 64 位。";
    if (page === "APPOINTMENTS" || page === "AUTHORITY_GRANTS") {
      if (!person.selected) next.person = "请选择本页获权候选。";
      if (page === "APPOINTMENTS" && !person.roleCodes.includes(role as components["schemas"]["IdentityRoleCodeV1"])) next.role = "请选择服务端准许的岗位。";
      if (page === "AUTHORITY_GRANTS" && !person.authorityCodes.includes(authority as components["schemas"]["GrantableAuthorityCodeV1"])) next.authority = "请选择服务端准许的业务权限。";
      if (!start || !Number.isFinite(new Date(start).getTime())) next.start = "请输入有效的生效时间。";
      if (end && (!Number.isFinite(new Date(end).getTime()) || new Date(end).getTime() <= new Date(start).getTime())) next.end = "结束时间必须晚于生效时间。";
    }
    setErrors(next);
    if (Object.keys(next).length) return;
    if (page === "PRINCIPALS") command.submit({ commandType: "CREATE_IDENTITY_PRINCIPAL", body: { providerUserSelector: provider.selected, displayName: name.trim() } });
    if (page === "ORGANIZATIONS") command.submit({ commandType: "CREATE_ORGANIZATION_UNIT", body: { parentOrganizationId: organization.selected, code, displayName: name.trim() } });
    if (page === "APPOINTMENTS") command.submit({ commandType: "CREATE_APPOINTMENT", body: { principalId: person.selected, organizationId: organization.selected, roleCode: role as components["schemas"]["IdentityRoleCodeV1"], effectiveFrom: new Date(start).toISOString(), effectiveUntil: end ? new Date(end).toISOString() : null } });
    if (page === "AUTHORITY_GRANTS") command.submit({ commandType: "CREATE_AUTHORITY_GRANT", body: { appointmentId: person.selected, authorityCode: authority as components["schemas"]["GrantableAuthorityCodeV1"], scopeOrganizationId: organization.selected, validFrom: new Date(start).toISOString(), validUntil: end ? new Date(end).toISOString() : null } });
  }
  return <form ref={form} className="identity-edit-form" noValidate onSubmit={event => { event.preventDefault(); submit(); }}>
    <h2>{{ PRINCIPALS: "建立身份主体", ORGANIZATIONS: "建立组织", APPOINTMENTS: "建立任职", AUTHORITY_GRANTS: "建立直接授权" }[page]}</h2>
    <fieldset disabled={!command.canSubmit}>
      {page === "PRINCIPALS" && <>
        <IdentityField label="完整用户名" value={provider.search} onChange={change(provider.change)} />
        <button type="button" disabled={provider.loading || !safeText(provider.search.trim(), 200)} onClick={() => void provider.query()}>精确查询</button>
        {provider.loading && <p role="status">正在精确查询…</p>}{provider.message && <p role="status">{provider.message}</p>}
        <IdentityField label="身份提供方用户" value={provider.selected} onChange={change(provider.select)} error={errors.provider}><option value="">请选择精确匹配的用户</option>{provider.items.map(item => <option key={item.selector} value={item.selector}>{item.label}</option>)}</IdentityField>
      </>}
      {(page === "PRINCIPALS" || page === "ORGANIZATIONS") && <IdentityField label="显示名称" value={name} onChange={change(setName)} error={errors.name} />}
      {page === "ORGANIZATIONS" && <IdentityField label="组织代码" value={code} onChange={change(setCode)} error={errors.code} />}
      {(page === "APPOINTMENTS" || page === "AUTHORITY_GRANTS") && <IdentityOptionField label={page === "APPOINTMENTS" ? "身份主体" : "授权任职"} options={person} error={errors.person} onDirty={onDirty} />}
      {page !== "PRINCIPALS" && <IdentityOptionField label={page === "ORGANIZATIONS" ? "上级组织" : page === "APPOINTMENTS" ? "所属组织" : "组织范围"} options={organization} error={errors.organization} onDirty={onDirty} />}
      {page === "APPOINTMENTS" && <IdentityField label="岗位" value={person.roleCodes.includes(role as components["schemas"]["IdentityRoleCodeV1"]) ? role : ""} onChange={change(setRole)} error={errors.role}><option value="">请选择岗位</option>{person.roleCodes.map(value => <option key={value} value={value}>{roleLabels[value]}</option>)}</IdentityField>}
      {page === "AUTHORITY_GRANTS" && <IdentityField label="权限" value={person.authorityCodes.includes(authority as components["schemas"]["GrantableAuthorityCodeV1"]) ? authority : ""} onChange={change(setAuthority)} error={errors.authority}><option value="">请选择业务权限</option>{person.authorityCodes.map(value => <option key={value} value={value}>{authorityLabels[value]}</option>)}</IdentityField>}
      {(page === "APPOINTMENTS" || page === "AUTHORITY_GRANTS") && <>
        <p className="identity-form-help">本机时区：{Intl.DateTimeFormat().resolvedOptions().timeZone}。提交时转换为 UTC 时间。</p>
        <IdentityField label="生效时间" type="datetime-local" value={start} onChange={change(setStart)} error={errors.start} />
        <IdentityField label="结束时间（可留空）" type="datetime-local" value={end} onChange={change(setEnd)} error={errors.end} />
        <p className="identity-form-help">任期、授权有效期包含关系及组织范围由服务端最终验证。</p>
      </>}
    </fieldset>
    <div className="identity-form-actions"><button type="submit" className="identity-primary" disabled={!command.canSubmit}>确认创建</button><button type="button" disabled={command.locked} onClick={command.cancel}>取消</button></div>
  </form>;
}
