import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { RecoveryPage } from "./RecoveryPage";
import {
  SessionController,
  type SessionContext,
  type OidcAdapter,
} from "./sessionController";
import { RecoveryStore, markerKey } from "./recoveryMarker";
import { createWorkbenchApi } from "../../lib/api";
import {
  SessionProvider,
  useActorSession,
  useWorkbenchSession,
} from "./SessionProvider";
import {
  deferred,
  envelope,
  jsonResponse,
  receipt,
  taskId,
  selectorId,
} from "../../test/fixtures";
const scope = "ask1.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const context: SessionContext = {
  displayName: "合成恢复办理人",
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
  respond: (r: Request) => Promise<Response>,
  opts: {
    context?: SessionContext;
    markerScope?: string;
    commandType?: string;
    storage?: Storage;
  } = {},
) {
  const recovery = new RecoveryStore(opts.storage ?? sessionStorage);
  const marker = {
    commandId: selectorId,
    commandType: opts.commandType ?? "RECORD_CONTACT_RESULT",
    actorScopeKey: opts.markerScope ?? scope,
    recordedAt: new Date().toISOString(),
  };
  sessionStorage.setItem(markerKey, JSON.stringify(marker));
  let logins = 0;
  const oidc: OidcAdapter = {
    initialize: async () => true,
    getValidAccessToken: async () => "recovery-synthetic-token",
    login: async () => {
      logins++;
    },
    logout: async () => {},
    clear() {},
    sessionStartedAt: () => Date.now(),
  };
  const controller = new SessionController(oidc, recovery, async () =>
    jsonResponse(opts.context ?? context),
  );
  const requests: Request[] = [];
  const api = createWorkbenchApi(
    async (r) => {
      requests.push(r);
      return respond(r);
    },
    location.origin,
    recovery,
  );
  return { controller, api, marker, requests, logins: () => logins };
}
const query = () => screen.getByRole("button", { name: "查询原操作结果" });
async function mounted(f: ReturnType<typeof fixture>, onReady?: () => void) {
  const view = render(
    <RecoveryPage controller={f.controller} api={f.api} onReady={onReady} />,
  );
  await waitFor(() => expect(query()).toBeEnabled());
  return view;
}

it("offers only receipt lookup after body loss and never initially reads cards or replays", async () => {
  const f = fixture(async () => jsonResponse({}, 404));
  render(<RecoveryPage controller={f.controller} api={f.api} />);
  expect(
    screen.queryAllByRole("button", { name: "查询原操作结果" }),
  ).toHaveLength(1);
  await waitFor(() => expect(query()).toBeEnabled());
  expect(f.requests).toHaveLength(0);
  fireEvent(window, new Event("focus"));
  fireEvent(document, new Event("visibilitychange"));
  expect(f.requests).toHaveLength(0);
  expect(screen.getByRole("heading", { name: "记录首联结果" })).toBeVisible();
  expect(document.body.textContent).not.toContain(selectorId);
  expect(document.body.textContent).not.toContain(scope);
  fireEvent.click(query());
  await waitFor(() => expect(query()).toBeEnabled());
  expect(f.requests).toHaveLength(1);
  expect(f.requests[0].url).toContain(`/api/v1/commands/${selectorId}/receipt`);
  expect(f.requests[0].method).toBe("GET");
  expect(f.requests[0].headers.get("Authorization")).toBe(
    "Bearer recovery-synthetic-token",
  );
  expect(f.requests[0].headers.get("X-Appointment-Id")).toBe(taskId);
  expect(f.controller.recovery.read()).toEqual(f.marker);
  expect(document.body.textContent).not.toMatch(
    /使用原请求重试|自动重发|重新提交|重放/,
  );
});

