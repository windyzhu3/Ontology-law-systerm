import { createLeadIntakeApi } from "../lead-intake/leadIntakeApi";
import { StrictMode } from "react";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { expect, it, beforeEach, afterEach, vi } from "vitest";
import { SessionApplication } from "./SessionApplication";
import { fixture as identityFixture } from "../identity/identityWriteFixtures";
import {
  SessionController,
  type SessionContext,
  type OidcAdapter,
} from "./sessionController";
import { RecoveryStore, markerKey } from "./recoveryMarker";
import { createWorkbenchApi } from "../../lib/api";
import {
  deferred,
  envelope,
  jsonResponse,
  receipt,
  taskId,
  selectorId,
  tags,
} from "../../test/fixtures";
const scope = "ask1.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
it('recovers an original command before the audit-only default entry and returns to audit afterwards',async()=>{
 history.replaceState(null,'','/login');const marker={commandId:selectorId,commandType:'CREATE_IDENTITY_PRINCIPAL',actorScopeKey:scope,recordedAt:new Date().toISOString()};sessionStorage.setItem(markerKey,JSON.stringify(marker));
 const reads:Request[]=[];vi.stubGlobal('fetch',async(request:Request)=>{reads.push(request);return jsonResponse({items:[]});});
 const f=fixture({context:{...context,canEnterWorkbench:false,canEnterIdentityAdmin:false,canReadAuditRecords:true},respond:async()=>jsonResponse({...receipt(selectorId),resultFact:{factType:'IDENTITY_PRINCIPAL',factRef:'safe-identity-result',revision:1}})});
 render(<SessionApplication controller={f.controller} api={f.api}/>);const confirm=await screen.findByRole('button',{name:'确认本次身份'});await waitFor(()=>expect(confirm).toBeEnabled());fireEvent.click(confirm);
 expect(await screen.findByRole('heading',{name:'核对原操作结果'})).toBeVisible();expect(f.controller.recovery.read()).toEqual(marker);expect(reads).toHaveLength(0);
 const query=await screen.findByRole('button',{name:'查询原操作结果'});await waitFor(()=>expect(query).toBeEnabled());fireEvent.click(query);fireEvent.click(await screen.findByRole('button',{name:'继续'}));expect(await screen.findByRole('heading',{name:'审计记录'})).toBeVisible();expect(location.pathname).toBe('/admin/audit-records');expect(f.requests).toHaveLength(1);
});
it.each(['/admin/audit-records','/login'])('enters the audit-only page from %s without identity directory access',async path=>{
 history.replaceState(null,'',path);
 vi.stubGlobal('fetch',async()=>new Response(JSON.stringify({items:[]}),{status:200,headers:{'Content-Type':'application/json','Cache-Control':'no-store'}}));
 const f=fixture({context:{...context,canEnterWorkbench:false,canEnterIdentityAdmin:false,canReadAuditRecords:true}});
 render(<SessionApplication controller={f.controller} api={f.api}/>);
 const confirm=await screen.findByRole('button',{name:'确认本次身份'});await waitFor(()=>expect(confirm).toBeEnabled());fireEvent.click(confirm);
 expect(await screen.findByRole('heading',{name:'审计记录'})).toBeVisible();
 expect(screen.queryByRole('link',{name:'身份主体'})).not.toBeInTheDocument();expect(screen.queryByRole('button',{name:/导出/})).not.toBeInTheDocument();
});

it.each(["switch", "popstate", "logout"] as const)("guards a dirty identity editor before explicit %s but immediately clears on expiry", async action => {
  history.replaceState(null, "", "/admin/identity/principals");
  const f = fixture({ context: { ...context, canEnterIdentityAdmin: true } });
  const identity = identityFixture("/admin/identity/principals", { recovery: f.controller.recovery });
  render(<SessionApplication controller={f.controller} api={f.api} identityApi={identity.api} />);
  const confirm = await screen.findByRole("button", { name: "确认本次身份" }); await waitFor(() => expect(confirm).toBeEnabled()); fireEvent.click(confirm);
  fireEvent.click(await screen.findByRole("button", { name: "修改名称" })); fireEvent.change(screen.getByLabelText("显示名称"), { target: { value: "私密待保存" } });
  if (action === "popstate") act(() => { history.replaceState(null, "", "/admin/identity/organizations"); window.dispatchEvent(new PopStateEvent("popstate")); });
  else fireEvent.click(screen.getByRole("button", { name: action === "switch" ? "切换任职" : "退出" }));
  expect(screen.getByRole("dialog", { name: "舍弃未保存的修改？" })).toBeVisible();
  expect(identity.writes).toHaveLength(0);
  act(() => f.controller.invalidate("EXPIRED"));
  expect(screen.queryByLabelText("显示名称")).not.toBeInTheDocument(); expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
});

