import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { expect, it } from "vitest";
import { IdentityAdminApplication } from "./IdentityAdminApplication";
import { fixture, json, nextTag, principal, success, tag } from "./identityWriteFixtures";
import { deferred, draftId, selectorId, taskId, testSession } from "../../test/fixtures";
import { RecoveryStore, markerKey } from "../session/recoveryMarker";

const path = "/admin/identity/principals" as const;
function mount(f: ReturnType<typeof fixture>) { return render(<IdentityAdminApplication session={f.session} api={f.api} path={path} onNavigate={() => {}} sessionActions={null} />); }
async function rename() { fireEvent.click(await screen.findByRole("button", { name: "修改名称" })); fireEvent.change(screen.getByLabelText("显示名称"), { target: { value: "改后名称" } }); fireEvent.click(screen.getByRole("button", { name: "保存名称" })); }
function problem(code: string, status: number, retryPolicy: string) { return json({ type: "https://example.test/problem", title: "未提交", status, code, detail: "safe", instance: "/problems/fixture", retryPolicy, ...(code === "STALE_IDENTITY" ? { currentETag: { resourceKind: "IDENTITY", value: nextTag } } : {}), ...(code === "VALIDATION_FAILED" ? { fieldErrors: [{ pointer: "/body/displayName", code: "INVALID_FORMAT", detail: "safe" }] } : {}) }, status); }

it("retries the identical page original after loss using a rotated token and the shared clue", async () => {
  let token = "old", count = 0;
  const f = fixture(path, { handle: async request => { if (request.method === "PATCH" && ++count === 1) throw new Error("lost"); } });
  f.session = { ...f.session, getValidAccessToken: async () => token };
  const view = mount(f); await rename();
  await screen.findByRole("button", { name: "重试原请求" });
  expect(screen.getByLabelText("显示名称")).toBeDisabled();
  const before = f.writes[0];
  expect(f.api.recovery.read()?.commandId).toBe(before.headers.get("Idempotency-Key"));
  expect(Object.keys(JSON.parse(sessionStorage.getItem(markerKey)!)).sort()).toEqual(["actorScopeKey", "commandId", "commandType", "recordedAt"]);
  token = "rotated";
  view.rerender(<IdentityAdminApplication session={{ ...f.session }} api={f.api} path={path} onNavigate={() => {}} sessionActions={null} />);
  fireEvent.click(screen.getByRole("button", { name: "重试原请求" }));
  await screen.findByText(/结果已记录/);
  expect(f.writes).toHaveLength(2);
  expect(f.writes[1].headers.get("Idempotency-Key")).toBe(before.headers.get("Idempotency-Key"));
  expect(f.writes[1].headers.get("If-Match")).toBe(tag);
  expect(await f.writes[1].clone().text()).toBe(await before.clone().text());
  expect(f.writes[1].headers.get("Authorization")).toBe("Bearer rotated");
});

it("keeps unknown status and marker after receipt 404 and query failure; does not POST automatically", async () => {
  const f = fixture(path, { handle: async request => { if (request.method === "PATCH") throw new Error("lost"); if (request.url.includes("/receipt")) return json({}, 404); } });
  mount(f); await rename();
  fireEvent.click(await screen.findByRole("button", { name: "查询原回执" }));
  await screen.findByRole("button", { name: "查询原回执" });
  expect(screen.getByRole("alert")).toHaveTextContent("结果尚未确认");
  expect(f.api.recovery.read()).not.toBeNull();
  expect(f.writes).toHaveLength(1);
  expect(f.requests.filter(r => r.url.includes("/receipt"))).toHaveLength(1);
});