it.each(["network", "wrong-command", "wrong-fact"] as const)(
  "retains unknown state after %s receipt failure",
  async (failure) => {
    const f = fixture(async () => {
      if (failure === "network") throw Error("secret-provider-detail");
      return jsonResponse(
        receipt(
          failure === "wrong-command" ? taskId : selectorId,
          failure === "wrong-fact" ? "ACTION_DRAFT" : "LEAD_CONTACT_RESULT",
        ),
      );
    });
    await mounted(f);
    fireEvent.click(query());
    await waitFor(() => expect(query()).toBeEnabled());
    expect(f.controller.recovery.read()).toEqual(f.marker);
    expect(screen.getByText("结果尚未确认，请勿重复提交。")).toBeVisible();
    expect(document.body.textContent).not.toContain("secret-provider-detail");
    expect(
      f.requests.every((r) => r.method === "GET" && r.url.endsWith("/receipt")),
    ).toBe(true);
  },
);

it("separates confirmed receipt from failed current read and retries only the read", async () => {
  let currentReads = 0;
  const f = fixture(async (r) => {
    if (r.url.endsWith("/receipt")) return jsonResponse(receipt(selectorId));
    if (++currentReads === 1) throw Error("read-secret");
    return jsonResponse(envelope());
  });
  await mounted(f);
  fireEvent.click(query());
  const refresh = await screen.findByRole("button", {
    name: "重新读取当前责任",
  });
  expect(f.controller.recovery.read()).toBeNull();
  expect(
    screen.getByText("原操作结果已确认，当前责任暂时无法读取。"),
  ).toBeVisible();
  expect(
    screen.queryByRole("button", { name: "查询原操作结果" }),
  ).not.toBeInTheDocument();
  fireEvent.click(refresh);
  await waitFor(() =>
    expect(screen.getByRole("button", { name: "继续" })).toBeEnabled(),
  );
  expect(f.requests).toHaveLength(3);
  expect(f.requests.every((r) => r.method === "GET")).toBe(true);
});

it("queries an Identity-only Actor's marker without treating it as workbench entry permission", async () => {
  const f = fixture(
    async () =>
      jsonResponse({
        ...receipt(selectorId),
        resultFact: {
          factType: "IDENTITY_PRINCIPAL",
          factRef: "safe-identity-reference",
          revision: 1,
        },
      }),
    {
      context: {
        ...context,
        canEnterWorkbench: false,
        canEnterIdentityAdmin: true,
      },
      commandType: "CREATE_IDENTITY_PRINCIPAL",
    },
  );
  let ready = 0;
  await mounted(f, () => ready++);
  fireEvent.click(query());
  fireEvent.click(await screen.findByRole("button", { name: "继续" }));
  expect(ready).toBe(1);
  expect(f.requests).toHaveLength(1);
  expect(f.controller.recovery.read()).toBeNull();
  expect(
    screen.queryByRole("button", { name: "重新读取当前责任" }),
  ).not.toBeInTheDocument();
});

it("settles an Identity rejection without business reads or automatic queries in recovery-only mode", async () => {
  const f = fixture(async () => jsonResponse({
    commandId: selectorId,
    receiptId: taskId,
    completedAt: "2026-09-08T02:10:00Z",
    outcome: "REJECTED",
    rejectionCode: "IDENTITY_LAST_ADMIN",
  }), {
    context: { ...context, canEnterWorkbench: false, canEnterIdentityAdmin: true },
    commandType: "SUSPEND_IDENTITY_PRINCIPAL",
  });
  const view = await mounted(f);
  vi.useFakeTimers();
  try {
    await act(async () => { await vi.advanceTimersByTimeAsync(240_000); });
    expect(f.requests).toHaveLength(0);
    await act(async () => { fireEvent.click(query()); });
    expect(screen.getByText("原操作结果已确认。")).toBeVisible();
    expect(screen.getByRole("button", { name: "继续" })).toBeEnabled();
    expect(document.body.textContent).not.toMatch(/处理结果已记录|业务完成|正在刷新当前责任|自动.*暂停/);
    fireEvent(window, new Event("focus"));
    fireEvent(document, new Event("visibilitychange"));
    await act(async () => { await vi.advanceTimersByTimeAsync(240_000); });
    expect(f.requests).toHaveLength(1);
    expect(f.requests[0].method).toBe("GET");
    expect(f.requests[0].url).toContain(`/commands/${selectorId}/receipt`);
    expect(f.controller.recovery.read()).toBeNull();
  } finally { view.unmount(); vi.useRealTimers(); }
});

