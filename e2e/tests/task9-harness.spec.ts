import { test, expect } from '@playwright/test';
import { requireLocalAcceptance, validateEnvironment, validateAccounts, LOCAL_RUNTIME_BRIDGE } from '../fixtures/local-environment';
import { safeFailureCode } from '../reporters/safe-reporter';
import { OperationJournal, type PhaseEvidence } from '../fixtures/operation-journal';
import { existsSync, mkdtempSync, readFileSync, writeFileSync, symlinkSync } from 'node:fs';
import { noLinks, sha } from '../fixtures/local-environment';
import { IdentitySetup, dispatchObserved, matchFact, requireReceiptLocationBuild, requireUnmappedSelfStatus } from '../fixtures/identity-setup';
import SafeReporter from '../reporters/safe-reporter';
import { createIdentityApi } from '../../apps/workbench/src/features/identity/identityApi';
import { RecoveryStore } from '../../apps/workbench/src/features/session/recoveryMarker';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { boundaryProbe, journalBindingProbe } from './boundary-probe';
import { allowReadOnlyRequest, COMPLETE_JOURNAL_SHA, readOnlyOutcome, type ReadOnlyEvidence } from '../fixtures/readonly-session';

for (const mismatch of ['environment', 'run', 'build'] as const) test(`offline readonly journal binding rejects wrong ${mismatch} despite matching byte hash`, () => {
  const current = { buildSha: '6c6c6d90105b6d647fd213afed0c30dd9ff3a594', environmentDigest: 'b'.repeat(64) };
  const identity = { runId: '74a496f6-494e-417d-9abd-69a85c94f165', ...current };
  const valid = Buffer.from(JSON.stringify({ identity, commands: [], stages: [] }));
  expect(journalBindingProbe(sha(valid))(valid, current)).toBe(sha(valid));
  const changed = mismatch === 'run' ? { ...identity, runId: '00000000-0000-4000-8000-000000000093' }
    : mismatch === 'build' ? { ...identity, buildSha: 'c'.repeat(40) } : identity;
  const bytes = Buffer.from(JSON.stringify({ identity: changed, commands: [], stages: [] }));
  const target = mismatch === 'environment' ? { ...current, environmentDigest: 'd'.repeat(64) } : current;
  expect(() => journalBindingProbe(sha(bytes))(bytes, target)).toThrow();
});

test('offline readonly entry allows only business reads and exact normal IdP authentication', () => {
  for (const method of ['POST', 'PUT', 'PATCH', 'DELETE']) expect(allowReadOnlyRequest(new URL('https://localhost:19444/api/v1/admin/identity/organizations'), method)).toBe(false);
  expect(allowReadOnlyRequest(new URL('https://localhost:19444/api/v1/admin/identity/principals'), 'GET')).toBe(true);
  for (const path of ['/realms/local-r1/protocol/openid-connect/token', '/realms/local-r1/login-actions/authenticate']) expect(allowReadOnlyRequest(new URL('https://localhost:19443' + path), 'POST')).toBe(true);
  expect(allowReadOnlyRequest(new URL('https://localhost:19443/admin/realms/local-r1/users'), 'POST')).toBe(false);
  expect(allowReadOnlyRequest(new URL('https://example.invalid/'), 'GET')).toBe(false);
});

test('offline readonly evidence never promotes refresh HTTP alone or masks boundary failure', () => {
  const evidence: ReadOnlyEvidence = { refreshObserved: true, refreshStatus: 200, refreshDuringBoundary: true, organizationStatus: 200, principalStatus: 200,
    adminVisible: true, loginVisible: false, sessionNoticeVisible: false, blockedBusinessWrites: 0, blockedRequests: 0, boundaryCompleted: true,
    journalBefore: COMPLETE_JOURNAL_SHA, journalAfter: COMPLETE_JOURNAL_SHA, environmentUnchanged: true, failureStep: null };
  expect(readOnlyOutcome(evidence)).toBe('PASSED_READ_ONLY_SUBSCENARIO');
  expect(readOnlyOutcome({ ...evidence, refreshObserved: false })).toBe('NOT_TRIGGERED');
  for (const patch of [{ organizationStatus: 401 }, { principalStatus: null }, { adminVisible: false }, { refreshDuringBoundary: false }, { boundaryCompleted: false },
    { journalAfter: '0'.repeat(64) }, { environmentUnchanged: false }, { blockedBusinessWrites: 1 }, { loginVisible: true }, { sessionNoticeVisible: true },
    { refreshObserved: false, failureStep: 'BOUNDARY' }, { refreshObserved: false, failureStep: 'APPROVAL' }]) expect(readOnlyOutcome({ ...evidence, ...patch })).toBe('FAILED');
});

