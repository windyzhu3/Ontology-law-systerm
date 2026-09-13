import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { expect, test } from '@playwright/test';

type ArmedWrite = {
  step: string; commandId: string; method: string; path: string;
  body: Record<string, unknown>; bodySha256: string; actorScopeKey: string;
  actorAppointmentId: string; onBehalfAppointmentId: null;
};
let environmentModule: any, setupModule: any;
try { environmentModule = require('../fixtures/r1-isolated-environment'); } catch { environmentModule = {}; }
try { setupModule = require('../fixtures/r1-isolated-setup'); } catch { setupModule = {}; }
const missing = () => { throw new Error('r1 isolated implementation is missing'); };
const requireR1IsolatedAcceptance = environmentModule.requireR1IsolatedAcceptance ?? missing;
const validateAcceptanceEnvironment = environmentModule.validateAcceptanceEnvironment ?? missing;
const AcceptanceDispatchGate = setupModule.AcceptanceDispatchGate ?? class { constructor() { missing(); } };
const AcceptanceJournal = setupModule.AcceptanceJournal ?? { open: missing };
const R1GoldenOrchestrator = setupModule.R1GoldenOrchestrator ?? class { constructor() { missing(); } };
const WRITE_SEQUENCE: { step: string; method: 'POST'|'PUT'; path: string|RegExp }[] = setupModule.WRITE_SEQUENCE ?? [];
const canonicalSha256 = setupModule.canonicalSha256 ?? missing;


const ROOT = resolve(__dirname, '../..');
const NODE = 'C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/node-v24.20.0-win-x64/node.exe';
const RUN = 'unit-r1';
const OPERATION = '00000000-0000-4000-8000-000000000901';
const ACTOR = 'ask1.' + 'A'.repeat(43);
const APPOINTMENT = '00000000-0000-4000-8000-000000000902';


test('offline config is the default and the live golden project needs exact explicit selection', () => {
  const cli = join(ROOT, 'node_modules/@playwright/test/cli.js');
  const config = join(ROOT, 'e2e/r1-isolated.config.ts');
  const clean = { ...process.env };
  for (const key of ['R1_ISOLATED_ACCEPTANCE', 'R1_E2E_RUN', 'R1_ISOLATED_OPERATION_ID']) delete clean[key];
  const defaultList = execFileSync(NODE, [cli, 'test', '--config', config, '--list'], {
    cwd: ROOT, env: clean, encoding: 'utf8', windowsHide: true,
  });
  expect(defaultList).toContain('offline-r1-isolated');
  expect(defaultList).not.toContain('approved-r1-isolated');
  const approvedList = execFileSync(NODE, [cli, 'test', '--config', config, '--project', 'approved-r1-isolated', '--list'], {
    cwd: ROOT, env: clean, encoding: 'utf8', windowsHide: true,
  });
  expect(approvedList).toContain('approved-r1-isolated');
  expect(approvedList).toContain('r1-golden-path.spec.ts');
  expect(approvedList).not.toContain('r1-isolated-harness.spec.ts');
});

test('explicit approval rejects missing run operation debug flags and Task9 controls before loading', () => {
  const base = {
    R1_ISOLATED_ACCEPTANCE: 'APPROVED_SYNTHETIC_ONLY',
    R1_E2E_RUN: RUN,
    R1_ISOLATED_OPERATION_ID: OPERATION,
  };
  expect(() => requireR1IsolatedAcceptance(base)).not.toThrow();
  for (const mutation of [
    { R1_ISOLATED_ACCEPTANCE: undefined },
    { R1_ISOLATED_ACCEPTANCE: 'yes' },
    { R1_E2E_RUN: 'old/run' },
    { R1_ISOLATED_OPERATION_ID: 'not-a-uuid' },
    { DEBUG: '1' },
    { PWDEBUG: '1' },
    { TASK9_LOCAL_ACCEPTANCE: 'APPROVED_SYNTHETIC_ONLY' },
    { TASK9_BUSINESS_RUN_ID: OPERATION },
    { PLAYWRIGHT_HTML_OPEN: 'always' },
  ]) expect(() => requireR1IsolatedAcceptance({ ...base, ...mutation })).toThrow('R1_ISOLATED_BOUNDARY');
  expect(() => requireR1IsolatedAcceptance({ ...base, R1_ISOLATED_CONTINUE_OPERATION_ID: randomUUID() })).toThrow('R1_ISOLATED_BOUNDARY');
});

