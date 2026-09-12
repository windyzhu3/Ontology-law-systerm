import { expect, test } from '@playwright/test';
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import {
  BUSINESS_PIN,
  IDENTITY_PREDECESSOR,
  requireBusinessAcceptance,
  validateBusinessEnvironment,
} from '../fixtures/business-environment';
import { LOCAL_RUNTIME_BRIDGE, sha } from '../fixtures/local-environment';
import { publicFactRef } from '../fixtures/identity-setup';
import {
  BUSINESS_CASES,
  BusinessJournal,
  type BusinessCommand,
  type BusinessEvidence,
  type BusinessRunIdentity,
} from '../fixtures/business-journal';
import { BusinessDispatchGate, allowBusinessRequest, canonicalBusinessJson } from '../fixtures/business-session';
import { BusinessSetup } from '../fixtures/r1-business-setup';
import BusinessReporter, { businessFailureCode } from '../reporters/business-reporter';

const runIdentity: BusinessRunIdentity = {
  runId: '00000000-0000-4000-8000-000000000101',
  environmentDigest: 'a'.repeat(64),
  buildSha: BUSINESS_PIN.buildSha,
  predecessorRunId: IDENTITY_PREDECESSOR.runId,
  predecessorSha256: IDENTITY_PREDECESSOR.journalSha256,
};
const taskId = '00000000-0000-4000-8000-000000000102';
const command: BusinessCommand = {
  step: 'capture-auto',
  commandId: '00000000-0000-4000-8000-000000000103',
  method: 'POST',
  path: '/api/v1/leads',
  bodySha256: 'b'.repeat(64),
  actorScopeKey: 'ask1.' + 'c'.repeat(43),
  requestSelectors: { actorAppointmentId: '00000000-0000-4000-8000-000000000108', taskId: null, subjectRef: null, subjectRevision: null, taskETag: null, draftId: null, draftRevision: null, draftDigest: null, draftETag: null, intendedValuesSha256: null },
};
const receipt = {
  commandId: command.commandId,
  receiptId: '00000000-0000-4000-8000-000000000104',
  completedAt: '2026-09-10T02:00:00.000Z',
  outcome: 'SUCCEEDED',
  resultFact: { factType: 'LEAD', factRef: 'd'.repeat(43), revision: 0 },
};

test('offline business approval accepts only the named six-card run and same-run continuation', () => {
  expect(() => requireBusinessAcceptance('APPROVED_SYNTHETIC_ONLY', 'APPROVED_SIX_CARD_CHAIN', runIdentity.runId, undefined)).not.toThrow();
  expect(() => requireBusinessAcceptance(undefined, 'APPROVED_SIX_CARD_CHAIN', runIdentity.runId, undefined)).toThrow();
  expect(() => requireBusinessAcceptance('APPROVED_SYNTHETIC_ONLY', 'wrong', runIdentity.runId, undefined)).toThrow();
  expect(() => requireBusinessAcceptance('APPROVED_SYNTHETIC_ONLY', 'APPROVED_SIX_CARD_CHAIN', 'not-a-uuid', undefined)).toThrow();
  expect(() => requireBusinessAcceptance('APPROVED_SYNTHETIC_ONLY', 'APPROVED_SIX_CARD_CHAIN', runIdentity.runId, '00000000-0000-4000-8000-000000000105')).toThrow();
  expect(() => requireBusinessAcceptance('APPROVED_SYNTHETIC_ONLY', 'APPROVED_SIX_CARD_CHAIN', runIdentity.runId, runIdentity.runId)).not.toThrow();
});

test('offline actual business config keeps stable worker projects and enables live matching only for the exact project option', () => {
  const folder = mkdtempSync(join(tmpdir(), 'task96k-actual-config-'));
  const configPath = join(folder, 'probe.config.cjs'), testPath = join(folder, 'benign.spec.cjs');
  const actualConfig = resolve(__dirname, '../business.config.ts'), playwright = resolve(__dirname, '../../node_modules/@playwright/test');
  writeFileSync(testPath, `const { test, expect } = require(${JSON.stringify(playwright)});\ntest('approved-local-business benign no-browser', () => expect(true).toBe(true));\n`);
  writeFileSync(configPath, `const loaded = require(${JSON.stringify(actualConfig)});\nconst actual = loaded.default ?? loaded;\nmodule.exports = { ...actual, testDir: __dirname, outputDir: ${JSON.stringify(join(folder, 'results'))}, reporter: [['line']], projects: actual.projects.map(project => ({ ...project, testDir: __dirname, testMatch: 'benign.spec.cjs' })) };\n`);
  const pinnedNode = 'C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/node-v24.20.0-win-x64/node.exe';
  const cli = resolve(__dirname, '../../node_modules/@playwright/test/cli.js');
  const run = (...args: string[]) => spawnSync(pinnedNode, [cli, 'test', '--config', configPath, ...args], { cwd: folder, encoding: 'utf8', windowsHide: true, timeout: 30_000, maxBuffer: 2 * 1024 * 1024 });

  for (const args of [
    ['--project', 'approved-local-business'],
    ['--project=approved-local-business'],
    ['--project=approved-local-business', '--grep-invert', '--reporter=line'],
    ['--project=approved-local-business', '--', '--reporter=line'],
  ]) {
    const explicit = run(...args);
    expect(explicit.status, explicit.stdout + explicit.stderr).toBe(0);
    expect(explicit.stdout).toContain('[approved-local-business]'); expect(explicit.stdout).toContain('1 passed');
  }

  const listed = run('--list');
  expect(listed.status, listed.stdout + listed.stderr).toBe(0);
  expect(listed.stdout).toContain('[offline-business]'); expect(listed.stdout).not.toContain('[approved-local-business]'); expect(listed.stdout).toContain('Total: 1 test in 1 file');

  const defaultRun = run();
  expect(defaultRun.status, defaultRun.stdout + defaultRun.stderr).toBe(0);
  expect(defaultRun.stdout).toContain('[offline-business]'); expect(defaultRun.stdout).not.toContain('[approved-local-business]'); expect(defaultRun.stdout).toContain('1 passed');

  for (const args of [
    ['--output', '--project=approved-local-business'],
    ['--grep-invert', '--project=approved-local-business'],
    ['-G', '--project=approved-local-business'],
    ['--', '--project=approved-local-business'],
    ['--', '--project', 'approved-local-business'],
  ]) {
    const unselected = run('--list', ...args);
    expect(unselected.status, JSON.stringify(args) + unselected.stdout + unselected.stderr).toBe(0);
    expect(unselected.stdout, JSON.stringify(args)).toContain('[offline-business]');
    expect(unselected.stdout, JSON.stringify(args)).not.toContain('[approved-local-business]');
    expect(unselected.stdout).toContain('Total: 1 test in 1 file');
  }

  const sameNameGrep = run('--grep', 'approved-local-business');
  expect(sameNameGrep.status, sameNameGrep.stdout + sameNameGrep.stderr).toBe(0);
  expect(sameNameGrep.stdout).toContain('[offline-business]'); expect(sameNameGrep.stdout).not.toContain('[approved-local-business]'); expect(sameNameGrep.stdout).toContain('1 passed');

  for (const args of [['--reporter=line'], ['--reporter', 'line']]) {
    const reporterOverride = run('--project=approved-local-business', ...args, '--list');
    expect(reporterOverride.status).not.toBe(0); expect(reporterOverride.stdout + reporterOverride.stderr).toContain('T9_BUSINESS_BOUNDARY');
  }
});

test('offline business environment rejects current artifact drift and an incomplete identity predecessor', () => {
  const value = {
    ...BUSINESS_PIN,
    predecessor: { ...IDENTITY_PREDECESSOR, commandCount: 16, stageCount: 7, pendingCount: 0 },
  };
  expect(() => validateBusinessEnvironment(value)).not.toThrow();
  for (const patch of [
    { buildSha: 'e'.repeat(40) },
    { releaseId: 'f'.repeat(32) },
    { revision: 11 },
    { predecessor: { ...value.predecessor, journalSha256: '0'.repeat(64) } },
    { predecessor: { ...value.predecessor, commandCount: 15 } },
    { predecessor: { ...value.predecessor, stageCount: 6 } },
    { predecessor: { ...value.predecessor, pendingCount: 1 } },
  ]) expect(() => validateBusinessEnvironment({ ...value, ...patch })).toThrow();
});

test('offline actual bridge rejects missing pending or tampered predecessor evidence', () => {
  const synthetic = mkdtempSync(join(tmpdir(), 'task96k-predecessor-'));
  const probe = String.raw`
import sys,types,json,copy
from pathlib import Path
root=Path(sys.argv[3]);runtime=root/'runtime';package=runtime/'releases'/('a'*32);package.mkdir(parents=True)
sys.path.insert(0,str(Path(sys.argv[1])/'deploy/local-login'))
import local_release as release_module
from local_worker import worker_command
runner=types.ModuleType('local_login');runner.ROOT=root;runner.RUNTIME=runtime;runner.TOOLS=root/'tools';runner.JAVA=runner.TOOLS/'java.exe';runner.ORIGIN='https://localhost:19444';runner.ISSUER='https://localhost:19443/realms/local-r1';sys.modules['local_login']=runner
jar=b'synthetic-business-jar';jar_hash=release_module.digest(jar);provenance={'sourceCommit':'c'*40,'jarSha256':jar_hash};manifest_hash=release_module.digest(release_module.encoded(provenance))
gate={'operating_mode':'ACTIVE','schema_contract_version':'52-plus-2-v1.2','revision':12,'active_manifest_hash':manifest_hash,'active_release_digest':jar_hash}
deployment={'releaseDigest':jar_hash,'manifestHash':manifest_hash,'tenantId':'00000000-0000-4000-8000-000000000201'}
for folder in (runtime,package):(folder/'application.properties').write_bytes(b'synthetic-config');(folder/'deployment.json').write_bytes(release_module.encoded(deployment))
(package/'release-manifest.json').write_bytes(release_module.encoded(provenance));(package/'app.jar').write_bytes(jar)
commands={**release_module.app_commands(runner,package),'worker':worker_command(runner,package)}
saved={name:{'pid':i+1,'executable':value[0],'args':value[1:],'created':'synthetic-'+name} for i,(name,value) in enumerate(commands.items())};(runtime/'processes.json').write_bytes(release_module.encoded(saved))
steps=['principal-intake','principal-supervisor','principal-contact','principal-delegate','organization','appointment-intake','appointment-supervisor','appointment-contact','appointment-delegate','grant-intake-0','grant-intake-1','grant-intake-2','grant-intake-3','grant-supervisor-0','grant-supervisor-1','grant-supervisor-2']
cases=['T9-L01-entry','T9-L03-unmapped','T9-I02-exact-directory-binding','T9-L04-qualification-stages','T9-I05-appointment-no-implicit-grant','T9-I06-minimum-business-grants','T9-I13-dynamic-entry']
stages=[]
for i,case in enumerate(cases):
 p=runtime/('report-'+str(i)+'.json');data={'runId':'74a496f6-494e-417d-9abd-69a85c94f165','caseIdentity':case,'status':'ACTIONS_VERIFIED','exitCode':None};p.write_bytes(release_module.encoded(data));stages.append({'caseIdentity':case,'reportPath':str(p),'reportSha256':release_module.digest(p.read_bytes())})
entries=[{'step':step,'status':'CONFIRMED','commandId':'00000000-0000-4000-8000-'+str(300+i).zfill(12),'resourcePath':'/synthetic/00000000-0000-4000-8000-'+str(400+i).zfill(12)} for i,step in enumerate(steps)]
scenario=sys.argv[4]
if scenario=='pending':entries[-1]['status']='PENDING'
journal={'identity':{'runId':'74a496f6-494e-417d-9abd-69a85c94f165'},'commands':entries,'stages':stages};journal_path=runtime/'task9-identity-operation.json';journal_path.write_bytes(release_module.encoded(journal))
if scenario=='missing-report':stages[-1] and Path(stages[-1]['reportPath']).unlink()
if scenario=='tampered-report':Path(stages[-1]['reportPath']).write_bytes(b'{}')
tenant=deployment['tenantId'];root_id='00000000-0000-4000-8000-000000000202';founder='00000000-0000-4000-8000-000000000203';appointment='00000000-0000-4000-8000-000000000204'
(runtime/'operator.json').write_bytes(release_module.encoded({'tenantId':tenant}));(runtime/'original-manifest.json').write_bytes(release_module.encoded({'rootCode':'ROOT','issuer':runner.ISSUER,'commandId':'00000000-0000-4000-8000-000000000205'}))
facts={'bootstrap':{'rootOrganizationId':root_id,'founderPrincipalId':founder,'appointmentId':appointment},'root':{'tenant_id':tenant,'organization_unit_id':root_id,'parent_organization_unit_id':None,'unit_code':'ROOT','state':'ACTIVE'},'founder':{'tenant_id':tenant,'principal_id':founder,'principal_kind':'HUMAN','state':'ACTIVE'},'founderAppointment':{'tenant_id':tenant,'appointment_id':appointment,'principal_id':founder,'organization_unit_id':root_id,'role_code':'IDENTITY_ADMIN','state':'ACTIVE'},'originalSlot':{'command_id':'00000000-0000-4000-8000-000000000205','command_execution_slot_id':'00000000-0000-4000-8000-000000000206'},'originalReceipt':{'command_execution_slot_id':'00000000-0000-4000-8000-000000000206'}}
(runtime/'worker').mkdir();(runtime/'worker'/'grants.json').write_bytes(release_module.encoded({'original':facts}));(runtime/'browser-credentials.json').write_bytes(b'{}');(runtime/'task9-browser-credentials.json').write_bytes(b'{}');(runtime/'task9-test-account-operation.json').write_bytes(b'{}')
class Boundary:
 def __init__(self,*args):pass
 def protect(self):pass
 def processes(self):return copy.deepcopy(list(saved.values()))
class Release:
 def __init__(self,*args):pass
 def current(self):return {'id':'a'*32,'gate':gate}
 def load(self,id):return {'kind':'controlled-local-release','provenance':provenance}
 def package(self,id):return package
release_module.RuntimeBoundary=Boundary;release_module.LocalRelease=Release
try:
 source=sys.stdin.read().replace('44c95f59853fa552d2d7ba933dcb80a4877464fe26dab7c34202d3e1fb0ad9a1',release_module.digest(journal_path.read_bytes()))
 exec(compile(source,'<task96k-bridge>','exec'));print('ACCEPTED')
except Exception:print('REJECTED');sys.exit(2)
`;
  for (const scenario of ['coherent', 'pending', 'missing-report', 'tampered-report']) {
    const bootstrap = spawnSync('D:/soft/python3/python.exe', ['-B', '-c', probe, resolve(__dirname, '../..'), 'business', synthetic + '-' + scenario, scenario], { input: LOCAL_RUNTIME_BRIDGE, encoding: 'utf8', windowsHide: true });
    expect(bootstrap.status, scenario).toBe(scenario === 'coherent' ? 0 : 2);
    expect(bootstrap.stdout.trim().endsWith(scenario === 'coherent' ? 'ACCEPTED' : 'REJECTED')).toBe(true);
  }
});

