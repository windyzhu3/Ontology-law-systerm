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
interface InitialBinding {
  nonce: string;
  subject: string;
  idAudience: string;
  authenticationTime: number;
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
    started = NaN,
    original: InitialBinding | null = null;
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
    original = null;
    if (client) wipe(client);
  };
  const audienceKey = (a: unknown) => {
    const values =
      typeof a === "string"
        ? [a]
        : Array.isArray(a) && a.every((value) => typeof value === "string")
          ? a
          : null;
    return values?.length && new Set(values).size === values.length
      ? JSON.stringify([...values].sort())
      : null;
  };
  const validate = (sdk: Keycloak, initial: boolean) => {
    const id = sdk.idTokenParsed;
    const access = sdk.tokenParsed;
    const audience = (a: unknown, want: string) =>
      a === want || (Array.isArray(a) && a.includes(want));
    const idAudience = audienceKey(id?.aud);
    const nonceInvalid = initial
      ? typeof id?.nonce !== "string" || !id.nonce
      : !original ||
        (Object.hasOwn(id ?? {}, "nonce") && id?.nonce !== original.nonce);
    const bindingInvalid = initial
      ? typeof id?.sub !== "string" || !id.sub || !idAudience
      : !original ||
        id?.sub !== original.subject ||
        idAudience !== original.idAudience ||
        id?.auth_time !== original.authenticationTime;
    if (
      !sdk.authenticated ||
      !sdk.token ||
      !sdk.refreshToken ||
      !sdk.idToken ||
      !id ||
      nonceInvalid ||
      bindingInvalid ||
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
    return idAudience!;
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
        const idAudience = validate(sdk, true);
        original = {
          nonce: sdk.idTokenParsed!.nonce as string,
          subject: sdk.idTokenParsed!.sub!,
          idAudience,
          authenticationTime: sdk.idTokenParsed!.auth_time as number,
        };
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
        validate(sdk, false);
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