test('environment projection rejects wrong run process inventory credentials and digest', () => {
  const valid = environment();
  expect(() => validateAcceptanceEnvironment(valid, RUN, OPERATION)).not.toThrow();
  for (const mutate of [
    (value: any) => value.run = 'other-run',
    (value: any) => delete value.processes.worker,
    (value: any) => value.credentialReferences.sales = '../sales-password.txt',
    (value: any) => value.environmentDigest = '0'.repeat(64),
    (value: any) => value.usernames.revokedAppointment = value.usernames.sales,
  ]) {
    const changed = structuredClone(valid); mutate(changed);
    expect(() => validateAcceptanceEnvironment(changed, RUN, OPERATION)).toThrow('R1_ISOLATED_BOUNDARY');
  }
});

test('fixed write policy permits only the exact ordered 15 management writes then one capture draft submit', () => {
  expect(WRITE_SEQUENCE.map(entry => entry.step)).toEqual([
    'principal-sales', 'principal-supervisor', 'principal-sourceOwner',
    'organization-OWNED_ROOT', 'organization-EMPTY_ROOT',
    'appointment-sales', 'appointment-supervisor', 'appointment-sourceOwner',
    'grant-sourceOwner-LEAD_CAPTURE', 'grant-sourceOwner-LEAD_INGRESS_RESOLVE',
    'grant-sourceOwner-LEAD_INGRESS_COMPLETE', 'grant-sourceOwner-SOURCE_INTAKE_REQUEST_ACK',
    'grant-supervisor-LEAD_ASSIGN', 'grant-supervisor-LEAD_ROUTING_DECIDE',
    'grant-supervisor-LEAD_VALIDITY_REVIEW', 'capture-R1_AUTO', 'contact-draft', 'contact-submit',
  ]);
  expect(WRITE_SEQUENCE.slice(0, 15).every(entry => entry.method === 'POST' && typeof entry.path === 'string' && entry.path.startsWith('/api/v1/admin/identity/'))).toBe(true);
  expect(WRITE_SEQUENCE[15]).toEqual({ step: 'capture-R1_AUTO', method: 'POST', path: '/api/v1/leads' });
  expect(WRITE_SEQUENCE[16].path).toEqual(/^\/api\/v1\/tasks\/[0-9a-f-]{36}\/draft$/);
  expect(WRITE_SEQUENCE[17].path).toEqual(/^\/api\/v1\/tasks\/[0-9a-f-]{36}\/commands\/record-contact-result$/);
});

test('journal saves original key digest and Actor before dispatch and confirms only its exact receipt', async () => {
  const folder = mkdtempSync(join(tmpdir(), 'r1-acceptance-journal-'));
  try {
    const path = join(folder, 'journal.json');
    const journal = await AcceptanceJournal.open(path, identity());
    const command = write('principal-sales', '/api/v1/admin/identity/principals', { providerUserSelector: 'opaque', displayName: 'Synthetic Sales' });
    await journal.begin(command);
    const stored = JSON.parse(readFileSync(path, 'utf8'));
    expect(stored.commands[0]).toMatchObject({ commandId: command.commandId, bodySha256: command.bodySha256, actorScopeKey: ACTOR, actorAppointmentId: APPOINTMENT, onBehalfAppointmentId: null, status: 'PENDING' });
    expect(stored.commands[0]).not.toHaveProperty('body');
    const fact = { factType: 'IDENTITY_PRINCIPAL', factRef: 'opaque-fact', revision: 0 };
    await expect(journal.confirm(command.commandId, {
      status: 201,
      headers: { location: `/api/v1/commands/${randomUUID()}/receipt`, 'cache-control': 'no-store' },
      body: { commandId: command.commandId, receiptId: randomUUID(), outcome: 'SUCCEEDED', resultFact: fact },
    }, { resourceId: randomUUID() })).rejects.toThrow('R1_ISOLATED_BOUNDARY');
    expect(journal.pending()?.commandId).toBe(command.commandId);
    const receiptId = randomUUID();
    await journal.confirm(command.commandId, {
      status: 201,
      headers: { location: `/api/v1/commands/${command.commandId}/receipt`, 'cache-control': 'no-store', etag: `"identity.${'A'.repeat(43)}"` },
      body: { commandId: command.commandId, receiptId, outcome: 'SUCCEEDED', resultFact: fact },
    }, { resourceId: randomUUID() });
    expect(journal.pending()).toBeUndefined();
    expect(journal.confirmed('principal-sales')).toMatchObject({ receiptId, resultFact: fact, status: 'CONFIRMED' });
  } finally { rmSync(folder, { recursive: true, force: true }); }
});

