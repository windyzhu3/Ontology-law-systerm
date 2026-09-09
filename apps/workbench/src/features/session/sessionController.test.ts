import { afterEach, beforeEach, expect, it, vi } from "vitest";
import {
  SessionController,
  type SessionContext,
  type OidcAdapter,
} from "./sessionController";
import { RecoveryStore, markerKey } from "./recoveryMarker";
import { deferred, jsonResponse } from "../../test/fixtures";
const own = "019c7000-0000-7000-8000-000000000001",
  delegated = "019c7000-0000-7000-8000-000000000002";
const key = "ask1.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  dkey = "ask1.bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
const context: SessionContext = {
  displayName: "测试办理人",
  state: "READY",
  appointmentChoices: [{ id: own, label: "联系人员" }],
  selectedAppointmentId: own,
  actorScopeKey: key,
  canEnterWorkbench: true,
  canEnterIdentityAdmin: false,
  delegatedAppointmentChoices: [{ id: delegated, label: "代办联系人员" }],
  selectedOnBehalfAppointmentId: null,
};
function adapter(): OidcAdapter {
  return {
    initialize: async () => true,
    getValidAccessToken: async () => "first",
    login: async () => {},
    logout: async () => {},
    clear() {},
    sessionStartedAt: () => Date.now(),
  };
}
beforeEach(() => sessionStorage.clear());
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

it("invokes the default browser fetch without using the controller as its receiver", async () => {
  vi.stubGlobal("fetch", async function (this: unknown) {
    if (this !== undefined && this !== globalThis)
      throw new TypeError("Illegal invocation");
    return jsonResponse(context);
  });
  const controller = new SessionController(
    adapter(),
    new RecoveryStore(sessionStorage),
  );
  await controller.initialize();
  expect(controller.getSnapshot().status).toBe("READY");
  expect(controller.getSnapshot().context).toEqual(context);
});

