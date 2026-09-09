import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { expect, it } from "vitest";
import { LoginEntry } from "./LoginEntry";
import { SessionController, type OidcAdapter } from "./sessionController";
import { RecoveryStore } from "./recoveryMarker";

function setup(login: () => Promise<void>, initialize = async () => false) {
  const adapter: OidcAdapter = {
    initialize,
    login,
    logout: async () => {},
    clear() {},
    getValidAccessToken: async () => {
      throw Error("must-not-read-business-data");
    },
    sessionStartedAt: () => Date.now(),
  };
  return new SessionController(
    adapter,
    new RecoveryStore(sessionStorage),
    async () => {
      throw Error("must-not-fetch");
    },
  );
}

it("routes an explicit activation through the real provider/controller and permits retry after safe failure", async () => {
  let calls = 0;
  const controller = setup(async () => {
    calls++;
    throw Error("provider-secret");
  });
  render(<LoginEntry controller={controller} />);
  const button = screen.getByRole("button", { name: "登录工作台" });
  await waitFor(() => expect(button).toBeEnabled());
  expect(calls).toBe(0);
  fireEvent.click(button);
  fireEvent.click(button);
  await waitFor(() => expect(button).toBeEnabled());
  expect(calls).toBe(1);
  expect(screen.getByRole("status")).toHaveTextContent("登录服务暂不可用");
  expect(document.body).not.toHaveTextContent("provider-secret");
  expect(controller.getSnapshot().context).toBeNull();
  fireEvent.click(button);
  await waitFor(() => expect(calls).toBe(2));
});

it("keeps login disabled until OIDC initialization settles", async () => {
  let complete!: (ready: boolean) => void;
  let calls = 0;
  const controller = setup(
    async () => {
      calls++;
    },
    () =>
      new Promise<boolean>((resolve) => {
        complete = resolve;
      }),
  );
  render(<LoginEntry controller={controller} />);
  const button = screen.getByRole("button", { name: "登录工作台" });
  expect(button).toBeDisabled();
  fireEvent.click(button);
  expect(calls).toBe(0);
  await act(async () => complete(false));
  expect(button).toBeEnabled();
});

it("a null controller refuses configuration without creating a mock session", () => {
  render(<LoginEntry controller={null} />);
  expect(screen.getByRole("button", { name: "登录工作台" })).toBeDisabled();
  expect(screen.getByRole("status")).toHaveTextContent("尚未配置");
});

it("does not let a late login rejection replace the signed-out state after provider unmount", async () => {
  let reject!: (error: Error) => void;
  const controller = setup(
    () =>
      new Promise<void>((_, fail) => {
        reject = fail;
      }),
  );
  const view = render(<LoginEntry controller={controller} />);
  const button = screen.getByRole("button", { name: "登录工作台" });
  await waitFor(() => expect(button).toBeEnabled());
  fireEvent.click(button);
  view.unmount();
  await act(async () => {});
  expect(controller.getSnapshot().status).toBe("SIGNED_OUT");
  await act(async () => reject(new Error("late-provider-secret")));
  expect(controller.getSnapshot().status).toBe("SIGNED_OUT");
  expect(controller.getSnapshot().context).toBeNull();
});