test('offline journal persists the original command before dispatch and blocks every second write while pending', async () => {
  const path = join(mkdtempSync(join(tmpdir(), 'task96k-business-')), 'task9-business-operation.json');
  let sent = 0;
  const journal = await BusinessJournal.open(path, runIdentity, () => {});
  const gate = new BusinessDispatchGate(journal);
  gate.arm({ step: command.step, method: command.method, path: command.path, body: { sourceRecordKey: 'task96k-fixed-auto' }, actorScopeKey: command.actorScopeKey, requestSelectors: command.requestSelectors });
  await gate.dispatch({ method: 'POST', path: '/api/v1/leads', bodyBytes: Buffer.from('{"sourceRecordKey":"task96k-fixed-auto"}'), commandId: command.commandId, actorScopeKey: command.actorScopeKey }, async () => {
    expect(JSON.parse(readFileSync(path, 'utf8')).commands[0]).toMatchObject({ commandId: command.commandId, status: 'PENDING' });
    sent++;
  });
  expect(sent).toBe(1);
  expect(() => gate.arm({ step: 'capture-manual', method: 'POST', path: '/api/v1/leads', body: { sourceRecordKey: 'task96k-fixed-manual' }, actorScopeKey: command.actorScopeKey, requestSelectors: command.requestSelectors })).toThrow();
  await expect(journal.begin({ ...command, step: 'capture-manual', commandId: '00000000-0000-4000-8000-000000000105' })).rejects.toThrow();
});

test('offline dispatch gate consumes its permit before any await and poisons a duplicate request', async () => {
  const path = join(mkdtempSync(join(tmpdir(), 'task96k-business-race-')), 'task9-business-operation.json');
  let release!: () => void;
  const blocked = new Promise<void>(resolve => { release = resolve; });
  const journal = await BusinessJournal.open(path, runIdentity, () => {});
  const gate = new BusinessDispatchGate(journal);
  gate.arm({ step: command.step, method: command.method, path: command.path, body: { sourceRecordKey: 'task96k-fixed-auto' }, actorScopeKey: command.actorScopeKey, requestSelectors: command.requestSelectors });
  const first = gate.dispatch({ method: 'POST', path: command.path, bodyBytes: Buffer.from('{"sourceRecordKey":"task96k-fixed-auto"}'), commandId: command.commandId, actorScopeKey: command.actorScopeKey }, async () => { await blocked; });
  await expect(gate.dispatch({ method: 'POST', path: command.path, bodyBytes: Buffer.from('{"sourceRecordKey":"task96k-fixed-auto"}'), commandId: '00000000-0000-4000-8000-000000000106', actorScopeKey: command.actorScopeKey }, async () => {})).rejects.toThrow();
  release(); await expect(first).rejects.toThrow();
  expect(gate.failed).toBe(true);
});

test('offline journal confirms only an exact receipt and non-secret lead selectors', async () => {
  const path = join(mkdtempSync(join(tmpdir(), 'task96k-business-receipt-')), 'task9-business-operation.json');
  const journal = await BusinessJournal.open(path, runIdentity, () => {}); await journal.begin(command);
  const selectors = { taskId, subjectRef: 'opaque-lead-ref-0001', subjectRevision: 0, ownerAppointmentId: '00000000-0000-4000-8000-000000000108', taskETag: '"task.' + 'e'.repeat(43) + '"', draftETag: null, draftId: null, draftRevision: null, draftDigest: null, draftValuesSha256: null, ...successorEvidence(taskId, 'COMPLETE_LEAD_INGRESS', '00000000-0000-4000-8000-000000000108') };
  await expect(journal.complete(command.commandId, 201, { ...receipt, commandId: '00000000-0000-4000-8000-000000000109' }, selectors)).rejects.toThrow();
  const reopened = await BusinessJournal.open(path, runIdentity, () => {});
  await expect(reopened.complete(command.commandId, 201, receipt, selectors)).resolves.toEqual(receipt.resultFact);
  expect(readFileSync(path, 'utf8')).not.toContain('example.invalid');
});

test('offline immutable public facts require digest while mutable facts require revision', async () => {
  const path = join(mkdtempSync(join(tmpdir(), 'task96k-business-fact-union-')), 'task9-business-operation.json');
  const journal = await BusinessJournal.open(path, runIdentity, () => {}); await seedCommands(journal, 4);
  const commandId = '00000000-0000-4000-8000-000000000150';
  await journal.begin({ step: 'routing-submit', commandId, method: 'POST', path: `/api/v1/tasks/${taskId}/commands/record-routing-disposition`, bodySha256: '9'.repeat(64), actorScopeKey: command.actorScopeKey,
    requestSelectors: taskRequestSelectors('routing-submit') });
  const selected = { taskId, subjectRef: 'opaque-lead-ref-0001', subjectRevision: 0, ownerAppointmentId: command.requestSelectors.actorAppointmentId, taskETag: '"task.' + 'e'.repeat(43) + '"', draftETag: null, draftId: null, draftRevision: null, draftDigest: null, draftValuesSha256: null, ...successorEvidence(taskId, 'ACK_SOURCE_INTAKE_STOP_REQUEST', command.requestSelectors.actorAppointmentId) };
  const base = { ...receipt, commandId, receiptId: '00000000-0000-4000-8000-000000000151' };
  await expect(journal.complete(commandId, 200, { ...base, resultFact: { factType: 'DECISION_RECORD', factRef: 'q'.repeat(43), revision: 0, digest: 'r'.repeat(43) } }, selected)).rejects.toThrow();
  const reopened = await BusinessJournal.open(path, runIdentity, () => {});
  await expect(reopened.complete(commandId, 200, { ...base, resultFact: { factType: 'DECISION_RECORD', factRef: 'q'.repeat(43), digest: 'r'.repeat(43) } }, selected)).resolves.toEqual({ factType: 'DECISION_RECORD', factRef: 'q'.repeat(43), digest: 'r'.repeat(43) });
});

test('offline same-stage continuation accepts exact earlier confirmations after pending receipt recovery', async () => {
  const path = join(mkdtempSync(join(tmpdir(), 'task96k-business-continuation-')), 'task9-business-operation.json');
  const journal = await BusinessJournal.open(path, runIdentity, () => {}); await seedCommands(journal, 2);
  const commandId = '00000000-0000-4000-8000-000000000152';
  await journal.begin({ step: 'complete-submit', commandId, method: 'POST', path: `/api/v1/tasks/${taskId}/commands/complete-lead-ingress`, bodySha256: '8'.repeat(64), actorScopeKey: command.actorScopeKey,
    requestSelectors: taskRequestSelectors('complete-submit') });
  const reopened = await BusinessJournal.open(path, runIdentity, () => {});
  await reopened.complete(commandId, 200, { ...receipt, commandId, receiptId: '00000000-0000-4000-8000-000000000153' },
    { taskId, subjectRef: 'opaque-lead-ref-0001', subjectRevision: 0, ownerAppointmentId: command.requestSelectors.actorAppointmentId, taskETag: '"task.' + 'e'.repeat(43) + '"', draftETag: null, draftId: null, draftRevision: null, draftDigest: null, draftValuesSha256: null, ...successorEvidence(taskId, 'RESOLVE_LEAD_ROUTING_GAP', command.requestSelectors.actorAppointmentId) });
  expect(() => reopened.requirePrevious(BUSINESS_CASES[0])).not.toThrow();
});

test('offline journal rejects wrong method path body actor and out-of-order steps', async () => {
  for (const changed of [
    { method: 'PUT' },
    { path: `/api/v1/tasks/${taskId}/draft` },
    { bodySha256: 'not-a-hash' },
    { actorScopeKey: 'not-an-actor' },
    { step: 'grant-contact-owner' },
  ]) {
    const path = join(mkdtempSync(join(tmpdir(), 'task96k-business-invalid-')), 'task9-business-operation.json');
    const journal = await BusinessJournal.open(path, runIdentity, () => {});
    await expect(journal.begin({ ...command, ...changed } as BusinessCommand)).rejects.toThrow();
  }
});

test('offline stage publication quarantines report failures and refuses stage skips', async () => {
  const folder = mkdtempSync(join(tmpdir(), 'task96k-business-stage-')), path = join(folder, 'task9-business-operation.json');
  const journal = await BusinessJournal.open(path, runIdentity, () => {});
  expect(() => journal.requirePrevious('T9-I06-contact-owner')).toThrow();
  await seedFirstStage(journal);
  const evidence = { ...phaseEvidence(folder, BUSINESS_CASES[0]), commands: journal.reportCommands(BUSINESS_CASES[0]) };
  await expect(journal.finishStage(BUSINESS_CASES[0], evidence, { writeEvidence() { throw new Error('synthetic-write-failure'); } })).rejects.toThrow();
  expect(existsSync(path + '.completion.pending')).toBe(true);
  await expect(BusinessJournal.open(path, runIdentity, () => {})).rejects.toThrow();
});

test('offline stage boundary forbids the eighth write until the exact first report is published and intact', async () => {
  const folder = mkdtempSync(join(tmpdir(), 'task96k-business-boundary-')), path = join(folder, 'task9-business-operation.json');
  const journal = await BusinessJournal.open(path, runIdentity, () => {}); await seedFirstStage(journal);
  const grant = { step: 'grant-contact-owner', commandId: '00000000-0000-4000-8000-000000000190', method: 'POST', path: '/api/v1/admin/identity/authority-grants', bodySha256: '4'.repeat(64), actorScopeKey: command.actorScopeKey,
    requestSelectors: { ...command.requestSelectors, actorAppointmentId: '00000000-0000-4000-8000-000000000191' } } as const;
  await expect(journal.begin(grant)).rejects.toThrow();
  const reopened = await BusinessJournal.open(path, runIdentity, () => {}), evidence = { ...phaseEvidence(folder, BUSINESS_CASES[0]), commands: reopened.reportCommands(BUSINESS_CASES[0]) };
  await reopened.finishStage(BUSINESS_CASES[0], evidence);
  expect(() => reopened.requirePrevious(BUSINESS_CASES[0])).toThrow();
  writeFileSync(evidence.reportPath, '{}');
  await expect(BusinessJournal.open(path, runIdentity, () => {})).rejects.toThrow();
});

test('offline late protection failure preserves report and completion quarantine across shutdown', async () => {
  const folder = mkdtempSync(join(tmpdir(), 'task96k-business-shutdown-')), path = join(folder, 'task9-business-operation.json');
  let armed = false, calls = 0;
  const journal = await BusinessJournal.open(path, runIdentity, async () => { if (armed && ++calls === 2) throw new Error('synthetic-late-protection'); });
  await seedFirstStage(journal); const evidence = { ...phaseEvidence(folder, BUSINESS_CASES[0]), commands: journal.reportCommands(BUSINESS_CASES[0]) }; armed = true;
  await expect(journal.finishStage(BUSINESS_CASES[0], evidence)).rejects.toThrow();
  expect(existsSync(evidence.reportPath)).toBe(true); expect(existsSync(path + '.completion.pending')).toBe(true);
  await expect(BusinessJournal.open(path, runIdentity, () => {})).rejects.toThrow();
});

test('offline request policy allows only exact IdP reads and named business reads before an armed write', () => {
  expect(allowBusinessRequest(new URL('https://localhost:19444/api/v1/workcards/current'), 'GET')).toBe(true);
  expect(allowBusinessRequest(new URL('https://localhost:19444/api/v1/admin/identity/authority-grants?limit=50'), 'GET')).toBe(true);
  expect(allowBusinessRequest(new URL('https://localhost:19443/realms/local-r1/protocol/openid-connect/token'), 'POST')).toBe(true);
  expect(allowBusinessRequest(new URL('https://localhost:19443/admin/realms/local-r1/users'), 'GET')).toBe(false);
  expect(allowBusinessRequest(new URL('https://localhost:19444/api/v1/leads'), 'POST')).toBe(false);
  expect(allowBusinessRequest(new URL('https://example.invalid/api/v1/workcards/current'), 'GET')).toBe(false);
});

test('offline BusinessSetup route aborts forbidden IdP paths and methods instead of bypassing its policy', async () => {
  const { setup, routeHandler } = await syntheticSetupRoute();
  for (const [url, method] of [
    ['https://localhost:19443/admin/realms/local-r1/users', 'GET'],
    ['https://localhost:19443/realms/other/protocol/openid-connect/token', 'POST'],
    ['https://localhost:19443/resources/local-r1/account', 'POST'],
  ]) {
    let continued = 0, aborted = 0;
    await routeHandler({
      request: () => ({ url: () => url, method: () => method, allHeaders: async () => ({}), headers: () => ({}), postDataBuffer: () => null }),
      continue: async () => { continued++; }, abort: async () => { aborted++; },
    });
    expect({ continued, aborted }).toEqual({ continued: 0, aborted: 1 });
  }
  await setup.close();
});

test('offline BusinessSetup manual capture records the actual supervisor task owner separately from its intake actor', async () => {
  const actorAppointmentId = '00000000-0000-4000-8000-000000000108';
  const ownerAppointmentId = '00000000-0000-4000-8000-000000000110';
  const actor = syntheticSession('intake', actorAppointmentId);
  const owner = syntheticSession('supervisor', ownerAppointmentId);
  const card = syntheticCard('ASSIGN_LEAD', taskId, 'opaque-lead-ref-manual-0001');
  let selected: any;
  const setup = new (BusinessSetup as any)({}, {}, { pending: () => undefined });
  (setup as any).verifyRecorded = async () => false;
  (setup as any).arm = () => {};
  (setup as any).fetch = async () => ({ status: 201, headers: {}, body: receipt });
  (setup as any).workbench = async () => owner;
  (setup as any).current = async () => null;
  (setup as any).refreshUi = async () => card;
  (setup as any).complete = async (_step: string, _response: unknown, _result: unknown, selectors: unknown) => { selected = selectors; };
  await (setup as any).capture(actor, 'capture-manual', 'LOCAL_SYNTHETIC', true, 'ASSIGN_LEAD', 'supervisor');
  expect(selected.ownerAppointmentId).toBe(ownerAppointmentId);
  expect(selected.ownerAppointmentId).not.toBe(actorAppointmentId);
  expect(selected).toMatchObject({ successorTaskId: card.taskId, successorTaskType: card.taskType, successorOwnerAppointmentId: ownerAppointmentId,
    successorSubjectRef: card.subject.subjectRef, successorSubjectRevision: card.subject.subjectRevision, successorTaskETag: card.preconditions.taskETag });
});

test('offline BusinessSetup accepts the schema-shaped ROOT projection without a fabricated revision field', async () => {
  const fixture = predecessorFixture();
  const setup = predecessorSetup(fixture);
  await expect((setup as any).verifyPredecessor()).resolves.toBeUndefined();
});

test('offline BusinessSetup accepts eleven visible original grants only when the seven business IDs and relationships are exact', async () => {
  const fixture = predecessorFixture();
  await expect((predecessorSetup(fixture) as any).verifyPredecessor()).resolves.toBeUndefined();
  expect(fixture.grants).toHaveLength(11);
});

test('offline BusinessSetup rejects a replacement predecessor grant ID', async () => {
  const fixture = predecessorFixture();
  fixture.grants[0] = { ...fixture.grants[0], id: '00000000-0000-4000-8000-000000000299' };
  await expect((predecessorSetup(fixture) as any).verifyPredecessor()).rejects.toThrow();
});

test('offline BusinessSetup rejects a predecessor business grant attached to the wrong appointment', async () => {
  const fixture = predecessorFixture();
  fixture.grants[0] = { ...fixture.grants[0], appointment: { ...fixture.grants[0].appointment, id: fixture.ids['appointment-supervisor'] } };
  await expect((predecessorSetup(fixture) as any).verifyPredecessor()).rejects.toThrow();
});

test('offline BusinessSetup rejects an inactive original organization', async () => {
  const fixture = predecessorFixture();
  fixture.organizations[1] = { ...fixture.organizations[1], state: 'CLOSED' };
  await expect((predecessorSetup(fixture) as any).verifyPredecessor()).rejects.toThrow();
});