for (const mismatch of ['blocked', 'journal'] as const) test(`offline readonly no refresh with known ${mismatch} failure is FAILED`, () => {
  const evidence: ReadOnlyEvidence = { refreshObserved: false, refreshStatus: null, refreshDuringBoundary: false, organizationStatus: 200, principalStatus: 200,
    adminVisible: true, loginVisible: false, sessionNoticeVisible: false, blockedBusinessWrites: 0, blockedRequests: 0, boundaryCompleted: false,
    journalBefore: COMPLETE_JOURNAL_SHA, journalAfter: COMPLETE_JOURNAL_SHA, environmentUnchanged: true, failureStep: null };
  expect(readOnlyOutcome(evidence)).toBe('NOT_TRIGGERED');
  expect(readOnlyOutcome({ ...evidence, ...(mismatch === 'blocked' ? { blockedRequests: 1 } : { journalAfter: '0'.repeat(64) }) })).toBe('FAILED');
});

test('offline delayed Python boundary keeps the route event loop runnable', async () => {
  const invoke = boundaryProbe('import time,json\ntime.sleep(0.3)\nprint(json.dumps({"synthetic":True}))');
  let tick = false;
  const timer = setTimeout(() => { tick = true; }, 30);
  try {
    const result = await invoke('snapshot');
    expect(result).toEqual({ synthetic: true });
    expect(tick, 'route/timer work must run before the delayed child completes').toBe(true);
  } finally { clearTimeout(timer); }
});

test('offline async protection must finish before durable dispatch authorization', async () => {
  const path = join(mkdtempSync(join(tmpdir(), 'task9-synthetic-')), 'journal.json');
  let release!: () => void, gated = false, sent = 0;
  const gate = new Promise<void>(resolve => { release = resolve; });
  const journal = await OperationJournal.open(path, identity, async () => { if (gated) await gate; });
  gated = true;
  const pending = dispatchObserved(journal, command, async () => { sent++; });
  try { await new Promise(resolve => setTimeout(resolve, 20)); expect(sent).toBe(0); }
  finally { release(); await pending; }
  expect(sent).toBe(1);
  expect(JSON.parse(readFileSync(path, 'utf8')).commands[0].status).toBe('PENDING');
});

test('offline armed business route is claimed before header await and duplicates poison dispatch', async () => {
  let handler!: (route: any) => Promise<void>, release!: () => void, sent = 0, aborted = 0;
  const gate = new Promise<void>(resolve => { release = resolve; });
  const context = { newPage: async () => ({ goto: async () => { throw new Error('synthetic-registration-stop'); } }), route: async (_: string, callback: typeof handler) => { handler = callback; } };
  const journal = await OperationJournal.open(join(mkdtempSync(join(tmpdir(), 'task9-route-synthetic-')), 'journal.json'), identity, () => {});
  const setup: any = Object.assign(Object.create(IdentitySetup.prototype), {
    environment: { assertUnchanged: async () => {}, accounts: { founder: {} }, bootstrap: { appointmentId: '00000000-0000-4000-8000-000000000001' } },
    browser: { newContext: async () => context }, sessions: [], journal, dispatchFailed: false,
  });
  await expect(setup.login('founder')).rejects.toThrow('synthetic-registration-stop');
  setup.admin = setup.sessions[0];
  setup.admin.self = { canEnterIdentityAdmin: true, selectedAppointmentId: setup.environment.bootstrap.appointmentId, selectedOnBehalfAppointmentId: null, actorScopeKey: command.actorScopeKey };
  setup.armed = { step: command.step, path: command.path, body: { displayName: 'synthetic' } };
  const route = () => ({ request: () => ({ url: () => 'https://localhost:19444' + command.path, method: () => 'POST',
    allHeaders: async () => { await gate; return { 'x-appointment-id': setup.environment.bootstrap.appointmentId, 'idempotency-key': command.commandId }; }, postDataBuffer: () => Buffer.from('{"displayName":"synthetic"}') }),
    continue: async () => { sent++; }, abort: async () => { aborted++; } });
  const first = handler(route());
  let second: Promise<void> | undefined;
  try {
    expect(setup.armed).toBeUndefined();
    second = handler(route()); await second;
  } finally { release(); await first; await second; }
  expect(sent).toBe(0); expect(aborted).toBe(2); expect(setup.dispatchFailed).toBe(true);
});

test('offline boundary timeout output overflow and invalid output never expose child details', async () => {
  for (const [index, program] of [
    'import time;time.sleep(2);print("{}")',
    'import sys;sys.stdout.write("sensitive-output"*1000)',
    'import sys;sys.stderr.write("sensitive-error"*1000)',
    'raise Exception("sensitive-exception")',
    'print("sensitive-invalid-json")',
  ].entries()) {
    const invoke = boundaryProbe(program, index === 0 ? 100 : 2_000, 128);
    let failure: any;
    try { await invoke('snapshot'); } catch (error) { failure = error; }
    expect(failure?.message).toBe('T9_BOUNDARY');
  }
});