test('unknown dispatch retains the original pending row and blocks every later write', async () => {
  const folder = mkdtempSync(join(tmpdir(), 'r1-acceptance-unknown-'));
  try {
    const journal = await AcceptanceJournal.open(join(folder, 'journal.json'), identity());
    const gate = new AcceptanceDispatchGate(journal);
    const first = armed('principal-sales', '/api/v1/admin/identity/principals', { providerUserSelector: 'opaque', displayName: 'Synthetic Sales' });
    gate.arm(first);
    let dispatched = false;
    await gate.dispatch(actual(first), async () => { dispatched = true; });
    expect(dispatched).toBe(true);
    expect(journal.pending()?.commandId).toBe(first.commandId);
    const second = armed('principal-supervisor', '/api/v1/admin/identity/principals', { providerUserSelector: 'other', displayName: 'Synthetic Supervisor' });
    gate.arm(second);
    await expect(gate.dispatch(actual(second), async () => {})).rejects.toThrow('R1_ISOLATED_BOUNDARY');
    expect(journal.pending()?.commandId).toBe(first.commandId);
    expect(JSON.parse(readFileSync(join(folder, 'journal.json'), 'utf8')).commands).toHaveLength(1);
  } finally { rmSync(folder, { recursive: true, force: true }); }
});

test('durable management stage prevents confirmed management commands from being repeated', async () => {
  const folder = mkdtempSync(join(tmpdir(), 'r1-acceptance-stage-'));
  try {
    const path = join(folder, 'journal.json');
    const journal = await AcceptanceJournal.open(path, identity());
    for (const policy of WRITE_SEQUENCE.slice(0, 15)) {
      const command = write(policy.step, String(policy.path), { marker: policy.step });
      await journal.begin(command);
      await journal.confirm(command.commandId, {
        status: 201,
        headers: { location: `/api/v1/commands/${command.commandId}/receipt`, 'cache-control': 'no-store', etag: `"identity.${'A'.repeat(43)}"` },
        body: { commandId: command.commandId, receiptId: randomUUID(), outcome: 'SUCCEEDED', resultFact: { factType: 'IDENTITY', factRef: randomUUID(), revision: 0 } },
      }, { resourceId: randomUUID() });
    }
    await journal.completeStage('MANAGEMENT_COMPLETED');
    const reopened = await AcceptanceJournal.open(path, identity());
    expect(reopened.hasStage('MANAGEMENT_COMPLETED')).toBe(true);
    await expect(reopened.begin(write('principal-sales', '/api/v1/admin/identity/principals', { marker: 'repeat' }))).rejects.toThrow('R1_ISOLATED_BOUNDARY');
  } finally { rmSync(folder, { recursive: true, force: true }); }
});

test('golden orchestrator runs fixed management before identities capture reloaded draft and one completion', async () => {
  const folder = mkdtempSync(join(tmpdir(), 'r1-acceptance-orchestrator-'));
  try {
    const journal = await AcceptanceJournal.open(join(folder, 'journal.json'), identity());
    const seen: string[] = [], checkpoints: Array<{ name: string; count: number }> = [];
    const receipts = new Map<string, any>();
    const adapter = {
      async prepareWrite(policy: any, resources: Record<string,string>) {
        if (policy.step === 'contact-draft' || policy.step === 'contact-submit') expect(resources.contactTask).toMatch(/^[0-9a-f-]{36}$/);
        const command = armed(policy.step, typeof policy.path === 'string' ? policy.path : policy.step === 'contact-draft'
          ? `/api/v1/tasks/${resources.contactTask}/draft`
          : `/api/v1/tasks/${resources.contactTask}/commands/record-contact-result`, { marker: policy.step });
        command.method = policy.method; return command;
      },
      async executeWrite(command: ArmedWrite, gate: any) {
        seen.push(command.step);
        await gate.dispatch(actual(command), async () => {});
        const resultFact = { factType: command.step === 'contact-submit' ? 'LEAD_CONTACT_RESULT' : 'R1_FIXTURE', factRef: randomUUID(), revision: 0 };
        const response = { status: command.method === 'PUT' || command.step === 'contact-submit' ? 200 : 201,
          headers: { location: `/api/v1/commands/${command.commandId}/receipt`, 'cache-control': 'no-store', etag: `"identity.${'A'.repeat(43)}"` },
          body: { commandId: command.commandId, receiptId: randomUUID(), outcome: 'SUCCEEDED', resultFact } };
        receipts.set(command.commandId, response.body);
        return { response, resourceId: command.step === 'capture-R1_AUTO' ? randomUUID() : command.step === 'contact-draft' ? randomUUID() : command.step === 'contact-submit' ? resultFact.factRef : randomUUID() };
      },
      async verifyIdentities(resources: Record<string,string>) { checkpoints.push({ name: 'identities', count: Object.keys(resources).length }); },
      async locateContactTask(resources: Record<string,string>) { checkpoints.push({ name: 'contact', count: Object.keys(resources).length }); return randomUUID(); },
      async reloadDraft(resources: Record<string,string>) { checkpoints.push({ name: 'reload', count: Object.keys(resources).length }); },
      async retrieveReceipt(commandId: string) { return receipts.get(commandId); },
      async closeCompletion(commandId: string, resources: Record<string,string>) { checkpoints.push({ name: 'closure', count: Object.keys(resources).length }); return { commandId, counts: { contactResult: 1, opportunity: 1, event: 2, outbox: 2, receipt: 1, audit: 1 } }; },
    };
    const orchestrator = new R1GoldenOrchestrator(journal, adapter);
    const report = await orchestrator.run();
    expect(seen).toEqual(WRITE_SEQUENCE.map(entry => entry.step));
    expect(checkpoints.map(entry => entry.name)).toEqual(['identities','contact','reload','closure']);
    expect(journal.hasStage('MANAGEMENT_COMPLETED')).toBe(true);
    expect(journal.hasStage('IDENTITIES_VERIFIED')).toBe(true);
    expect(journal.hasStage('CAPTURE_COMPLETED')).toBe(true);
    expect(journal.hasStage('DRAFT_RELOADED')).toBe(true);
    expect(journal.hasStage('GOLDEN_COMPLETED')).toBe(true);
    expect(report).toMatchObject({ managementCommandCount: 15, counts: { contactResult: 1, opportunity: 1, event: 2, outbox: 2, receipt: 1, audit: 1 } });
  } finally { rmSync(folder, { recursive: true, force: true }); }
});