test('offline BusinessSetup bounds a new contact grant by a finite original appointment interval', async () => {
  const originalNow = Date.now;
  Date.now = () => Date.parse('2026-09-10T02:00:30Z');
  try {
  const fixture = predecessorFixture();
  const contact: any = fixture.appointments.find(row => row.id === fixture.ids['appointment-contact'])!;
  contact.effectiveFrom = '2026-09-10T01:59:30Z';
  contact.effectiveUntil = '2099-09-10T02:00:00Z';
  const setup = predecessorSetup(fixture);
  await (setup as any).verifyPredecessor();
  const newId = '00000000-0000-4000-8000-000000000298';
  const bootstrap = { tenantId: '00000000-0000-4000-8000-000000000297', rootId: fixture.rootId, founderId: '00000000-0000-4000-8000-000000000296', appointmentId: fixture.founderAppointmentId };
  (setup as any).environment.bootstrap = bootstrap;
  let body: any, rowCall = 0;
  const created = { id: newId, appointment: { id: fixture.ids['appointment-contact'], label: 'synthetic-contact' }, authorityCode: 'SALES_CONTACT_OWNER', scopeOrganization: { id: fixture.rootId, label: 'ROOT' }, validFrom: '2026-09-10T02:00:00Z', validUntil: '2099-09-10T01:59:00Z', state: 'ACTIVE', etag: '"identity.' + 'j'.repeat(43) + '"' };
  const locator = { click: async () => {}, selectOption: async () => {}, fill: async () => {} };
  const response = { status: () => 201, json: async () => ({ ...receipt, resultFact: { factType: 'AUTHORITY_GRANT', factRef: publicFactRef(bootstrap, 'AUTHORITY_GRANT', newId), revision: 0 } }) };
  const admin = { ...syntheticSession('founder', bootstrap.appointmentId), page: { locator: () => locator, getByRole: () => locator, getByLabel: () => locator, waitForResponse: () => Promise.resolve(response) } };
  (setup as any).administrator = async () => admin;
  (setup as any).verifyRecorded = async () => false;
  (setup as any).rows = async () => rowCall++ === 0 ? fixture.grants : [...fixture.grants, { ...created, validFrom: body.validFrom, validUntil: body.validUntil }];
  (setup as any).arm = (_session: unknown, _step: string, _method: string, _path: string, value: unknown) => { body = value; };
  (setup as any).complete = async () => {};
  await (setup as any).grant();
  expect(body.validUntil).not.toBeNull();
  expect(body.validFrom).toBe('2026-09-10T02:00:00.000Z');
  expect(Date.parse(body.validFrom)).toBeLessThanOrEqual(Date.now());
  expect(Date.parse(body.validFrom)).toBeGreaterThanOrEqual(Date.parse(contact.effectiveFrom));
  expect(Date.parse(body.validUntil)).toBeLessThanOrEqual(Date.parse(contact.effectiveUntil));
  } finally { Date.now = originalNow; }
});

test('offline BusinessSetup refuses a grant before dispatch when no effective whole minute is inside the appointment', async () => {
  const originalNow = Date.now;
  Date.now = () => Date.parse('2026-09-10T02:00:30Z');
  try {
    const fixture = predecessorFixture();
    const contact: any = fixture.appointments.find(row => row.id === fixture.ids['appointment-contact'])!;
    contact.effectiveFrom = '2026-09-10T02:00:15Z'; contact.effectiveUntil = '2099-09-10T02:00:00Z';
    const setup = predecessorSetup(fixture); await (setup as any).verifyPredecessor();
    const locator = { click: async () => {}, selectOption: async () => {}, fill: async () => {} };
    const admin = { ...syntheticSession('founder', fixture.founderAppointmentId), page: { locator: () => locator, getByRole: () => locator, getByLabel: () => locator } };
    let armCalls = 0;
    (setup as any).administrator = async () => admin; (setup as any).verifyRecorded = async () => false;
    (setup as any).rows = async () => fixture.grants;
    (setup as any).arm = () => { armCalls++; throw new Error('synthetic-write-was-armed'); };
    await expect((setup as any).grant()).rejects.toThrow();
    expect(armCalls).toBe(0);
  } finally { Date.now = originalNow; }
});

test('offline BusinessSetup accepts normalized Z instants after the exact grant response succeeds', async () => {
  const originalNow = Date.now;
  Date.now = () => Date.parse('2026-09-10T02:00:30Z');
  try {
    const fixture = predecessorFixture();
    const contact: any = fixture.appointments.find(row => row.id === fixture.ids['appointment-contact'])!;
    contact.effectiveFrom = '2026-09-10T01:59:30Z'; contact.effectiveUntil = '2099-09-10T02:00:00Z';
    const setup = predecessorSetup(fixture); await (setup as any).verifyPredecessor();
    const newId = '00000000-0000-4000-8000-000000000311';
    const bootstrap = { tenantId: '00000000-0000-4000-8000-000000000297', rootId: fixture.rootId, founderId: '00000000-0000-4000-8000-000000000296', appointmentId: fixture.founderAppointmentId };
    (setup as any).environment.bootstrap = bootstrap;
    let rowCall = 0, completeCalls = 0;
    const created = { id: newId, appointment: { id: fixture.ids['appointment-contact'], label: 'synthetic-contact' }, authorityCode: 'SALES_CONTACT_OWNER', scopeOrganization: { id: fixture.rootId, label: 'ROOT' }, validFrom: '2026-09-10T02:00:00Z', validUntil: '2099-09-10T02:00:00Z', state: 'ACTIVE', etag: '"identity.' + 'j'.repeat(43) + '"' };
    const locator = { click: async () => {}, selectOption: async () => {}, fill: async () => {} };
    const response = { status: () => 201, json: async () => ({ ...receipt, resultFact: { factType: 'AUTHORITY_GRANT', factRef: publicFactRef(bootstrap, 'AUTHORITY_GRANT', newId), revision: 0 } }) };
    const admin = { ...syntheticSession('founder', bootstrap.appointmentId), page: { locator: () => locator, getByRole: () => locator, getByLabel: () => locator, waitForResponse: () => Promise.resolve(response) } };
    (setup as any).administrator = async () => admin; (setup as any).verifyRecorded = async () => false;
    (setup as any).rows = async () => rowCall++ === 0 ? fixture.grants : [...fixture.grants, created];
    (setup as any).arm = () => {};
    (setup as any).complete = async () => { completeCalls++; };
    await expect((setup as any).grant()).resolves.toBeUndefined();
    expect(completeCalls).toBe(1);
  } finally { Date.now = originalNow; }
});

test('offline BusinessSetup refreshes the cached founder through UI before phase-two header-based reads', async () => {
  const fixture = predecessorFixture(), reads: Array<{ path: string; authorization: string }> = [];
  const environment = { resources: fixture.ids, bootstrap: { rootId: fixture.rootId, appointmentId: fixture.founderAppointmentId } };
  const setup = new (BusinessSetup as any)({}, environment, { confirmed: () => undefined });
  const admin: any = { ...syntheticSession('founder', fixture.founderAppointmentId), auth: { Authorization: 'Bearer synthetic-expired' } };
  admin.self.canEnterIdentityAdmin = true; admin.self.canEnterWorkbench = false;
  let refreshed = false, responseWaiter: { predicate: (response: any) => boolean; resolve: (response: any) => void } | undefined;
  const uiPath = '/api/v1/admin/identity/principals';
  const currentHeaders = { authorization: 'Bearer synthetic-current', 'x-appointment-id': admin.appointmentId };
  const uiResponse = { url: () => 'https://localhost:19444' + uiPath + '?limit=20', status: () => 200,
    request: () => ({ method: () => 'GET', allHeaders: async () => currentHeaders }), allHeaders: async () => ({ 'cache-control': 'no-store' }),
    json: async () => ({ items: fixture.principals, nextCursor: null }) };
  const refresh = async () => {
    // Model only the browser/UI boundary. The real observer must capture the newly validated outgoing header.
    await (setup as any).observe({ url: () => 'https://localhost:19444' + uiPath, method: () => 'GET', allHeaders: async () => currentHeaders }, admin);
    refreshed = true;
    if (responseWaiter?.predicate(uiResponse)) responseWaiter.resolve(uiResponse);
  };
  admin.page = {
    url: () => 'https://localhost:19444/admin/identity/principals',
    getByRole: (role: string, options: { name: string }) => role === 'main' ? { count: async () => 1 } : {
      click: async () => {
        if (options.name === '刷新') return refresh();
        if (options.name === '新增直接授权') throw new Error('SYNTHETIC_PRE_DISPATCH_STOP');
        throw new Error('UNEXPECTED_UI_ACTION');
      },
    },
    locator: () => ({ click: async () => {} }),
    waitForResponse: (predicate: (response: any) => boolean) => new Promise(resolve => { responseWaiter = { predicate, resolve }; }),
    evaluate: async (_fn: unknown, value: { path: string; auth: Record<string, string> }) => {
      reads.push({ path: value.path, authorization: value.auth.Authorization });
      return { status: value.auth.Authorization === 'Bearer synthetic-current' ? 200 : 401, headers: { 'cache-control': 'no-store' },
        body: { items: fixture.grants, nextCursor: null } };
    },
  };
  (setup as any).sessions.set('founder', admin);
  (setup as any).contactAppointment = { effectiveFrom: '2026-09-09T00:00:00Z', effectiveUntil: null };
  await expect((setup as any).grant()).rejects.toThrow('SYNTHETIC_PRE_DISPATCH_STOP');
  expect(refreshed).toBe(true);
  expect(reads).toEqual([{ path: 'https://localhost:19444/api/v1/admin/identity/authority-grants?limit=50', authorization: 'Bearer synthetic-current' }]);
});

test('offline BusinessSetup refreshes a cached workbench before current reads and write arming', async () => {
  const { setup, session, counts } = cachedRefreshSetup('intake');
  const ready = await setup.workbench('intake');
  expect(await setup.current(ready)).toBeNull();
  setup.arm(ready, 'capture-auto', 'POST', '/api/v1/leads', {});
  expect(counts).toEqual({ refreshes: 1, reads: 1, arms: 1 });
  expect(session.auth.Authorization).toBe('Bearer synthetic-current');
});

test('offline BusinessSetup accepts case-insensitive reordered workbench cache header tokens', async () => {
  const { setup, session, counts } = cachedRefreshSetup('intake', {
    responses: [{ cacheControl: 'NO-CACHE, Private', vary: 'authorization' }],
  });
  await expect(setup.workbench('intake')).resolves.toBe(session);
  expect(counts).toEqual({ refreshes: 1, reads: 0, arms: 0 });
});

test('offline BusinessSetup accepts a workbench 304 only after an exact same-Actor UI 200 ETag chain', async () => {
  const { setup, session, counts } = cachedRefreshSetup('intake', {
    responses: [
      { etag: WORKBENCH_TAG },
      { status: 304, etag: WORKBENCH_TAG, ifNoneMatch: WORKBENCH_TAG },
    ],
  });
  await expect(setup.workbench('intake')).resolves.toBe(session);
  await expect(setup.workbench('intake')).resolves.toBe(session);
  expect(counts).toEqual({ refreshes: 2, reads: 0, arms: 0 });
  expect(session.auth.Authorization).toBe('Bearer synthetic-current');
});

test('offline BusinessSetup records the initial UI 200 before the first cached refresh returns 304', async () => {
  const { setup, session, counts, beginUi } = cachedRefreshSetup('intake', {
    installObserver: true,
    responses: [{ status: 304, etag: WORKBENCH_TAG, ifNoneMatch: WORKBENCH_TAG }],
  });
  let releaseEnvelope!: (value: any) => void;
  const envelope = new Promise(resolve => { releaseEnvelope = resolve; });
  const initial = await beginUi({ etag: WORKBENCH_TAG, body: envelope });
  const observation = initial.deliver();
  const refresh = setup.workbench('intake');
  await Promise.resolve(); expect(counts.refreshes).toBe(0);
  releaseEnvelope(validEmptyEnvelope());
  await observation;
  await expect(refresh).resolves.toBe(session);
  expect(counts).toEqual({ refreshes: 1, reads: 0, arms: 0 });
});

test('offline BusinessSetup blocks arm and dispatch while the initial UI response can still fail', async () => {
  const { setup, routeHandler, beginCurrent } = await syntheticSetupRoute();
  const counts = { arms: 0, dispatches: 0, aborts: 0 };
  setup.gate = { arm: () => { counts.arms++; }, dispatch: async (_request: unknown, send: () => Promise<void>) => { counts.dispatches++; await send(); } };
  let rejectEnvelope!: (error: Error) => void;
  const initial = await beginCurrent(new Promise((_resolve, reject) => { rejectEnvelope = reject; }));
  setup.login = async () => initial.session;
  const advance = setup.workbench('intake').then(async (ready: any) => {
    setup.arm(ready, 'capture-auto', 'POST', '/api/v1/leads', {});
  });
  await new Promise(resolve => setTimeout(resolve, 0));
  await routeHandler({
    request: () => ({ url: () => 'https://localhost:19444/api/v1/leads', method: () => 'POST', allHeaders: async () => ({}),
      headers: () => ({ 'x-appointment-id': initial.session.appointmentId, 'idempotency-key': '00000000-0000-4000-8000-000000000190' }), postDataBuffer: () => Buffer.from('{}') }),
    continue: async () => {}, abort: async () => { counts.aborts++; },
  });
  expect(counts).toEqual({ arms: 0, dispatches: 0, aborts: 1 });
  rejectEnvelope(new Error('SYNTHETIC_INVALID_INITIAL_ENVELOPE'));
  await expect(advance).rejects.toThrow('SYNTHETIC_INVALID_INITIAL_ENVELOPE');
  await expect(initial.observation).rejects.toThrow('SYNTHETIC_INVALID_INITIAL_ENVELOPE');
  expect(setup.dispatchFailed).toBe(true);
});

test('offline BusinessSetup rechecks pending UI observations at the final route send boundary', async () => {
  const { setup, routeHandler, beginCurrent } = await syntheticSetupRoute();
  const counts = { continues: 0, aborts: 0 };
  let enteredDispatch!: () => void, releaseDispatch!: () => void;
  const entered = new Promise<void>(resolve => { enteredDispatch = resolve; });
  const held = new Promise<void>(resolve => { releaseDispatch = resolve; });
  setup.gate = { dispatch: async (_request: unknown, send: () => Promise<void>) => { enteredDispatch(); await held; await send(); } };
  const dispatch = routeHandler({
    request: () => ({ url: () => 'https://localhost:19444/api/v1/leads', method: () => 'POST',
      headers: () => ({ 'x-appointment-id': setup.sessions.get('intake').appointmentId, 'idempotency-key': '00000000-0000-4000-8000-000000000191' }), postDataBuffer: () => Buffer.from('{}') }),
    continue: async () => { counts.continues++; }, abort: async () => { counts.aborts++; },
  });
  await entered;
  const pending = await beginCurrent(validEmptyEnvelope(), false);
  releaseDispatch(); await dispatch;
  expect(counts).toEqual({ continues: 0, aborts: 1 });
  pending.fail();
});

test('offline BusinessSetup fails closed when an initial UI request ends without a response', async () => {
  const { setup, beginCurrent } = await syntheticSetupRoute();
  const initial = await beginCurrent(validEmptyEnvelope(), false);
  setup.login = async () => initial.session;
  const advance = setup.workbench('intake');
  await Promise.resolve(); initial.fail();
  await expect(advance).rejects.toThrow();
  expect(() => setup.arm(initial.session, 'capture-auto', 'POST', '/api/v1/leads', {})).toThrow();
  expect(setup.dispatchFailed).toBe(true);
});

