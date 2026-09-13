import { beforeEach, describe, expect, it, vi } from "vitest";

import { RecoveryStore, markerKey } from "../session/recoveryMarker";
import { createWorkbenchApi, type OriginalWrite, type WorkbenchSession } from "../../lib/api";
import { TransportError } from "../../lib/sessionTransport";
import {
  createIdentityApi,
  type IdentityOriginalWrite,
} from "./identityApi";

const commandId = "019c7000-0000-7000-8000-000000000001";
const targetId = "019c7000-0000-7000-8000-000000000002";
const appointmentId = "019c7000-0000-7000-8000-000000000003";
const organizationId = "019c7000-0000-7000-8000-000000000004";
const principalId = "019c7000-0000-7000-8000-000000000005";
const scope = "ask1.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const identityETag = '"identity.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"';

const session: WorkbenchSession = {
  identityEpoch: 1,
  actorScopeKey: scope,
  selectedAppointmentId: appointmentId,
  selectedOnBehalfAppointmentId: null,
  getValidAccessToken: async () => "current-token",
  isCurrent: () => true,
  invalidate: () => {},
};

type CommandType = IdentityOriginalWrite["commandType"];

const writes = [
  {
    original: {
      commandType: "CREATE_IDENTITY_PRINCIPAL",
      key: commandId,
      body: { providerUserSelector: "c2VsZWN0b3I", displayName: "张三" },
    },
    method: "POST",
    path: "/api/v1/admin/identity/principals",
    factType: "IDENTITY_PRINCIPAL",
    status: 201,
  },
  {
    original: {
      commandType: "RENAME_IDENTITY_PRINCIPAL",
      key: commandId,
      targetId,
      ifMatch: identityETag,
      body: { displayName: "张三（新）" },
    },
    method: "PATCH",
    path: `/api/v1/admin/identity/principals/${targetId}/display-name`,
    factType: "IDENTITY_PRINCIPAL",
    status: 200,
  },
  ...[
    ["SUSPEND_IDENTITY_PRINCIPAL", "suspend"],
    ["RESUME_IDENTITY_PRINCIPAL", "resume"],
    ["DISABLE_IDENTITY_PRINCIPAL", "disable"],
  ].map(([commandType, action]) => ({
    original: {
      commandType,
      key: commandId,
      targetId,
      ifMatch: identityETag,
      body: { reasonCode: "ADMINISTRATIVE_ACTION" },
    },
    method: "POST",
    path: `/api/v1/admin/identity/principals/${targetId}/${action}`,
    factType: "IDENTITY_PRINCIPAL",
    status: 200,
  })),
  {
    original: {
      commandType: "CREATE_ORGANIZATION_UNIT",
      key: commandId,
      body: {
        parentOrganizationId: organizationId,
        code: "EAST_TEAM",
        displayName: "华东组",
      },
    },
    method: "POST",
    path: "/api/v1/admin/identity/organizations",
    factType: "ORGANIZATION_UNIT",
    status: 201,
  },
  {
    original: {
      commandType: "RENAME_ORGANIZATION_UNIT",
      key: commandId,
      targetId,
      ifMatch: identityETag,
      body: { displayName: "华东一组" },
    },
    method: "PATCH",
    path: `/api/v1/admin/identity/organizations/${targetId}/display-name`,
    factType: "ORGANIZATION_UNIT",
    status: 200,
  },
  {
    original: {
      commandType: "CLOSE_ORGANIZATION_UNIT",
      key: commandId,
      targetId,
      ifMatch: identityETag,
      body: { reasonCode: "SECURITY_RESPONSE" },
    },
    method: "POST",
    path: `/api/v1/admin/identity/organizations/${targetId}/close`,
    factType: "ORGANIZATION_UNIT",
    status: 200,
  },
  {
    original: {
      commandType: "CREATE_APPOINTMENT",
      key: commandId,
      body: {
        principalId,
        organizationId,
        roleCode: "CONTACT_OPERATOR",
        effectiveFrom: "2026-09-09T00:00:00Z",
        effectiveUntil: null,
      },
    },
    method: "POST",
    path: "/api/v1/admin/identity/appointments",
    factType: "APPOINTMENT",
    status: 201,
  },
  ...[
    ["SUSPEND_APPOINTMENT", "suspend"],
    ["RESUME_APPOINTMENT", "resume"],
    ["END_APPOINTMENT", "end"],
  ].map(([commandType, action]) => ({
    original: {
      commandType,
      key: commandId,
      targetId,
      ifMatch: identityETag,
      body: { reasonCode: "ADMINISTRATIVE_ACTION" },
    },
    method: "POST",
    path: `/api/v1/admin/identity/appointments/${targetId}/${action}`,
    factType: "APPOINTMENT",
    status: 200,
  })),
  {
    original: {
      commandType: "CREATE_AUTHORITY_GRANT",
      key: commandId,
      body: {
        appointmentId,
        authorityCode: "SALES_CONTACT_OWNER",
        scopeOrganizationId: organizationId,
        validFrom: "2026-09-09T00:00:00Z",
        validUntil: null,
      },
    },
    method: "POST",
    path: "/api/v1/admin/identity/authority-grants",
    factType: "AUTHORITY_GRANT",
    status: 201,
  },
  {
    original: {
      commandType: "REVOKE_AUTHORITY_GRANT",
      key: commandId,
      targetId,
      ifMatch: identityETag,
      body: { reasonCode: "SECURITY_RESPONSE" },
    },
    method: "POST",
    path: `/api/v1/admin/identity/authority-grants/${targetId}/revoke`,
    factType: "AUTHORITY_GRANT",
    status: 200,
  },
] as const;