test('offline duplicate route during journal protection preserves original pending without sending', async () => {
  let handler!: (route: any) => Promise<void>, release!: () => void, entered!: () => void, gated = false, sent = 0, aborted = 0;
  const gate = new Promise<void>(resolve => { release = resolve; }), protecting = new Promise<void>(resolve => { entered = resolve; });
  const path = join(mkdtempSync(join(tmpdir(), 'task9-route-pending-synthetic-')), 'journal.json');
  const journal = await OperationJournal.open(path, identity, async () => { if (gated) { entered(); await gate; } });
  const context = { newPage: async () => ({ goto: async () => { throw new Error('synthetic-registration-stop'); } }), route: async (_: string, callback: typeof handler) => { handler = callback; } };
  const setup: any = Object.assign(Object.create(IdentitySetup.prototype), {
    environment: { assertUnchanged: async () => {}, accounts: { founder: {} }, bootstrap: { appointmentId: '00000000-0000-4000-8000-000000000001' } },
    browser: { newContext: async () => context }, sessions: [], journal, dispatchFailed: false,
  });
  await expect(setup.login('founder')).rejects.toThrow('synthetic-registration-stop');
  setup.admin = setup.sessions[0];
  setup.admin.self = { canEnterIdentityAdmin: true, selectedAppointmentId: setup.environment.bootstrap.appointmentId, selectedOnBehalfAppointmentId: null, actorScopeKey: command.actorScopeKey };
  setup.armed = { step: command.step, path: command.path, body: { displayName: 'synthetic' } };
  const route = () => ({ request: () => ({ url: () => 'https://localhost:19444' + command.path, method: () => 'POST',
    allHeaders: async () => ({ 'x-appointment-id': setup.environment.bootstrap.appointmentId, 'idempotency-key': command.commandId }), postDataBuffer: () => Buffer.from('{"displayName":"synthetic"}') }),
    continue: async () => { sent++; }, abort: async () => { aborted++; } });
  gated = true; const first = handler(route());
  try { await protecting; await handler(route()); } finally { release(); await first; }
  expect(sent).toBe(0); expect(aborted).toBe(2); expect(setup.dispatchFailed).toBe(true);
  expect(JSON.parse(readFileSync(path, 'utf8')).commands[0]).toMatchObject({ commandId: command.commandId, status: 'PENDING' });
  await expect(journal.begin({ ...command, commandId: '00000000-0000-4000-8000-000000000093' })).rejects.toThrow();
});

test('offline competing journal mutation cannot overwrite in-flight original state', async () => {
  const path = join(mkdtempSync(join(tmpdir(), 'task9-race-synthetic-')), 'journal.json');
  let release!: () => void, gated = false;
  const gate = new Promise<void>(resolve => { release = resolve; });
  const journal = await OperationJournal.open(path, identity, async () => { if (gated) await gate; });
  gated = true;
  const original = { ...command }, first = journal.begin(original);
  original.bodySha256 = 'f'.repeat(64);
  try {
    await expect(journal.begin({ ...command, step: 'principal-supervisor', commandId: '00000000-0000-4000-8000-000000000093' })).rejects.toThrow();
    await expect(journal.finishStage('T9-L01-entry', phaseEvidence(join(path, '..')))).rejects.toThrow();
    expect(() => journal.requirePrevious('T9-L01-entry')).toThrow();
  } finally { release(); await first; }
  const saved = JSON.parse(readFileSync(path, 'utf8'));
  expect(saved.commands).toHaveLength(1); expect(saved.commands[0]).toMatchObject(command);
});

test('offline async late protection failure preserves pending intent and blocks reopened dispatch', async () => {
  for (const failAt of [2, 3]) {
    const path = join(mkdtempSync(join(tmpdir(), 'task9-protect-synthetic-')), 'journal.json');
    let armed = false, calls = 0, sent = 0;
    const journal = await OperationJournal.open(path, identity, async () => {
      if (armed && ++calls === failAt) { await new Promise(resolve => setTimeout(resolve, 5)); throw new Error('synthetic-protection'); }
    });
    armed = true;
    await expect(dispatchObserved(journal, command, async () => { sent++; })).rejects.toThrow();
    expect(sent).toBe(0); expect(existsSync(path + '.pending')).toBe(true);
    expect(JSON.parse(readFileSync(path + '.pending', 'utf8')).commands[0]).toMatchObject(command);
    await expect(OperationJournal.open(path, identity, () => {})).rejects.toThrow();
    await expect(journal.begin({ ...command, commandId: '00000000-0000-4000-8000-000000000093' })).rejects.toThrow();
  }
});