it("refuses receipt lookup for another Actor without guessing identities", async () => {
  const f = fixture(async () => jsonResponse(receipt(selectorId)), {
    markerScope: "ask1.bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  });
  render(<RecoveryPage controller={f.controller} api={f.api} />);
  await screen.findByText("请先选择原操作身份，再核对原回执。");
  expect(query()).toBeDisabled();
  fireEvent.click(query());
  expect(f.requests).toHaveLength(0);
  expect(f.controller.recovery.read()).toEqual(f.marker);
});

it("requires explicit abandonment confirmation, preserves on cancellation, and rejects a late query after abandonment", async () => {
  const pending = deferred<Response>();
  const f = fixture(async () => pending.promise);
  await mounted(f);
  fireEvent.click(screen.getByRole("button", { name: "放弃本地线索" }));
  fireEvent.click(screen.getByRole("button", { name: "取消放弃" }));
  expect(f.controller.recovery.read()).toEqual(f.marker);
  fireEvent.click(query());
  fireEvent.click(query());
  await waitFor(() => expect(f.requests).toHaveLength(1));
  expect(query()).toBeDisabled();
  fireEvent.click(screen.getByRole("button", { name: "放弃本地线索" }));
  expect(
    screen.getByText("只删除本地线索，不撤销原操作。确认放弃？"),
  ).toBeVisible();
  fireEvent.click(screen.getByRole("button", { name: "确认放弃本地线索" }));
  await waitFor(() => expect(f.controller.recovery.read()).toBeNull());
  await act(async () => pending.resolve(jsonResponse(receipt(selectorId))));
  expect(
    screen.getByText("已放弃本地线索，原操作结果仍需另行核对。"),
  ).toBeVisible();
  expect(f.requests).toHaveLength(1);
});

it.each(["receipt", "abandon"] as const)(
  "pauses if storage removal throws after removing during %s",
  async (action) => {
    const storage: Storage = {
      get length() {
        return sessionStorage.length;
      },
      clear: () => sessionStorage.clear(),
      key: (index) => sessionStorage.key(index),
      setItem: (key, value) => sessionStorage.setItem(key, value),
      getItem: (key: string) => sessionStorage.getItem(key),
      removeItem: (key: string) => {
        sessionStorage.removeItem(key);
        throw Error("secret-storage-detail");
      },
    };
    const f = fixture(async () => jsonResponse(receipt(selectorId)), {
      storage,
    });
    await mounted(f);
    if (action === "receipt") {
      fireEvent.click(query());
      await waitFor(() => expect(f.requests).toHaveLength(1));
      await waitFor(() =>
        expect(
          screen.queryByText("正在查询原回执，请稍候。"),
        ).not.toBeInTheDocument(),
      );
    } else {
      fireEvent.click(screen.getByRole("button", { name: "放弃本地线索" }));
      fireEvent.click(screen.getByRole("button", { name: "确认放弃本地线索" }));
    }
    expect(
      screen.queryByRole("button", { name: "继续" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText("当前没有待核对的本地线索。"),
    ).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain("secret-storage-detail");
    expect(f.requests.every((r) => r.url.endsWith("/receipt"))).toBe(true);
  },
);

it("does not strand a pending receipt when the document hides and becomes visible", async () => {
  const pending = deferred<Response>();
  const f = fixture(async (r) =>
    r.url.endsWith("/receipt") ? pending.promise : jsonResponse(envelope()),
  );
  await mounted(f);
  fireEvent.click(query());
  await waitFor(() => expect(f.requests).toHaveLength(1));
  Object.defineProperty(document, "visibilityState", {
    configurable: true,
    value: "hidden",
  });
  fireEvent(document, new Event("visibilitychange"));
  Object.defineProperty(document, "visibilityState", {
    configurable: true,
    value: "visible",
  });
  fireEvent(document, new Event("visibilitychange"));
  await act(async () => pending.resolve(jsonResponse(receipt(selectorId))));
  expect(await screen.findByRole("button", { name: "继续" })).toBeEnabled();
});

it("allows only a manual current-read retry after hiding during confirmed recovery", async () => {
  const pending = deferred<Response>();
  let reads = 0;
  const f = fixture(async (r) => {
    if (r.url.endsWith("/receipt")) return jsonResponse(receipt(selectorId));
    return ++reads === 1 ? pending.promise : jsonResponse(envelope());
  });
  await mounted(f);
  fireEvent.click(query());
  await waitFor(() => expect(f.requests).toHaveLength(2));
  expect(f.controller.recovery.read()).toBeNull();
  expect(
    screen.getByRole("button", { name: "重新读取当前责任" }),
  ).toBeDisabled();
  Object.defineProperty(document, "visibilityState", {
    configurable: true,
    value: "hidden",
  });
  fireEvent(document, new Event("visibilitychange"));
  Object.defineProperty(document, "visibilityState", {
    configurable: true,
    value: "visible",
  });
  fireEvent(document, new Event("visibilitychange"));
  fireEvent(window, new Event("focus"));
  expect(f.requests[1].signal.aborted).toBe(true);
  expect(f.requests).toHaveLength(2);
  expect(
    screen.getByRole("button", { name: "重新读取当前责任" }),
  ).toBeEnabled();
  expect(
    screen.getByText("原操作结果已确认，当前责任暂时无法读取。"),
  ).toBeVisible();
  expect(
    screen.queryByRole("button", { name: "查询原操作结果" }),
  ).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "重新读取当前责任" }));
  await screen.findByRole("button", { name: "继续" });
  await act(async () => pending.resolve(jsonResponse({}, 503)));
  expect(screen.getByRole("button", { name: "继续" })).toBeEnabled();
  expect(f.requests).toHaveLength(3);
  expect(f.requests.every((r) => r.method === "GET")).toBe(true);
  expect(f.requests.filter((r) => r.url.endsWith("/receipt"))).toHaveLength(1);
});

it.each(["corrupt", "expired"] as const)(
  "can explicitly abandon a %s local clue without querying it",
  async (kind) => {
    const f = fixture(async () => jsonResponse({}, 404));
    sessionStorage.setItem(
      markerKey,
      kind === "corrupt"
        ? "{broken"
        : JSON.stringify({ ...f.marker, recordedAt: "2020-01-01T00:00:00Z" }),
    );
    render(<RecoveryPage controller={f.controller} api={f.api} />);
    const abandon = await screen.findByRole("button", { name: "放弃本地线索" });
    await waitFor(() => expect(abandon).toBeEnabled());
    expect(query()).toBeDisabled();
    fireEvent.click(abandon);
    fireEvent.click(screen.getByRole("button", { name: "取消放弃" }));
    expect(sessionStorage.getItem(markerKey)).not.toBeNull();
    fireEvent.click(abandon);
    fireEvent.click(screen.getByRole("button", { name: "确认放弃本地线索" }));
    expect(await screen.findByRole("button", { name: "继续" })).toBeEnabled();
    expect(f.controller.recovery.read()).toBeNull();
    expect(f.requests).toHaveLength(0);
  },
);

it.each([401, 403])(
  "removes private operation and identity fields on receipt %s",
  async (status) => {
    const f = fixture(async () => jsonResponse({}, status));
    await mounted(f);
    fireEvent.click(query());
    expect(
      await screen.findByRole("button", { name: "重新登录" }),
    ).toBeEnabled();
    expect(screen.queryByText("业务一组 · 线索专员")).not.toBeInTheDocument();
    expect(screen.queryByText("合成恢复办理人")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: "记录首联结果" }),
    ).not.toBeInTheDocument();
    expect(f.controller.recovery.read()).toEqual(f.marker);
    fireEvent.click(screen.getByRole("button", { name: "重新登录" }));
    await waitFor(() => expect(f.logins()).toBe(1));
  },
);

