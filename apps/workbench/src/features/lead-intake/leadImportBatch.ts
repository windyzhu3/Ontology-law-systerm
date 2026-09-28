import type { components } from "../../generated/api/schema";
import { TransportError, type WorkbenchSession } from "../../lib/sessionTransport";
import { scopePattern, uuidPattern, type RecoveryStore } from "../session/recoveryMarker";
import type { createLeadIntakeApi, LeadCaptureWrite } from "./leadIntakeApi";

export type LeadImportEntry = { rowNumber: number } & (
  | { write: LeadCaptureWrite; errors?: never }
  | { errors: readonly string[]; write?: never }
);
export type LeadImportStatus = "pending" | "invalid" | "submitting" | "unresolved" | "captured" | "rejected";
export type LeadImportResultRow = Readonly<{
  rowNumber: number;
  displayName: string;
  status: LeadImportStatus;
  message: string;
  subjectFactRef?: string;
  receipt?: Readonly<components["schemas"]["CommandReceipt"]>;
}>;
export type LeadImportSnapshot = Readonly<{
  rows: readonly LeadImportResultRow[];
  busy: boolean;
  notice: string;
}>;
const messages: Record<LeadImportStatus, string> = {
  pending: "等待提交", invalid: "请修正后重新核对", submitting: "正在提交本行",
  unresolved: "请核对本次录入结果，不要重复提交", captured: "已录入；后续责任以工作台显示为准",
  rejected: "本行未录入，请核对后处理",
};

/** Bounded, session-owned batch. Bodies stay in memory; durable recovery uses the shared exact-command marker. */
export class LeadImportBatch {
  private readonly entries: readonly LeadImportEntry[];
  private state: LeadImportSnapshot;
  private listeners = new Set<() => void>();

  constructor(
    readonly actorScopeKey: string,
    entries: readonly LeadImportEntry[],
    private readonly api: ReturnType<typeof createLeadIntakeApi>,
    private readonly recovery: RecoveryStore,
  ) {
    if (!scopePattern.test(actorScopeKey) || !entries.length || entries.length > 200) throw new Error("请重新核对导入范围。");
    const rowNumbers = new Set<number>(), commandKeys = new Set<string>(), sources = new Set<string>();
    for (const entry of entries) {
      if (!Number.isSafeInteger(entry.rowNumber) || entry.rowNumber < 2 || rowNumbers.has(entry.rowNumber)) throw new Error("请重新核对来源行号。");
      rowNumbers.add(entry.rowNumber);
      if (entry.write) {
        const { key, body } = entry.write;
        const source = JSON.stringify([body.sourceChannelCode, body.sourceAccountCode, body.sourceRecordKey]);
        if (!uuidPattern.test(key) || commandKeys.has(key.toLowerCase()) || sources.has(source)
          || !body.sourceRecordKey?.trim() || !body.legalNeedSummary?.trim() || entry.errors) throw new Error("请重新核对重复记录和必填内容。");
        commandKeys.add(key.toLowerCase()); sources.add(source);
      } else if (!entry.errors?.length) throw new Error("请先核对导入内容。");
    }
    // No caller-owned request object can change after the user's preview confirmation.
    this.entries = structuredClone(entries);
    this.state = Object.freeze({ busy: false, notice: "", rows: Object.freeze(entries.map(entry => Object.freeze({
      rowNumber: entry.rowNumber, displayName: entry.write?.body.customerName || entry.write?.body.contactName || entry.write?.body.capturedName || `第 ${entry.rowNumber} 行`, status: entry.write ? "pending" as const : "invalid" as const,
      message: entry.write ? messages.pending : entry.errors.join("；"),
    }))) });
  }