it("explicitly leaves an unknown identity original for the existing recovery page without rewriting its clue or payload", async () => {
  history.replaceState(null, "", "/admin/identity/principals");
  const f = fixture({ context: { ...context, canEnterIdentityAdmin: true }, respond: async () => jsonResponse({}, 404) });
  const identity = identityFixture("/admin/identity/principals", { recovery: f.controller.recovery, handle: async request => { if (request.method !== "GET") throw new Error("lost response"); return undefined; } });
  render(<SessionApplication controller={f.controller} api={f.api} identityApi={identity.api} />);
  const confirm = await screen.findByRole("button", { name: "确认本次身份" }); await waitFor(() => expect(confirm).toBeEnabled()); fireEvent.click(confirm);
  fireEvent.click(await screen.findByRole("button", { name: "修改名称" })); fireEvent.change(screen.getByLabelText("显示名称"), { target: { value: "私密改名" } }); fireEvent.click(screen.getByRole("button", { name: "保存名称" }));
  const recover = await screen.findByRole("button", { name: "前往恢复入口" });
  const marker = sessionStorage.getItem(markerKey); fireEvent.click(recover);
  expect(screen.getByRole("dialog", { name: "离开本页并核对恢复线索？" })).toHaveTextContent("原请求正文");
  fireEvent.click(screen.getByRole("button", { name: "留在本页" })); expect(screen.getByLabelText("显示名称")).toHaveValue("私密改名");
  fireEvent.click(recover); fireEvent.click(screen.getByRole("button", { name: "确认前往恢复" }));
  expect(screen.queryByLabelText("显示名称")).not.toBeInTheDocument();
  await screen.findByRole("heading", { name: "核对原操作结果" });
  expect(await screen.findByText("当前仅能查询原回执。")).toBeVisible();
  expect(sessionStorage.getItem(markerKey)).toBe(marker); expect(identity.writes).toHaveLength(1);
  expect(f.requests.every(r => r.method === "GET")).toBe(true);
});
beforeEach(() => history.replaceState(null, "", "/workbench"));
afterEach(() => vi.unstubAllGlobals());
const context: SessionContext = {
  displayName: "合成入口办理人",
  state: "READY",
  appointmentChoices: [{ id: taskId, label: "业务一组 · 线索专员" }],
  selectedAppointmentId: taskId,
  selectedOnBehalfAppointmentId: null,
  delegatedAppointmentChoices: [],
  actorScopeKey: scope,
  canEnterWorkbench: true,
  canEnterIdentityAdmin: false,
};
function fixture(
  options: {
    initialize?: () => Promise<boolean>;
    context?: SessionContext | ((init: RequestInit) => SessionContext);
    respond?: (r: Request) => Promise<Response>;
  } = {},
) {
  let initializations = 0,
    token = "assembly-initial",
    clock = Date.now();
  const oidc: OidcAdapter = {
    initialize: async () => {
      initializations++;
      return options.initialize ? options.initialize() : true;
    },
    getValidAccessToken: async () => token,
    login: async () => {},
    logout: async () => {
      throw Error("idp-unavailable");
    },
    clear() {},
    sessionStartedAt: () => clock,
  };
  const self: RequestInit[] = [],
    requests: Request[] = [],
    store = new RecoveryStore(sessionStorage);
  const controller = new SessionController(
    oidc,
    store,
    async (_url, init) => {
      self.push(init!);
      return jsonResponse(
        typeof options.context === "function"
          ? options.context(init!)
          : (options.context ?? context),
      );
    },
    () => clock,
  );
  const api = createWorkbenchApi(
    async (r) => {
      requests.push(r);
      return options.respond
        ? options.respond(r)
        : jsonResponse(envelope(5, true));
    },
    location.origin,
    store,
  );
  return {
    controller,
    api,
    self,
    requests,
    oidc,
    initializations: () => initializations,
    rotate: (value: string) => {
      token = value;
    },
    warn: () => {
      clock += 1_740_000;
      controller.checkLifetime();
    },
  };
}
async function enter(f: ReturnType<typeof fixture>) {
  render(
    <StrictMode>
      <SessionApplication controller={f.controller} api={f.api} />
    </StrictMode>,
  );
  const confirm = await screen.findByRole("button", { name: "确认本次身份" });
  await waitFor(() => expect(confirm).toBeEnabled());
  expect(f.requests).toHaveLength(0);
  fireEvent.click(confirm);
  await screen.findByLabelText("联系说明");
}
it("waits for callback initialization and explicit final identity before a real authorized card GET", async () => {
  history.replaceState(null, "", "/auth/callback");
  const pending = deferred<boolean>();
  const f = fixture({ initialize: () => pending.promise });
  render(
    <StrictMode>
      <SessionApplication controller={f.controller} api={f.api} />
    </StrictMode>,
  );
  expect(f.requests).toHaveLength(0);
  expect(
    screen.queryByRole("main", { name: "责任工作台" }),
  ).not.toBeInTheDocument();
  await act(async () => pending.resolve(true));
  const confirm = await screen.findByRole("button", { name: "确认本次身份" });
  await waitFor(() => expect(confirm).toBeEnabled());
  expect(f.requests).toHaveLength(0);
  fireEvent.click(confirm);
  await screen.findByLabelText("联系说明");
  expect(location.pathname).toBe("/workbench");
  expect(f.requests[0].method).toBe("GET");
  expect(f.requests[0].headers.get("X-Appointment-Id")).toBe(taskId);
  expect(f.requests[0].headers.get("Authorization")).toBe(
    "Bearer assembly-initial",
  );
  expect(f.initializations()).toBe(1);
});
it("retains the same editor and live OriginalWrite through renewal and warning, never routing a new marker to body-lost recovery", async () => {
  const f = fixture({
    respond: async (r) =>
      r.method === "POST"
        ? Promise.reject(Error("unknown"))
        : jsonResponse(envelope(5, true)),
  });
  await enter(f);
  const input = screen.getByLabelText("联系说明");
  fireEvent.click(screen.getByRole("button", { name: "保存联系结果" }));
  await screen.findByRole("button", { name: "使用原请求重试" });
  f.rotate("assembly-renewed");
  await act(async () => {
    await f.controller.getValidAccessToken();
    f.warn();
  });
  expect(screen.getByLabelText("联系说明")).toBe(input);
  expect(screen.getByRole("button", { name: "使用原请求重试" })).toBeEnabled();
  expect(
    screen.queryByRole("heading", { name: "核对原操作结果" }),
  ).not.toBeInTheDocument();
  const first = f.requests.find((r) => r.method === "POST")!;
  fireEvent.click(screen.getByRole("button", { name: "使用原请求重试" }));
  await waitFor(() =>
    expect(f.requests.filter((r) => r.method === "POST")).toHaveLength(2),
  );
  const retry = f.requests.filter((r) => r.method === "POST")[1];
  expect(retry.headers.get("Idempotency-Key")).toBe(
    first.headers.get("Idempotency-Key"),
  );
  expect(await retry.clone().text()).toBe(await first.clone().text());
  expect(retry.headers.get("Authorization")).toBe("Bearer assembly-renewed");
});
it("requires explicit selection before querying a body-lost marker and never posts", async () => {
  sessionStorage.setItem(
    markerKey,
    JSON.stringify({
      commandId: selectorId,
      commandType: "RECORD_CONTACT_RESULT",
      actorScopeKey: scope,
      recordedAt: new Date().toISOString(),
    }),
  );
  const f = fixture({
    respond: async (r) =>
      r.url.endsWith("/receipt")
        ? jsonResponse(receipt(selectorId))
        : jsonResponse(envelope()),
  });
  render(<SessionApplication controller={f.controller} api={f.api} />);
  const confirm = await screen.findByRole("button", { name: "确认本次身份" });
  await waitFor(() => expect(confirm).toBeEnabled());
  fireEvent.click(confirm);
  const query = await screen.findByRole("button", { name: "查询原操作结果" });
  await waitFor(() => expect(query).toBeEnabled());
  expect(f.requests).toHaveLength(0);
  fireEvent.click(query);
  fireEvent.click(await screen.findByRole("button", { name: "继续" }));
  await screen.findByLabelText("联系说明");
  expect(f.requests.every((r) => r.method === "GET")).toBe(true);
  expect(f.initializations()).toBe(1);
});
it("makes corrupt marker cleanup reachable before chooser storage admission and still requires selection afterward", async () => {
  sessionStorage.setItem(markerKey, "{broken");
  const f = fixture();
  render(<SessionApplication controller={f.controller} api={f.api} />);
  fireEvent.click(await screen.findByRole("button", { name: "放弃本地线索" }));
  fireEvent.click(screen.getByRole("button", { name: "确认放弃本地线索" }));
  fireEvent.click(await screen.findByRole("button", { name: "继续" }));
  expect(
    await screen.findByRole("button", { name: "确认本次身份" }),
  ).toBeEnabled();
  expect(f.requests).toHaveLength(0);
});
it("does not initialize or read private data on an unknown route", () => {
  history.replaceState(null, "", "/identity/admin");
  const f = fixture();
  render(<SessionApplication controller={f.controller} api={f.api} />);
  expect(
    screen.getByText("此入口暂不可用，请通过工作台入口继续。"),
  ).toBeVisible();
  expect(f.initializations()).toBe(0);
  expect(f.self).toHaveLength(0);
  expect(f.requests).toHaveLength(0);
});

it("enters identity administration through the qualified chooser action and requires a fresh confirmation", async () => {
  const captured: Request[] = [];
  vi.stubGlobal("fetch", async (request: Request) => {
    captured.push(request);
    return new Response(JSON.stringify({ items: [], nextCursor: null }), {
      status: 200,
      headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
    });
  });
  const f = fixture({
    context: {
      ...context,
      canEnterWorkbench: false,
      canEnterIdentityAdmin: true,
    },
  });
  render(<SessionApplication controller={f.controller} api={f.api} />);
  const businessConfirm = await screen.findByRole("button", {
    name: "确认本次身份",
  });
  await waitFor(() => expect(businessConfirm).toBeEnabled());
  expect(captured).toHaveLength(0);

  fireEvent.click(businessConfirm);
  expect(
    await screen.findByText(
      "当前任职不能进入业务工作台；具备管理资格时可使用身份管理地址。",
    ),
  ).toBeVisible();
  const enterAdmin = screen.getByRole("button", { name: "进入身份管理" });
  expect(enterAdmin).toBeEnabled();
  expect(businessConfirm).toBeDisabled();

  fireEvent.click(enterAdmin);
  expect(location.pathname).toBe("/admin/identity/principals");
  expect(
    screen.getByText("即将进入身份管理，请确认本次本人任职。"),
  ).toBeVisible();
  expect(screen.queryByRole("button", { name: "进入身份管理" })).not.toBeInTheDocument();
  const adminConfirm = screen.getByRole("button", { name: "确认本次身份" });
  expect(adminConfirm).toBeEnabled();
  expect(captured).toHaveLength(0);

  fireEvent.click(adminConfirm);
  expect(await screen.findByRole("heading", { name: "用户与身份主体" })).toBeVisible();
  await waitFor(() => expect(captured).toHaveLength(1));
  expect(captured[0].url).toContain("/api/v1/admin/identity/principals?limit=20");
});

