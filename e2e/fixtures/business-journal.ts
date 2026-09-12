import { closeSync, existsSync, fsyncSync, openSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { basename, dirname } from 'node:path';
import { check, exact, noLinks, sha, uuid } from './local-environment';
import { requireVerifiedBusinessRestart, type BusinessRestart } from './business-restart';

export const BUSINESS_CASES = ['T9-W01-ingress-routing-ack', 'T9-I06-contact-owner', 'T9-W01-assign-contact-review'] as const;
export type BusinessCaseId = typeof BUSINESS_CASES[number];
export const BUSINESS_STEPS = [
  'capture-auto', 'complete-draft', 'complete-submit', 'routing-draft', 'routing-submit', 'ack-draft', 'ack-submit',
  'grant-contact-owner',
  'capture-manual', 'assign-draft', 'assign-submit', 'contact-draft', 'contact-submit', 'review-draft', 'review-submit',
] as const;
export type BusinessStep = typeof BUSINESS_STEPS[number];
const STAGE_END = [7, 8, 15] as const;
const HASH = /^[0-9a-f]{64}$/;
const DIGEST = /^[A-Za-z0-9_-]{43}$/;
const ACTOR = /^ask1\.[A-Za-z0-9_-]{43}$/;
const TASK_PATH = /^\/api\/v1\/tasks\/([0-9a-f-]{36})\/(draft|commands\/(complete-lead-ingress|record-routing-disposition|acknowledge-source-intake-stop-request|assign-lead|record-contact-result|review-lead-validity))$/;

export interface BusinessRunIdentity { runId: string; environmentDigest: string; buildSha: string; predecessorRunId: string; predecessorSha256: string }
export interface RequestSelectors {
  actorAppointmentId: string; taskId: string | null; subjectRef: string | null; subjectRevision: number | null; taskETag: string | null;
  draftId: string | null; draftRevision: number | null; draftDigest: string | null; draftETag: string | null; intendedValuesSha256: string | null;
}
export interface BusinessCommand { step: BusinessStep; commandId: string; method: string; path: string; bodySha256: string; actorScopeKey: string; requestSelectors: RequestSelectors }
export type BusinessFact =
  | { factType: 'LEAD' | 'ACTION_DRAFT' | 'TASK_OCCURRENCE' | 'LEAD_ASSIGNMENT' | 'AUTHORITY_GRANT'; factRef: string; revision: number }
  | { factType: 'DECISION_RECORD' | 'LEAD_CONTACT_RESULT'; factRef: string; digest: string };
export interface CardSelectors {
  taskId: string; subjectRef: string; subjectRevision: number; ownerAppointmentId: string;
  taskETag: string; draftETag: string | null; draftId: string | null; draftRevision: number | null; draftDigest: string | null; draftValuesSha256: string | null;
  successorTaskId: string | null; successorTaskType: string | null; successorOwnerAppointmentId: string | null;
  successorSubjectRef: string | null; successorSubjectRevision: number | null; successorTaskETag: string | null;
}
export interface GrantSelectors { resourceId: string }
export type BusinessSelectors = CardSelectors | GrantSelectors;
export interface BusinessEntry extends BusinessCommand { at: string; status: 'PENDING' | 'CONFIRMED'; httpStatus?: number; receiptId?: string; resultFact?: BusinessFact; selectors?: BusinessSelectors }
export type BusinessReportCommand = Omit<BusinessEntry, 'actorScopeKey' | 'requestSelectors' | 'at'>;
export interface BusinessEvidence extends BusinessRunIdentity {
  apiIdentity: string; executedAt: string; caseIdentity: BusinessCaseId; status: 'ACTIONS_VERIFIED'; exitCode: null; reportPath: string;
  commands: BusinessReportCommand[]; http: Array<{path: string; status: number}>; U01: 'NOT_EXECUTED'; U02: 'NOT_EXECUTED'; U03: 'NOT_EXECUTED';
}
interface StageCommit { caseIdentity: BusinessCaseId; reportPath: string; reportSha256: string; status: 'PASSED_SUBSCENARIO'; exitCode: 0 }
interface Journal { identity: BusinessRunIdentity; commands: BusinessEntry[]; stages: StageCommit[] }
export interface CompletionIO { writeEvidence?: (path: string, bytes: string) => void }

function expectedRoute(step: BusinessStep, method: string, path: string): boolean {
  if (step === 'capture-auto' || step === 'capture-manual') return method === 'POST' && path === '/api/v1/leads';
  if (step === 'grant-contact-owner') return method === 'POST' && path === '/api/v1/admin/identity/authority-grants';
  const match = TASK_PATH.exec(path); if (!match) return false;
  if (step.endsWith('-draft')) return method === 'PUT' && match[2] === 'draft';
  const suffix: Record<string, string> = { complete: 'complete-lead-ingress', routing: 'record-routing-disposition', ack: 'acknowledge-source-intake-stop-request', assign: 'assign-lead', contact: 'record-contact-result', review: 'review-lead-validity' };
  return method === 'POST' && match[2] === `commands/${suffix[step.split('-')[0]]}`;
}
function expectedFact(step: BusinessStep): string {
  if (step === 'capture-auto' || step === 'capture-manual' || step === 'complete-submit') return 'LEAD';
  if (step.endsWith('-draft')) return 'ACTION_DRAFT';
  if (step === 'grant-contact-owner') return 'AUTHORITY_GRANT';
  if (step === 'assign-submit') return 'LEAD_ASSIGNMENT';
  if (step === 'contact-submit') return 'LEAD_CONTACT_RESULT';
  return 'DECISION_RECORD';
}
function validFact(step: BusinessStep, fact: unknown): asserts fact is BusinessFact {
  check(fact && typeof fact === 'object'); const value = fact as Record<string, any>, expected = expectedFact(step);
  check(value.factType === expected && typeof value.factRef === 'string' && value.factRef.length >= 16 && value.factRef.length <= 512);
  if (expected === 'DECISION_RECORD' || expected === 'LEAD_CONTACT_RESULT') check(exact(value, ['factType','factRef','digest']) && DIGEST.test(value.digest));
  else check(exact(value, ['factType','factRef','revision']) && Number.isSafeInteger(value.revision) && value.revision >= 0);
}
function validIdentity(identity: BusinessRunIdentity): void {
  check(exact(identity, ['runId', 'environmentDigest', 'buildSha', 'predecessorRunId', 'predecessorSha256']));
  check(uuid.test(identity.runId) && HASH.test(identity.environmentDigest) && /^[0-9a-f]{40}$/.test(identity.buildSha));
  check(uuid.test(identity.predecessorRunId) && HASH.test(identity.predecessorSha256));
}
function validCommand(command: BusinessCommand, index?: number): void {
  check(exact(command, ['step', 'commandId', 'method', 'path', 'bodySha256', 'actorScopeKey', 'requestSelectors']));
  check(BUSINESS_STEPS.includes(command.step) && (index === undefined || command.step === BUSINESS_STEPS[index]));
  check(uuid.test(command.commandId) && HASH.test(command.bodySha256) && ACTOR.test(command.actorScopeKey));
  check(expectedRoute(command.step, command.method, command.path));
  const s = command.requestSelectors; check(s && exact(s, ['actorAppointmentId', 'taskId', 'subjectRef', 'subjectRevision', 'taskETag', 'draftId', 'draftRevision', 'draftDigest', 'draftETag', 'intendedValuesSha256']) && uuid.test(s.actorAppointmentId));
  check((s.taskId === null && s.subjectRef === null && s.subjectRevision === null && s.taskETag === null) ||
    (uuid.test(s.taskId ?? '') && typeof s.subjectRef === 'string' && s.subjectRef.length >= 16 && Number.isSafeInteger(s.subjectRevision) && Number(s.subjectRevision) >= 0 && /^"task\.[A-Za-z0-9_-]{43}"$/.test(s.taskETag ?? '')));
  check((command.step === 'capture-auto' || command.step === 'capture-manual' || command.step === 'grant-contact-owner') === (s.taskId === null));
  if (s.taskId === null) check(s.draftId === null && s.draftRevision === null && s.draftDigest === null && s.draftETag === null && s.intendedValuesSha256 === null);
  else if (command.step.endsWith('-draft')) check(s.draftId === null && s.draftRevision === null && s.draftDigest === null && s.draftETag === null && HASH.test(s.intendedValuesSha256 ?? ''));
  else check(uuid.test(s.draftId ?? '') && Number.isSafeInteger(s.draftRevision) && Number(s.draftRevision) >= 0 && DIGEST.test(s.draftDigest ?? '') && /^"draft\.[A-Za-z0-9_-]{43}"$/.test(s.draftETag ?? '') && HASH.test(s.intendedValuesSha256 ?? ''));
}
function validSelectors(step: BusinessStep, value: unknown): void {
  check(value && typeof value === 'object'); const v = value as Record<string, any>;
  if (step === 'grant-contact-owner') { check(exact(v, ['resourceId']) && uuid.test(v.resourceId)); return; }
  check(exact(v, ['taskId', 'subjectRef', 'subjectRevision', 'ownerAppointmentId', 'taskETag', 'draftETag', 'draftId', 'draftRevision', 'draftDigest', 'draftValuesSha256', 'successorTaskId', 'successorTaskType', 'successorOwnerAppointmentId', 'successorSubjectRef', 'successorSubjectRevision', 'successorTaskETag']));
  check(uuid.test(v.taskId) && typeof v.subjectRef === 'string' && v.subjectRef.length >= 16 && v.subjectRef.length <= 512);
  check(Number.isSafeInteger(v.subjectRevision) && v.subjectRevision >= 0 && uuid.test(v.ownerAppointmentId));
  check(/^"task\.[A-Za-z0-9_-]{43}"$/.test(v.taskETag));
  check((v.draftETag === null && v.draftId === null && v.draftRevision === null && v.draftDigest === null && v.draftValuesSha256 === null)
    || (/^"draft\.[A-Za-z0-9_-]{43}"$/.test(v.draftETag) && uuid.test(v.draftId) && Number.isSafeInteger(v.draftRevision) && v.draftRevision >= 0 && DIGEST.test(v.draftDigest) && HASH.test(v.draftValuesSha256)));
  check((v.successorTaskId === null && v.successorTaskType === null && v.successorOwnerAppointmentId === null && v.successorSubjectRef === null && v.successorSubjectRevision === null && v.successorTaskETag === null)
    || (uuid.test(v.successorTaskId) && ['COMPLETE_LEAD_INGRESS', 'RESOLVE_LEAD_ROUTING_GAP', 'ACK_SOURCE_INTAKE_STOP_REQUEST', 'ASSIGN_LEAD', 'CONTACT_LEAD', 'REVIEW_LEAD_VALIDITY'].includes(v.successorTaskType)
      && uuid.test(v.successorOwnerAppointmentId) && typeof v.successorSubjectRef === 'string' && v.successorSubjectRef.length >= 16 && Number.isSafeInteger(v.successorSubjectRevision) && v.successorSubjectRevision >= 0 && /^"task\.[A-Za-z0-9_-]{43}"$/.test(v.successorTaskETag)));
}
function validate(data: Journal): void {
  check(exact(data, ['identity', 'commands', 'stages'])); validIdentity(data.identity);
  check(Array.isArray(data.commands) && data.commands.length <= BUSINESS_STEPS.length && Array.isArray(data.stages));
  const keys = new Set<string>();
  for (const [index, entry] of data.commands.entries()) {
    const { at, status, httpStatus, receiptId, resultFact, selectors, ...command } = entry; validCommand(command, index);
    check(new Date(at).toISOString() === at && !keys.has(entry.commandId)); keys.add(entry.commandId);
    check(status === 'PENDING' || status === 'CONFIRMED');
    if (status === 'PENDING') check(index === data.commands.length - 1 && httpStatus === undefined && receiptId === undefined && resultFact === undefined && selectors === undefined);
    else {
      check((httpStatus === 200 || httpStatus === 201) && uuid.test(receiptId ?? ''));
      validFact(entry.step, resultFact); validSelectors(entry.step, selectors);
    }
  }
  check(data.commands.filter(x => x.status === 'PENDING').length <= 1);
  check(data.stages.length <= BUSINESS_CASES.length && data.stages.every((stage, index) => exact(stage, ['caseIdentity', 'reportPath', 'reportSha256', 'status', 'exitCode']) && stage.caseIdentity === BUSINESS_CASES[index] && HASH.test(stage.reportSha256) && stage.status === 'PASSED_SUBSCENARIO' && stage.exitCode === 0 && data.commands.slice(0, STAGE_END[index]).every(x => x.status === 'CONFIRMED')));
}
function durableExclusive(path: string, bytes: string): void { const fd = openSync(path, 'wx', 0o600); try { writeFileSync(fd, bytes); fsyncSync(fd); } finally { closeSync(fd); } }

export class BusinessJournal {
  private text = ''; private data!: Journal; private busy = false; private failed = false;
  private constructor(readonly path: string, private readonly guard: () => void | Promise<void>, private readonly restart?: BusinessRestart) {}
  private async protect() { await this.guard(); this.restart?.assertUnchanged(); }
  static async open(path: string, identity: BusinessRunIdentity, protect: () => void | Promise<void>, restart?: BusinessRestart): Promise<BusinessJournal> {
    if (restart) requireVerifiedBusinessRestart(restart);
    const journal = new BusinessJournal(path, protect, restart); await journal.initialize(structuredClone(identity)); return journal;
  }
  private async initialize(identity: BusinessRunIdentity) {
    validIdentity(identity); await this.protect(); noLinks(dirname(this.path)); this.requireClear();
    if (existsSync(this.path)) { noLinks(this.path); this.text = readFileSync(this.path, 'utf8'); this.data = JSON.parse(this.text); validate(this.data);
      if (this.restart) { this.restart.assertJournal(this.path, identity, this.data); const pending = this.pending(); if (pending) this.restart.assertPending(pending, sha(this.text)); }
      else check(JSON.stringify(this.data.identity) === JSON.stringify(identity)); }
    else { check(!this.restart); this.data = { identity, commands: [], stages: [] }; validate(this.data); this.text = JSON.stringify(this.data); durableExclusive(this.path, this.text); await this.protect(); }
    this.verifyEvidence();
  }
  private requireClear() { check(!existsSync(this.path + '.pending') && !existsSync(this.path + '.completion.pending')); }
  private async mutate<T>(action: () => Promise<T>): Promise<T> { check(!this.busy && !this.failed); this.busy = true; try { return await action(); } catch (error) { this.failed = true; throw error; } finally { this.busy = false; } }
  pending(): BusinessEntry | undefined { return structuredClone(this.data.commands.find(x => x.status === 'PENDING')); }
  confirmed(step: BusinessStep): BusinessEntry | undefined { return structuredClone(this.data.commands.find(x => x.step === step && x.status === 'CONFIRMED')); }
  stageCommands(id: BusinessCaseId): BusinessEntry[] { const index = BUSINESS_CASES.indexOf(id); check(index >= 0); return structuredClone(this.data.commands.slice(index ? STAGE_END[index - 1] : 0, STAGE_END[index])); }
  reportCommands(id: BusinessCaseId): BusinessReportCommand[] { return this.stageCommands(id).map(({ actorScopeKey: _actor, requestSelectors: _request, at: _at, ...entry }) => entry); }
  private validateEvidencePath(path: string, id: BusinessCaseId) { const prefix = `task9-business-${this.data.identity.runId}-${id}-`; check(dirname(path) === dirname(this.path) && basename(path).startsWith(prefix) && basename(path).endsWith('.json') && uuid.test(basename(path).slice(prefix.length, -5))); }
  private validateEvidence(evidence: BusinessEvidence, id: BusinessCaseId) {
    check(exact(evidence, ['runId','environmentDigest','buildSha','predecessorRunId','predecessorSha256','apiIdentity','executedAt','caseIdentity','status','exitCode','reportPath','commands','http','U01','U02','U03']));
    const identity = this.restart && id === BUSINESS_CASES[2] ? this.restart.activeIdentity : this.data.identity;
    for (const key of ['runId','environmentDigest','buildSha','predecessorRunId','predecessorSha256'] as const) check(evidence[key] === identity[key]);
    if (this.restart) check(evidence.apiIdentity === (id === BUSINESS_CASES[2] ? this.restart.activeApiIdentity : this.restart.originalApiIdentity));
    check(HASH.test(evidence.apiIdentity) && new Date(evidence.executedAt).toISOString() === evidence.executedAt && evidence.caseIdentity === id && evidence.status === 'ACTIONS_VERIFIED' && evidence.exitCode === null);
    this.validateEvidencePath(evidence.reportPath, id); check(JSON.stringify(evidence.commands) === JSON.stringify(this.reportCommands(id)));
    check(Array.isArray(evidence.http) && evidence.http.length <= 100 && evidence.http.every(x => exact(x, ['path','status']) && x.path.startsWith('/api/v1/') && Number.isInteger(x.status) && x.status >= 100 && x.status <= 599));
    check(evidence.U01 === 'NOT_EXECUTED' && evidence.U02 === 'NOT_EXECUTED' && evidence.U03 === 'NOT_EXECUTED');
  }
  private verifyEvidence() { this.restart?.assertUnchanged(); this.restart?.assertJournal(this.path, this.restart.activeIdentity, this.data); for (const stage of this.data.stages) { this.validateEvidencePath(stage.reportPath, stage.caseIdentity); noLinks(stage.reportPath); const bytes = readFileSync(stage.reportPath, 'utf8'); check(sha(bytes) === stage.reportSha256); this.validateEvidence(JSON.parse(bytes), stage.caseIdentity); } }
  private async save(next: Journal) {
    this.requireClear(); this.verifyEvidence(); await this.protect(); this.requireClear(); noLinks(this.path); validate(next); this.restart?.assertJournal(this.path, this.restart.activeIdentity, next);
    const bytes = JSON.stringify(next), pending = this.path + '.pending'; const fd = openSync(pending, 'wx', 0o600);
    try { check(readFileSync(this.path, 'utf8') === this.text); writeFileSync(fd, bytes); fsyncSync(fd); } finally { closeSync(fd); }
    await this.protect(); noLinks(this.path); noLinks(pending); check(readFileSync(this.path, 'utf8') === this.text && readFileSync(pending, 'utf8') === bytes);
    renameSync(pending, this.path); this.text = bytes; this.data = next;
  }
  async begin(command: BusinessCommand): Promise<void> { const original = structuredClone(command); return this.mutate(async () => {
    validCommand(original, this.data.commands.length); check(!this.pending() && this.data.commands.every(x => x.status === 'CONFIRMED'));
    const completedBoundaries = STAGE_END.filter(end => end <= this.data.commands.length).length; check(this.data.stages.length === completedBoundaries);
    await this.save({ ...this.data, commands: [...this.data.commands, { ...original, at: new Date().toISOString(), status: 'PENDING' }] });
  }); }
  async complete(commandId: string, status: number, receipt: any, selectors: BusinessSelectors): Promise<BusinessFact> {
    receipt = structuredClone(receipt); selectors = structuredClone(selectors); return this.mutate(async () => {
      const pending = this.pending(); check(pending?.commandId === commandId && (status === 200 || status === 201));
      check(exact(receipt, ['commandId','receiptId','completedAt','outcome','resultFact']) && receipt.commandId === commandId && uuid.test(receipt.receiptId) && Number.isFinite(Date.parse(receipt.completedAt)) && receipt.outcome === 'SUCCEEDED');
      const fact = receipt.resultFact; validFact(pending.step, fact); validSelectors(pending.step, selectors);
      const entry: BusinessEntry = { ...pending, status: 'CONFIRMED', httpStatus: status, receiptId: receipt.receiptId, resultFact: structuredClone(fact), selectors };
      await this.save({ ...this.data, commands: this.data.commands.map(x => x.commandId === commandId ? entry : x) }); return structuredClone(entry.resultFact!);
    });
  }
  requirePrevious(id: BusinessCaseId): void { check(!this.busy && !this.failed); this.requirePreviousState(id); }
  private requirePreviousState(id: BusinessCaseId) { this.requireClear(); this.verifyEvidence(); check(!this.pending()); const index = BUSINESS_CASES.indexOf(id), start = index > 0 ? STAGE_END[index - 1] : 0; check(index >= 0 && this.data.stages.length === index && this.data.commands.length >= start && this.data.commands.length <= STAGE_END[index] && this.data.commands.every(x => x.status === 'CONFIRMED')); }
  async finishStage(id: BusinessCaseId, evidence: BusinessEvidence, io: CompletionIO = {}): Promise<void> { evidence = structuredClone(evidence); return this.mutate(async () => {
    const index = BUSINESS_CASES.indexOf(id); check(index >= 0 && this.data.stages.length === index && this.data.commands.length === STAGE_END[index] && this.data.commands.every(x => x.status === 'CONFIRMED'));
    this.validateEvidence(evidence, id); await this.protect(); noLinks(this.path); check(readFileSync(this.path, 'utf8') === this.text && !existsSync(this.path + '.pending'));
    const evidenceBytes = JSON.stringify(evidence), next: Journal = { ...this.data, stages: [...this.data.stages, { caseIdentity: id, reportPath: evidence.reportPath, reportSha256: sha(evidenceBytes), status: 'PASSED_SUBSCENARIO', exitCode: 0 }] };
    validate(next); const nextBytes = JSON.stringify(next), intent = this.path + '.completion.pending'; durableExclusive(intent, nextBytes);
    (io.writeEvidence ?? durableExclusive)(evidence.reportPath, evidenceBytes); await this.protect(); noLinks(evidence.reportPath); check(readFileSync(evidence.reportPath, 'utf8') === evidenceBytes); noLinks(intent); check(readFileSync(intent, 'utf8') === nextBytes && readFileSync(this.path, 'utf8') === this.text);
    renameSync(intent, this.path); this.text = nextBytes; this.data = next;
  }); }
}
