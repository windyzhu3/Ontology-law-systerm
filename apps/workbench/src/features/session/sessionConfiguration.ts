import type { WorkbenchApi } from "../../lib/api";
import type Keycloak from "keycloak-js";
import type { KeycloakConfig } from "keycloak-js";
import type { SessionController } from "./sessionController";
import { SessionController as Controller } from "./sessionController";
import { createOidcAdapter } from "./oidcAdapter";
import { RecoveryStore } from "./recoveryMarker";
import { createWorkbenchApi } from "../../lib/api";
export interface SessionRuntime {
  controller: SessionController | null;
  api?: WorkbenchApi;
  configurationError?: string;
}
export function createSessionRuntime(
  env: Record<string, unknown>,
  createClient?: (config: KeycloakConfig) => Keycloak,
): SessionRuntime {
  let adapter;
  try {
    const value = (key: string) => {
      const v = env[key];
      if (
        typeof v !== "string" ||
        !v ||
        v.trim() !== v ||
        /[\u0000-\u0020\u007f]/.test(v)
      )
        throw new Error();
      return v;
    };
    const origin = value("VITE_APP_ORIGIN"),
      address = new URL(origin);
    if (
      address.origin !== origin ||
      (address.protocol !== "https:" &&
        !(
          address.protocol === "http:" &&
          ["localhost", "127.0.0.1", "[::1]"].includes(address.hostname)
        ))
    )
      throw new Error();
    adapter = createOidcAdapter(
      {
        issuer: value("VITE_OIDC_ISSUER"),
        clientId: value("VITE_OIDC_CLIENT_ID"),
        audience: value("VITE_OIDC_AUDIENCE"),
        origin,
        redirectUri: origin + "/auth/callback",
        logoutRedirectUri: origin + "/login",
      },
      createClient,
    );
  } catch {
    return {
      controller: null,
      configurationError: "登录部署配置不可用，请联系律所管理员。",
    };
  }
  try {
    const store = new RecoveryStore(window.sessionStorage);
    return {
      controller: new Controller(adapter, store),
      api: createWorkbenchApi(undefined, location.origin, store),
    };
  } catch {
    return {
      controller: null,
      configurationError:
        "浏览器恢复存储不可用，请检查浏览器设置后重新打开页面。",
    };
  }
}