it("does not expose a functional identity administration entry to an ordinary appointment", async () => {
  const f = fixture();
  render(<SessionApplication controller={f.controller} api={f.api} />);
  await screen.findByRole("heading", { name: "请选择本次办理身份" });
  expect(screen.queryByRole("button", { name: "进入身份管理" })).not.toBeInTheDocument();
});

it("cannot use a direct SELF administration qualification from a delegated draft", async () => {
  const delegatedScope = "ask1.bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
  const f = fixture({
    context: (init) => {
      const delegated = new Headers(init.headers).get("X-On-Behalf-Appointment-Id");
      return {
        ...context,
        delegatedAppointmentChoices: [{ id: selectorId, label: "合法代办任职" }],
        selectedOnBehalfAppointmentId: delegated,
        actorScopeKey: delegated ? delegatedScope : scope,
        canEnterWorkbench: false,
        canEnterIdentityAdmin: delegated === null,
      };
    },
  });
  render(<SessionApplication controller={f.controller} api={f.api} />);
  const enterAdmin = await screen.findByRole("button", { name: "进入身份管理" });
  expect(enterAdmin).toBeEnabled();
  fireEvent.click(screen.getByRole("radio", { name: "合法代办" }));
  expect(enterAdmin).toBeDisabled();
  fireEvent.change(screen.getByLabelText("被代办任职"), {
    target: { value: selectorId },
  });
  expect(enterAdmin).toBeDisabled();
  fireEvent.click(screen.getByRole("button", { name: "确认本次身份" }));
  expect(
    await screen.findByText(
      "当前任职不能进入业务工作台；请联系律所管理员。",
    ),
  ).toBeVisible();
  expect(screen.queryByRole("button", { name: "进入身份管理" })).not.toBeInTheDocument();
  expect(location.pathname).toBe("/workbench");
});

it("rejects an expired chooser administration action even through its stale element", async () => {
  const f = fixture({
    context: { ...context, canEnterWorkbench: false, canEnterIdentityAdmin: true },
  });
  render(<SessionApplication controller={f.controller} api={f.api} />);
  const staleEntry = await screen.findByRole("button", { name: "进入身份管理" });
  act(() => f.controller.invalidate("EXPIRED"));
  fireEvent.click(staleEntry);
  expect(location.pathname).not.toBe("/admin/identity/principals");
  expect(screen.queryByRole("button", { name: "进入身份管理" })).not.toBeInTheDocument();
});

it("retains a pending recovery marker when requesting administration and recovers before admission", async () => {
  const marker = {
    commandId: selectorId,
    commandType: "CREATE_IDENTITY_PRINCIPAL",
    actorScopeKey: scope,
    recordedAt: new Date().toISOString(),
  };
  sessionStorage.setItem(markerKey, JSON.stringify(marker));
  const captured: Request[] = [];
  vi.stubGlobal("fetch", async (request: Request) => {
    captured.push(request);
    return new Response(JSON.stringify({ items: [], nextCursor: null }), {
      status: 200,
      headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
    });
  });
  const f = fixture({
    context: { ...context, canEnterWorkbench: false, canEnterIdentityAdmin: true },
  });
  render(<SessionApplication controller={f.controller} api={f.api} />);
  fireEvent.click(await screen.findByRole("button", { name: "进入身份管理" }));
  expect(f.controller.recovery.read()).toEqual(marker);
  fireEvent.click(screen.getByRole("button", { name: "确认本次身份" }));
  expect(await screen.findByRole("heading", { name: "核对原操作结果" })).toBeVisible();
  expect(f.controller.recovery.read()).toEqual(marker);
  expect(captured).toHaveLength(0);
});

it("admits an explicitly confirmed direct identity admin without workbench permission", async () => {
  history.replaceState(null, "", "/admin/identity/principals");
  const captured: Request[] = [];
  vi.stubGlobal("fetch", async (request: Request) => {
    captured.push(request);
    return new Response(JSON.stringify({
        items: [
          {
            id: taskId,
            displayName: "陈晓",
            state: "ACTIVE",
            etag: `"identity.${"a".repeat(43)}"`,
          },
        ],
        nextCursor: null,
      }), { status: 200, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } });
  });
  const f = fixture({
    context: {
      ...context,
      canEnterWorkbench: false,
      canEnterIdentityAdmin: true,
    },
  });
  render(<SessionApplication controller={f.controller} api={f.api} />);
  expect(captured).toHaveLength(0);
  const confirm = await screen.findByRole("button", { name: "确认本次身份" });
  await waitFor(() => expect(confirm).toBeEnabled());
  expect(captured).toHaveLength(0);
  fireEvent.click(confirm);
  expect(await screen.findByRole("button", { name: "陈晓" })).toBeVisible();
  expect(captured).toHaveLength(1);
  expect(captured[0].url).toContain(
    "/api/v1/admin/identity/principals?limit=20",
  );
  expect(captured[0].headers.get("Authorization")).toBe(
    "Bearer assembly-initial",
  );
  expect(captured[0].headers.get("X-Appointment-Id")).toBe(taskId);
  expect(captured[0].headers.has("X-On-Behalf-Appointment-Id")).toBe(false);
  expect(screen.queryByText("创建时间")).not.toBeInTheDocument();
});

it("admits identity administration independently when the same direct actor can also enter workbench", async () => {
  history.replaceState(null, "", "/admin/identity/organizations");
  const captured: Request[] = [];
  vi.stubGlobal("fetch", async (request: Request) => {
    captured.push(request);
    return new Response(JSON.stringify({ items: [], nextCursor: null }), {
      status: 200,
      headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
    });
  });
  const f = fixture({ context: { ...context, canEnterWorkbench: true, canEnterIdentityAdmin: true } });
  render(<SessionApplication controller={f.controller} api={f.api} />);
  const confirm = await screen.findByRole("button", { name: "确认本次身份" });
  await waitFor(() => expect(confirm).toBeEnabled());
  fireEvent.click(confirm);
  expect(await screen.findByRole("heading", { name: "组织架构" })).toBeVisible();
  expect(screen.queryByRole("main", { name: "责任工作台" })).not.toBeInTheDocument();
  await waitFor(() => expect(captured).toHaveLength(1));
});

it("keeps a confirmed delegated actor and denies identity administration without a direct fallback", async () => {
  history.replaceState(null, "", "/admin/identity/principals");
  const delegatedScope = "ask1.bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
  const captured: Request[] = [];
  vi.stubGlobal("fetch", async (request: Request) => {
    captured.push(request);
    return new Response(JSON.stringify({
      items: [
        {
          id: selectorId,
          displayName: "不得披露的管理员",
          state: "ACTIVE",
          etag: `"identity.${"b".repeat(43)}"`,
        },
      ],
      nextCursor: null,
    }), {
      status: 200,
      headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
    });
  });
  const f = fixture({
    context: (init) => {
      const headers = new Headers(init.headers);
      const delegated = headers.get("X-On-Behalf-Appointment-Id");
      return {
        ...context,
        selectedAppointmentId: headers.get("X-Appointment-Id") ?? taskId,
        selectedOnBehalfAppointmentId: delegated,
        delegatedAppointmentChoices: [
          { id: selectorId, label: "合法代办任职" },
        ],
        actorScopeKey: delegated ? delegatedScope : scope,
        canEnterWorkbench: false,
        canEnterIdentityAdmin: delegated === null,
      };
    },
  });

  render(<SessionApplication controller={f.controller} api={f.api} />);
  const delegated = await screen.findByRole("radio", { name: "合法代办" });
  expect(f.controller.getSnapshot().context?.canEnterIdentityAdmin).toBe(true);
  fireEvent.click(delegated);
  fireEvent.change(screen.getByLabelText("被代办任职"), {
    target: { value: selectorId },
  });
  const confirm = screen.getByRole("button", { name: "确认本次身份" });
  await waitFor(() => expect(confirm).toBeEnabled());
  fireEvent.click(confirm);

  expect(
    await screen.findByText(
      "当前任职不能进入身份管理；请确认本人任职具备管理资格。",
    ),
  ).toBeVisible();
  expect(captured).toHaveLength(0);
  expect(
    screen.queryByRole("navigation", { name: "身份管理" }),
  ).not.toBeInTheDocument();
  expect(screen.queryByText("不得披露的管理员")).not.toBeInTheDocument();
  expect(f.controller.getSnapshot().context?.selectedOnBehalfAppointmentId).toBe(
    selectorId,
  );
  expect(f.controller.getSnapshot().context?.actorScopeKey).toBe(delegatedScope);
  expect(f.controller.getSnapshot().context?.canEnterIdentityAdmin).toBe(false);
  expect(f.self).toHaveLength(2);
  const selectedRequest = new Headers(f.self[f.self.length - 1]?.headers);
  expect(selectedRequest.get("X-Appointment-Id")).toBe(taskId);
  expect(selectedRequest.get("X-On-Behalf-Appointment-Id")).toBe(selectorId);
});