it("corrects only a proven validation failure while retaining the old key", async () => {
  let calls = 0;
  const f = fixture(path, { handle: async r => { if (r.method === "PATCH" && ++calls === 1) return problem("VALIDATION_FAILED", 400, "SAME_KEY_AFTER_FIX"); } });
  mount(f); await rename(); await screen.findByText(/操作未提交/);
  expect(screen.getByLabelText("显示名称")).toBeEnabled();
  fireEvent.change(screen.getByLabelText("显示名称"), { target: { value: "已修正" } });
  fireEvent.click(screen.getByRole("button", { name: "保存名称" }));
  await waitFor(() => expect(f.writes).toHaveLength(2));
  expect(f.writes[1].headers.get("Idempotency-Key")).toBe(f.writes[0].headers.get("Idempotency-Key"));
  expect(await f.writes[1].clone().json()).toEqual({ displayName: "已修正" });
});

it("requires a successful reread before stale failure can create a new key and fresh target ETag", async () => {
  let writeCalls = 0, readCalls = 0;
  const f = fixture(path, { handle: async r => { if (r.method === "PATCH" && ++writeCalls === 1) return problem("STALE_IDENTITY", 412, "NEW_KEY_AFTER_REFRESH"); if (r.method === "GET") { readCalls++; return json({ items: [{ ...principal, etag: readCalls > 1 ? nextTag : tag }], nextCursor: null }); } } });
  mount(f); await rename(); await screen.findByText(/操作未提交/);
  fireEvent.click(screen.getByRole("button", { name: "取消" }));
  if (screen.queryByRole("button", { name: "确认舍弃" })) fireEvent.click(screen.getByRole("button", { name: "确认舍弃" }));
  expect(screen.getByRole("button", { name: "重新读取并核对" })).toBeVisible();
  expect(f.writes).toHaveLength(1);
  fireEvent.click(screen.getByRole("button", { name: "重新读取并核对" }));
  await screen.findByText(/已重新读取，请重新选择/);
  await rename();
  await waitFor(() => expect(f.writes).toHaveLength(2));
  expect(f.writes[1].headers.get("Idempotency-Key")).not.toBe(f.writes[0].headers.get("Idempotency-Key"));
  expect(f.writes[1].headers.get("If-Match")).toBe(nextTag);
});

it("retains a confirmed success across list refresh failure and only re-GETs", async () => {
  let reads = 0;
  const f = fixture(path, { handle: async r => { if (r.method === "GET" && ++reads >= 2) return json({}, 503); } });
  mount(f); await rename();
  await screen.findByText(/结果已记录，列表刷新失败/);
  expect(f.api.recovery.read()).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "重读列表" }));
  await waitFor(() => expect(reads).toBe(3));
  await waitFor(() => expect(screen.getByText(/结果已记录，列表刷新失败/)).toBeVisible());
  expect(f.writes).toHaveLength(1);
});

it("does not reclassify a recorded success as unknown when navigation is attempted during its reread", async () => {
  const reread = deferred<Response>(); let reads = 0;
  const f = fixture(path, { handle: async r => { if (r.method === "GET" && ++reads === 2) return reread.promise; } });
  mount(f); await rename(); await screen.findByText(/结果已记录/);
  fireEvent.click(screen.getByRole("link", { name: "组织架构" }));
  expect(screen.getByText(/结果已记录/)).toBeVisible(); expect(screen.queryByText(/结果尚未确认/)).not.toBeInTheDocument();
  reread.resolve(json({ items: [principal], nextCursor: null })); await screen.findByText(/当前页已重新读取/);
});

it("blocks an extra write and navigation while the first dispatch is pending", async () => {
  const pending = deferred<Response>(), navigations: string[] = [];
  const f = fixture(path, { handle: async r => { if (r.method === "PATCH") return pending.promise; } });
  render(<IdentityAdminApplication session={f.session} api={f.api} path={path} onNavigate={p => navigations.push(p)} sessionActions={null} />);
  await rename(); await waitFor(() => expect(f.writes).toHaveLength(1));
  fireEvent.click(screen.getByRole("link", { name: "组织架构" }));
  fireEvent.click(screen.getByRole("button", { name: "保存名称" }));
  expect(navigations).toEqual([]); expect(f.writes).toHaveLength(1);
  pending.resolve(success(f.writes[0]));
  await screen.findByText(/结果已记录/);
});

