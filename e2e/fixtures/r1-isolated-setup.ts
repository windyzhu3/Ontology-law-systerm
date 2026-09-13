import { createHash, randomUUID } from 'node:crypto';
import { closeSync, existsSync, fsyncSync, openSync, readFileSync, renameSync, writeFileSync } from 'node:fs';

import { FACT_DIGEST, HASH, UUID, boundary } from './r1-isolated-environment';


export const ACTOR_SCOPE = /^ask1\.[A-Za-z0-9_-]{43}$/;
export const WRITE_SEQUENCE: readonly { step: string; method: 'POST'|'PUT'; path: string|RegExp }[] = [
  ...['sales','supervisor','sourceOwner'].map(account => ({ step: `principal-${account}`, method: 'POST' as const, path: '/api/v1/admin/identity/principals' })),
  ...['OWNED_ROOT','EMPTY_ROOT'].map(code => ({ step: `organization-${code}`, method: 'POST' as const, path: '/api/v1/admin/identity/organizations' })),
  ...['sales','supervisor','sourceOwner'].map(account => ({ step: `appointment-${account}`, method: 'POST' as const, path: '/api/v1/admin/identity/appointments' })),
  ...[
    ['sourceOwner','LEAD_CAPTURE'], ['sourceOwner','LEAD_INGRESS_RESOLVE'], ['sourceOwner','LEAD_INGRESS_COMPLETE'], ['sourceOwner','SOURCE_INTAKE_REQUEST_ACK'],
    ['supervisor','LEAD_ASSIGN'], ['supervisor','LEAD_ROUTING_DECIDE'], ['supervisor','LEAD_VALIDITY_REVIEW'],
  ].map(([account, authority]) => ({ step: `grant-${account}-${authority}`, method: 'POST' as const, path: '/api/v1/admin/identity/authority-grants' })),
  { step: 'grant-sales-SALES_CONTACT_OWNER', method: 'POST', path: '/api/v1/admin/identity/authority-grants' },
  { step: 'capture-R1_AUTO', method: 'POST', path: '/api/v1/leads' },
  { step: 'ingress-draft', method: 'PUT', path: /^\/api\/v1\/tasks\/[0-9a-f-]{36}\/draft$/ },
  { step: 'ingress-submit', method: 'POST', path: /^\/api\/v1\/tasks\/[0-9a-f-]{36}\/commands\/complete-lead-ingress$/ },
  { step: 'contact-draft', method: 'PUT', path: /^\/api\/v1\/tasks\/[0-9a-f-]{36}\/draft$/ },
  { step: 'contact-submit', method: 'POST', path: /^\/api\/v1\/tasks\/[0-9a-f-]{36}\/commands\/record-contact-result$/ },
];

export function canonicalSha256(value: unknown): string {
  const canonical = (item: any): any => Array.isArray(item) ? item.map(canonical)
    : item && typeof item === 'object' ? Object.fromEntries(Object.keys(item).sort().map(key => [key, canonical(item[key])])) : item;
  return createHash('sha256').update(JSON.stringify(canonical(value))).digest('hex');
}

export interface JournalIdentity { run: string; operationId: string; environmentDigest: string; sourceCommit: string }
export interface JournalCommand {
  step: string; commandId: string; method: string; path: string; bodySha256: string;
  actorScopeKey: string; actorAppointmentId: string; onBehalfAppointmentId: null;
}
export interface ArmedWrite extends JournalCommand { body: Record<string, unknown> }
export type WriteIntent = Omit<ArmedWrite, 'commandId'> & { commandId?: string };
export type ConfirmedWrite = JournalCommand & { status: 'CONFIRMED'; httpStatus: number; receiptId: string; resultFact: Record<string, unknown>; resourceId: string };
type Pending = JournalCommand & { status: 'PENDING' };
type JournalData = { profile: 'R1_ISOLATED_ACCEPTANCE_JOURNAL_V1'; identity: JournalIdentity; commands: (Pending|ConfirmedWrite)[]; stages: string[] };
const STAGES = ['MANAGEMENT_COMPLETED','SALES_AUTHORITY_COMPLETED','IDENTITIES_VERIFIED','CAPTURE_COMPLETED','INGRESS_DRAFT_RELOADED','INGRESS_COMPLETED','DRAFT_RELOADED','GOLDEN_COMPLETED'] as const;

function exact(value: unknown, keys: string[]): value is Record<string, any> {
  return !!value && typeof value === 'object' && Object.keys(value).sort().join() === [...keys].sort().join();
}

function validIdentity(value: any): value is JournalIdentity {
  return exact(value, ['run','operationId','environmentDigest','sourceCommit']) && /^[a-z0-9][a-z0-9-]{0,31}$/.test(value.run)
    && UUID.test(value.operationId) && HASH.test(value.environmentDigest) && /^[0-9a-f]{40}$/.test(value.sourceCommit);
}