it("does not disclose an identity list when the confirmed direct appointment lacks admin qualification", async () => {
  history.replaceState(null, "", "/admin/identity/appointments");
  const captured: Request[] = [];
  vi.stubGlobal("fetch", async (request: Request) => {
    captured.push(request);
    return new Response();
  });
  const f = fixture({ context: { ...context, canEnterIdentityAdmin: false } });
  render(<SessionApplication controller={f.controller} api={f.api} />);
  const confirm = await screen.findByRole("button", { name: "确认本次身份" });
  await waitFor(() => expect(confirm).toBeEnabled());
  fireEvent.click(confirm);
  expect(await screen.findByText("当前任职不能进入身份管理；请确认本人任职具备管理资格。")).toBeVisible();
  expect(captured).toHaveLength(0);
  expect(screen.queryByRole("navigation", { name: "身份管理" })).not.toBeInTheDocument();
});

it("returns a recovered identity command to the originally requested static admin page", async () => {
  history.replaceState(null, "", "/admin/identity/principals");
  sessionStorage.setItem(markerKey, JSON.stringify({
    commandId: selectorId,
    commandType: "CREATE_IDENTITY_PRINCIPAL",
    actorScopeKey: scope,
    recordedAt: new Date().toISOString(),
  }));
  const captured: Request[] = [];
  vi.stubGlobal("fetch", async (request: Request) => {
    captured.push(request);
    return new Response(JSON.stringify({ items: [], nextCursor: null }), {
      status: 200,
      headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
    });
  });
  const f = fixture({
    context: { ...context, canEnterWorkbench: false, canEnterIdentityAdmin: true },
    respond: async () => jsonResponse({
      ...receipt(selectorId),
      resultFact: { factType: "IDENTITY_PRINCIPAL", factRef: "safe-result", revision: 1 },
    }),
  });
  render(<SessionApplication controller={f.controller} api={f.api} />);
  const confirm = await screen.findByRole("button", { name: "确认本次身份" });
  await waitFor(() => expect(confirm).toBeEnabled());
  fireEvent.click(confirm);
  const query = await screen.findByRole("button", { name: "查询原操作结果" });
  await waitFor(() => expect(query).toBeEnabled());
  expect(captured).toHaveLength(0);
  fireEvent.click(query);
  fireEvent.click(await screen.findByRole("button", { name: "继续" }));
  expect(await screen.findByRole("heading", { name: "用户与身份主体" })).toBeVisible();
  expect(captured).toHaveLength(1);
});

it("T9-L07 keeps dirty input through real renewal and sends only an explicitly confirmed write with the new token", async () => {
  const data = envelope(5, true);
  data.currentCard!.commandForm.fields.find(
    (v) => v.name === "resultSummary",
  )!.label = "结果说明";
  data.currentCard!.primaryCommand.label = "记录联系结果";
  const f = fixture({
    respond: async (r) => {
      if (r.method === "PUT") {
        const body = await r.clone().json();
        return jsonResponse(
          {
            receipt: receipt(r.headers.get("Idempotency-Key")!, "ACTION_DRAFT"),
            draft: { ...data.currentCard!.actionDraft!, values: body.values },
            preconditions: tags,
          },
          200,
          tags.draftETag,
        );
      }
      if (r.method === "POST")
        return jsonResponse(receipt(r.headers.get("Idempotency-Key")!));
      return jsonResponse(data);
    },
  });
  render(<SessionApplication controller={f.controller} api={f.api} />);
  const confirm = await screen.findByRole("button", { name: "确认本次身份" });
  await waitFor(() => expect(confirm).toBeEnabled());
  fireEvent.click(confirm);
  const input = await screen.findByLabelText("结果说明");
  fireEvent.change(input, { target: { value: "尚未保存的输入" } });
  const renewal = deferred<string>();
  f.oidc.getValidAccessToken = () => renewal.promise;
  let pending!: Promise<string>;
  act(() => {
    pending = f.controller.getValidAccessToken();
  });
  await act(async () => {
    renewal.resolve("assembly-new-token");
    await pending;
  });
  expect(screen.getByLabelText("结果说明")).toBe(input);
  expect(input).toHaveValue("尚未保存的输入");
  expect(screen.getByRole("button", { name: "记录联系结果" })).toBeEnabled();
  expect(f.requests.filter((r) => r.method === "POST")).toHaveLength(0);
  fireEvent.click(screen.getByRole("button", { name: "保存草稿" }));
  await waitFor(() =>
    expect(screen.getByRole("button", { name: "记录联系结果" })).toBeEnabled(),
  );
  fireEvent.click(screen.getByRole("button", { name: "记录联系结果" }));
  await waitFor(() =>
    expect(f.requests.filter((r) => r.method === "POST")).toHaveLength(1),
  );
  const post = f.requests.find((r) => r.method === "POST")!;
  expect(post.headers.get("Authorization")).toBe("Bearer assembly-new-token");
  expect(post.headers.get("X-Appointment-Id")).toBe(taskId);
  expect(post.headers.get("If-Match")).toBe(tags.taskETag);
  expect((await post.clone().json()).resultSummary).toBe("尚未保存的输入");
});

it("keeps pending delegated recovery through intermediate own selection and authorizes only the explicitly selected pair", async () => {
  const delegatedScope = "ask1.bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
  sessionStorage.setItem(
    markerKey,
    JSON.stringify({
      commandId: selectorId,
      commandType: "RECORD_CONTACT_RESULT",
      actorScopeKey: delegatedScope,
      recordedAt: new Date().toISOString(),
    }),
  );
  const f = fixture({
    context: (init) => {
      const headers = new Headers(init.headers),
        own = headers.get("X-Appointment-Id"),
        delegated = headers.get("X-On-Behalf-Appointment-Id");
      const choices = [
        { id: taskId, label: "本人任职一" },
        { id: selectorId, label: "本人任职二" },
      ];
      return own
        ? {
            ...context,
            appointmentChoices: choices,
            delegatedAppointmentChoices: [
              { id: selectorId, label: "合法代办任职" },
            ],
            selectedOnBehalfAppointmentId: delegated,
            actorScopeKey: delegated ? delegatedScope : scope,
          }
        : {
            ...context,
            state: "APPOINTMENT_SELECTION_REQUIRED",
            appointmentChoices: choices,
            selectedAppointmentId: null,
            actorScopeKey: null,
            canEnterWorkbench: false,
          };
    },
    respond: async () => jsonResponse({}, 404),
  });
  render(<SessionApplication controller={f.controller} api={f.api} />);
  const own = await screen.findByLabelText("本人任职");
  expect(own).toHaveValue("");
  expect(screen.getByRole("button", { name: "确认本次身份" })).toBeDisabled();
  fireEvent.change(own, { target: { value: taskId } });
  fireEvent.click(screen.getByRole("button", { name: "确认任职" }));
  const delegated = screen.getByRole("radio", { name: "合法代办" });
  await waitFor(() => expect(delegated).toBeEnabled());
  expect(f.controller.recovery.read()?.actorScopeKey).toBe(delegatedScope);
  expect(f.requests).toHaveLength(0);
  fireEvent.click(delegated);
  fireEvent.change(screen.getByLabelText("被代办任职"), {
    target: { value: selectorId },
  });
  fireEvent.click(screen.getByRole("button", { name: "确认本次身份" }));
  const query = await screen.findByRole("button", { name: "查询原操作结果" });
  await waitFor(() => expect(query).toBeEnabled());
  fireEvent.click(query);
  await waitFor(() => expect(f.requests).toHaveLength(1));
  expect(f.requests[0].headers.get("X-Appointment-Id")).toBe(taskId);
  expect(f.requests[0].headers.get("X-On-Behalf-Appointment-Id")).toBe(
    selectorId,
  );
  expect(f.requests[0].method).toBe("GET");
});

