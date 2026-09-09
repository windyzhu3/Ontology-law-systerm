import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { IdentityAdminApplication } from "./IdentityAdminApplication";
import { fixture, json, nextTag, principal, roles, rows } from "./identityWriteFixtures";
import { deferred, draftId, selectorId, taskId, testSession } from "../../test/fixtures";
import type { IdentityAdminRoute } from "./identityRoutes";
const principalPath = "/admin/identity/principals" as const;
function mount(f: ReturnType<typeof fixture>, path: IdentityAdminRoute = principalPath, onNavigate = (_path: IdentityAdminRoute) => {}) { return render(<IdentityAdminApplication session={f.session} api={f.api} path={path} onNavigate={onNavigate} sessionActions={null} />); }
const change = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });

it("invalidates the precise provider choice on a username edit and ignores the late old lookup", async () => {
  const late = deferred<Response>(); let lookup = 0;
  const f = fixture(principalPath, { handle: async r => { if (r.url.includes("provider-users") && ++lookup === 1) return late.promise; } });
  mount(f); await screen.findByText("本页 1 项"); fireEvent.click(screen.getByRole("button", { name: "新增身份主体" }));
  change("完整用户名", "old.user"); fireEvent.click(screen.getByRole("button", { name: "精确查询" }));
  await waitFor(() => expect(lookup).toBe(1)); change("完整用户名", "new.user");
  fireEvent.click(screen.getByRole("button", { name: "精确查询" }));
  await screen.findByRole("option", { name: "陈晓（已核验用户名）" });
  late.resolve(json({ items: [{ selector: "old_secret", label: "旧用户" }], nextCursor: null }));
  await act(async () => {}); expect(screen.queryByRole("option", { name: "旧用户" })).not.toBeInTheDocument();
  change("身份提供方用户", "opaque_provider_choice"); change("显示名称", "本地名称");
  change("完整用户名", "third.user");
  expect(screen.queryByRole("option", { name: "陈晓（已核验用户名）" })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "确认创建" }));
  expect(f.writes).toHaveLength(0); expect(screen.getByLabelText("身份提供方用户")).toHaveAttribute("aria-invalid", "true");
  expect(document.body.textContent).not.toContain("opaque_provider_choice");
  expect(f.requests.filter(r => r.url.includes("provider-users")).every(r => !r.url.includes("cursor=") && !r.url.includes("limit="))).toBe(true);
});

it.each(["empty", "failed"] as const)("does not let %s provider lookup create a made-up subject", async mode => {
  const f = fixture(principalPath, { handle: async r => { if (r.url.includes("provider-users")) return mode === "empty" ? json({ items: [], nextCursor: null }) : json({}, 503); } });
  mount(f); fireEvent.click(await screen.findByRole("button", { name: "新增身份主体" })); change("完整用户名", "missing.user");
  fireEvent.click(screen.getByRole("button", { name: "精确查询" }));
  await screen.findByText(mode === "empty" ? "未找到完全匹配的用户。" : "精确查询失败，请核对完整用户名后重试。");
  change("显示名称", "张三"); fireEvent.click(screen.getByRole("button", { name: "确认创建" })); expect(f.writes).toHaveLength(0);
});