it.each(["initial", "selection"] as const)(
  "bounds the entire %s SELF response and rejects its late body after the original deadline",
  async (phase) => {
    vi.useFakeTimers();
    const headers = deferred<Response>();
    let body!: ReadableStreamDefaultController<Uint8Array>;
    const response = new Response(
      new ReadableStream<Uint8Array>({
        start(controller) {
          body = controller;
        },
      }),
      { status: 200, headers: { "Content-Type": "application/json" } },
    );
    let stalled = phase === "initial";
    let requestSignal: AbortSignal | null | undefined;
    const controller = new SessionController(
      adapter(),
      new RecoveryStore(sessionStorage),
      async (_url, init) => {
        if (!stalled) return jsonResponse(context);
        requestSignal = init?.signal;
        return headers.promise;
      },
    );
    if (phase === "selection") {
      await controller.initialize();
      stalled = true;
    }
    let settled = false;
    const operation = (
      phase === "initial"
        ? controller.initialize()
        : controller.selectOnBehalfAppointment(delegated)
    ).then(() => {
      settled = true;
    });
    await vi.advanceTimersByTimeAsync(0);
    expect(controller.getSnapshot().status).toBe(
      phase === "initial" ? "INITIALIZING" : "SELECTING",
    );
    await vi.advanceTimersByTimeAsync(8_000);
    headers.resolve(response);
    await vi.advanceTimersByTimeAsync(0);
    expect(response.bodyUsed).toBe(true);
    expect(requestSignal?.aborted).toBe(false);
    await vi.advanceTimersByTimeAsync(2_001);
    try {
      expect(controller.getSnapshot().status).toBe("UNAVAILABLE");
      expect(controller.getSnapshot().context).toBeNull();
      expect(controller.getSnapshot().message).toBe(
        "登录服务暂不可用，请重新登录。",
      );
      expect(requestSignal?.aborted).toBe(true);
      expect(settled).toBe(true);
    } finally {
      body.enqueue(
        new TextEncoder().encode(
          JSON.stringify(
            phase === "initial"
              ? context
              : {
                  ...context,
                  selectedOnBehalfAppointmentId: delegated,
                  actorScopeKey: dkey,
                },
          ),
        ),
      );
      body.close();
      await operation;
    }
    expect(controller.getSnapshot().status).toBe("UNAVAILABLE");
    expect(controller.getSnapshot().context).toBeNull();
    stalled = false;
    await controller.initialize();
    expect(controller.getSnapshot().status).toBe("READY");
    expect(controller.getSnapshot().context?.actorScopeKey).toBe(key);
  },
);
it("uses authenticated own context and explicit paired delegated selectors", async () => {
  const requests: Request[] = [];
  const controller = new SessionController(
    adapter(),
    new RecoveryStore(sessionStorage),
    async (input, init) => {
      const r = new Request(
        new URL(String(input), window.location.origin),
        init,
      );
      requests.push(r);
      return jsonResponse(
        r.headers.has("X-On-Behalf-Appointment-Id")
          ? {
              ...context,
              actorScopeKey: dkey,
              selectedOnBehalfAppointmentId: delegated,
            }
          : context,
      );
    },
  );
  await controller.initialize();
  expect(controller.getSnapshot().context).toEqual(context);
  const epoch = controller.getSnapshot().identityEpoch;
  await controller.selectOnBehalfAppointment(delegated);
  expect(requests[1].headers.get("X-Appointment-Id")).toBe(own);
  expect(requests[1].headers.get("X-On-Behalf-Appointment-Id")).toBe(delegated);
  expect(controller.getSnapshot().context?.actorScopeKey).toBe(dkey);
  expect(controller.getSnapshot().identityEpoch).toBeGreaterThan(epoch);
});
it("single-flights same actor renewal and rejects late credentials after logout", async () => {
  const oidc = adapter();
  let calls = 0;
  const delayed = deferred<string>();
  oidc.getValidAccessToken = () =>
    ++calls === 1 ? Promise.resolve("first") : delayed.promise;
  const controller = new SessionController(
    oidc,
    new RecoveryStore(sessionStorage),
    async () => jsonResponse(context),
  );
  await controller.initialize();
  const epoch = controller.getSnapshot().identityEpoch;
  const a = controller.getValidAccessToken(),
    b = controller.getValidAccessToken();
  delayed.resolve("rotated");
  expect(await Promise.all([a, b])).toEqual(["rotated", "rotated"]);
  expect(calls).toBe(2);
  expect(controller.getSnapshot().identityEpoch).toBe(epoch);
  const late = deferred<string>();
  oidc.getValidAccessToken = () => late.promise;
  const request = controller.getValidAccessToken().catch(() => "refused");
  await controller.logout();
  late.resolve("late-token");
  expect(await request).toBe("refused");
  expect(controller.getSnapshot().context).toBeNull();
});
it.each([
  { ...context, tenantId: own },
  { ...context, selectedAppointmentId: delegated },
  {
    ...context,
    selectedOnBehalfAppointmentId: delegated,
    canEnterIdentityAdmin: true,
  },
  { ...context, state: "APPOINTMENT_SELECTION_REQUIRED" },
  { ...context, actorScopeKey: "guessed" },
])("refuses malformed self context and discloses no authority", async (bad) => {
  const controller = new SessionController(
    adapter(),
    new RecoveryStore(sessionStorage),
    async () => jsonResponse(bad),
  );
  await controller.initialize();
  expect(controller.getSnapshot().status).toBe("UNAVAILABLE");
  expect(controller.getSnapshot().context).toBeNull();
});
it("keeps pending delegated clue through initial own context and selecting the original delegation", async () => {
  const marker = {
    commandId: own,
    commandType: "RECORD_CONTACT_RESULT",
    actorScopeKey: dkey,
    recordedAt: new Date().toISOString(),
  };
  sessionStorage.setItem(markerKey, JSON.stringify(marker));
  const controller = new SessionController(
    adapter(),
    new RecoveryStore(sessionStorage),
    async (_input, init) =>
      jsonResponse(
        new Headers(init?.headers).has("X-On-Behalf-Appointment-Id")
          ? {
              ...context,
              actorScopeKey: dkey,
              selectedOnBehalfAppointmentId: delegated,
            }
          : context,
      ),
  );
  await controller.initialize();
  await controller.selectOnBehalfAppointment(delegated);
  expect(controller.getSnapshot().context?.actorScopeKey).toBe(dkey);
  expect(JSON.parse(sessionStorage.getItem(markerKey)!)).toEqual(marker);
});
it("keeps original delegated recovery reachable through intermediate own selection after multi-appointment relogin", async () => {
  const marker = {
    commandId: own,
    commandType: "RECORD_CONTACT_RESULT",
    actorScopeKey: dkey,
    recordedAt: new Date().toISOString(),
  };
  const recovery = new RecoveryStore(sessionStorage);
  recovery.reserve(marker);
  const requests: Request[] = [];
  const controller = new SessionController(
    adapter(),
    recovery,
    async (input, init) => {
      const request = new Request(
        new URL(String(input), location.origin),
        init,
      );
      requests.push(request);
      if (!request.headers.has("X-Appointment-Id"))
        return jsonResponse({
          ...context,
          state: "APPOINTMENT_SELECTION_REQUIRED",
          appointmentChoices: [
            ...context.appointmentChoices,
            { id: delegated, label: "另一本人任职" },
          ],
          selectedAppointmentId: null,
          actorScopeKey: null,
          delegatedAppointmentChoices: [],
          canEnterWorkbench: false,
        });
      return jsonResponse(
        request.headers.has("X-On-Behalf-Appointment-Id")
          ? {
              ...context,
              selectedOnBehalfAppointmentId: delegated,
              actorScopeKey: dkey,
            }
          : context,
      );
    },
  );
  await controller.initialize();
  await controller.prepareAppointment(own);
  expect(controller.getSnapshot().context?.delegatedAppointmentChoices).toEqual(
    [{ id: delegated, label: "代办联系人员" }],
  );
  expect(controller.getSnapshot().switchConfirmation).toBe(false);
  expect(recovery.read()).toEqual(marker);
  expect(() =>
    recovery.reserveWrite(delegated, "RECORD_CONTACT_RESULT", key, {}),
  ).toThrow();
  await controller.selectOnBehalfAppointment(delegated);
  expect(controller.getSnapshot().context?.actorScopeKey).toBe(dkey);
  expect(recovery.read()).toEqual(marker);
  expect(requests).toHaveLength(3);
  expect(
    requests.every(
      (r) => r.method === "GET" && r.url.endsWith("/api/v1/session/context"),
    ),
  ).toBe(true);
  expect(requests[2].headers.get("X-Appointment-Id")).toBe(own);
  expect(requests[2].headers.get("X-On-Behalf-Appointment-Id")).toBe(delegated);
});
it("expires on idle and absolute time without background renewal extending activity", async () => {
  let now = 0;
  const oidc = adapter();
  oidc.sessionStartedAt = () => 0;
  const controller = new SessionController(
    oidc,
    new RecoveryStore(sessionStorage),
    async () => jsonResponse(context),
    () => now,
  );
  await controller.initialize();
  now = 29 * 60_000;
  controller.checkLifetime();
  expect(controller.getSnapshot().warning).toBe(true);
  await controller.getValidAccessToken();
  now = 30 * 60_000;
  controller.checkLifetime();
  expect(controller.getSnapshot().status).toBe("EXPIRED");
  expect(controller.getSnapshot().context).toBeNull();
  await expect(controller.getValidAccessToken()).rejects.toThrow();
});
it("clears locally before failed IdP logout and does not claim global success", async () => {
  const oidc = adapter();
  let controller: SessionController;
  oidc.logout = async () => {
    expect(controller.getSnapshot().context).toBeNull();
    throw Error("external details");
  };
  controller = new SessionController(
    oidc,
    new RecoveryStore(sessionStorage),
    async () => jsonResponse(context),
  );
  await controller.initialize();
  await controller.logout();
  expect(controller.getSnapshot().status).toBe("SIGNED_OUT");
  expect(controller.getSnapshot().message).toContain("统一会话退出尚未确认");
});
it("broadcasts logout after local clear and clears another mounted tab", async () => {
  const channels = new Set<FakeChannel>();
  class FakeChannel extends EventTarget {
    constructor(_name: string) {
      super();
      channels.add(this);
    }
    postMessage(value: unknown) {
      expect(value).toBe("LOGOUT");
      for (const peer of channels)
        if (peer !== this)
          peer.dispatchEvent(new MessageEvent("message", { data: value }));
    }
    close() {
      channels.delete(this);
    }
  }
  vi.stubGlobal("BroadcastChannel", FakeChannel);
  const a = new SessionController(
    adapter(),
    new RecoveryStore(sessionStorage),
    async () => jsonResponse(context),
  );
  const b = new SessionController(
    adapter(),
    new RecoveryStore(sessionStorage),
    async () => jsonResponse(context),
  );
  const stopA = a.attachBrowser(),
    stopB = b.attachBrowser();
  await a.initialize();
  await b.initialize();
  await a.logout();
  expect(b.getSnapshot().context).toBeNull();
  expect(b.getSnapshot().status).toBe("SIGNED_OUT");
  stopA();
  stopB();
  expect(channels.size).toBe(0);
});
it("enforces timer expiry, bfcache cleanup and fallback focus without treating focus as activity", async () => {
  vi.useFakeTimers();
  vi.stubGlobal("BroadcastChannel", undefined);
  let now = 0;
  const oidc = adapter();
  oidc.sessionStartedAt = () => 0;
  const controller = new SessionController(
    oidc,
    new RecoveryStore(sessionStorage),
    async () => jsonResponse(context),
    () => now,
  );
  const stop = controller.attachBrowser();
  await controller.initialize();
  window.dispatchEvent(new Event("focus"));
  expect(controller.getSnapshot().context).toBeNull();
  await controller.initialize();
  now = 30 * 60_000;
  await vi.advanceTimersByTimeAsync(1000);
  expect(controller.getSnapshot().status).toBe("EXPIRED");
  now = 0;
  await controller.initialize();
  window.dispatchEvent(
    new PageTransitionEvent("pagehide", { persisted: true }),
  );
  expect(controller.getSnapshot().context).toBeNull();
  stop();
  expect(vi.getTimerCount()).toBe(0);
});
it("does not revive a timed-out initialization when the SDK responds late", async () => {
  vi.useFakeTimers();
  const ready = deferred<boolean>();
  const oidc = adapter();
  oidc.initialize = () => ready.promise;
  const controller = new SessionController(
    oidc,
    new RecoveryStore(sessionStorage),
    async () => jsonResponse(context),
  );
  const init = controller.initialize();
  await vi.advanceTimersByTimeAsync(10_000);
  await init;
  expect(controller.getSnapshot().status).toBe("UNAVAILABLE");
  ready.resolve(true);
  await Promise.resolve();
  expect(controller.getSnapshot().context).toBeNull();
});
it("requires an explicit risk confirmation before changing to a different actor with an unresolved clue", async () => {
  sessionStorage.setItem(
    markerKey,
    JSON.stringify({
      commandId: own,
      commandType: "RECORD_CONTACT_RESULT",
      actorScopeKey: dkey,
      recordedAt: new Date().toISOString(),
    }),
  );
  const controller = new SessionController(
    adapter(),
    new RecoveryStore(sessionStorage),
    async () => jsonResponse(context),
  );
  await controller.initialize();
  await controller.selectOnBehalfAppointment(null);
  expect(controller.getSnapshot().context).toBeNull();
  expect(controller.getSnapshot().message).toContain("不撤销原操作");
  expect(sessionStorage.getItem(markerKey)).not.toBeNull();
  controller.confirmIdentitySwitch(true);
  expect(controller.getSnapshot().context?.actorScopeKey).toBe(key);
  expect(sessionStorage.getItem(markerKey)).toBeNull();
});
