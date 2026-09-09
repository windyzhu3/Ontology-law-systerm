import createClient from "openapi-fetch";

import type { operations, paths } from "../../generated/api/schema";
import { provenWriteOutcome } from "../session/recoveryOutcome";
import { type RecoveryStore } from "../session/recoveryMarker";
import {
  createSessionTransport,
  matchesReceipt,
  TransportError,
  type WorkbenchSession,
} from "../../lib/sessionTransport";
import {
  validIdentityQuery,
  validIdentityRead,
  validIdentityOriginal,
  type IdentityOriginalWrite,
  type IdentityReadOperation,
} from "./identityContract";

export type { IdentityOriginalWrite } from "./identityContract";

const identityTag = /^"identity\.[A-Za-z0-9_-]{43}"$/;

function assertOriginal(original: IdentityOriginalWrite): void {
  if (!validIdentityOriginal(original))
    throw new Error("原身份管理请求不可用，请重新核对。");
}

/** Identity-admin adapter. Credentials are read from the current session per request. */
export function createIdentityApi(
  recovery: RecoveryStore,
  fetcher?: (request: Request) => Promise<Response>,
  baseUrl = window.location.origin,
) {
  const client = createClient<paths>({
    baseUrl,
    ...(fetcher ? { fetch: fetcher } : {}),
  });
  const transport = createSessionTransport(false);
  const read = async <T>(
    operation: IdentityReadOperation,
    session: WorkbenchSession,
    query: Record<string, unknown>,
    signal: AbortSignal,
    request: () => Promise<{ response: Response; data?: T; error?: unknown }>,
  ) => {
    if (!validIdentityQuery(operation, query))
      throw new Error("身份管理查询条件不可用，请重新核对。");
    const result = await request();
    transport.assertCurrent(session, signal);
    if (
      result.response.ok &&
      (result.response.status !== 200 ||
        !result.response.headers.get("Cache-Control")?.toLowerCase().includes("no-store") ||
        !validIdentityRead(operation, result.data, query))
    )
      throw new Error("身份管理数据暂时不可用，请刷新后重试。");
    return transport.checked(session, signal, result);
  };
  return {
    recovery,
    async listIdentityProviderUsers(
      session: WorkbenchSession,
      query: operations["listIdentityProviderUsers"]["parameters"]["query"],
      signal: AbortSignal,
    ) {
      return read("listIdentityProviderUsers", session, query, signal, async () =>
        client.GET("/api/v1/admin/identity/provider-users", {
          params: { query },
          headers: await transport.auth(session, signal),
          signal,
          cache: "no-store",
        }),
      );
    },
    async getIdentityAdminOptions(
      session: WorkbenchSession,
      query: operations["getIdentityAdminOptions"]["parameters"]["query"],
      signal: AbortSignal,
    ) {
      return read("getIdentityAdminOptions", session, query, signal, async () =>
        client.GET("/api/v1/admin/identity/options", {
          params: { query },
          headers: await transport.auth(session, signal),
          signal,
          cache: "no-store",
        }),
      );
    },
    async listIdentityPrincipals(
      session: WorkbenchSession,
      query: NonNullable<operations["listIdentityPrincipals"]["parameters"]["query"]>,
      signal: AbortSignal,
    ) {
      return read("listIdentityPrincipals", session, query, signal, async () =>
        client.GET("/api/v1/admin/identity/principals", {
          params: { query },
          headers: await transport.auth(session, signal),
          signal,
          cache: "no-store",
        }),
      );
    },
    async listOrganizationUnits(
      session: WorkbenchSession,
      query: NonNullable<operations["listOrganizationUnits"]["parameters"]["query"]>,
      signal: AbortSignal,
    ) {
      return read("listOrganizationUnits", session, query, signal, async () =>
        client.GET("/api/v1/admin/identity/organizations", {
          params: { query },
          headers: await transport.auth(session, signal),
          signal,
          cache: "no-store",
        }),
      );
    },
    async listAppointments(
      session: WorkbenchSession,
      query: NonNullable<operations["listAppointments"]["parameters"]["query"]>,
      signal: AbortSignal,
    ) {
      return read("listAppointments", session, query, signal, async () =>
        client.GET("/api/v1/admin/identity/appointments", {
          params: { query },
          headers: await transport.auth(session, signal),
          signal,
          cache: "no-store",
        }),
      );
    },
    async listAuthorityGrants(
      session: WorkbenchSession,
      query: NonNullable<operations["listAuthorityGrants"]["parameters"]["query"]>,
      signal: AbortSignal,
    ) {
      return read("listAuthorityGrants", session, query, signal, async () =>
        client.GET("/api/v1/admin/identity/authority-grants", {
          params: { query },
          headers: await transport.auth(session, signal),
          signal,
          cache: "no-store",
        }),
      );
    },
    async write(
      session: WorkbenchSession,
      original: IdentityOriginalWrite,
      signal: AbortSignal,
    ) {
      assertOriginal(original);
      const headers = {
        ...(await transport.auth(session, signal)),
        "Idempotency-Key": original.key,
        ...("ifMatch" in original ? { "If-Match": original.ifMatch } : {}),
      };
      const marker = recovery.reserveWrite(
        original.key,
        original.commandType,
        session.actorScopeKey,
        original,
      );
      transport.assertCurrent(session, signal);
      let result: { response: Response; data?: unknown; error?: unknown };
      switch (original.commandType) {
        case "CREATE_IDENTITY_PRINCIPAL":
          result = await client.POST("/api/v1/admin/identity/principals", {
            params: { header: { "Idempotency-Key": original.key } }, headers, body: original.body, signal,
          });
          break;
        case "RENAME_IDENTITY_PRINCIPAL":
          result = await client.PATCH("/api/v1/admin/identity/principals/{id}/display-name", {
            params: { path: { id: original.targetId }, header: { "Idempotency-Key": original.key, "If-Match": original.ifMatch } }, headers, body: original.body, signal,
          });
          break;
        case "SUSPEND_IDENTITY_PRINCIPAL":
          result = await client.POST("/api/v1/admin/identity/principals/{id}/suspend", {
            params: { path: { id: original.targetId }, header: { "Idempotency-Key": original.key, "If-Match": original.ifMatch } }, headers, body: original.body, signal,
          });
          break;
        case "RESUME_IDENTITY_PRINCIPAL":
          result = await client.POST("/api/v1/admin/identity/principals/{id}/resume", {
            params: { path: { id: original.targetId }, header: { "Idempotency-Key": original.key, "If-Match": original.ifMatch } }, headers, body: original.body, signal,
          });
          break;
        case "DISABLE_IDENTITY_PRINCIPAL":
          result = await client.POST("/api/v1/admin/identity/principals/{id}/disable", {
            params: { path: { id: original.targetId }, header: { "Idempotency-Key": original.key, "If-Match": original.ifMatch } }, headers, body: original.body, signal,
          });
          break;
        case "CREATE_ORGANIZATION_UNIT":
          result = await client.POST("/api/v1/admin/identity/organizations", {
            params: { header: { "Idempotency-Key": original.key } }, headers, body: original.body, signal,
          });
          break;
        case "RENAME_ORGANIZATION_UNIT":
          result = await client.PATCH("/api/v1/admin/identity/organizations/{id}/display-name", {
            params: { path: { id: original.targetId }, header: { "Idempotency-Key": original.key, "If-Match": original.ifMatch } }, headers, body: original.body, signal,
          });
          break;
        case "CLOSE_ORGANIZATION_UNIT":
          result = await client.POST("/api/v1/admin/identity/organizations/{id}/close", {
            params: { path: { id: original.targetId }, header: { "Idempotency-Key": original.key, "If-Match": original.ifMatch } }, headers, body: original.body, signal,
          });
          break;
        case "CREATE_APPOINTMENT":
          result = await client.POST("/api/v1/admin/identity/appointments", {
            params: { header: { "Idempotency-Key": original.key } }, headers, body: original.body, signal,
          });
          break;
        case "SUSPEND_APPOINTMENT":
          result = await client.POST("/api/v1/admin/identity/appointments/{id}/suspend", {
            params: { path: { id: original.targetId }, header: { "Idempotency-Key": original.key, "If-Match": original.ifMatch } }, headers, body: original.body, signal,
          });
          break;
        case "RESUME_APPOINTMENT":
          result = await client.POST("/api/v1/admin/identity/appointments/{id}/resume", {
            params: { path: { id: original.targetId }, header: { "Idempotency-Key": original.key, "If-Match": original.ifMatch } }, headers, body: original.body, signal,
          });
          break;
        case "END_APPOINTMENT":
          result = await client.POST("/api/v1/admin/identity/appointments/{id}/end", {
            params: { path: { id: original.targetId }, header: { "Idempotency-Key": original.key, "If-Match": original.ifMatch } }, headers, body: original.body, signal,
          });
          break;
        case "CREATE_AUTHORITY_GRANT":
          result = await client.POST("/api/v1/admin/identity/authority-grants", {
            params: { header: { "Idempotency-Key": original.key } }, headers, body: original.body, signal,
          });
          break;
        case "REVOKE_AUTHORITY_GRANT":
          result = await client.POST("/api/v1/admin/identity/authority-grants/{id}/revoke", {
            params: { path: { id: original.targetId }, header: { "Idempotency-Key": original.key, "If-Match": original.ifMatch } }, headers, body: original.body, signal,
          });
          break;
      }
      transport.assertCurrent(session, signal);
      if (!result.response.ok && provenWriteOutcome(result.error, result.response.status, marker)) {
        recovery.clear(marker);
        const problem = result.error as { code: string; retryPolicy?: string; currentETag?: { value?: string } };
        throw new TransportError(result.response.status, problem.code, true, problem.retryPolicy, problem.currentETag?.value);
      }
      if (result.response.ok) {
        const expectedStatus = original.commandType.startsWith("CREATE_") ? 201 : 200;
        if (
          result.response.status !== expectedStatus ||
          !matchesReceipt(result.data, marker) ||
          (result.data.outcome === "NO_CHANGE" &&
            ![
              "RENAME_IDENTITY_PRINCIPAL",
              "RENAME_ORGANIZATION_UNIT",
            ].includes(original.commandType)) ||
          !result.response.headers
            .get("Cache-Control")
            ?.toLowerCase()
            .includes("no-store") ||
          !identityTag.test(result.response.headers.get("ETag") ?? "") ||
          result.response.headers.get("Location") !== `/api/v1/commands/${original.key}/receipt`
        )
          throw new Error("身份管理响应不可用，请刷新后核对结果。");
        recovery.clear(marker);
      }
      return transport.checked(session, signal, result);
    },
    async receipt(
      session: WorkbenchSession,
      key: string,
      signal: AbortSignal,
    ) {
      const marker = recovery.read();
      if (
        !marker ||
        marker.commandId !== key ||
        marker.actorScopeKey !== session.actorScopeKey
      )
        throw new Error("结果尚未确认，不能自动重发。");
      const result = await client.GET("/api/v1/commands/{commandId}/receipt", {
        params: { path: { commandId: key } },
        headers: await transport.auth(session, signal),
        cache: "no-store",
        signal,
      });
      transport.assertCurrent(session, signal);
      if (result.response.status === 200) {
        if (
          !result.response.headers
            .get("Cache-Control")
            ?.toLowerCase()
            .includes("no-store") ||
          !matchesReceipt(result.data, marker)
        )
          throw new Error("身份管理回执暂时不可用，请稍后重试。");
        recovery.clear(marker);
      }
      return transport.checked(session, signal, result);
    },
  };
}

export type IdentityApi = ReturnType<typeof createIdentityApi>;