function identity() {
  return { run: RUN, operationId: OPERATION, environmentDigest: 'a'.repeat(64), sourceCommit: 'b'.repeat(40) };
}

function write(step: string, path: string, body: Record<string, unknown>) {
  return {
    step, commandId: randomUUID(), method: 'POST', path,
    bodySha256: canonicalSha256(body), actorScopeKey: ACTOR,
    actorAppointmentId: APPOINTMENT, onBehalfAppointmentId: null,
  };
}

function armed(step: string, path: string, body: Record<string, unknown>): ArmedWrite {
  const value = write(step, path, body);
  return { ...value, body };
}

function actual(command: ArmedWrite) {
  return {
    commandId: command.commandId, method: command.method, path: command.path,
    bodyBytes: Buffer.from(JSON.stringify(command.body)), actorScopeKey: command.actorScopeKey,
    actorAppointmentId: command.actorAppointmentId, onBehalfAppointmentId: null,
  };
}

function environment() {
  const value: any = {
    profile: 'R1_ISOLATED_ACCEPTANCE_INPUT_V1', run: RUN, operationId: OPERATION,
    origin: 'https://localhost:29444', apiOrigin: 'https://localhost:29445', issuer: 'https://localhost:29443/realms/r1-e2e',
    sourceCommit: 'a'.repeat(40), bootstrapSource: 'original',
    bootstrap: { tenantId: '00000000-0000-4000-8000-000000000001', rootId: '00000000-0000-4000-8000-000000000002', founderId: '00000000-0000-4000-8000-000000000003', founderAppointmentId: '00000000-0000-4000-8000-000000000004', originalGrantIds: [1,2,3,4].map(index => `00000000-0000-4000-8010-${String(index).padStart(12, '0')}`) },
    usernames: { founder: `r1-${RUN}-founder`, sales: `r1-${RUN}-sales`, supervisor: `r1-${RUN}-supervisor`, sourceOwner: `r1-${RUN}-source-owner`, revokedAppointment: `r1-${RUN}-revoked` },
    credentialReferences: { founder: 'secrets/founder-password.txt', sales: 'secrets/sales-password.txt', supervisor: 'secrets/supervisor-password.txt', sourceOwner: 'secrets/sourceOwner-password.txt' },
    artifacts: { jarSha256: '1'.repeat(64), spaSha256: '2'.repeat(64), schemaSha256: '3'.repeat(64) },
    configurationSha256: '4'.repeat(64), sourceSha256: '5'.repeat(64),
    processes: Object.fromEntries(['api','worker','spa'].map((name, index) => [name, { pid: index + 1, created: '2026-09-13T00:00:01Z', identitySha256: String(index + 6).repeat(64) }])),
  };
  value.environmentDigest = canonicalSha256(value);
  return value;
}
