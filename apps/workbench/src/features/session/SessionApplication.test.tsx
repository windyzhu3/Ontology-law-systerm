import { StrictMode } from "react";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { expect, it, beforeEach } from "vitest";
import { SessionApplication } from "./SessionApplication";
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
beforeEach(() => history.replaceState(null, "", "/workbench"));
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
  expect(screen.getByText("候选尚未保存，请先保存后确认。")).toBeVisible();
  expect(screen.getByRole("button", { name: "记录联系结果" })).toBeDisabled();
  expect(f.requests.filter((r) => r.method === "POST")).toHaveLength(0);
  fireEvent.click(screen.getByRole("button", { name: "保存候选" }));
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
      "当前任职不能进入业务工作台；管理入口尚未开放，请联系律所管理员。",
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