const terminal = (factType: string, outcome: "SUCCEEDED" | "NO_CHANGE" = "SUCCEEDED") => ({
  commandId,
  receiptId: targetId,
  outcome,
  completedAt: "2026-09-09T01:00:00Z",
  resultFact: { factType, factRef: "opaque-identity-reference", revision: 0 },
});

const json = (body: unknown, status = 200, extra: HeadersInit = {}) =>
  new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json",
      "cache-control": "no-store",
      ...extra,
    },
  });

const validationProblem = {
  type: "https://example.test/problems/validation-failed",
  title: "校验失败",
  detail: "请核对内容。",
  instance: "/problems/occurrence",
  status: 400,
  code: "VALIDATION_FAILED",
  retryPolicy: "SAME_KEY_AFTER_FIX",
  fieldErrors: [{ pointer: "/displayName", code: "REQUIRED", detail: "必填" }],
};

beforeEach(() => sessionStorage.clear());

const choice = { id: targetId, label: "安全显示名" };
const readCases = [
  {
    operation: "listIdentityProviderUsers",
    query: { search: "alice", limit: 1 },
    body: {
      items: [{ label: "alice", selector: "c2VsZWN0b3I" }],
      nextCursor: null,
    },
  },
  {
    operation: "getIdentityAdminOptions",
    query: {
      page: "APPOINTMENTS",
      optionKind: "ORGANIZATION",
      limit: 20,
      cursor: "cursor-token",
    },
    body: {
      page: "APPOINTMENTS",
      optionKind: "ORGANIZATION",
      candidates: { items: [choice], nextCursor: null },
      roleCodes: ["INTAKE_OPERATOR", "ROUTING_SUPERVISOR", "CONTACT_OPERATOR"],
      grantableAuthorityCodes: [],
    },
  },
  {
    operation: "listIdentityPrincipals",
    query: { limit: 50, cursor: "cursor-token" },
    body: {
      items: [{ id: targetId, displayName: choice.label, state: "ACTIVE", etag: identityETag }],
      nextCursor: null,
    },
  },
  {
    operation: "listOrganizationUnits",
    query: { limit: 50, cursor: "cursor-token" },
    body: {
      items: [{ id: targetId, code: "EAST_TEAM", displayName: choice.label, parentOrganizationId: organizationId, state: "ACTIVE", etag: identityETag }],
      nextCursor: null,
    },
  },
  {
    operation: "listAppointments",
    query: { limit: 50, cursor: "cursor-token" },
    body: {
      items: [{ id: targetId, principal: choice, organization: { id: organizationId, label: "华东组" }, roleCode: "CONTACT_OPERATOR", effectiveFrom: "2026-09-09T00:00:00Z", effectiveUntil: null, state: "ACTIVE", etag: identityETag }],
      nextCursor: null,
    },
  },
  {
    operation: "listAuthorityGrants",
    query: { limit: 50, cursor: "cursor-token" },
    body: {
      items: [{ id: targetId, appointment: choice, authorityCode: "SALES_CONTACT_OWNER", scopeOrganization: { id: organizationId, label: "华东组" }, validFrom: "2026-09-09T00:00:00Z", validUntil: null, state: "ACTIVE", etag: identityETag }],
      nextCursor: null,
    },
  },
] as const;

