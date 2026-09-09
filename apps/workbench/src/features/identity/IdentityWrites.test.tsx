import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { expect, it } from "vitest";
import { IdentityAdminApplication } from "./IdentityAdminApplication";
import type { IdentityAdminRoute } from "./identityRoutes";
import { fixture, rows, tag } from "./identityWriteFixtures";
import { taskId, selectorId, draftId } from "../../test/fixtures";

export function mount(f: ReturnType<typeof fixture>, path: IdentityAdminRoute) {
  return render(<IdentityAdminApplication session={f.session} api={f.api} path={path} onNavigate={() => {}} sessionActions={null} />);
}
const change = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });
const lifecycle: Array<[IdentityAdminRoute, string, string, string, string]> = [
  ["/admin/identity/principals", "ACTIVE", "暂停使用", "暂停", "suspend"],
  ["/admin/identity/principals", "SUSPENDED", "恢复使用", "恢复", "resume"],
  ["/admin/identity/principals", "ACTIVE", "禁用身份", "禁用", "disable"],
  ["/admin/identity/organizations", "ACTIVE", "关闭组织", "关闭", "close"],
  ["/admin/identity/appointments", "ACTIVE", "暂停任职", "暂停", "suspend"],
  ["/admin/identity/appointments", "SUSPENDED", "恢复任职", "恢复", "resume"],
  ["/admin/identity/appointments", "ACTIVE", "结束任职", "结束", "end"],
  ["/admin/identity/authority-grants", "ACTIVE", "撤销授权", "撤销", "revoke"],
];
it.each(lifecycle)("wires %s %s %s through a reasoned confirmation and exact original Request", async (path, state, label, action, suffix) => {
  const f = fixture(path, { row: { ...rows[path], state } });
  mount(f, path);
  const trigger = await screen.findByRole("button", { name: label });
  expect(trigger).toBeEnabled();
  fireEvent.click(trigger);
  const dialog = screen.getByRole("dialog");
  expect(dialog).toHaveTextContent(path.includes("organizations") ? "销售二组" : "陈晓");
  expect(dialog).toHaveTextContent("服务端");
  expect(f.writes).toHaveLength(0);
  expect(within(dialog).getByRole("button", { name: `确认${action}` })).toBeDisabled();
  fireEvent.click(within(dialog).getByRole("button", { name: "取消" }));
  expect(f.writes).toHaveLength(0);
  await waitFor(() => expect(trigger).toHaveFocus());
  fireEvent.click(trigger);
  change("操作原因", "ADMINISTRATIVE_ACTION");
  const confirm = screen.getByRole("button", { name: `确认${action}` });
  fireEvent.click(confirm); fireEvent.click(confirm);
  await waitFor(() => expect(f.writes).toHaveLength(1));
  const request = f.writes[0];
  expect(new URL(request.url).pathname).toBe(`/api/v1${path}/${taskId}/${suffix}`);
  expect(request.method).toBe("POST");
  expect(await request.clone().json()).toEqual({ reasonCode: "ADMINISTRATIVE_ACTION" });
  expect(request.headers.get("If-Match")).toBe(tag);
  expect(request.headers.get("Idempotency-Key")).toMatch(/^[0-9a-f]{8}-[0-9a-f-]{27}$/i);
  expect(request.headers.get("X-Appointment-Id")).toBe(taskId);
  expect(request.headers.get("Authorization")).toBe("Bearer test-only");
  expect(request.headers.has("X-On-Behalf-Appointment-Id")).toBe(false);
  await screen.findByText(/结果已记录/);
});

