import { parseEnvelope } from "../workcard/contract";
import createClient from "openapi-fetch";
import type { components, paths } from "../../generated/api/schema";
import { createSessionTransport, matchesReceipt, TransportError, type WorkbenchSession } from "../../lib/sessionTransport";
import { uuidPattern, type RecoveryStore } from "../session/recoveryMarker";
import { provenWriteOutcome } from "../session/recoveryOutcome";
export type LeadCaptureWrite = { key: string; body: components["schemas"]["CaptureLeadV1"] };
type IntakeSource = components["schemas"]["LeadIntakeSourceV1"];
export type SourceSelection="BOUND_TO_PRINCIPAL" | "SELECTABLE";
function sourceList(value: unknown): {sources:IntakeSource[];sourceSelection:SourceSelection} {
  const invalid = () => new Error("来源信息暂时不可用，请刷新后重试。");
  if (!value || typeof value !== "object" || Array.isArray(value) || Object.keys(value).some(key=>key!=="sources"&&key!=="sourceSelection") || !("sources" in value)
    || !Array.isArray(value.sources) || value.sources.length > 50) throw invalid();
  const selection="sourceSelection" in value?value.sourceSelection:"SELECTABLE";
  if(selection!=="BOUND_TO_PRINCIPAL"&&selection!=="SELECTABLE"||selection==="BOUND_TO_PRINCIPAL"&&value.sources.length>1)throw invalid();
  const codes = ["sourceAccountCode", "sourceChannelCode", "serviceCategoryCode", "jurisdictionCode", "urgencyCode"];
  const accounts = new Set<string>(), names = new Set<string>();
  const sources=value.sources.map((item: unknown) => {
    if (!item || typeof item !== "object" || Array.isArray(item)) throw invalid();
    const fields = item as Record<string, unknown>;
    if (Object.keys(fields).length !== 6 || codes.some(key => typeof fields[key] !== "string" || !(key === "sourceAccountCode" ? /^[A-Za-z][A-Za-z0-9_]{0,63}$/ : /^[A-Z][A-Z0-9_]{0,63}$/).test(fields[key] as string))
      || typeof fields.displayName !== "string" || !fields.displayName.trim() || [...fields.displayName].length > 200 || /[\u0000-\u001f\u007f-\u009f]/.test(fields.displayName)) throw invalid();
    const source = fields as IntakeSource;
    if (accounts.has(source.sourceAccountCode) || names.has(source.displayName)) throw invalid();
    accounts.add(source.sourceAccountCode); names.add(source.displayName);
    return { ...source };
  });
  return {sources,sourceSelection:selection};
}
/** Uses the existing capture command and shared unresolved-write guard; never stores lead body in browser storage. */
export function createLeadIntakeApi(recovery: RecoveryStore, fetcher?: (request: Request) => Promise<Response>, baseUrl = window.location.origin) {
  const client = createClient<paths>({ baseUrl, ...(fetcher ? { fetch: fetcher } : {}) });
  const transport = createSessionTransport(false);
  const unknownResult = () => new Error("录入结果尚未确认，请先核对原回执。");
  return {
    async followup(session: WorkbenchSession, factRef: string, signal: AbortSignal) {
      if (!/^[A-Za-z0-9_-]{43}$/.test(factRef)) throw new Error("录入关联暂不可用。");
      const headers = await transport.auth(session, signal);
      const result = await transport.request(session, signal, middleware => client.GET("/api/v1/workcards/current", { headers, signal, cache: "no-store", middleware: [middleware] }));
      transport.assertCurrent(session, signal); transport.checked(session, signal, result);
      if (result.response.status !== 200) throw new Error("当前责任暂不可用。");
      const value = parseEnvelope(result.data);
      if (!value.myTasks || value.myTasks.some(task => !task.subjectFactRef)) throw new Error("当前责任关联暂不可用。");
      return value.myTasks.filter(task => task.subjectFactRef === factRef);
    },
    async sources(session: WorkbenchSession, signal: AbortSignal) {
      const headers = await transport.auth(session, signal);
      const result = await transport.request(session, signal, middleware => client.GET("/api/v1/leads/intake-sources", {
        headers, signal, cache: "no-store", middleware: [middleware],
      }));
      transport.assertCurrent(session, signal);
      transport.checked(session, signal, result);
      if (result.response.status !== 200 || !result.response.headers.get("Cache-Control")?.toLowerCase().split(",").some(value => value.trim() === "no-store"))
        throw new Error("来源信息暂时不可用，请刷新后重试。");
      return sourceList(result.data);
    },
    async capture(session: WorkbenchSession, original: LeadCaptureWrite, signal: AbortSignal) {
      if (!uuidPattern.test(original.key)) throw new Error("录入请求无效，请重新核对。");
      const headers = await transport.auth(session, signal);
      const marker = recovery.reserveWrite(original.key, "CAPTURE_LEAD", session.actorScopeKey, original);
      transport.assertCurrent(session, signal);
      const result = await transport.request(session, signal, middleware => client.POST("/api/v1/leads", {
        params: { header: { "Idempotency-Key": original.key } }, headers, body: original.body, signal, middleware: [middleware],
      }));
      transport.assertCurrent(session, signal);
      if (!result.response.ok) {
        if (provenWriteOutcome(result.error, result.response.status, marker)) {
          recovery.clear(marker);
          const problem = result.error as { code: string; retryPolicy?: string };
          throw new TransportError(result.response.status, problem.code, true, problem.retryPolicy);
        }
        transport.checked(session, signal, result);
        throw unknownResult();
      }
      const data: unknown = result.data;
      if (result.response.status !== 201 || !matchesReceipt(data, marker) || data.outcome === "REJECTED"
        || !result.response.headers.get("Cache-Control")?.toLowerCase().includes("no-store")
        || result.response.headers.get("Location") !== `/api/v1/commands/${original.key}/receipt`) throw unknownResult();
      recovery.clear(marker);
      return { data };
    },
    async receipt(session: WorkbenchSession, key: string, signal: AbortSignal) {
      const marker = recovery.read();
      if (!marker || marker.commandId !== key || marker.commandType !== "CAPTURE_LEAD" || marker.actorScopeKey !== session.actorScopeKey) throw unknownResult();
      const headers = await transport.auth(session, signal);
      const result = await transport.request(session, signal, middleware => client.GET("/api/v1/commands/{commandId}/receipt", {
        params: { path: { commandId: key } }, headers, signal, cache: "no-store", middleware: [middleware],
      }));
      transport.assertCurrent(session, signal);
      transport.checked(session, signal, result);
      if (result.response.status !== 200 || !matchesReceipt(result.data, marker)
        || !result.response.headers.get("Cache-Control")?.toLowerCase().includes("no-store")) throw unknownResult();
      recovery.clear(marker);
      return { data: result.data };
    },
  };
}