function policy(index: number, command: JournalCommand): void {
  const expected = WRITE_SEQUENCE[index]; boundary(expected && command.step === expected.step && command.method === expected.method);
  boundary(typeof expected.path === 'string' ? command.path === expected.path : expected.path.test(command.path));
  boundary(UUID.test(command.commandId) && HASH.test(command.bodySha256) && ACTOR_SCOPE.test(command.actorScopeKey));
  boundary(UUID.test(command.actorAppointmentId) && command.onBehalfAppointmentId === null);
}

function validProgress(data: JournalData): void {
  boundary(data.stages.every((stage, index) => stage === STAGES[index]));
  const confirmed = data.commands.filter(command => command.status === 'CONFIRMED').length;
  const minimums = [15,16,16,17,18,19,20,21];
  if (data.stages.length) boundary(confirmed >= minimums[data.stages.length - 1]);
  const maximums = [15,16,16,17,18,19,19,21,21];
  boundary(data.commands.length <= maximums[data.stages.length]);
  if (data.stages.includes('MANAGEMENT_COMPLETED')) boundary(data.commands.slice(0, 15).every(command => command.status === 'CONFIRMED'));
  if (data.stages.includes('SALES_AUTHORITY_COMPLETED')) boundary(data.commands[15]?.status === 'CONFIRMED');
}

export class AcceptanceJournal {
  private constructor(readonly path: string, private data: JournalData, private protect: () => Promise<void>) {}
  static async open(path: string, identity: JournalIdentity, protect: () => Promise<void> = async () => {}): Promise<AcceptanceJournal> {
    boundary(validIdentity(identity));
    if (!existsSync(path)) {
      const data: JournalData = { profile: 'R1_ISOLATED_ACCEPTANCE_JOURNAL_V1', identity, commands: [], stages: [] };
      const fd = openSync(path, 'wx', 0o600); try { writeFileSync(fd, JSON.stringify(data)); fsyncSync(fd); } finally { closeSync(fd); }
      await protect(); return new AcceptanceJournal(path, data, protect);
    }
    let data: JournalData; try { data = JSON.parse(readFileSync(path, 'utf8')); } catch { throw new Error('R1_ISOLATED_BOUNDARY'); }
    boundary(data.profile === 'R1_ISOLATED_ACCEPTANCE_JOURNAL_V1' && JSON.stringify(data.identity) === JSON.stringify(identity));
    boundary(Array.isArray(data.commands) && Array.isArray(data.stages));
    data.commands.forEach((command, index) => { policy(index, command); boundary(command.status === 'PENDING' || command.status === 'CONFIRMED'); });
    boundary(data.commands.filter(command => command.status === 'PENDING').length <= 1 && !data.commands.slice(0, -1).some(command => command.status === 'PENDING'));
    validProgress(data);
    return new AcceptanceJournal(path, data, protect);
  }
  pending(): Pending | undefined { return this.data.commands.find(command => command.status === 'PENDING') as Pending|undefined; }
  confirmed(step: string): ConfirmedWrite | undefined { return this.data.commands.find(command => command.step === step && command.status === 'CONFIRMED') as ConfirmedWrite|undefined; }
  resources(): Record<string,string> { return Object.fromEntries(this.data.commands.filter(command => command.status === 'CONFIRMED').map(command => [command.step, (command as ConfirmedWrite).resourceId])); }
  hasStage(stage: string): boolean { return this.data.stages.includes(stage); }
  private async save(): Promise<void> {
    const temporary = this.path + '.' + randomUUID() + '.tmp';
    const fd = openSync(temporary, 'wx', 0o600); try { writeFileSync(fd, JSON.stringify(this.data)); fsyncSync(fd); } finally { closeSync(fd); }
    renameSync(temporary, this.path); await this.protect();
  }
  async begin(command: JournalCommand): Promise<void> {
    boundary(!this.pending()); policy(this.data.commands.length, command);
    this.data.commands.push({ ...command, status: 'PENDING' }); await this.save();
  }
  async confirm(commandId: string, response: { status: number; headers: Record<string,string>; body: any }, selected: { resourceId: string }): Promise<void> {
    const pending = this.pending(); boundary(pending?.commandId === commandId);
    boundary(['capture-R1_AUTO','ingress-submit','contact-submit'].includes(pending.step)
      ? typeof selected.resourceId === 'string' && selected.resourceId.length >= 16 && selected.resourceId.length <= 512
      : UUID.test(selected.resourceId));
    const expectedStatus = pending.method === 'PUT' ? 201 : ['ingress-submit','contact-submit'].includes(pending.step) ? 200 : 201;
    boundary(response.status === expectedStatus && response.headers.location === `/api/v1/commands/${commandId}/receipt`);
    boundary(response.headers['cache-control']?.toLowerCase().split(',').map(value => value.trim()).includes('no-store'));
    const receipt = response.body;
    boundary(receipt?.commandId === commandId && UUID.test(receipt.receiptId) && receipt.outcome === 'SUCCEEDED');
    boundary(exact(receipt.resultFact, ['factType','factRef','revision']) || exact(receipt.resultFact, ['factType','factRef','digest']));
    const index = this.data.commands.indexOf(pending);
    this.data.commands[index] = { ...pending, status: 'CONFIRMED', httpStatus: response.status, receiptId: receipt.receiptId, resultFact: receipt.resultFact, resourceId: selected.resourceId };
    await this.save();
  }
  async completeStage(stage: string): Promise<void> {
    const index = STAGES.indexOf(stage as typeof STAGES[number]); boundary(index === this.data.stages.length && !this.pending());
    if (stage === 'MANAGEMENT_COMPLETED') boundary(this.data.commands.length === 15 && this.data.commands.every(command => command.status === 'CONFIRMED'));
    if (stage === 'SALES_AUTHORITY_COMPLETED') boundary(this.data.commands.length === 16 && this.data.commands.every(command => command.status === 'CONFIRMED'));
    this.data.stages.push(stage); await this.save();
  }
}

