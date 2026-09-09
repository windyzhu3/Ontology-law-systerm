import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { expect, it } from "vitest";
import type { components } from "../../generated/api/schema";
import { deferred, taskId, selectorId, testSession } from "../../test/fixtures";
import { createIdentityApi } from "./identityApi";
import { IdentityAdminApplication } from "./IdentityAdminApplication";
import type { IdentityAdminRoute } from "./identityRoutes";
import { RecoveryStore } from "../session/recoveryMarker";

type S = components["schemas"];
const identityETag = `"identity.${"a".repeat(43)}"`;
const organizationId = "019c7000-0000-7000-8000-000000000004";
const headers = { "Content-Type": "application/json", "Cache-Control": "no-store" };
const response = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers });
const bodies: Record<IdentityAdminRoute, unknown> = {
  "/admin/identity/principals": {
    items: [{ id: taskId, displayName: "陈晓", state: "ACTIVE", etag: identityETag } satisfies S["IdentityPrincipalV1"]],
    nextCursor: null,
  },
  "/admin/identity/organizations": {
    items: [{ id: organizationId, parentOrganizationId: selectorId, code: "SALES_TEAM_02", displayName: "销售二组", state: "ACTIVE", etag: identityETag } satisfies S["OrganizationUnitV1"]],
    nextCursor: null,
  },
  "/admin/identity/appointments": {
    items: [{ id: taskId, principal: { id: selectorId, label: "陈晓" }, organization: { id: organizationId, label: "销售二组" }, roleCode: "CONTACT_OPERATOR", effectiveFrom: "2026-06-12T09:00:00Z", effectiveUntil: null, state: "ACTIVE", etag: identityETag } satisfies S["AppointmentV1"]],
    nextCursor: null,
  },
  "/admin/identity/authority-grants": {
    items: [{ id: taskId, appointment: { id: selectorId, label: "陈晓 · 销售二组 · 首联经办" }, authorityCode: "SALES_CONTACT_OWNER", scopeOrganization: { id: organizationId, label: "商务中心及下级组织" }, validFrom: "2026-06-15T09:00:00Z", validUntil: null, state: "ACTIVE", etag: identityETag } satisfies S["AuthorityGrantV1"]],
    nextCursor: null,
  },
};
const cases: Array<[IdentityAdminRoute, string, string]> = [
  ["/admin/identity/principals", "/api/v1/admin/identity/principals?limit=20", "陈晓"],
  ["/admin/identity/organizations", "/api/v1/admin/identity/organizations?limit=20", "销售二组"],
  ["/admin/identity/appointments", "/api/v1/admin/identity/appointments?limit=20", "首联经办"],
  ["/admin/identity/authority-grants", "/api/v1/admin/identity/authority-grants?limit=20", "首联处置"],
];

it.each(cases)("renders the real bounded read for %s without identifiers or removed fields", async (path, pathname, visibleText) => {
  const captured: Request[] = [];
  const session = { ...testSession(), displayName: "林澜", getValidAccessToken: async () => "admin-token" };
  const api = createIdentityApi(
    new RecoveryStore(sessionStorage),
    async (request) => {
      captured.push(request);
      return response(bodies[path]);
    },
    location.origin,
  );
  render(<IdentityAdminApplication session={session} api={api} path={path} onNavigate={() => {}} sessionActions={<span>林澜 / 管理模式</span>} />);
  expect(await screen.findAllByText(visibleText)).not.toHaveLength(0);
  expect(captured).toHaveLength(1);
  expect(captured[0].url).toContain(pathname);
  expect(captured[0].headers.get("Authorization")).toBe("Bearer admin-token");
  expect(captured[0].headers.get("X-Appointment-Id")).toBe(taskId);
  expect(captured[0].headers.has("X-On-Behalf-Appointment-Id")).toBe(false);
  expect(document.body.textContent).not.toContain(taskId);
  expect(document.body.textContent).not.toContain(identityETag);
  expect(screen.queryByText("创建时间")).not.toBeInTheDocument();
  expect(screen.queryByText("授予人")).not.toBeInTheDocument();
  expect(screen.getAllByText("当前仅开放查询，写入功能尚未接入").length).toBeGreaterThan(0);
  if (path === "/admin/identity/appointments" || path === "/admin/identity/authority-grants")
    expect(screen.getByLabelText(`${path === "/admin/identity/appointments" ? "任职管理" : "直接授权"}列表`).textContent).not.toContain("09:00");
});