it("ignores an old receipt after expiry and never reads old business cards", async () => {
  const pending = deferred<Response>();
  const f = fixture(async () => pending.promise);
  await mounted(f);
  fireEvent.click(query());
  await waitFor(() => expect(f.requests).toHaveLength(1));
  act(() => f.controller.invalidate("EXPIRED"));
  await act(async () => pending.resolve(jsonResponse(receipt(selectorId))));
  expect(screen.getByRole("button", { name: "重新登录" })).toBeEnabled();
  expect(screen.queryByText("业务一组 · 线索专员")).not.toBeInTheDocument();
  expect(f.controller.recovery.read()).toEqual(f.marker);
  expect(f.requests).toHaveLength(1);
});

it("keeps unavailable storage paused when explicit removal also fails", async () => {
  const storage = {
    getItem: () => {
      throw Error("secret-storage");
    },
    removeItem: () => {
      throw Error("secret-storage");
    },
  } as unknown as Storage;
  const f = fixture(async () => jsonResponse({}, 404), { storage });
  render(<RecoveryPage controller={f.controller} api={f.api} />);
  fireEvent.click(await screen.findByRole("button", { name: "放弃本地线索" }));
  fireEvent.click(screen.getByRole("button", { name: "确认放弃本地线索" }));
  expect(query()).toBeDisabled();
  expect(
    screen.queryByRole("button", { name: "继续" }),
  ).not.toBeInTheDocument();
  expect(screen.getByText("恢复存储不可用，请联系律所管理员。")).toBeVisible();
  expect(document.body.textContent).not.toContain("secret-storage");
  expect(f.requests).toHaveLength(0);
});