test('offline BusinessSetup rejects envelopes the production SPA parser cannot cache', async () => {
  for (const [name, body] of Object.entries({
    'empty summary': { ...validEmptyEnvelope(), todaySummary: '' },
    'incomplete composer': { ...validEmptyEnvelope(), chatComposer: {} },
    'negative waiting count': { ...validEmptyEnvelope(), waitingCount: -1 },
  })) {
    const { setup, session, counts } = cachedRefreshSetup('intake', { responses: [{ body }] });
    await expect(setup.workbench('intake'), name).rejects.toThrow('T9_BUSINESS_BOUNDARY');
    expect(() => setup.arm(session, 'capture-auto', 'POST', '/api/v1/leads', {}), name).toThrow();
    expect(counts, name).toEqual({ refreshes: 1, reads: 0, arms: 0 });
  }
});

test('offline BusinessSetup keeps a harness CURRENT read out of the SPA cache proof', async () => {
  const { setup, session, beginUi, harnessCalls } = cachedRefreshSetup('intake', {
    installObserver: true,
    harnessResponse: { etag: OTHER_WORKBENCH_TAG },
    responses: [{ status: 304, etag: WORKBENCH_TAG, ifNoneMatch: WORKBENCH_TAG }],
  });
  const initial = await beginUi({ etag: WORKBENCH_TAG }); await initial.deliver();
  await expect(setup.current(session)).resolves.toBeNull();
  expect(harnessCalls).toEqual([{ url: 'https://localhost:19444/api/v1/workcards/current', options: {
    headers: { Authorization: 'Bearer synthetic-current', 'X-Appointment-Id': session.appointmentId }, failOnStatusCode: false, maxRedirects: 0 } }]);
  await expect(setup.workbench('intake')).resolves.toBe(session);
});

test('offline BusinessSetup ignores an older UI response delivered after a newer request', async () => {
  const NEWEST_TAG = '"wb.' + 'n'.repeat(43) + '"';
  const { setup, session, beginUi } = cachedRefreshSetup('intake', {
    installObserver: true,
    responses: [{ status: 304, etag: NEWEST_TAG, ifNoneMatch: NEWEST_TAG }],
  });
  const initial = await beginUi({ etag: WORKBENCH_TAG }); await initial.deliver();
  const older = await beginUi({ etag: OTHER_WORKBENCH_TAG, body: validEmptyEnvelope('旧摘要') });
  const newer = await beginUi({ etag: NEWEST_TAG, body: validEmptyEnvelope('新摘要') });
  await newer.deliver(); await older.deliver();
  await expect(setup.workbench('intake')).resolves.toBe(session);
});

test('offline BusinessSetup ignores a superseded same-Actor request failure after the newer UI response succeeds', async () => {
  const NEWEST_TAG = '"wb.' + 'n'.repeat(43) + '"';
  const { setup, session, beginUi } = cachedRefreshSetup('intake', {
    installObserver: true,
    responses: [{ status: 304, etag: NEWEST_TAG, ifNoneMatch: NEWEST_TAG }],
  });
  const older = await beginUi({ etag: OTHER_WORKBENCH_TAG });
  const newer = await beginUi({ etag: NEWEST_TAG }); await newer.deliver();
  older.fail(); await older.completion;
  expect(setup.dispatchFailed).toBe(false);
  expect(session.auth).toEqual({ Authorization: 'Bearer synthetic-current', 'X-Appointment-Id': session.appointmentId });
  await expect(setup.workbench('intake')).resolves.toBe(session);
});

test('offline BusinessSetup ignores a superseded same-Actor body rejection after a newer UI request begins', async () => {
  const NEWEST_TAG = '"wb.' + 'n'.repeat(43) + '"';
  let markBodyStarted!: () => void, rejectOlder!: (error: Error) => void;
  const bodyStarted = new Promise<void>(resolve => { markBodyStarted = resolve; });
  const heldBody = new Promise((_resolve, reject) => { rejectOlder = reject; });
  const olderBody = { then: (resolve: (value: unknown) => void, reject: (error: unknown) => void) => {
    markBodyStarted(); return heldBody.then(resolve, reject);
  } };
  const { setup, session, beginUi } = cachedRefreshSetup('intake', {
    installObserver: true,
    responses: [{ status: 304, etag: NEWEST_TAG, ifNoneMatch: NEWEST_TAG }],
  });
  const older = await beginUi({ etag: OTHER_WORKBENCH_TAG, body: olderBody }); const olderObservation = older.deliver();
  await bodyStarted;
  const newer = await beginUi({ etag: NEWEST_TAG });
  rejectOlder(new Error('SYNTHETIC_SUPERSEDED_BODY_ABORT')); await olderObservation;
  expect(setup.dispatchFailed).toBe(false);
  await newer.deliver();
  expect(session.auth).toEqual({ Authorization: 'Bearer synthetic-current', 'X-Appointment-Id': session.appointmentId });
  await expect(setup.workbench('intake')).resolves.toBe(session);
});

for (const scenario of [
  { name: 'wrong appointment', wire: { requestAppointmentId: '00000000-0000-4000-8000-000000000299' } },
  { name: 'forbidden on-behalf appointment', wire: { onBehalfAppointmentId: '00000000-0000-4000-8000-000000000298' } },
] as const) {
  test(`offline BusinessSetup poisons an older ${scenario.name} UI request after a newer response succeeds`, async () => {
    const NEWEST_TAG = '"wb.' + 'n'.repeat(43) + '"';
    const { setup, session, counts, beginUi } = cachedRefreshSetup('intake', { installObserver: true });
    const older = await beginUi({ etag: OTHER_WORKBENCH_TAG, ...scenario.wire });
    const newer = await beginUi({ etag: NEWEST_TAG }); await newer.deliver();
    await expect(older.deliver()).rejects.toThrow();
    expect(session.auth).toEqual({});
    expect(session.workbenchCache).toBeUndefined();
    expect(setup.dispatchFailed).toBe(true);
    expect(() => setup.arm(session, 'capture-auto', 'POST', '/api/v1/leads', {})).toThrow();
    expect(counts.arms).toBe(0);
  });
}

test('offline BusinessSetup rejects a response when its request Actor snapshot is no longer current', async () => {
  const { setup, session, counts, beginUi } = cachedRefreshSetup('intake', { installObserver: true });
  let releaseEnvelope!: (value: any) => void;
  const body = new Promise(resolve => { releaseEnvelope = resolve; });
  const pending = await beginUi({ body }); const observation = pending.deliver();
  session.self.actorScopeKey = 'ask1.' + 'd'.repeat(43);
  releaseEnvelope(validEmptyEnvelope());
  await expect(observation).rejects.toThrow();
  expect(() => setup.arm(session, 'capture-auto', 'POST', '/api/v1/leads', {})).toThrow();
  expect(counts.arms).toBe(0);
});

test('offline BusinessSetup poisons writes after a dedicated CURRENT response fails', async () => {
  const { setup, session, counts, beginUi } = cachedRefreshSetup('intake', { installObserver: true, harnessResponse: { status: 401 } });
  const initial = await beginUi({ etag: WORKBENCH_TAG }); await initial.deliver();
  await expect(setup.current(session)).rejects.toThrow();
  expect(session.auth).toEqual({});
  expect(session.workbenchCache).toBeUndefined();
  expect(setup.dispatchFailed).toBe(true);
  expect(() => setup.arm(session, 'capture-auto', 'POST', '/api/v1/leads', {})).toThrow();
  expect(counts.arms).toBe(0);
});

test('offline BusinessSetup rechecks Actor and pending UI state when a dedicated CURRENT read completes', async () => {
  for (const change of ['actor', 'pending'] as const) {
    const { setup, session, counts, beginUi } = cachedRefreshSetup('intake', { installObserver: true });
    const initial = await beginUi({ etag: WORKBENCH_TAG }); await initial.deliver();
    let enteredRead!: () => void, releaseRead!: () => void;
    const entered = new Promise<void>(resolve => { enteredRead = resolve; });
    const held = new Promise<void>(resolve => { releaseRead = resolve; });
    const originalGet = session.context.request.get;
    session.context.request.get = async (...args: any[]) => { enteredRead(); await held; return originalGet(...args); };
    const read = setup.current(session);
    await entered;
    let pending: any;
    if (change === 'actor') session.self.actorScopeKey = 'ask1.' + 'd'.repeat(43);
    else pending = await beginUi({ etag: OTHER_WORKBENCH_TAG });
    releaseRead(); await expect(read, change).rejects.toThrow();
    expect(session.auth, change).toEqual({});
    expect(session.workbenchCache, change).toBeUndefined();
    expect(() => setup.arm(session, 'capture-auto', 'POST', '/api/v1/leads', {}), change).toThrow();
    expect(counts.arms, change).toBe(0);
    pending?.fail();
  }
});

test('offline BusinessSetup invalidates cached workbench proof after an Actor change', async () => {
  const { setup, session, counts } = cachedRefreshSetup('intake', {
    responses: [{ etag: WORKBENCH_TAG }, { status: 304, etag: WORKBENCH_TAG, ifNoneMatch: WORKBENCH_TAG }],
  });
  await setup.workbench('intake');
  session.self.actorScopeKey = 'ask1.' + 'd'.repeat(43);
  await expect(setup.workbench('intake')).rejects.toThrow('T9_BUSINESS_BOUNDARY');
  expect(counts).toEqual({ refreshes: 2, reads: 0, arms: 0 });
  expect(session.auth).toEqual({});
});

test('offline BusinessSetup refuses workbench 304 without an exact same-Actor cached ETag chain', async () => {
  const invalid = [
    { name: 'no prior envelope', responses: [{ status: 304, etag: WORKBENCH_TAG, ifNoneMatch: WORKBENCH_TAG }] },
    { name: 'missing request tag', responses: [{ etag: WORKBENCH_TAG }, { status: 304, etag: WORKBENCH_TAG }] },
    { name: 'mismatched request tag', responses: [{ etag: WORKBENCH_TAG }, { status: 304, etag: WORKBENCH_TAG, ifNoneMatch: OTHER_WORKBENCH_TAG }] },
    { name: 'missing response tag', responses: [{ etag: WORKBENCH_TAG }, { status: 304, etag: null, ifNoneMatch: WORKBENCH_TAG }] },
    { name: 'mismatched response tag', responses: [{ etag: WORKBENCH_TAG }, { status: 304, etag: OTHER_WORKBENCH_TAG, ifNoneMatch: WORKBENCH_TAG }] },
  ];
  for (const scenario of invalid) {
    const { setup, session, counts } = cachedRefreshSetup('intake', { responses: scenario.responses });
    if (scenario.responses.length > 1) await setup.workbench('intake');
    await expect(setup.workbench('intake'), scenario.name).rejects.toThrow('T9_BUSINESS_BOUNDARY');
    expect(counts, scenario.name).toEqual({ refreshes: scenario.responses.length, reads: 0, arms: 0 });
    expect(session.auth, scenario.name).toEqual({});
  }
});

test('offline BusinessSetup refuses a cached workbench response with a wrong endpoint cache policy', async () => {
  for (const [name, response] of Object.entries({
    'admin policy': { cacheControl: 'no-store', vary: 'Authorization' },
    'public policy': { cacheControl: 'public, no-cache', vary: 'Authorization' },
    'missing private': { cacheControl: 'no-cache', vary: 'Authorization' },
    'missing no-cache': { cacheControl: 'private', vary: 'Authorization' },
    'missing vary': { cacheControl: 'private, no-cache', vary: null },
    'wrong vary': { cacheControl: 'private, no-cache', vary: 'Cookie' },
    'invalid tag': { cacheControl: 'private, no-cache', vary: 'Authorization', etag: '"task.' + 'a'.repeat(43) + '"' },
    'missing envelope': { cacheControl: 'private, no-cache', vary: 'Authorization', body: null },
  })) {
    const { setup, session, counts } = cachedRefreshSetup('intake', { responses: [response] });
    await expect(setup.workbench('intake'), name).rejects.toThrow('T9_BUSINESS_BOUNDARY');
    expect(counts, name).toEqual({ refreshes: 1, reads: 0, arms: 0 });
    expect(session.auth, name).toEqual({});
  }
});

test('offline BusinessSetup keeps cached admin refreshes on the exact 200 no-store policy', async () => {
  for (const [name, response] of Object.entries({
    '304 no-store': { status: 304, cacheControl: 'no-store' },
    '200 workbench policy': { cacheControl: 'private, no-cache', vary: 'Authorization', etag: WORKBENCH_TAG },
    '200 mixed policy': { cacheControl: 'no-store, private, no-cache' },
  })) {
    const { setup, session, counts } = cachedRefreshSetup('founder', { responses: [response] });
    await expect(setup.administrator(), name).rejects.toThrow('T9_BUSINESS_BOUNDARY');
    expect(counts, name).toEqual({ refreshes: 1, reads: 0, arms: 0 });
    expect(session.auth, name).toEqual({});
  }
});

test('offline BusinessSetup refuses cached admin and workbench reads or arming when UI refresh fails or changes Actor', async () => {
  for (const alias of ['founder', 'intake'] as const) {
    for (const failure of ['401', '403', '503', 'click', 'wrong-actor', 'on-behalf', 'wrong-origin', 'wrong-path', 'unobserved'] as const) {
      const { setup, session, counts } = cachedRefreshSetup(alias, failure);
      const action = async () => {
        const ready = alias === 'founder' ? await setup.administrator() : await setup.workbench('intake');
        if (alias === 'founder') await setup.rows(ready, '/api/v1/admin/identity/authority-grants');
        else await setup.current(ready);
        setup.arm(ready, 'capture-auto', 'POST', '/api/v1/leads', {});
      };
      await expect(action(), alias + ':' + failure).rejects.toThrow();
      expect(counts, alias + ':' + failure).toEqual({ refreshes: 1, reads: 0, arms: 0 });
      expect(session.auth, alias + ':' + failure).toEqual({});
    }
  }
});

