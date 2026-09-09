import createClient, { type Middleware } from "openapi-fetch";

import type { components, operations, paths } from "../../generated/api/schema";
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
type S = components["schemas"];
type IdentitySuccessReceipt =
  | S["IdentityPrincipalCommandReceiptV1"]
  | S["OrganizationUnitCommandReceiptV1"]
  | S["AppointmentCommandReceiptV1"]
  | S["AuthorityGrantCommandReceiptV1"];

const validIdentitySuccessReceipt = (
  value: unknown,
  marker: Parameters<typeof matchesReceipt>[1],
): value is IdentitySuccessReceipt =>
  matchesReceipt(value, marker) && value.outcome !== "REJECTED";

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
  const read = async <Operation extends IdentityReadOperation>(
    operation: Operation,
    session: WorkbenchSession,
    query: Record<string, unknown>,
    signal: AbortSignal,
    request: (middleware: Middleware, headers: HeadersInit) => Promise<{
      response: Response;
      data?: unknown;
      error?: unknown;
    }>,
  ) => {
    if (!validIdentityQuery(operation, query))
      throw new Error("身份管理查询条件不可用，请重新核对。");
    const headers = await transport.auth(session, signal);
    const result = await transport.request(session, signal, (middleware) =>
      request(middleware, headers),
    );
    transport.assertCurrent(session, signal);
    if (!result.response.ok) {
      transport.checked(session, signal, result);
      throw new Error("身份管理数据暂时不可用，请刷新后重试。");
    }
    if (
      result.response.status !== 200 ||
      !result.response.headers.get("Cache-Control")?.toLowerCase().includes("no-store") ||
      !validIdentityRead(operation, result.data, query)
    )
      throw new Error("身份管理数据暂时不可用，请刷新后重试。");
    return {
      data: result.data,
      status: result.response.status,
      etag: result.response.headers.get("ETag"),
    };
  };
  return {
    recovery,
    async listIdentityProviderUsers(
      session: WorkbenchSession,
      query: operations["listIdentityProviderUsers"]["parameters"]["query"],
      signal: AbortSignal,
    ) {
      return read("listIdentityProviderUsers", session, query, signal, async (middleware, headers) =>
        client.GET("/api/v1/admin/identity/provider-users", {
          params: { query },
          headers,
          signal,
          cache: "no-store",
          middleware: [middleware],
        }),
      );
    },
    async getIdentityAdminOptions(
      session: WorkbenchSession,
      query: operations["getIdentityAdminOptions"]["parameters"]["query"],
      signal: AbortSignal,
    ) {
      return read("getIdentityAdminOptions", session, query, signal, async (middleware, headers) =>
        client.GET("/api/v1/admin/identity/options", {
          params: { query },
          headers,
          signal,
          cache: "no-store",
          middleware: [middleware],
        }),
      );
    },
    async listIdentityPrincipals(
      session: WorkbenchSession,
      query: NonNullable<operations["listIdentityPrincipals"]["parameters"]["query"]>,
      signal: AbortSignal,
    ) {
      return read("listIdentityPrincipals", session, query, signal, async (middleware, headers) =>
        client.GET("/api/v1/admin/identity/principals", {
          params: { query },
          headers,
          signal,
          cache: "no-store",
          middleware: [middleware],
        }),
      );
    },
    async listOrganizationUnits(
      session: WorkbenchSession,
      query: NonNullable<operations["listOrganizationUnits"]["parameters"]["query"]>,
      signal: AbortSignal,
    ) {
      return read("listOrganizationUnits", session, query, signal, async (middleware, headers) =>
        client.GET("/api/v1/admin/identity/organizations", {
          params: { query },
          headers,
          signal,
          cache: "no-store",
          middleware: [middleware],
        }),
      );
    },
    async listAppointments(
      session: WorkbenchSession,
      query: NonNullable<operations["listAppointments"]["parameters"]["query"]>,
      signal: AbortSignal,
    ) {
      return read("listAppointments", session, query, signal, async (middleware, headers) =>
        client.GET("/api/v1/admin/identity/appointments", {
          params: { query },
          headers,
          signal,
          cache: "no-store",
          middleware: [middleware],
        }),
      );
    },
    async listAuthorityGrants(
      session: WorkbenchSession,
      query: NonNullable<operations["listAuthorityGrants"]["parameters"]["query"]>,
      signal: AbortSignal,
    ) {
      return read("listAuthorityGrants", session, query, signal, async (middleware, headers) =>
        client.GET("/api/v1/admin/identity/authority-grants", {
          params: { query },
          headers,
          signal,
          cache: "no-store",
          middleware: [middleware],
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
      const result = await transport.request(session, signal, async (middleware) => {
        let result: { response: Response; data?: unknown; error?: unknown };
        switch (original.commandType) {
        case "CREATE_IDENTITY_PRINCIPAL":
          result = await client.POST("/api/v1/admin/identity/principals", {
            params: { header: { "Idempotency-Key": original.key } }, headers, body: original.body, signal, middleware: [middleware],
          });
          break;
        case "RENAME_IDENTITY_PRINCIPAL":
          result = await client.PATCH("/api/v1/admin/identity/principals/{id}/display-name", {
            params: { path: { id: original.targetId }, header: { "Idempotency-Key": original.key, "If-Match": original.ifMatch } }, headers, body: original.body, signal, middleware: [middleware],
          });
          break;
        case "SUSPEND_IDENTITY_PRINCIPAL":
          result = await client.POST("/api/v1/admin/identity/principals/{id}/suspend", {
            params: { path: { id: original.targetId }, header: { "Idempotency-Key": original.key, "If-Match": original.ifMatch } }, headers, body: original.body, signal, middleware: [middleware],
          });
          break;
        case "RESUME_IDENTITY_PRINCIPAL":
          result = await client.POST("/api/v1/admin/identity/principals/{id}/resume", {
            params: { path: { id: original.targetId }, header: { "Idempotency-Key": original.key, "If-Match": original.ifMatch } }, headers, body: original.body, signal, middleware: [middleware],
          });
          break;
        case "DISABLE_IDENTITY_PRINCIPAL":
          result = await client.POST("/api/v1/admin/identity/principals/{id}/disable", {
            params: { path: { id: original.targetId }, header: { "Idempotency-Key": original.key, "If-Match": original.ifMatch } }, headers, body: original.body, signal, middleware: [middleware],
          });
          break;
        case "CREATE_ORGANIZATION_UNIT":
          result = await client.POST("/api/v1/admin/identity/organizations", {
            params: { header: { "Idempotency-Key": original.key } }, headers, body: original.body, signal, middleware: [middleware],
          });
          break;
        case "RENAME_ORGANIZATION_UNIT":
          result = await client.PATCH("/api/v1/admin/identity/organizations/{id}/display-name", {
            params: { path: { id: original.targetId }, header: { "Idempotency-Key": original.key, "If-Match": original.ifMatch } }, headers, body: original.body, signal, middleware: [middleware],
          });
          break;
        case "CLOSE_ORGANIZATION_UNIT":
          result = await client.POST("/api/v1/admin/identity/organizations/{id}/close", {
            params: { path: { id: original.targetId }, header: { "Idempotency-Key": original.key, "If-Match": original.ifMatch } }, headers, body: original.body, signal, middleware: [middleware],
          });
          break;
        case "CREATE_APPOINTMENT":
          result = await client.POST("/api/v1/admin/identity/appointments", {
            params: { header: { "Idempotency-Key": original.key } }, headers, body: original.body, signal, middleware: [middleware],
          });
          break;
        case "SUSPEND_APPOINTMENT":
          result = await client.POST("/api/v1/admin/identity/appointments/{id}/suspend", {
            params: { path: { id: original.targetId }, header: { "Idempotency-Key": original.key, "If-Match": original.ifMatch } }, headers, body: original.body, signal, middleware: [middleware],
          });
          break;
        case "RESUME_APPOINTMENT":
          result = await client.POST("/api/v1/admin/identity/appointments/{id}/resume", {
            params: { path: { id: original.targetId }, header: { "Idempotency-Key": original.key, "If-Match": original.ifMatch } }, headers, body: original.body, signal, middleware: [middleware],
          });
          break;
        case "END_APPOINTMENT":
          result = await client.POST("/api/v1/admin/identity/appointments/{id}/end", {
            params: { path: { id: original.targetId }, header: { "Idempotency-Key": original.key, "If-Match": original.ifMatch } }, headers, body: original.body, signal, middleware: [middleware],
          });
          break;
        case "CREATE_AUTHORITY_GRANT":
          result = await client.POST("/api/v1/admin/identity/authority-grants", {
            params: { header: { "Idempotency-Key": original.key } }, headers, body: original.body, signal, middleware: [middleware],
          });
          break;
        case "REVOKE_AUTHORITY_GRANT":
          result = await client.POST("/api/v1/admin/identity/authority-grants/{id}/revoke", {
            params: { path: { id: original.targetId }, header: { "Idempotency-Key": original.key, "If-Match": original.ifMatch } }, headers, body: original.body, signal, middleware: [middleware],
          });
          break;
        }
        return result;
      });
      transport.assertCurrent(session, signal);
      if (!result.response.ok) {
        if (provenWriteOutcome(result.error, result.response.status, marker)) {
          recovery.clear(marker);
          const problem = result.error as { code: string; retryPolicy?: string; currentETag?: { value?: string } };
          throw new TransportError(result.response.status, problem.code, true, problem.retryPolicy, problem.currentETag?.value);
        }
        transport.checked(session, signal, result);
        throw new Error("身份管理响应不可用，请刷新后核对结果。");
      }
      const expectedStatus = original.commandType.startsWith("CREATE_") ? 201 : 200;
      if (
        result.response.status !== expectedStatus ||
        !validIdentitySuccessReceipt(result.data, marker) ||
        !result.response.headers
          .get("Cache-Control")
          ?.toLowerCase()
          .includes("no-store") ||
        !identityTag.test(result.response.headers.get("ETag") ?? "") ||
        result.response.headers.get("Location") !== `/api/v1/commands/${original.key}/receipt`
      )
        throw new Error("身份管理响应不可用，请刷新后核对结果。");
      recovery.clear(marker);
      return {
        data: result.data,
        status: result.response.status,
        etag: result.response.headers.get("ETag"),
      };
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
      const headers = await transport.auth(session, signal);
      const result = await transport.request(session, signal, (middleware) =>
        client.GET("/api/v1/commands/{commandId}/receipt", {
          params: { path: { commandId: key } },
          headers,
          cache: "no-store",
          signal,
          middleware: [middleware],
        }),
      );
      transport.assertCurrent(session, signal);
      if (!result.response.ok) {
        transport.checked(session, signal, result);
        throw new Error("身份管理回执暂时不可用，请稍后重试。");
      }
      if (
        result.response.status !== 200 ||
        !result.response.headers
          .get("Cache-Control")
          ?.toLowerCase()
          .includes("no-store") ||
        !matchesReceipt(result.data, marker)
      )
        throw new Error("身份管理回执暂时不可用，请稍后重试。");
      recovery.clear(marker);
      return {
        data: result.data,
        status: result.response.status,
        etag: result.response.headers.get("ETag"),
      };
    },
  };
}

export type IdentityApi = ReturnType<typeof createIdentityApi>;
