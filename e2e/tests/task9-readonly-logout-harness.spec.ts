import { expect, test } from '@playwright/test';
import * as environments from '../fixtures/business-environment';
import { mkdtempSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';

let logoutFixture: any = {};
try { logoutFixture = require('../fixtures/readonly-logout'); } catch {}
let LogoutReporter: any;
try { LogoutReporter = require('../reporters/readonly-logout-reporter').default; } catch {}
const runLogout = (...args: any[]) => {
  expect(typeof logoutFixture.runReadOnlyLogout).toBe('function');
  return logoutFixture.runReadOnlyLogout(...args);
};

test('logout loader is a distinct guarded entry before any external bridge access', () => {
  expect(typeof environments.loadReadOnlyLogoutEnvironment).toBe('function');
});

const appointment = '00000000-0000-7000-8000-000000000901';
const actor = 'ask1.' + 'a'.repeat(43);
const waiting = () => ({ todaySummary: '今日等待事项', currentCard: null, nextSummaries: [], waitingCount: 1, chatComposer: { mode: 'ACTION_DRAFT', targetTaskId: null, placeholder: '暂无可处理责任', enabled: false } });

function transport(failure = '') {
  const folder = mkdtempSync(join(tmpdir(), 'task96r-logout-'));
  const wall = Date.parse(environments.READONLY_WAITING_PIN.dueAt) - 3_600_000;
  let elapsed = 0, closed = false, opened = false, guardCalls = 0, routeHandler: any;
  const forwarded: string[] = [], sleeps: number[] = [], probes: Array<{ path: string; status: number }> = [];
  const pages: any[] = [];
  const token = (page: any) => {
    const expiry = failure === 'expired-token' ? Math.floor((wall - 1_000) / 1000) : Math.floor((wall + 1_800_000) / 1000);
    return `Bearer e30.${Buffer.from(JSON.stringify({ exp: expiry, page: page.index, login: page.logins })).toString('base64url')}.signature`;
  };
  function clearPeer(source: any, message: string) {
    for (const page of pages) if (page !== source) {
      if (failure === 'peer-not-cleared' && source.logouts === 1) continue;
      page.stage = 'login'; page.currentUrl = environments.BUSINESS_PIN.origin + '/login';
      page.message = failure === 'peer-success-message' && source.logouts === 1 ? '统一会话退出成功。' : message;
    }
  }
  async function emit(page: any, path: string, method = 'GET') {
    const isApi = path.startsWith('/api/'), isCurrent = path === '/api/v1/workcards/current';
    const url = (path.startsWith('/realms/') ? new URL(environments.BUSINESS_PIN.issuer).origin + path : environments.BUSINESS_PIN.origin + path) + (failure === 'id-token-hint' && path.endsWith('/protocol/openid-connect/logout') && page.logouts === 2 ? '?id_token_hint=forbidden' : '');
    const headers: Record<string,string> = isApi ? { authorization: token(page), 'x-appointment-id': appointment, ...(failure === 'reentry-304' && isCurrent && page.logins > 1 ? { 'if-none-match': '"wb.' + 'b'.repeat(43) + '"' } : {}) } : {};
    const request: any = { url: () => url, method: () => method, allHeaders: async () => headers, headers: () => headers, postData: () => method === 'POST' && path.endsWith('/token') ? 'grant_type=authorization_code' : null, serviceWorker: () => null, frame: () => ({ page: () => page }) };
    let continued = false, aborted = false;
    await routeHandler({ request: () => request, continue: async () => { forwarded.push(`${method} ${path}`); continued = true; }, abort: async () => { aborted = true; if (failure !== 'fault-no-requestfailed') page.listeners.requestfailed?.(request); } });
    if (aborted || !continued) return false;
    if (path.endsWith('/protocol/openid-connect/logout')) { page.stage = 'keycloak'; page.currentUrl = url; }
    if (path.endsWith('/logout/logout-confirm')) { page.stage = 'login'; page.currentUrl = environments.BUSINESS_PIN.origin + '/login'; page.message = null; }
    const status = path.endsWith('/logout/logout-confirm') ? 302 : failure === 'reentry-304' && isCurrent && page.logins > 1 ? 304 : 200;
    const value = path === '/api/v1/session/context' ? { state: 'READY', selectedAppointmentId: appointment, selectedOnBehalfAppointmentId: null, appointmentChoices: [{ id: appointment, label: '原合成任职' }], delegatedAppointmentChoices: [], actorScopeKey: failure === 'identity-drift' && page.logins > 1 ? 'ask1.' + 'z'.repeat(43) : actor, canEnterWorkbench: true, canEnterIdentityAdmin: false, displayName: failure === 'identity-drift' && page.logins > 1 ? '漂移身份' : '原合成联系人' } : isCurrent ? waiting() : {};
    const response: any = { url: () => url, request: () => request, status: () => status, allHeaders: async () => isApi ? (isCurrent ? { 'cache-control': 'private, no-cache', vary: 'Authorization', etag: '"wb.' + 'b'.repeat(43) + '"' } : { 'cache-control': 'no-store' }) : {}, json: async () => structuredClone(value) };
    page.listeners.response?.(response);
    return true;
  }
  function locator(page: any, selector: string, role?: string, name?: string): any {
    const visible = () => {
      if (role === 'button' && name === '登录工作台') return page.stage === 'login';
      if (role === 'button' && name === '确认本次身份') return page.stage === 'chooser';
      if (role === 'button' && name === '退出') return page.stage === 'workbench';
      if (role === 'heading') return page.stage === 'chooser' ? name === '请选择本次办理身份' : page.stage === 'workbench' && name === '当前无可处理责任，另有等待事项';
      if (selector === 'input[name="username"]' || selector === 'input[name="password"]' || selector.includes('[type="submit"]')) return page.stage === 'keycloak-login';
      if (selector === 'form#kc-logout' || selector === '#kc-logout') return page.stage === 'keycloak';
      if (selector === '.session-actions > span' || selector === '.waiting-count > span' || selector === '.today-summary p') return page.stage === 'workbench';
      if (selector === 'article.current-card' || selector === '.next-summary' || selector === 'textarea, input:not([type="hidden"])') return false;
      if (selector.startsWith('text=')) return page.message === selector.slice(5);
      return true;
    };
    const item: any = { first: () => item, locator: (child: string) => locator(page, child), isVisible: async () => visible(), waitFor: async () => { if (!visible()) throw Error('not visible'); }, count: async () => visible() ? 1 : 0,
      textContent: async () => selector === '.session-actions > span' ? '原合成联系人 · 原合成任职' : selector === '.waiting-count > span' ? '等待 1' : selector === '.today-summary p' ? waiting().todaySummary : selector === '.choice-account > span' ? '原合成联系人' : page.message,
      inputValue: async () => appointment, getAttribute: async (key: string) => selector === 'form#kc-logout' && key === 'action' ? environments.BUSINESS_PIN.issuer + '/protocol/openid-connect/logout/logout-confirm?session_code=private-never-read' : null,
      fill: async () => {}, click: async () => {
        if (role === 'button' && name === '登录工作台') {
          page.logins++; page.stage = page.logins === 1 && page.index === 0 ? 'keycloak-login' : 'chooser';
          page.currentUrl = page.stage === 'keycloak-login' ? environments.BUSINESS_PIN.issuer + '/protocol/openid-connect/auth' : environments.BUSINESS_PIN.origin + '/workbench';
          if (page.stage === 'chooser') { await emit(page, '/realms/local-r1/protocol/openid-connect/token', 'POST'); await emit(page, '/api/v1/session/context'); }
        } else if (selector.includes('[type="submit"]') && page.stage === 'keycloak-login') {
          page.stage = 'chooser'; page.currentUrl = environments.BUSINESS_PIN.origin + '/workbench'; await emit(page, '/realms/local-r1/protocol/openid-connect/token', 'POST'); await emit(page, '/api/v1/session/context');
        } else if (role === 'button' && name === '确认本次身份') {
          page.stage = 'workbench'; page.currentUrl = environments.BUSINESS_PIN.origin + '/workbench'; await emit(page, '/api/v1/workcards/current');
          if (failure === 'unexpected-write' && page.index === 0 && page.logins === 1) await emit(page, '/api/v1/leads', 'POST');
        } else if (role === 'button' && name === '退出') {
          page.logouts++; page.stage = 'login'; page.currentUrl = environments.BUSINESS_PIN.origin + '/login'; page.message = '已退出本页面，统一会话退出尚未确认。'; clearPeer(page, '已退出本页面，请重新登录。');
          if (failure === 'fault-as-success' && page.logouts === 2) return;
          await emit(page, '/realms/local-r1/protocol/openid-connect/logout');
        } else if (selector === '#kc-logout') await emit(page, '/realms/local-r1/protocol/openid-connect/logout/logout-confirm', 'POST');
      } };
    return item;
  }
  function makePage(index: number) {
    const page: any = { index, logins: 0, logouts: 0, stage: 'login', message: null, currentUrl: environments.BUSINESS_PIN.origin + '/login', listeners: {} as Record<string,Function>,
      on(event: string, callback: Function) { page.listeners[event] = callback; }, url: () => page.currentUrl, async goto(url: string) { page.currentUrl = url; page.stage = 'login'; },
      async waitForURL(predicate: any) { if (typeof predicate === 'function' ? !predicate(new URL(page.currentUrl)) : false) throw Error('wrong URL'); },
      getByRole: (role: string, options: any) => locator(page, '', role, options.name), getByText: (name: string) => locator(page, 'text=' + name), locator: (selector: string) => locator(page, selector),
      async bringToFront() {}, async waitForEvent(event: string) { expect(event).toBe('framenavigated'); if (failure === 'history-none') throw Error('no history'); return new Promise(resolve => { page.navigation = resolve; }); },
      async goBack() { if (failure === 'history-none') return null; page.stage = failure === 'history-revival' ? 'workbench' : 'login'; page.currentUrl = environments.BUSINESS_PIN.origin + (page.stage === 'workbench' ? '/workbench' : '/login'); page.navigation?.({}); return failure === 'history-same-document' ? null : {}; },
    };
    pages.push(page); return page;
  }
  const context: any = { on() {}, async route(_pattern: string, handler: any) { routeHandler = handler; }, async newPage() { return makePage(pages.length); }, request: { async get(url: string, options: any) {
    expect(new URL(url).origin).toBe(environments.BUSINESS_PIN.origin); expect(['/api/v1/session/context','/api/v1/workcards/current']).toContain(new URL(url).pathname);
    expect(options.maxRedirects).toBe(0); expect(options.failOnStatusCode).toBe(false); expect(options.headers.Authorization).toMatch(/^Bearer /);
    const status = failure === 'old-token-200' && probes.length === 0 ? 200 : 401; probes.push({ path: new URL(url).pathname, status }); return { status: () => status };
  } }, close: async () => {} };
  const browser: any = { version: () => environments.BUSINESS_PIN.browserVersion, async newContext(options: any) { opened = true; expect(options).toMatchObject({ serviceWorkers: 'block', ignoreHTTPSErrors: false }); return context; }, async close() { closed = true; } };
  const environment: any = { ...environments.BUSINESS_PIN, environmentDigest: 'c'.repeat(64), apiIdentity: 'd'.repeat(64), outputDirectory: folder, readonlyProof: { ...environments.READONLY_WAITING_PIN }, accounts: { contact: { username: 'synthetic-contact', password: 'ephemeral' } }, resources: { 'appointment-contact': appointment },
    verifyBrowser(actual: string) { expect(actual).toBe(environments.BUSINESS_PIN.browserVersion); }, async assertUnchanged() { guardCalls++; if (closed && failure === 'final-guard') throw Error('private final guard'); } };
  const clock: any = { now: () => elapsed, wallNow: () => wall + elapsed, async sleep(ms: number) { sleeps.push(ms); expect(ms).toBeGreaterThan(0); expect(ms).toBeLessThanOrEqual(30_000); elapsed += ms; }, deadline: 510_000 };
  return { browser, environment, clock, folder, forwarded, probes, sleeps, pages, opened: () => opened, closed: () => closed, guardCalls: () => guardCalls };
}

async function execute(failure = '') {
  const value = transport(failure), result = await runLogout(value.browser, value.environment, { clock: value.clock });
  const saved = JSON.parse(readFileSync(result.reportPath, 'utf8'));
  expect(saved).toEqual(result); expect(value.closed()).toBe(true); expect(readdirSync(value.folder)).toHaveLength(1);
  expect(JSON.stringify(saved)).not.toMatch(/Bearer |ephemeral|原合成联系人|ask1\.|session_code|private final guard/);
  return { result, value };
}

test('actual logout consumer distinguishes the held network failure from confirmed logout and rejects four active old bearer probes', async () => {
  const { result, value } = await execute();
  expect(result.status).toBe('PASSED_READ_ONLY_SUBSCENARIO');
  expect(result.scenarios).toEqual({ faultLogout: 'PASSED', confirmedLogout: 'PASSED', tokenRejection: 'PASSED', historySafety: 'PASSED' });
  expect(result.counts).toMatchObject({ successfulSelf: 4, faultedLogoutRequests: 1, forwardedLogoutGets: 1, confirmPosts: 1, requestFailed: 1, tokenProbes: 4, blockedRequests: 0, businessWrites: 0 });
  expect(value.probes).toEqual([{ path: '/api/v1/session/context', status: 401 }, { path: '/api/v1/workcards/current', status: 401 }, { path: '/api/v1/session/context', status: 401 }, { path: '/api/v1/workcards/current', status: 401 }]);
  expect(Math.max(...value.sleeps, 0)).toBeLessThanOrEqual(30_000);
});

test('actual logout consumer treats a same-document history event as triggered even when goBack returns null', async () => {
  const { result } = await execute('history-same-document');
  expect(result.status).toBe('PASSED_READ_ONLY_SUBSCENARIO'); expect(result.scenarios.historySafety).toBe('PASSED');
});

test('actual logout consumer reuses the verified same-Actor workbench cache for a matching reentry 304', async () => {
  const { result } = await execute('reentry-304');
  expect(result.status).toBe('PASSED_READ_ONLY_SUBSCENARIO');
  expect(result.http.filter((event: any) => event.kind === 'CURRENT').map((event: any) => event.status)).toEqual([200, 200, 304, 304]);
});

for (const [failure, step] of [
  ['fault-as-success','CONFIRMED_LOGOUT'], ['old-token-200','TOKEN_REJECTION'], ['identity-drift','REENTRY'], ['peer-not-cleared','FAULT_LOCAL_CLEAR'], ['peer-success-message','FAULT_LOCAL_CLEAR'],
  ['history-none','HISTORY'], ['history-revival','HISTORY'], ['expired-token','TOKEN_REJECTION'], ['final-guard','FINAL_GUARD'], ['unexpected-write','NETWORK'], ['id-token-hint','NETWORK'], ['fault-no-requestfailed','FAULT_TRANSPORT'],
] as const) test(`actual logout consumer cannot pass ${failure}`, async () => {
  const { result, value } = await execute(failure);
  expect(result.status).not.toBe('PASSED_READ_ONLY_SUBSCENARIO'); expect(result.failureStep).toBe(step);
  if (failure === 'fault-as-success') expect(result.scenarios.confirmedLogout).not.toBe('PASSED');
  if (failure === 'old-token-200' || failure === 'expired-token') expect(result.scenarios.tokenRejection).not.toBe('PASSED');
  if (failure === 'unexpected-write') expect(value.forwarded).not.toContain('POST /api/v1/leads');
});

test('launch failure remains guarded, closes no context and publishes only sanitized FAILED evidence', async () => {
  const value = transport(); let launches = 0;
  const result = await runLogout(async () => { launches++; throw Error('private launch detail'); }, value.environment, { clock: value.clock });
  expect(launches).toBe(1); expect(value.opened()).toBe(false); expect(value.guardCalls()).toBe(2);
  expect(result).toMatchObject({ status: 'FAILED', failureStep: 'PRECHECK', counts: { successfulSelf: 0, tokenProbes: 0 } });
  expect(readFileSync(result.reportPath, 'utf8')).not.toContain('private launch detail');
});

test('due margin refusal is NOT_EXECUTED before browser launch', async () => {
  const value = transport(); value.clock.wallNow = () => Date.parse(environments.READONLY_WAITING_PIN.dueAt) - 599_999; let launches = 0;
  const result = await runLogout(async () => { launches++; return value.browser; }, value.environment, { clock: value.clock });
  expect(result.status).toBe('NOT_EXECUTED'); expect(launches).toBe(0); expect(value.opened()).toBe(false);
});

test('exclusive logout report collision preserves original bytes', async () => {
  const value = transport(), id = '00000000-0000-4000-8000-000000000999', path = join(value.folder, `task96r-readonly-logout-${id}.json`); writeFileSync(path, 'original evidence');
  await expect(runLogout(value.browser, value.environment, { clock: value.clock, reportId: () => id })).rejects.toThrow(); expect(readFileSync(path, 'utf8')).toBe('original evidence'); expect(value.closed()).toBe(true);
});

async function approved(operation: () => Promise<void>, patch: Record<string,string|undefined> = {}) {
  const keys = ['TASK9_LOCAL_ACCEPTANCE','TASK9_READONLY_LOGOUT','TASK9_READONLY_WAITING','TASK9_READONLY_SESSION','TASK9_BUSINESS_ACCEPTANCE','TASK9_BUSINESS_RUN_ID','TASK9_BUSINESS_CONTINUE_RUN_ID','TASK9_BUSINESS_RESTART_SHA256','TASK9_BUSINESS_RECOVER_COMMAND_ID','TASK9_BUSINESS_RECOVER_JOURNAL_SHA256','TASK9_RUN_ID','TASK9_CONTINUE_RUN_ID','TASK9_RECOVER_COMMAND_ID','DEBUG','PWDEBUG','PW_TEST_DEBUG','NODE_TLS_REJECT_UNAUTHORIZED'];
  const values: Record<string,string|undefined> = Object.fromEntries(keys.map(key => [key, undefined])); Object.assign(values, { TASK9_LOCAL_ACCEPTANCE: 'APPROVED_SYNTHETIC_ONLY', TASK9_READONLY_LOGOUT: 'APPROVED_EXISTING_SESSION_LOGOUT_ONLY' }, patch);
  const previous = Object.fromEntries(keys.map(key => [key, process.env[key]]));
  try { for (const [key,value] of Object.entries(values)) if (value === undefined) delete process.env[key]; else process.env[key] = value; await operation(); }
  finally { for (const [key,value] of Object.entries(previous)) if (value === undefined) delete process.env[key]; else process.env[key] = value; }
}

test('logout approval reaches only the shared bridge while all conflicting and unsafe flags fail first', async () => {
  let reads = 0; const external = { runtime: mkdtempSync(join(tmpdir(), 'task96r-flags-')), toolchain: () => ({ browserVersion: environments.BUSINESS_PIN.browserVersion, browserRevision: environments.BUSINESS_PIN.browserRevision }), async invokeLocalRuntime() { reads++; throw Error('synthetic external bridge'); } };
  await approved(async () => { await expect(environments.loadReadOnlyLogoutEnvironment(external)).rejects.toThrow('synthetic external bridge'); expect(reads).toBe(1); });
  for (const key of ['TASK9_READONLY_WAITING','TASK9_READONLY_SESSION','TASK9_BUSINESS_ACCEPTANCE','TASK9_BUSINESS_RUN_ID','TASK9_BUSINESS_CONTINUE_RUN_ID','TASK9_BUSINESS_RESTART_SHA256','TASK9_BUSINESS_RECOVER_COMMAND_ID','TASK9_BUSINESS_RECOVER_JOURNAL_SHA256','TASK9_RUN_ID','TASK9_CONTINUE_RUN_ID','TASK9_RECOVER_COMMAND_ID','DEBUG','PWDEBUG','PW_TEST_DEBUG','NODE_TLS_REJECT_UNAUTHORIZED']) {
    reads = 0; await approved(async () => { await expect(environments.loadReadOnlyLogoutEnvironment(external)).rejects.toThrow(); expect(reads).toBe(0); }, { [key]: key === 'NODE_TLS_REJECT_UNAUTHORIZED' ? '0' : 'conflict' });
  }
  await approved(async () => { reads = 0; await expect(environments.loadBusinessEnvironment(external)).rejects.toThrow(); expect(reads).toBe(0); });
  await approved(async () => { reads = 0; await expect(environments.loadReadOnlyWaitingEnvironment(external)).rejects.toThrow(); expect(reads).toBe(0); });
});

test('logout CLI discovers zero real cases by default and only the exact approved project', () => {
  const config = resolve('e2e/readonly-logout.config.ts'), cli = resolve('node_modules/@playwright/test/cli.js');
  const run = (configPath: string, ...args: string[]) => spawnSync(process.execPath, [cli,'test','--config',configPath,...args], { encoding: 'utf8', windowsHide: true, timeout: 30_000 });
  const folder = mkdtempSync(join(tmpdir(), 'task96r-config-')), discovery = join(folder, 'discovery.config.cjs'), probe = join(folder, 'probe.config.cjs');
  writeFileSync(discovery, `const {default:c}=require(${JSON.stringify(config)});module.exports={...c,testDir:${JSON.stringify(resolve('e2e/readonly'))},reporter:[['list']]};`);
  for (const args of [[], ['--grep','approved-local-readonly-logout'], ['--','--project=approved-local-readonly-logout']]) expect(run(discovery,'--list',...args).stdout).toContain('Total: 0 tests in 0 files');
  const selected = run(discovery,'--list','--project=approved-local-readonly-logout'); expect(selected.status, selected.stdout + selected.stderr).toBe(0); expect(selected.stdout).toContain('T9-L09-existing-session-logout');
  const override = run(config,'--list','--project=approved-local-readonly-logout','--reporter=line'); expect(override.status).not.toBe(0); expect(override.stdout + override.stderr).toContain('T9_READONLY_LOGOUT_BOUNDARY');
  writeFileSync(join(folder,'benign.spec.cjs'), `const {test}=require(${JSON.stringify(resolve('node_modules/@playwright/test'))});test('benign',()=>{});`);
  writeFileSync(probe, `const {default:c}=require(${JSON.stringify(config)});module.exports={...c,testDir:__dirname,reporter:[['line']],projects:c.projects.map(p=>({...p,testDir:__dirname,testMatch:'benign.spec.cjs'}))};`);
  const worker = run(probe,'--project=approved-local-readonly-logout'); expect(worker.status, worker.stdout + worker.stderr).toBe(0); expect(worker.stdout).toContain('1 passed');
});

test('closed logout reporter emits no titles, errors or streams', () => {
  expect(typeof LogoutReporter).toBe('function'); const reporter = new LogoutReporter(), lines: string[] = [], original = process.stdout.write;
  try { process.stdout.write = ((data: any) => { lines.push(String(data)); return true; }) as any; reporter.onStdOut(); reporter.onStdErr(); reporter.onError(); reporter.onTestEnd({ title: 'private' }, { status: 'failed', errors: [{ message: 'private' }] }); reporter.onEnd({ status: 'failed' }); }
  finally { process.stdout.write = original; }
  expect(lines.join('')).toBe('T9-L09-existing-session-logout status=failed\nT9-L09-existing-session-logout exit=1\n');
});