test('offline stale journal instance cannot publish over a completed competing phase', async () => {
  const folder = mkdtempSync(join(tmpdir(), 'task9-stale-synthetic-')), path = join(folder, 'journal.json');
  let release!: () => void, gated = false;
  const gate = new Promise<void>(resolve => { release = resolve; });
  const first = await OperationJournal.open(path, identity, async () => { if (gated) await gate; });
  const other = await OperationJournal.open(path, identity, () => {});
  gated = true;
  const write = first.begin(command), rejected = expect(write).rejects.toThrow();
  await other.finishStage('T9-L01-entry', phaseEvidence(folder));
  release(); await rejected;
  expect(JSON.parse(readFileSync(path, 'utf8')).stages).toHaveLength(1);
  expect(JSON.parse(readFileSync(path, 'utf8')).commands).toHaveLength(0);
  expect(existsSync(path + '.pending')).toBe(true);
  await expect(OperationJournal.open(path, identity, () => {})).rejects.toThrow();
});

test('offline explicit-local gate', async () => {
  expect(() => requireLocalAcceptance(undefined)).toThrow();
  expect(() => requireLocalAcceptance('APPROVED_SYNTHETIC_ONLY')).not.toThrow();
  expect(() => requireLocalAcceptance('production')).toThrow();
  expect(safeFailureCode('password=do-not-log')).not.toContain('do-not-log');
});

test('offline unmapped SELF contract accepts only the deployed 401 denial', async () => {
  expect(() => requireUnmappedSelfStatus(401)).not.toThrow();
  for (const status of [200, 400, 403, 500])
    expect(() => requireUnmappedSelfStatus(status)).toThrow();
});

const environment = {
  origin: 'https://localhost:19444', issuer: 'https://localhost:19443/realms/local-r1',
  buildSha: '6c6c6d90105b6d647fd213afed0c30dd9ff3a594',
  releaseId: '2db735dbaddc435fb585483f5393f40e',
  jarSha256: 'b51bd7dba4648000d1a09ea6ffef4ba3bb4eab17bc922a05935ef5e8619623f7',
  manifestHash: 'c5f374ee58e4c21b8c2b726cc5e1fe8e35f7fbe2128a2d5900bb8d910e950844',
  revision: 11, browserVersion: '153.0.8010.12', browserRevision: '1243',
};
test('offline rejects wrong origin issuer artifact or browser', async () => {
  expect(() => validateEnvironment(environment)).not.toThrow();
  for (const key of Object.keys(environment)) {
    expect(() => validateEnvironment({ ...environment, [key]: 'wrong-synthetic-value' })).toThrow();
  }
});

const aliases = ['intake', 'supervisor', 'contact', 'delegate'];
function accounts() {
  const original = { founder: { username: 'synthetic-founder', password: 'synthetic-only' }, unmapped: { username: 'synthetic-unmapped', password: 'synthetic-only' } };
  const entries = Object.fromEntries(aliases.map((alias, i) => [alias, { username: `task9-local-${alias}`, password: 'synthetic-only', email: `${alias}@example.invalid`, firstName: 'synthetic', lastName: alias, providerUserId: `00000000-0000-4000-8000-00000000000${i}` }]));
  const safe = Object.fromEntries(Object.entries(entries).map(([alias, { password: _, ...entry }]) => [alias, entry]));
  const operation = { stage: 'COMPLETE', runId: 'synthetic-provisioning-run-000001', accounts: safe,
    temporaryClientId: 'synthetic-only', beforeOriginalUsers: { founder: {}, unmapped: {} }, beforeDirectoryRoles: [], beforeJwksHash: 'a'.repeat(64), recoveryContainer: 'synthetic-only', temporaryClientUuid: '00000000-0000-4000-8000-000000000097', temporaryServiceUserUuid: '00000000-0000-4000-8000-000000000098', temporaryCredentialSha256: 'b'.repeat(64),
    temporaryClientDeleted: true, temporaryCredentialRejected: true, temporaryTokenRejected: true, originalUsersUnchanged: true, realmPublicKeysUnchanged: true, directoryReadOnlyUnchanged: true, temporaryClientUserAndRolesAbsent: true, temporaryRecoveryContainerRemoved: true };
  return { original, credentials: { runId: operation.runId, accounts: entries }, operation };
}
test('offline rejects unfinished operation and replaced provider identity', async () => {
  const f = accounts();
  expect(() => validateAccounts(f.original, f.credentials, f.operation)).not.toThrow();
  f.credentials.accounts.intake.providerUserId = '00000000-0000-4000-8000-000000000096';
  expect(() => validateAccounts(f.original, f.credentials, f.operation)).toThrow();
  const g = accounts(); g.operation.stage = 'RECOVERY';
  expect(() => validateAccounts(g.original, g.credentials, g.operation)).toThrow();
});