it.each([401, 403])("clears sensitive forms and rows on %i without delaying invalidation for dirty confirmation", async status => {
  const f = fixture(path, { handle: async r => { if (r.method === "PATCH") return json({ code: "NOT_AUTHORIZED" }, status); } });
  const invalidations: number[] = []; f.session = { ...f.session, invalidate: n => invalidations.push(n) };
  mount(f); await rename();
  await waitFor(() => expect(invalidations).toContain(status));
  expect(screen.queryByLabelText("显示名称")).not.toBeInTheDocument();
  expect(screen.queryByText("陈晓")).not.toBeInTheDocument();
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  expect(f.api.recovery.read()).not.toBeNull();
});

it("drops late feedback and form values across actor epochs without deleting the previous Actor marker", async () => {
  const pending = deferred<Response>();
  const f = fixture(path, { handle: async r => { if (r.method === "PATCH") return pending.promise; } });
  const view = mount(f); await rename(); await waitFor(() => expect(f.writes).toHaveLength(1));
  const oldClue = sessionStorage.getItem(markerKey);
  view.rerender(<IdentityAdminApplication session={testSession(2)} api={f.api} path={path} onNavigate={() => {}} sessionActions={null} />);
  expect(screen.queryByLabelText("显示名称")).not.toBeInTheDocument();
  pending.resolve(success(f.writes[0]));
  await act(async () => {});
  expect(sessionStorage.getItem(markerKey)).toBe(oldClue);
  expect(screen.queryByText(/结果已记录/)).not.toBeInTheDocument();
});

it.each(["broken", "business", "save-failed"] as const)("does not bypass shared recovery admission when %s", async kind => {
  const recovery = kind === "save-failed" ? new RecoveryStore({ getItem: () => null, setItem: () => { throw new Error("storage"); } } as unknown as Storage) : new RecoveryStore(sessionStorage);
  if (kind === "broken") sessionStorage.setItem(markerKey, "{broken");
  if (kind === "business") recovery.reserve({ commandId: selectorId, commandType: "CAPTURE_LEAD", actorScopeKey: testSession().actorScopeKey, recordedAt: new Date().toISOString() });
  const before = sessionStorage.getItem(markerKey), f = fixture(path, { recovery });
  mount(f); await rename();
  await screen.findByRole("button", { name: "查询原回执" });
  expect(f.writes).toHaveLength(0);
  expect(sessionStorage.getItem(markerKey)).toBe(before);
  expect(screen.getByRole("alert")).not.toHaveTextContent("恢复线索已保留");
});

it("distinguishes NO_CHANGE and recovered REJECTED without reporting rejected as success", async () => {
  const f = fixture(path, { handle: async r => { if (r.method === "PATCH") throw new Error("lost"); if (r.url.includes("/receipt")) return json({ commandId: f.writes[0].headers.get("Idempotency-Key"), receiptId: draftId, completedAt: "2026-09-09T01:00:00Z", outcome: "REJECTED", rejectionCode: "IDENTITY_LAST_ADMIN" }); } });
  mount(f); await rename(); fireEvent.click(await screen.findByRole("button", { name: "查询原回执" }));
  await screen.findByText(/操作已拒绝.*最后一位/);
  expect(screen.queryByText(/结果已记录/)).not.toBeInTheDocument(); expect(f.api.recovery.read()).toBeNull();
});

it("keeps the original NO write policy after a receipt 404 instead of enabling a forbidden retry", async () => {
  const f = fixture(path, { handle: async r => { if (r.method === "PATCH") return problem("COMMAND_PAYLOAD_CONFLICT", 409, "NO"); if (r.url.includes("/receipt")) return json({}, 404); } });
  mount(f); await rename(); const query = await screen.findByRole("button", { name: "查询原回执" });
  expect(screen.queryByRole("button", { name: "重试原请求" })).not.toBeInTheDocument();
  fireEvent.click(query); await screen.findByRole("button", { name: "查询原回执" });
  expect(screen.queryByRole("button", { name: "重试原请求" })).not.toBeInTheDocument(); expect(f.writes).toHaveLength(1);
});