test('offline BusinessSetup fresh-process phase two reads the contact appointment after reopening phase one', async () => {
  const folder = mkdtempSync(join(tmpdir(), 'task96k-business-reopened-grant-')), path = join(folder, 'task9-business-operation.json');
  const journal = await BusinessJournal.open(path, runIdentity, () => {}); await seedFirstStage(journal);
  const firstEvidence = { ...phaseEvidence(folder, BUSINESS_CASES[0]), commands: journal.reportCommands(BUSINESS_CASES[0]) };
  await journal.finishStage(BUSINESS_CASES[0], firstEvidence);
  const reopened = await BusinessJournal.open(path, runIdentity, () => {});
  expect(() => reopened.requirePrevious(BUSINESS_CASES[1])).not.toThrow();

  const fixture = predecessorFixture();
  const contact: any = fixture.appointments.find(row => row.id === fixture.ids['appointment-contact'])!;
  contact.effectiveUntil = '2099-09-10T02:00:00Z';
  const bootstrap = { tenantId: '00000000-0000-4000-8000-000000000297', rootId: fixture.rootId, founderId: '00000000-0000-4000-8000-000000000296', appointmentId: fixture.founderAppointmentId };
  const environment: any = { resources: fixture.ids, bootstrap, assertUnchanged: async () => {} };
  const setup = new (BusinessSetup as any)({}, environment, reopened);
  const newId = '00000000-0000-4000-8000-000000000309', commandId = '00000000-0000-4000-8000-000000000310';
  let body: any, grantReads = 0, appointmentReads = 0;
  const locator = { click: async () => {}, selectOption: async () => {}, fill: async () => {} };
  const result = { ...receipt, commandId, resultFact: { factType: 'AUTHORITY_GRANT', factRef: publicFactRef(bootstrap, 'AUTHORITY_GRANT', newId), revision: 0 } };
  const response = { status: () => 201, json: async () => result };
  const admin = { ...syntheticSession('founder', bootstrap.appointmentId), page: { locator: () => locator, getByRole: () => locator, getByLabel: () => locator, waitForResponse: () => Promise.resolve(response) } };
  (setup as any).administrator = async () => admin;
  (setup as any).rows = async (_session: unknown, readPath: string) => {
    if (readPath.endsWith('/appointments')) { appointmentReads++; return fixture.appointments; }
    grantReads++; return grantReads === 1 ? fixture.grants : [...fixture.grants, { id: newId, appointment: { id: fixture.ids['appointment-contact'], label: 'synthetic-contact' }, authorityCode: 'SALES_CONTACT_OWNER', scopeOrganization: { id: fixture.rootId, label: 'ROOT' }, validFrom: body.validFrom, validUntil: body.validUntil, state: 'ACTIVE', etag: '"identity.' + 'j'.repeat(43) + '"' }];
  };
  (setup as any).arm = (_session: unknown, _step: string, _method: string, _path: string, value: unknown) => { body = value; };
  (setup as any).complete = async (_step: string, _response: unknown, receiptValue: any, selectorsValue: any) => {
    await reopened.begin({ step: 'grant-contact-owner', commandId, method: 'POST', path: '/api/v1/admin/identity/authority-grants', bodySha256: '7'.repeat(64), actorScopeKey: admin.self.actorScopeKey, requestSelectors: { ...command.requestSelectors, actorAppointmentId: admin.appointmentId } });
    await reopened.complete(commandId, 201, receiptValue, selectorsValue);
  };
  await expect((setup as any).grant()).resolves.toBeUndefined();
  expect(appointmentReads).toBe(1);
  expect(reopened.confirmed('grant-contact-owner')?.selectors).toEqual({ resourceId: newId });
});

test('offline BusinessSetup refuses a different same-type task during confirmed-draft continuation', async () => {
  const originalTaskId = '00000000-0000-4000-8000-000000000270';
  const unrelatedTaskId = '00000000-0000-4000-8000-000000000271';
  const session = syntheticCommandSession('intake', command.requestSelectors.actorAppointmentId);
  const draft = syntheticDraft('00000000-0000-4000-8000-000000000272', { rationaleSummary: 'Task 9.6k synthetic acknowledgement received.' });
  const current = syntheticCard('ACK_SOURCE_INTAKE_STOP_REQUEST', unrelatedTaskId, 'opaque-lead-ref-unrelated-0001', draft);
  const recorded = recordedDraftEntry('ack-draft', originalTaskId, session.appointmentId, draft);
  const setup = continuationSetup(recorded, session, current);
  await expect((setup as any).card(session, 'ack', 'ACK_SOURCE_INTAKE_STOP_REQUEST', draft.values, null, null)).rejects.toThrow();
  expect(session.submitClicks).toBe(0);
});

test('offline BusinessSetup refuses a changed saved draft before continuation submit', async () => {
  const originalTaskId = '00000000-0000-4000-8000-000000000270';
  const session = syntheticCommandSession('intake', command.requestSelectors.actorAppointmentId);
  const recordedDraft = syntheticDraft('00000000-0000-4000-8000-000000000272', { rationaleSummary: 'Task 9.6k synthetic acknowledgement received.' });
  const changedDraft = { ...recordedDraft, draftId: '00000000-0000-4000-8000-000000000273', digest: 'm'.repeat(43), values: { rationaleSummary: 'changed-value' } };
  const current = syntheticCard('ACK_SOURCE_INTAKE_STOP_REQUEST', originalTaskId, 'opaque-lead-ref-recorded-0001', changedDraft);
  const recorded = recordedDraftEntry('ack-draft', originalTaskId, session.appointmentId, recordedDraft);
  const setup = continuationSetup(recorded, session, current);
  await expect((setup as any).card(session, 'ack', 'ACK_SOURCE_INTAKE_STOP_REQUEST', recordedDraft.values, null, null)).rejects.toThrow();
  expect(session.submitClicks).toBe(0);
});

test('offline BusinessSetup refuses changed revision digest or intended values on the recorded draft ID', async () => {
  const originalTaskId = '00000000-0000-4000-8000-000000000270';
  const session = syntheticCommandSession('intake', command.requestSelectors.actorAppointmentId);
  const recordedDraft = syntheticDraft('00000000-0000-4000-8000-000000000272', { rationaleSummary: 'Task 9.6k synthetic acknowledgement received.' });
  const changedDraft = { ...recordedDraft, draftRevision: 1, digest: 'm'.repeat(43), values: { rationaleSummary: 'changed-value' } };
  const current = syntheticCard('ACK_SOURCE_INTAKE_STOP_REQUEST', originalTaskId, 'opaque-lead-ref-recorded-0001', changedDraft);
  const recorded = recordedDraftEntry('ack-draft', originalTaskId, session.appointmentId, recordedDraft);
  const setup = continuationSetup(recorded, session, current);
  await expect((setup as any).card(session, 'ack', 'ACK_SOURCE_INTAKE_STOP_REQUEST', recordedDraft.values, null, null)).rejects.toThrow();
  expect(session.submitClicks).toBe(0);
});

test('offline BusinessSetup leaves an ambiguous pending cross-owner submit fenced with its original draft evidence', async () => {
  const savedRun = process.env.TASK9_BUSINESS_RUN_ID, savedContinue = process.env.TASK9_BUSINESS_CONTINUE_RUN_ID;
  process.env.TASK9_BUSINESS_RUN_ID = runIdentity.runId; process.env.TASK9_BUSINESS_CONTINUE_RUN_ID = runIdentity.runId;
  try {
    const supervisor = syntheticSession('supervisor', '00000000-0000-4000-8000-000000000280');
    const intake = syntheticSession('intake', '00000000-0000-4000-8000-000000000281');
    const draft = syntheticDraft('00000000-0000-4000-8000-000000000282', { decisionCode: 'REQUEST_SOURCE_INTAKE_STOP', rationaleSummary: 'Task 9.6k requests source intake stop acknowledgement.' });
    const pending: any = {
      ...command, step: 'routing-submit', commandId: '00000000-0000-4000-8000-000000000283', status: 'PENDING', actorScopeKey: supervisor.self.actorScopeKey,
      requestSelectors: { actorAppointmentId: supervisor.appointmentId, taskId, subjectRef: 'opaque-lead-ref-routing-0001', subjectRevision: 1, taskETag: '"task.' + 'e'.repeat(43) + '"', draftId: draft.draftId, draftRevision: draft.draftRevision, draftDigest: draft.digest, draftETag: '"draft.' + 'f'.repeat(43) + '"', intendedValuesSha256: sha(canonicalBusinessJson(draft.values)) },
    };
    let completeCalls = 0;
    const journal = { pending: () => pending, complete: async () => { completeCalls++; } };
    const setup = new (BusinessSetup as any)({}, {}, journal);
    (setup as any).workbench = async (alias: string) => alias === 'supervisor' ? supervisor : intake;
    (setup as any).read = async () => ({ ...receipt, commandId: pending.commandId, resultFact: { factType: 'DECISION_RECORD', factRef: 'n'.repeat(43), digest: 'o'.repeat(43) } });
    (setup as any).current = async (session: any) => session.alias === 'supervisor' ? null : syntheticCard('ACK_SOURCE_INTAKE_STOP_REQUEST', '00000000-0000-4000-8000-000000000284', 'opaque-lead-ref-ack-0001');
    await expect((setup as any).reconcilePending()).rejects.toThrow();
    expect(completeCalls).toBe(0);
    expect(pending.requestSelectors).toMatchObject({ draftId: draft.draftId, draftRevision: 0, draftDigest: draft.digest, draftETag: '"draft.' + 'f'.repeat(43) + '"' });
  } finally {
    if (savedRun === undefined) delete process.env.TASK9_BUSINESS_RUN_ID; else process.env.TASK9_BUSINESS_RUN_ID = savedRun;
    if (savedContinue === undefined) delete process.env.TASK9_BUSINESS_CONTINUE_RUN_ID; else process.env.TASK9_BUSINESS_CONTINUE_RUN_ID = savedContinue;
  }
});

test('offline BusinessSetup terminal pending-submit recovery retains the exact stored draft evidence', async () => {
  const savedRun = process.env.TASK9_BUSINESS_RUN_ID, savedContinue = process.env.TASK9_BUSINESS_CONTINUE_RUN_ID;
  process.env.TASK9_BUSINESS_RUN_ID = runIdentity.runId; process.env.TASK9_BUSINESS_CONTINUE_RUN_ID = runIdentity.runId;
  try {
    const intake = syntheticSession('intake', command.requestSelectors.actorAppointmentId);
    const values = { rationaleSummary: 'Task 9.6k synthetic acknowledgement received.' };
    const draft = syntheticDraft('00000000-0000-4000-8000-000000000304', values);
    const requestSelectors = {
      actorAppointmentId: intake.appointmentId, taskId, subjectRef: 'opaque-lead-ref-ack-terminal-0001', subjectRevision: 1,
      taskETag: '"task.' + 't'.repeat(43) + '"', draftId: draft.draftId, draftRevision: draft.draftRevision,
      draftDigest: draft.digest, draftETag: '"draft.' + 'u'.repeat(43) + '"', intendedValuesSha256: sha(canonicalBusinessJson(values)),
    };
    const pending: any = { ...command, step: 'ack-submit', commandId: '00000000-0000-4000-8000-000000000305', status: 'PENDING', actorScopeKey: intake.self.actorScopeKey, requestSelectors };
    let completedSelectors: any;
    const setup = new (BusinessSetup as any)({}, {}, { pending: () => pending, complete: async (_id: string, _status: number, _receipt: any, result: any) => { completedSelectors = result; } });
    (setup as any).workbench = async () => intake;
    (setup as any).read = async () => ({ ...receipt, commandId: pending.commandId, resultFact: { factType: 'DECISION_RECORD', factRef: 'v'.repeat(43), digest: 'w'.repeat(43) } });
    (setup as any).current = async () => null;
    await expect((setup as any).reconcilePending()).resolves.toBeUndefined();
    expect(completedSelectors).toEqual({
      taskId, subjectRef: requestSelectors.subjectRef, subjectRevision: requestSelectors.subjectRevision, ownerAppointmentId: intake.appointmentId,
      taskETag: requestSelectors.taskETag, draftId: draft.draftId, draftRevision: draft.draftRevision, draftDigest: draft.digest,
      draftETag: requestSelectors.draftETag, draftValuesSha256: requestSelectors.intendedValuesSha256,
      successorTaskId: null, successorTaskType: null, successorOwnerAppointmentId: null,
      successorSubjectRef: null, successorSubjectRevision: null, successorTaskETag: null,
    });
  } finally {
    if (savedRun === undefined) delete process.env.TASK9_BUSINESS_RUN_ID; else process.env.TASK9_BUSINESS_RUN_ID = savedRun;
    if (savedContinue === undefined) delete process.env.TASK9_BUSINESS_CONTINUE_RUN_ID; else process.env.TASK9_BUSINESS_CONTINUE_RUN_ID = savedContinue;
  }
});

test('offline journal durably retains exact original draft evidence before submit dispatch', async () => {
  const path = join(mkdtempSync(join(tmpdir(), 'task96k-business-submit-evidence-')), 'task9-business-operation.json');
  const journal = await BusinessJournal.open(path, runIdentity, () => {}); await seedCommands(journal, 4);
  const draft = syntheticDraft('00000000-0000-4000-8000-000000000285', { decisionCode: 'REQUEST_SOURCE_INTAKE_STOP', rationaleSummary: 'Task 9.6k requests source intake stop acknowledgement.' });
  const requestSelectors = {
    actorAppointmentId: command.requestSelectors.actorAppointmentId, taskId, subjectRef: 'opaque-lead-ref-routing-0001', subjectRevision: 1, taskETag: '"task.' + 'e'.repeat(43) + '"',
    draftId: draft.draftId, draftRevision: draft.draftRevision, draftDigest: draft.digest, draftETag: '"draft.' + 'f'.repeat(43) + '"', intendedValuesSha256: sha(canonicalBusinessJson(draft.values)),
  };
  await expect(journal.begin({ step: 'routing-submit', commandId: '00000000-0000-4000-8000-000000000286', method: 'POST', path: `/api/v1/tasks/${taskId}/commands/record-routing-disposition`, bodySha256: '6'.repeat(64), actorScopeKey: command.actorScopeKey, requestSelectors })).resolves.toBeUndefined();
  expect(JSON.parse(readFileSync(path, 'utf8')).commands[4].requestSelectors).toEqual(requestSelectors);
});

test('offline BusinessSetup pending draft recovery refuses an unrelated same-type current task', async () => {
  const savedRun = process.env.TASK9_BUSINESS_RUN_ID, savedContinue = process.env.TASK9_BUSINESS_CONTINUE_RUN_ID;
  process.env.TASK9_BUSINESS_RUN_ID = runIdentity.runId; process.env.TASK9_BUSINESS_CONTINUE_RUN_ID = runIdentity.runId;
  try {
    const session = syntheticSession('intake', command.requestSelectors.actorAppointmentId);
    const recoveredDraft = syntheticDraft('00000000-0000-4000-8000-000000000287', { rationaleSummary: 'Task 9.6k synthetic acknowledgement received.' });
    const pending: any = { ...command, step: 'ack-draft', commandId: '00000000-0000-4000-8000-000000000288', status: 'PENDING',
      requestSelectors: { ...taskRequestSelectors('ack-draft'), actorAppointmentId: session.appointmentId, taskId, subjectRef: 'opaque-lead-ref-recorded-0001', intendedValuesSha256: sha(canonicalBusinessJson(recoveredDraft.values)) } };
    let completeCalls = 0;
    const setup = new (BusinessSetup as any)({}, {}, { pending: () => pending, complete: async () => { completeCalls++; } });
    (setup as any).workbench = async () => session;
    (setup as any).read = async () => ({ ...receipt, commandId: pending.commandId, resultFact: { factType: 'ACTION_DRAFT', factRef: 'p'.repeat(43), revision: recoveredDraft.draftRevision } });
    (setup as any).current = async () => syntheticCard('ACK_SOURCE_INTAKE_STOP_REQUEST', '00000000-0000-4000-8000-000000000289', 'opaque-lead-ref-unrelated-0001', recoveredDraft);
    await expect((setup as any).reconcilePending()).rejects.toThrow();
    expect(completeCalls).toBe(0);
  } finally {
    if (savedRun === undefined) delete process.env.TASK9_BUSINESS_RUN_ID; else process.env.TASK9_BUSINESS_RUN_ID = savedRun;
    if (savedContinue === undefined) delete process.env.TASK9_BUSINESS_CONTINUE_RUN_ID; else process.env.TASK9_BUSINESS_CONTINUE_RUN_ID = savedContinue;
  }
});