const identity = { runId: '00000000-0000-4000-8000-000000000091', environmentDigest: 'a'.repeat(64), buildSha: environment.buildSha };
const command = { step: 'principal-intake', commandId: '00000000-0000-4000-8000-000000000092', method: 'POST', path: '/api/v1/admin/identity/principals', bodySha256: 'b'.repeat(64), actorScopeKey: 'ask1.' + 'a'.repeat(43) };
test('offline pending write blocks another key and survives reopening', async () => {
  const path = join(mkdtempSync(join(tmpdir(), 'task9-synthetic-')), 'journal.json');
  const journal = await OperationJournal.open(path, identity, () => {});
  await journal.begin(command);
  await expect(journal.begin({ ...command, commandId: '00000000-0000-4000-8000-000000000093' })).rejects.toThrow();
  await expect(OperationJournal.open(path, { ...identity, runId: '00000000-0000-4000-8000-000000000094' }, () => {})).rejects.toThrow();
  const reopened = await OperationJournal.open(path, identity, () => {});
  await expect(reopened.begin(command)).rejects.toThrow();
  expect(readFileSync(path, 'utf8')).not.toContain('providerUserSelector');
});
test('offline protection or persistence failure prevents dispatch authorization', async () => {
  const path = join(mkdtempSync(join(tmpdir(), 'task9-synthetic-')), 'journal.json');
  await expect(OperationJournal.open(path, identity, () => { throw new Error('synthetic-boundary-denied'); })).rejects.toThrow();
  const journal = await OperationJournal.open(path, identity, () => {});
  writeFileSync(path, '{}');
  await expect(journal.begin(command)).rejects.toThrow();
});
test('offline journal rejects secrets and uncontrolled mutation paths', async () => {
  for (const bad of [{ ...command, password: 'never-store' }, { ...command, path: '/api/v1/tasks' }, { ...command, actorScopeKey: 'password=never-store' }]) {
    const path = join(mkdtempSync(join(tmpdir(), 'task9-synthetic-')), 'journal.json');
    const journal = await OperationJournal.open(path, identity, () => {});
    await expect(journal.begin(bad)).rejects.toThrow();
  }
});

test('offline confirms only matching opaque receipt and exact resource reference', async () => {
  const path = join(mkdtempSync(join(tmpdir(), 'task9-synthetic-')), 'journal.json');
  const journal = await OperationJournal.open(path, identity, () => {}); await journal.begin(command);
  const receipt = { commandId: command.commandId, receiptId: '00000000-0000-4000-8000-000000000095', completedAt: '2026-09-10T01:00:00Z', outcome: 'SUCCEEDED', resultFact: { factType: 'IDENTITY_PRINCIPAL', factRef: 'a'.repeat(43), revision: 0 } };
  const resource = command.path + '/00000000-0000-4000-8000-000000000090';
  await expect(journal.complete(command.commandId, 201, { ...receipt, commandId: '00000000-0000-4000-8000-000000000096' }, resource)).rejects.toThrow();
  expect(journal.pending()?.commandId).toBe(command.commandId);
  const reconciled = await OperationJournal.open(path, identity, () => {});
  await expect(reconciled.complete(command.commandId, 201, receipt, resource)).resolves.toBeDefined();
  expect(reconciled.confirmed('principal-intake')?.resultFact?.factRef).toBe('a'.repeat(43));
});

test('offline linked directory is rejected without reading its files', async () => {
  const base = mkdtempSync(join(tmpdir(), 'task9-synthetic-'));
  const link = base + '-junction'; symlinkSync(base, link, 'junction');
  expect(() => noLinks(link)).toThrow();
});

test('offline dispatch observes a durable original key and stops on failed journaling', async () => {
  const path = join(mkdtempSync(join(tmpdir(), 'task9-synthetic-')), 'journal.json');
  const journal = await OperationJournal.open(path, identity, async () => {});
  let sent = 0;
  await dispatchObserved(journal, command, async () => { expect(JSON.parse(readFileSync(path, 'utf8')).commands[0]?.commandId).toBe(command.commandId); sent++; });
  expect(sent).toBe(1);
  await expect(dispatchObserved(journal, { ...command, commandId: '00000000-0000-4000-8000-000000000090' }, async () => { sent++; })).rejects.toThrow();
  expect(sent).toBe(1);
});

test('offline frozen ReceiptLocation rejects legacy resource Location without losing original recovery key', async () => {
  for (const correct of [false, true]) {
    const values = new Map<string, string>();
    const storage: Storage = { get length() { return values.size; }, key: i => [...values.keys()][i] ?? null, getItem: key => values.get(key) ?? null, setItem: (key, value) => { values.set(key, value); }, removeItem: key => { values.delete(key); }, clear: () => values.clear() };
    const recovery = new RecoveryStore(storage);
    const response = { commandId: command.commandId, receiptId: '00000000-0000-4000-8000-000000000095', completedAt: '2026-09-10T01:00:00Z', outcome: 'SUCCEEDED', resultFact: { factType: 'IDENTITY_PRINCIPAL', factRef: 'a'.repeat(43), revision: 0 } };
    const api = createIdentityApi(recovery, async () => new Response(JSON.stringify(response), { status: 201, headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ETag: '"identity.' + 'a'.repeat(43) + '"', Location: correct ? `/api/v1/commands/${command.commandId}/receipt` : '/api/v1/admin/identity/principals/00000000-0000-4000-8000-000000000094' } }), 'https://localhost:19444');
    const session = { identityEpoch: 1, actorScopeKey: command.actorScopeKey, selectedAppointmentId: '00000000-0000-4000-8000-000000000093', selectedOnBehalfAppointmentId: null, getValidAccessToken: async () => 'synthetic-only', isCurrent: () => true, invalidate() {} };
    const request = api.write(session, { commandType: 'CREATE_IDENTITY_PRINCIPAL', key: command.commandId, body: { providerUserSelector: 'synthetic_selector', displayName: '本地合成受理' } }, new AbortController().signal);
    if (correct) { await expect(request).resolves.toMatchObject({ status: 201 }); expect(recovery.read()).toBeNull(); }
    else { await expect(request).rejects.toThrow(); expect(recovery.read()).toMatchObject({ commandId: command.commandId, actorScopeKey: command.actorScopeKey, commandType: 'CREATE_IDENTITY_PRINCIPAL' }); expect(Object.keys(recovery.read()!).sort()).toEqual(['actorScopeKey', 'commandId', 'commandType', 'recordedAt']); }
  }
});

