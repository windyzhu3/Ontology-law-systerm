import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { useLayoutEffect } from "react";
import { expect, it } from "vitest";
import { AppointmentChooser } from "./AppointmentChooser";
import {
  SessionController,
  type SessionContext,
  type OidcAdapter,
} from "./sessionController";
import { RecoveryStore, markerKey } from "./recoveryMarker";
import { jsonResponse, deferred } from "../../test/fixtures";
const own = "019c7000-0000-7000-8000-000000000001";
const other = "019c7000-0000-7000-8000-000000000002";
const delegated = "019c7000-0000-7000-8000-000000000003";
const key = "ask1.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const dkey = "ask1.bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
const ready: SessionContext = {
  displayName: "安全测试办理人",
  state: "READY",
  appointmentChoices: [
    { id: own, label: "本人业务任职" },
    { id: other, label: "另一本人任职" },
  ],
  selectedAppointmentId: own,
  selectedOnBehalfAppointmentId: null,
  delegatedAppointmentChoices: [{ id: delegated, label: "已有合法代办任职" }],
  actorScopeKey: key,
  canEnterWorkbench: true,
  canEnterIdentityAdmin: false,
};
const multi: SessionContext = {
  ...ready,
  state: "APPOINTMENT_SELECTION_REQUIRED",
  selectedAppointmentId: null,
  delegatedAppointmentChoices: [],
  actorScopeKey: null,
  canEnterWorkbench: false,
};
function fixture(
  initial = ready,
  respond?: (request: Request, index: number) => Promise<Response>,
  storage: Storage = sessionStorage,
) {
  const requests: Request[] = [];
  let logouts = 0;
  const oidc: OidcAdapter = {
    initialize: async () => true,
    login: async () => {},
    logout: async () => {
      logouts++;
    },
    clear() {},
    getValidAccessToken: async () => "synthetic-oidc-token",
    sessionStartedAt: () => Date.now(),
  };
  const controller = new SessionController(
    oidc,
    new RecoveryStore(storage),
    async (input, init) => {
      const request = new Request(
        new URL(String(input), location.origin),
        init,
      );
      requests.push(request);
      if (respond) return respond(request, requests.length);
      const ownId = request.headers.get("X-Appointment-Id");
      const delegatedId = request.headers.get("X-On-Behalf-Appointment-Id");
      return jsonResponse(
        !ownId
          ? initial
          : {
              ...ready,
              selectedAppointmentId: ownId,
              selectedOnBehalfAppointmentId: delegatedId,
              actorScopeKey: delegatedId ? dkey : key,
              delegatedAppointmentChoices:
                ownId === own ? ready.delegatedAppointmentChoices : [],
            },
      );
    },
  );
  return { controller, requests, logouts: () => logouts };
}
const confirm = () => screen.getByRole("button", { name: "确认本次身份" });
async function show(initial = ready) {
  const f = fixture(initial);
  render(<AppointmentChooser controller={f.controller} />);
  await screen.findByLabelText("本人任职");
  return f;
}

