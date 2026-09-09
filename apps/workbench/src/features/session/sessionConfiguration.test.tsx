import { render, screen } from "@testing-library/react";
import { it, expect } from "vitest";
import type Keycloak from "keycloak-js";
import { createSessionRuntime } from "./sessionConfiguration";
import { SessionApplication } from "./SessionApplication";
const configured = () => ({
  VITE_OIDC_ISSUER: "https://identity.example.test/realms/firm",
  VITE_OIDC_CLIENT_ID: "synthetic-public",
  VITE_OIDC_AUDIENCE: "synthetic-api",
  VITE_APP_ORIGIN: location.origin,
});
it.each([
  {},
  { VITE_OIDC_ISSUER: "http://insecure.test/realms/firm" },
  { VITE_APP_ORIGIN: "https://another.test" },
  { VITE_OIDC_CLIENT_ID: " " },
])(
  "refuses invalid fixed configuration before SDK or network calls %j",
  (override) => {
    let sdk = 0;
    const env = Object.keys(override).length
      ? { ...configured(), ...override }
      : {};
    const runtime = createSessionRuntime(env, () => {
      sdk++;
      throw Error("secret-config");
    });
    render(<SessionApplication {...runtime} />);
    expect(screen.getByRole("button", { name: "登录工作台" })).toBeDisabled();
    expect(runtime.configurationError).toBe(
      "登录部署配置不可用，请联系律所管理员。",
    );
    expect(sdk).toBe(0);
    expect(document.body.textContent).not.toContain("secret-config");
  },
);
it("derives only fixed callback and logout targets and ignores URL-supplied settings", async () => {
  history.replaceState(
    null,
    "",
    "/login?issuer=https://attacker.test&returnTo=https://attacker.test",
  );
  const seen: unknown[] = [];
  const sdk = {
    init: async (options: unknown) => {
      seen.push(options);
      return false;
    },
    clearToken() {},
  } as unknown as Keycloak;
  const runtime = createSessionRuntime(configured(), (config) => {
    seen.push(config);
    return sdk;
  });
  expect(runtime.controller).not.toBeNull();
  await runtime.controller!.initialize();
  expect(seen).toEqual([
    {
      url: "https://identity.example.test",
      realm: "firm",
      clientId: "synthetic-public",
    },
    expect.objectContaining({
      redirectUri: location.origin + "/auth/callback",
      pkceMethod: "S256",
      useNonce: true,
      responseMode: "query",
    }),
  ]);
});
it("fails safely when the browser sessionStorage getter throws", () => {
  const descriptor = Object.getOwnPropertyDescriptor(window, "sessionStorage")!;
  Object.defineProperty(window, "sessionStorage", {
    configurable: true,
    get() {
      throw Error("storage-secret");
    },
  });
  try {
    const runtime = createSessionRuntime(configured());
    render(<SessionApplication {...runtime} />);
    expect(screen.getByRole("button", { name: "登录工作台" })).toBeDisabled();
    expect(
      screen.getByText(
        "浏览器恢复存储不可用，请检查浏览器设置后重新打开页面。",
      ),
    ).toBeVisible();
  } finally {
    Object.defineProperty(window, "sessionStorage", descriptor);
  }
});