export class AcceptanceDispatchGate {
  private armed?: WriteIntent;
  private poisoned = false;
  constructor(private journal: AcceptanceJournal) {}
  arm(command: WriteIntent): void { boundary(!this.poisoned && !this.armed && canonicalSha256(command.body) === command.bodySha256); this.armed = command; }
  async dispatch(actual: { commandId: string; method: string; path: string; bodyBytes: Buffer; actorScopeKey: string; actorAppointmentId: string; onBehalfAppointmentId: null }, send: () => Promise<void>): Promise<void> {
    const armed = this.armed; this.armed = undefined;
    try {
      boundary(!this.poisoned && armed && !this.journal.pending());
      const body = JSON.parse(actual.bodyBytes.toString('utf8'));
      boundary(UUID.test(actual.commandId) && (!armed.commandId || actual.commandId === armed.commandId) && actual.method === armed.method && actual.path === armed.path);
      boundary(actual.actorScopeKey === armed.actorScopeKey && actual.actorAppointmentId === armed.actorAppointmentId && actual.onBehalfAppointmentId === null);
      boundary(canonicalSha256(body) === armed.bodySha256 && JSON.stringify(body) === JSON.stringify(armed.body));
      await this.journal.begin({ step: armed.step, commandId: actual.commandId, method: armed.method, path: armed.path, bodySha256: armed.bodySha256, actorScopeKey: armed.actorScopeKey, actorAppointmentId: armed.actorAppointmentId, onBehalfAppointmentId: null });
      await send();
    } catch (error) { this.poisoned = true; throw error; }
  }
}

export interface GoldenAdapter {
  prepareWrite(policy: typeof WRITE_SEQUENCE[number], resources: Record<string,string>): Promise<WriteIntent>;
  executeWrite(command: WriteIntent, gate: AcceptanceDispatchGate): Promise<{ response: { status: number; headers: Record<string,string>; body: any }; resourceId: string }>;
  verifyIdentities(resources: Record<string,string>): Promise<void>;
  locateIngressTask(resources: Record<string,string>, capture: ConfirmedWrite): Promise<string>;
  reloadIngressDraft(resources: Record<string,string>): Promise<void>;
  locateContactTask(resources: Record<string,string>, capture: ConfirmedWrite, ingress: ConfirmedWrite): Promise<string>;
  reloadDraft(resources: Record<string,string>): Promise<void>;
  retrieveReceipt(alias: 'sourceOwner'|'sales', commandId: string, resources: Record<string,string>): Promise<any>;
  closeCompletion(commandId: string, resultFactDigest: string, resources: Record<string,string>, capture: ConfirmedWrite, ingress: ConfirmedWrite): Promise<{ commandId: string; counts: Record<string,number> }>;
}

