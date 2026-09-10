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
import {
  BUSINESS_CASES,
  BusinessJournal,
  type BusinessCommand,
  type BusinessEvidence,
  type BusinessRunIdentity,
} from '../fixtures/business-journal';
import { BusinessDispatchGate, allowBusinessRequest } from '../fixtures/business-session';
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
  requestSelectors: { actorAppointmentId: '00000000-0000-4000-8000-000000000108', taskId: null, subjectRef: null, subjectRevision: null, taskETag: null },
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
  const selectors = { taskId, subjectRef: 'opaque-lead-ref-0001', subjectRevision: 0, ownerAppointmentId: '00000000-0000-4000-8000-000000000108', taskETag: '"task.' + 'e'.repeat(43) + '"', draftETag: null, draftId: null, successorTaskId: taskId, successorTaskType: 'COMPLETE_LEAD_INGRESS' };
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
    requestSelectors: { ...command.requestSelectors, taskId, subjectRef: 'opaque-lead-ref-0001', subjectRevision: 0, taskETag: '"task.' + 'e'.repeat(43) + '"' } });
  const selected = { taskId, subjectRef: 'opaque-lead-ref-0001', subjectRevision: 0, ownerAppointmentId: command.requestSelectors.actorAppointmentId, taskETag: '"task.' + 'e'.repeat(43) + '"', draftETag: null, draftId: null, successorTaskId: taskId, successorTaskType: 'ACK_SOURCE_INTAKE_STOP_REQUEST' };
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
    requestSelectors: { ...command.requestSelectors, taskId, subjectRef: 'opaque-lead-ref-0001', subjectRevision: 0, taskETag: '"task.' + 'e'.repeat(43) + '"' } });
  const reopened = await BusinessJournal.open(path, runIdentity, () => {});
  await reopened.complete(commandId, 200, { ...receipt, commandId, receiptId: '00000000-0000-4000-8000-000000000153' },
    { taskId, subjectRef: 'opaque-lead-ref-0001', subjectRevision: 0, ownerAppointmentId: command.requestSelectors.actorAppointmentId, taskETag: '"task.' + 'e'.repeat(43) + '"', draftETag: null, draftId: null, successorTaskId: taskId, successorTaskType: 'RESOLVE_LEAD_ROUTING_GAP' });
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
      requestSelectors: index === 0 ? command.requestSelectors : { ...command.requestSelectors, taskId, subjectRef: 'opaque-lead-ref-0001', subjectRevision: 0, taskETag: '"task.' + 'e'.repeat(43) + '"' } });
    const immutable = factType === 'DECISION_RECORD';
    await journal.complete(commandId, step.endsWith('-draft') ? 200 : 201, { ...receipt, commandId, receiptId: `00000000-0000-4000-8000-${String(130 + index).padStart(12, '0')}`, resultFact: immutable ? { factType, factRef: String.fromCharCode(97 + index).repeat(43), digest: 'z'.repeat(43) } : { factType, factRef: String.fromCharCode(97 + index).repeat(43), revision: 0 } },
      { taskId, subjectRef: 'opaque-lead-ref-0001', subjectRevision: 0, ownerAppointmentId: '00000000-0000-4000-8000-000000000108', taskETag: '"task.' + 'e'.repeat(43) + '"', draftETag: step.endsWith('-draft') ? '"draft.' + 'f'.repeat(43) + '"' : null, draftId: step.endsWith('-draft') ? '00000000-0000-4000-8000-000000000140' : null, successorTaskId: taskId, successorTaskType: step === 'complete-submit' ? 'RESOLVE_LEAD_ROUTING_GAP' : step === 'routing-submit' ? 'ACK_SOURCE_INTAKE_STOP_REQUEST' : step === 'ack-submit' ? null : 'COMPLETE_LEAD_INGRESS' });
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
      requestSelectors: index === 0 ? command.requestSelectors : { ...command.requestSelectors, taskId, subjectRef: 'opaque-lead-ref-0001', subjectRevision: 0, taskETag: '"task.' + 'e'.repeat(43) + '"' } });
    await journal.complete(commandId, step.endsWith('-draft') ? 200 : 201, { ...receipt, commandId, receiptId: `00000000-0000-4000-8000-${String(170 + index).padStart(12, '0')}`, resultFact: { factType, factRef: String.fromCharCode(103 + index).repeat(43), revision: 0 } },
      { taskId, subjectRef: 'opaque-lead-ref-0001', subjectRevision: 0, ownerAppointmentId: command.requestSelectors.actorAppointmentId, taskETag: '"task.' + 'e'.repeat(43) + '"', draftETag: step.endsWith('-draft') ? '"draft.' + 'f'.repeat(43) + '"' : null, draftId: step.endsWith('-draft') ? '00000000-0000-4000-8000-000000000180' : null, successorTaskId: taskId, successorTaskType: index === 2 ? 'RESOLVE_LEAD_ROUTING_GAP' : 'COMPLETE_LEAD_INGRESS' });
  }
}