test('offline BusinessSetup pending draft recovery accepts the exact same-Actor public draft factRef', async () => {
  const savedRun = process.env.TASK9_BUSINESS_RUN_ID, savedContinue = process.env.TASK9_BUSINESS_CONTINUE_RUN_ID;
  process.env.TASK9_BUSINESS_RUN_ID = runIdentity.runId; process.env.TASK9_BUSINESS_CONTINUE_RUN_ID = runIdentity.runId;
  try {
    const appointmentId = '00000000-0000-4000-8000-000000000215';
    const session = syntheticSession('intake', appointmentId);
    const recoveredDraft = syntheticDraft('00000000-0000-4000-8000-000000000306', { rationaleSummary: 'Task 9.6k synthetic acknowledgement received.' });
    const pending: any = { ...command, step: 'ack-draft', commandId: '00000000-0000-4000-8000-000000000307', status: 'PENDING', actorScopeKey: session.self.actorScopeKey,
      requestSelectors: { ...taskRequestSelectors('ack-draft'), actorAppointmentId: appointmentId, taskId, subjectRef: 'opaque-lead-ref-recorded-0001', intendedValuesSha256: sha(canonicalBusinessJson(recoveredDraft.values)) } };
    let completedSelectors: any;
    const environment = { bootstrap: { tenantId: '00000000-0000-4000-8000-000000000201' }, resources: { 'principal-intake': '00000000-0000-4000-8000-000000000211' } };
    const setup = new (BusinessSetup as any)({}, environment, { pending: () => pending, complete: async (_id: string, _status: number, _receipt: any, selected: any) => { completedSelectors = selected; } });
    (setup as any).workbench = async () => session;
    (setup as any).read = async () => ({ ...receipt, commandId: pending.commandId, resultFact: { factType: 'ACTION_DRAFT', factRef: '6fPLw_QLCoD1Kzy-d4J8qyYm3UfuYjI_STKJGKmKTPc', revision: 0 } });
    (setup as any).current = async () => syntheticCard('ACK_SOURCE_INTAKE_STOP_REQUEST', taskId, 'opaque-lead-ref-recorded-0001', recoveredDraft);
    await expect((setup as any).reconcilePending()).resolves.toBeUndefined();
    expect(completedSelectors.draftId).toBe(recoveredDraft.draftId);
  } finally {
    if (savedRun === undefined) delete process.env.TASK9_BUSINESS_RUN_ID; else process.env.TASK9_BUSINESS_RUN_ID = savedRun;
    if (savedContinue === undefined) delete process.env.TASK9_BUSINESS_CONTINUE_RUN_ID; else process.env.TASK9_BUSINESS_CONTINUE_RUN_ID = savedContinue;
  }
});

test('offline BusinessSetup pending draft recovery refuses a mismatched same-Actor draft factRef', async () => {
  const savedRun = process.env.TASK9_BUSINESS_RUN_ID, savedContinue = process.env.TASK9_BUSINESS_CONTINUE_RUN_ID;
  process.env.TASK9_BUSINESS_RUN_ID = runIdentity.runId; process.env.TASK9_BUSINESS_CONTINUE_RUN_ID = runIdentity.runId;
  try {
    const appointmentId = '00000000-0000-4000-8000-000000000215';
    const session = syntheticSession('intake', appointmentId);
    const recoveredDraft = syntheticDraft('00000000-0000-4000-8000-000000000306', { rationaleSummary: 'Task 9.6k synthetic acknowledgement received.' });
    const pending: any = { ...command, step: 'ack-draft', commandId: '00000000-0000-4000-8000-000000000308', status: 'PENDING', actorScopeKey: session.self.actorScopeKey,
      requestSelectors: { ...taskRequestSelectors('ack-draft'), actorAppointmentId: appointmentId, taskId, subjectRef: 'opaque-lead-ref-recorded-0001', intendedValuesSha256: sha(canonicalBusinessJson(recoveredDraft.values)) } };
    let completeCalls = 0;
    const environment = { bootstrap: { tenantId: '00000000-0000-4000-8000-000000000201' }, resources: { 'principal-intake': '00000000-0000-4000-8000-000000000211' } };
    const setup = new (BusinessSetup as any)({}, environment, { pending: () => pending, complete: async () => { completeCalls++; } });
    (setup as any).workbench = async () => session;
    (setup as any).read = async () => ({ ...receipt, commandId: pending.commandId, resultFact: { factType: 'ACTION_DRAFT', factRef: 'x'.repeat(43), revision: 0 } });
    (setup as any).current = async () => syntheticCard('ACK_SOURCE_INTAKE_STOP_REQUEST', taskId, 'opaque-lead-ref-recorded-0001', recoveredDraft);
    await expect((setup as any).reconcilePending()).rejects.toThrow();
    expect(completeCalls).toBe(0);
  } finally {
    if (savedRun === undefined) delete process.env.TASK9_BUSINESS_RUN_ID; else process.env.TASK9_BUSINESS_RUN_ID = savedRun;
    if (savedContinue === undefined) delete process.env.TASK9_BUSINESS_CONTINUE_RUN_ID; else process.env.TASK9_BUSINESS_CONTINUE_RUN_ID = savedContinue;
  }
});

test('offline BusinessSetup leaves ambiguous manual-capture recovery fenced instead of adopting a supervisor card', async () => {
  const savedRun = process.env.TASK9_BUSINESS_RUN_ID, savedContinue = process.env.TASK9_BUSINESS_CONTINUE_RUN_ID;
  process.env.TASK9_BUSINESS_RUN_ID = runIdentity.runId; process.env.TASK9_BUSINESS_CONTINUE_RUN_ID = runIdentity.runId;
  try {
    const intake = syntheticSession('intake', command.requestSelectors.actorAppointmentId);
    const supervisor = syntheticSession('supervisor', '00000000-0000-4000-8000-000000000290');
    const pending: any = { ...command, step: 'capture-manual', commandId: '00000000-0000-4000-8000-000000000291', status: 'PENDING', actorScopeKey: intake.self.actorScopeKey };
    let completeCalls = 0;
    const setup = new (BusinessSetup as any)({}, {}, { pending: () => pending, complete: async () => { completeCalls++; } });
    (setup as any).workbench = async (alias: string) => alias === 'intake' ? intake : supervisor;
    (setup as any).read = async () => ({ ...receipt, commandId: pending.commandId });
    (setup as any).current = async (session: any) => session.alias === 'intake' ? null : syntheticCard('ASSIGN_LEAD', '00000000-0000-4000-8000-000000000292', 'opaque-lead-ref-manual-recovery');
    await expect((setup as any).reconcilePending()).rejects.toThrow();
    expect(completeCalls).toBe(0);
  } finally {
    if (savedRun === undefined) delete process.env.TASK9_BUSINESS_RUN_ID; else process.env.TASK9_BUSINESS_RUN_ID = savedRun;
    if (savedContinue === undefined) delete process.env.TASK9_BUSINESS_CONTINUE_RUN_ID; else process.env.TASK9_BUSINESS_CONTINUE_RUN_ID = savedContinue;
  }
});

test('offline BusinessSetup rejects a known-success successor whose causal digest does not match the original receipt', async () => {
  const supervisor = syntheticCommandSession('supervisor', '00000000-0000-4000-8000-000000000293');
  const intake = syntheticSession('intake', command.requestSelectors.actorAppointmentId);
  const draft = { ...syntheticDraft('00000000-0000-4000-8000-000000000294', { decisionCode: 'REQUEST_SOURCE_INTAKE_STOP', rationaleSummary: 'Task 9.6k requests source intake stop acknowledgement.' }), actionCode: 'RECORD_ROUTING_DISPOSITION' };
  const original = syntheticCard('RESOLVE_LEAD_ROUTING_GAP', taskId, 'opaque-lead-ref-recorded-0001', draft);
  const successor = { ...syntheticCard('ACK_SOURCE_INTAKE_STOP_REQUEST', '00000000-0000-4000-8000-000000000295', 'opaque-lead-ref-ack-0001'), commandForm: { values: { causalDecisionId: '00000000-0000-4000-8000-000000000296', causalDecisionHash: 'wrong-digest' } } };
  const recorded = recordedDraftEntry('routing-draft', taskId, supervisor.appointmentId, draft);
  let clicked = false, responseCall = 0;
  const result = { ...receipt, commandId: '00000000-0000-4000-8000-000000000297', resultFact: { factType: 'DECISION_RECORD', factRef: 'q'.repeat(43), digest: 'r'.repeat(43) } };
  const post = { url: () => `https://localhost:19444/api/v1/tasks/${taskId}/commands/record-routing-disposition`, status: () => 200, json: async () => result };
  const refreshed = { status: () => 200, json: async () => ({ currentCard: null }) };
  supervisor.page.locator = () => ({ _apiName: 'Locator', _expect: async () => ({ matches: true, received: 'enabled', log: [], timeout: 0 }), click: async () => { clicked = true; supervisor.submitClicks++; } });
  supervisor.page.waitForResponse = () => Promise.resolve(responseCall++ === 0 ? post : refreshed);
  const setup = continuationSetup(recorded, supervisor, original);
  (setup as any).workbench = async () => intake;
  (setup as any).current = async (session: any) => session === supervisor ? original : clicked ? successor : null;
  (setup as any).refreshUi = async (session: any) => session === intake && clicked ? successor : null;
  await expect((setup as any).card(supervisor, 'routing', 'RESOLVE_LEAD_ROUTING_GAP', draft.values, 'ACK_SOURCE_INTAKE_STOP_REQUEST', 'intake')).rejects.toThrow();
});

test('offline BusinessSetup actual ASSIGN consumer accepts the successor Lead revision from zero to one', async () => {
  const supervisor = syntheticCommandSession('supervisor', '00000000-0000-4000-8000-000000000310');
  const contact = syntheticSession('contact', '00000000-0000-4000-8000-000000000311');
  const values = { ownerAppointmentId: contact.appointmentId };
  const draft = { ...syntheticDraft('00000000-0000-4000-8000-000000000312', values), actionCode: 'ASSIGN_LEAD' };
  const original = syntheticCard('ASSIGN_LEAD', taskId, 'opaque-lead-ref-recorded-0001', draft);
  const successor = syntheticCard('CONTACT_LEAD', '00000000-0000-4000-8000-000000000313', 'opaque-lead-ref-contact-0001');
  successor.subject.subjectRevision = 1;
  successor.commandForm = { values: { leadAssignmentId: '00000000-0000-4000-8000-000000000314', leadAssignmentRevision: 0 } };
  const recorded = recordedDraftEntry('assign-draft', taskId, supervisor.appointmentId, draft);
  const result = { ...receipt, commandId: '00000000-0000-4000-8000-000000000315', resultFact: { factType: 'LEAD_ASSIGNMENT', factRef: 'q'.repeat(43), revision: 0 } };
  const post = { url: () => `https://localhost:19444/api/v1/tasks/${taskId}/commands/assign-lead`, status: () => 200, json: async () => result };
  const refreshed = { status: () => 200, json: async () => ({ currentCard: null }) };
  let clicked = false, responseCall = 0;
  supervisor.page.locator = () => ({ _apiName: 'Locator', _expect: async () => ({ matches: true, received: 'enabled', log: [], timeout: 0 }), click: async () => { clicked = true; supervisor.submitClicks++; } });
  supervisor.page.waitForResponse = () => Promise.resolve(responseCall++ === 0 ? post : refreshed);
  const setup = continuationSetup(recorded, supervisor, original);
  (setup as any).workbench = async () => contact;
  (setup as any).current = async (session: any) => session === supervisor ? original : clicked ? successor : null;
  (setup as any).refreshUi = async (session: any) => session === contact && clicked ? successor : null;

  await expect((setup as any).card(supervisor, 'assign', 'ASSIGN_LEAD', values, 'CONTACT_LEAD', 'contact')).resolves.toBeUndefined();
  expect(supervisor.submitClicks).toBe(1);
});

test('offline BusinessSetup requires exact ASSIGN successor and new Assignment revisions', () => {
  const setup = new (BusinessSetup as any)({}, {}, {});
  const assignmentId = '00000000-0000-4000-8000-000000000316';
  const requireAssign = (originalRevision: number, successorRevision: number, factType = 'LEAD_ASSIGNMENT', receiptRevision = 0,
    leadAssignmentRevision = receiptRevision, leadAssignmentId = assignmentId) => (setup as any).requireKnownSuccessor(
      'assign-submit', { subject: { subjectRevision: originalRevision } }, { resultFact: { factType, revision: receiptRevision } },
      { subject: { subjectRevision: successorRevision }, commandForm: { values: { leadAssignmentId, leadAssignmentRevision } } },
    );

  expect(() => requireAssign(0, 1)).not.toThrow();
  expect(() => requireAssign(8, 9)).not.toThrow();
  for (const invalid of [
    [-1, 0], [0, 0], [0, 2], [2.5, 3.5],
    [Number.MAX_SAFE_INTEGER, Number.MAX_SAFE_INTEGER], [Number.MAX_SAFE_INTEGER + 1, Number.MAX_SAFE_INTEGER + 1],
  ] as const) expect(() => requireAssign(invalid[0], invalid[1])).toThrow();
  expect(() => requireAssign(0, 1, 'LEAD', 0)).toThrow();
  expect(() => requireAssign(0, 1, 'LEAD_ASSIGNMENT', 1, 1)).toThrow();
  expect(() => requireAssign(0, 1, 'LEAD_ASSIGNMENT', 0, 1)).toThrow();
  expect(() => requireAssign(0, 1, 'LEAD_ASSIGNMENT', 0, 0, 'not-a-uuid')).toThrow();
});

test('offline BusinessSetup keeps COMPLETE ROUTING and CONTACT successor revision rules unchanged', () => {
  const setup = new (BusinessSetup as any)({}, {}, {});
  const original = { subject: { subjectRevision: 3 } };
  const leadReceipt = { resultFact: { factType: 'LEAD', revision: 7 } };
  expect(() => (setup as any).requireKnownSuccessor('complete-submit', original, leadReceipt,
    { subject: { subjectRevision: 7 }, commandForm: { values: {} } })).not.toThrow();
  expect(() => (setup as any).requireKnownSuccessor('complete-submit', original, leadReceipt,
    { subject: { subjectRevision: 6 }, commandForm: { values: {} } })).toThrow();

  const decisionReceipt = { resultFact: { factType: 'DECISION_RECORD', digest: 'd'.repeat(43) } };
  const routing = { subject: { subjectRevision: 3 }, commandForm: { values: { causalDecisionId: '00000000-0000-4000-8000-000000000317', causalDecisionHash: 'd'.repeat(43) } } };
  expect(() => (setup as any).requireKnownSuccessor('routing-submit', original, decisionReceipt, routing)).not.toThrow();
  expect(() => (setup as any).requireKnownSuccessor('routing-submit', original, decisionReceipt,
    { ...routing, subject: { subjectRevision: 4 } })).toThrow();

  const contactReceipt = { resultFact: { factType: 'LEAD_CONTACT_RESULT', digest: 'e'.repeat(43) } };
  const contact = { subject: { subjectRevision: 3 }, commandForm: { values: { triggeringContactResultId: '00000000-0000-4000-8000-000000000318', triggeringContactResultHash: 'e'.repeat(43) } } };
  expect(() => (setup as any).requireKnownSuccessor('contact-submit', original, contactReceipt, contact)).not.toThrow();
  expect(() => (setup as any).requireKnownSuccessor('contact-submit', original, contactReceipt,
    { ...contact, subject: { subjectRevision: 4 } })).toThrow();
});

test('offline BusinessSetup refuses capture before dispatch when the intended owner already has a same-type card', async () => {
  const actor = syntheticSession('intake', command.requestSelectors.actorAppointmentId);
  const owner = syntheticSession('supervisor', '00000000-0000-4000-8000-000000000290');
  const unrelated = syntheticCard('ASSIGN_LEAD', '00000000-0000-4000-8000-000000000298', 'opaque-lead-ref-existing-0001');
  let fetchCalls = 0;
  const setup = new (BusinessSetup as any)({}, {}, { pending: () => undefined });
  (setup as any).verifyRecorded = async () => false; (setup as any).workbench = async () => owner; (setup as any).current = async () => unrelated;
  (setup as any).fetch = async () => { fetchCalls++; return { status: 201, headers: {}, body: receipt }; };
  (setup as any).arm = () => {}; (setup as any).complete = async () => {};
  await expect((setup as any).capture(actor, 'capture-manual', 'LOCAL_SYNTHETIC', true, 'ASSIGN_LEAD', 'supervisor')).rejects.toThrow();
  expect(fetchCalls).toBe(0);
});