describe("identity reads", () => {
  it.each(readCases)("maps $operation query and own-identity headers", async ({ operation, query, body }) => {
    const captured: Request[] = [];
    const api = createIdentityApi(
      new RecoveryStore(sessionStorage),
      async (request) => {
        captured.push(request);
        return json(body);
      },
      "https://api.example.test",
    );
    const signal = new AbortController().signal;
    switch (operation) {
      case "listIdentityProviderUsers":
        await api.listIdentityProviderUsers(session, query, signal);
        break;
      case "getIdentityAdminOptions":
        await api.getIdentityAdminOptions(session, query, signal);
        break;
      case "listIdentityPrincipals":
        await api.listIdentityPrincipals(session, query, signal);
        break;
      case "listOrganizationUnits":
        await api.listOrganizationUnits(session, query, signal);
        break;
      case "listAppointments":
        await api.listAppointments(session, query, signal);
        break;
      case "listAuthorityGrants":
        await api.listAuthorityGrants(session, query, signal);
        break;
    }

    expect(captured).toHaveLength(1);
    const request = captured[0];
    expect(request.method).toBe("GET");
    expect(request.cache).toBe("no-store");
    expect(request.headers.get("Authorization")).toBe("Bearer current-token");
    expect(request.headers.get("X-Appointment-Id")).toBe(appointmentId);
    expect(request.headers.get("X-On-Behalf-Appointment-Id")).toBeNull();
    expect(Object.fromEntries(new URL(request.url).searchParams)).toEqual(
      Object.fromEntries(Object.entries(query).map(([key, value]) => [key, String(value)])),
    );
  });

  it.each([
    ["provider candidates never exceed one", "listIdentityProviderUsers", { ...readCases[0].body, items: [readCases[0].body.items[0], readCases[0].body.items[0]] }],
    ["provider lookup never returns a continuation", "listIdentityProviderUsers", { ...readCases[0].body, nextCursor: "unexpected" }],
    ["principal pages never exceed fifty", "listIdentityPrincipals", { ...readCases[2].body, items: Array.from({ length: 51 }, () => readCases[2].body.items[0]) }],
    ["SERVICE data is not accepted", "listOrganizationUnits", { ...readCases[3].body, items: [{ ...readCases[3].body.items[0], principalKind: "SERVICE" }] }],
    ["appointment enums remain closed", "listAppointments", { ...readCases[4].body, items: [{ ...readCases[4].body.items[0], roleCode: "SERVICE_OPERATOR" }] }],
    ["authority enums remain closed", "listAuthorityGrants", { ...readCases[5].body, items: [{ ...readCases[5].body.items[0], authorityCode: "SYSTEM_ADMIN" }] }],
    ["options cannot expand grantable authority", "getIdentityAdminOptions", { ...readCases[1].body, grantableAuthorityCodes: ["IDENTITY_PRINCIPAL_MANAGE"] }],
  ])("rejects malformed read bodies: %s", async (_name, operation, body) => {
    const api = createIdentityApi(
      new RecoveryStore(sessionStorage),
      async () => json(body),
      "https://api.example.test",
    );
    const signal = new AbortController().signal;
    const entry = readCases.find((item) => item.operation === operation)!;
    let result: Promise<unknown>;
    switch (operation) {
      case "listIdentityProviderUsers":
        result = api.listIdentityProviderUsers(session, entry.query as typeof readCases[0]["query"], signal);
        break;
      case "getIdentityAdminOptions":
        result = api.getIdentityAdminOptions(session, entry.query as typeof readCases[1]["query"], signal);
        break;
      case "listIdentityPrincipals":
        result = api.listIdentityPrincipals(session, entry.query, signal);
        break;
      case "listOrganizationUnits":
        result = api.listOrganizationUnits(session, entry.query, signal);
        break;
      case "listAppointments":
        result = api.listAppointments(session, entry.query, signal);
        break;
      default:
        result = api.listAuthorityGrants(session, entry.query, signal);
    }
    await expect(result).rejects.toThrow("身份管理数据暂时不可用");
  });

  it.each([
    ["provider cursor", "listIdentityProviderUsers", { search: "alice", cursor: "not-allowed" }],
    ["blank provider search", "listIdentityProviderUsers", { search: "   " }],
    ["limit above fifty", "listIdentityPrincipals", { limit: 51 }],
    ["invalid option pair", "getIdentityAdminOptions", { page: "PRINCIPALS", optionKind: "ORGANIZATION" }],
  ])("rejects invalid query before dispatch: %s", async (_name, operation, query) => {
    const requests: Request[] = [];
    const api = createIdentityApi(
      new RecoveryStore(sessionStorage),
      async (request) => {
        requests.push(request);
        return json({});
      },
      "https://api.example.test",
    );
    const signal = new AbortController().signal;
    const call = operation === "listIdentityProviderUsers"
      ? api.listIdentityProviderUsers(session, query as typeof readCases[0]["query"], signal)
      : operation === "getIdentityAdminOptions"
        ? api.getIdentityAdminOptions(session, query as typeof readCases[1]["query"], signal)
        : api.listIdentityPrincipals(
            session,
            query as { limit?: number; cursor?: string },
            signal,
          );
    await expect(call).rejects.toThrow("查询条件不可用");
    expect(requests).toHaveLength(0);
  });

  it("rejects delegated identity and 304 without treating either as an empty list", async () => {
    const requests: Request[] = [];
    const delegated = { ...session, selectedOnBehalfAppointmentId: targetId };
    const api = createIdentityApi(
      new RecoveryStore(sessionStorage),
      async (request) => {
        requests.push(request);
        return new Response(null, { status: 304 });
      },
      "https://api.example.test",
    );
    await expect(
      api.listIdentityPrincipals(delegated, {}, new AbortController().signal),
    ).rejects.toThrow("不接受代办身份");
    expect(requests).toHaveLength(0);
    await expect(
      api.listIdentityPrincipals(session, {}, new AbortController().signal),
    ).rejects.toMatchObject({ status: 304 });
  });
});

