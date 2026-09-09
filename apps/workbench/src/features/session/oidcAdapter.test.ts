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
  vi.unstubAllGlobals();
  localStorage.clear();
  history.replaceState(null, "", "/");
});
const jwt = (data: object) =>
  `${btoa("{}")} .${btoa(JSON.stringify(data))}.signature`.replace(" ", "");
async function callback(
  nonceMode: "correct" | "wrong" | "missing",
  idToken = true,
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
      requests.push(new Request(input, init));
      return new Response(
        JSON.stringify({
          access_token: jwt({ ...data, aud: "business-api" }),
          refresh_token: jwt(data),
          ...(idToken ? { id_token: jwt(data) } : {}),
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      );
    },
  );
  return { requests, state, login };
}
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