test('offline BusinessSetup rejects a captured card whose Lead revision does not match the exact receipt', async () => {
  const actor = syntheticSession('intake', command.requestSelectors.actorAppointmentId);
  const card = syntheticCard('COMPLETE_LEAD_INGRESS', '00000000-0000-4000-8000-000000000299', 'opaque-lead-ref-captured-0001');
  card.subject.subjectRevision = 1;
  const setup = new (BusinessSetup as any)({}, {}, { pending: () => undefined });
  (setup as any).verifyRecorded = async () => false; (setup as any).current = async () => null; (setup as any).refreshUi = async () => card;
  (setup as any).fetch = async () => ({ status: 201, headers: {}, body: receipt });
  (setup as any).arm = () => {}; (setup as any).complete = async () => {};
  await expect((setup as any).capture(actor, 'capture-auto', 'LOCAL_SYNTHETIC_AUTO', false, 'COMPLETE_LEAD_INGRESS', 'intake')).rejects.toThrow();
});

test('offline BusinessSetup never arms a fresh draft write for a card different from the recorded predecessor successor', async () => {
  const session: any = syntheticSession('intake', command.requestSelectors.actorAppointmentId);
  session.page.locator = () => ({ _apiName: 'Locator', _expect: async () => ({ matches: true, received: 'disabled', log: [], timeout: 0 }), fill: async () => {}, selectOption: async () => {} });
  const expectedTaskId = '00000000-0000-4000-8000-000000000300', unrelatedTaskId = '00000000-0000-4000-8000-000000000301';
  const current: any = syntheticCard('ACK_SOURCE_INTAKE_STOP_REQUEST', unrelatedTaskId, 'opaque-lead-ref-unrelated-0002');
  current.commandForm = { actionCode: 'ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST', schemaVersion: 1, fields: [], values: { causalDecisionId: '00000000-0000-4000-8000-000000000302', causalDecisionHash: 'h'.repeat(43) } };
  const predecessor = { ...command, step: 'routing-submit', status: 'CONFIRMED', selectors: {
    taskId, subjectRef: 'opaque-lead-ref-routing-0002', subjectRevision: 1, ownerAppointmentId: '00000000-0000-4000-8000-000000000293', taskETag: '"task.' + 'g'.repeat(43) + '"',
    draftETag: '"draft.' + 'f'.repeat(43) + '"', draftId: '00000000-0000-4000-8000-000000000303', draftRevision: 0, draftDigest: 'd'.repeat(43), draftValuesSha256: '1'.repeat(64),
    ...successorEvidence(expectedTaskId, 'ACK_SOURCE_INTAKE_STOP_REQUEST', session.appointmentId),
  } };
  predecessor.selectors.successorSubjectRef = 'opaque-lead-ref-expected-0002';
  let armCalls = 0;
  const setup = new (BusinessSetup as any)({}, {}, { confirmed: (step: string) => step === 'routing-submit' ? predecessor : undefined });
  (setup as any).verifyRecorded = async () => false; (setup as any).current = async () => current;
  (setup as any).arm = () => { armCalls++; throw new Error('synthetic-stop-after-arm'); };
  await (setup as any).card(session, 'ack', 'ACK_SOURCE_INTAKE_STOP_REQUEST', { rationaleSummary: 'Task 9.6k synthetic acknowledgement received.' }, null, null).catch(() => {});
  expect(armCalls).toBe(0);
});

test('offline reporter never emits credentials or raw errors', () => {
  const output: string[] = [], write = process.stdout.write;
  process.stdout.write = ((chunk: any) => { output.push(String(chunk)); return true; }) as typeof write;
  try {
    const reporter = new BusinessReporter(); reporter.onStdOut(); reporter.onStdErr(); reporter.onError();
    reporter.onTestEnd({ title: 'password=do-not-log' } as any, { status: 'failed', errors: [{ message: 'token=do-not-log' }] } as any);
    reporter.onEnd({ status: 'failed' } as any);
  } finally { process.stdout.write = write; }
  expect(output.join('')).not.toContain('do-not-log');
  expect(output.join('')).toContain('status=failed');
  expect(businessFailureCode('secret=do-not-log')).toBe('T9_BUSINESS_FAILURE_CLOSED');
});

function phaseEvidence(folder: string, id: typeof BUSINESS_CASES[number]): BusinessEvidence {
  return { ...runIdentity, apiIdentity: 'f'.repeat(64), executedAt: '2026-09-10T02:00:00.000Z', caseIdentity: id, status: 'ACTIONS_VERIFIED', exitCode: null,
    reportPath: join(folder, `task9-business-${runIdentity.runId}-${id}-00000000-0000-4000-8000-000000000110.json`), commands: [], http: [{ path: '/api/v1/workcards/current', status: 200 }],
    U01: 'NOT_EXECUTED', U02: 'NOT_EXECUTED', U03: 'NOT_EXECUTED' };
}

async function seedFirstStage(journal: BusinessJournal) {
  const entries = [
    ['capture-auto', 'POST', '/api/v1/leads', 'LEAD'],
    ['complete-draft', 'PUT', `/api/v1/tasks/${taskId}/draft`, 'ACTION_DRAFT'],
    ['complete-submit', 'POST', `/api/v1/tasks/${taskId}/commands/complete-lead-ingress`, 'LEAD'],
    ['routing-draft', 'PUT', `/api/v1/tasks/${taskId}/draft`, 'ACTION_DRAFT'],
    ['routing-submit', 'POST', `/api/v1/tasks/${taskId}/commands/record-routing-disposition`, 'DECISION_RECORD'],
    ['ack-draft', 'PUT', `/api/v1/tasks/${taskId}/draft`, 'ACTION_DRAFT'],
    ['ack-submit', 'POST', `/api/v1/tasks/${taskId}/commands/acknowledge-source-intake-stop-request`, 'DECISION_RECORD'],
  ] as const;
  for (const [index, [step, method, path, factType]] of entries.entries()) {
    const commandId = `00000000-0000-4000-8000-${String(120 + index).padStart(12, '0')}`;
    await journal.begin({ step, commandId, method, path, bodySha256: String(index + 1).repeat(64).slice(0, 64), actorScopeKey: command.actorScopeKey,
      requestSelectors: index === 0 ? command.requestSelectors : taskRequestSelectors(step) });
    const immutable = factType === 'DECISION_RECORD';
    await journal.complete(commandId, step.endsWith('-draft') ? 200 : 201, { ...receipt, commandId, receiptId: `00000000-0000-4000-8000-${String(130 + index).padStart(12, '0')}`, resultFact: immutable ? { factType, factRef: String.fromCharCode(97 + index).repeat(43), digest: 'z'.repeat(43) } : { factType, factRef: String.fromCharCode(97 + index).repeat(43), revision: 0 } },
      { taskId, subjectRef: 'opaque-lead-ref-0001', subjectRevision: 0, ownerAppointmentId: '00000000-0000-4000-8000-000000000108', taskETag: '"task.' + 'e'.repeat(43) + '"', draftETag: step.endsWith('-draft') ? '"draft.' + 'f'.repeat(43) + '"' : null, draftId: step.endsWith('-draft') ? '00000000-0000-4000-8000-000000000140' : null, draftRevision: step.endsWith('-draft') ? 0 : null, draftDigest: step.endsWith('-draft') ? 'y'.repeat(43) : null, draftValuesSha256: step.endsWith('-draft') ? '0'.repeat(64) : null, ...successorEvidence(step === 'ack-submit' ? null : taskId, step === 'complete-submit' ? 'RESOLVE_LEAD_ROUTING_GAP' : step === 'routing-submit' ? 'ACK_SOURCE_INTAKE_STOP_REQUEST' : step === 'ack-submit' ? null : 'COMPLETE_LEAD_INGRESS', '00000000-0000-4000-8000-000000000108') });
  }
}

async function seedCommands(journal: BusinessJournal, count: number) {
  const entries = [
    ['capture-auto', 'POST', '/api/v1/leads', 'LEAD'],
    ['complete-draft', 'PUT', `/api/v1/tasks/${taskId}/draft`, 'ACTION_DRAFT'],
    ['complete-submit', 'POST', `/api/v1/tasks/${taskId}/commands/complete-lead-ingress`, 'LEAD'],
    ['routing-draft', 'PUT', `/api/v1/tasks/${taskId}/draft`, 'ACTION_DRAFT'],
  ] as const;
  for (const [index, [step, method, path, factType]] of entries.slice(0, count).entries()) {
    const commandId = `00000000-0000-4000-8000-${String(160 + index).padStart(12, '0')}`;
    await journal.begin({ step, commandId, method, path, bodySha256: String(index + 1).repeat(64), actorScopeKey: command.actorScopeKey,
      requestSelectors: index === 0 ? command.requestSelectors : taskRequestSelectors(step) });
    await journal.complete(commandId, step.endsWith('-draft') ? 200 : 201, { ...receipt, commandId, receiptId: `00000000-0000-4000-8000-${String(170 + index).padStart(12, '0')}`, resultFact: { factType, factRef: String.fromCharCode(103 + index).repeat(43), revision: 0 } },
      { taskId, subjectRef: 'opaque-lead-ref-0001', subjectRevision: 0, ownerAppointmentId: command.requestSelectors.actorAppointmentId, taskETag: '"task.' + 'e'.repeat(43) + '"', draftETag: step.endsWith('-draft') ? '"draft.' + 'f'.repeat(43) + '"' : null, draftId: step.endsWith('-draft') ? '00000000-0000-4000-8000-000000000180' : null, draftRevision: step.endsWith('-draft') ? 0 : null, draftDigest: step.endsWith('-draft') ? 'y'.repeat(43) : null, draftValuesSha256: step.endsWith('-draft') ? '0'.repeat(64) : null, ...successorEvidence(taskId, index === 2 ? 'RESOLVE_LEAD_ROUTING_GAP' : 'COMPLETE_LEAD_INGRESS', command.requestSelectors.actorAppointmentId) });
  }
}

async function syntheticSetupRoute() {
  let handler: any, responseListener: ((response: any) => void) | undefined, requestFailedListener: ((request: any) => void) | undefined;
  const appointmentId = '00000000-0000-4000-8000-000000000108';
  const payload = Buffer.from(JSON.stringify({ iss: BUSINESS_PIN.issuer, preferred_username: 'task9-local-intake', sub: '00000000-0000-4000-8000-000000000109' })).toString('base64url');
  const tokenResponse = { status: () => 200, json: async () => ({ access_token: `e30.${payload}.signature` }) };
  const selfResponse = { status: () => 200, json: async () => ({ state: 'READY', selectedAppointmentId: appointmentId, selectedOnBehalfAppointmentId: null, appointmentChoices: [{ id: appointmentId }], actorScopeKey: 'ask1.' + 'c'.repeat(43), canEnterWorkbench: true, canEnterIdentityAdmin: false }) };
  let responseCall = 0;
  const clickable = { click: async () => {}, fill: async () => {}, count: async () => 1 };
  const page = {
    goto: async () => {}, waitForURL: async () => {}, getByRole: () => clickable, locator: () => clickable,
    waitForResponse: () => Promise.resolve(responseCall++ === 0 ? tokenResponse : selfResponse),
    on: (event: string, listener: (value: any) => void) => {
      if (event === 'response') responseListener = listener;
      if (event === 'requestfailed') requestFailedListener = listener;
    },
  };
  const context = { route: async (_pattern: string, value: any) => { handler = value; }, newPage: async () => page, close: async () => {} };
  const browser = { newContext: async () => context };
  const environment = {
    assertUnchanged: async () => {}, resources: { 'appointment-intake': appointmentId }, bootstrap: { appointmentId: '00000000-0000-4000-8000-000000000107' },
    accounts: { intake: { username: 'task9-local-intake', password: 'synthetic-password', providerUserId: '00000000-0000-4000-8000-000000000109' } },
  };
  const setup = new (BusinessSetup as any)(browser, environment, {});
  await (setup as any).login('intake');
  const session = setup.sessions.get('intake');
  return {
    setup, routeHandler: handler as (route: any) => Promise<void>,
    beginCurrent: async (body: any, deliver = true) => {
      const headers = { authorization: 'Bearer synthetic-current', 'x-appointment-id': appointmentId };
      const request = { url: () => 'https://localhost:19444/api/v1/workcards/current', method: () => 'GET', allHeaders: async () => headers,
        headers: () => headers, postDataBuffer: () => null };
      const response = { url: request.url, status: () => 200, request: () => request,
        allHeaders: async () => ({ 'cache-control': 'private, no-cache', vary: 'Authorization', etag: WORKBENCH_TAG }), json: async () => body };
      await setup.observe(request, session); if (deliver) responseListener!(response);
      return { session, observation: session.workbenchObservation as Promise<void>, fail: () => requestFailedListener!(request) };
    },
  };
}

const WORKBENCH_TAG = '"wb.' + 'a'.repeat(43) + '"';
const OTHER_WORKBENCH_TAG = '"wb.' + 'b'.repeat(43) + '"';
type CachedRefreshWire = { status?: number; cacheControl?: string; vary?: string | null; etag?: string | null; ifNoneMatch?: string; body?: any;
  requestAppointmentId?: string; onBehalfAppointmentId?: string };

function validEmptyEnvelope(todaySummary = '今日暂无待处理责任。') {
  return { todaySummary, currentCard: null, nextSummaries: [], waitingCount: 0,
    chatComposer: { mode: 'ACTION_DRAFT', targetTaskId: null, placeholder: '当前没有候选内容', enabled: false } };
}