test('offline receipt matches exact actor-scoped fact ID, never a display name', async () => {
  const actor = { appointmentId: '00000000-0000-4000-8000-000000000001', founderId: '00000000-0000-4000-8000-000000000002', tenantId: '00000000-0000-4000-8000-000000000003', rootId: '00000000-0000-4000-8000-000000000005' };
  const fact = { factType: 'IDENTITY_PRINCIPAL', factRef: '41V4O1g4d31tHMzv5eqlLVXqb-rOxOsQ7kiHMoiiT6k' };
  const wrong = { id: '00000000-0000-4000-8000-000000000006', displayName: '本地合成受理' };
  const right = { id: '00000000-0000-4000-8000-000000000004', displayName: '本地合成受理' };
  expect(matchFact(actor, fact, [wrong, right])).toBe(right.id);
  expect(() => matchFact(actor, fact, [wrong])).toThrow();
  expect(() => matchFact({ ...actor, appointmentId: wrong.id }, fact, [right])).toThrow();
});

test('offline reporter discards raw credentials and preserves failed exit', async () => {
  const output: string[] = [], write = process.stdout.write;
  process.stdout.write = ((chunk: any) => { output.push(String(chunk)); return true; }) as typeof write;
  try {
    const reporter = new SafeReporter();
    reporter.onStdOut(); reporter.onStdErr(); reporter.onError();
    reporter.onTestEnd({ title: 'password=synthetic-leak' } as any, { status: 'failed', errors: [{ message: 'token=synthetic-leak' }] } as any);
    reporter.onEnd({ status: 'failed' } as any);
  } finally { process.stdout.write = write; }
  expect(output.join('')).not.toContain('synthetic-leak');
  expect(output.join('')).toContain('status=failed exit=1');
});

test('offline known Location drift binary cannot authorize a real write', async () => {
  expect(() => requireReceiptLocationBuild('04bd695f7a8f656a5ed8fb96c5168e44a91bab8d')).toThrow();
  expect(() => requireReceiptLocationBuild('synthetic-invalid')).toThrow();
  expect(() => requireReceiptLocationBuild(environment.buildSha)).not.toThrow();
});

test('offline actual Python bridge rejects each controlled historical process before evidence in load and snapshot', async () => {
  const synthetic = mkdtempSync(join(tmpdir(), 'task9-process-synthetic-'));
  const probe = String.raw`
import sys,types,copy
from pathlib import Path
sys.path.insert(0,str(Path(sys.argv[1])/'deploy/local-login'))
import local_release as release_module
from local_worker import worker_command
runner=types.ModuleType('local_login')
runner.ROOT=Path(sys.argv[3]);runner.RUNTIME=runner.ROOT/'synthetic-runtime';runner.TOOLS=runner.ROOT/'tools';runner.JAVA=runner.TOOLS/'java.exe'
sys.modules['local_login']=runner
package=runner.RUNTIME/'releases'/'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
historical=runner.RUNTIME/'releases'/'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
commands={**release_module.app_commands(runner,package),'worker':worker_command(runner,package)}
old={**release_module.app_commands(runner,historical),'worker':worker_command(runner,historical)}
saved={name:{'pid':i+1,'executable':command[0],'args':command[1:],'created':'synthetic-start-'+name} for i,(name,command) in enumerate(commands.items())}
changed=sys.argv[4]
if changed in old:
    saved[changed]['executable']=old[changed][0];saved[changed]['args']=old[changed][1:]
actual=copy.deepcopy(saved)
if changed=='api-created':actual['api']['created']='different-synthetic-start'
if changed=='api-pid':actual['api']['pid']=99
if changed=='spa-actual':actual['spa']['args']=old['spa'][1:]
class Boundary:
    def __init__(self,runner):pass
    def protect(self):pass
    def processes(self):return list(actual.values())
    def process(self,pid):return next(p for p in actual.values() if p['pid']==pid)
class Release:
    def __init__(self,*args):pass
    def current(self):return {'id':'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'}
    def load(self,id):return {}
    def package(self,id):return package
class EvidenceBoundary(Exception):pass
def read(path):
    if path.name=='processes.json':return saved
    raise EvidenceBoundary()
release_module.RuntimeBoundary=Boundary;release_module.LocalRelease=Release;release_module.read_json=read;release_module.regular=lambda p:p
try:
    exec(compile(sys.stdin.read(),'<actual-task9-bridge>','exec'))
except EvidenceBoundary:
    print('EVIDENCE_REACHED')
except Exception:
    print('REJECTED_BEFORE_EVIDENCE');sys.exit(2)
`;
  for (const mode of ['load', 'snapshot']) for (const changed of ['none', 'api', 'spa', 'worker', 'api-created', 'api-pid', 'spa-actual']) {
    const result = spawnSync('D:/soft/python3/python.exe', ['-B', '-c', probe, resolve(__dirname, '../..'), mode, synthetic, changed], { input: LOCAL_RUNTIME_BRIDGE, encoding: 'utf8', windowsHide: true });
    expect(result.status).toBe(changed === 'none' ? 0 : 2);
    expect(result.stdout.trim()).toBe(changed === 'none' ? 'EVIDENCE_REACHED' : 'REJECTED_BEFORE_EVIDENCE');
  }
});