  snapshot = (): LeadImportSnapshot => this.state;
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  };
  private update(patch: Partial<LeadImportSnapshot>, row?: { index: number; status: LeadImportStatus; subjectFactRef?: string; receipt?: components["schemas"]["CommandReceipt"] }) {
    const rows = row ? Object.freeze(this.state.rows.map((value, index) => index === row.index
      ? Object.freeze({ ...value, status: row.status, message: messages[row.status], ...(row.subjectFactRef ? { subjectFactRef: row.subjectFactRef } : {}), ...(row.receipt ? { receipt: Object.freeze(structuredClone(row.receipt)) } : {}) }) : value)) : this.state.rows;
    this.state = Object.freeze({ ...this.state, ...patch, rows });
    // View subscription failures must never change a known command outcome.
    for (const listener of this.listeners) { try { listener(); } catch { /* Other subscribers still receive the state. */ } }
  }
  private assertSession(session: WorkbenchSession, signal: AbortSignal) {
    if (session.actorScopeKey !== this.actorScopeKey || !session.isCurrent() || signal.aborted) throw new Error("会话已变化，请重新核对。");
  }
  private hasPendingCommand(): boolean {
    try { return this.recovery.read() !== null; } catch { return true; }
  }
  private afterFailure(index: number, error: unknown) {
    if (error instanceof TransportError && error.provenOutcome) {
      this.update({ notice: "本行未录入，已暂停后续提交，请核对后处理。" }, { index, status: "rejected" });
      return;
    }
    // A different command may reserve the shared marker while this row awaits a fresh token.
    // Only the exact row's marker is evidence that this capture entered dispatch.
    let unresolved = true;
    try {
      const marker = this.recovery.read();
      if (marker && (marker.commandId !== this.entries[index].write?.key || marker.commandType !== "CAPTURE_LEAD" || marker.actorScopeKey !== this.actorScopeKey)) {
        this.update({ notice: "仍有未决操作，请先核对原回执。" }, { index, status: "pending" });
        return;
      }
      unresolved = marker !== null;
    } catch { /* Unavailable recovery storage cannot prove that the write was not sent. */ }
    this.update({ notice: unresolved ? "有一条提交结果尚未确认，已暂停后续提交，请勿重复导入。" : "录入尚未开始，请核对登录状态后继续。" },
      { index, status: unresolved ? "unresolved" : "pending" });
  }

  async submit(session: WorkbenchSession, signal: AbortSignal): Promise<void> {
    this.assertSession(session, signal);
    if (this.state.busy || this.state.rows.some(row => row.status === "unresolved")) return;
    if (this.hasPendingCommand()) { this.update({ notice: "仍有未决操作，请先核对原回执。" }); return; }
    this.update({ busy: true, notice: "" });
    try {
      for (let index = 0; index < this.entries.length; index++) {
        if (this.state.rows[index].status !== "pending") continue;
        this.assertSession(session, signal);
        if (this.hasPendingCommand()) { this.update({ notice: "仍有未决操作，请先核对原回执。" }); break; }
        const entry = this.entries[index];
        if (!entry.write) continue;
        this.update({}, { index, status: "submitting" });
        try {
          const result = await this.api.capture(session, entry.write, signal);
          this.update({}, { index, status: "captured", subjectFactRef: result.data.resultFact.factRef, receipt: result.data });
        } catch (error) { this.afterFailure(index, error); break; }
      }
    } finally { this.update({ busy: false }); }
  }

  /** Recovery never dispatches a capture or automatically continues the remaining batch. */
  async recover(session: WorkbenchSession, signal: AbortSignal): Promise<void> {
    this.assertSession(session, signal);
    if (this.state.busy) return;
    const index = this.state.rows.findIndex(row => row.status === "unresolved");
    if (index < 0) return;
    const entry = this.entries[index];
    if (!entry.write) return;
    this.update({ busy: true });
    try {
      const { data } = await this.api.receipt(session, entry.write.key, signal);
      this.update({ notice: "本次结果已核对；剩余行需由你确认后继续提交。" },
        { index, receipt: data, status: data!.outcome === "REJECTED" ? "rejected" : "captured", ...(data.outcome !== "REJECTED" ? { subjectFactRef: data.resultFact.factRef } : {}) });
    } catch {
      this.update({ notice: "结果仍未确认，请稍后核对原回执；不会自动重发。" });
    } finally { this.update({ busy: false }); }
  }
}