it.each(["principals", "organizations"] as const)("wires %s rename with only trimmed displayName", async kind => {
  const path = `/admin/identity/${kind}` as const, f = fixture(path);
  mount(f, path);
  fireEvent.click(await screen.findByRole("button", { name: kind === "principals" ? "修改名称" : "修改组织名称" }));
  change("显示名称", "  新名称  ");
  fireEvent.click(screen.getByRole("button", { name: "保存名称" }));
  await waitFor(() => expect(f.writes).toHaveLength(1));
  expect(f.writes[0].method).toBe("PATCH");
  expect(new URL(f.writes[0].url).pathname).toBe(`/api/v1${path}/${taskId}/display-name`);
  expect(await f.writes[0].clone().json()).toEqual({ displayName: "新名称" });
  expect(f.writes[0].headers.get("If-Match")).toBe(tag);
  expect(f.writes[0].headers.get("Idempotency-Key")).toMatch(/^[0-9a-f]{8}-[0-9a-f-]{27}$/i);
  expect(f.writes[0].headers.get("Authorization")).toBe("Bearer test-only");
  expect(f.writes[0].headers.get("X-Appointment-Id")).toBe(taskId);
  expect(f.writes[0].headers.has("X-On-Behalf-Appointment-Id")).toBe(false);
  await screen.findByText(/结果已记录/);
});

it.each([
  ["principals", "新增身份主体"], ["organizations", "新增组织"], ["appointments", "新建任职"], ["authority-grants", "新增直接授权"],
] as const)("wires %s creation from the right detail controls", async (kind, label) => {
  const path = `/admin/identity/${kind}` as const, f = fixture(path);
  mount(f, path);
  await screen.findByText("本页 1 项");
  fireEvent.click(screen.getByRole("button", { name: label }));
  expect(screen.queryByRole("button", { name: label })).not.toBeInTheDocument();
  let body: Record<string, unknown>;
  if (kind === "principals") {
    change("完整用户名", "chen.xiao");
    fireEvent.click(screen.getByRole("button", { name: "精确查询" }));
    await screen.findByRole("option", { name: "陈晓（已核验用户名）" });
    change("身份提供方用户", "opaque_provider_choice");
    change("显示名称", "  陈晓新身份  ");
    body = { providerUserSelector: "opaque_provider_choice", displayName: "陈晓新身份" };
    expect(f.requests.filter(r => r.url.includes("provider-users"))[0].url).toContain("?search=chen.xiao");
  } else {
    await screen.findByRole("option", { name: "销售二组" });
    change(kind === "organizations" ? "上级组织" : kind === "appointments" ? "所属组织" : "组织范围", draftId);
    if (kind === "organizations") {
      change("组织代码", "SALES_NEW"); change("显示名称", "新组");
      body = { parentOrganizationId: draftId, code: "SALES_NEW", displayName: "新组" };
    } else {
      await screen.findByRole("option", { name: "陈晓" });
      change(kind === "appointments" ? "身份主体" : "授权任职", selectorId);
      change(kind === "appointments" ? "岗位" : "权限", kind === "appointments" ? "CONTACT_OPERATOR" : "SALES_CONTACT_OWNER");
      change("生效时间", "2026-09-10T09:30");
      expect(screen.getByText(/本机时区/)).toBeVisible();
      const start = new Date(2026, 8, 10, 9, 30).toISOString();
      body = kind === "appointments" ? { principalId: selectorId, organizationId: draftId, roleCode: "CONTACT_OPERATOR", effectiveFrom: start, effectiveUntil: null } : { appointmentId: selectorId, authorityCode: "SALES_CONTACT_OWNER", scopeOrganizationId: draftId, validFrom: start, validUntil: null };
    }
  }
  fireEvent.click(screen.getByRole("button", { name: "确认创建" }));
  await waitFor(() => expect(f.writes).toHaveLength(1));
  const request = f.writes[0];
  expect(new URL(request.url).pathname).toBe(`/api/v1${path}`);
  expect(request.method).toBe("POST");
  expect(await request.clone().json()).toEqual(body);
  expect(request.headers.has("If-Match")).toBe(false);
  expect(request.headers.get("Authorization")).toBe("Bearer test-only");
  expect(request.headers.get("X-Appointment-Id")).toBe(taskId);
  expect(request.headers.has("X-On-Behalf-Appointment-Id")).toBe(false);
  expect(request.headers.get("Idempotency-Key")).toMatch(/^[0-9a-f]{8}-[0-9a-f-]{27}$/i);
  await screen.findByText(/结果已记录/);
});