describe("identity writes", () => {
  it.each(writes)(
    "maps $original.commandType to its exact request",
    async ({ original, method, path, factType, status }) => {
      const captured: Request[] = [];
      const recovery = new RecoveryStore(sessionStorage);
      const api = createIdentityApi(
        recovery,
        async (request) => {
          captured.push(request);
          return json(terminal(factType), status, {
            ETag: identityETag,
            Location: `/api/v1/commands/${commandId}/receipt`,
          });
        },
        "https://api.example.test",
      );

      await api.write(
        session,
        original as IdentityOriginalWrite,
        new AbortController().signal,
      );

      expect(captured).toHaveLength(1);
      const request = captured[0];
      expect(request.method).toBe(method);
      expect(new URL(request.url).pathname).toBe(path);
      expect(await request.clone().json()).toEqual(original.body);
      expect(request.headers.get("Authorization")).toBe("Bearer current-token");
      expect(request.headers.get("X-Appointment-Id")).toBe(appointmentId);
      expect(request.headers.get("X-On-Behalf-Appointment-Id")).toBeNull();
      expect(request.headers.get("Idempotency-Key")).toBe(commandId);
      expect(request.headers.get("If-Match")).toBe(
        "ifMatch" in original ? original.ifMatch : null,
      );
      expect(recovery.read()).toBeNull();
    },
  );

  it.each([
    ["IDENTITY_BINDING_CONFLICT", "NO", writes[0], undefined],
    ["IDENTITY_STATE_CONFLICT", "NEW_KEY_AFTER_REFRESH", writes[2], undefined],
    ["IDENTITY_SELF_LOCKOUT", "NO", writes[4], undefined],
    ["IDENTITY_LAST_ADMIN", "NO", writes[13], undefined],
    ["IDENTITY_ORGANIZATION_DEPENDENCY", "NEW_KEY_AFTER_ADMIN_FIX", writes[7], undefined],
    ["IDENTITY_RESPONSIBILITY_DEPENDENCY", "NEW_KEY_AFTER_ADMIN_FIX", writes[11], undefined],
    ["STALE_IDENTITY", "NEW_KEY_AFTER_REFRESH", writes[1], { resourceKind: "IDENTITY", value: identityETag }],
    ["IDENTITY_PRECONDITION_REQUIRED", "SAME_KEY_AFTER_FIX", writes[1], { resourceKind: "IDENTITY", value: identityETag }],
  ])("preserves safe %s retry semantics for later forms", async (code, retryPolicy, entry, currentETag) => {
    const status = code === "STALE_IDENTITY" ? 412 : code === "IDENTITY_PRECONDITION_REQUIRED" ? 428 : 409;
    const recovery = new RecoveryStore(sessionStorage);
    const api = createIdentityApi(
      recovery,
      async () =>
        json(
          {
            ...validationProblem,
            status,
            code,
            retryPolicy,
            fieldErrors: undefined,
            ...(currentETag ? { currentETag } : {}),
          },
          status,
        ),
      "https://api.example.test",
    );
    const error = await api
      .write(session, entry.original as IdentityOriginalWrite, new AbortController().signal)
      .catch((reason: unknown) => reason);
    expect(error).toMatchObject({ status, code, retryPolicy, provenOutcome: true });
    expect(String((error as Error).message)).not.toContain("occurrence");
    expect(recovery.read()).toBeNull();
  });

  it.each(writes)(
    "retains the original $original.commandType clue on a mismatched success Fact",
    async ({ original, status }) => {
      const recovery = new RecoveryStore(sessionStorage);
      const api = createIdentityApi(
        recovery,
        async () =>
          json(terminal("LEAD"), status, {
            ETag: identityETag,
            Location: `/api/v1/commands/${commandId}/receipt`,
          }),
        "https://api.example.test",
      );
      await expect(
        api.write(session, original as IdentityOriginalWrite, new AbortController().signal),
      ).rejects.toThrow("身份管理响应不可用");
      expect(recovery.read()?.commandType).toBe(original.commandType);
    },
  );

  it.each(writes)(
    "recognizes an exact pre-slot error for $original.commandType without inventing a new key",
    async ({ original }) => {
      const recovery = new RecoveryStore(sessionStorage);
      const api = createIdentityApi(
        recovery,
        async () => json(validationProblem, 400),
        "https://api.example.test",
      );
      const error = await api
        .write(session, original as IdentityOriginalWrite, new AbortController().signal)
        .catch((reason: unknown) => reason);
      expect(error).toBeInstanceOf(TransportError);
      expect(error).toMatchObject({
        status: 400,
        code: "VALIDATION_FAILED",
        retryPolicy: "SAME_KEY_AFTER_FIX",
        provenOutcome: true,
      });
      expect(recovery.read()).toBeNull();
    },
  );

  it.each([
    writes[1],
    writes[6],
  ])("accepts NO_CHANGE only for rename command $original.commandType", async ({ original, factType }) => {
    const recovery = new RecoveryStore(sessionStorage);
    const api = createIdentityApi(
      recovery,
      async () => json(terminal(factType, "NO_CHANGE"), 200, {
        ETag: identityETag,
        Location: `/api/v1/commands/${commandId}/receipt`,
      }),
      "https://api.example.test",
    );
    await api.write(session, original as IdentityOriginalWrite, new AbortController().signal);
    expect(recovery.read()).toBeNull();
  });

  it("rejects an impossible lifecycle NO_CHANGE and retains its clue", async () => {
    const original = writes[2];
    const recovery = new RecoveryStore(sessionStorage);
    const api = createIdentityApi(
      recovery,
      async () => json(terminal(original.factType, "NO_CHANGE"), 200, {
        ETag: identityETag,
        Location: `/api/v1/commands/${commandId}/receipt`,
      }),
      "https://api.example.test",
    );
    await expect(
      api.write(session, original.original as IdentityOriginalWrite, new AbortController().signal),
    ).rejects.toThrow("身份管理响应不可用");
    expect(recovery.read()?.commandId).toBe(commandId);
  });

  it("requires no-store on a successful write disclosure", async () => {
    const recovery = new RecoveryStore(sessionStorage);
    const api = createIdentityApi(
      recovery,
      async () =>
        new Response(JSON.stringify(terminal("IDENTITY_PRINCIPAL")), {
          status: 201,
          headers: {
            "content-type": "application/json",
            ETag: identityETag,
            Location: `/api/v1/commands/${commandId}/receipt`,
          },
        }),
      "https://api.example.test",
    );
    await expect(
      api.write(session, writes[0].original, new AbortController().signal),
    ).rejects.toThrow("身份管理响应不可用");
    expect(recovery.read()?.commandId).toBe(commandId);
  });

  it("replays only the same retained object with freshly acquired credentials", async () => {
    const requests: Request[] = [];
    let token = "rotated-one";
    const rotatingSession = { ...session, getValidAccessToken: async () => token };
    const original = writes[0].original;
    const api = createIdentityApi(
      new RecoveryStore(sessionStorage),
      async (request) => {
        requests.push(request);
        throw new Error("response lost");
      },
      "https://api.example.test",
    );
    await expect(api.write(rotatingSession, original, new AbortController().signal)).rejects.toThrow();
    token = "rotated-two";
    await expect(api.write(rotatingSession, original, new AbortController().signal)).rejects.toThrow();
    expect(requests.map((request) => request.headers.get("Authorization"))).toEqual([
      "Bearer rotated-one",
      "Bearer rotated-two",
    ]);
    expect(await requests[0].clone().json()).toEqual(await requests[1].clone().json());
  });

  it.each([
    ["unknown command", { ...writes[0].original, commandType: "CREATE_ARBITRARY_IDENTITY" }],
    ["fabricated create target", { ...writes[0].original, targetId }],
    ["create If-Match", { ...writes[0].original, ifMatch: identityETag }],
    ["missing update target", { ...writes[1].original, targetId: undefined }],
    ["weak update ETag", { ...writes[1].original, ifMatch: `W/${identityETag}` }],
    ["extra body property", { ...writes[0].original, body: { ...writes[0].original.body, bearer: "secret" } }],
    ["management authority grant", { ...writes[12].original, body: { ...writes[12].original.body, authorityCode: "IDENTITY_PRINCIPAL_MANAGE" } }],
  ])("rejects a closed original request with %s before credentials or dispatch", async (_name, malformed) => {
    const requests: Request[] = [];
    const getValidAccessToken = vi.fn(async () => "must-not-be-read");
    const api = createIdentityApi(
      new RecoveryStore(sessionStorage),
      async (request) => {
        requests.push(request);
        return json({});
      },
      "https://api.example.test",
    );
    await expect(
      api.write(
        { ...session, getValidAccessToken },
        malformed as IdentityOriginalWrite,
        new AbortController().signal,
      ),
    ).rejects.toThrow("原身份管理请求不可用");
    expect(getValidAccessToken).not.toHaveBeenCalled();
    expect(requests).toHaveLength(0);
  });

  it("rechecks the session after reserving the marker and before dispatch", async () => {
    let current = true;
    const values = new Map<string, string>();
    const storage = {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => {
        values.set(key, value);
        current = false;
      },
      removeItem: (key: string) => values.delete(key),
    } as unknown as Storage;
    const requests: Request[] = [];
    const api = createIdentityApi(
      new RecoveryStore(storage),
      async (request) => {
        requests.push(request);
        return json(terminal("IDENTITY_PRINCIPAL"), 201);
      },
      "https://api.example.test",
    );
    await expect(
      api.write({ ...session, isCurrent: () => current }, writes[0].original, new AbortController().signal),
    ).rejects.toThrow("会话已变化");
    expect(requests).toHaveLength(0);
    expect(values.get(markerKey)).toBeDefined();
  });

  it("does not dispatch an already-cancelled identity request", async () => {
    const requests: Request[] = [];
    const controller = new AbortController();
    controller.abort();
    const api = createIdentityApi(
      new RecoveryStore(sessionStorage),
      async (request) => {
        requests.push(request);
        return json({});
      },
      "https://api.example.test",
    );
    await expect(api.write(session, writes[0].original, controller.signal)).rejects.toThrow("会话已变化");
    expect(requests).toHaveLength(0);
    expect(sessionStorage.getItem(markerKey)).toBeNull();
  });

  it.each([
    ["clone", (original: typeof writes[0]["original"]) => ({ ...original })],
    ["changed body", (original: typeof writes[0]["original"]) => ({ ...original, body: { ...original.body, displayName: "李四" } })],
    ["changed key", (original: typeof writes[0]["original"]) => ({ ...original, key: targetId })],
  ])("does not let a %s impersonate the retained request", async (_name, alter) => {
    const requests: Request[] = [];
    const recovery = new RecoveryStore(sessionStorage);
    const api = createIdentityApi(
      recovery,
      async (request) => {
        requests.push(request);
        throw new Error("response lost");
      },
      "https://api.example.test",
    );
    const original = writes[0].original;
    await expect(api.write(session, original, new AbortController().signal)).rejects.toThrow();
    await expect(
      api.write(session, alter(original), new AbortController().signal),
    ).rejects.toThrow("未保留完整原请求");
    expect(requests).toHaveLength(1);
  });

  it("rejects delegated administration and marker storage failure before dispatch", async () => {
    const requests: Request[] = [];
    const delegated = { ...session, selectedOnBehalfAppointmentId: targetId };
    const storage = {
      getItem: () => null,
      setItem: () => {
        throw new Error("disabled");
      },
      removeItem: () => {},
    } as unknown as Storage;
    const fetcher = async (request: Request) => {
      requests.push(request);
      return json(terminal("IDENTITY_PRINCIPAL"), 201);
    };
    await expect(
      createIdentityApi(new RecoveryStore(sessionStorage), fetcher, "https://api.example.test")
        .write(delegated, writes[0].original, new AbortController().signal),
    ).rejects.toThrow("不接受代办身份");
    await expect(
      createIdentityApi(new RecoveryStore(storage), fetcher, "https://api.example.test")
        .write(session, writes[0].original, new AbortController().signal),
    ).rejects.toThrow("恢复存储不可用");
    expect(requests).toHaveLength(0);
  });

  it("does not disclose or clear a terminal response after the session becomes stale", async () => {
    let current = true;
    let resolve!: (response: Response) => void;
    const response = new Promise<Response>((done) => (resolve = done));
    const recovery = new RecoveryStore(sessionStorage);
    const staleSession = { ...session, isCurrent: () => current };
    const api = createIdentityApi(recovery, async () => response, "https://api.example.test");
    const pending = api.write(staleSession, writes[0].original, new AbortController().signal);
    await vi.waitFor(() => expect(recovery.read()?.commandId).toBe(commandId));
    current = false;
    resolve(json(terminal("IDENTITY_PRINCIPAL"), 201, {
      ETag: identityETag,
      Location: `/api/v1/commands/${commandId}/receipt`,
    }));
    await expect(pending).rejects.toThrow("会话已变化");
    expect(recovery.read()?.commandId).toBe(commandId);
  });

  it.each([401, 403])("invalidates on %i without clearing an unresolved result", async (status) => {
    const invalidate = vi.fn();
    const recovery = new RecoveryStore(sessionStorage);
    const api = createIdentityApi(
      recovery,
      async () =>
        json(
          {
            ...validationProblem,
            status,
            code: status === 401 ? "UNAUTHENTICATED" : "NOT_AUTHORIZED",
            retryPolicy: status === 401 ? "SAME_KEY_AFTER_REAUTH" : "NO",
            fieldErrors: undefined,
          },
          status,
        ),
      "https://api.example.test",
    );
    await expect(
      api.write({ ...session, invalidate }, writes[0].original, new AbortController().signal),
    ).rejects.toMatchObject({ status });
    expect(invalidate).toHaveBeenCalledWith(status);
    expect(recovery.read()?.commandId).toBe(commandId);
  });

  it("blocks workbench writes after an unresolved identity write", async () => {
    const captured: Request[] = [];
    const recovery = new RecoveryStore(sessionStorage);
    const fetcher = async (request: Request) => {
      captured.push(request);
      throw new Error("response lost");
    };
    const identity = createIdentityApi(recovery, fetcher, "https://api.example.test");
    const workbench = createWorkbenchApi(fetcher, "https://api.example.test", recovery);

    await expect(
      identity.write(session, writes[0].original, new AbortController().signal),
    ).rejects.toThrow();
    expect(recovery.read()?.commandId).toBe(commandId);
    const sentBefore = captured.length;
    const businessOriginal = {
      kind: "command",
      action: "ASSIGN_LEAD",
      key: targetId,
      taskId: targetId,
      headers: { "If-Match": '"task.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"' },
      body: {
        draftId: targetId,
        expectedDraftRevision: 0,
        draftDigest: "a".repeat(43),
        ownerAppointmentId: appointmentId,
      },
    } satisfies OriginalWrite;
    await expect(
      workbench.write(session, businessOriginal, new AbortController().signal),
    ).rejects.toThrow();
    expect(captured).toHaveLength(sentBefore);
  });

  it("blocks identity writes after an unresolved workbench write", async () => {
    const captured: Request[] = [];
    const recovery = new RecoveryStore(sessionStorage);
    const fetcher = async (request: Request) => {
      captured.push(request);
      throw new Error("response lost");
    };
    const workbench = createWorkbenchApi(fetcher, "https://api.example.test", recovery);
    const identity = createIdentityApi(recovery, fetcher, "https://api.example.test");
    const businessOriginal = {
      kind: "command",
      action: "ASSIGN_LEAD",
      key: commandId,
      taskId: targetId,
      headers: { "If-Match": '"task.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"' },
      body: {
        draftId: targetId,
        expectedDraftRevision: 0,
        draftDigest: "a".repeat(43),
        ownerAppointmentId: appointmentId,
      },
    } satisfies OriginalWrite;

    await expect(
      workbench.write(session, businessOriginal, new AbortController().signal),
    ).rejects.toThrow();
    const sentBefore = captured.length;
    await expect(
      identity.write(session, writes[0].original, new AbortController().signal),
    ).rejects.toThrow();
    expect(captured).toHaveLength(sentBefore);
  });
});

