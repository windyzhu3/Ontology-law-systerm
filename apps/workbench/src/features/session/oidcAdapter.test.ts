import Keycloak from "keycloak-js";
import { afterEach, expect, it, vi } from "vitest";
import { createOidcAdapter, type OidcConfiguration } from "./oidcAdapter";
const issuer = "https://identity.example.test/realms/fixture";
const config: OidcConfiguration = {
  issuer,
  clientId: "workbench",
  audience: "business-api",
  origin: window.location.origin,
  redirectUri: window.location.origin + "/auth/callback",
  logoutRedirectUri: window.location.origin + "/login",
};
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  localStorage.clear();
  history.replaceState(null, "", "/");
});
const jwt = (data: object) =>
  `${btoa("{}")} .${btoa(JSON.stringify(data))}.signature`.replace(" ", "");
async function callback(
  nonceMode: "correct" | "wrong" | "missing",
  idToken = true,
  refreshIdOverrides:
    | Record<string, unknown>
    | ((nonce: string, issued: number) => Record<string, unknown>) = {},
  refreshAccessOverrides: Record<string, unknown> = {},
  beforeRefreshResponse: () => Promise<void> = async () => {},
) {
  const sdk = new Keycloak({
    url: "https://identity.example.test",
    realm: "fixture",
    clientId: "workbench",
  });
  await sdk.init({
    checkLoginIframe: false,
    pkceMethod: "S256",
    useNonce: true,
    responseMode: "query",
  });
  const login = new URL(
    await sdk.createLoginUrl({ redirectUri: config.redirectUri }),
  );
  const nonce = login.searchParams.get("nonce")!;
  const state = login.searchParams.get("state")!;
  history.replaceState(
    null,
    "",
    `/auth/callback?state=${state}&session_state=fixture&code=one-time-code`,
  );
  const issued = Math.floor(Date.now() / 1000);
  const refreshId =
    typeof refreshIdOverrides === "function"
      ? refreshIdOverrides(nonce, issued)
      : refreshIdOverrides;
  const data = {
    iss: issuer,
    aud: "workbench",
    sub: "synthetic-subject",
    iat: issued,
    exp: issued + 300,
    auth_time: issued,
    ...(nonceMode === "missing"
      ? {}
      : { nonce: nonceMode === "correct" ? nonce : "unrelated" }),
  };
  const requests: Request[] = [];
  vi.stubGlobal(
    "fetch",
    async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = new Request(input, init);
      requests.push(request);
      const form = new URLSearchParams(await request.clone().text());
      const refresh = form.get("grant_type") === "refresh_token";
      const responseData: Record<string, unknown> = refresh
        ? {
            ...data,
            iat: issued + 250,
            exp: issued + 550,
            ...refreshId,
          }
        : data;
      if (refresh && !("nonce" in refreshId)) delete responseData.nonce;
      if (refresh) await beforeRefreshResponse();
      return new Response(
        JSON.stringify({
          access_token: jwt({
            ...responseData,
            aud: "business-api",
            ...(refresh ? refreshAccessOverrides : {}),
          }),
          refresh_token: jwt(responseData),
          ...(idToken ? { id_token: jwt(responseData) } : {}),
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      );
    },
  );
  return { requests, state, login, issued };
}
const requestGrants = async (requests: Request[]) =>
  Promise.all(
    requests.map(async (request) =>
      new URLSearchParams(await request.clone().text()).get("grant_type"),
    ),
  );
const accessTokenOutcome = async (
  adapter: ReturnType<typeof createOidcAdapter>,
) => {
  try {
    await adapter.getValidAccessToken();
    return "resolved";
  } catch {
    return "rejected";
  }
};
it("uses the pinned SDK code exchange with S256 and consumes one-time protocol state without storing tokens", async () => {
  const { requests, login } = await callback("correct");
  const adapter = createOidcAdapter(config);
  expect(await adapter.initialize()).toBe(true);
  expect(login.searchParams.get("code_challenge_method")).toBe("S256");
  expect(login.searchParams.get("response_type")).toBe("code");
  const exchange = await requests[0].clone().text();
  expect(exchange).toContain("code_verifier=");
  expect(exchange).toContain("grant_type=authorization_code");
  const token = await adapter.getValidAccessToken();
  expect(token).not.toBe("");
  expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toContain(
    token,
  );
  expect(
    Object.keys(localStorage).some((k) => k.startsWith("kc-callback-")),
  ).toBe(false);
  expect(window.location.href).not.toContain("code=");
  expect(adapter.sessionStartedAt()).toBeLessThanOrEqual(Date.now());
});
it("accepts a refresh ID token without nonce and keeps it valid on the next non-refresh read", async () => {
  vi.useFakeTimers({ toFake: ["Date"] });
  const { requests, issued } = await callback("correct");
  const adapter = createOidcAdapter(config);
  await expect(adapter.initialize()).resolves.toBe(true);
  const started = adapter.sessionStartedAt();

  vi.setSystemTime((issued + 250) * 1000);
  await adapter.getValidAccessToken();

  expect(await requestGrants(requests)).toEqual([
    "authorization_code",
    "refresh_token",
  ]);
  await adapter.getValidAccessToken();
  expect(requests).toHaveLength(2);
  expect(adapter.sessionStartedAt()).toBe(started);
});
it("rejects a refresh ID token whose nonce differs from the initial verified nonce", async () => {
  vi.useFakeTimers({ toFake: ["Date"] });
  const { requests, issued } = await callback("correct", true, {
    nonce: "unrelated-refresh-nonce",
  });
  const adapter = createOidcAdapter(config);
  await expect(adapter.initialize()).resolves.toBe(true);

  vi.setSystemTime((issued + 250) * 1000);
  expect(await accessTokenOutcome(adapter)).toBe("rejected");
  expect(await accessTokenOutcome(adapter)).toBe("rejected");
  expect(adapter.sessionStartedAt()).toBeNaN();
  expect(await requestGrants(requests)).toEqual([
    "authorization_code",
    "refresh_token",
  ]);
});
it("accepts a refresh ID token carrying the exact initial verified nonce", async () => {
  vi.useFakeTimers({ toFake: ["Date"] });
  const { requests, issued } = await callback("correct", true, (nonce) => ({
    nonce,
  }));
  const adapter = createOidcAdapter(config);
  await expect(adapter.initialize()).resolves.toBe(true);

  vi.setSystemTime((issued + 250) * 1000);
  await adapter.getValidAccessToken();
  expect(await requestGrants(requests)).toEqual([
    "authorization_code",
    "refresh_token",
  ]);
});
it.each([
  ["null", null],
  ["empty", ""],
  ["non-string", 7],
])("rejects a refresh ID token with %s nonce", async (_label, nonce) => {
  vi.useFakeTimers({ toFake: ["Date"] });
  const { requests, issued } = await callback("correct", true, { nonce });
  const adapter = createOidcAdapter(config);
  await expect(adapter.initialize()).resolves.toBe(true);

  vi.setSystemTime((issued + 250) * 1000);
  expect(await accessTokenOutcome(adapter)).toBe("rejected");
  expect(await requestGrants(requests)).toEqual([
    "authorization_code",
    "refresh_token",
  ]);
});
it.each([
  ["subject", (issued: number) => ({ sub: "different" })],
  ["ID audience", (issued: number) => ({ aud: ["workbench", "other"] })],
  ["authentication time", (issued: number) => ({ auth_time: issued + 1 })],
  ["issuer", () => ({ iss: "https://other.example.test/realms/fixture" })],
] as const)("rejects a refresh that changes the original %s binding", async (_label, claims) => {
  vi.useFakeTimers({ toFake: ["Date"] });
  const issued = Math.floor(Date.now() / 1000);
  const prepared = await callback("correct", true, claims(issued));
  const adapter = createOidcAdapter(config);
  await expect(adapter.initialize()).resolves.toBe(true);

  vi.setSystemTime((prepared.issued + 250) * 1000);
  expect(await accessTokenOutcome(adapter)).toBe("rejected");
});
it("retains the access-token audience guard after refresh", async () => {
  vi.useFakeTimers({ toFake: ["Date"] });
  const { issued } = await callback(
    "correct",
    true,
    {},
    { aud: "different-api" },
  );
  const adapter = createOidcAdapter(config);
  await expect(adapter.initialize()).resolves.toBe(true);

  vi.setSystemTime((issued + 250) * 1000);
  expect(await accessTokenOutcome(adapter)).toBe("rejected");
});
it("discards a refresh result that becomes stale after clear", async () => {
  vi.useFakeTimers({ toFake: ["Date"] });
  let markRefreshStarted!: () => void;
  let releaseRefresh!: () => void;
  const refreshStarted = new Promise<void>((resolve) => {
    markRefreshStarted = resolve;
  });
  const refreshRelease = new Promise<void>((resolve) => {
    releaseRefresh = resolve;
  });
  const { requests, issued } = await callback(
    "correct",
    true,
    {},
    {},
    async () => {
      markRefreshStarted();
      await refreshRelease;
    },
  );
  const adapter = createOidcAdapter(config);
  await expect(adapter.initialize()).resolves.toBe(true);
  vi.setSystemTime((issued + 250) * 1000);

  const refresh = adapter.getValidAccessToken();
  await refreshStarted;
  adapter.clear();
  releaseRefresh();

  expect(await refresh.then(
    () => "resolved",
    () => "rejected",
  )).toBe("rejected");
  expect(await accessTokenOutcome(adapter)).toBe("rejected");
  expect(adapter.sessionStartedAt()).toBeNaN();
  expect(await requestGrants(requests)).toEqual([
    "authorization_code",
    "refresh_token",
  ]);
});
it.each([
  ["wrong", true],
  ["missing", true],
  ["correct", false],
] as const)("fails closed on %s nonce / ID token=%s", async (mode, idToken) => {
  await callback(mode, idToken);
  const adapter = createOidcAdapter(config);
  await expect(adapter.initialize()).rejects.toThrow();
  await expect(adapter.getValidAccessToken()).rejects.toThrow();
});
it("rejects replayed or unknown callback state with no exchange", async () => {
  const { state, requests } = await callback("correct");
  localStorage.removeItem("kc-callback-" + state);
  await expect(createOidcAdapter(config).initialize()).rejects.toThrow();
  expect(requests).toHaveLength(0);
});
it("refuses a non-fixed callback/origin or issuer chosen from an untrusted location", () => {
  expect(() =>
    createOidcAdapter({
      ...config,
      redirectUri: "https://attacker.example/callback",
    }),
  ).toThrow();
  expect(() =>
    createOidcAdapter({
      ...config,
      issuer: "http://identity.example.test/realms/fixture",
    }),
  ).toThrow();
});
it("wipes every credential after an incomplete token response before constructing a logout URL", async () => {
  await callback("correct");
  const externalFetch = fetch;
  vi.stubGlobal(
    "fetch",
    async (input: RequestInfo | URL, init?: RequestInit) => {
      const response = await externalFetch(input, init);
      const body = await response.json();
      delete body.access_token;
      return new Response(JSON.stringify(body), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    },
  );
  const sdk = new Keycloak({
    url: "https://identity.example.test",
    realm: "fixture",
    clientId: "workbench",
  });
  const adapter = createOidcAdapter(config, () => sdk);
  await expect(adapter.initialize()).rejects.toThrow();
  expect(sdk.idToken === undefined).toBe(true);
  expect(sdk.refreshToken === undefined).toBe(true);
  const logout = new URL(
    sdk.createLogoutUrl({
      redirectUri: config.logoutRedirectUri,
      logoutMethod: "GET",
    }),
  );
  expect(logout.searchParams.has("id_token_hint")).toBe(false);
});
