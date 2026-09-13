import { chromium, expect, test, type Page } from '@playwright/test';
import * as environments from '../fixtures/business-environment';
import { mkdtempSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { runReadOnlyWaiting } from '../fixtures/readonly-waiting';
import ReadOnlyWaitingReporter from '../reporters/readonly-waiting-reporter';
import { validateWorkbenchCache } from '../fixtures/workbench-cache';
const envelope = () => ({ todaySummary: '今日等待事项', currentCard: null, nextSummaries: [], waitingCount: 1, chatComposer: { mode: 'ACTION_DRAFT', targetTaskId: null, placeholder: '暂无可处理责任', enabled: false } });
const actor = 'ask1.' + 'a'.repeat(43), tag = '"wb.' + 'b'.repeat(43) + '"';
const headers = { 'Cache-Control': 'NO-CACHE, private', Vary: 'AUTHORIZATION', ETag: tag };
test('shared cache consumes real envelope and retains it through matching same Actor 304', () => {
  const consume = validateWorkbenchCache;
  const first = consume({ status: 200, headers, body: envelope(), actorScopeKey: actor, generation: 1 });
  const second = consume({ status: 304, headers, body: '', requestHeaders: { 'If-None-Match': tag }, actorScopeKey: actor, generation: 2 }, first);
  expect(second.envelope).toEqual(envelope()); expect(second.etag).toBe(tag);
});
test('shared cache refuses unsupported 304, wrong Actor, weak tag, body, headers and malformed 200', () => {
  const consume = validateWorkbenchCache, first = consume({ status: 200, headers, body: envelope(), actorScopeKey: actor, generation: 1 });
  const request = { status: 304, headers, body: '', requestHeaders: { 'if-none-match': tag }, actorScopeKey: actor, generation: 2 };
  expect(() => consume(request)).toThrow();
  for (const patch of [{ actorScopeKey: 'different' }, { generation: 1 }, { body: '{}' }, { requestHeaders: {} }, { headers: { ...headers, ETag: 'W/' + tag } }, { headers: { ...headers, Vary: 'Cookie' } }, { headers: { ...headers, 'Cache-Control': 'public, no-cache' } }, { status: 200, body: { ...envelope(), waitingCount: -1 } }]) expect(() => consume({ ...request, ...patch }, first)).toThrow();
});

test('readonly loader refuses absent approval before external bridge or credential access', async () => {
  const loader = environments.loadReadOnlyWaitingEnvironment;
  let invoked = false;
  await expect(loader({ runtime: mkdtempSync(join(tmpdir(), 'task96q-flags-')), toolchain() { invoked = true; throw Error(); }, async invokeLocalRuntime() { invoked = true; throw Error(); } })).rejects.toThrow();
  expect(invoked).toBe(false);
});

function fakeTransport(failure = '', waitingPage?: Page) {
  const folder = mkdtempSync(join(tmpdir(), 'task96q-flow-')), wall = Date.parse('2026-09-12T02:00:00Z');
  let elapsed = 0, mountedAt = -1, automatic = 0, current = 0, renewed = false, manual = false, closed = false, opened = false, drift = false;
  let route: any; const listeners: Record<string, Function> = {}, forwarded: string[] = [];
  const appointment = '00000000-0000-7000-8000-000000000111';
  const bearer = () => 'Bearer e30.' + Buffer.from(JSON.stringify({ exp: Math.floor(wall / 1000) + (failure === 'too-late' ? 1200 : renewed ? 600 : 240) })).toString('base64url') + (renewed && failure !== 'same-bearer' ? '.renewed' : '.initial');
  let initialBearer = bearer();
  async function emit(path: string, method = 'GET', refresh = false) {
    const isCurrent = path === '/api/v1/workcards/current', isSelf = path === '/api/v1/session/context';
    const reqHeaders = { authorization: failure === 'same-bearer' && manual ? initialBearer : bearer(), 'x-appointment-id': failure === 'wrong-appointment' && isCurrent ? '00000000-0000-7000-8000-000000000999' : appointment, ...(isCurrent && current > 0 ? { 'if-none-match': tag } : {}) };
    const url = path.startsWith('/realms/') ? new URL(environments.BUSINESS_PIN.issuer).origin + path : environments.BUSINESS_PIN.origin + path;
    const request: any = { url: () => url, method: () => method, allHeaders: async () => reqHeaders, serviceWorker: () => failure === 'service-worker' ? {} : null, postData: () => failure === 'token-password' ? 'grant_type=password' : refresh ? 'grant_type=refresh_token' : 'grant_type=authorization_code' };
    let allowed = false; await route({ request: () => request, continue: async () => { forwarded.push(method + ' ' + path); allowed = true; }, abort: async () => {} });
    if (!allowed) return;
    const status = failure === 'token-failed' && path.includes('/token') && !refresh ? 500 : isCurrent && failure === 'no-prior-304' ? 304 : isCurrent && current++ > 0 && !['no-304','changed-envelope'].includes(failure) ? 304 : 200;
    const body = isCurrent ? { ...envelope(), ...(failure === 'changed-envelope' && current > 1 ? { todaySummary: 'changed summary' } : {}) } : isSelf ? { state: 'READY', selectedAppointmentId: appointment, selectedOnBehalfAppointmentId: null, appointmentChoices: [{ id: appointment, label: '原合成任职' }], delegatedAppointmentChoices: [], actorScopeKey: drift ? 'ask1.' + 'z'.repeat(43) : actor, canEnterWorkbench: true, canEnterIdentityAdmin: false, displayName: '原合成联系人' } : {};
    const response: any = { url: () => url, request: () => request, status: () => status, allHeaders: async () => {
      if (isCurrent && failure === 'late-identity' && manual) { drift = true; await emit('/api/v1/session/context'); }
      return isCurrent ? failure === 'bad-cache' && current === 2 ? { ...headers, Vary: 'Cookie' } : headers : { 'Cache-Control': 'no-store' };
    }, json: async () => structuredClone(body), text: async () => {
      if (status === 304 && failure === 'unreadable-304') throw Error('Response body is unavailable for redirect responses');
      return status === 304 ? '' : JSON.stringify(body);
    } };
    listeners.response?.(response);
  }
  function locator(selector: string): any {
    if (waitingPage && selector.startsWith('.waiting-count')) return waitingPage.locator(selector);
    const visible = () => !(failure === 'wrong-ui' && selector.includes('当前无可处理'));
    return { first: () => locator(selector), isVisible: async () => visible(), waitFor: async () => {}, count: async () => selector === 'article.current-card' || selector === '.next-summary' ? 0 : 1,
      textContent: async () => selector === '.today-summary p' ? envelope().todaySummary : selector === '.waiting-count > span' ? (failure === 'wait11' ? '等待 11' : '等待 1') : selector === '.session-actions > span' ? '原合成联系人 · 原合成任职' : selector === '.choice-account > span' ? '原合成联系人' : '',
      inputValue: async () => appointment,
      fill: async () => {},
      click: async () => {
        if (selector.includes('[type="submit"]')) { await emit('/realms/local-r1/protocol/openid-connect/token','POST'); await emit('/api/v1/session/context'); }
        if (selector === '确认本次身份') { mountedAt = elapsed; await emit('/api/v1/workcards/current'); if (failure === 'mutation') await emit('/api/v1/leads','POST'); if (failure === 'unknown-api') await emit('/api/v1/admin/identity/principals'); }
        if (selector === '刷新当前责任') { manual = true; if (failure !== 'no-refresh') { renewed = true; await emit('/realms/local-r1/protocol/openid-connect/token','POST',true); } await emit('/api/v1/workcards/current'); }
      } };
  }
  const page: any = { evaluate: async () => true, on: (name: string, cb: Function) => { listeners[name] = cb; }, goto: async () => {}, waitForURL: async () => {}, locator, getByRole: (_role: string, options: any) => locator(options.name), getByText: (name: string) => locator(name) };
  const browser: any = { version: () => environments.BUSINESS_PIN.browserVersion, newContext: async () => { opened = true; return { route: async (_pattern: string, cb: any) => { route = cb; }, newPage: async () => page, on: (name: string, cb: Function) => { listeners[name] = cb; } }; }, close: async () => { closed = true; } };
  const clock = { now: () => elapsed, wallNow: () => wall + elapsed, sleep: async (ms: number) => {
    expect(ms).toBeGreaterThan(0); expect(ms).toBeLessThanOrEqual(30_000); elapsed += ms;
    while (mountedAt >= 0 && automatic < (failure === 'seventh' ? 7 : 6) && elapsed >= mountedAt + (automatic + 1) * 30_000) { automatic++; await emit('/api/v1/workcards/current'); }
    if (manual && failure === 'budget-reset' && elapsed >= 270_000 && automatic === 6) { automatic++; await emit('/api/v1/workcards/current'); }
  } };
  const environment: any = { ...environments.BUSINESS_PIN, environmentDigest: 'c'.repeat(64), apiIdentity: 'd'.repeat(64), outputDirectory: folder,
    readonlyProof: { ...environments.READONLY_WAITING_PIN }, accounts: { contact: { username: 'synthetic-contact', password: 'ephemeral' } }, resources: { 'appointment-contact': appointment },
    verifyBrowser(version: string) { expect(version).toBe(environments.BUSINESS_PIN.browserVersion); }, async assertUnchanged() { if (closed && failure === 'final-guard') throw Error('private guard detail'); } };
  return { browser, environment, clock, folder, forwarded, closed: () => closed, opened: () => opened };
}
async function runTransport(failure = '') {
  const transport = fakeTransport(failure);
  const result = await runReadOnlyWaiting(transport.browser, transport.environment, { clock: transport.clock });
  const saved = JSON.parse(readFileSync(result.reportPath, 'utf8'));
  expect(saved).toEqual(result); expect(transport.closed()).toBe(true);
  expect(readdirSync(transport.folder)).toHaveLength(1);
  expect(JSON.stringify(saved)).not.toMatch(/Bearer |ephemeral|原合成联系人|ask1\.|private guard detail/);
  return { result, transport };
}
test('actual readonly flow preserves six automatic reads, same Actor 304, natural renewal and quiet mounted page', async () => {
  const { result } = await runTransport();
  expect(result.status).toBe('PASSED_READ_ONLY_SUBSCENARIO');
  expect(result.counts).toMatchObject({ current: 8, automatic: 6, manual: 1, notModified: 7, refresh: 1, blockedRequests: 0 });
  expect(result.quietDurationMs).toBeGreaterThanOrEqual(60_000);
});
test('actual readonly consumer handles pinned Playwright unreadable 304 body through the prior parsed cache', async () => {
  const { result } = await runTransport('unreadable-304'); expect(result.status).toBe('PASSED_READ_ONLY_SUBSCENARIO'); expect(result.counts.notModified).toBe(7);
});
async function withSyntheticWaiting(count: number, consume: (page: Page) => Promise<void>) {
  const browser = await chromium.launch({ headless: true });
  try {
    expect(browser.version()).toBe(environments.BUSINESS_PIN.browserVersion);
    const context = await browser.newContext({ offline: true, serviceWorkers: 'block', ignoreHTTPSErrors: false });
    await context.route('**/*', route => route.abort());
    const page = await context.newPage();
    await page.setContent(`<meta http-equiv="Content-Security-Policy" content="default-src 'none'"><section aria-label="后续责任与等待"><p class="waiting-count"><svg aria-hidden="true"></svg><span>等待 ${count}</span><span class="muted">当前无需操作</span></p></section>`);
    await consume(page);
  } finally { await browser.close(); }
}
test('synthetic DOM pinned locator refuses the original strict selector matching both waiting spans', async () => {
  await withSyntheticWaiting(1, async page => {
    expect(await page.locator('.waiting-count > span').count()).toBe(2);
    await expect(page.locator('.waiting-count > span').textContent({ timeout: 1000 })).rejects.toThrow(/strict mode violation/);
  });
});
test('synthetic DOM actual readonly consumer accepts waiting 1 alongside the second explanatory span', async () => {
  await withSyntheticWaiting(1, async page => {
    const transport = fakeTransport('', page);
    const result = await runReadOnlyWaiting(transport.browser, transport.environment, { clock: transport.clock });
    expect(result.status).toBe('PASSED_READ_ONLY_SUBSCENARIO');
    expect(result.counts).toMatchObject({ current: 8, automatic: 6, manual: 1 });
  });
});
test('synthetic DOM actual readonly consumer refuses waiting 11 alongside the second explanatory span', async () => {
  await withSyntheticWaiting(11, async page => {
    const transport = fakeTransport('', page);
    const result = await runReadOnlyWaiting(transport.browser, transport.environment, { clock: transport.clock });
    expect(result.status).toBe('FAILED'); expect(result.failureStep).toBe('INITIAL_UI');
    expect(result.counts).toMatchObject({ current: 1, automatic: 0, manual: 0 });
  });
});
for (const failure of ['seventh','budget-reset','wrong-ui','wait11','same-bearer','no-refresh','no-304','mutation','final-guard','token-password','token-failed','too-late','wrong-appointment','service-worker','no-prior-304','changed-envelope','bad-cache','late-identity','unknown-api']) test(`actual readonly flow cannot pass ${failure}`, async () => {
  const { result, transport } = await runTransport(failure);
  expect(result.status).not.toBe('PASSED_READ_ONLY_SUBSCENARIO');
  if (failure === 'mutation') expect(transport.forwarded).not.toContain('POST /api/v1/leads');
  if (failure === 'token-password') expect(transport.forwarded).not.toContain('POST /realms/local-r1/protocol/openid-connect/token');
  if (failure === 'unknown-api') expect(transport.forwarded).not.toContain('GET /api/v1/admin/identity/principals');
});

test('readonly refusal at due boundary writes NOT_EXECUTED before opening any browser context', async () => {
  const transport = fakeTransport(); transport.clock.wallNow = () => Date.parse(environments.READONLY_WAITING_PIN.dueAt) - 599_999;
  let launches = 0;
  const result = await runReadOnlyWaiting(async () => { launches++; return transport.browser; }, transport.environment, { clock: transport.clock });
  expect(result.status).toBe('NOT_EXECUTED'); expect(result.counts.current).toBe(0); expect(transport.opened()).toBe(false);
  expect(launches).toBe(0);
});
test('actual readonly lifecycle consumes browser launch rejection and publishes guarded environment-bound FAILED evidence', async () => {
  const transport = fakeTransport(), lifecycle: string[] = [];
  transport.environment.assertUnchanged = async () => { lifecycle.push('guard'); };
  const launch = async () => { lifecycle.push('launch'); throw Error('private browser launch detail'); };
  const result = await runReadOnlyWaiting(launch, transport.environment, { clock: transport.clock });
  expect(lifecycle).toEqual(['guard','launch','guard']);
  expect(result.status).toBe('FAILED'); expect(result.failureStep).toBe('PRECHECK');
  expect(result).toMatchObject({ buildSha: environments.BUSINESS_PIN.buildSha, environmentDigest: 'c'.repeat(64), apiIdentity: 'd'.repeat(64), counts: { current: 0, automatic: 0, manual: 0 }, checks: { environmentUnchanged: true, journalUnchanged: true, checkpointUnchanged: true } });
  expect(transport.opened()).toBe(false); expect(transport.forwarded).toEqual([]);
  expect(readdirSync(transport.folder)).toEqual([result.reportPath.split(/[\\/]/).pop()]);
  expect(JSON.parse(readFileSync(result.reportPath,'utf8'))).toEqual(result);
  expect(readFileSync(result.reportPath,'utf8')).not.toContain('private browser launch detail');
});
test('exclusive report collision preserves original bytes and cannot publish a replacement pass', async () => {
  const transport = fakeTransport(), id = '00000000-0000-4000-8000-000000000999';
  const path = join(transport.folder, `task96q-readonly-waiting-${id}.json`); writeFileSync(path, 'original evidence');
  await expect(runReadOnlyWaiting(transport.browser, transport.environment, { clock: transport.clock, reportId: () => id })).rejects.toThrow();
  expect(readFileSync(path, 'utf8')).toBe('original evidence'); expect(transport.closed()).toBe(true);
});
async function approved<T>(operation: () => Promise<T>, patch: Record<string,string|undefined> = {}): Promise<T> {
  const values: Record<string,string|undefined> = { TASK9_LOCAL_ACCEPTANCE: 'APPROVED_SYNTHETIC_ONLY', TASK9_READONLY_WAITING: 'APPROVED_EXISTING_WAIT_ONLY', ...patch };
  const previous = Object.fromEntries(Object.keys(values).map(key => [key, process.env[key]]));
  try { for (const [key,value] of Object.entries(values)) { if (value === undefined) delete process.env[key]; else process.env[key] = value; } return await operation(); }
  finally { for (const [key,value] of Object.entries(previous)) { if (value === undefined) delete process.env[key]; else process.env[key] = value; } }
}
test('readonly approval reaches only the existing bridge while every writing or debug flag is refused before credentials', async () => {
  const folder = mkdtempSync(join(tmpdir(), 'task96q-approval-')); let reads = 0;
  const external = { runtime: folder, toolchain: () => ({ browserVersion: environments.BUSINESS_PIN.browserVersion, browserRevision: environments.BUSINESS_PIN.browserRevision }), async invokeLocalRuntime() { reads++; throw Error('synthetic external bridge'); } };
  await approved(async () => { await expect(environments.loadReadOnlyWaitingEnvironment(external)).rejects.toThrow('synthetic external bridge'); expect(reads).toBe(1); });
  for (const key of ['TASK9_BUSINESS_ACCEPTANCE','TASK9_BUSINESS_RUN_ID','TASK9_BUSINESS_CONTINUE_RUN_ID','TASK9_BUSINESS_RESTART_SHA256','TASK9_BUSINESS_RECOVER_COMMAND_ID','TASK9_BUSINESS_RECOVER_JOURNAL_SHA256','TASK9_RUN_ID','TASK9_CONTINUE_RUN_ID','TASK9_RECOVER_COMMAND_ID','DEBUG','PWDEBUG','PW_TEST_DEBUG','NODE_TLS_REJECT_UNAUTHORIZED']) {
    reads = 0; await approved(async () => { await expect(environments.loadReadOnlyWaitingEnvironment(external)).rejects.toThrow(); expect(reads).toBe(0); }, { [key]: key === 'NODE_TLS_REJECT_UNAUTHORIZED' ? '0' : '' });
  }
  await approved(async () => { reads = 0; await expect(environments.loadBusinessEnvironment(external)).rejects.toThrow(); expect(reads).toBe(0); });
  expect(readdirSync(folder)).toEqual([]);
});
test('readonly real CLI discovers zero default real cases and only exact selected project, retaining worker reload identity', () => {
  const config = resolve('e2e/readonly-waiting.config.ts'), cli = resolve('node_modules/@playwright/test/cli.js');
  const run = (configPath: string, ...args: string[]) => spawnSync(process.execPath, [cli,'test','--config',configPath,...args], { encoding: 'utf8', windowsHide: true, timeout: 30_000 });
  const folder = mkdtempSync(join(tmpdir(), 'task96q-config-')), probe = join(folder, 'probe.config.cjs');
  const discovery = join(folder,'discovery.config.cjs');
  writeFileSync(discovery, `const {default:c}=require(${JSON.stringify(config)});module.exports={...c,testDir:${JSON.stringify(resolve('e2e/readonly'))},reporter:[['list']]};`);
  for (const args of [[], ['--grep','approved-local-readonly-waiting'], ['--','--project=approved-local-readonly-waiting']]) {
    const result = run(discovery,'--list',...args); expect(result.stdout).toContain('Total: 0 tests in 0 files');
  }
  const selected = run(discovery,'--list','--project=approved-local-readonly-waiting');
  expect(selected.status, selected.stdout + selected.stderr).toBe(0); expect(selected.stdout).toContain('Total: 1 test in 1 file'); expect(selected.stdout).toContain('[approved-local-readonly-waiting]');
  const override = run(config,'--list','--project=approved-local-readonly-waiting','--reporter=line'); expect(override.status).not.toBe(0); expect(override.stderr).toContain('T9_READONLY_WAITING_BOUNDARY');
  writeFileSync(join(folder,'benign.spec.cjs'), `const {test,expect}=require(${JSON.stringify(resolve('node_modules/@playwright/test'))});test('benign',()=>expect(1).toBe(1));`);
  writeFileSync(probe, `const {default:c}=require(${JSON.stringify(config)});module.exports={...c,testDir:__dirname,reporter:[['line']],projects:c.projects.map(p=>({...p,testDir:__dirname,testMatch:'benign.spec.cjs'}))};`);
  const worker = run(probe,'--project=approved-local-readonly-waiting'); expect(worker.status, worker.stdout + worker.stderr).toBe(0); expect(worker.stdout).toContain('1 passed');
});
test('closed readonly reporter never emits raw names errors or streams', () => {
  const reporter = new ReadOnlyWaitingReporter(), lines: string[] = [], original = process.stdout.write;
  try { process.stdout.write = ((data: any) => { lines.push(String(data)); return true; }) as any;
    reporter.onStdOut(); reporter.onStdErr(); reporter.onError(); reporter.onTestEnd({ title: 'private title' } as any, { status: 'failed', errors: [{ message: 'private error' }] } as any); reporter.onEnd({ status: 'failed' } as any);
  } finally { process.stdout.write = original; }
  expect(lines.join('')).toBe('T9-W06-W08-readonly-waiting-refresh status=failed\nT9-W06-W08-readonly-waiting-refresh exit=1\n');
});

test('readonly loader rejects actual missing tampered or pending predecessor files after protection without creating evidence', async () => {
  const id = (n: number) => `00000000-0000-7000-8000-${String(n).padStart(12,'0')}`;
  const accounts = Object.fromEntries(['intake','supervisor','contact','delegate'].map((alias,i) => [alias, { username: `task9-local-${alias}`, password: 'synthetic-only', email: `${alias}@example.invalid`, firstName: alias, lastName: 'Synthetic', providerUserId: id(i) }]));
  const saved = Object.fromEntries(Object.entries(accounts).map(([alias, { password: _, ...account }]) => [alias,account]));
  const snapshot = { ...environments.BUSINESS_PIN, apiIdentity: 'a'.repeat(64), processIdentity: 'b'.repeat(64), releaseIdentity: 'c'.repeat(64) };
  const loaded = { ...snapshot, predecessor: { ...environments.IDENTITY_PREDECESSOR, commandCount: 16, stageCount: 7, pendingCount: 0 }, original: { founder: { username: 'synthetic-founder', password: 'synthetic' }, unmapped: { username: 'synthetic-unmapped', password: 'synthetic' } }, credentials: { runId: 'synthetic', accounts }, operation: { runId: 'synthetic', stage: 'COMPLETE', accounts: saved, temporaryClientDeleted: true, temporaryCredentialRejected: true, temporaryTokenRejected: true, originalUsersUnchanged: true, realmPublicKeysUnchanged: true, directoryReadOnlyUnchanged: true, temporaryClientUserAndRolesAbsent: true, temporaryRecoveryContainerRemoved: true }, bootstrap: { tenantId: id(10), rootId: id(11), founderId: id(12), appointmentId: id(13) }, resources: Object.fromEntries(environments.IDENTITY_RESOURCE_STEPS.map((step,i) => [step,id(20+i)])) };
  for (const mode of ['missing','tampered','pending','completion']) {
    const folder = mkdtempSync(join(tmpdir(),'task96q-files-')), path = join(folder,'task9-business-operation.json'); let protectedRead = false;
    if (mode !== 'missing') writeFileSync(path,'invalid original bytes');
    if (mode === 'pending' || mode === 'completion') writeFileSync(path + (mode === 'pending' ? '.pending' : '.completion.pending'),'ambiguous');
    const before = readdirSync(folder);
    await approved(async () => { await expect(environments.loadReadOnlyWaitingEnvironment({ runtime: folder, toolchain: () => ({ browserVersion: snapshot.browserVersion, browserRevision: snapshot.browserRevision }), async invokeLocalRuntime(kind) { if (kind === 'protect') protectedRead = true; return structuredClone(kind === 'snapshot' ? snapshot : kind === 'business' ? loaded : {}); } })).rejects.toThrow(); });
    expect(protectedRead).toBe(true); expect(readdirSync(folder)).toEqual(before);
    if (mode !== 'missing') expect(readFileSync(path,'utf8')).toBe('invalid original bytes');
  }
});