function phaseEvidence(folder: string): PhaseEvidence {
  return { ...identity, apiIdentity: 'c'.repeat(64), executedAt: '2026-09-10T01:00:00.000Z', caseIdentity: 'T9-L01-entry', status: 'ACTIONS_VERIFIED', exitCode: null,
    reportPath: join(folder, `task9-${identity.runId}-T9-L01-entry-00000000-0000-4000-8000-000000000088.json`), http: [{ path: '/api/v1/session/context', status: 200 }], U01: 'NOT_EXECUTED', U02: 'NOT_EXECUTED', U03: 'NOT_EXECUTED' };
}
test('offline completion write and final protection failures cannot authorize successor after reopening', async () => {
  for (const failure of ['before-report', 'after-report-write', 'after-report-protect']) {
    const folder = mkdtempSync(join(tmpdir(), 'task9-phase-synthetic-')), path = join(folder, 'journal.json'), evidence = phaseEvidence(folder);
    let protectedFailure = false;
    const journal = await OperationJournal.open(path, identity, async () => { if (protectedFailure) throw new Error('synthetic-protection-denied'); });
    await expect(journal.finishStage('T9-L01-entry', evidence, { writeEvidence(file, bytes) {
      if (failure === 'before-report') throw new Error('synthetic-write-denied');
      writeFileSync(file, bytes, { flag: 'wx' });
      if (failure === 'after-report-write') throw new Error('synthetic-flush-unknown');
      protectedFailure = true;
    } })).rejects.toThrow();
    expect(existsSync(path + '.completion.pending')).toBe(true);
    await expect(journal.begin(command)).rejects.toThrow();
    let sent = false;
    await expect((async () => {
      const continued = await OperationJournal.open(path, identity, () => {});
      continued.requirePrevious('T9-L03-unmapped');
      await dispatchObserved(continued, command, async () => { sent = true; });
    })()).rejects.toThrow();
    expect(sent).toBe(false);
    if (existsSync(evidence.reportPath)) expect(JSON.parse(readFileSync(evidence.reportPath, 'utf8')).status).toBe('ACTIONS_VERIFIED');
  }
});

test('offline completed phase requires intact prepared evidence on continuation', async () => {
  const folder = mkdtempSync(join(tmpdir(), 'task9-phase-synthetic-')), path = join(folder, 'journal.json'), evidence = phaseEvidence(folder);
  const journal = await OperationJournal.open(path, identity, () => {});
  await journal.finishStage('T9-L01-entry', evidence);
  expect(existsSync(path + '.completion.pending')).toBe(false);
  expect(JSON.parse(readFileSync(evidence.reportPath, 'utf8')).status).toBe('ACTIONS_VERIFIED');
  expect(JSON.parse(readFileSync(path, 'utf8')).stages[0]).toMatchObject({ caseIdentity: 'T9-L01-entry', status: 'PASSED_SUBSCENARIO', exitCode: 0, reportPath: evidence.reportPath });
  const continued = await OperationJournal.open(path, identity, () => {});
  expect(() => continued.requirePrevious('T9-L03-unmapped')).not.toThrow();
  writeFileSync(evidence.reportPath, '{}');
  expect(() => continued.requirePrevious('T9-L03-unmapped')).toThrow();
  await expect(continued.begin(command)).rejects.toThrow();
  await expect(OperationJournal.open(path, identity, () => {})).rejects.toThrow();
});