it("does not grant business entry after Identity-only receipt recovery", async () => {
  sessionStorage.setItem(
    markerKey,
    JSON.stringify({
      commandId: selectorId,
      commandType: "CREATE_IDENTITY_PRINCIPAL",
      actorScopeKey: scope,
      recordedAt: new Date().toISOString(),
    }),
  );
  const f = fixture({
    context: {
      ...context,
      canEnterWorkbench: false,
      canEnterIdentityAdmin: true,
    },
    respond: async () =>
      jsonResponse({
        ...receipt(selectorId),
        resultFact: {
          factType: "IDENTITY_PRINCIPAL",
          factRef: "safe-identity-result",
          revision: 1,
        },
      }),
  });
  render(<SessionApplication controller={f.controller} api={f.api} />);
  const confirm = await screen.findByRole("button", { name: "确认本次身份" });
  await waitFor(() => expect(confirm).toBeEnabled());
  fireEvent.click(confirm);
  const query = await screen.findByRole("button", { name: "查询原操作结果" });
  await waitFor(() => expect(query).toBeEnabled());
  fireEvent.click(query);
  fireEvent.click(await screen.findByRole("button", { name: "继续" }));
  expect(
    await screen.findByText(
      "当前任职不能进入业务工作台；具备管理资格时可使用身份管理地址。",
    ),
  ).toBeVisible();
  expect(f.requests).toHaveLength(1);
  expect(
    screen.queryByRole("main", { name: "责任工作台" }),
  ).not.toBeInTheDocument();
});

it("clears the workbench on logout and reports IdP logout uncertainty without automatic initialization", async () => {
  const f = fixture();
  await enter(f);
  fireEvent.change(screen.getByLabelText("联系说明"), {
    target: { value: "private-dirty" },
  });
  fireEvent.click(screen.getByRole("button", { name: "退出" }));
  expect(screen.queryByLabelText("联系说明")).not.toBeInTheDocument();
  expect(
    await screen.findByText("已退出本页面，统一会话退出尚未确认。"),
  ).toBeVisible();
  await act(async () => {});
  expect(f.initializations()).toBe(1);
  expect(location.pathname).toBe("/login");
  expect(document.body.textContent).not.toContain("private-dirty");
});

it("clears expired private content and rejects a late POST before same-Actor marker-only recovery", async () => {
  const pending = deferred<Response>();
  const f = fixture({
    respond: async (r) =>
      r.method === "POST"
        ? pending.promise
        : r.url.endsWith("/receipt")
          ? jsonResponse(
              receipt(JSON.parse(sessionStorage.getItem(markerKey)!).commandId),
            )
          : jsonResponse(envelope(5, true)),
  });
  await enter(f);
  fireEvent.click(screen.getByRole("button", { name: "保存联系结果" }));
  await waitFor(() =>
    expect(f.requests.filter((r) => r.method === "POST")).toHaveLength(1),
  );
  const post = f.requests.find((r) => r.method === "POST")!;
  act(() => f.controller.invalidate("EXPIRED"));
  await act(async () =>
    pending.resolve(
      jsonResponse(receipt(post.headers.get("Idempotency-Key")!)),
    ),
  );
  expect(screen.queryByLabelText("联系说明")).not.toBeInTheDocument();
  expect(f.controller.recovery.read()).not.toBeNull();
  await act(async () => f.controller.initialize());
  const confirm = await screen.findByRole("button", { name: "确认本次身份" });
  await waitFor(() => expect(confirm).toBeEnabled());
  fireEvent.click(confirm);
  const query = await screen.findByRole("button", { name: "查询原操作结果" });
  await waitFor(() => expect(query).toBeEnabled());
  expect(
    screen.queryByRole("button", { name: "使用原请求重试" }),
  ).not.toBeInTheDocument();
  fireEvent.click(query);
  await screen.findByRole("button", { name: "继续" });
  expect(f.requests.filter((r) => r.method === "POST")).toHaveLength(1);
});

it("clears private data on another tab's logout and refuses history return without reinitializing", async () => {
  const f = fixture();
  await enter(f);
  const channel = new BroadcastChannel("r1.session-logout");
  try {
    channel.postMessage("LOGOUT");
    await screen.findByText("已退出本页面，请重新登录。");
    expect(screen.queryByLabelText("联系说明")).not.toBeInTheDocument();
    const requests = f.requests.length;
    act(() => {
      history.replaceState(null, "", "/workbench");
      window.dispatchEvent(new PopStateEvent("popstate"));
    });
    await waitFor(() => expect(location.pathname).toBe("/login"));
    expect(f.requests).toHaveLength(requests);
    expect(f.initializations()).toBe(1);
  } finally {
    channel.close();
  }
});

it("keeps no-appointment and unqualified identity states distinct from an empty workbench", async () => {
  const f = fixture({
    context: {
      ...context,
      state: "NO_APPOINTMENT",
      appointmentChoices: [],
      selectedAppointmentId: null,
      actorScopeKey: null,
      canEnterWorkbench: false,
    },
  });
  render(<SessionApplication controller={f.controller} api={f.api} />);
  await screen.findByRole("heading", { name: "请选择本次办理身份" });
  expect(screen.getByRole("button", { name: "确认本次身份" })).toBeDisabled();
  expect(screen.queryByText("当前暂无需要处理的责任")).not.toBeInTheDocument();
  expect(f.requests).toHaveLength(0);
});

it("removes old card and waits for final confirmation again on a requested identity switch", async () => {
  const f = fixture();
  await enter(f);
  fireEvent.change(screen.getByLabelText("联系说明"), {
    target: { value: "old-private-draft" },
  });
  const before = f.requests.length;
  fireEvent.click(screen.getByRole("button", { name: "切换任职" }));
  expect(screen.queryByLabelText("联系说明")).not.toBeInTheDocument();
  expect(
    await screen.findByRole("button", { name: "确认本次身份" }),
  ).toBeEnabled();
  expect(f.requests).toHaveLength(before);
  expect(document.body.textContent).not.toContain("old-private-draft");
  expect(f.initializations()).toBe(1);
});

it("cannot inherit workbench admission when its controller is replaced", async () => {
  const f = fixture(),
    next = fixture();
  const view = render(
    <SessionApplication controller={f.controller} api={f.api} />,
  );
  const confirm = await screen.findByRole("button", { name: "确认本次身份" });
  await waitFor(() => expect(confirm).toBeEnabled());
  fireEvent.click(confirm);
  await screen.findByLabelText("联系说明");
  view.rerender(
    <SessionApplication controller={next.controller} api={next.api} />,
  );
  expect(screen.queryByLabelText("联系说明")).not.toBeInTheDocument();
  expect(
    await screen.findByRole("button", { name: "确认本次身份" }),
  ).toBeEnabled();
  expect(next.requests).toHaveLength(0);
});