it("requires explicit own selection before current delegated candidates and never defaults even a single delegation", async () => {
  const f = fixture(multi);
  const confirmed: SessionContext[] = [];
  render(
    <AppointmentChooser
      controller={f.controller}
      onConfirmed={(c) => confirmed.push(c)}
    />,
  );
  expect(screen.queryAllByRole("combobox")).toHaveLength(1);
  const ownSelect = await screen.findByLabelText("本人任职");
  expect(ownSelect).toHaveValue("");
  expect(confirm()).toBeDisabled();
  expect(screen.getByRole("radio", { name: "合法代办" })).toBeDisabled();
  fireEvent.change(ownSelect, { target: { value: own } });
  expect(f.requests).toHaveLength(1);
  fireEvent.click(screen.getByRole("button", { name: "确认任职" }));
  await waitFor(() =>
    expect(screen.getByRole("radio", { name: "合法代办" })).toBeEnabled(),
  );
  expect(confirmed).toHaveLength(0);
  fireEvent.click(screen.getByRole("radio", { name: "合法代办" }));
  const delegateSelect = screen.getByLabelText("被代办任职");
  expect(delegateSelect).toHaveValue("");
  expect(confirm()).toBeDisabled();
  fireEvent.change(delegateSelect, { target: { value: delegated } });
  fireEvent.click(confirm());
  fireEvent.click(confirm());
  await waitFor(() => expect(confirmed).toHaveLength(1));
  expect(confirmed[0].actorScopeKey).toBe(dkey);
  await act(async () => {});
  expect(confirm()).toBeDisabled();
  expect(screen.getByRole("radio", { name: "合法代办" })).toBeChecked();
  expect(f.requests).toHaveLength(3);
  expect(f.requests[2].headers.get("Authorization")).toBe(
    "Bearer synthetic-oidc-token",
  );
  expect(f.requests[2].headers.get("X-Appointment-Id")).toBe(own);
  expect(f.requests[2].headers.get("X-On-Behalf-Appointment-Id")).toBe(
    delegated,
  );
  expect(
    f.requests.every(
      (r) => r.method === "GET" && r.url.endsWith("/api/v1/session/context"),
    ),
  ).toBe(true);
  expect(document.body.textContent).not.toContain(own);
  expect(document.body.textContent).not.toContain(delegated);
});

it("accepts a server-selected singleton only after explicit final activation", async () => {
  const f = fixture({
    ...ready,
    appointmentChoices: ready.appointmentChoices.slice(0, 1),
  });
  const confirmed: SessionContext[] = [];
  render(
    <AppointmentChooser
      controller={f.controller}
      onConfirmed={(c) => confirmed.push(c)}
    />,
  );
  await screen.findByLabelText("本人任职");
  expect(confirmed).toHaveLength(0);
  fireEvent.click(confirm());
  await waitFor(() => expect(confirmed).toHaveLength(1));
  expect(confirmed[0].selectedOnBehalfAppointmentId).toBeNull();
  expect(f.requests).toHaveLength(1);
});

it("refuses zero own appointments and explains absent delegation without fallback", async () => {
  const f = await show({
    ...multi,
    state: "NO_APPOINTMENT",
    appointmentChoices: [],
  });
  expect(screen.getByLabelText("本人任职")).toBeDisabled();
  expect(confirm()).toBeDisabled();
  expect(screen.getByRole("radio", { name: "合法代办" })).toBeDisabled();
  expect(screen.getByRole("status")).toHaveTextContent("当前没有可用本人任职");
  expect(f.requests).toHaveLength(1);
});

it("disables delegation when the selected own appointment has no current candidates", async () => {
  await show({ ...ready, delegatedAppointmentChoices: [] });
  expect(screen.getByRole("radio", { name: "合法代办" })).toBeDisabled();
  expect(screen.getByText("当前任职没有可用的合法代办关系。")).toBeVisible();
});

it("clears an old delegated draft as soon as own selection changes", async () => {
  const f = await show();
  fireEvent.click(screen.getByRole("radio", { name: "合法代办" }));
  fireEvent.change(screen.getByLabelText("被代办任职"), {
    target: { value: delegated },
  });
  fireEvent.change(screen.getByLabelText("本人任职"), {
    target: { value: other },
  });
  expect(confirm()).toBeDisabled();
  expect(screen.queryByLabelText("被代办任职")).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "更换任职" }));
  await waitFor(() =>
    expect(screen.getByRole("radio", { name: "合法代办" })).toBeDisabled(),
  );
  await waitFor(() => expect(confirm()).toBeEnabled());
  expect(
    f.controller.getSnapshot().context?.selectedOnBehalfAppointmentId,
  ).toBeNull();
});