test('offline actual Python bridge binds only coherent replacement manifest before credentials', async () => {
  const synthetic = mkdtempSync(join(tmpdir(), 'task9-release-synthetic-'));
  const probe = String.raw`
import sys,types,copy,io,contextlib
from pathlib import Path
sys.path.insert(0,str(Path(sys.argv[1])/'deploy/local-login'))
import local_release as release_module
from local_worker import worker_command
runner=types.ModuleType('local_login')
runner.ROOT=Path(sys.argv[3])/(sys.argv[2]+'-'+sys.argv[4]);runner.RUNTIME=runner.ROOT/'synthetic-runtime'
runner.TOOLS=runner.ROOT/'tools';runner.JAVA=runner.TOOLS/'java.exe'
runner.ORIGIN='https://localhost:19444';runner.ISSUER='https://localhost:19443/realms/local-r1'
sys.modules['local_login']=runner
package=runner.RUNTIME/'releases'/'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';package.mkdir(parents=True)
jar=b'synthetic-jar-only';jar_digest=release_module.digest(jar)
provenance={'sourceCommit':'c'*40,'jarSha256':jar_digest}
manifest=copy.deepcopy(provenance);kind='controlled-local-release';case=sys.argv[4]
if case=='unknown-kind':kind='unknown-release'
if case=='source-kind':kind='controlled-local-source-release'
if case=='legacy-kind':kind='legacy-byte-snapshot'
if case=='manifest-provenance':manifest['unmatchedSyntheticField']=True
manifest_digest=release_module.digest(release_module.encoded(provenance))
gate={'operating_mode':'ACTIVE','schema_contract_version':'52-plus-2-v1.2','revision':10,
      'active_manifest_hash':manifest_digest,'active_release_digest':jar_digest}
if case=='manifest-digest':gate['active_manifest_hash']='d'*64
if case=='provenance-release-digest':gate['active_release_digest']='e'*64
deployment={'releaseDigest':gate['active_release_digest'],'manifestHash':gate['active_manifest_hash']}
for folder in (package,runner.RUNTIME):
    (folder/'application.properties').write_bytes(b'synthetic-config-only')
    (folder/'deployment.json').write_bytes(release_module.encoded(deployment))
(package/'release-manifest.json').write_bytes(release_module.encoded(manifest))
(package/'app.jar').write_bytes(b'different-synthetic-jar' if case=='jar-bytes' else jar)
record={'kind':kind,'provenance':provenance}
current={'id':'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','gate':gate}
commands={**release_module.app_commands(runner,package),'worker':worker_command(runner,package)}
saved={name:{'pid':i+1,'executable':command[0],'args':command[1:],'created':'synthetic-start-'+name} for i,(name,command) in enumerate(commands.items())}
(runner.RUNTIME/'processes.json').write_bytes(release_module.encoded(saved))
class Boundary:
    def __init__(self,runner):pass
    def protect(self):pass
    def processes(self):return copy.deepcopy(list(saved.values()))
class Release:
    def __init__(self,*args):pass
    def current(self):return current
    def load(self,id):return record
    def package(self,id):return package
class VerifiedBoundary(Exception):pass
class CredentialAccess(Exception):pass
regular=release_module.regular
def guarded_regular(path):
    if path.name=='original-manifest.json':raise VerifiedBoundary()
    if path.name in ('browser-credentials.json','task9-browser-credentials.json','task9-test-account-operation.json'):raise CredentialAccess()
    return regular(path)
release_module.RuntimeBoundary=Boundary;release_module.LocalRelease=Release;release_module.regular=guarded_regular
scope={}
try:
    with contextlib.redirect_stdout(io.StringIO()):
        try:exec(compile(sys.stdin.read(),'<actual-task9-release-bridge>','exec'),scope)
        except VerifiedBoundary:
            if sys.argv[2]!='load':raise
    assert scope['result']['buildSha']=='c'*40 and scope['result']['jarSha256']==jar_digest
    print('VERIFIED_BEFORE_CREDENTIALS')
except CredentialAccess:
    print('CREDENTIAL_ACCESS_ATTEMPTED');sys.exit(3)
except Exception:
    print('REJECTED_BEFORE_CREDENTIALS');sys.exit(2)
`;
  for (const mode of ['snapshot', 'load']) for (const scenario of ['coherent', 'unknown-kind', 'source-kind', 'legacy-kind', 'manifest-provenance', 'manifest-digest', 'provenance-release-digest', 'jar-bytes']) {
    const result = spawnSync('D:/soft/python3/python.exe', ['-B', '-c', probe, resolve(__dirname, '../..'), mode, synthetic, scenario], { input: LOCAL_RUNTIME_BRIDGE, encoding: 'utf8', windowsHide: true });
    expect(result.status, `${mode}:${scenario}`).toBe(scenario === 'coherent' ? 0 : 2);
    expect(result.stdout.trim(), `${mode}:${scenario}`).toBe(scenario === 'coherent' ? 'VERIFIED_BEFORE_CREDENTIALS' : 'REJECTED_BEFORE_CREDENTIALS');
  }
});
