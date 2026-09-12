import type { Browser, Page, Request, Response, TestInfo } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { closeSync, fsyncSync, openSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { performance } from 'node:perf_hooks';
import { BUSINESS_PIN, READONLY_WAITING_PIN, type loadReadOnlyLogoutEnvironment } from './business-environment';
import { check, exact, noLinks } from './local-environment';
import { lowerHeaders, validateWorkbenchCache, type WorkbenchCache } from './workbench-cache';

export const READONLY_LOGOUT_CASE = 'T9-L09-existing-session-logout';
export type LogoutScenarioStatus = 'PASSED' | 'FAILED' | 'NOT_TRIGGERED';
export type LogoutFailureStep = 'PRECHECK' | 'LOGIN' | 'IDENTITY' | 'WAITING' | 'NETWORK' | 'FAULT_LOCAL_CLEAR' | 'FAULT_TRANSPORT' | 'REENTRY' | 'CONFIRMED_LOGOUT' | 'TOKEN_REJECTION' | 'HISTORY' | 'CLEANUP' | 'FINAL_GUARD' | null;
export type LogoutStatus = 'PASSED_READ_ONLY_SUBSCENARIO' | 'FAILED' | 'NOT_TRIGGERED' | 'NOT_EXECUTED';
export interface LogoutReport {
  caseIdentity: typeof READONLY_LOGOUT_CASE; status: LogoutStatus; buildSha: string; environmentDigest: string; apiIdentity: string; executedAt: string;
  journalSha256: string; checkpointSha256: string; reportPath: string;
  scenarios: { faultLogout: LogoutScenarioStatus; confirmedLogout: LogoutScenarioStatus; tokenRejection: LogoutScenarioStatus; historySafety: LogoutScenarioStatus };
  checks: { sourceCleared: boolean; peerCleared: boolean; faultNotForwarded: boolean; faultRequestFailed: boolean; peerDidNotClaimSuccess: boolean; bothReentered: boolean; confirmFormVisible: boolean; serverLogoutConfirmed: boolean; oldTokensRejected: boolean; historyDidNotRevive: boolean; environmentUnchanged: boolean; journalUnchanged: boolean; checkpointUnchanged: boolean };
  counts: { successfulSelf: number; current: number; faultedLogoutRequests: number; forwardedLogoutGets: number; confirmPosts: number; requestFailed: number; tokenProbes: number; blockedRequests: number; businessWrites: number };
  http: Array<{ kind: 'SELF' | 'CURRENT' | 'LOGOUT_GET' | 'LOGOUT_CONFIRM' | 'OLD_SELF' | 'OLD_CURRENT'; status: number; offsetMs: number }>;
  failureStep: LogoutFailureStep;
}
export interface LogoutClock { now(): number; wallNow(): number; sleep(ms: number): Promise<void>; deadline?: number }
export interface LogoutDependencies { clock?: LogoutClock; reportId?: () => string }
type Environment = Awaited<ReturnType<typeof loadReadOnlyLogoutEnvironment>>;
type BrowserSource = Browser | (() => Promise<Browser>) | undefined;

const SELF = '/api/v1/session/context', CURRENT = '/api/v1/workcards/current';
const TOKEN = '/realms/local-r1/protocol/openid-connect/token';
const LOGOUT = '/realms/local-r1/protocol/openid-connect/logout';
const CONFIRM = '/realms/local-r1/protocol/openid-connect/logout/logout-confirm';
const SOURCE_MESSAGE = '已退出本页面，统一会话退出尚未确认。';
const PEER_MESSAGE = '已退出本页面，请重新登录。';
const SUCCESS_CLAIM = '统一会话退出成功。';
const realClock: LogoutClock = { now: () => performance.now(), wallNow: () => Date.now(), sleep: ms => new Promise(resolve => setTimeout(resolve, ms)) };

export function assertReadOnlyLogoutConfig(info: TestInfo): void {
  check(info.project.name === 'approved-local-readonly-logout' && info.project.retries === 0 && info.project.repeatEach === 1 && info.project.timeout === 540_000);
  check(info.config.workers === 1 && !info.config.fullyParallel);
  check(info.config.reporter.length === 1 && info.config.reporter[0][0].replaceAll('\\','/').endsWith('/e2e/reporters/readonly-logout-reporter.ts'));
  const use = info.project.use;
  check(use.trace === 'off' && use.video === 'off' && use.screenshot === 'off' && use.storageState === undefined && use.serviceWorkers === 'block' && use.ignoreHTTPSErrors === false);
}

function expiry(authorization: string): number {
  check(/^Bearer \S+$/.test(authorization));
  const claims = JSON.parse(Buffer.from(authorization.slice(7).split('.')[1], 'base64url').toString('utf8'));
  check(Number.isSafeInteger(claims.exp) && claims.exp > 0);
  return claims.exp * 1000;
}

function allowed(url: URL, method: string): boolean {
  const read = ['GET','HEAD','OPTIONS'].includes(method);
  if (url.origin === BUSINESS_PIN.origin) return read && (!(url.pathname === '/api' || url.pathname.startsWith('/api/')) || [SELF,CURRENT].includes(url.pathname));
  if (url.origin !== new URL(BUSINESS_PIN.issuer).origin) return false;
  if (method === 'GET') return url.pathname === LOGOUT || url.pathname === '/realms/local-r1' || url.pathname.startsWith('/realms/local-r1/') || url.pathname.startsWith('/resources/');
  return method === 'POST' && ([TOKEN,CONFIRM].includes(url.pathname) || url.pathname === '/realms/local-r1/login-actions/authenticate');
}

// External browser transport, clock and UUID entropy may vary in offline tests.
// Route decisions, UI/identity checks, lifecycle and report publication remain real.
export async function runReadOnlyLogout(browserSource: BrowserSource, environment: Environment, dependencies: LogoutDependencies = {}): Promise<LogoutReport> {
  const clock = dependencies.clock ?? realClock, started = clock.now(), deadline = Math.min(started + 510_000, clock.deadline ?? Infinity);
  const id = (dependencies.reportId ?? randomUUID)(); check(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(id));
  const report: LogoutReport = {
    caseIdentity: READONLY_LOGOUT_CASE, status: 'FAILED', buildSha: environment.buildSha, environmentDigest: environment.environmentDigest, apiIdentity: environment.apiIdentity,
    executedAt: new Date(clock.wallNow()).toISOString(), journalSha256: READONLY_WAITING_PIN.journalSha256, checkpointSha256: READONLY_WAITING_PIN.checkpointSha256,
    reportPath: resolve(environment.outputDirectory, `task96r-readonly-logout-${id}.json`),
    scenarios: { faultLogout: 'FAILED', confirmedLogout: 'FAILED', tokenRejection: 'FAILED', historySafety: 'FAILED' },
    checks: { sourceCleared: false, peerCleared: false, faultNotForwarded: false, faultRequestFailed: false, peerDidNotClaimSuccess: false, bothReentered: false, confirmFormVisible: false, serverLogoutConfirmed: false, oldTokensRejected: false, historyDidNotRevive: false, environmentUnchanged: false, journalUnchanged: false, checkpointUnchanged: false },
    counts: { successfulSelf: 0, current: 0, faultedLogoutRequests: 0, forwardedLogoutGets: 0, confirmPosts: 0, requestFailed: 0, tokenProbes: 0, blockedRequests: 0, businessWrites: 0 }, http: [], failureStep: null,
  };
  let browser = typeof browserSource === 'function' ? undefined : browserSource, source: Page | undefined, peer: Page | undefined;
  let step: LogoutFailureStep = 'PRECHECK', stopped = false, observation = Promise.resolve(), faultArmed = false, faultReleased = false, faultRequest: Request | undefined;
  let releaseFault!: () => void; const faultGate = new Promise<void>(resolve => { releaseFault = resolve; });
  type Seen = { self: any; selfCount: number; currentCount: number; authorization: string; expiresAt: number; cache?: WorkbenchCache };
  const seen = new Map<Page, Seen>(), baseline: { self?: any } = {};
  const fail = (at: LogoutFailureStep) => { report.failureStep ??= at; stopped = true; };
  const healthy = () => check(!stopped && clock.now() < deadline);
  const settle = async () => { await observation; healthy(); };
  const delay = async (ms: number) => { check(ms > 0 && ms <= 30_000); await clock.sleep(ms); await settle(); };
  const until = async (done: () => boolean, maximum = 30_000) => {
    const end = clock.now() + maximum;
    while (!done()) { healthy(); check(clock.now() < end); await delay(Math.min(250, end - clock.now())); }
    await settle();
  };
  const pageFor = (request: Request) => request.frame().page();
  const identity = (value: any) => {
    const own = environment.resources['appointment-contact'];
    check(value?.state === 'READY' && value.selectedAppointmentId === own && value.selectedOnBehalfAppointmentId === null && value.canEnterWorkbench === true && value.canEnterIdentityAdmin === false);
    check(Array.isArray(value.appointmentChoices) && value.appointmentChoices.length === 1 && value.appointmentChoices[0].id === own && typeof value.appointmentChoices[0].label === 'string');
    check(typeof value.displayName === 'string' && /^ask1\.[A-Za-z0-9_-]{43}$/.test(value.actorScopeKey));
    if (baseline.self) check(value.actorScopeKey === baseline.self.actorScopeKey && value.selectedAppointmentId === baseline.self.selectedAppointmentId && value.displayName === baseline.self.displayName && value.appointmentChoices[0].label === baseline.self.appointmentChoices[0].label);
    else baseline.self = structuredClone(value);
  };
  const observe = async (response: Response) => {
    const request = response.request(), url = new URL(response.url()), page = pageFor(request), state = seen.get(page); check(state);
    if (url.origin === BUSINESS_PIN.origin && url.pathname === SELF) {
      check(request.method() === 'GET' && response.status() === 200);
      const headers = lowerHeaders(await response.allHeaders()); check(headers['cache-control']?.toLowerCase() === 'no-store');
      const value = await response.json(); identity(value); state.self = structuredClone(value); state.selfCount++; report.counts.successfulSelf++;
      const requestHeaders = await request.allHeaders(), authorization = requestHeaders.authorization ?? requestHeaders.Authorization; check(authorization); state.authorization = authorization; state.expiresAt = expiry(authorization);
      report.http.push({ kind: 'SELF', status: 200, offsetMs: clock.now() - started });
    } else if (url.origin === BUSINESS_PIN.origin && url.pathname === CURRENT) {
      check(request.method() === 'GET' && [200,304].includes(response.status()) && state.self);
      const requestHeaders = lowerHeaders(await request.allHeaders()), authorization = requestHeaders.authorization;
      check(authorization && requestHeaders['x-appointment-id'] === state.self.selectedAppointmentId && !requestHeaders['x-on-behalf-appointment-id']);
      state.cache = validateWorkbenchCache({ status: response.status(), headers: lowerHeaders(await response.allHeaders()), requestHeaders, body: response.status() === 200 ? await response.json() : undefined, actorScopeKey: state.self.actorScopeKey, generation: state.currentCount + 1 }, state.cache);
      const value = state.cache.envelope; check(exact(value, ['todaySummary','currentCard','nextSummaries','waitingCount','chatComposer']));
      check(typeof value.todaySummary === 'string' && value.currentCard === null && Array.isArray(value.nextSummaries) && value.nextSummaries.length === 0 && value.waitingCount === 1);
      check(value.chatComposer?.enabled === false && value.chatComposer.targetTaskId === null);
      state.authorization = authorization; state.expiresAt = expiry(authorization); state.currentCount++; report.counts.current++;
      report.http.push({ kind: 'CURRENT', status: response.status(), offsetMs: clock.now() - started });
    } else if (url.origin === new URL(BUSINESS_PIN.issuer).origin && url.pathname === TOKEN) check(response.status() === 200);
    else if (url.origin === new URL(BUSINESS_PIN.issuer).origin && url.pathname === LOGOUT) report.http.push({ kind: 'LOGOUT_GET', status: response.status(), offsetMs: clock.now() - started });
    else if (url.origin === new URL(BUSINESS_PIN.issuer).origin && url.pathname === CONFIRM) report.http.push({ kind: 'LOGOUT_CONFIRM', status: response.status(), offsetMs: clock.now() - started });
  };
  const cleared = async (page: Page, message: string) => {
    check(new URL(page.url()).origin !== BUSINESS_PIN.origin || new URL(page.url()).pathname === '/login');
    check(await page.getByText(message, { exact: true }).isVisible());
    check(await page.locator('.session-actions > span').count() === 0 && await page.locator('article.current-card').count() === 0 && await page.locator('.next-summary').count() === 0);
    check(await page.locator('textarea, input:not([type="hidden"])').count() === 0);
  };
  const waitingUi = async (page: Page, state: Seen) => {
    check(state.self && state.currentCount > 0);
    await page.getByRole('heading', { name: '当前无可处理责任，另有等待事项', exact: true }).waitFor({ state: 'visible', timeout: 15_000 });
    check((await page.locator('.waiting-count > span').first().textContent())?.trim() === '等待 1');
    check((await page.locator('.session-actions > span').textContent())?.trim() === `${state.self.displayName} · ${state.self.appointmentChoices[0].label}`);
    check(await page.locator('article.current-card').count() === 0 && await page.locator('.next-summary').count() === 0 && await page.locator('textarea, input:not([type="hidden"])').count() === 0);
  };
  const enter = async (page: Page, credentials: boolean) => {
    const state = seen.get(page)!; const priorSelf = state.selfCount, priorCurrent = state.currentCount;
    await page.goto(BUSINESS_PIN.origin + '/login', { waitUntil: 'domcontentloaded', timeout: 30_000 });
    await page.getByRole('button', { name: '登录工作台', exact: true }).click({ timeout: 30_000 });
    if (credentials) {
      await page.waitForURL(url => url.origin === new URL(BUSINESS_PIN.issuer).origin && url.pathname.startsWith('/realms/local-r1/'), { timeout: 30_000 });
      await page.locator('input[name="username"]').fill(environment.accounts.contact.username);
      await page.locator('input[name="password"]').fill(environment.accounts.contact.password);
      await page.locator('input[type="submit"],button[type="submit"]').click({ timeout: 30_000 });
    }
    await until(() => state.selfCount > priorSelf);
    await page.getByRole('heading', { name: '请选择本次办理身份', exact: true }).waitFor({ state: 'visible', timeout: 30_000 });
    check((await page.locator('.choice-account > span').textContent())?.trim() === state.self.displayName && await page.locator('#choice-own').inputValue() === state.self.selectedAppointmentId);
    await page.getByRole('button', { name: '确认本次身份', exact: true }).click({ timeout: 30_000 });
    await until(() => state.currentCount > priorCurrent); await waitingUi(page, state);
  };
  try {
    check(JSON.stringify(environment.readonlyProof) === JSON.stringify(READONLY_WAITING_PIN));
    await environment.assertUnchanged();
    if (Date.parse(READONLY_WAITING_PIN.dueAt) - clock.wallNow() < 600_000) { report.status = 'NOT_EXECUTED'; throw Error(); }
    if (typeof browserSource === 'function') browser = await browserSource();
    check(browser); environment.verifyBrowser(browser.version());
    const context = await browser.newContext({ serviceWorkers: 'block', ignoreHTTPSErrors: false, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' });
    context.on('serviceworker', () => fail('NETWORK'));
    await context.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url()), method = request.method();
      try {
        healthy(); check(!request.serviceWorker() && allowed(url, method));
        if (url.pathname === TOKEN && method === 'POST') check(['authorization_code','refresh_token'].includes(new URLSearchParams(request.postData() ?? '').get('grant_type') ?? ''));
        if (url.pathname === LOGOUT && method === 'GET') check(!url.searchParams.has('id_token_hint'));
        if (url.pathname === LOGOUT && method === 'GET' && faultArmed) {
          check(pageFor(request) === source && !faultRequest); faultRequest = request; report.counts.faultedLogoutRequests++; await faultGate;
          await route.abort('failed'); return;
        }
        if (url.pathname === LOGOUT && method === 'GET') report.counts.forwardedLogoutGets++;
        if (url.pathname === CONFIRM && method === 'POST') report.counts.confirmPosts++;
        await route.continue();
      } catch {
        report.counts.blockedRequests++; if (url.origin === BUSINESS_PIN.origin && !['GET','HEAD','OPTIONS'].includes(method)) report.counts.businessWrites++;
        fail('NETWORK'); await route.abort().catch(() => {});
      }
    });
    source = await context.newPage(); peer = await context.newPage();
    for (const page of [source,peer]) {
      seen.set(page, { self: undefined, selfCount: 0, currentCount: 0, authorization: '', expiresAt: 0 });
      page.on('response', response => { observation = observation.then(() => observe(response)).catch(() => fail(step)); });
      page.on('requestfailed', request => { report.counts.requestFailed++; if (!(faultReleased && request === faultRequest)) fail('NETWORK'); });
    }
    step = 'LOGIN'; await enter(source, true); step = 'IDENTITY'; await enter(peer, false); step = 'WAITING';
    check(report.counts.successfulSelf >= 2 && report.counts.current >= 2);

    step = 'FAULT_LOCAL_CLEAR'; faultArmed = true;
    const faultClick = source.getByRole('button', { name: '退出', exact: true }).click({ timeout: 30_000, noWaitAfter: true }).catch(() => {});
    await until(() => !!faultRequest); await cleared(source, SOURCE_MESSAGE); report.checks.sourceCleared = true;
    await cleared(peer, PEER_MESSAGE); report.checks.peerCleared = true;
    check(await peer.getByText(SUCCESS_CLAIM, { exact: true }).count() === 0); report.checks.peerDidNotClaimSuccess = true;
    check(report.counts.forwardedLogoutGets === 0); report.checks.faultNotForwarded = true;
    step = 'FAULT_TRANSPORT'; faultReleased = true; releaseFault(); await faultClick; await until(() => report.counts.requestFailed === 1);
    check(report.counts.faultedLogoutRequests === 1 && report.counts.requestFailed === 1 && report.counts.forwardedLogoutGets === 0); report.checks.faultRequestFailed = true; report.scenarios.faultLogout = 'PASSED';

    step = 'REENTRY'; faultArmed = false; await enter(peer, false); await enter(source, false); report.checks.bothReentered = true;
    check(report.counts.successfulSelf >= 4); const old = [seen.get(source)!, seen.get(peer)!].map(value => ({ authorization: value.authorization, expiresAt: value.expiresAt }));

    step = 'CONFIRMED_LOGOUT'; await source.getByRole('button', { name: '退出', exact: true }).click({ timeout: 30_000, noWaitAfter: true });
    await source.waitForURL(url => url.origin === new URL(BUSINESS_PIN.issuer).origin && url.pathname === LOGOUT, { timeout: 30_000 });
    await cleared(peer, PEER_MESSAGE);
    const form = source.locator('form#kc-logout'); await form.waitFor({ state: 'visible', timeout: 30_000 });
    const action = await form.getAttribute('action'); check(action); const actionUrl = new URL(action, BUSINESS_PIN.issuer);
    check(actionUrl.origin === new URL(BUSINESS_PIN.issuer).origin && actionUrl.pathname === CONFIRM);
    const confirm = source.locator('#kc-logout'); check(await confirm.isVisible()); report.checks.confirmFormVisible = true;
    await confirm.click({ timeout: 30_000 });
    await source.waitForURL(url => url.origin === BUSINESS_PIN.origin && url.pathname === '/login', { timeout: 30_000 });
    check(Number(report.counts.forwardedLogoutGets) === 1 && Number(report.counts.confirmPosts) === 1); report.checks.serverLogoutConfirmed = true; report.scenarios.confirmedLogout = 'PASSED';

    step = 'TOKEN_REJECTION';
    for (const state of old) for (const [path, kind] of [[SELF,'OLD_SELF'],[CURRENT,'OLD_CURRENT']] as const) {
      check(clock.wallNow() < state.expiresAt);
      const response = await context.request.get(BUSINESS_PIN.origin + path, { headers: { Authorization: state.authorization, 'X-Appointment-Id': environment.resources['appointment-contact'] }, maxRedirects: 0, failOnStatusCode: false });
      check(clock.wallNow() < state.expiresAt); const status = response.status(); report.counts.tokenProbes++; report.http.push({ kind, status, offsetMs: clock.now() - started }); check(status === 401);
    }
    report.checks.oldTokensRejected = true; report.scenarios.tokenRejection = 'PASSED';

    step = 'HISTORY';
    for (const page of [source,peer]) {
      await page.bringToFront();
      const navigated = page.waitForEvent('framenavigated', { timeout: 30_000 }).then(() => true).catch(() => false);
      const navigation = await page.goBack({ waitUntil: 'domcontentloaded', timeout: 30_000 });
      if (navigation === null && !(await navigated)) { report.status = 'NOT_TRIGGERED'; report.scenarios.historySafety = 'NOT_TRIGGERED'; throw Error(); }
      await page.bringToFront(); const url = new URL(page.url()); check(url.origin !== BUSINESS_PIN.origin || url.pathname !== '/workbench');
      check(await page.locator('.session-actions > span').count() === 0 && await page.locator('article.current-card').count() === 0 && await page.locator('.next-summary').count() === 0 && await page.locator('textarea, input:not([type="hidden"])').count() === 0);
      check(await page.getByText(baseline.self.displayName, { exact: true }).count() === 0);
    }
    report.checks.historyDidNotRevive = true; report.scenarios.historySafety = 'PASSED'; report.status = 'PASSED_READ_ONLY_SUBSCENARIO'; report.failureStep = null;
  } catch {
    report.failureStep ??= step;
    if (report.status === 'PASSED_READ_ONLY_SUBSCENARIO') report.status = 'FAILED';
  } finally {
    try { await browser?.close(); } catch { fail('CLEANUP'); }
    for (const state of seen.values()) { state.authorization = ''; state.expiresAt = 0; state.self = undefined; }
    await observation;
    try { await environment.assertUnchanged(); report.checks.environmentUnchanged = true; report.checks.journalUnchanged = true; report.checks.checkpointUnchanged = true; } catch { fail('FINAL_GUARD'); }
    if (stopped) report.status = 'FAILED';
  }
  noLinks(environment.outputDirectory); const fd = openSync(report.reportPath, 'wx', 0o600);
  try { writeFileSync(fd, JSON.stringify(report)); fsyncSync(fd); } finally { closeSync(fd); }
  noLinks(report.reportPath); return report;
}