export class R1GoldenOrchestrator {
  private gate: AcceptanceDispatchGate;
  constructor(private journal: AcceptanceJournal, private adapter: GoldenAdapter) { this.gate = new AcceptanceDispatchGate(journal); }
  private async write(index: number, resources: Record<string,string>): Promise<ConfirmedWrite> {
    const policyEntry = WRITE_SEQUENCE[index];
    const previous = this.journal.confirmed(policyEntry.step);
    if (previous) return previous;
    boundary(!this.journal.pending());
    const command = await this.adapter.prepareWrite(policyEntry, resources);
    policy(index, { ...command, commandId: command.commandId ?? randomUUID() }); boundary(canonicalSha256(command.body) === command.bodySha256);
    this.gate.arm(command);
    const result = await this.adapter.executeWrite(command, this.gate);
    const actualCommandId = result.response.body?.commandId; boundary(UUID.test(actualCommandId));
    await this.journal.confirm(actualCommandId, result.response, { resourceId: result.resourceId });
    const confirmed = this.journal.confirmed(policyEntry.step); boundary(confirmed);
    resources[policyEntry.step] = confirmed.resourceId;
    return confirmed;
  }
  private taskFromDraft(step: 'ingress-draft'|'contact-draft'): string | undefined {
    const path = this.journal.confirmed(step)?.path, match = path?.match(/^\/api\/v1\/tasks\/([0-9a-f-]{36})\/draft$/);
    return match?.[1];
  }
  async run(): Promise<{ flowProfile: 'CAPTURE_INGRESS_AUTOASSIGN_CONTACT_V1'; managementCommandCount: 16; goldenCommandId: string; resultFact: Record<string,unknown>; counts: Record<string,number> }> {
    boundary(!this.journal.pending());
    const resources = this.journal.resources();
    if (!this.journal.hasStage('MANAGEMENT_COMPLETED')) {
      for (let index = 0; index < 15; index++) await this.write(index, resources);
      await this.journal.completeStage('MANAGEMENT_COMPLETED');
    }
    if (!this.journal.hasStage('SALES_AUTHORITY_COMPLETED')) {
      await this.write(15, resources); await this.journal.completeStage('SALES_AUTHORITY_COMPLETED');
    }
    if (!this.journal.hasStage('IDENTITIES_VERIFIED')) {
      await this.adapter.verifyIdentities(resources); await this.journal.completeStage('IDENTITIES_VERIFIED');
    }
    const capture = await this.write(16, resources);
    boundary(capture.resultFact.factType === 'LEAD' && typeof capture.resultFact.factRef === 'string' && Number.isSafeInteger(capture.resultFact.revision));
    if (!this.journal.hasStage('CAPTURE_COMPLETED')) await this.journal.completeStage('CAPTURE_COMPLETED');
    resources.ingressTask = this.taskFromDraft('ingress-draft') ?? '';
    resources.ingressTask = await this.adapter.locateIngressTask(resources, capture); boundary(UUID.test(resources.ingressTask));
    if (!this.journal.hasStage('INGRESS_DRAFT_RELOADED')) {
      await this.write(17, resources); await this.adapter.reloadIngressDraft(resources); await this.journal.completeStage('INGRESS_DRAFT_RELOADED');
    }
    const ingress = await this.write(18, resources);
    const ingressReceipt = await this.adapter.retrieveReceipt('sourceOwner', ingress.commandId, resources);
    boundary(JSON.stringify(ingressReceipt) === JSON.stringify({ commandId: ingress.commandId, receiptId: ingress.receiptId, outcome: 'SUCCEEDED', resultFact: ingress.resultFact }));
    boundary(ingress.resultFact.factType === 'LEAD' && ingress.resultFact.factRef === capture.resultFact.factRef
      && Number.isSafeInteger(ingress.resultFact.revision) && ingress.resultFact.revision === Number(capture.resultFact.revision) + 1);
    if (!this.journal.hasStage('INGRESS_COMPLETED')) await this.journal.completeStage('INGRESS_COMPLETED');
    resources.contactTask = this.taskFromDraft('contact-draft') ?? '';
    resources.contactTask = await this.adapter.locateContactTask(resources, capture, ingress); boundary(UUID.test(resources.contactTask));
    if (!this.journal.hasStage('DRAFT_RELOADED')) {
      await this.write(19, resources); await this.adapter.reloadDraft(resources); await this.journal.completeStage('DRAFT_RELOADED');
    }
    const submit = await this.write(20, resources);
    const recovered = await this.adapter.retrieveReceipt('sales', submit.commandId, resources);
    boundary(JSON.stringify(recovered) === JSON.stringify({ commandId: submit.commandId, receiptId: submit.receiptId, outcome: 'SUCCEEDED', resultFact: submit.resultFact }));
    boundary(submit.resultFact.factType === 'LEAD_CONTACT_RESULT' && FACT_DIGEST.test(String(submit.resultFact.digest)));
    const closure = await this.adapter.closeCompletion(submit.commandId, String(submit.resultFact.digest), resources, capture, ingress);
    boundary(closure.commandId === submit.commandId && JSON.stringify(closure.counts) === JSON.stringify({ contactResult: 1, opportunity: 1, event: 2, outbox: 2, receipt: 1, audit: 1 }));
    if (!this.journal.hasStage('GOLDEN_COMPLETED')) await this.journal.completeStage('GOLDEN_COMPLETED');
    return { flowProfile: 'CAPTURE_INGRESS_AUTOASSIGN_CONTACT_V1', managementCommandCount: 16, goldenCommandId: submit.commandId, resultFact: submit.resultFact, counts: closure.counts };
  }
}