it("refuses final activation in the first commit before provider setup", async () => {
  const f = fixture();
  let firstDisabled: boolean | undefined;
  let calls = 0;
  function Observe() {
    useLayoutEffect(() => {
      const button = confirm() as HTMLButtonElement;
      firstDisabled = button.disabled;
      button.click();
    }, []);
    return (
      <AppointmentChooser
        controller={f.controller}
        onConfirmed={() => calls++}
      />
    );
  }
  render(<Observe />);
  expect(firstDisabled).toBe(true);
  expect(calls).toBe(0);
  await screen.findByLabelText("本人任职");
});

function pendingMarker(scope = dkey) {
  const marker = {
    commandId: other,
    commandType: "RECORD_CONTACT_RESULT",
    actorScopeKey: scope,
    recordedAt: new Date().toISOString(),
  };
  sessionStorage.setItem(markerKey, JSON.stringify(marker));
  return marker;
}
it("retains an unmatched clue through intermediate own selection and final original delegation without receipt probing", async () => {
  const marker = pendingMarker();
  const f = fixture(multi),
    confirmed: SessionContext[] = [];
  render(
    <AppointmentChooser
      controller={f.controller}
      onConfirmed={(c) => confirmed.push(c)}
    />,
  );
  await screen.findByLabelText("本人任职");
  fireEvent.change(screen.getByLabelText("本人任职"), {
    target: { value: own },
  });
  fireEvent.click(screen.getByRole("button", { name: "确认任职" }));
  await waitFor(() =>
    expect(screen.getByRole("radio", { name: "合法代办" })).toBeEnabled(),
  );
  expect(f.controller.recovery.read()).toEqual(marker);
  expect(
    screen.queryByRole("button", { name: "确认放弃线索并使用此身份" }),
  ).not.toBeInTheDocument();
  expect(confirmed).toHaveLength(0);
  expect(() =>
    f.controller.recovery.reserveWrite(own, "RECORD_CONTACT_RESULT", key, {}),
  ).toThrow();
  fireEvent.click(screen.getByRole("radio", { name: "合法代办" }));
  fireEvent.change(screen.getByLabelText("被代办任职"), {
    target: { value: delegated },
  });
  fireEvent.click(confirm());
  await waitFor(() => expect(confirmed).toHaveLength(1));
  expect(f.controller.recovery.read()).toEqual(marker);
  expect(f.requests).toHaveLength(3);
  expect(
    f.requests.every(
      (r) => r.method === "GET" && r.url.endsWith("/api/v1/session/context"),
    ),
  ).toBe(true);
});

it("requires explicit risk acceptance for a different final identity and cancellation preserves the clue", async () => {
  const marker = pendingMarker();
  const f = fixture(),
    confirmed: SessionContext[] = [];
  render(
    <AppointmentChooser
      controller={f.controller}
      onConfirmed={(c) => confirmed.push(c)}
    />,
  );
  await screen.findByLabelText("本人任职");
  fireEvent.click(confirm());
  const cancel = await screen.findByRole("button", { name: "取消切换" });
  await waitFor(() => expect(cancel).toHaveFocus());
  expect(
    screen.getByText(/改用其他身份将只删除本地线索，不撤销原操作/),
  ).toBeVisible();
  expect(confirmed).toHaveLength(0);
  expect(f.controller.recovery.read()).toEqual(marker);
  fireEvent.click(cancel);
  await waitFor(() => expect(confirm()).toBeEnabled());
  expect(confirm()).toHaveFocus();
  expect(f.controller.recovery.read()).toEqual(marker);
  fireEvent.click(confirm());
  fireEvent.click(
    await screen.findByRole("button", { name: "确认放弃线索并使用此身份" }),
  );
  await waitFor(() => expect(confirmed).toHaveLength(1));
  expect(f.controller.recovery.read()).toBeNull();
  expect(confirmed[0].actorScopeKey).toBe(key);
});