it("uses only returned cursors and visited history, clearing selection between pages", async () => {
  const captured: Request[] = [];
  const pages = [
    { items: [{ id: taskId, displayName: "第一页人员", state: "ACTIVE", etag: identityETag }], nextCursor: "opaque-next" },
    { items: [{ id: selectorId, displayName: "第二页人员", state: "SUSPENDED", etag: identityETag }], nextCursor: null },
    { items: [{ id: taskId, displayName: "第一页人员", state: "ACTIVE", etag: identityETag }], nextCursor: "opaque-next" },
  ];
  const api = createIdentityApi(new RecoveryStore(sessionStorage), async (request) => {
    captured.push(request);
    return response(pages.shift());
  }, location.origin);
  render(<IdentityAdminApplication session={testSession()} api={api} path="/admin/identity/principals" onNavigate={() => {}} sessionActions={null} />);
  await screen.findByRole("button", { name: "第一页人员" });
  expect(screen.getByText("本页 1 项")).toBeVisible();
  expect(screen.getByRole("button", { name: "上一页" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "上一页" }).closest(".identity-list-scroll")).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "下一页" }));
  expect(screen.queryByText("第一页人员")).not.toBeInTheDocument();
  await screen.findByRole("button", { name: "第二页人员" });
  expect(captured[1].url).toContain("cursor=opaque-next");
  expect(screen.getByRole("button", { name: "下一页" })).toBeDisabled();
  fireEvent.click(screen.getByRole("button", { name: "上一页" }));
  await screen.findByRole("button", { name: "第一页人员" });
  expect(captured[2].url).not.toContain("cursor=");
});

it("keeps an exact selection on refresh only while that id remains on the page", async () => {
  const firstItems = [
    { id: taskId, displayName: "人员甲", state: "ACTIVE", etag: identityETag },
    { id: selectorId, displayName: "人员乙", state: "SUSPENDED", etag: identityETag },
  ];
  const pages = [
    { items: firstItems, nextCursor: null },
    { items: [...firstItems].reverse(), nextCursor: null },
    { items: [firstItems[0]], nextCursor: null },
  ];
  const api = createIdentityApi(new RecoveryStore(sessionStorage), async () => response(pages.shift()), location.origin);
  render(<IdentityAdminApplication session={testSession()} api={api} path="/admin/identity/principals" onNavigate={() => {}} sessionActions={null} />);
  fireEvent.click(await screen.findByRole("button", { name: "人员乙" }));
  expect(screen.getByRole("heading", { name: "人员乙" })).toBeVisible();
  fireEvent.click(screen.getByRole("button", { name: "刷新" }));
  await waitFor(() => expect(screen.getByRole("button", { name: "刷新" })).toBeEnabled());
  expect(screen.getByRole("heading", { name: "人员乙" })).toBeVisible();
  fireEvent.click(screen.getByRole("button", { name: "刷新" }));
  await waitFor(() => expect(screen.getByRole("button", { name: "刷新" })).toBeEnabled());
  expect(screen.queryByRole("heading", { name: "人员乙" })).not.toBeInTheDocument();
  expect(screen.getByText("请选择一项查看详情。")).toBeVisible();
});

it("does not let a late prior identity response overwrite the new identity", async () => {
  const first = deferred<Response>();
  const second = deferred<Response>();
  let calls = 0;
  const api = createIdentityApi(new RecoveryStore(sessionStorage), async () => (++calls === 1 ? first.promise : second.promise), location.origin);
  const oldSession = testSession(1);
  const view = render(<IdentityAdminApplication session={oldSession} api={api} path="/admin/identity/principals" onNavigate={() => {}} sessionActions={null} />);
  await waitFor(() => expect(calls).toBe(1));
  const newSession = testSession(2);
  view.rerender(<IdentityAdminApplication session={newSession} api={api} path="/admin/identity/principals" onNavigate={() => {}} sessionActions={null} />);
  await waitFor(() => expect(calls).toBe(2));
  second.resolve(response({ items: [{ id: selectorId, displayName: "新身份数据", state: "ACTIVE", etag: identityETag }], nextCursor: null }));
  await screen.findByRole("button", { name: "新身份数据" });
  first.resolve(response({ items: [{ id: taskId, displayName: "旧身份私密数据", state: "ACTIVE", etag: identityETag }], nextCursor: null }));
  await waitFor(() => expect(screen.queryByText("旧身份私密数据")).not.toBeInTheDocument());
});