it("can explicitly clear an invalid clue before choosing among multiple own appointments without an Actor", async () => {
  sessionStorage.setItem(markerKey, "{broken");
  const f = fixture({
    context: {
      ...context,
      state: "APPOINTMENT_SELECTION_REQUIRED",
      appointmentChoices: [
        { id: taskId, label: "本人任职一" },
        { id: selectorId, label: "本人任职二" },
      ],
      selectedAppointmentId: null,
      actorScopeKey: null,
      canEnterWorkbench: false,
    },
  });
  render(<SessionApplication controller={f.controller} api={f.api} />);
  const abandon = await screen.findByRole("button", { name: "放弃本地线索" });
  fireEvent.click(abandon);
  fireEvent.click(screen.getByRole("button", { name: "取消放弃" }));
  expect(sessionStorage.getItem(markerKey)).toBe("{broken");
  fireEvent.click(abandon);
  fireEvent.click(screen.getByRole("button", { name: "确认放弃本地线索" }));
  fireEvent.click(await screen.findByRole("button", { name: "继续" }));
  const own = await screen.findByLabelText("本人任职");
  expect(own).toHaveValue("");
  fireEvent.change(own, { target: { value: taskId } });
  expect(screen.getByRole("button", { name: "确认任职" })).toBeEnabled();
  expect(f.requests).toHaveLength(0);
});

it.each(["replacement", "expiry"] as const)(
  "rejects stale invalid-clue cleanup without an Actor after %s",
  async (change) => {
    sessionStorage.setItem(markerKey, "{broken");
    const f = fixture({
      context: {
        ...context,
        state: "APPOINTMENT_SELECTION_REQUIRED",
        appointmentChoices: [
          { id: taskId, label: "本人任职一" },
          { id: selectorId, label: "本人任职二" },
        ],
        selectedAppointmentId: null,
        actorScopeKey: null,
        canEnterWorkbench: false,
      },
    });
    render(<SessionApplication controller={f.controller} api={f.api} />);
    fireEvent.click(
      await screen.findByRole("button", { name: "放弃本地线索" }),
    );
    const confirm = screen.getByRole("button", { name: "确认放弃本地线索" });
    const replacement = JSON.stringify({
      commandId: selectorId,
      commandType: "RECORD_CONTACT_RESULT",
      actorScopeKey: scope,
      recordedAt: new Date().toISOString(),
    });
    if (change === "replacement")
      sessionStorage.setItem(markerKey, replacement);
    else act(() => f.controller.invalidate("EXPIRED"));
    fireEvent.click(confirm);
    expect(sessionStorage.getItem(markerKey)).toBe(
      change === "replacement" ? replacement : "{broken",
    );
    expect(f.requests).toHaveLength(0);
    expect(
      screen.queryByRole("main", { name: "责任工作台" }),
    ).not.toBeInTheDocument();
  },
);

it("enters real intake from the workbench and protects dirty contents on browser navigation", async () => {
  const f = fixture();
  const intakeApi = createLeadIntakeApi(f.controller.recovery, async () => new Response(JSON.stringify({ sources: [{ sourceAccountCode: "SALES", displayName: "客户转介绍", sourceChannelCode: "MANUAL", serviceCategoryCode: "CONSULTATION", jurisdictionCode: "CN", urgencyCode: "NORMAL" }] }), { headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } }));
  render(<SessionApplication controller={f.controller} api={f.api} intakeApi={intakeApi} />);
  const confirm = await screen.findByRole("button", { name: "确认本次身份" }); await waitFor(() => expect(confirm).toBeEnabled()); fireEvent.click(confirm);
  const entry = await screen.findByRole("button", { name: "录入线索" }); await waitFor(() => expect(entry).toBeEnabled()); fireEvent.click(entry);
  fireEvent.change(await screen.findByLabelText("客户名称"), { target: { value: "未保存客户" } });
  expect(location.pathname).toBe("/business/leads/intake");
  act(() => { history.replaceState(null, "", "/workbench"); window.dispatchEvent(new PopStateEvent("popstate")); });
  expect(screen.getByRole("dialog", { name: "放弃本次未保存内容？" })).toBeVisible();
  fireEvent.click(screen.getByRole("button", { name: "继续填写" }));
  expect(screen.getByLabelText("客户名称")).toHaveValue("未保存客户");
  act(() => f.controller.invalidate("EXPIRED"));
  expect(screen.queryByLabelText("客户名称")).not.toBeInTheDocument();
});

it("continues from confirmed intake to its exact task and completes it through the shared confirmation", async () => {
 let completed = false;
 const body = envelope(5, true), ref = "a".repeat(43);
 const f = fixture({respond: async request => {
  if (request.method === "POST") { completed = true; return jsonResponse(receipt(request.headers.get("Idempotency-Key")!)); }
  return jsonResponse(completed ? {...body, currentCard: null, recommendedTaskId: null, myTasks: [], chatComposer: {...body.chatComposer, targetTaskId: null, enabled: false}} : body);
 }});
 const intakeApi = createLeadIntakeApi(f.controller.recovery, async request => {
  const payload = request.url.endsWith("intake-sources") ? { sources: [{ sourceAccountCode: "SALES", displayName: "客户转介绍", sourceChannelCode: "MANUAL", serviceCategoryCode: "CONSULTATION", jurisdictionCode: "CN", urgencyCode: "NORMAL" }] }
   : request.method === "POST" ? { ...receipt(request.headers.get("Idempotency-Key")!), resultFact: { factType: "LEAD", factRef: ref, revision: 0 } }
   : { ...body, myTasks: [{ taskId, businessPurpose: { code: "CONTACT_LEAD", label: "记录联系结果" }, priority: "NORMAL", timeHint: "今天内", subjectFactRef: ref }] };
  return new Response(JSON.stringify(payload), { status: request.method === "POST" ? 201 : 200, headers: { "Content-Type": "application/json", "Cache-Control": "no-store", "Location": `/api/v1/commands/${request.headers.get("Idempotency-Key")}/receipt` } });
 });
 render(<SessionApplication controller={f.controller} api={f.api} intakeApi={intakeApi} />);
 const confirm = await screen.findByRole("button", { name: "确认本次身份" }); await waitFor(() => expect(confirm).toBeEnabled()); fireEvent.click(confirm);
 const entry = await screen.findByRole("button", { name: "录入线索" }); await waitFor(() => expect(entry).toBeEnabled()); fireEvent.click(entry);
 fireEvent.change(await screen.findByLabelText("需求描述"), { target: { value: "客户需求" } }); fireEvent.click(screen.getByRole("button", { name: "保存线索" }));
 fireEvent.click(await screen.findByRole("button", { name: "继续办理：记录联系结果" }));
 await waitFor(() => expect(f.requests.some(r => new URL(r.url).searchParams.get("taskId") === taskId)).toBe(true));
 expect(location.pathname).toBe("/workbench");
 const complete = await screen.findByRole("button", {name: "保存联系结果"});
 await waitFor(() => expect(complete).toBeEnabled());
 fireEvent.click(complete);
 await waitFor(() => expect(screen.queryByRole("button", {name: "保存联系结果"})).toBeNull());
 const commands = f.requests.filter(r => r.method === "POST");
 expect(commands).toHaveLength(1);
 expect(new URL(commands[0].url).pathname).toContain(taskId);
 expect(f.requests.at(-1)?.method).toBe("GET");
 expect(f.controller.recovery.read()).toBeNull();
});
it.each(['/management/team-tasks','/management/team-tasks/operations'])('admits management-only direct HUMAN session at %s',async path=>{
 history.replaceState(null,'',path);
 const f=fixture({context:{...context,canEnterWorkbench:false,canManageOwnerExceptions:true}});
 const fetcher=vi.fn().mockResolvedValue(jsonResponse({items:[]}));vi.stubGlobal('fetch',fetcher);
 render(<SessionApplication controller={f.controller} api={f.api}/>);
 const confirm=await screen.findByRole('button',{name:'确认本次身份'});await waitFor(()=>expect(confirm).toBeEnabled());fireEvent.click(confirm);
 await screen.findByRole('heading',{name:'团队待办'});
 await screen.findByRole('heading',{name:'当前授权范围内没有待处置异常'});
 expect(f.requests).toHaveLength(0);expect(fetcher).toHaveBeenCalledTimes(1);
});
it('does not admit manager routes from workbench qualification alone',async()=>{
 history.replaceState(null,'','/management/team-tasks');const f=fixture();render(<SessionApplication controller={f.controller} api={f.api}/>);
 const confirm=await screen.findByRole('button',{name:'确认本次身份'});await waitFor(()=>expect(confirm).toBeEnabled());fireEvent.click(confirm);
 await screen.findByText('当前任职不能进入团队待办，请确认本人任职具备管理资格。');expect(f.requests).toHaveLength(0);
});


