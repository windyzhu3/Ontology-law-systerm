import Keycloak, { type KeycloakConfig } from "keycloak-js";
import { bounded, SessionFailure, type OidcAdapter } from "./sessionController";
export interface OidcConfiguration {
  issuer: string;
  clientId: string;
  audience: string;
  origin: string;
  redirectUri: string;
  logoutRedirectUri: string;
}
export function createOidcAdapter(
  config: OidcConfiguration,
  createClient: (config: KeycloakConfig) => Keycloak = (config) =>
    new Keycloak(config),
): OidcAdapter {
  const issuer = new URL(config.issuer);
  if (
    issuer.protocol !== "https:" ||
    issuer.username ||
    issuer.password ||
    issuer.search ||
    issuer.hash ||
    !/^\/realms\/[^/]+$/.test(issuer.pathname) ||
    config.origin !== window.location.origin ||
    config.redirectUri !== config.origin + "/auth/callback" ||
    config.logoutRedirectUri !== config.origin + "/login" ||
    !config.clientId ||
    !config.audience
  )
    throw new Error("登录部署配置不可用。");
  const fixed = Object.freeze({ ...config });
  let client: Keycloak | null = null,
    generation = 0,
    authenticated = false,
    started = NaN;
  const fail = () => new Error("登录验证未完成，请重新登录。");
  const wipe = (sdk: Keycloak) => {
    // SDK clearToken only cleans up when an access token exists; incomplete
    // exchanges may already have populated refresh and ID credentials.
    sdk.clearToken();
    sdk.token = undefined;
    sdk.refreshToken = undefined;
    sdk.idToken = undefined;
    sdk.tokenParsed = undefined;
    sdk.refreshTokenParsed = undefined;
    sdk.idTokenParsed = undefined;
    sdk.subject = undefined;
    sdk.realmAccess = undefined;
    sdk.resourceAccess = undefined;
    sdk.authenticated = false;
  };
  const clear = () => {
    generation++;
    authenticated = false;
    started = NaN;
    if (client) wipe(client);
  };
  const validate = (sdk: Keycloak) => {
    const id = sdk.idTokenParsed;
    const access = sdk.tokenParsed;
    const audience = (a: unknown, want: string) =>
      a === want || (Array.isArray(a) && a.includes(want));
    if (
      !sdk.authenticated ||
      !sdk.token ||
      !sdk.refreshToken ||
      !sdk.idToken ||
      !id ||
      typeof id.nonce !== "string" ||
      !id.nonce ||
      id.iss !== fixed.issuer ||
      !audience(id.aud, fixed.clientId) ||
      !access ||
      access.iss !== fixed.issuer ||
      !audience(access.aud, fixed.audience) ||
      typeof id.auth_time !== "number" ||
      !Number.isSafeInteger(id.auth_time) ||
      id.auth_time < 0
    )
      throw fail();
  };
  return {
    async initialize() {
      clear();
      const captured = generation;
      const sdk = createClient({
        url: issuer.origin,
        realm: decodeURIComponent(issuer.pathname.slice(8)),
        clientId: fixed.clientId,
      });
      client = sdk;
      const callback = window.location.pathname === "/auth/callback";
      try {
        const result = await bounded(
          sdk
            .init({
              flow: "standard",
              pkceMethod: "S256",
              useNonce: true,
              responseMode: "query",
              redirectUri: fixed.redirectUri,
              checkLoginIframe: false,
              enableLogging: false,
              scope: "openid",
              logoutMethod: "GET",
            })
            .then((result) => {
              if (captured !== generation) {
                wipe(sdk);
                throw fail();
              }
              return result;
            }),
        );
        if (captured !== generation) throw fail();
        if (!result) {
          if (callback) throw fail();
          return false;
        }
        if (!callback) throw fail();
        validate(sdk);
        authenticated = true;
        started =
          ((sdk.idTokenParsed!.auth_time as number) + (sdk.timeSkew ?? 0)) *
          1000;
        return true;
      } catch {
        if (captured === generation) clear();
        wipe(sdk);
        throw fail();
      } finally {
        if (callback) history.replaceState(null, "", "/auth/callback");
      }
    },
    async getValidAccessToken() {
      if (!client || !authenticated) throw new SessionFailure(401);
      const sdk = client,
        captured = generation;
      try {
        await bounded(
          sdk.updateToken(60).then((result) => {
            if (captured !== generation) {
              wipe(sdk);
              throw fail();
            }
            return result;
          }),
        );
        if (captured !== generation || !authenticated)
          throw new SessionFailure(401);
        validate(sdk);
        return sdk.token!;
      } catch {
        const invalid = !sdk.authenticated;
        if (captured === generation) clear();
        wipe(sdk);
        throw new SessionFailure(invalid ? 401 : 503);
      }
    },
    async login() {
      if (!client) throw fail();
      await client.login({ redirectUri: fixed.redirectUri, scope: "openid" });
    },
    async logout() {
      const sdk = client;
      clear();
      if (!sdk) throw fail();
      await sdk.logout({
        redirectUri: fixed.logoutRedirectUri,
        logoutMethod: "GET",
      });
    },
    clear,
    sessionStartedAt: () => started,
  };
}
