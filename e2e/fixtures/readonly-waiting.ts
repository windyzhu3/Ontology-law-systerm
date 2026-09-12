import type { Browser, Page, Request, Response, TestInfo } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { closeSync, fsyncSync, openSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { performance } from 'node:perf_hooks';
import { check, noLinks } from './local-environment';
import { BUSINESS_PIN, READONLY_WAITING_PIN, type loadReadOnlyWaitingEnvironment } from './business-environment';
import { exactHeaderTokens, lowerHeaders, validateWorkbenchCache, type WorkbenchCache } from './workbench-cache';
import { sameValues } from '../../apps/workbench/src/features/workcard/contract';

export const READONLY_WAITING_CASE = 'T9-W06-W08-readonly-waiting-refresh';
const SELF = '/api/v1/session/context', CURRENT = '/api/v1/workcards/current';
const TOKEN = BUSINESS_PIN.issuer + '/protocol/openid-connect/token';
const PAUSED = '自动刷新已暂停，可手动刷新。';
type Environment = Awaited<ReturnType<typeof loadReadOnlyWaitingEnvironment>>;
type FailureStep = 'PRECHECK' | 'LOGIN' | 'IDENTITY' | 'NETWORK' | 'CACHE' | 'INITIAL_UI' | 'AUTOMATIC' | 'EXPIRY' | 'MANUAL' | 'QUIET' | 'CLEANUP' | 'FINAL_GUARD' | null;
type Status = 'PASSED_READ_ONLY_SUBSCENARIO' | 'FAILED' | 'NOT_TRIGGERED' | 'NOT_EXECUTED';
export interface WaitingClock { now(): number; wallNow(): number; sleep(ms: number): Promise<void>; deadline?: number }
const realClock: WaitingClock = { now: () => performance.now(), wallNow: () => Date.now(), sleep: ms => new Promise(resolve => setTimeout(resolve, ms)) };
export interface WaitingDependencies { clock?: WaitingClock; reportId?: () => string }
export interface WaitingReport {
  caseIdentity: typeof READONLY_WAITING_CASE; status: Status; buildSha: string; environmentDigest: string; apiIdentity: string; executedAt: string;
  journalSha256: string; checkpointSha256: string; reportPath: string; UAT: 'USER_CONFIRMED_CLOSED';
  counts: { current: number; automatic: number; manual: number; notModified: number; refresh: number; blockedRequests: number; businessWrites: number };
  http: Array<{ kind: 'SELF' | 'CURRENT' | 'REFRESH'; status: number; offsetMs: number }>;
  checks: { sameActor: boolean; changedBearer: boolean; unchangedEnvelope: boolean; paused: boolean; quiet: boolean; environmentUnchanged: boolean; journalUnchanged: boolean; checkpointUnchanged: boolean };
  quietDurationMs: number; failureStep: FailureStep;
}
export function assertReadOnlyWaitingConfig(info: TestInfo): void {
  check(info.project.name === 'approved-local-readonly-waiting' && info.project.retries === 0 && info.project.repeatEach === 1 && info.project.timeout === 540_000 && info.config.workers === 1 && !info.config.fullyParallel);
  check(info.config.reporter.length === 1 && info.config.reporter[0][0].replaceAll('\\','/').endsWith('/e2e/reporters/readonly-waiting-reporter.ts'));
  const use = info.project.use;
  check(use.trace === 'off' && use.video === 'off' && use.screenshot === 'off' && use.storageState === undefined && use.serviceWorkers === 'block' && use.ignoreHTTPSErrors === false);
}
function allowed(url: URL, method: string): boolean {
  const read = ['GET','HEAD','OPTIONS'].includes(method);
  if (url.origin === BUSINESS_PIN.origin) return read && (!(url.pathname === '/api' || url.pathname.startsWith('/api/')) || [SELF,CURRENT].includes(url.pathname));
  if (url.origin !== new URL(BUSINESS_PIN.issuer).origin) return false;
  if (read) return url.pathname === '/realms/local-r1' || url.pathname.startsWith('/realms/local-r1/') || url.pathname.startsWith('/resources/');
  return method === 'POST' && (url.href === TOKEN || url.pathname === '/realms/local-r1/login-actions/authenticate');
}
function expiryHint(bearer: string): number {
  const claims = JSON.parse(Buffer.from(bearer.slice(7).split('.')[1], 'base64url').toString('utf8'));
  check(Number.isSafeInteger(claims.exp) && claims.exp > 0); return claims.exp * 1000;
}

// Only clock/entropy and the external Playwright transport vary offline. All validation,
// observation, route decisions, lifecycle and exclusive report publication stay real.
export async function runReadOnlyWaiting(browserSource: Browser | (() => Promise<Browser>) | undefined, environment: Environment, dependencies: WaitingDependencies = {}): Promise<WaitingReport> {
  let browser = typeof browserSource === 'function' ? undefined : browserSource;
  const clock = dependencies.clock ?? realClock, started = clock.now();
  const deadline = Math.min(started + 510_000, clock.deadline ?? Infinity);
  const id = (dependencies.reportId ?? randomUUID)(); check(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(id));
  const report: WaitingReport = { caseIdentity: READONLY_WAITING_CASE, status: 'FAILED', buildSha: environment.buildSha, environmentDigest: environment.environmentDigest, apiIdentity: environment.apiIdentity,
    executedAt: new Date(clock.wallNow()).toISOString(), journalSha256: READONLY_WAITING_PIN.journalSha256, checkpointSha256: READONLY_WAITING_PIN.checkpointSha256,
    reportPath: resolve(environment.outputDirectory, `task96q-readonly-waiting-${id}.json`), UAT: 'USER_CONFIRMED_CLOSED',
    counts: { current: 0, automatic: 0, manual: 0, notModified: 0, refresh: 0, blockedRequests: 0, businessWrites: 0 }, http: [],
    checks: { sameActor: false, changedBearer: false, unchangedEnvelope: false, paused: false, quiet: false, environmentUnchanged: false, journalUnchanged: false, checkpointUnchanged: false }, quietDurationMs: 0, failureStep: null };
  let step: FailureStep = 'PRECHECK', phase: 'INITIAL' | 'AUTO' | 'EXPIRED' | 'MANUAL' | 'QUIET' = 'INITIAL';
  let page: Page | undefined, observation = Promise.resolve(), stopped = false, self: any, cache: WorkbenchCache | undefined, baseline: WorkbenchCache['envelope'] | undefined;
  let completed = 0, lastStart = 0, bearer = '', tokenExpiry = 0, beforeManualBearer = '';
  const snapshots = new WeakMap<Request, { generation: number; actor: string; authorization: string; headers: Record<string,string>; offsetMs: number }>();
  const fail = (at: FailureStep) => { report.failureStep ??= at; stopped = true; };
  const healthy = () => { check(!stopped && clock.now() < deadline); };
  const settle = async () => { await observation; healthy(); };
  const delay = async (ms: number) => { check(ms > 0 && ms <= 30_000); await clock.sleep(ms); await settle(); };
  const until = async (done: () => boolean, maximum: number) => {
    const end = clock.now() + maximum;
    while (!done()) { healthy(); check(clock.now() < end); await delay(Math.min(500, end - clock.now())); }
    await settle();
  };
  const identity = (value: any) => {
    const appointment = environment.resources['appointment-contact'];
    check(value?.state === 'READY' && value.selectedAppointmentId === appointment && value.selectedOnBehalfAppointmentId === null && value.canEnterWorkbench === true && value.canEnterIdentityAdmin === false);
    check(Array.isArray(value.appointmentChoices) && value.appointmentChoices.length === 1 && value.appointmentChoices[0].id === appointment && typeof value.appointmentChoices[0].label === 'string');
    check(typeof value.displayName === 'string' && /^ask1\.[A-Za-z0-9_-]{43}$/.test(value.actorScopeKey));
    if (self) check(self.actorScopeKey === value.actorScopeKey && self.selectedAppointmentId === value.selectedAppointmentId && self.displayName === value.displayName && self.appointmentChoices[0].label === value.appointmentChoices[0].label);
  };
  const observe = async (response: Response) => {
    const request = response.request(), url = new URL(response.url()), responseHeaders = lowerHeaders(await response.allHeaders());
    if (url.origin === BUSINESS_PIN.origin && url.pathname === SELF) {
      check(request.method() === 'GET' && response.status() === 200 && exactHeaderTokens(responseHeaders['cache-control'], ['no-store']));
      const value = await response.json(); identity(value); self = structuredClone(value);
      report.http.push({ kind: 'SELF', status: 200, offsetMs: clock.now() - started });
    } else if (response.url() === TOKEN && request.method() === 'POST') {
      check(response.status() === 200);
      if (new URLSearchParams(request.postData() ?? '').get('grant_type') === 'refresh_token') {
        report.counts.refresh++;
        report.http.push({ kind: 'REFRESH', status: response.status(), offsetMs: clock.now() - started });
      }
    } else if (url.origin === BUSINESS_PIN.origin && url.pathname === CURRENT) {
      const snapshot = snapshots.get(request); check(snapshot && self && snapshot.actor === self.actorScopeKey);
      const actualHeaders = lowerHeaders(await request.allHeaders());
      check(actualHeaders.authorization === snapshot.authorization && actualHeaders['x-appointment-id'] === environment.resources['appointment-contact'] && !actualHeaders['x-on-behalf-appointment-id']);
      // Pinned Playwright refuses body access for every 300–399 response. A 304
      // retains only the already parsed same-Actor envelope; it is never parsed as 200.
      const body = response.status() === 200 ? await response.json() : undefined;
      check(snapshot.actor === self.actorScopeKey && snapshot.generation === completed + 1);
      cache = validateWorkbenchCache({ status: response.status(), headers: responseHeaders, requestHeaders: snapshot.headers, body, actorScopeKey: snapshot.actor, generation: snapshot.generation }, cache);
      const envelope = cache.envelope;
      check(envelope.currentCard === null && envelope.nextSummaries.length === 0 && envelope.waitingCount === 1 && !envelope.chatComposer.enabled && envelope.chatComposer.targetTaskId === null);
      if (baseline) check(sameValues(baseline, envelope)); else { check(response.status() === 200); baseline = structuredClone(envelope); }
      bearer = snapshot.authorization; tokenExpiry = expiryHint(bearer); completed++;
      report.counts.notModified += response.status() === 304 ? 1 : 0;
      report.http.push({ kind: 'CURRENT', status: response.status(), offsetMs: snapshot.offsetMs });
      report.checks.sameActor = true; report.checks.unchangedEnvelope = true;
    }
  };
  const ui = async (paused = false) => {
    check(page && baseline && self);
    check(await page.evaluate(() => document.visibilityState === 'visible'));
    const heading = page.getByRole('heading', { name: '当前无可处理责任，另有等待事项', exact: true });
    await heading.waitFor({ state: 'visible', timeout: 15_000 }); check(await heading.isVisible());
    check(await page.locator('article.current-card').count() === 0 && await page.locator('.next-summary').count() === 0);
    check((await page.locator('.waiting-count > span').textContent())?.trim() === '等待 1');
    check(await page.locator('.today-summary p').isVisible() && (await page.locator('.today-summary p').textContent())?.trim() === baseline.todaySummary);
    check((await page.locator('.session-actions > span').textContent())?.trim() === `${self.displayName} · ${self.appointmentChoices[0].label}`);
    if (paused) { check(await page.getByText(PAUSED, { exact: true }).isVisible()); report.checks.paused = true; }
  };
  try {
    check(sameValues(environment.readonlyProof, READONLY_WAITING_PIN));
    await environment.assertUnchanged();
    if (Date.parse(READONLY_WAITING_PIN.dueAt) - clock.wallNow() < 600_000) { report.status = 'NOT_EXECUTED'; throw Error(); }
    if (typeof browserSource === 'function') browser = await browserSource();
    check(browser); environment.verifyBrowser(browser.version());
    const context = await browser.newContext({ serviceWorkers: 'block', ignoreHTTPSErrors: false, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' });
    context.on('serviceworker', () => fail('NETWORK'));
    await context.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url()), method = request.method(), start = clock.now();
      try {
        healthy(); check(!request.serviceWorker() && allowed(url, method));
        if (url.href === TOKEN && method === 'POST') check(['authorization_code','refresh_token'].includes(new URLSearchParams(request.postData() ?? '').get('grant_type') ?? ''));
        if (url.origin === BUSINESS_PIN.origin && url.pathname === CURRENT) {
          check(method === 'GET'); await settle(); identity(self);
          const requestHeaders = lowerHeaders(await request.allHeaders());
          check(/^Bearer \S+$/.test(requestHeaders.authorization ?? '') && requestHeaders['x-appointment-id'] === self.selectedAppointmentId && !requestHeaders['x-on-behalf-appointment-id']);
          const generation = report.counts.current + 1;
          if (phase === 'INITIAL') check(generation === 1);
          else if (phase === 'AUTO') { check(generation >= 2 && generation <= 7 && start - lastStart >= 25_000 && start - lastStart <= 45_000); report.counts.automatic++; }
          else { check(phase === 'MANUAL' && generation === 8 && report.counts.manual === 0); report.counts.manual++; }
          report.counts.current++; lastStart = start;
          snapshots.set(request, { generation, actor: self.actorScopeKey, authorization: requestHeaders.authorization, headers: { ...requestHeaders }, offsetMs: start - started });
        }
        await route.continue();
      } catch { report.counts.blockedRequests++; if (url.origin === BUSINESS_PIN.origin && !['GET','HEAD','OPTIONS'].includes(method)) report.counts.businessWrites++; fail('NETWORK'); await route.abort().catch(() => {}); }
    });
    page = await context.newPage();
    page.on('response', response => { observation = observation.then(() => observe(response)).catch(() => fail('CACHE')); });
    page.on('requestfailed', () => fail('NETWORK'));
    step = 'LOGIN'; await page.goto(BUSINESS_PIN.origin + '/login', { waitUntil: 'domcontentloaded', timeout: 30_000 });
    await page.getByRole('button', { name: '登录工作台', exact: true }).click({ timeout: 30_000 });
    await page.waitForURL(url => url.origin === new URL(BUSINESS_PIN.issuer).origin && url.pathname.startsWith('/realms/local-r1/'), { timeout: 30_000 });
    await page.locator('input[name="username"]').fill(environment.accounts.contact.username);
    await page.locator('input[name="password"]').fill(environment.accounts.contact.password);
    await page.locator('input[type="submit"],button[type="submit"]').click({ timeout: 30_000 });
    await until(() => !!self, 30_000); step = 'IDENTITY';
    await page.getByRole('heading', { name: '请选择本次办理身份', exact: true }).waitFor({ timeout: 30_000 });
    check((await page.locator('.choice-account > span').textContent())?.trim() === self.displayName);
    check(await page.locator('#choice-own').inputValue() === self.selectedAppointmentId);
    await page.getByRole('button', { name: '确认本次身份', exact: true }).click({ timeout: 30_000 });
    await until(() => completed === 1, 30_000); step = 'INITIAL_UI'; await ui();
    phase = 'AUTO'; step = 'AUTOMATIC'; await until(() => completed === 7, 240_000); await ui(true);
    phase = 'EXPIRED'; step = 'EXPIRY'; beforeManualBearer = bearer;
    const wait = Math.max(0, tokenExpiry + 2000 - clock.wallNow());
    if (wait > 300_000 || clock.now() + wait + 90_000 >= deadline) { report.status = 'NOT_TRIGGERED'; throw Error(); }
    const expires = clock.now() + wait;
    while (clock.now() < expires) { await delay(Math.min(30_000, expires - clock.now())); await ui(true); }
    check(completed === 7 && report.counts.current === 7); const refreshBefore = report.counts.refresh;
    phase = 'MANUAL'; step = 'MANUAL'; await page.getByRole('button', { name: '刷新当前责任', exact: true }).click({ timeout: 30_000 });
    await until(() => completed === 8, 30_000); await ui(true);
    report.checks.changedBearer = bearer !== beforeManualBearer;
    if (!report.checks.changedBearer || report.counts.refresh <= refreshBefore || report.counts.notModified === 0) { report.status = 'NOT_TRIGGERED'; throw Error(); }
    phase = 'QUIET'; step = 'QUIET'; const quietStart = clock.now();
    while (clock.now() - quietStart < 60_000) { await delay(Math.min(30_000, 60_000 - (clock.now() - quietStart))); await ui(true); }
    report.quietDurationMs = clock.now() - quietStart; check(Number(completed) === 8 && Number(report.counts.current) === 8);
    report.checks.quiet = true; report.status = 'PASSED_READ_ONLY_SUBSCENARIO';
  } catch { report.failureStep ??= step; if (report.status === 'PASSED_READ_ONLY_SUBSCENARIO') report.status = 'FAILED'; }
  finally {
    try { await browser?.close(); } catch { fail('CLEANUP'); }
    await observation;
    try { await environment.assertUnchanged(); report.checks.environmentUnchanged = true; report.checks.journalUnchanged = true; report.checks.checkpointUnchanged = true; } catch { fail('FINAL_GUARD'); }
    if (stopped) report.status = 'FAILED';
  }
  noLinks(environment.outputDirectory);
  const fd = openSync(report.reportPath, 'wx', 0o600);
  try { writeFileSync(fd, JSON.stringify(report)); fsyncSync(fd); } finally { closeSync(fd); }
  noLinks(report.reportPath);
  return report;
}
