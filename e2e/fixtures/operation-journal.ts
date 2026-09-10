import { closeSync, existsSync, fsyncSync, openSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { basename, dirname } from 'node:path';
import { check, exact, noLinks, sha, uuid } from './local-environment';

export const CASES = ['T9-L01-entry', 'T9-L03-unmapped', 'T9-I02-exact-directory-binding', 'T9-L04-qualification-stages', 'T9-I05-appointment-no-implicit-grant', 'T9-I06-minimum-business-grants', 'T9-I13-dynamic-entry'] as const;
export type CaseId = typeof CASES[number];
export const PATH_FACT = {
  '/api/v1/admin/identity/principals': 'IDENTITY_PRINCIPAL',
  '/api/v1/admin/identity/organizations': 'ORGANIZATION_UNIT',
  '/api/v1/admin/identity/appointments': 'APPOINTMENT',
  '/api/v1/admin/identity/authority-grants': 'AUTHORITY_GRANT',
} as const;
export interface RunIdentity { runId: string; environmentDigest: string; buildSha: string }
export interface PhaseEvidence extends RunIdentity {
  apiIdentity: string; executedAt: string; caseIdentity: CaseId;
  status: 'ACTIONS_VERIFIED'; exitCode: null; reportPath: string;
  http: Array<{ path: string; status: number }>;
  U01: 'NOT_EXECUTED'; U02: 'NOT_EXECUTED'; U03: 'NOT_EXECUTED';
}
export interface CompletionIO { writeEvidence?: (path: string, bytes: string) => void }
export interface Command {
  step: string; commandId: string; method: string; path: string; bodySha256: string; actorScopeKey: string;
}
export interface Fact { factType: string; factRef: string; revision: number }
export interface Entry extends Command { at: string; status: 'PENDING' | 'CONFIRMED'; httpStatus?: number; receiptId?: string; resultFact?: Fact; resourcePath?: string }
interface PhaseCommit { caseIdentity: CaseId; reportPath: string; reportSha256: string; status: 'PASSED_SUBSCENARIO'; exitCode: 0 }
interface Journal { identity: RunIdentity; commands: Entry[]; stages: PhaseCommit[] }
const hash = /^[0-9a-f]{64}$/;
const steps = /^(principal-(intake|supervisor|contact|delegate)|organization|appointment-(intake|supervisor|contact|delegate)|grant-(intake|supervisor)-[0-3])$/;
function validCommand(c: Command): void {
  check(exact(c, ['step', 'commandId', 'method', 'path', 'bodySha256', 'actorScopeKey']));
  check(steps.test(c.step) && uuid.test(c.commandId) && c.method === 'POST' && Object.hasOwn(PATH_FACT, c.path) && hash.test(c.bodySha256) && /^ask1\.[A-Za-z0-9_-]{43}$/.test(c.actorScopeKey));
  const expected = c.step.startsWith('principal-') ? 'principals' : c.step === 'organization' ? 'organizations' : c.step.startsWith('appointment-') ? 'appointments' : 'authority-grants';
  check(c.path === `/api/v1/admin/identity/${expected}`);
}
function validate(data: Journal): void {
  check(exact(data, ['identity', 'commands', 'stages']) && exact(data.identity, ['runId', 'environmentDigest', 'buildSha']));
  check(uuid.test(data.identity.runId) && hash.test(data.identity.environmentDigest) && /^[0-9a-f]{40}$/.test(data.identity.buildSha));
  check(Array.isArray(data.commands) && data.commands.length <= 16 && Array.isArray(data.stages));
  const keys = new Set(), names = new Set();
  for (const [i, e] of data.commands.entries()) {
    const { at, status, httpStatus, receiptId, resultFact, resourcePath, ...command } = e; validCommand(command);
    check(new Date(at).toISOString() === at && !keys.has(e.commandId) && !names.has(e.step)); keys.add(e.commandId); names.add(e.step);
    check(status === 'PENDING' || status === 'CONFIRMED');
    if (status === 'PENDING') check(i === data.commands.length - 1 && httpStatus === undefined && receiptId === undefined && resultFact === undefined && resourcePath === undefined);
    else {
      check((httpStatus === 201 || httpStatus === 200) && uuid.test(receiptId!) && resultFact && exact(resultFact, ['factType', 'factRef', 'revision']));
      check(resultFact.factType === PATH_FACT[e.path as keyof typeof PATH_FACT] && /^[A-Za-z0-9_-]{43}$/.test(resultFact.factRef) && resultFact.revision === 0);
      check(resourcePath?.startsWith(e.path + '/') && uuid.test(resourcePath.slice(e.path.length + 1)));
    }
  }
  check(data.stages.length <= CASES.length && data.stages.every((s, i) => exact(s, ['caseIdentity', 'reportPath', 'reportSha256', 'status', 'exitCode']) && s.caseIdentity === CASES[i] && typeof s.reportPath === 'string' && hash.test(s.reportSha256) && s.status === 'PASSED_SUBSCENARIO' && s.exitCode === 0));
}
function durableExclusive(path: string, bytes: string): void {
  const fd = openSync(path, 'wx', 0o600);
  try { writeFileSync(fd, bytes); fsyncSync(fd); } finally { closeSync(fd); }
}
export class OperationJournal {
  private text = '';
  private data!: Journal;
  private busy = false;
  private failed = false;
  private constructor(readonly path: string, private readonly protect: () => void | Promise<void>) {}
  static async open(path: string, identity: RunIdentity, protect: () => void | Promise<void>): Promise<OperationJournal> {
    const journal = new OperationJournal(path, protect);
    await journal.initialize(structuredClone(identity)); return journal;
  }
  private async initialize(identity: RunIdentity): Promise<void> {
    const path = this.path;
    await this.protect(); noLinks(dirname(path)); this.requireCompletionClear(); check(!existsSync(path + '.pending'));
    if (existsSync(path)) { noLinks(path); this.text = readFileSync(path, 'utf8'); this.data = JSON.parse(this.text); validate(this.data); check(JSON.stringify(this.data.identity) === JSON.stringify(identity)); }
    else {
      this.data = { identity, commands: [], stages: [] }; validate(this.data);
      this.text = JSON.stringify(this.data); const fd = openSync(path, 'wx', 0o600);
      try { writeFileSync(fd, this.text); fsyncSync(fd); } finally { closeSync(fd); }
      await this.protect();
    }
    this.verifyEvidence();
  }
  private async mutate<T>(action: () => Promise<T>): Promise<T> {
    // Claim synchronously, before the first awaited protection. Never queue a
    // stale mutation or retry it after an unknown outcome.
    check(!this.busy && !this.failed); this.busy = true;
    try { return await action(); }
    catch (error) { this.failed = true; throw error; }
    finally { this.busy = false; }
  }
  private requireCompletionClear(): void { check(!existsSync(this.path + '.completion.pending')); }
  private validateEvidence(evidence: PhaseEvidence, id: CaseId): void {
    check(exact(evidence, ['runId', 'environmentDigest', 'buildSha', 'apiIdentity', 'executedAt', 'caseIdentity', 'status', 'exitCode', 'reportPath', 'http', 'U01', 'U02', 'U03']));
    check(evidence.runId === this.data.identity.runId && evidence.environmentDigest === this.data.identity.environmentDigest && evidence.buildSha === this.data.identity.buildSha);
    check(hash.test(evidence.apiIdentity) && new Date(evidence.executedAt).toISOString() === evidence.executedAt && evidence.caseIdentity === id && evidence.status === 'ACTIONS_VERIFIED' && evidence.exitCode === null);
    this.validateEvidencePath(evidence.reportPath, id);
    check(evidence.U01 === 'NOT_EXECUTED' && evidence.U02 === 'NOT_EXECUTED' && evidence.U03 === 'NOT_EXECUTED');
    check(Array.isArray(evidence.http) && evidence.http.length <= 1000 && evidence.http.every(item => exact(item, ['path', 'status']) && Number.isInteger(item.status) && item.status >= 100 && item.status <= 599 && (item.path === '/api/v1/session/context' || item.path === '/api/v1/workcards/current' || Object.hasOwn(PATH_FACT, item.path) || /^\/api\/v1\/commands\/[0-9a-f-]{36}\/receipt$/.test(item.path))));
  }
  private validateEvidencePath(path: string, id: CaseId): void {
    const prefix = `task9-${this.data.identity.runId}-${id}-`;
    check(dirname(path) === dirname(this.path) && basename(path).startsWith(prefix) && basename(path).endsWith('.json') && uuid.test(basename(path).slice(prefix.length, -5)));
  }
  private verifyEvidence(): void {
    for (const stage of this.data.stages) {
      this.validateEvidencePath(stage.reportPath, stage.caseIdentity); noLinks(stage.reportPath);
      const bytes = readFileSync(stage.reportPath, 'utf8'); check(sha(bytes) === stage.reportSha256);
      this.validateEvidence(JSON.parse(bytes), stage.caseIdentity);
    }
  }
  private async save(next: Journal): Promise<void> {
    this.requireCompletionClear(); this.verifyEvidence();
    await this.protect(); this.requireCompletionClear(); noLinks(this.path); validate(next);
    const nextBytes = JSON.stringify(next);
    const fd = openSync(this.path + '.pending', 'wx', 0o600);
    try { check(readFileSync(this.path, 'utf8') === this.text); writeFileSync(fd, nextBytes); fsyncSync(fd); } finally { closeSync(fd); }
    await this.protect(); this.requireCompletionClear(); this.verifyEvidence();
    await this.protect(); this.requireCompletionClear(); noLinks(this.path); noLinks(this.path + '.pending');
    check(readFileSync(this.path, 'utf8') === this.text && readFileSync(this.path + '.pending', 'utf8') === nextBytes);
    // Publish only after every fallible check. Failure preserves the pending file.
    renameSync(this.path + '.pending', this.path);
    this.text = nextBytes; this.data = next;
  }
  async begin(command: Command): Promise<void> {
    const original = structuredClone(command);
    return this.mutate(async () => {
      validCommand(original); check(!this.pending()); check(!this.data.commands.some(e => e.step === original.step || e.commandId === original.commandId));
      await this.save({ ...this.data, commands: [...this.data.commands, { ...original, at: new Date().toISOString(), status: 'PENDING' }] });
    });
  }
  pending(): Entry | undefined { return structuredClone(this.data.commands.find(e => e.status === 'PENDING')); }
  confirmed(step: string): Entry | undefined { return structuredClone(this.data.commands.find(e => e.step === step && e.status === 'CONFIRMED')); }
  async complete(commandId: string, status: number, receipt: any, resourcePath: string): Promise<Fact> {
    receipt = structuredClone(receipt);
    return this.mutate(async () => {
    const pending = this.pending(); check(pending && pending.commandId === commandId && (status === 201 || status === 200));
    check(exact(receipt, ['commandId', 'receiptId', 'completedAt', 'outcome', 'resultFact']) && receipt.commandId === commandId && receipt.outcome === 'SUCCEEDED' && uuid.test(receipt.receiptId));
    check(Number.isFinite(Date.parse(receipt.completedAt)));
    const fact = receipt.resultFact;
    check(exact(fact, ['factType', 'factRef', 'revision']) && fact.factType === PATH_FACT[pending.path as keyof typeof PATH_FACT] && /^[A-Za-z0-9_-]{43}$/.test(fact.factRef) && fact.revision === 0);
    const entry: Entry = { ...pending, status: 'CONFIRMED', httpStatus: status, receiptId: receipt.receiptId, resourcePath, resultFact: { factType: fact.factType, factRef: fact.factRef, revision: fact.revision } };
    await this.save({ ...this.data, commands: this.data.commands.map(e => e.commandId === commandId ? entry : e) }); return structuredClone(entry.resultFact!);
    });
  }
  requirePrevious(id: CaseId): void {
    check(!this.busy && !this.failed); this.requirePreviousState(id);
  }
  private requirePreviousState(id: CaseId): void {
    this.requireCompletionClear(); this.verifyEvidence();
    check(!this.pending()); const index = CASES.indexOf(id); check(index >= 0 && this.data.stages.length === index);
  }
  async finishStage(id: CaseId, evidence: PhaseEvidence, io: CompletionIO = {}): Promise<void> {
    evidence = structuredClone(evidence);
    return this.mutate(async () => {
    this.requirePreviousState(id); this.validateEvidence(evidence, id); await this.protect();
    noLinks(this.path); check(readFileSync(this.path, 'utf8') === this.text && !existsSync(this.path + '.pending'));
    const evidenceBytes = JSON.stringify(evidence);
    const next: Journal = { ...this.data, stages: [...this.data.stages, { caseIdentity: id, reportPath: evidence.reportPath, reportSha256: sha(evidenceBytes), status: 'PASSED_SUBSCENARIO', exitCode: 0 }] };
    validate(next); const nextBytes = JSON.stringify(next), intent = this.path + '.completion.pending';
    // Durable intent is a quarantine marker, NOT a commit. Reopened workers and
    // all command saves reject it. Never delete/overwrite it after an error.
    durableExclusive(intent, nextBytes);
    (io.writeEvidence ?? durableExclusive)(evidence.reportPath, evidenceBytes);
    await this.protect(); noLinks(evidence.reportPath);
    check(readFileSync(evidence.reportPath, 'utf8') === evidenceBytes);
    this.verifyEvidence();
    noLinks(intent); check(readFileSync(intent, 'utf8') === nextBytes);
    noLinks(this.path); check(readFileSync(this.path, 'utf8') === this.text && !existsSync(this.path + '.pending'));
    // The final atomic rename is the publication boundary. No protection, I/O,
    // validation, reporter callback, or other fallible completion work follows.
    // A failed native rename preserves the intent and blocks every later write.
    renameSync(intent, this.path);
    this.text = nextBytes; this.data = next;
    });
  }
}
