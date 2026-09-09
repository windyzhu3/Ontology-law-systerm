export interface RecoveryMarker {
  commandId: string;
  commandType: string;
  actorScopeKey: string;
  recordedAt: string;
}
export const markerKey = "r1.pending-command";
export const publicCommandFacts = {
  CAPTURE_LEAD: "LEAD",
  SAVE_ACTION_DRAFT: "ACTION_DRAFT",
  RESOLVE_DUPLICATE_LEAD: "DECISION_RECORD",
  COMPLETE_LEAD_INGRESS: "LEAD",
  ASSIGN_LEAD: "LEAD_ASSIGNMENT",
  RECORD_ROUTING_DISPOSITION: "DECISION_RECORD",
  ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST: "DECISION_RECORD",
  RECORD_CONTACT_RESULT: "LEAD_CONTACT_RESULT",
  REVIEW_LEAD_VALIDITY: "DECISION_RECORD",
  CREATE_IDENTITY_PRINCIPAL: "IDENTITY_PRINCIPAL",
  RENAME_IDENTITY_PRINCIPAL: "IDENTITY_PRINCIPAL",
  SUSPEND_IDENTITY_PRINCIPAL: "IDENTITY_PRINCIPAL",
  RESUME_IDENTITY_PRINCIPAL: "IDENTITY_PRINCIPAL",
  DISABLE_IDENTITY_PRINCIPAL: "IDENTITY_PRINCIPAL",
  CREATE_ORGANIZATION_UNIT: "ORGANIZATION_UNIT",
  RENAME_ORGANIZATION_UNIT: "ORGANIZATION_UNIT",
  CLOSE_ORGANIZATION_UNIT: "ORGANIZATION_UNIT",
  CREATE_APPOINTMENT: "APPOINTMENT",
  SUSPEND_APPOINTMENT: "APPOINTMENT",
  RESUME_APPOINTMENT: "APPOINTMENT",
  END_APPOINTMENT: "APPOINTMENT",
  CREATE_AUTHORITY_GRANT: "AUTHORITY_GRANT",
  REVOKE_AUTHORITY_GRANT: "AUTHORITY_GRANT",
} as const;
export const uuidPattern =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const scopePattern = /^ask1\.[A-Za-z0-9_-]{43}$/;
const unavailable = () =>
  new Error("浏览器恢复存储不可用或线索已失效，结果尚未确认，不能自动重发。");
const same = (a: RecoveryMarker, b: RecoveryMarker) =>
  a.commandId === b.commandId &&
  a.commandType === b.commandType &&
  a.actorScopeKey === b.actorScopeKey &&
  a.recordedAt === b.recordedAt;
function validate(
  value: unknown,
  now: number,
): asserts value is RecoveryMarker {
  if (!value || typeof value !== "object") throw unavailable();
  const v = value as RecoveryMarker;
  const time = Date.parse(v.recordedAt);
  if (
    Object.keys(v).sort().join() !==
      "actorScopeKey,commandId,commandType,recordedAt" ||
    typeof v.commandId !== "string" ||
    !uuidPattern.test(v.commandId) ||
    !Object.hasOwn(publicCommandFacts, v.commandType) ||
    typeof v.actorScopeKey !== "string" ||
    !scopePattern.test(v.actorScopeKey) ||
    typeof v.recordedAt !== "string" ||
    !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/.test(v.recordedAt) ||
    !Number.isFinite(time) ||
    new Date(time).toISOString().replace(".000Z", "Z") !==
      v.recordedAt.replace(".000Z", "Z") ||
    time > now ||
    now - time >= 86_400_000
  )
    throw unavailable();
}
// All four fields are ASCII and bounded: names/punctuation plus UUID36, scope48,
// longest static command and ISO24. Whitespace does not expand the stored format.
const maxBytes = JSON.stringify({
  commandId: "x".repeat(36),
  commandType: "x".repeat(
    Math.max(...Object.keys(publicCommandFacts).map((k) => k.length)),
  ),
  actorScopeKey: "x".repeat(48),
  recordedAt: "x".repeat(24),
}).length;
export class RecoveryStore {
  private originals = new WeakMap<object, string>();
  /** Shared admission point for all 23 public SPA mutations, including Identity. */
  reserveWrite(
    commandId: string,
    commandType: string,
    actorScopeKey: string,
    original: object,
  ): RecoveryMarker {
    const existing = this.read(),
      serialized = JSON.stringify(original);
    if (existing && this.originals.get(original) !== serialized)
      throw new Error("未保留完整原请求，结果尚未确认，不能自动重发。");
    const marker = {
      commandId,
      commandType,
      actorScopeKey,
      recordedAt: existing?.recordedAt ?? new Date(this.now()).toISOString(),
    };
    this.reserve(marker);
    this.originals.set(original, serialized);
    return marker;
  }
  abandon(confirmed: boolean): void {
    if (!confirmed) throw new Error("请确认只删除本地线索，不撤销原操作。");
    try {
      this.storage.getItem(markerKey);
      this.storage.removeItem(markerKey);
      if (this.storage.getItem(markerKey) !== null) throw unavailable();
    } catch {
      throw unavailable();
    }
  }
  constructor(
    private readonly storage: Storage,
    private readonly now = Date.now,
  ) {}
  read(): RecoveryMarker | null {
    try {
      const raw = this.storage.getItem(markerKey);
      if (raw === null) return null;
      if (new TextEncoder().encode(raw).length > maxBytes) throw unavailable();
      const value: unknown = JSON.parse(raw);
      validate(value, this.now());
      return value;
    } catch {
      throw unavailable();
    }
  }
  reserve(marker: RecoveryMarker): void {
    validate(marker, this.now());
    const old = this.read();
    if (old) {
      if (!same(old, marker)) throw new Error("仍有未决操作，请先核对原回执。");
      return;
    }
    try {
      this.storage.setItem(markerKey, JSON.stringify(marker));
      if (!this.read() || !same(this.read()!, marker)) throw unavailable();
    } catch {
      throw unavailable();
    }
  }
  clear(marker: RecoveryMarker): void {
    const old = this.read();
    if (!old || !same(old, marker)) return;
    try {
      this.storage.removeItem(markerKey);
      if (this.storage.getItem(markerKey) !== null) throw unavailable();
    } catch {
      throw unavailable();
    }
  }
}