function cachedRefreshSetup(alias: 'founder' | 'intake', input: string | { failure?: string; installObserver?: boolean; harnessResponse?: CachedRefreshWire; responses?: CachedRefreshWire[] } = '') {
  const failure = typeof input === 'string' ? input : input.failure ?? '';
  const configured = typeof input === 'string' ? [] : input.responses ?? [];
  const appointmentId = '00000000-0000-4000-8000-000000000108';
  const setup = new (BusinessSetup as any)({}, { bootstrap: { appointmentId }, resources: { 'appointment-intake': appointmentId } }, {});
  const counts = { refreshes: 0, reads: 0, arms: 0 };
  const harnessCalls: Array<{ url: string; options: any }> = [];
  const session: any = { ...syntheticSession(alias, appointmentId), auth: { Authorization: 'Bearer synthetic-expired' } };
  session.self.canEnterIdentityAdmin = alias === 'founder'; session.self.canEnterWorkbench = alias !== 'founder';
  const pagePath = alias === 'founder' ? '/admin/identity/principals' : '/workbench';
  const readPath = alias === 'founder' ? '/api/v1/admin/identity/principals' : '/api/v1/workcards/current';
  let waiter: { predicate: (response: any) => boolean; resolve: (response: any) => void; reject: (error: Error) => void } | undefined;
  const headers = { authorization: 'Bearer synthetic-current', 'x-appointment-id': failure === 'wrong-actor' ? 'different-appointment' : appointmentId,
    ...(failure === 'on-behalf' ? { 'x-on-behalf-appointment-id': appointmentId } : {}) };
  let refreshIndex = 0;
  const makeResponse = (selected: CachedRefreshWire) => {
    const responseHeaders = alias === 'founder'
      ? { 'cache-control': selected.cacheControl ?? 'no-store', ...(selected.vary == null ? {} : { vary: selected.vary }), ...(selected.etag == null ? {} : { etag: selected.etag }) }
      : { 'cache-control': selected.cacheControl ?? 'private, no-cache', ...(selected.vary === null ? {} : { vary: selected.vary ?? 'Authorization' }), ...(selected.etag === null ? {} : { etag: selected.etag ?? WORKBENCH_TAG }) };
    const requestHeaders = { ...headers,
      ...(selected.requestAppointmentId === undefined ? {} : { 'x-appointment-id': selected.requestAppointmentId }),
      ...(selected.onBehalfAppointmentId === undefined ? {} : { 'x-on-behalf-appointment-id': selected.onBehalfAppointmentId }),
      ...(selected.ifNoneMatch === undefined ? {} : { 'if-none-match': selected.ifNoneMatch }) };
    const request = { url: () => 'https://localhost:19444' + readPath, method: () => 'GET', allHeaders: async () => requestHeaders,
      headers: () => requestHeaders, postDataBuffer: () => null };
    return {
      url: () => (failure === 'wrong-origin' ? 'https://invalid.example' : 'https://localhost:19444') + (failure === 'wrong-path' ? '/api/v1/session/context' : readPath),
      status: () => selected.status ?? (/^\d+$/.test(failure) ? Number(failure) : 200),
      request: () => request, allHeaders: async () => responseHeaders,
      json: async () => selected.body === undefined ? validEmptyEnvelope() : selected.body,
    };
  };
  const response = () => makeResponse(configured[Math.min(refreshIndex++, Math.max(0, configured.length - 1))] ?? {});
  let responseListener: ((response: any) => void) | undefined, requestFailedListener: ((request: any) => void) | undefined;
  session.page = {
    url: () => 'https://localhost:19444' + pagePath,
    getByRole: (role: string, options: { name: string }) => role === 'main' ? { count: async () => 1 } : {
      click: async () => {
        counts.refreshes++;
        if (failure === 'click') throw new Error('SYNTHETIC_REFRESH_FAILURE');
        expect(options.name).toBe(alias === 'founder' ? '刷新' : '刷新当前责任');
        const currentResponse = response();
        if (failure !== 'unobserved') await setup.observe(currentResponse.request(), session);
        responseListener?.(currentResponse);
        if (waiter?.predicate(currentResponse)) waiter.resolve(currentResponse);
        else waiter?.reject(new Error('SYNTHETIC_NO_MATCHING_RESPONSE'));
      },
    },
    waitForResponse: (predicate: (response: any) => boolean) => new Promise((resolve, reject) => { waiter = { predicate, resolve, reject }; }),
    on: (event: string, listener: (value: any) => void) => {
      if (event === 'response') responseListener = listener;
      if (event === 'requestfailed') requestFailedListener = listener;
    },
    evaluate: async (_fn: unknown, value: { auth: Record<string, string> }) => {
      counts.reads++;
      const selected = typeof input === 'string' ? {} : input.harnessResponse ?? {};
      const harnessResponse = makeResponse(selected);
      if (alias === 'intake') { await setup.observe(harnessResponse.request(), session); responseListener?.(harnessResponse); }
      return { status: value.auth.Authorization === 'Bearer synthetic-current' ? 200 : 401,
        headers: alias === 'founder' ? { 'cache-control': 'no-store' }
          : { etag: selected.etag ?? WORKBENCH_TAG, 'cache-control': 'private, no-cache', vary: 'Authorization' },
        body: alias === 'founder' ? { items: [], nextCursor: null } : selected.body ?? validEmptyEnvelope() };
    },
  };
  session.context.request = { get: async (url: string, options: any) => { counts.reads++; harnessCalls.push({ url, options }); const selected = typeof input === 'string' ? {} : input.harnessResponse ?? {};
    return { status: () => selected.status ?? 200, headers: () => ({ etag: selected.etag ?? WORKBENCH_TAG, 'cache-control': 'private, no-cache', vary: 'Authorization' }),
      text: async () => JSON.stringify(selected.body ?? validEmptyEnvelope()) }; } };
  if (typeof input !== 'string' && input.installObserver) (setup as any).installWorkbenchResponseObserver(session);
  setup.sessions.set(alias, session); setup.gate = { arm: () => { counts.arms++; } };
  return {
    setup, session, counts, harnessCalls,
    beginUi: async (wire: CachedRefreshWire) => {
      expect(responseListener).toBeDefined(); const uiResponse = makeResponse(wire);
      await setup.observe(uiResponse.request(), session);
      const completion = session.workbenchPending.get(session.workbenchGeneration) as Promise<void>;
      return {
        deliver: () => { responseListener!(uiResponse); return session.workbenchObservation as Promise<void>; },
        fail: () => requestFailedListener!(uiResponse.request()), completion,
      };
    },
  };
}

function syntheticSession(alias: string, appointmentId: string) {
  return {
    alias, appointmentId, context: { close: async () => {} },
    self: { selectedAppointmentId: appointmentId, actorScopeKey: 'ask1.' + 'c'.repeat(43) }, auth: {},
    page: { locator: () => ({ _apiName: 'Locator', _expect: async () => ({ matches: true, received: 'visible', log: [], timeout: 0 }) }) },
  };
}

function syntheticCard(taskType: string, id: string, subjectRef: string, actionDraft: any = null) {
  return {
    taskId: id, taskType, taskRevision: 0, versionStatus: 'CURRENT',
    subject: { subjectType: 'LEAD', subjectRef, subjectRevision: 0 },
    primaryCommand: { enabled: true }, actionDraft,
    preconditions: { taskETag: '"task.' + 'e'.repeat(43) + '"', subjectETag: '"subject.' + 's'.repeat(43) + '"', draftETag: actionDraft ? '"draft.' + 'f'.repeat(43) + '"' : null },
    commandForm: { values: {} },
  };
}

function predecessorFixture() {
  const ids: Record<string, string> = {
    organization: '00000000-0000-4000-8000-000000000210',
    'principal-intake': '00000000-0000-4000-8000-000000000211', 'principal-supervisor': '00000000-0000-4000-8000-000000000212',
    'principal-contact': '00000000-0000-4000-8000-000000000213', 'principal-delegate': '00000000-0000-4000-8000-000000000214',
    'appointment-intake': '00000000-0000-4000-8000-000000000215', 'appointment-supervisor': '00000000-0000-4000-8000-000000000216',
    'appointment-contact': '00000000-0000-4000-8000-000000000217', 'appointment-delegate': '00000000-0000-4000-8000-000000000218',
    'grant-intake-0': '00000000-0000-4000-8000-000000000220', 'grant-intake-1': '00000000-0000-4000-8000-000000000221',
    'grant-intake-2': '00000000-0000-4000-8000-000000000222', 'grant-intake-3': '00000000-0000-4000-8000-000000000223',
    'grant-supervisor-0': '00000000-0000-4000-8000-000000000224', 'grant-supervisor-1': '00000000-0000-4000-8000-000000000225',
    'grant-supervisor-2': '00000000-0000-4000-8000-000000000226',
  };
  const rootId = '00000000-0000-4000-8000-000000000219';
  const etag = '"identity.' + 'i'.repeat(43) + '"';
  const aliases = ['intake', 'supervisor', 'contact', 'delegate'] as const;
  const principals = aliases.map(alias => ({ id: ids[`principal-${alias}`], displayName: `synthetic-${alias}`, state: 'ACTIVE', etag }));
  const organizations = [
    { id: rootId, parentOrganizationId: null, code: 'ROOT', displayName: 'ROOT', state: 'ACTIVE', etag },
    { id: ids.organization, parentOrganizationId: rootId, code: 'LOCAL_ACCEPTANCE', displayName: 'Local acceptance', state: 'ACTIVE', etag },
  ];
  const appointments = aliases.map(alias => ({ id: ids[`appointment-${alias}`], principal: { id: ids[`principal-${alias}`], label: `synthetic-${alias}` }, organization: { id: ids.organization, label: 'Local acceptance' }, roleCode: alias === 'intake' ? 'INTAKE_OPERATOR' : alias === 'supervisor' ? 'ROUTING_SUPERVISOR' : 'CONTACT_OPERATOR', effectiveFrom: '2026-09-09T00:00:00Z', effectiveUntil: null, state: 'ACTIVE', etag }));
  const codes = ['LEAD_CAPTURE', 'LEAD_INGRESS_RESOLVE', 'LEAD_INGRESS_COMPLETE', 'SOURCE_INTAKE_REQUEST_ACK', 'LEAD_ASSIGN', 'LEAD_ROUTING_DECIDE', 'LEAD_VALIDITY_REVIEW'];
  const businessSteps = ['grant-intake-0', 'grant-intake-1', 'grant-intake-2', 'grant-intake-3', 'grant-supervisor-0', 'grant-supervisor-1', 'grant-supervisor-2'];
  const grants = codes.map((authorityCode, index) => ({ id: ids[businessSteps[index]], appointment: { id: index < 4 ? ids['appointment-intake'] : ids['appointment-supervisor'], label: 'synthetic' }, authorityCode, scopeOrganization: { id: rootId, label: 'ROOT' }, validFrom: '2026-09-09T00:00:00Z', validUntil: null, state: 'ACTIVE', etag }));
  for (const [index, authorityCode] of ['IDENTITY_PRINCIPAL_MANAGE', 'IDENTITY_ORGANIZATION_MANAGE', 'IDENTITY_APPOINTMENT_MANAGE', 'IDENTITY_AUTHORITY_MANAGE'].entries()) {
    grants.push({ id: `00000000-0000-4000-8000-${String(230 + index).padStart(12, '0')}`, appointment: { id: '00000000-0000-4000-8000-000000000204', label: 'synthetic-founder' }, authorityCode, scopeOrganization: { id: rootId, label: 'ROOT' }, validFrom: '2026-09-08T00:00:00Z', validUntil: null, state: 'ACTIVE', etag });
  }
  return { ids, rootId, founderAppointmentId: '00000000-0000-4000-8000-000000000204', principals, organizations, appointments, grants };
}

function predecessorSetup(fixture: ReturnType<typeof predecessorFixture>) {
  const setup = new (BusinessSetup as any)({}, { resources: fixture.ids, bootstrap: { rootId: fixture.rootId, appointmentId: fixture.founderAppointmentId } }, {});
  (setup as any).administrator = async () => ({});
  (setup as any).rows = async (_session: unknown, path: string) => path.endsWith('/principals') ? fixture.principals : path.endsWith('/organizations') ? fixture.organizations : path.endsWith('/appointments') ? fixture.appointments : fixture.grants;
  return setup;
}

function syntheticDraft(draftId: string, values: Record<string, unknown>) {
  return { draftId, draftRevision: 0, actionCode: 'ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST', schemaVersion: 1, values, digest: 'k'.repeat(43), updatedAt: '2026-09-10T02:00:00Z', editable: true };
}

function syntheticCommandSession(alias: string, appointmentId: string) {
  let responseCall = 0;
  const post = { url: () => `https://localhost:19444/api/v1/tasks/${taskId}/commands/acknowledge-source-intake-stop-request`, status: () => 200, json: async () => receipt };
  const refreshed = { url: () => 'https://localhost:19444/api/v1/workcards/current', status: () => 200, json: async () => ({ currentCard: null }) };
  const session: any = syntheticSession(alias, appointmentId);
  session.submitClicks = 0;
  session.page.locator = () => ({ _apiName: 'Locator', _expect: async () => ({ matches: true, received: 'enabled', log: [], timeout: 0 }), click: async () => { session.submitClicks++; } });
  session.page.waitForResponse = () => Promise.resolve(responseCall++ === 0 ? post : refreshed);
  return session;
}

function recordedDraftEntry(step: string, recordedTaskId: string, appointmentId: string, draft: any) {
  const card = syntheticCard('ACK_SOURCE_INTAKE_STOP_REQUEST', recordedTaskId, 'opaque-lead-ref-recorded-0001', draft);
  return {
    ...command, step, path: `/api/v1/tasks/${recordedTaskId}/draft`, method: 'PUT', status: 'CONFIRMED', resultFact: { factType: 'ACTION_DRAFT', factRef: 'x'.repeat(43), revision: draft.draftRevision },
    requestSelectors: { actorAppointmentId: appointmentId, taskId: recordedTaskId, subjectRef: card.subject.subjectRef, subjectRevision: card.subject.subjectRevision, taskETag: card.preconditions.taskETag, draftId: null, draftRevision: null, draftDigest: null, draftETag: null, intendedValuesSha256: sha(canonicalBusinessJson(draft.values)) },
    selectors: { taskId: recordedTaskId, subjectRef: card.subject.subjectRef, subjectRevision: card.subject.subjectRevision, ownerAppointmentId: appointmentId, taskETag: card.preconditions.taskETag, draftETag: card.preconditions.draftETag, draftId: draft.draftId, draftRevision: draft.draftRevision, draftDigest: draft.digest, draftValuesSha256: sha(canonicalBusinessJson(draft.values)), ...successorEvidence(null, null, appointmentId) },
  };
}

function continuationSetup(recorded: any, session: any, current: any) {
  const previous: Record<string, string> = { 'complete-draft': 'capture-auto', 'routing-draft': 'complete-submit', 'ack-draft': 'routing-submit', 'assign-draft': 'capture-manual', 'contact-draft': 'assign-submit', 'review-draft': 'contact-submit' };
  const predecessor = { ...command, step: previous[recorded.step], status: 'CONFIRMED', selectors: {
    ...recorded.selectors, successorTaskId: recorded.requestSelectors.taskId, successorTaskType: current.taskType,
    successorOwnerAppointmentId: session.appointmentId, successorSubjectRef: recorded.requestSelectors.subjectRef,
    successorSubjectRevision: recorded.requestSelectors.subjectRevision, successorTaskETag: recorded.requestSelectors.taskETag,
  } };
  const journal = { confirmed: (step: string) => step === recorded.step ? recorded : step === predecessor.step ? predecessor : undefined };
  const setup = new (BusinessSetup as any)({}, { assertUnchanged: async () => {} }, journal);
  (setup as any).verifyRecorded = async (step: string) => step === recorded.step;
  (setup as any).current = async () => current;
  (setup as any).arm = () => {};
  (setup as any).complete = async () => {};
  return setup;
}

function taskRequestSelectors(step: string) {
  const submit = step.endsWith('-submit');
  return { ...command.requestSelectors, taskId, subjectRef: 'opaque-lead-ref-0001', subjectRevision: 0, taskETag: '"task.' + 'e'.repeat(43) + '"',
    draftId: submit ? '00000000-0000-4000-8000-000000000189' : null, draftRevision: submit ? 0 : null, draftDigest: submit ? 'v'.repeat(43) : null,
    draftETag: submit ? '"draft.' + 'f'.repeat(43) + '"' : null, intendedValuesSha256: '0'.repeat(64) };
}

function successorEvidence(successorTaskId: string | null, successorTaskType: string | null, ownerAppointmentId: string) {
  return successorTaskId === null ? { successorTaskId: null, successorTaskType: null, successorOwnerAppointmentId: null, successorSubjectRef: null, successorSubjectRevision: null, successorTaskETag: null }
    : { successorTaskId, successorTaskType, successorOwnerAppointmentId: ownerAppointmentId, successorSubjectRef: 'opaque-lead-ref-0001', successorSubjectRevision: 0, successorTaskETag: '"task.' + 'e'.repeat(43) + '"' };
}