it("refuses unreadable recovery storage and does not crash or confirm", async () => {
  const storage = {
    getItem() {
      throw Error("secret-storage");
    },
  } as unknown as Storage;
  const f = fixture(ready, undefined, storage);
  let confirms = 0;
  render(
    <AppointmentChooser
      controller={f.controller}
      onConfirmed={() => confirms++}
    />,
  );
  await screen.findByLabelText("本人任职");
  expect(confirm()).toBeDisabled();
  expect(screen.getByRole("status")).toHaveTextContent("恢复线索存储不可用");
  expect(document.body.textContent).not.toContain("secret-storage");
  expect(confirms).toBe(0);
  expect(f.requests).toHaveLength(1);
});

it("retains the clue and refuses confirmation when exact-marker removal fails", async () => {
  const marker = pendingMarker();
  const storage: Storage = {
    get length() {
      return sessionStorage.length;
    },
    key: (i) => sessionStorage.key(i),
    getItem: (k) => sessionStorage.getItem(k),
    setItem: (k, v) => sessionStorage.setItem(k, v),
    removeItem() {
      throw Error("secret-remove");
    },
    clear() {},
  };
  const f = fixture(ready, undefined, storage);
  let confirms = 0;
  render(
    <AppointmentChooser
      controller={f.controller}
      onConfirmed={() => confirms++}
    />,
  );
  await screen.findByLabelText("本人任职");
  fireEvent.click(confirm());
  fireEvent.click(
    await screen.findByRole("button", { name: "确认放弃线索并使用此身份" }),
  );
  await waitFor(() =>
    expect(screen.getByRole("status")).toHaveTextContent("身份核对未完成"),
  );
  expect(confirms).toBe(0);
  expect(f.controller.recovery.read()).toEqual(marker);
  expect(document.body.textContent).not.toContain("secret-remove");
});

it("safely retries a failed context selection without disclosing external error details", async () => {
  const f = fixture(multi, async (r, index) => {
    if (index === 2) throw Error("secret-provider-details");
    return jsonResponse(r.headers.has("X-Appointment-Id") ? ready : multi);
  });
  render(<AppointmentChooser controller={f.controller} />);
  await screen.findByLabelText("本人任职");
  fireEvent.change(screen.getByLabelText("本人任职"), {
    target: { value: own },
  });
  fireEvent.click(screen.getByRole("button", { name: "确认任职" }));
  fireEvent.click(await screen.findByRole("button", { name: "重新核对身份" }));
  await waitFor(() => expect(screen.getByLabelText("本人任职")).toBeEnabled());
  expect(f.requests).toHaveLength(3);
  expect(confirm()).toBeDisabled();
  expect(document.body.textContent).not.toContain("secret-provider-details");
});

it.each(["logout", "unmount", "replace"] as const)(
  "refuses late final context after %s",
  async (action) => {
    const delayed = deferred<Response>();
    const f = fixture(ready, async (_r, index) =>
      index === 1 ? jsonResponse(ready) : delayed.promise,
    );
    let confirms = 0;
    const view = render(
      <AppointmentChooser
        controller={f.controller}
        onConfirmed={() => confirms++}
      />,
    );
    await screen.findByLabelText("本人任职");
    fireEvent.click(screen.getByRole("radio", { name: "合法代办" }));
    fireEvent.change(screen.getByLabelText("被代办任职"), {
      target: { value: delegated },
    });
    fireEvent.click(confirm());
    await waitFor(() => expect(f.requests).toHaveLength(2));
    if (action === "logout") {
      fireEvent.click(screen.getByRole("button", { name: "退出" }));
      expect(f.logouts()).toBe(1);
    } else if (action === "unmount") view.unmount();
    else
      view.rerender(
        <AppointmentChooser
          controller={fixture(multi).controller}
          onConfirmed={() => confirms++}
        />,
      );
    expect(document.body.textContent).not.toContain("安全测试办理人");
    await act(async () =>
      delayed.resolve(
        jsonResponse({
          ...ready,
          selectedOnBehalfAppointmentId: delegated,
          actorScopeKey: dkey,
        }),
      ),
    );
    expect(confirms).toBe(0);
    expect(f.controller.getSnapshot().context).toBeNull();
  },
);
it("does not confirm an old request after logout and reinitialization to the same Actor", async () => {
  pendingMarker(key);
  const delayed = deferred<Response>();
  const f = fixture(ready, async (_r, index) =>
    index === 2 ? delayed.promise : jsonResponse(ready),
  );
  let confirms = 0;
  render(
    <AppointmentChooser
      controller={f.controller}
      onConfirmed={() => confirms++}
    />,
  );
  await screen.findByLabelText("本人任职");
  fireEvent.click(confirm());
  await waitFor(() => expect(f.requests).toHaveLength(2));
  await act(async () => {
    await f.controller.logout();
    await f.controller.initialize();
  });
  expect(f.controller.getSnapshot().context?.actorScopeKey).toBe(key);
  await act(async () => delayed.resolve(jsonResponse(ready)));
  expect(confirms).toBe(0);
});