it("keeps option kinds independently paged with exact ids even when labels repeat", async () => {
  const path = "/admin/identity/appointments" as const;
  const f = fixture(path, { handle: async r => {
    const url = new URL(r.url); if (!url.pathname.endsWith("options")) return;
    const kind = url.searchParams.get("optionKind");
    return json({ page: "APPOINTMENTS", optionKind: kind, candidates: { items: kind === "PRINCIPAL" ? [{ id: url.searchParams.has("cursor") ? taskId : selectorId, label: "同名人员" }] : [{ id: draftId, label: "同名组织" }], nextCursor: kind === "PRINCIPAL" && !url.searchParams.has("cursor") ? "person-next" : null }, roleCodes: roles, grantableAuthorityCodes: [] });
  } });
  mount(f, path); fireEvent.click(await screen.findByRole("button", { name: "新建任职" }));
  await screen.findByRole("option", { name: "同名人员" }); change("身份主体", selectorId); change("所属组织", draftId);
  const pagination = screen.getByLabelText("身份主体候选分页"); fireEvent.click(within(pagination).getByRole("button", { name: "下一页候选" }));
  await screen.findByRole("option", { name: "同名人员" }); expect(screen.getByLabelText("身份主体")).toHaveValue(""); expect(screen.getByLabelText("所属组织")).toHaveValue(draftId);
  change("身份主体", taskId); change("岗位", "CONTACT_OPERATOR"); change("生效时间", "2026-09-10T09:30");
  fireEvent.click(screen.getByRole("button", { name: "确认创建" })); await waitFor(() => expect(f.writes).toHaveLength(1));
  expect((await f.writes[0].clone().json()).principalId).toBe(taskId);
  const options = f.requests.filter(r => r.url.includes("options"));
  expect(options).toHaveLength(3); expect(options[2].url).toContain("optionKind=PRINCIPAL&limit=20&cursor=person-next");
  expect(screen.queryByRole("option", { name: "身份管理员" })).not.toBeInTheDocument();
});

it("associates field errors and rejects backwards windows", async () => {
  const path = "/admin/identity/appointments" as const, f = fixture(path); mount(f, path);
  fireEvent.click(await screen.findByRole("button", { name: "新建任职" })); await screen.findByRole("option", { name: "陈晓" });
  change("身份主体", selectorId); change("所属组织", draftId); change("岗位", "CONTACT_OPERATOR");
  change("生效时间", "2026-09-10T09:30"); change("结束时间（可留空）", "2026-09-10T09:00");
  fireEvent.click(screen.getByRole("button", { name: "确认创建" }));
  const end = screen.getByLabelText("结束时间（可留空）"); expect(end).toHaveAttribute("aria-invalid", "true");
  expect(document.getElementById(end.getAttribute("aria-describedby")!)).toHaveTextContent("结束时间必须晚于生效时间");
  expect(f.writes).toHaveLength(0);
});

it.each(["", "   ", "不安全\u0007名称", "名".repeat(201)])("does not submit an invalid rename %j and links its field error", async value => {
  const f = fixture(principalPath); mount(f); fireEvent.click(await screen.findByRole("button", { name: "修改名称" }));
  change("显示名称", value); fireEvent.click(screen.getByRole("button", { name: "保存名称" }));
  const field = screen.getByLabelText("显示名称"); expect(field).toHaveAttribute("aria-invalid", "true"); expect(document.getElementById(field.getAttribute("aria-describedby")!)).toHaveTextContent("1～200"); expect(f.writes).toHaveLength(0);
});

it.each(["lower", "1START", "A-INVALID", "A".repeat(65)])("does not submit an organization with invalid code %s", async code => {
  const path = "/admin/identity/organizations" as const, f = fixture(path); mount(f, path);
  fireEvent.click(await screen.findByRole("button", { name: "新增组织" })); await screen.findByRole("option", { name: "销售二组" });
  change("上级组织", draftId); change("显示名称", "新组织"); change("组织代码", code); fireEvent.click(screen.getByRole("button", { name: "确认创建" }));
  expect(screen.getByLabelText("组织代码")).toHaveAttribute("aria-invalid", "true"); expect(f.writes).toHaveLength(0);
});

it.each([
  ["/admin/identity/principals", "DISABLED", ["恢复使用", "暂停使用", "禁用身份"]],
  ["/admin/identity/organizations", "CLOSED", ["关闭组织"]],
  ["/admin/identity/appointments", "ENDED", ["恢复任职", "暂停任职", "结束任职"]],
  ["/admin/identity/authority-grants", "REVOKED", ["撤销授权"]],
] as const)("offers no forbidden lifecycle actions for %s in %s", async (path, state, forbidden) => {
  const f = fixture(path, { row: { ...rows[path], state } }); mount(f, path); await screen.findByText("本页 1 项");
  for (const label of forbidden) expect(screen.queryByRole("button", { name: label })).not.toBeInTheDocument(); expect(f.writes).toHaveLength(0);
});