it("preserves a replacement marker while a corrupt-clue abandonment prompt is open", async () => {
  const f = fixture(async () => jsonResponse({}, 404));
  sessionStorage.setItem(markerKey, "{broken");
  render(<RecoveryPage controller={f.controller} api={f.api} />);
  fireEvent.click(await screen.findByRole("button", { name: "放弃本地线索" }));
  sessionStorage.setItem(markerKey, JSON.stringify(f.marker));
  fireEvent.click(screen.getByRole("button", { name: "确认放弃本地线索" }));
  expect(f.controller.recovery.read()).toEqual(f.marker);
  expect(
    screen.queryByRole("button", { name: "继续" }),
  ).not.toBeInTheDocument();
  expect(f.requests).toHaveLength(0);
});

it("exposes an authorized Actor for recovery while keeping workbench access denied", async () => {
  const f = fixture(async () => jsonResponse({}, 404), {
    context: {
      ...context,
      canEnterWorkbench: false,
      canEnterIdentityAdmin: true,
    },
  });
  function Consumer() {
    const actor = useActorSession(),
      workbench = useWorkbenchSession();
    return (
      <>
        <span>{actor ? "可核对回执" : "等待会话"}</span>
        <span>{workbench ? "可进入工作卡" : "禁止进入工作卡"}</span>
      </>
    );
  }
  render(
    <SessionProvider controller={f.controller}>
      <Consumer />
    </SessionProvider>,
  );
  expect(await screen.findByText("可核对回执")).toBeVisible();
  expect(screen.getByText("禁止进入工作卡")).toBeVisible();
});