describe("identity receipt recovery", () => {
  const seed = (
    recovery: RecoveryStore,
    actorScopeKey = scope,
    commandType = "CREATE_IDENTITY_PRINCIPAL",
  ) =>
    recovery.reserve({
      commandId,
      commandType,
      actorScopeKey,
      recordedAt: new Date().toISOString(),
    });

  it("queries only the exact same-Actor command and clears an exact terminal receipt", async () => {
    const requests: Request[] = [];
    const recovery = new RecoveryStore(sessionStorage);
    seed(recovery);
    const api = createIdentityApi(
      recovery,
      async (request) => {
        requests.push(request);
        return json(terminal("IDENTITY_PRINCIPAL"));
      },
      "https://api.example.test",
    );
    const result = await api.receipt(session, commandId, new AbortController().signal);
    expect(result.data).toEqual(terminal("IDENTITY_PRINCIPAL"));
    expect(new URL(requests[0].url).pathname).toBe(`/api/v1/commands/${commandId}/receipt`);
    expect(requests[0].cache).toBe("no-store");
    expect(recovery.read()).toBeNull();
  });

  it("accepts an exact terminal Identity rejection and clears the clue", async () => {
    const recovery = new RecoveryStore(sessionStorage);
    seed(recovery);
    const rejected = {
      commandId,
      receiptId: targetId,
      outcome: "REJECTED",
      completedAt: "2026-09-09T01:00:00Z",
      rejectionCode: "IDENTITY_BINDING_CONFLICT",
    };
    const api = createIdentityApi(recovery, async () => json(rejected), "https://api.example.test");
    expect((await api.receipt(session, commandId, new AbortController().signal)).data).toEqual(rejected);
    expect(recovery.read()).toBeNull();
  });

  it.each([
    ["different actor", { ...session, actorScopeKey: "ask1.bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" }],
    ["delegated actor", { ...session, selectedOnBehalfAppointmentId: targetId }],
  ])("does not dispatch for a %s", async (_name, selectedSession) => {
    const requests: Request[] = [];
    const recovery = new RecoveryStore(sessionStorage);
    seed(recovery);
    const api = createIdentityApi(
      recovery,
      async (request) => {
        requests.push(request);
        return json(terminal("IDENTITY_PRINCIPAL"));
      },
      "https://api.example.test",
    );
    await expect(
      api.receipt(selectedSession, commandId, new AbortController().signal),
    ).rejects.toThrow();
    expect(requests).toHaveLength(0);
    expect(recovery.read()?.commandId).toBe(commandId);
  });

  it.each([
    ["404", json({ ...validationProblem, status: 404, code: "NOT_FOUND", retryPolicy: "NO", fieldErrors: undefined }, 404)],
    ["malformed body", json({ outcome: "SUCCEEDED" })],
  ])("retains the clue for %s receipt results", async (_name, response) => {
    const recovery = new RecoveryStore(sessionStorage);
    seed(recovery);
    const api = createIdentityApi(recovery, async () => response.clone(), "https://api.example.test");
    await expect(
      api.receipt(session, commandId, new AbortController().signal),
    ).rejects.toThrow();
    expect(recovery.read()?.commandId).toBe(commandId);
  });

  it("requires exactly 200 before accepting a recovered receipt", async () => {
    const recovery = new RecoveryStore(sessionStorage);
    seed(recovery);
    const api = createIdentityApi(
      recovery,
      async () => json(terminal("IDENTITY_PRINCIPAL"), 201),
      "https://api.example.test",
    );
    await expect(
      api.receipt(session, commandId, new AbortController().signal),
    ).rejects.toThrow("身份管理回执暂时不可用");
    expect(recovery.read()?.commandId).toBe(commandId);
  });

  it.each([
    ["CREATE_IDENTITY_PRINCIPAL", "IDENTITY_PRINCIPAL"],
    ["SUSPEND_APPOINTMENT", "APPOINTMENT"],
  ])("rejects impossible recovered NO_CHANGE for %s and retains the clue", async (commandType, factType) => {
    const recovery = new RecoveryStore(sessionStorage);
    seed(recovery, scope, commandType);
    const api = createIdentityApi(
      recovery,
      async () => json(terminal(factType, "NO_CHANGE")),
      "https://api.example.test",
    );
    await expect(
      api.receipt(session, commandId, new AbortController().signal),
    ).rejects.toThrow("身份管理回执暂时不可用");
    expect(recovery.read()?.commandId).toBe(commandId);
  });

  it.each([
    ["RENAME_IDENTITY_PRINCIPAL", "IDENTITY_PRINCIPAL"],
    ["RENAME_ORGANIZATION_UNIT", "ORGANIZATION_UNIT"],
  ])("accepts recovered NO_CHANGE for %s", async (commandType, factType) => {
    const recovery = new RecoveryStore(sessionStorage);
    seed(recovery, scope, commandType);
    const api = createIdentityApi(
      recovery,
      async () => json(terminal(factType, "NO_CHANGE")),
      "https://api.example.test",
    );
    expect(
      (await api.receipt(session, commandId, new AbortController().signal)).data.outcome,
    ).toBe("NO_CHANGE");
    expect(recovery.read()).toBeNull();
  });
});