it("projects missing-parent and cyclic organizations once without claiming a root", async () => {
  const one = taskId, two = selectorId, missing = organizationId;
  const api = createIdentityApi(new RecoveryStore(sessionStorage), async () => response({
    items: [
      { id: one, parentOrganizationId: two, code: "ONE", displayName: "循环一", state: "ACTIVE", etag: identityETag },
      { id: two, parentOrganizationId: one, code: "TWO", displayName: "循环二", state: "ACTIVE", etag: identityETag },
      { id: missing, parentOrganizationId: "019c7000-0000-7000-8000-000000000099", code: "MISSING", displayName: "缺失上级", state: "ACTIVE", etag: identityETag },
    ], nextCursor: null,
  }), location.origin);
  render(<IdentityAdminApplication session={testSession()} api={api} path="/admin/identity/organizations" onNavigate={() => {}} sessionActions={null} />);
  expect(await screen.findByRole("button", { name: "循环一" })).toBeVisible();
  expect(screen.getByRole("button", { name: "循环二" })).toBeVisible();
  fireEvent.click(screen.getByRole("button", { name: "缺失上级" }));
  expect(screen.getByText("上级组织未加载")).toBeVisible();
  expect(screen.getByText("下级情况未完整加载")).toBeVisible();
  expect(screen.queryByText("根组织")).not.toBeInTheDocument();
});

it("expands only already loaded organization relationships without another request", async () => {
  let calls = 0;
  const api = createIdentityApi(new RecoveryStore(sessionStorage), async () => {
    calls += 1;
    return response({
      items: [
        { id: taskId, parentOrganizationId: null, code: "ROOT", displayName: "远川律师事务所", state: "ACTIVE", etag: identityETag },
        { id: selectorId, parentOrganizationId: taskId, code: "CHILD", displayName: "管理委员会", state: "ACTIVE", etag: identityETag },
      ],
      nextCursor: null,
    });
  }, location.origin);
  render(<IdentityAdminApplication session={testSession()} api={api} path="/admin/identity/organizations" onNavigate={() => {}} sessionActions={null} />);
  expect(await screen.findByRole("button", { name: "管理委员会" })).toBeVisible();
  fireEvent.click(screen.getByRole("button", { name: "收起远川律师事务所的已加载下级" }));
  expect(screen.queryByRole("button", { name: "管理委员会" })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "展开远川律师事务所的已加载下级" }));
  expect(screen.getByRole("button", { name: "管理委员会" })).toBeVisible();
  expect(calls).toBe(1);
});

it("shows a safe retryable failure instead of treating a failed GET as empty", async () => {
  let calls = 0;
  const api = createIdentityApi(new RecoveryStore(sessionStorage), async () => {
    calls += 1;
    return calls === 1 ? response({ detail: "secret backend trace 123" }, 503) : response(bodies["/admin/identity/principals"]);
  }, location.origin);
  render(<IdentityAdminApplication session={testSession()} api={api} path="/admin/identity/principals" onNavigate={() => {}} sessionActions={null} />);
  expect(await screen.findByRole("alert")).toHaveTextContent("身份管理数据暂时不可用，请重读后再试。");
  expect(document.body.textContent).not.toContain("secret backend trace 123");
  expect(screen.queryByText("当前页没有可显示的记录。")).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "重读" }));
  expect(await screen.findByRole("button", { name: "陈晓" })).toBeVisible();
});

it("clears disclosed rows immediately when the read is denied", async () => {
  const invalidations: number[] = [];
  let calls = 0;
  const session = { ...testSession(), invalidate: (status: number) => invalidations.push(status) };
  const api = createIdentityApi(new RecoveryStore(sessionStorage), async () => {
    calls += 1;
    return calls === 1
      ? response(bodies["/admin/identity/principals"])
      : response({ code: "NOT_AUTHORIZED", detail: "private policy detail" }, 403);
  }, location.origin);
  render(<IdentityAdminApplication session={session} api={api} path="/admin/identity/principals" onNavigate={() => {}} sessionActions={null} />);
  await screen.findByRole("button", { name: "陈晓" });
  fireEvent.click(screen.getByRole("button", { name: "刷新" }));
  await waitFor(() => expect(invalidations).toContain(403));
  expect(await screen.findByRole("alert")).toHaveTextContent("身份管理数据暂时不可用，请重读后再试。");
  expect(screen.queryByText("陈晓")).not.toBeInTheDocument();
  expect(document.body.textContent).not.toContain("private policy detail");
});
