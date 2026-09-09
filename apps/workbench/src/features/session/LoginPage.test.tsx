import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { expect, it } from "vitest";
import { LoginPage } from "./LoginPage";

it("offers only an explicit hosted-login action and suppresses duplicate activation", async () => {
  let calls = 0;
  let complete!: () => void;
  const operation = new Promise<void>((resolve) => {
    complete = resolve;
  });
  const { container } = render(
    <LoginPage
      onLogin={() => {
        calls++;
        return operation;
      }}
    />,
  );
  expect(calls).toBe(0);
  expect(container.querySelectorAll("input,textarea,select")).toHaveLength(0);
  expect(screen.queryAllByRole("button")).toHaveLength(1);
  const button = screen.getByRole("button", { name: "登录工作台" });
  button.focus();
  expect(button).toHaveFocus();
  fireEvent.click(button);
  fireEvent.click(button);
  expect(calls).toBe(1);
  expect(button).toBeDisabled();
  expect(screen.getByRole("status")).toHaveTextContent("正在前往统一身份服务");
  await act(async () => complete());
  expect(button).toBeEnabled();
});

it("recovers from a rejected login without showing the external error", async () => {
  render(
    <LoginPage
      onLogin={async () => {
        throw new Error("secret-token-and-provider-url");
      }}
    />,
  );
  fireEvent.click(screen.getByRole("button", { name: "登录工作台" }));
  await waitFor(() =>
    expect(screen.getByRole("button", { name: "登录工作台" })).toBeEnabled(),
  );
  expect(screen.getByRole("status")).toHaveTextContent(
    "登录服务暂不可用，请稍后重试。",
  );
  expect(document.body).not.toHaveTextContent("secret-token-and-provider-url");
});

it("refuses an unconfigured action without a credential or mock-session fallback", () => {
  const { container } = render(<LoginPage />);
  expect(screen.getByRole("button", { name: "登录工作台" })).toBeDisabled();
  expect(screen.getByRole("status")).toHaveTextContent(
    "登录服务尚未配置，请联系律所管理员。",
  );
  expect(container.querySelectorAll("input,a,textarea")).toHaveLength(0);
});

it("does not transfer an unmounted operation failure to a new login page", async () => {
  let reject!: (reason: Error) => void;
  const operation = new Promise<void>((_, fail) => {
    reject = fail;
  });
  const view = render(<LoginPage onLogin={() => operation} />);
  fireEvent.click(screen.getByRole("button", { name: "登录工作台" }));
  view.unmount();
  render(<LoginPage onLogin={async () => {}} />);
  await act(async () => reject(new Error("late-secret")));
  expect(screen.getByRole("status")).toBeEmptyDOMElement();
  expect(screen.getByRole("button", { name: "登录工作台" })).toBeEnabled();
});