describe("identity request safety boundary", () => {
  const failingBody = (status: number) =>
    new Response(
      new ReadableStream({
        start(controller) {
          controller.error(new Error("PRIVATE_BODY_STREAM_FAILURE"));
        },
      }),
      {
        status,
        headers: { "content-type": "application/json" },
      },
    );

  it("converts malformed successful JSON to a static local error", async () => {
    const api = createIdentityApi(
      new RecoveryStore(sessionStorage),
      async () =>
        new Response("PRIVATE_RESPONSE_BODY", {
          status: 200,
          headers: {
            "content-type": "application/json",
            "cache-control": "no-store",
          },
        }),
      "https://api.example.test",
    );
    const error = await api
      .listIdentityProviderUsers(
        session,
        readCases[0].query,
        new AbortController().signal,
      )
      .catch((reason: unknown) => reason);
    expect(error).toBeInstanceOf(Error);
    expect((error as Error).message).toBe("服务暂时不可用，请稍后重试。");
    expect((error as Error).message).not.toContain("PRIVATE_RESPONSE_BODY");
  });

  it.each([401, 403])(
    "invalidates the current session on %s before a failing error body is read",
    async (status) => {
      const invalidate = vi.fn();
      const currentSession = { ...session, invalidate };
      const recovery = new RecoveryStore(sessionStorage);
      const api = createIdentityApi(
        recovery,
        async () => failingBody(status),
        "https://api.example.test",
      );
      const error = await api
        .write(currentSession, writes[0].original, new AbortController().signal)
        .catch((reason: unknown) => reason);
      expect(invalidate).toHaveBeenCalledWith(status);
      expect(error).toBeInstanceOf(Error);
      expect((error as Error).message).toBe("服务暂时不可用，请稍后重试。");
      expect((error as Error).message).not.toContain("PRIVATE_BODY_STREAM_FAILURE");
      expect(recovery.read()?.commandId).toBe(commandId);
    },
  );

  it("converts a rejected network request to a static error and retains the marker", async () => {
    const recovery = new RecoveryStore(sessionStorage);
    const api = createIdentityApi(
      recovery,
      async () => {
        throw new Error("PRIVATE_NETWORK_FAILURE");
      },
      "https://api.example.test",
    );
    const error = await api
      .write(session, writes[0].original, new AbortController().signal)
      .catch((reason: unknown) => reason);
    expect(error).toBeInstanceOf(Error);
    expect((error as Error).message).toBe("服务暂时不可用，请稍后重试。");
    expect((error as Error).message).not.toContain("PRIVATE_NETWORK_FAILURE");
    expect(recovery.read()?.commandId).toBe(commandId);
  });

  it("does not invalidate a newer actor when an old 401 arrives late", async () => {
    let resolve!: (response: Response) => void;
    const response = new Promise<Response>((done) => (resolve = done));
    let current = true;
    const invalidate = vi.fn();
    const oldSession = { ...session, isCurrent: () => current, invalidate };
    const requests: Request[] = [];
    const api = createIdentityApi(
      new RecoveryStore(sessionStorage),
      async (request) => {
        requests.push(request);
        return response;
      },
      "https://api.example.test",
    );
    const pending = api.listIdentityProviderUsers(
      oldSession,
      readCases[0].query,
      new AbortController().signal,
    );
    await vi.waitFor(() => expect(requests).toHaveLength(1));
    current = false;
    resolve(failingBody(401));
    await expect(pending).rejects.toThrow("会话已变化，请重新核对");
    expect(invalidate).not.toHaveBeenCalled();
  });
});

describe("identity write success boundary", () => {
  it("rejects REJECTED delivered on a successful write response and retains the clue", async () => {
    const recovery = new RecoveryStore(sessionStorage);
    const rejected = {
      commandId,
      receiptId: targetId,
      outcome: "REJECTED",
      completedAt: "2026-09-09T01:00:00Z",
      rejectionCode: "IDENTITY_BINDING_CONFLICT",
    };
    const api = createIdentityApi(
      recovery,
      async () =>
        json(rejected, 201, {
          ETag: identityETag,
          Location: `/api/v1/commands/${commandId}/receipt`,
        }),
      "https://api.example.test",
    );
    await expect(
      api.write(session, writes[0].original, new AbortController().signal),
    ).rejects.toThrow("身份管理响应不可用");
    expect(recovery.read()?.commandId).toBe(commandId);
  });
});
