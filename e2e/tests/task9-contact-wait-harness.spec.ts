import { expect, test } from '@playwright/test';
import { mkdtempSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { BusinessJournal, CONTACT_WAIT_CASE, type BusinessCommand, type BusinessStep, type BusinessRunIdentity } from '../fixtures/business-journal';
import { BusinessDispatchGate } from '../fixtures/business-session';
import { BUSINESS_PIN, IDENTITY_PREDECESSOR, IDENTITY_RESOURCE_STEPS, contactGrantResourceId, loadBusinessEnvironment } from '../fixtures/business-environment';
import { BusinessSetup } from '../fixtures/r1-business-setup';
import BusinessReporter from '../reporters/business-reporter';

const CASE = CONTACT_WAIT_CASE;
const predecessor = { runId: '9848f4ee-5612-49df-9e10-a8c40c09bd3d', journalSha256: '841a4d275bf97bdb832bbb138f70efbc310dbbe10f532d6c4dbb435380d52333' };
const identity: BusinessRunIdentity = { runId: '00000000-0000-4000-8000-000000000501', environmentDigest: 'a'.repeat(64), buildSha: BUSINESS_PIN.buildSha, predecessorRunId: predecessor.runId, predecessorSha256: predecessor.journalSha256 };
const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const taskTag = '"task.' + 't'.repeat(43) + '"', draftTag = '"draft.' + 'd'.repeat(43) + '"';
const steps: BusinessStep[] = ['capture-manual','assign-draft','assign-submit','contact-draft','contact-submit'];
function command(step: BusinessStep): BusinessCommand {
  const capture = step.startsWith('capture'), draft = step.endsWith('-draft');
  return { step, commandId: randomUUID(), method: draft ? 'PUT' : 'POST', path: capture ? '/api/v1/leads' : `/api/v1/tasks/${id(502)}/${draft ? 'draft' : 'commands/' + (step === 'assign-submit' ? 'assign-lead' : 'record-contact-result')}`, bodySha256: 'b'.repeat(64), actorScopeKey: 'ask1.' + 'c'.repeat(43),
    requestSelectors: { actorAppointmentId: id(503), taskId: capture ? null : id(502), subjectRef: capture ? null : 'opaque-synthetic-lead-0001', subjectRevision: capture ? null : 0, taskETag: capture ? null : taskTag, draftId: capture || draft ? null : id(504), draftRevision: capture || draft ? null : 0, draftDigest: capture || draft ? null : 'd'.repeat(43), draftETag: capture || draft ? null : draftTag, intendedValuesSha256: capture ? null : 'a'.repeat(64) } };
}
function selected() { return { taskId: id(502), subjectRef: 'opaque-synthetic-lead-0001', subjectRevision: 0, ownerAppointmentId: id(503), taskETag: taskTag, draftId: null, draftRevision: null, draftDigest: null, draftETag: null, draftValuesSha256: null, successorTaskId: null, successorTaskType: null, successorOwnerAppointmentId: null, successorSubjectRef: null, successorSubjectRevision: null, successorTaskETag: null }; }
function receipt(c: BusinessCommand) { const factType = c.step.endsWith('-draft') ? 'ACTION_DRAFT' : c.step === 'assign-submit' ? 'LEAD_ASSIGNMENT' : c.step === 'contact-submit' ? 'LEAD_CONTACT_RESULT' : 'LEAD'; return { commandId: c.commandId, receiptId: randomUUID(), completedAt: new Date().toISOString(), outcome: 'SUCCEEDED', resultFact: { factType, factRef: 'opaque-synthetic-result-0001', ...(factType === 'LEAD_CONTACT_RESULT' ? { digest: 'd'.repeat(43) } : { revision: 0 }) } }; }
async function open(path: string, guard = async () => {}) {
  return BusinessJournal.openContactWait(path, identity, guard);
}
test('waiting closed profile persists exactly five commands and one durable report', async () => {
  const folder = mkdtempSync(join(tmpdir(), 'task96p-journal-')), path = join(folder, 'task9-contact-wait-operation.json');
  const journal = await open(path);
  for (const step of steps) { const c = command(step); await journal.begin(c); await journal.complete(c.commandId, 200, receipt(c), selected()); }
  const reportPath = join(folder, `task9-contact-wait-${identity.runId}-${CASE}-${randomUUID()}.json`);
  await journal.finishStage(CASE as any, { ...identity, apiIdentity: 'e'.repeat(64), executedAt: new Date().toISOString(), caseIdentity: CASE as any, status: 'ACTIONS_VERIFIED', exitCode: null, reportPath, commands: journal.reportCommands(CASE as any), http: [], U01: 'NOT_EXECUTED', U02: 'NOT_EXECUTED', U03: 'NOT_EXECUTED' });
  const saved = JSON.parse(readFileSync(path, 'utf8'));
  expect(saved.commands.map((c: any) => c.step)).toEqual(['capture-manual','assign-draft','assign-submit','contact-draft','contact-submit']);
  expect(saved.stages).toHaveLength(1); expect(existsSync(reportPath)).toBe(true);
  await expect(open(path)).resolves.toBeDefined();
  await expect(journal.begin(command('review-draft'))).rejects.toThrow();
});
test('original public setup rejects waiting authority before loading or opening a journal', async () => {
  const prior = process.env.TASK9_BUSINESS_ACCEPTANCE; process.env.TASK9_BUSINESS_ACCEPTANCE = 'APPROVED_CONTACT_WAIT_CHAIN';
  let loads = 0;
  try { await expect(BusinessSetup.create({} as any, (async () => { loads++; throw new Error('transport'); }) as any)).rejects.toThrow(); expect(loads).toBe(0); }
  finally { if (prior === undefined) delete process.env.TASK9_BUSINESS_ACCEPTANCE; else process.env.TASK9_BUSINESS_ACCEPTANCE = prior; }
});
test('waiting journal forbids profile confusion, predecessor drift, reorder and sidecars before dispatch', async () => {
  for (const step of ['capture-auto','grant-contact-owner','review-draft','assign-draft'] as const) {
    const path = join(mkdtempSync(join(tmpdir(), 'task96p-order-')), 'task9-contact-wait-operation.json');
    const journal = await open(path), before = readFileSync(path), gate = new BusinessDispatchGate(journal), c = command(step); let sent = 0;
    gate.arm({ step: c.step, method: c.method, path: c.path, actorScopeKey: c.actorScopeKey, requestSelectors: c.requestSelectors, body: {} });
    await expect(gate.dispatch({ method: c.method, path: c.path, bodyBytes: Buffer.from('{}'), commandId: c.commandId, actorScopeKey: c.actorScopeKey }, async () => { sent++; })).rejects.toThrow();
    expect(sent).toBe(0); expect(readFileSync(path)).toEqual(before);
  }
  const folder = mkdtempSync(join(tmpdir(), 'task96p-mode-')), path = join(folder, 'task9-contact-wait-operation.json');
  await expect(open(join(folder, 'task9-business-operation.json'))).rejects.toThrow();
  await expect(BusinessJournal.open(path, identity, async () => {})).rejects.toThrow();
  await open(path); const before = readFileSync(path);
  for (const suffix of ['.pending','.completion.pending']) {
    const isolated = join(mkdtempSync(join(tmpdir(), 'task96p-sidecar-')), 'task9-contact-wait-operation.json');
    writeFileSync(isolated, before); writeFileSync(isolated + suffix, 'ambiguous'); await expect(open(isolated)).rejects.toThrow();
  }
});
test('waiting actual config discovery grants real matching only to the exact project option', () => {
  const folder = mkdtempSync(join(tmpdir(), 'task96p-config-')), configPath = join(folder, 'probe.config.cjs');
  writeFileSync(join(folder, 'benign.spec.cjs'), `const { test } = require(${JSON.stringify(resolve('node_modules/@playwright/test'))}); test('approved-local-contact-wait benign', async () => {});`);
  writeFileSync(configPath, `const loaded = require(${JSON.stringify(resolve('e2e/business.config.ts'))}); const actual = loaded.default ?? loaded; module.exports = { ...actual, testDir: __dirname, reporter: [['list']], projects: actual.projects.map(p => ({...p, testDir: __dirname, testMatch: 'benign.spec.cjs'})) };`);
  const run = (...args: string[]) => spawnSync(process.execPath, [resolve('node_modules/@playwright/test/cli.js'), 'test', '--config', configPath, ...args], { encoding: 'utf8', windowsHide: true, timeout: 30000 });
  const selected = run('--project=approved-local-contact-wait'); expect(selected.status, selected.stdout + selected.stderr).toBe(0); expect(selected.stdout).toContain('1 passed');
  for (const args of [[], ['--grep','approved-local-contact-wait'], ['--output','--project=approved-local-contact-wait'], ['--','--project=approved-local-contact-wait']]) {
    const result = run('--list', ...args); expect(result.status, result.stdout + result.stderr).toBe(0); expect(result.stdout).not.toContain('[approved-local-contact-wait]'); expect(result.stdout).toContain('Total: 1 test in 1 file');
  }
  const override = run('--project=approved-local-contact-wait','--reporter=list','--list'); expect(override.status).not.toBe(0); expect(override.stdout + override.stderr).toContain('T9_BUSINESS_BOUNDARY');
});
async function approval(action: () => Promise<void>, patch: Record<string, string | undefined> = {}) {
  const values = { TASK9_LOCAL_ACCEPTANCE: 'APPROVED_SYNTHETIC_ONLY', TASK9_BUSINESS_ACCEPTANCE: 'APPROVED_CONTACT_WAIT_CHAIN', TASK9_BUSINESS_RUN_ID: identity.runId, TASK9_BUSINESS_CONTINUE_RUN_ID: undefined, TASK9_BUSINESS_RESTART_SHA256: undefined, TASK9_BUSINESS_RECOVER_COMMAND_ID: undefined, TASK9_BUSINESS_RECOVER_JOURNAL_SHA256: undefined, ...patch };
  const saved = Object.fromEntries(Object.keys(values).map(key => [key, process.env[key]]));
  try { for (const [key, value] of Object.entries(values)) if (value === undefined) delete process.env[key]; else process.env[key] = value; await action(); }
  finally { for (const [key, value] of Object.entries(saved)) if (value === undefined) delete process.env[key]; else process.env[key] = value; }
}
test('waiting approval reaches the existing bridge and rejects restart recovery and wrong-run flags before transport', async () => {
  let reads = 0;
  const external = { runtime: mkdtempSync(join(tmpdir(), 'task96p-environment-')), toolchain: () => ({ browserVersion: BUSINESS_PIN.browserVersion, browserRevision: BUSINESS_PIN.browserRevision }), invokeLocalRuntime: async () => { reads++; throw new Error('synthetic external bridge'); } };
  await approval(async () => { await expect(loadBusinessEnvironment(external)).rejects.toThrow('synthetic external bridge'); expect(reads).toBe(1); });
  for (const patch of [{ TASK9_LOCAL_ACCEPTANCE: undefined }, { TASK9_BUSINESS_RUN_ID: 'invalid' }, { TASK9_BUSINESS_RUN_ID: '00000000-0000-7000-8000-000000000901' }, { TASK9_BUSINESS_RUN_ID: predecessor.runId }, { TASK9_BUSINESS_CONTINUE_RUN_ID: id(999) }, { TASK9_BUSINESS_RESTART_SHA256: '' }, { TASK9_BUSINESS_RECOVER_COMMAND_ID: '' }, { TASK9_BUSINESS_RECOVER_JOURNAL_SHA256: '' }]) {
    reads = 0; await approval(async () => { await expect(loadBusinessEnvironment(external)).rejects.toThrow(); expect(reads).toBe(0); }, patch);
  }
});
test('waiting authenticated journal projection accepts a canonical v7 contact resource selector', () => {
  // This is the loader's real projection after authentication, not a synthetic fixed-SHA proof.
  const value = contactGrantResourceId({ step: 'grant-contact-owner', selectors: { resourceId: '00000000-0000-7000-8000-000000000901' } });
  expect(value).toBe('00000000-0000-7000-8000-000000000901');
});
test('waiting authenticated journal projection rejects malformed resource UUID and selector confusion', () => {
  for (const resourceId of ['not-a-uuid','00000000-0000-7000-8000-00000000090z','00000000000070008000000000000901','00000000-0000-7000-8000-000000000901-extra',901,null]) {
    expect(() => contactGrantResourceId({ step: 'grant-contact-owner', selectors: { resourceId } })).toThrow();
  }
  expect(() => contactGrantResourceId({ step: 'capture-manual', selectors: { resourceId: id(901) } })).toThrow();
  expect(() => contactGrantResourceId({ step: 'grant-contact-owner', selectors: { resourceId: id(901), other: id(902) } })).toThrow();
});

function environmentFixture(folder: string) {
  const accounts = Object.fromEntries(['intake','supervisor','contact','delegate'].map((alias, index) => [alias, { username: `task9-local-${alias}`, password: 'synthetic-only', email: `${alias}@example.invalid`, firstName: alias, lastName: 'Synthetic', providerUserId: id(700 + index) }]));
  const saved = Object.fromEntries(Object.entries(accounts).map(([alias, { password: _, ...value }]) => [alias, value]));
  const snapshot = { ...BUSINESS_PIN, apiIdentity: 'e'.repeat(64), processIdentity: 'f'.repeat(64), releaseIdentity: 'a'.repeat(64) };
  const loaded = { ...snapshot, predecessor: { ...IDENTITY_PREDECESSOR, commandCount: 16, stageCount: 7, pendingCount: 0 }, original: { founder: { username: 'synthetic-founder', password: 'synthetic-only' }, unmapped: { username: 'synthetic-unmapped', password: 'synthetic-only' } }, credentials: { runId: 'synthetic', accounts }, operation: { runId: 'synthetic', stage: 'COMPLETE', accounts: saved, temporaryClientDeleted: true, temporaryCredentialRejected: true, temporaryTokenRejected: true, originalUsersUnchanged: true, realmPublicKeysUnchanged: true, directoryReadOnlyUnchanged: true, temporaryClientUserAndRolesAbsent: true, temporaryRecoveryContainerRemoved: true }, bootstrap: { tenantId: id(710), rootId: id(711), founderId: id(712), appointmentId: id(713) }, resources: Object.fromEntries(IDENTITY_RESOURCE_STEPS.map((step, index) => [step, id(800 + index)])) };
  return { loaded, dependencies: { runtime: folder, toolchain: () => ({ browserVersion: BUSINESS_PIN.browserVersion, browserRevision: BUSINESS_PIN.browserRevision }), async invokeLocalRuntime(mode: string) { return structuredClone(mode === 'snapshot' ? snapshot : mode === 'business' ? loaded : {}); } } };
}
test('waiting loader rejects absent changed partial or pending predecessor files without creating a new journal', async () => {
  for (const scenario of ['missing','changed','partial','pending','completion','identity']) {
    const folder = mkdtempSync(join(tmpdir(), 'task96p-predecessor-')), source = environmentFixture(folder), path = join(folder, 'task9-business-operation.json');
    if (scenario !== 'missing') writeFileSync(path, JSON.stringify({ identity: { ...identity, runId: predecessor.runId }, commands: scenario === 'partial' ? [] : Array.from({length: 15}, () => ({ status: 'CONFIRMED' })), stages: [] }));
    if (scenario === 'pending' || scenario === 'completion') writeFileSync(path + (scenario === 'pending' ? '.pending' : '.completion.pending'), 'ambiguous');
    if (scenario === 'identity') source.loaded.predecessor.journalSha256 = 'f'.repeat(64) as any;
    const before = existsSync(path) ? readFileSync(path) : null;
    await approval(async () => { await expect(loadBusinessEnvironment(source.dependencies)).rejects.toThrow(); });
    expect(existsSync(join(folder, 'task9-contact-wait-operation.json'))).toBe(false);
    if (before) expect(readFileSync(path)).toEqual(before);
  }
});

function browserTransport(failure = '') {
  const folder = mkdtempSync(join(tmpdir(), 'task96p-consumer-')), source = environmentFixture(folder), r = source.loaded.resources, b = source.loaded.bootstrap;
  const etag = '"identity.' + 'i'.repeat(43) + '"', contactGrant = id(900), forms = new Map<string, Record<string, unknown>>(), envelopes = new Map<string, any>();
  const empty = (waitingCount = 0) => ({ todaySummary: '合成服务端今日摘要', currentCard: null, nextSummaries: [], waitingCount, chatComposer: { mode: 'ACTION_DRAFT', targetTaskId: null, placeholder: '当前没有候选内容', enabled: false } });
  const aliases = ['intake','supervisor','contact','delegate'];
  const principals = aliases.map(alias => ({ id: r[`principal-${alias}`], displayName: alias, state: 'ACTIVE', etag }));
  const organizations = [{ id: b.rootId, parentOrganizationId: null, code: 'ROOT', displayName: 'ROOT', state: 'ACTIVE', etag }, { id: r.organization, parentOrganizationId: b.rootId, code: 'LOCAL_ACCEPTANCE', displayName: 'Local acceptance', state: 'ACTIVE', etag }];
  const appointments = aliases.map(alias => ({ id: r[`appointment-${alias}`], principal: { id: r[`principal-${alias}`], label: alias }, organization: { id: r.organization, label: 'Local acceptance' }, roleCode: alias === 'intake' ? 'INTAKE_OPERATOR' : alias === 'supervisor' ? 'ROUTING_SUPERVISOR' : 'CONTACT_OPERATOR', effectiveFrom: '2026-09-09T00:00:00Z', effectiveUntil: null, state: 'ACTIVE', etag }));
  const codes = ['LEAD_CAPTURE','LEAD_INGRESS_RESOLVE','LEAD_INGRESS_COMPLETE','SOURCE_INTAKE_REQUEST_ACK','LEAD_ASSIGN','LEAD_ROUTING_DECIDE','LEAD_VALIDITY_REVIEW'];
  const grant = (grantId: string, owner: string, authorityCode: string) => ({ id: grantId, appointment: { id: owner, label: 'synthetic' }, authorityCode, scopeOrganization: { id: b.rootId, label: 'ROOT' }, validFrom: '2026-09-09T00:00:00Z', validUntil: null, state: 'ACTIVE', etag });
  const grants = codes.map((code, i) => grant(r[`grant-${i < 4 ? 'intake' : 'supervisor'}-${i < 4 ? i : i - 4}`], r[`appointment-${i < 4 ? 'intake' : 'supervisor'}`], code));
  ['IDENTITY_PRINCIPAL_MANAGE','IDENTITY_ORGANIZATION_MANAGE','IDENTITY_APPOINTMENT_MANAGE','IDENTITY_AUTHORITY_MANAGE'].forEach((code, i) => grants.push(grant(id(910 + i), b.appointmentId, code)));
  grants.push(grant(contactGrant, r['appointment-contact'], 'SALES_CONTACT_OWNER'));
  if (failure === 'grant-id') grants[11].id = id(999);
  if (failure === 'grant-owner') grants[11].appointment.id = r['appointment-delegate'];
  if (failure === 'grant-expired') grants[11].validUntil = '2026-09-09T00:00:00Z' as any;
  if (failure === 'grant-scope') grants[11].scopeOrganization.id = r.organization;
  const wires: Array<{ method: string; path: string; body: any; commandId: string }> = [], ui: string[] = [];
  const environment: any = { ...BUSINESS_PIN, runtime: folder, environmentDigest: identity.environmentDigest, apiIdentity: 'e'.repeat(64), bootstrap: b, resources: r, accounts: { ...source.loaded.original, ...source.loaded.credentials.accounts }, waitingPredecessor: { ...predecessor, contactGrantId: contactGrant, assertUnchanged() {} }, verifyBrowser(actual: string) { expect(actual).toBe(BUSINESS_PIN.browserVersion); }, async assertUnchanged() {} };
  function card(type: 'ASSIGN_LEAD'|'CONTACT_LEAD') {
    const taskId = id(type === 'ASSIGN_LEAD' ? 920 : 921), actionCode = type === 'ASSIGN_LEAD' ? 'ASSIGN_LEAD' : 'RECORD_CONTACT_RESULT';
    const values = type === 'ASSIGN_LEAD' ? { ownerAppointmentId: '' } : { leadAssignmentId: id(922), leadAssignmentRevision: 0, contactChannelCode: '', resultCode: '', resultSummary: '' };
    const fields = (type === 'ASSIGN_LEAD' ? ['ownerAppointmentId'] : ['contactChannelCode','resultCode','resultSummary']).map(name => ({ name, label: name, control: name === 'resultSummary' ? 'TEXTAREA' : 'SELECT', required: true, readOnly: false, options: (name === 'ownerAppointmentId' ? [r['appointment-contact']] : name === 'resultCode' ? ['NOT_CONNECTED','SUSPECT_INVALID'] : name === 'contactChannelCode' ? ['EMAIL','PHONE'] : []).map(value => ({ value, label: value, disabled: false })) }));
    return { taskId, taskType: type, taskRevision: 0, versionStatus: 'CURRENT', subject: { subjectType: 'LEAD', subjectRef: 'opaque-synthetic-lead-0001', subjectRevision: type === 'ASSIGN_LEAD' ? 0 : 1, title: 'Synthetic lead' }, owner: { displayName: 'synthetic', organizationLabel: 'ROOT' }, businessPurpose: { code: type, label: type }, primaryCommand: { code: actionCode, label: actionCode, enabled: true }, expectedCompletionFact: type === 'ASSIGN_LEAD' ? 'LEAD_ASSIGNMENT' : 'LEAD_CONTACT_RESULT', sla: { code: 'SYNTHETIC', dueAt: '2026-09-13T02:30:00Z', status: 'ON_TRACK', timeHint: '合成时间' }, commandForm: { actionCode, schemaVersion: 1, values, fields }, actionDraft: null, preconditions: { taskETag: taskTag, subjectETag: '"subject.' + 's'.repeat(43) + '"', draftETag: null } };
  }
  function setCard(alias: string, value: any) { envelopes.set(alias, { ...empty(), currentCard: value, chatComposer: { mode: 'ACTION_DRAFT', targetTaskId: value.taskId, placeholder: '候选内容', enabled: true } }); }
  const browser: any = { version: () => BUSINESS_PIN.browserVersion, async newContext() {
    let alias = '', routeHandler: any, currentUrl = BUSINESS_PIN.origin + '/login'; const listeners: Record<string, Function> = {}, waiters: Array<{ predicate: Function; resolve: Function }> = [];
    const appointment = () => alias === 'founder' ? b.appointmentId : r[`appointment-${alias}`];
    const self = () => ({ state: 'READY', selectedAppointmentId: appointment(), selectedOnBehalfAppointmentId: null, appointmentChoices: [{ id: appointment() }], actorScopeKey: 'ask1.' + 'c'.repeat(43), canEnterWorkbench: alias !== 'founder', canEnterIdentityAdmin: alias === 'founder' });
    const envelope = () => envelopes.get(alias) ?? empty();
    const headers = () => ({ authorization: 'Bearer synthetic-transport', 'x-appointment-id': appointment() });
    async function emit(path: string, method = 'GET', body?: any, commandId = randomUUID()) {
      const requestHeaders = { ...headers(), ...(body ? { 'idempotency-key': commandId } : {}) };
      if (failure === 'actor' && method === 'POST' && path.includes('record-contact-result')) requestHeaders['x-appointment-id'] = id(999);
      const request: any = { url: () => (path.includes('openid-connect') ? BUSINESS_PIN.issuer : BUSINESS_PIN.origin) + path, method: () => method, headers: () => requestHeaders, allHeaders: async () => requestHeaders, postDataBuffer: () => body ? Buffer.from(JSON.stringify(body)) : null };
      let allowed = false; await routeHandler({ request: () => request, continue: async () => { allowed = true; }, abort: async () => {} });
      if (!allowed) throw new Error('synthetic blocked transport');
      let result: any, responseHeaders: any = { 'cache-control': 'no-store' };
      if (method === 'GET') {
        result = path === '/api/v1/session/context' ? self() : path === '/api/v1/workcards/current' ? structuredClone(envelope()) : { items: path.includes('/principals') ? principals : path.includes('/organizations') ? organizations : path.includes('/appointments') ? appointments : grants, nextCursor: null };
        if (path === '/api/v1/workcards/current') responseHeaders = { 'cache-control': 'private, no-cache', vary: 'Authorization', etag: '"wb.' + 'w'.repeat(43) + '"' };
      } else if (path.includes('openid-connect')) {
        result = { access_token: 'e30.' + Buffer.from(JSON.stringify({ iss: BUSINESS_PIN.issuer, preferred_username: environment.accounts[alias].username, sub: environment.accounts[alias].providerUserId })).toString('base64url') + '.synthetic' };
      } else {
        wires.push({ method, path, body, commandId }); const step = steps[wires.length - 1], c = { ...command(step), commandId }, received = receipt(c); responseHeaders.location = `/api/v1/commands/${commandId}/receipt`;
        if (path === '/api/v1/leads') { setCard('supervisor', card('ASSIGN_LEAD')); result = received; }
        else if (method === 'PUT') {
          const current = envelope().currentCard;
          current.actionDraft = { draftId: id(930 + wires.length), draftRevision: 0, actionCode: current.commandForm.actionCode, schemaVersion: 1, values: body.values, digest: 'd'.repeat(43), updatedAt: '2026-09-12T00:00:00Z', editable: true };
          current.preconditions.draftETag = draftTag; result = { draft: current.actionDraft, preconditions: current.preconditions, receipt: received }; responseHeaders.etag = draftTag;
        } else { result = received; envelopes.set(alias, empty()); if (path.includes('assign-lead')) setCard('contact', card('CONTACT_LEAD')); else {
          const waiting = empty(failure === 'wait-zero' ? 0 : failure === 'wait-two' ? 2 : 1);
          if (failure === 'composer') waiting.chatComposer.enabled = true;
          if (failure === 'composer-target') (waiting.chatComposer as any).targetTaskId = id(999);
          if (failure === 'next') (waiting.nextSummaries as any[]).push({ taskId: id(999), businessPurpose: { code: 'CONTACT_LEAD', label: 'Contact' }, priority: 'NORMAL', timeHint: 'next' });
          if (failure === 'current') (waiting as any).currentCard = card('CONTACT_LEAD');
          envelopes.set(alias, waiting); if (failure === 'receipt') result.commandId = id(999);
          if (failure === 'unknown') throw new Error('synthetic response lost');
        } }
      }
      const response: any = { url: request.url, request: () => request, status: () => 200, allHeaders: async () => responseHeaders, json: async () => structuredClone(result) };
      listeners.response?.(response);
      for (const waiting of [...waiters]) if (waiting.predicate(response)) { waiters.splice(waiters.indexOf(waiting), 1); waiting.resolve(response); }
      return { status: 200, headers: responseHeaders, body: result };
    }
    function locator(selector: string, role?: string, name?: string): any {
      const present = () => selector === 'article.current-card' || selector === '#primary-confirm' ? !!envelope().currentCard : selector === '.next-summary' ? envelope().nextSummaries.length > 0 : role === 'heading' ? name === (envelope().waitingCount > 0 && failure !== 'heading' ? '当前无可处理责任，另有等待事项' : '当前暂无可处理责任') : true;
      const disabled = () => selector === '#primary-confirm' ? !envelope().currentCard?.actionDraft : name === '保存候选' && !envelope().currentCard;
      return { _apiName: 'Locator', first: () => locator(selector, role, name), count: async () => present() ? 1 : 0,
        _expect: async (expression: string, options: any) => {
          ui.push(selector || `${role}:${name}`); const text = selector === '.today-summary p' ? (failure === 'summary' ? '错误摘要' : envelope().todaySummary) : selector === '.waiting-count > span' ? `等待 ${failure === 'dom-count' ? 11 : envelope().waitingCount}` : '';
          const matches = expression === 'to.have.count' ? (present() ? 1 : 0) === options.expectedNumber : expression.includes('text') ? options.expectedText.every((x: any) => options.expectedText[0].matchSubstring ? text.includes(x.string) : text === x.string) : expression === 'to.be.disabled' ? disabled() : expression === 'to.be.enabled' ? !disabled() : present();
          return { matches, received: text, log: [], timedOut: !matches };
        },
        fill: async (value: string) => { if (selector.includes('username')) alias = value.replace('task9-local-', '').replace('synthetic-', ''); else if (!selector.includes('password')) { const values = forms.get(alias) ?? {}; values[selector === '#chat-candidate' ? 'resultSummary' : selector.replace('#candidate-', '')] = value; forms.set(alias, values); } },
        selectOption: async (value: string) => { const values = forms.get(alias) ?? {}; values[selector.replace('#candidate-', '')] = value; forms.set(alias, values); },
        click: async () => {
          if (selector.includes('[type="submit"]')) { await emit('/protocol/openid-connect/token', 'POST'); await emit('/api/v1/session/context'); }
          else if (name === '确认本次身份') { currentUrl = BUSINESS_PIN.origin + (alias === 'founder' ? '/admin/identity/principals' : '/workbench'); if (alias !== 'founder') await emit('/api/v1/workcards/current'); }
          else if (name === '刷新当前责任') await emit('/api/v1/workcards/current');
          else if (name === '刷新') await emit('/api/v1/admin/identity/principals');
          else if (name === '保存候选') { const c = envelope().currentCard; await emit(`/api/v1/tasks/${c.taskId}/draft`, 'PUT', { actionCode: c.commandForm.actionCode, schemaVersion: 1, values: { ...c.commandForm.values, ...forms.get(alias) } }); }
          else if (selector === '#primary-confirm') { const c = envelope().currentCard, draft = c.actionDraft; await emit(`/api/v1/tasks/${c.taskId}/commands/${c.taskType === 'ASSIGN_LEAD' ? 'assign-lead' : 'record-contact-result'}`, 'POST', { ...draft.values, draftId: draft.draftId, expectedDraftRevision: draft.draftRevision, draftDigest: draft.digest }); await emit('/api/v1/workcards/current'); }
        } };
    }
    const page: any = { goto: async () => {}, waitForURL: async () => {}, url: () => currentUrl, locator, getByRole: (role: string, options: any = {}) => locator('', role, options.name), on: (event: string, callback: Function) => { listeners[event] = callback; }, waitForResponse: (predicate: Function) => new Promise(resolve => waiters.push({ predicate, resolve })), evaluate: async (_fn: unknown, args: any) => emit(new URL(args.path).pathname + new URL(args.path).search, args.init.method ?? 'GET', args.init.body, args.init.commandId) };
    // These external pages model the initial identity confirmation screen.
    const originalRole = page.getByRole; page.getByRole = (role: string, options: any = {}) => { const result = originalRole(role, options); if (role === 'main') result.count = async () => currentUrl.endsWith('/login') ? 0 : 1; return result; };
    return { route: async (_pattern: string, handler: any) => { routeHandler = handler; }, newPage: async () => page, close: async () => {}, request: { get: async (_url: string, options: any) => { expect(options.maxRedirects).toBe(0); expect(options.headers['X-Appointment-Id']).toBe(appointment()); return { status: () => 200, headers: () => ({ 'cache-control': 'private, no-cache', vary: 'Authorization', etag: '"wb.' + 'w'.repeat(43) + '"' }), text: async () => JSON.stringify(envelope()) }; } } };
  } };
  return { folder, browser, environment, wires, ui };
}
test('actual waiting setup executes five armed writes and completes only after server waiting envelope and DOM', async () => {
  const transport = browserTransport();
  await approval(async () => { const setup = await BusinessSetup.createContactWait(transport.browser, async () => transport.environment); await setup.stage(CASE as any); await setup.close(); });
  const data = JSON.parse(readFileSync(join(transport.folder, 'task9-contact-wait-operation.json'), 'utf8'));
  expect(data.commands.map((x: any) => x.status)).toEqual(Array(5).fill('CONFIRMED')); expect(data.stages).toHaveLength(1);
  expect(transport.wires.map(x => x.method)).toEqual(['POST','PUT','POST','PUT','POST']);
  expect(transport.wires[0].body.sourceRecordKey).toBe(`task96p-${identity.runId}-manual`); expect(transport.wires[0].body.email).toBe(`task96p-${identity.runId}-manual@example.invalid`);
  expect(transport.wires[4].body).toMatchObject({ resultCode: 'NOT_CONNECTED', contactChannelCode: 'EMAIL', resultSummary: 'Task 9.6p synthetic contact not connected.' });
  expect(transport.ui).toContain('heading:当前无可处理责任，另有等待事项'); expect(transport.ui).toContain('.today-summary p'); expect(transport.ui).toContain('.waiting-count > span');
});
for (const failure of ['wait-zero','wait-two','composer','composer-target','next','current','heading','summary','dom-count','receipt','actor','unknown']) test(`waiting setup blocks stage completion and further writes for ${failure}`, async () => {
  const transport = browserTransport(failure);
  await approval(async () => { const setup = await BusinessSetup.createContactWait(transport.browser, async () => transport.environment); await expect(setup.stage(CASE)).rejects.toThrow(); await expect(setup.journal.begin(command('review-draft'))).rejects.toThrow(); await setup.close(); });
  const data = JSON.parse(readFileSync(join(transport.folder, 'task9-contact-wait-operation.json'), 'utf8'));
  expect(data.stages).toHaveLength(0); expect(data.commands).toHaveLength(failure === 'actor' ? 4 : 5);
  if (failure !== 'actor') expect(data.commands[4].status).toBe('PENDING');
  expect(transport.wires).toHaveLength(failure === 'actor' ? 4 : 5);
});
for (const failure of ['grant-id','grant-owner','grant-expired','grant-scope']) test(`waiting setup rejects original contact ${failure} before first write`, async () => {
  const transport = browserTransport(failure);
  await approval(async () => { const setup = await BusinessSetup.createContactWait(transport.browser, async () => transport.environment); await expect(setup.stage(CASE)).rejects.toThrow(); await setup.close(); });
  expect(transport.wires).toHaveLength(0); const data = JSON.parse(readFileSync(join(transport.folder, 'task9-contact-wait-operation.json'), 'utf8')); expect(data.commands).toEqual([]); expect(data.stages).toEqual([]);
});
test('waiting journal excludes concurrent duplicate gate dispatch and preserves unknown original key', async () => {
  let release!: () => void, entered!: () => void, pause = false;
  const held = new Promise<void>(resolve => { release = resolve; }), observed = new Promise<void>(resolve => { entered = resolve; });
  const path = join(mkdtempSync(join(tmpdir(), 'task96p-busy-')), 'task9-contact-wait-operation.json');
  const journal = await open(path, async () => { if (pause) { entered(); await held; } }); pause = true;
  const gate = new BusinessDispatchGate(journal), c = command('capture-manual');
  gate.arm({ step: c.step, method: c.method, path: c.path, body: {}, actorScopeKey: c.actorScopeKey, requestSelectors: c.requestSelectors });
  const wire = { method: c.method, path: c.path, bodyBytes: Buffer.from('{}'), commandId: c.commandId, actorScopeKey: c.actorScopeKey }; let sent = 0;
  const dispatch = gate.dispatch(wire, async () => { sent++; }); await observed;
  await expect(journal.begin(command('capture-manual'))).rejects.toThrow();
  await expect(gate.dispatch(wire, async () => { sent++; })).rejects.toThrow(); release(); await expect(dispatch).rejects.toThrow();
  expect(sent).toBe(0); expect(journal.pending()?.commandId).toBe(c.commandId);
  const reopened = await open(path); await expect(reopened.begin(command('capture-manual'))).rejects.toThrow();
});
test('waiting constructors refuse old mode old stage and other-run journal without modifying files', async () => {
  const transport = browserTransport(); let loads = 0;
  await approval(async () => { await expect(BusinessSetup.createContactWait(transport.browser, async () => { loads++; return transport.environment; })).rejects.toThrow(); }, { TASK9_BUSINESS_ACCEPTANCE: 'APPROVED_SIX_CARD_CHAIN' }); expect(loads).toBe(0);
  await approval(async () => { const setup = await BusinessSetup.createContactWait(transport.browser, async () => transport.environment); const before = readFileSync(setup.journal.path); await expect(setup.stage('T9-W01-ingress-routing-ack')).rejects.toThrow(); expect(readFileSync(setup.journal.path)).toEqual(before); await setup.close(); });
  const path = join(transport.folder, 'task9-contact-wait-operation.json');
  for (const patch of [{ runId: id(999) }, { predecessorRunId: IDENTITY_PREDECESSOR.runId }, { predecessorSha256: 'f'.repeat(64) }]) await expect(BusinessJournal.openContactWait(path, { ...identity, ...patch }, async () => {})).rejects.toThrow();
});
test('waiting report partial completion tampering and completion intent cannot be adopted', async () => {
  const folder = mkdtempSync(join(tmpdir(), 'task96p-completion-')), path = join(folder, 'task9-contact-wait-operation.json');
  const journal = await open(path);
  for (const step of steps) { const c = command(step); await journal.begin(c); await journal.complete(c.commandId, 200, receipt(c), selected()); }
  const reportPath = join(folder, `task9-contact-wait-${identity.runId}-${CASE}-${randomUUID()}.json`);
  const evidence = { ...identity, apiIdentity: 'e'.repeat(64), executedAt: new Date().toISOString(), caseIdentity: CASE, status: 'ACTIONS_VERIFIED' as const, exitCode: null, reportPath, commands: journal.reportCommands(CASE), http: [], U01: 'NOT_EXECUTED' as const, U02: 'NOT_EXECUTED' as const, U03: 'NOT_EXECUTED' as const };
  await journal.finishStage(CASE, evidence); const completed = readFileSync(path);
  writeFileSync(reportPath, JSON.stringify({ ...evidence, commands: [] })); await expect(open(path)).rejects.toThrow(); expect(readFileSync(path)).toEqual(completed);
  writeFileSync(reportPath, JSON.stringify(evidence));
  const partial = JSON.parse(completed.toString()); partial.commands.pop(); writeFileSync(path, JSON.stringify(partial)); await expect(open(path)).rejects.toThrow();
  const isolated = join(mkdtempSync(join(tmpdir(), 'task96p-intent-')), 'task9-contact-wait-operation.json'), unfinished = await open(isolated);
  for (const step of steps) { const c = command(step); await unfinished.begin(c); await unfinished.complete(c.commandId, 200, receipt(c), selected()); }
  const failedEvidence = { ...evidence, commands: unfinished.reportCommands(CASE), reportPath: join(resolve(isolated, '..'), `task9-contact-wait-${identity.runId}-${CASE}-${randomUUID()}.json`) };
  await expect(unfinished.finishStage(CASE, failedEvidence, { writeEvidence: () => { throw new Error('synthetic interrupted evidence write'); } })).rejects.toThrow();
  expect(existsSync(isolated + '.completion.pending')).toBe(true); await expect(open(isolated)).rejects.toThrow();
});
test('waiting reporter emits only its closed case and safe failure code', () => {
  const reporter = new BusinessReporter(), output: string[] = [], original = process.stdout.write;
  try {
    process.stdout.write = ((chunk: any) => { output.push(String(chunk)); return true; }) as any;
    reporter.onStdOut(); reporter.onStdErr(); reporter.onTestEnd({ title: CASE } as any, { status: 'failed', error: { message: 'synthetic-private-error' } } as any);
    reporter.onTestEnd({ title: 'synthetic-private-title' } as any, { status: 'failed' } as any);
  } finally { process.stdout.write = original; }
  expect(output.join('')).toBe('T9-W09-contact-wait-preparation phase=test status=failed code=T9_CONTACT_WAIT_FAILURE\nT9-BUSINESS-OFFLINE phase=test status=failed code=T9_BUSINESS_FAILURE_CLOSED\n');
});
