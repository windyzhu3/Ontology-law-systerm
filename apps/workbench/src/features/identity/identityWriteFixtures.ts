import type { IdentityAdminRoute } from "./identityRoutes";
import { taskId, selectorId, draftId, testSession } from "../../test/fixtures";
import { RecoveryStore } from "../session/recoveryMarker";
import { createIdentityApi } from "./identityApi";

// Controlled transport fixtures for DOM integration tests, never product data.
export const tag = `"identity.${"a".repeat(43)}"`;
export const nextTag = `"identity.${"b".repeat(43)}"`;
export const roles = ["INTAKE_OPERATOR", "ROUTING_SUPERVISOR", "CONTACT_OPERATOR"];
export const authorities = ["LEAD_CAPTURE", "LEAD_INGRESS_RESOLVE", "LEAD_INGRESS_COMPLETE", "LEAD_ASSIGN", "LEAD_ROUTING_DECIDE", "SOURCE_INTAKE_REQUEST_ACK", "SALES_CONTACT_OWNER", "LEAD_VALIDITY_REVIEW"];
export const json = (data: unknown, status = 200, extra: HeadersInit = {}) => new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store", ...extra } });
export const principal = { id: taskId, displayName: "陈晓", state: "ACTIVE", etag: tag };
export const rows: Record<IdentityAdminRoute, Record<string, unknown>> = {
  "/admin/identity/principals": principal,
  "/admin/identity/organizations": { id: taskId, parentOrganizationId: selectorId, code: "SALES", displayName: "销售二组", state: "ACTIVE", etag: tag },
  "/admin/identity/appointments": { id: taskId, principal: { id: selectorId, label: "陈晓" }, organization: { id: draftId, label: "销售二组" }, roleCode: "CONTACT_OPERATOR", effectiveFrom: "2026-06-12T09:00:00Z", effectiveUntil: null, state: "ACTIVE", etag: tag },
  "/admin/identity/authority-grants": { id: taskId, appointment: { id: selectorId, label: "陈晓 · 首联经办" }, authorityCode: "SALES_CONTACT_OWNER", scopeOrganization: { id: draftId, label: "销售二组" }, validFrom: "2026-06-12T09:00:00Z", validUntil: null, state: "ACTIVE", etag: tag },
};
export function success(request: Request, outcome = "SUCCEEDED") {
  const path = new URL(request.url).pathname;
  const factType = path.includes("principals") ? "IDENTITY_PRINCIPAL" : path.includes("organizations") ? "ORGANIZATION_UNIT" : path.includes("appointments") ? "APPOINTMENT" : "AUTHORITY_GRANT";
  const key = request.headers.get("Idempotency-Key");
  return json({ commandId: key, receiptId: draftId, outcome, completedAt: "2026-09-09T01:00:00Z", resultFact: { factType, factRef: "safe-reference", revision: 1 } }, path.split("/").length === 6 ? 201 : 200, { ETag: nextTag, Location: `/api/v1/commands/${key}/receipt` });
}
export function fixture(path: IdentityAdminRoute, overrides: { row?: Record<string, unknown>; handle?: (request: Request) => Promise<Response | undefined>; recovery?: RecoveryStore } = {}) {
  const requests: Request[] = [];
  const writes: Request[] = [];
  const api = createIdentityApi(overrides.recovery ?? new RecoveryStore(sessionStorage), async request => {
    requests.push(request.clone());
    if (request.method !== "GET") writes.push(request.clone());
    const custom = await overrides.handle?.(request);
    if (custom) return custom;
    if (request.method !== "GET") return success(request);
    const url = new URL(request.url);
    if (url.pathname.endsWith("provider-users")) return json({ items: [{ label: "陈晓（已核验用户名）", selector: "opaque_provider_choice" }], nextCursor: null });
    if (url.pathname.endsWith("options")) {
      const page = url.searchParams.get("page"), optionKind = url.searchParams.get("optionKind");
      return json({ page, optionKind, candidates: { items: [{ id: optionKind === "ORGANIZATION" ? draftId : selectorId, label: optionKind === "ORGANIZATION" ? "销售二组" : "陈晓" }], nextCursor: null }, roleCodes: page === "APPOINTMENTS" ? roles : [], grantableAuthorityCodes: page === "AUTHORITY_GRANTS" ? authorities : [] });
    }
    return json({ items: [overrides.row ?? rows[path]], nextCursor: null });
  }, location.origin);
  return { api, requests, writes, session: testSession() };
}