it("keeps SAME_KEY_AFTER_BACKOFF on explicit retry and never offers a fresh-key recheck", async () => {
  let attempts = 0;
  const f = fixture(path, { handle: async r => { if (r.method === "PATCH" && ++attempts === 1) return problem("RATE_LIMITED", 429, "SAME_KEY_AFTER_BACKOFF"); } });
  mount(f); await rename(); fireEvent.click(await screen.findByRole("button", { name: "重试原请求" }));
  await waitFor(() => expect(f.writes).toHaveLength(2));
  expect(f.writes[1].headers.get("Idempotency-Key")).toBe(f.writes[0].headers.get("Idempotency-Key")); expect(screen.queryByRole("button", { name: "重新读取并核对" })).not.toBeInTheDocument();
});

it.each(["IDENTITY_ORGANIZATION_DEPENDENCY", "IDENTITY_RESPONSIBILITY_DEPENDENCY"])("requires admin correction and successful recheck for %s", async code => {
  const f = fixture(path, { handle: async r => { if (r.method === "PATCH") return problem(code, 409, "NEW_KEY_AFTER_ADMIN_FIX"); } });
  mount(f); await rename(); await screen.findByText(/操作未提交/);
  expect(screen.getByText(/请先联系管理员处理依赖/)).toBeVisible();
  fireEvent.click(screen.getByRole("button", { name: "取消" }));
  expect(screen.getByRole("button", { name: "重新读取并核对" })).toBeVisible(); expect(screen.getByRole("button", { name: "保存名称" })).toBeDisabled(); expect(f.writes).toHaveLength(1);
});

it.each(["IDENTITY_SELF_LOCKOUT", "IDENTITY_LAST_ADMIN", "IDENTITY_BINDING_CONFLICT"])("shows a named safe refusal for %s without a retry write", async code => {
  const f = fixture(path, { handle: async r => { if (r.method === "PATCH") return problem(code, 409, "NO"); } });
  mount(f); await rename(); await screen.findByText(/操作未提交/);
  expect(screen.queryByRole("button", { name: "重试原请求" })).not.toBeInTheDocument(); expect(screen.getByRole("button", { name: "保存名称" })).toBeDisabled(); expect(f.writes).toHaveLength(1);
});

it("renders a valid rename NO_CHANGE as recorded without another write", async () => {
  const f = fixture(path, { handle: async r => { if (r.method === "PATCH") return success(r, "NO_CHANGE"); } }); mount(f); await rename();
  await screen.findByText("结果已记录，名称未变化。"); expect(f.writes).toHaveLength(1); expect(f.api.recovery.read()).toBeNull();
});

it("requires a reread after a recovered stale rejection without replaying its terminal key", async () => {
  const f = fixture(path, { handle: async r => { if (r.method === "PATCH") throw new Error("lost"); if (r.url.includes("/receipt")) return json({ commandId: f.writes[0].headers.get("Idempotency-Key"), receiptId: draftId, completedAt: "2026-09-09T01:00:00Z", outcome: "REJECTED", rejectionCode: "STALE_IDENTITY" }); } });
  mount(f); await rename(); fireEvent.click(await screen.findByRole("button", { name: "查询原回执" }));
  await screen.findByText(/操作已拒绝/); expect(screen.getByRole("button", { name: "重新读取并核对" })).toBeVisible();
  fireEvent.click(screen.getByRole("button", { name: "修改名称" })); expect(screen.queryByLabelText("显示名称")).not.toBeInTheDocument(); expect(f.writes).toHaveLength(1);
});
