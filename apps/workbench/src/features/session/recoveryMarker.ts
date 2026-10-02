export interface RecoveryMarker {
  commandId: string;
  commandType: string;
  actorScopeKey: string;
  recordedAt: string;
}
export const markerKey = "r1.pending-command";
export const publicCommandFacts = {
 SUBMIT_TRANSFER:"TRANSFER_SUBMISSION",RESUBMIT_TRANSFER:"TRANSFER_SUBMISSION",RECORD_TRANSFER_CONFLICT_REVIEW:"TRANSFER_CONFLICT_REVIEW",RECORD_TRANSFER_INTAKE:"TRANSFER_INTAKE",CLASSIFY_MATTER:"MATTER_CLASSIFICATION",
 REQUEST_CONTRACT_RECEIPT_REVIEW:'CONTRACT_PAYMENT_REQUEST',RECORD_CONTRACT_RECEIPT_REVIEW:'CONTRACT_PAYMENT_REVIEW',SUPPLEMENT_CONTRACT_RECEIPT:'CONTRACT_PAYMENT_REVIEW',
 END_CONTRACT_NEGOTIATION:'CONTRACT_NEGOTIATION_DISPOSITION',REQUEST_CONTRACT_TERMINATION_REVIEW:'CONTRACT_NEGOTIATION_DISPOSITION',RECORD_CONTRACT_TERMINATION_REVIEW:'CONTRACT_NEGOTIATION_DISPOSITION',
 RECORD_OPPORTUNITY_FOLLOWUP_ATTEMPT:"FOLLOWUP_ATTEMPT",
 RECORD_QUOTE_FOLLOWUP_ATTEMPT:"FOLLOWUP_ATTEMPT",
 END_QUOTE_NEGOTIATION:'QUOTE_TERMINATION',
 START_QUOTE_PREPARATION:'QUOTE_PREPARATION_INTENT',
 RETURN_CONTRACT_SIGNATURE_FOR_REVISION:'CONTRACT_SIGNATURE_REVISION_RETURN',
  SAVE_CONTRACT_SIGNATURE_DRAFT:'CONTRACT_SIGNATURE_DRAFT',
  CONFIRM_CONTRACT_SIGNATURE_ARRANGEMENT:'CONTRACT_SIGNATURE_ARRANGEMENT',
  SUBMIT_CONTRACT_SIGNATURE:'CONTRACT_SIGNATURE_SUBMISSION',
  RECORD_CONTRACT_SIGNATURE_VERIFICATION:'CONTRACT_SIGNATURE_VERIFICATION',
  VERIFY_CONTRACT_EXECUTION_CONDITIONS:'CONTRACT_EXECUTION_VERIFICATION',
  ARCHIVE_CONTRACT_SIGNATURE:'CONTRACT_SIGNATURE_ARCHIVE',
  RETURN_CONTRACT_FOR_REVISION:'CONTRACT_SIGNATURE_REVISION_RETURN',
  REQUEST_CONTRACT_PREPARATION:'CONTRACT_PREPARATION_REQUEST',
  RECORD_CONTRACT_PREPARATION_DECISION:'CONTRACT_PREPARATION_DECISION',
  START_CONTRACT_PREPARATION:'CONTRACT',
  SAVE_CONTRACT_DRAFT:'CONTRACT_DRAFT',
  FORM_CONTRACT:'CONTRACT_REVISION',
  REQUEST_CONTRACT_REVIEW:'CONTRACT_REVIEW_REQUEST',
  RECORD_CONTRACT_REVIEW:'CONTRACT_REVIEW_DECISION',
  REQUEST_CONTRACT_APPROVAL:'CONTRACT_APPROVAL_REQUEST',
  RECORD_CONTRACT_DECISION:'CONTRACT_APPROVAL_DECISION',
  SAVE_QUOTE_DRAFT: 'QUOTE_DRAFT',
  FORM_QUOTE: 'QUOTE_REVISION',
  REQUEST_QUOTE_APPROVAL: 'QUOTE_APPROVAL_REQUEST',
  RECORD_QUOTE_DECISION: 'QUOTE_APPROVAL_DECISION',
  RECORD_QUOTE_DELIVERY: 'QUOTE_ISSUE',
  RECORD_QUOTE_RESPONSE: 'QUOTE_RESPONSE',
  OPEN_OPPORTUNITY_MATERIAL_UPLOAD: "OPPORTUNITY_MATERIAL_UPLOAD",
  ACCEPT_OPPORTUNITY_MATERIAL: "OPPORTUNITY_MATERIAL_VERSION",
  SAVE_OPPORTUNITY_CUSTOMER_DRAFT: "OPPORTUNITY_CUSTOMER_DRAFT",
  CONFIRM_OPPORTUNITY_CUSTOMER_REQUIREMENTS: "OPPORTUNITY_CUSTOMER_CONFIRMATION",
  CLOSE_OPPORTUNITY: "OPPORTUNITY_CLOSURE",
  TRANSFER_OPPORTUNITY_RESPONSIBILITY: "OPPORTUNITY_OWNER_EXCEPTION",
  RECORD_OPPORTUNITY_OWNER_COORDINATION: "OPPORTUNITY_OWNER_EXCEPTION",
  CAPTURE_LEAD: "LEAD",
  SAVE_ACTION_DRAFT: "ACTION_DRAFT",
  RESOLVE_DUPLICATE_LEAD: "DECISION_RECORD",
  COMPLETE_LEAD_INGRESS: "LEAD",
  ASSIGN_LEAD: "LEAD_ASSIGNMENT",
  RECORD_SOURCE_REQUEST_CONTINUATION: "DECISION_RECORD",
  RECORD_ROUTING_DISPOSITION: "DECISION_RECORD",
  ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST: "DECISION_RECORD",
  RECORD_CONTACT_RESULT: "LEAD_CONTACT_RESULT",
  REVIEW_LEAD_VALIDITY: "DECISION_RECORD",
  RECORD_OPPORTUNITY_PROGRESS: "OPPORTUNITY_PROGRESS",
  CREATE_IDENTITY_PRINCIPAL: "IDENTITY_PRINCIPAL",
  RENAME_IDENTITY_PRINCIPAL: "IDENTITY_PRINCIPAL",
  SUSPEND_IDENTITY_PRINCIPAL: "IDENTITY_PRINCIPAL",
  RESUME_IDENTITY_PRINCIPAL: "IDENTITY_PRINCIPAL",
  DISABLE_IDENTITY_PRINCIPAL: "IDENTITY_PRINCIPAL",
  CREATE_ORGANIZATION_UNIT: "ORGANIZATION_UNIT",
  RENAME_ORGANIZATION_UNIT: "ORGANIZATION_UNIT",
  CLOSE_ORGANIZATION_UNIT: "ORGANIZATION_UNIT",
  CREATE_APPOINTMENT_ROLE: "APPOINTMENT_ROLE",
  RENAME_APPOINTMENT_ROLE: "APPOINTMENT_ROLE",
  DEACTIVATE_APPOINTMENT_ROLE: "APPOINTMENT_ROLE",
  REACTIVATE_APPOINTMENT_ROLE: "APPOINTMENT_ROLE",
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
  /** Shared admission point for public SPA mutations, including Identity and R2 progress. */
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



