import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { expect, it } from "vitest";
import { useLayoutEffect } from "react";
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

it("refuses activation in the first commit before provider passive setup", async () => {
  let loginCalls = 0;
  let initializeCalls = 0;
  let disabledAtFirstCommit: boolean | undefined;
  let complete!: (ready: boolean) => void;
  const controller = setup(
    async () => {
      loginCalls++;
    },
    () => {
      initializeCalls++;
      return new Promise<boolean>((resolve) => {
        complete = resolve;
      });
    },
  );
  function ObserveFirstCommit() {
    useLayoutEffect(() => {
      const button = screen.getByRole<HTMLButtonElement>("button", {
        name: "登录工作台",
      });
      disabledAtFirstCommit = button.disabled;
      button.click();
    }, []);
    return <LoginEntry controller={controller} />;
  }
  render(<ObserveFirstCommit />);
  expect({ disabledAtFirstCommit, loginCalls, initializeCalls }).toEqual({
    disabledAtFirstCommit: true,
    loginCalls: 0,
    initializeCalls: 1,
  });
  expect(screen.getByRole("button", { name: "登录工作台" })).toBeDisabled();
  await act(async () => complete(false));
  expect(screen.getByRole("button", { name: "登录工作台" })).toBeEnabled();
  fireEvent.click(screen.getByRole("button", { name: "登录工作台" }));
  expect(loginCalls).toBe(1);
});

it("does not inherit setup admission when the caller replaces the controller", async () => {
  const first = setup(async () => {});
  let loginCalls = 0;
  let initializeCalls = 0;
  let complete!: (ready: boolean) => void;
  const replacement = setup(
    async () => {
      loginCalls++;
    },
    () => {
      initializeCalls++;
      return new Promise<boolean>((resolve) => {
        complete = resolve;
      });
    },
  );
  let disabledOnReplacement: boolean | undefined;
  function ObserveReplacement({
    controller,
  }: {
    controller: SessionController;
  }) {
    useLayoutEffect(() => {
      if (controller !== replacement) return;
      const button = screen.getByRole<HTMLButtonElement>("button", {
        name: "登录工作台",
      });
      disabledOnReplacement = button.disabled;
      button.click();
    }, [controller]);
    return <LoginEntry controller={controller} />;
  }
  const view = render(<ObserveReplacement controller={first} />);
  await waitFor(() =>
    expect(screen.getByRole("button", { name: "登录工作台" })).toBeEnabled(),
  );
  view.rerender(<ObserveReplacement controller={replacement} />);
  expect({ disabledOnReplacement, loginCalls, initializeCalls }).toEqual({
    disabledOnReplacement: true,
    loginCalls: 0,
    initializeCalls: 1,
  });
  await act(async () => complete(false));
  const button = screen.getByRole("button", { name: "登录工作台" });
  expect(button).toBeEnabled();
  fireEvent.click(button);
  expect(loginCalls).toBe(1);
});

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
