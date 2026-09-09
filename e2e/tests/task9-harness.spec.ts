import { test, expect } from '@playwright/test';
import { requireLocalAcceptance, validateEnvironment, validateAccounts, LOCAL_RUNTIME_BRIDGE } from '../fixtures/local-environment';
import { safeFailureCode } from '../reporters/safe-reporter';
import { OperationJournal, type PhaseEvidence } from '../fixtures/operation-journal';
import { existsSync, mkdtempSync, readFileSync, writeFileSync, symlinkSync } from 'node:fs';
import { noLinks } from '../fixtures/local-environment';
import { dispatchObserved, matchFact, requireReceiptLocationBuild } from '../fixtures/identity-setup';
import SafeReporter from '../reporters/safe-reporter';
import { createIdentityApi } from '../../apps/workbench/src/features/identity/identityApi';
import { RecoveryStore } from '../../apps/workbench/src/features/session/recoveryMarker';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

test('offline explicit-local gate', () => {
  expect(() => requireLocalAcceptance(undefined)).toThrow();
  expect(() => requireLocalAcceptance('APPROVED_SYNTHETIC_ONLY')).not.toThrow();
  expect(() => requireLocalAcceptance('production')).toThrow();
  expect(safeFailureCode('password=do-not-log')).not.toContain('do-not-log');
});