it.each(['/management/leads','/management/team-tasks','/management/team-tasks/operations','/management/opportunities','/business/leads/intake','/admin/identity/organizations'])('returns to %s after an actual signed-out entry and callback without admitting an appointment', async path => {
 history.replaceState(null, '', path);
 const signedOut=fixture({initialize:async()=>false});
 const first=render(<SessionApplication controller={signedOut.controller} api={signedOut.api}/>);
 await screen.findByRole('button',{name:'登录工作台'});
 await waitFor(()=>expect(location.pathname).toBe('/login'));
 first.unmount();
 history.replaceState(null,'','/auth/callback');
 const authenticated=fixture();
 render(<SessionApplication controller={authenticated.controller} api={authenticated.api}/>);
 await screen.findByRole('button',{name:'确认本次身份'});
 await waitFor(()=>expect(location.pathname).toBe(path));
 expect(screen.queryByRole('main',{name:'责任工作台'})).not.toBeInTheDocument();
 expect(authenticated.requests).toHaveLength(0);
});

it('admits an independent management reader without workbench or contract body rights',async()=>{
 history.replaceState(null,'','/management/contracts');
 const reads=vi.fn().mockResolvedValue(jsonResponse({items:[],nextCursor:null}));vi.stubGlobal('fetch',reads);
 const f=fixture({context:{...context,canEnterWorkbench:false,canReadOpportunityLedger:false,canReadBusinessManagement:true}});
 render(<SessionApplication controller={f.controller} api={f.api}/>);
 const confirm=await screen.findByRole('button',{name:'确认本次身份'});await waitFor(()=>expect(confirm).toBeEnabled());fireEvent.click(confirm);
 await screen.findByRole('heading',{name:'合同台账'});await screen.findByText('暂无匹配记录。');
 expect(screen.queryByRole('button',{name:'我的待办'})).toBeNull();expect(screen.queryByRole('button',{name:'商机台账'})).toBeNull();
 expect(reads).toHaveBeenCalled();expect(reads.mock.calls.every(args=>String(args[0]).startsWith('/api/v1/business-management/payments'))).toBe(true);expect(f.requests).toHaveLength(0);
 act(()=>f.controller.invalidate('EXPIRED'));expect(screen.queryByRole('heading',{name:'合同台账'})).toBeNull();
});

it('opens the authorized contract view when returning from overview to the contract ledger',async()=>{
 history.replaceState(null,'','/management/overview');
 const reads=vi.fn().mockImplementation(async url=>jsonResponse(String(url)==='/api/v1/business-overview'?{month:'2026-10',asOf:'2026-10-01T00:00:00Z',metrics:[]}:{items:[],nextCursor:null}));vi.stubGlobal('fetch',reads);
 const f=fixture({context:{...context,canEnterWorkbench:false,canReadBusinessOverview:true,canReadBusinessManagement:true,businessManagementViews:['contracts','payments','transfer']}});
 render(<SessionApplication controller={f.controller} api={f.api}/>);
 const confirm=await screen.findByRole('button',{name:'确认本次身份'});await waitFor(()=>expect(confirm).toBeEnabled());fireEvent.click(confirm);
 await screen.findByRole('heading',{name:'经营概览'});fireEvent.click(screen.getByRole('button',{name:'合同台账'}));
 await screen.findByRole('heading',{name:'合同台账'});await waitFor(()=>expect(reads.mock.calls.some(args=>String(args[0]).startsWith('/api/v1/contracts'))).toBe(true));
 expect(reads.mock.calls.some(args=>String(args[0]).startsWith('/api/v1/business-management/payments'))).toBe(false);
 expect(location.pathname).toBe('/management/contracts');expect(f.requests).toHaveLength(0);
});

it('opens transfer directly for a transfer-only reader without probing payment or contract data',async()=>{
 history.replaceState(null,'','/management/contracts');const reads=vi.fn().mockResolvedValue(jsonResponse({items:[],nextCursor:null}));vi.stubGlobal('fetch',reads);
 const f=fixture({context:{...context,canEnterWorkbench:false,canReadBusinessManagement:true,businessManagementViews:['transfer']}});render(<SessionApplication controller={f.controller} api={f.api}/>);
 const confirm=await screen.findByRole('button',{name:'确认本次身份'});await waitFor(()=>expect(confirm).toBeEnabled());fireEvent.click(confirm);await screen.findByText('暂无匹配记录。');
 expect(screen.getByLabelText('查看内容')).toHaveValue('transfer');expect(screen.queryByRole('option',{name:'收款'})).toBeNull();expect(reads.mock.calls.every(args=>String(args[0]).startsWith('/api/v1/business-management/transfer'))).toBe(true);
});

it.each(['/management/team-tasks','/workbench'])('admits team-only readers from %s without workcard or exception requests',async(path)=>{
 history.replaceState(null,'',path);const reads=vi.fn().mockResolvedValue(jsonResponse({items:[],nextCursor:null}));vi.stubGlobal('fetch',reads);
 const f=fixture({context:{...context,canEnterWorkbench:false,canReadTeamTasks:true,canManageOwnerExceptions:false}});
 render(<SessionApplication controller={f.controller} api={f.api}/>);
 const confirm=await screen.findByRole('button',{name:'确认本次身份'});await waitFor(()=>expect(confirm).toBeEnabled());fireEvent.click(confirm);
 await screen.findByRole('heading',{name:'团队待办'});await screen.findByText('当前筛选下没有事项，不代表全部业务已完成。');
 expect(screen.getByLabelText('查看内容')).toHaveValue('tasks');expect(screen.queryByRole('button',{name:'我的待办'})).toBeNull();expect(f.requests).toHaveLength(0);
 expect(reads.mock.calls.every(args=>String(args[0]).startsWith('/api/v1/team-management/tasks'))).toBe(true);
 act(()=>f.controller.invalidate('EXPIRED'));expect(screen.queryByRole('heading',{name:'团队待办'})).toBeNull();
});

it('returns from business management to team management for a read-only dual-scope reader',async()=>{
 history.replaceState(null,'','/management/contracts');const reads=vi.fn().mockResolvedValue(jsonResponse({items:[],nextCursor:null}));vi.stubGlobal('fetch',reads);
 const f=fixture({context:{...context,canEnterWorkbench:false,canReadOpportunityLedger:false,canReadBusinessManagement:true,canReadTeamTasks:true,canManageOwnerExceptions:false}});
 render(<SessionApplication controller={f.controller} api={f.api}/>);const confirm=await screen.findByRole('button',{name:'确认本次身份'});await waitFor(()=>expect(confirm).toBeEnabled());fireEvent.click(confirm);
 await screen.findByRole('heading',{name:'合同台账'});fireEvent.click(await screen.findByRole('button',{name:'团队待办'}));
 await screen.findByRole('heading',{name:'团队待办'});expect(location.pathname).toBe('/management/team-tasks');expect(screen.queryByRole('button',{name:'我的待办'})).toBeNull();expect(f.requests).toHaveLength(0);
});