it("clears prior confirmation feedback when authority is invalidated externally", async () => {
  const f = await show();
  fireEvent.click(confirm());
  await waitFor(() =>
    expect(screen.getByRole("status")).toHaveTextContent("本次身份已确认"),
  );
  act(() =>
    f.controller.invalidate("EXPIRED", "登录状态已失效，请重新登录后继续。"),
  );
  expect(screen.getByRole("status")).toHaveTextContent("登录状态已失效");
  expect(screen.getByRole("status")).not.toHaveTextContent("本次身份已确认");
  expect(confirm()).toBeDisabled();
  expect(document.body.textContent).not.toContain("安全测试办理人");
});
it("shows complete long selected labels as text, without interpreting markup or displaying IDs", async () => {
  const ownLabel = "某某省某某市联合专业法律服务团队 · 本人业务任职完整名称";
  const delegatedLabel =
    "<img src=x onerror=alert(1)> 已有合法代办任职完整名称";
  const f = fixture({
    ...ready,
    appointmentChoices: [{ id: own, label: ownLabel }],
    delegatedAppointmentChoices: [{ id: delegated, label: delegatedLabel }],
  });
  const { container } = render(
    <AppointmentChooser controller={f.controller} />,
  );
  await screen.findByLabelText("本人任职");
  expect(screen.getByLabelText("当前本人任职完整名称")).toHaveTextContent(
    ownLabel,
  );
  fireEvent.click(screen.getByRole("radio", { name: "合法代办" }));
  fireEvent.change(screen.getByLabelText("被代办任职"), {
    target: { value: delegated },
  });
  expect(screen.getByLabelText("当前被代办任职完整名称")).toHaveTextContent(
    delegatedLabel,
  );
  expect(container.querySelectorAll("img")).toHaveLength(0);
  expect(document.body.textContent).not.toContain(delegated);
});
it("refuses noncandidate values even if an option is injected into a native control", async () => {
  const f = await show();
  const fake = "019c7000-0000-7000-8000-000000000099";
  const select = screen.getByLabelText("本人任职");
  const option = document.createElement("option");
  option.value = fake;
  option.textContent = "不受信任选项";
  select.append(option);
  fireEvent.change(select, { target: { value: fake } });
  expect(select).toHaveValue(own);
  expect(f.requests).toHaveLength(1);
  fireEvent.click(screen.getByRole("radio", { name: "合法代办" }));
  const delegateSelect = screen.getByLabelText("被代办任职");
  delegateSelect.append(option.cloneNode(true));
  fireEvent.change(delegateSelect, { target: { value: fake } });
  expect(delegateSelect).toHaveValue("");
  expect(confirm()).toBeDisabled();
  expect(f.requests).toHaveLength(1);
});