const environment = {
  origin: 'https://localhost:19444', issuer: 'https://localhost:19443/realms/local-r1',
  buildSha: '04bd695f7a8f656a5ed8fb96c5168e44a91bab8d',
  releaseId: 'aabce4e3252946e4952cbcb41ff280d1',
  jarSha256: 'b8af135f74cdafcb8396ee4d55e0526cec359ce65ee801ddd91f058e3bc2513a',
  manifestHash: '8478c05d4d783f85b7d49340e05ac667edb3ea081915e71c12efa511b5a62a25',
  revision: 9, browserVersion: '153.0.8010.12', browserRevision: '1243',
};
test('offline rejects wrong origin issuer artifact or browser', () => {
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
test('offline rejects unfinished operation and replaced provider identity', () => {
  const f = accounts();
  expect(() => validateAccounts(f.original, f.credentials, f.operation)).not.toThrow();
  f.credentials.accounts.intake.providerUserId = '00000000-0000-4000-8000-000000000096';
  expect(() => validateAccounts(f.original, f.credentials, f.operation)).toThrow();
  const g = accounts(); g.operation.stage = 'RECOVERY';
  expect(() => validateAccounts(g.original, g.credentials, g.operation)).toThrow();
});

const identity = { runId: '00000000-0000-4000-8000-000000000091', environmentDigest: 'a'.repeat(64), buildSha: environment.buildSha };
const command = { step: 'principal-intake', commandId: '00000000-0000-4000-8000-000000000092', method: 'POST', path: '/api/v1/admin/identity/principals', bodySha256: 'b'.repeat(64), actorScopeKey: 'ask1.' + 'a'.repeat(43) };
test('offline pending write blocks another key and survives reopening', () => {
  const path = join(mkdtempSync(join(tmpdir(), 'task9-synthetic-')), 'journal.json');
  const journal = new OperationJournal(path, identity, () => {});
  journal.begin(command);
  expect(() => journal.begin({ ...command, commandId: '00000000-0000-4000-8000-000000000093' })).toThrow();
  expect(() => new OperationJournal(path, { ...identity, runId: '00000000-0000-4000-8000-000000000094' }, () => {})).toThrow();
  const reopened = new OperationJournal(path, identity, () => {});
  expect(() => reopened.begin(command)).toThrow();
  expect(readFileSync(path, 'utf8')).not.toContain('providerUserSelector');
});
test('offline protection or persistence failure prevents dispatch authorization', () => {
  const path = join(mkdtempSync(join(tmpdir(), 'task9-synthetic-')), 'journal.json');
  expect(() => new OperationJournal(path, identity, () => { throw new Error('synthetic-boundary-denied'); })).toThrow();
  const journal = new OperationJournal(path, identity, () => {});
  writeFileSync(path, '{}');
  expect(() => journal.begin(command)).toThrow();
});
test('offline journal rejects secrets and uncontrolled mutation paths', () => {
  for (const bad of [{ ...command, password: 'never-store' }, { ...command, path: '/api/v1/tasks' }, { ...command, actorScopeKey: 'password=never-store' }]) {
    const path = join(mkdtempSync(join(tmpdir(), 'task9-synthetic-')), 'journal.json');
    const journal = new OperationJournal(path, identity, () => {});
    expect(() => journal.begin(bad)).toThrow();
  }
});

test('offline confirms only matching opaque receipt and exact resource reference', () => {
  const path = join(mkdtempSync(join(tmpdir(), 'task9-synthetic-')), 'journal.json');
  const journal = new OperationJournal(path, identity, () => {}); journal.begin(command);
  const receipt = { commandId: command.commandId, receiptId: '00000000-0000-4000-8000-000000000095', completedAt: '2026-09-10T01:00:00Z', outcome: 'SUCCEEDED', resultFact: { factType: 'IDENTITY_PRINCIPAL', factRef: 'a'.repeat(43), revision: 0 } };
  const resource = command.path + '/00000000-0000-4000-8000-000000000090';
  expect(() => journal.complete(command.commandId, 201, { ...receipt, commandId: '00000000-0000-4000-8000-000000000096' }, resource)).toThrow();
  expect(journal.pending()?.commandId).toBe(command.commandId);
  expect(() => journal.complete(command.commandId, 201, receipt, resource)).not.toThrow();
  expect(journal.confirmed('principal-intake')?.resultFact?.factRef).toBe('a'.repeat(43));
});

test('offline linked directory is rejected without reading its files', () => {
  const base = mkdtempSync(join(tmpdir(), 'task9-synthetic-'));
  const link = base + '-junction'; symlinkSync(base, link, 'junction');
  expect(() => noLinks(link)).toThrow();
});

test('offline dispatch observes a durable original key and stops on failed journaling', async () => {
  const path = join(mkdtempSync(join(tmpdir(), 'task9-synthetic-')), 'journal.json');
  const journal = new OperationJournal(path, identity, () => {});
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

test('offline receipt matches exact actor-scoped fact ID, never a display name', () => {
  const actor = { appointmentId: '00000000-0000-4000-8000-000000000001', founderId: '00000000-0000-4000-8000-000000000002', tenantId: '00000000-0000-4000-8000-000000000003', rootId: '00000000-0000-4000-8000-000000000005' };
  const fact = { factType: 'IDENTITY_PRINCIPAL', factRef: '41V4O1g4d31tHMzv5eqlLVXqb-rOxOsQ7kiHMoiiT6k' };
  const wrong = { id: '00000000-0000-4000-8000-000000000006', displayName: '本地合成受理' };
  const right = { id: '00000000-0000-4000-8000-000000000004', displayName: '本地合成受理' };
  expect(matchFact(actor, fact, [wrong, right])).toBe(right.id);
  expect(() => matchFact(actor, fact, [wrong])).toThrow();
  expect(() => matchFact({ ...actor, appointmentId: wrong.id }, fact, [right])).toThrow();
});

test('offline reporter discards raw credentials and preserves failed exit', () => {
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

test('offline known Location drift binary cannot authorize a real write', () => {
  expect(() => requireReceiptLocationBuild('04bd695f7a8f656a5ed8fb96c5168e44a91bab8d')).toThrow();
  expect(() => requireReceiptLocationBuild('synthetic-invalid')).toThrow();
});

test('offline actual Python bridge rejects each controlled historical process before evidence in load and snapshot', () => {
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
    const journal = new OperationJournal(path, identity, () => { if (protectedFailure) throw new Error('synthetic-protection-denied'); });
    expect(() => journal.finishStage('T9-L01-entry', evidence, { writeEvidence(file, bytes) {
      if (failure === 'before-report') throw new Error('synthetic-write-denied');
      writeFileSync(file, bytes, { flag: 'wx' });
      if (failure === 'after-report-write') throw new Error('synthetic-flush-unknown');
      protectedFailure = true;
    } })).toThrow();
    expect(existsSync(path + '.completion.pending')).toBe(true);
    expect(() => journal.begin(command)).toThrow();
    let sent = false;
    await expect((async () => {
      const continued = new OperationJournal(path, identity, () => {});
      continued.requirePrevious('T9-L03-unmapped');
      await dispatchObserved(continued, command, async () => { sent = true; });
    })()).rejects.toThrow();
    expect(sent).toBe(false);
    if (existsSync(evidence.reportPath)) expect(JSON.parse(readFileSync(evidence.reportPath, 'utf8')).status).toBe('ACTIONS_VERIFIED');
  }
});

test('offline completed phase requires intact prepared evidence on continuation', () => {
  const folder = mkdtempSync(join(tmpdir(), 'task9-phase-synthetic-')), path = join(folder, 'journal.json'), evidence = phaseEvidence(folder);
  const journal = new OperationJournal(path, identity, () => {});
  journal.finishStage('T9-L01-entry', evidence);
  expect(existsSync(path + '.completion.pending')).toBe(false);
  expect(JSON.parse(readFileSync(evidence.reportPath, 'utf8')).status).toBe('ACTIONS_VERIFIED');
  expect(JSON.parse(readFileSync(path, 'utf8')).stages[0]).toMatchObject({ caseIdentity: 'T9-L01-entry', status: 'PASSED_SUBSCENARIO', exitCode: 0, reportPath: evidence.reportPath });
  const continued = new OperationJournal(path, identity, () => {});
  expect(() => continued.requirePrevious('T9-L03-unmapped')).not.toThrow();
  writeFileSync(evidence.reportPath, '{}');
  expect(() => continued.requirePrevious('T9-L03-unmapped')).toThrow();
  expect(() => continued.begin(command)).toThrow();
  expect(() => new OperationJournal(path, identity, () => {})).toThrow();
});