it("requires discard for dirty navigation and never mixes the previous row ETag with a new row", async () => {
  const f = fixture(principalPath, { handle: async r => { if (r.method === "GET") return json({ items: [principal, { ...principal, id: selectorId, displayName: "周宁", etag: nextTag }], nextCursor: null }); } });
  const navigations: string[] = []; mount(f, principalPath, value => navigations.push(value));
  fireEvent.click(await screen.findByRole("button", { name: "修改名称" })); change("显示名称", "未保存");
  fireEvent.click(screen.getByRole("link", { name: "组织架构" })); expect(navigations).toEqual([]);
  fireEvent.click(screen.getByRole("button", { name: "继续编辑" })); expect(screen.getByLabelText("显示名称")).toHaveValue("未保存");
  fireEvent.click(screen.getByRole("button", { name: "周宁" })); fireEvent.click(screen.getByRole("button", { name: "确认舍弃" }));
  expect(screen.queryByLabelText("显示名称")).not.toBeInTheDocument(); expect(f.writes).toHaveLength(0);
  fireEvent.click(screen.getByRole("button", { name: "暂停使用" })); change("操作原因", "SECURITY_RESPONSE"); fireEvent.click(screen.getByRole("button", { name: "确认暂停" }));
  await waitFor(() => expect(f.writes).toHaveLength(1)); expect(f.writes[0].url).toContain(`/${selectorId}/suspend`); expect(f.writes[0].headers.get("If-Match")).toBe(nextTag);
});

it("traps keyboard focus and Escape returns to the original confirmation trigger", async () => {
  const f = fixture(principalPath); mount(f);
  const trigger = await screen.findByRole("button", { name: "暂停使用" }); fireEvent.click(trigger);
  const reason = screen.getByLabelText("操作原因"), cancel = screen.getByRole("button", { name: "取消" }); expect(reason).toHaveFocus();
  fireEvent.keyDown(document, { key: "Tab", shiftKey: true }); expect(cancel).toHaveFocus();
  fireEvent.keyDown(document, { key: "Tab" }); expect(reason).toHaveFocus();
  fireEvent.keyDown(document, { key: "Escape" }); expect(screen.queryByRole("dialog")).not.toBeInTheDocument(); await waitFor(() => expect(trigger).toHaveFocus()); expect(f.writes).toHaveLength(0);
});

it("restores focus only after the browser can focus the formerly inert background", async () => {
  // jsdom does not implement the browser's refusal to focus inert elements.
  const nativeFocus = HTMLElement.prototype.focus;
  const nativeSetAttribute = Element.prototype.setAttribute;
  const inert = vi.spyOn(Element.prototype, "setAttribute").mockImplementation(function (this: Element, name, value) { nativeSetAttribute.call(this, name, value); if (name === "inert" && this.contains(document.activeElement)) (document.activeElement as HTMLElement).blur(); });
  const focus = vi.spyOn(HTMLElement.prototype, "focus").mockImplementation(function (this: HTMLElement) { if (!this.closest("[inert]")) nativeFocus.call(this); });
  try {
    const f = fixture(principalPath); mount(f);
    const trigger = await screen.findByRole("button", { name: "暂停使用" }); fireEvent.click(trigger);
    fireEvent.keyDown(document, { key: "Escape" });
    await waitFor(() => expect(trigger).toHaveFocus());
  } finally { focus.mockRestore(); inert.mockRestore(); }
});

it("drops provider and form state on epoch change but preserves dirty inputs through token rotation", async () => {
  const f = fixture(principalPath); const view = mount(f); fireEvent.click(await screen.findByRole("button", { name: "新增身份主体" }));
  change("完整用户名", "demo.user"); change("显示名称", "敏感草稿");
  view.rerender(<IdentityAdminApplication session={{ ...f.session, getValidAccessToken: async () => "rotated" }} api={f.api} path={principalPath} onNavigate={() => {}} sessionActions={null} />);
  expect(screen.getByLabelText("显示名称")).toHaveValue("敏感草稿");
  view.rerender(<IdentityAdminApplication session={testSession(2)} api={f.api} path={principalPath} onNavigate={() => {}} sessionActions={null} />);
  expect(screen.queryByLabelText("完整用户名")).not.toBeInTheDocument(); expect(document.body.textContent).not.toContain("敏感草稿");
});