it.each(['/management/leads','/workbench'])('admits a lead-only reader from %s without requesting business tasks',async(path)=>{
 history.replaceState(null,'',path);const reads=vi.fn().mockImplementation(async path=>jsonResponse(String(path).endsWith('/sources')?{items:[]}:{items:[],nextCursor:null}));vi.stubGlobal('fetch',reads);
 const f=fixture({context:{...context,canEnterWorkbench:false,canReadLeadManagement:true,canReadTeamTasks:false,canManageOwnerExceptions:false}});
 render(<SessionApplication controller={f.controller} api={f.api}/>);const confirm=await screen.findByRole('button',{name:'确认本次身份'});await waitFor(()=>expect(confirm).toBeEnabled());fireEvent.click(confirm);
 await screen.findByRole('heading',{name:'客户与线索'});await screen.findByText('当前筛选下没有有权记录，可继续翻页核对。');expect(screen.queryByRole('button',{name:'我的待办'})).toBeNull();expect(f.requests).toHaveLength(0);expect(reads.mock.calls.every(args=>String(args[0]).startsWith('/api/v1/lead-management/'))).toBe(true);
 act(()=>f.controller.invalidate('EXPIRED'));expect(screen.queryByRole('heading',{name:'客户与线索'})).toBeNull();
});
it('navigates from the read-only team view into the independently authorized lead view',async()=>{
 history.replaceState(null,'','/management/team-tasks');vi.stubGlobal('fetch',vi.fn().mockImplementation(async path=>jsonResponse(String(path).endsWith('/sources')?{items:[]}:{items:[],nextCursor:null})));
 const f=fixture({context:{...context,canEnterWorkbench:false,canReadTeamTasks:true,canReadLeadManagement:true,canManageOwnerExceptions:false}});render(<SessionApplication controller={f.controller} api={f.api}/>);const confirm=await screen.findByRole('button',{name:'确认本次身份'});await waitFor(()=>expect(confirm).toBeEnabled());fireEvent.click(confirm);
 await screen.findByRole('heading',{name:'团队待办'});fireEvent.click(screen.getByRole('button',{name:'客户与线索'}));await screen.findByRole('heading',{name:'客户与线索'});expect(location.pathname).toBe('/management/leads');expect(f.requests).toHaveLength(0);
});
it.each(['/management/overview','/workbench'])('admits a read-only overview entry from %s and clears it on identity expiry',async(path)=>{
 history.replaceState(null,'',path);const metrics=[['leads','新增线索'],['opportunities','有效商机'],['signedContracts','签署归档合同'],['acceptedMatters','已接收案件'],['overdueTasks','当前逾期待办']].map(([key,label])=>({key,label,status:'AVAILABLE',count:0}));const reads=vi.fn().mockResolvedValue(jsonResponse({month:'2026-09',asOf:'2026-09-28T01:00:00Z',metrics}));vi.stubGlobal('fetch',reads);
 const f=fixture({context:{...context,canEnterWorkbench:false,canReadBusinessOverview:true,canReadOpportunityLedger:false,canReadBusinessManagement:false,canReadTeamTasks:false,canReadLeadManagement:false,canManageOwnerExceptions:false}});render(<SessionApplication controller={f.controller} api={f.api}/>);const confirm=await screen.findByRole('button',{name:'确认本次身份'});await waitFor(()=>expect(confirm).toBeEnabled());fireEvent.click(confirm);
 await screen.findByRole('heading',{name:'经营概览'});await screen.findByText('新增线索');expect(f.requests).toHaveLength(0);expect(reads.mock.calls.every(args=>String(args[0])==='/api/v1/business-overview')).toBe(true);act(()=>f.controller.invalidate('EXPIRED'));expect(screen.queryByText('新增线索')).toBeNull();
});

it.each([true,false])('keeps all common navigation entries across leads, overview and team (team reader %s)',async(canReadTeamTasks)=>{
 history.replaceState(null,'','/management/leads');
 vi.stubGlobal('fetch',vi.fn().mockImplementation(async path=>jsonResponse(String(path)==='/api/v1/business-overview'?{month:'2026-09',asOf:'2026-09-28T01:00:00Z',metrics:[]}:String(path).endsWith('/sources')?{items:[]}:{items:[],nextCursor:null})));
 const f=fixture({context:{...context,canEnterWorkbench:true,canReadLeadManagement:true,canReadBusinessOverview:true,canReadOpportunityLedger:true,canReadBusinessManagement:true,canReadTeamTasks,canManageOwnerExceptions:true}});
 render(<SessionApplication controller={f.controller} api={f.api}/>);
 const confirm=await screen.findByRole('button',{name:'确认本次身份'});await waitFor(()=>expect(confirm).toBeEnabled());fireEvent.click(confirm);
 const names=['客户与线索','经营概览','来源与责任','商机台账','合同台账','团队待办','我的待办'];
 await screen.findByRole('heading',{name:'客户与线索'});
 const labels=()=>Array.from(document.querySelectorAll('aside.sidebar button')).map(b=>b.textContent);
 expect(labels()).toEqual(names);
 const identity=()=>document.querySelector('.session-identity')?.textContent;
 expect(identity()).toBe('合成入口办理人 · 业务一组 · 线索专员');
 const push=vi.spyOn(history,'pushState');
 fireEvent.click(screen.getByRole('button',{name:'经营概览'}));await screen.findByRole('heading',{name:'经营概览'});
 expect(location.pathname).toBe('/management/overview');expect(labels()).toEqual(names);expect(identity()).toBe('合成入口办理人 · 业务一组 · 线索专员');
 fireEvent.click(screen.getByRole('button',{name:'团队待办'}));await screen.findByRole('heading',{name:'团队待办'});expect(labels()).toEqual(names);expect(identity()).toBe('合成入口办理人 · 业务一组 · 线索专员');
 expect(document.querySelector('aside.sidebar a[href="/workbench"]')).toBeNull();
 await waitFor(()=>expect(screen.getByRole('button',{name:'来源与责任'})).toBeEnabled());
 fireEvent.click(screen.getByRole('button',{name:'来源与责任'}));await screen.findByRole('heading',{name:'来源与责任'});expect(labels()).toEqual(names);
 expect(push).toHaveBeenCalled();push.mockRestore();
 act(()=>history.back());await waitFor(()=>expect(location.pathname).toBe('/management/team-tasks'));await screen.findByRole('heading',{name:'团队待办'});expect(labels()).toEqual(names);expect(identity()).toBe('合成入口办理人 · 业务一组 · 线索专员');
});

it.each(["button", "broadcast"] as const)("explicit admin logout via %s must not send the next ordinary account back to identity administration", async action => {
  history.replaceState(null, "", "/admin/identity/principals");
  sessionStorage.setItem('ols.login-destination.v1', JSON.stringify({path:'/admin/identity/organizations',savedAt:Date.now()}));
  const admin = fixture({context:{...context,canEnterWorkbench:false,canEnterIdentityAdmin:true}});
  const identity = identityFixture('/admin/identity/principals',{recovery:admin.controller.recovery});
  const first = render(<SessionApplication controller={admin.controller} api={admin.api} identityApi={identity.api}/>);
  const confirm = await screen.findByRole('button',{name:'确认本次身份'});
  await waitFor(()=>expect(confirm).toBeEnabled()); fireEvent.click(confirm);
  await screen.findByRole('button',{name:'新增身份主体'});
  if(action==='button') fireEvent.click(screen.getByRole('button',{name:'退出'}));
  else {
    const channel=new BroadcastChannel('r1.session-logout');
    channel.postMessage('LOGOUT');
    await screen.findByText('已退出本页面，请重新登录。');
    channel.close();
  }
  await waitFor(()=>expect(location.pathname).toBe('/login'));
  expect(sessionStorage.getItem('ols.login-destination.v1')).toBeNull();
  first.unmount();
  history.replaceState(null,'','/auth/callback');
  const manager=fixture();
  render(<SessionApplication controller={manager.controller} api={manager.api}/>);
  const nextConfirm=await screen.findByRole('button',{name:'确认本次身份'});
  await waitFor(()=>expect(nextConfirm).toBeEnabled());
  expect(location.pathname).toBe('/workbench');
  expect(manager.requests).toHaveLength(0);
  fireEvent.click(nextConfirm);
  await screen.findByRole('main',{name:'责任工作台'});
  expect(screen.queryByText('当前任职不能进入身份管理；请确认本人任职具备管理资格。')).toBeNull();
});
