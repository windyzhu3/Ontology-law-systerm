import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { expect, it } from "vitest";
import { App } from "../../App";
import { createWorkbenchApi } from "../../lib/api";
import {
  deferred,
  envelope,
  jsonResponse,
  receipt,
  tags,
  taskId,
} from "../../test/fixtures";
import {
  SessionController,
  type OidcAdapter,
  type SessionContext,
} from "./sessionController";
import { SessionProvider, useWorkbenchSession } from "./SessionProvider";
import { RecoveryStore, markerKey } from "./recoveryMarker";
const context: SessionContext = {
  displayName: "合成测试办理人",
  state: "READY",
  appointmentChoices: [{ id: taskId, label: "联系人员" }],
  selectedAppointmentId: taskId,
  selectedOnBehalfAppointmentId: null,
  delegatedAppointmentChoices: [],
  actorScopeKey: "ask1.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  canEnterWorkbench: true,
  canEnterIdentityAdmin: false,
};
function oidc(): OidcAdapter {
  return {
    initialize: async () => true,
    getValidAccessToken: async () => "initial",
    login: async () => {},
    logout: async () => {},
    clear() {},
    sessionStartedAt: () => Date.now(),
  };
}
it("T9-L07 retains dirty input through an actual controller renewal and submits once with renewed Bearer", async () => {
  const adapter = oidc();
  let token = "old-token";
  const renewal = deferred<string>();
  let rotating = false;
  adapter.getValidAccessToken = () =>
    rotating ? renewal.promise : Promise.resolve(token);
  const controller = new SessionController(
    adapter,
    new RecoveryStore(sessionStorage),
    async () => jsonResponse(context),
  );
  const requests: Request[] = [];
  const data = envelope(5, true);
  data.currentCard!.commandForm.fields.find(
    (f) => f.name === "resultSummary",
  )!.label = "结果说明";
  data.currentCard!.primaryCommand.label = "记录联系结果";
  const api = createWorkbenchApi(async (request) => {
    requests.push(request);
    if (request.method === "PUT") {
      const body = await request.clone().json();
      return jsonResponse(
        {
          receipt: receipt(
            request.headers.get("Idempotency-Key")!,
            "ACTION_DRAFT",
          ),
          draft: { ...data.currentCard!.actionDraft!, values: body.values },
          preconditions: tags,
        },
        200,
        tags.draftETag,
      );
    }
    if (request.method === "POST")
      return jsonResponse(receipt(request.headers.get("Idempotency-Key")!));
    return jsonResponse(data);
  });
  function Consumer() {
    return <App session={useWorkbenchSession()} api={api} />;
  }
  render(
    <SessionProvider controller={controller}>
      <Consumer />
    </SessionProvider>,
  );
  const input = await screen.findByLabelText("结果说明");
  fireEvent.change(input, { target: { value: "尚未保存的输入" } });
  input.focus();
  rotating = true;
  let refresh: Promise<string>;
  act(() => {
    refresh = controller.getValidAccessToken();
  });
  await act(async () => {
    token = "renewed-token";
    renewal.resolve(token);
    await refresh!;
    rotating = false;
  });
  expect(screen.getByLabelText("结果说明")).toBe(input);
  expect(screen.getByLabelText("结果说明")).toHaveValue("尚未保存的输入");
  expect(input).toHaveFocus();
  expect(screen.getByText("候选尚未保存，请先保存后确认。")).toBeVisible();
  expect(screen.getByRole("button", { name: "记录联系结果" })).toBeDisabled();
  expect(requests.filter((r) => r.method === "POST")).toHaveLength(0);
  fireEvent.click(screen.getByRole("button", { name: "保存候选" }));
  await waitFor(() =>
    expect(screen.getByRole("button", { name: "记录联系结果" })).toBeEnabled(),
  );
  fireEvent.click(screen.getByRole("button", { name: "记录联系结果" }));
  await waitFor(() =>
    expect(requests.filter((r) => r.method === "POST")).toHaveLength(1),
  );
  const post = requests.find((r) => r.method === "POST")!;
  expect(post.headers.get("Authorization")).toBe("Bearer renewed-token");
  expect(post.headers.get("X-Appointment-Id")).toBe(taskId);
  expect(post.headers.has("X-On-Behalf-Appointment-Id")).toBe(false);
  expect(post.headers.get("If-Match")).toBe(tags.taskETag);
  expect((await post.clone().json()).resultSummary).toBe("尚未保存的输入");
});
it("T9-L11 clears expired sensitive view and recovers a persisted unknown POST only after same-scope relogin", async () => {
  const controller = new SessionController(
    oidc(),
    new RecoveryStore(sessionStorage),
    async () => jsonResponse(context),
  );
  let posts = 0,
    reads = 0;
  const api = createWorkbenchApi(async (request) => {
    if (request.method === "POST") {
      posts++;
      throw Error("lost response");
    }
    if (request.url.includes("/receipt")) {
      reads++;
      return jsonResponse(
        receipt(JSON.parse(sessionStorage.getItem(markerKey)!).commandId),
      );
    }
    return jsonResponse(envelope(5, true));
  });
  function Consumer() {
    return <App session={useWorkbenchSession()} api={api} />;
  }
  render(
    <SessionProvider controller={controller}>
      <Consumer />
    </SessionProvider>,
  );
  fireEvent.click(await screen.findByRole("button", { name: "保存联系结果" }));
  await screen.findByRole("button", { name: "使用原请求重试" });
  await act(async () => controller.invalidate());
  expect(screen.queryByText("王某 · 劳动仲裁咨询")).not.toBeInTheDocument();
  expect(sessionStorage.getItem(markerKey)).not.toBeNull();
  await act(async () => controller.initialize());
  const recover = await screen.findByRole("button", { name: "查询原回执" });
  expect(
    screen.queryByRole("button", { name: "使用原请求重试" }),
  ).not.toBeInTheDocument();
  fireEvent.click(recover);
  await waitFor(() => expect(sessionStorage.getItem(markerKey)).toBeNull());
  expect(reads).toBe(1);
  expect(posts).toBe(1);
});
