import { test, chromium, type Browser, type Page } from '@playwright/test';
import { readFileSync, existsSync, writeFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { resolve, join } from 'node:path';
import { check, loadLocalEnvironment, noLinks, ORIGIN, ISSUER, protect, runtime, sha } from '../fixtures/local-environment';
import { allowReadOnlyRequest, COMPLETE_JOURNAL_SHA, readOnlyOutcome, type ReadOnlyEvidence } from '../fixtures/readonly-session';

test('T9-READONLY-SESSION', async ({}, info) => {
  let browser: Browser | undefined, page: Page | undefined;
  let step = 'APPROVAL', probing = false, boundaryPending = false, work: Promise<void> | undefined;
  const evidence: ReadOnlyEvidence = { refreshObserved: false, refreshStatus: null, refreshDuringBoundary: false,
    organizationStatus: null, principalStatus: null, adminVisible: false, loginVisible: false, sessionNoticeVisible: false,
    blockedBusinessWrites: 0, blockedRequests: 0, boundaryCompleted: false, journalBefore: '', journalAfter: '', environmentUnchanged: false, failureStep: null };
  const journal = join(runtime, 'task9-identity-operation.json');
  const journalHash = () => {
    noLinks(journal); check(!existsSync(journal + '.pending') && !existsSync(journal + '.completion.pending'));
    return sha(readFileSync(journal));
  };
  const ui = async () => {
    if (!page || page.isClosed()) return;
    evidence.adminVisible = await page.getByRole('navigation', { name: '身份管理', exact: true }).isVisible();
    evidence.loginVisible = await page.getByRole('button', { name: '登录工作台', exact: true }).isVisible();
    evidence.sessionNoticeVisible = await page.getByText('请重新登录以核对当前会话。', { exact: true }).isVisible()
      || await page.getByText('登录服务暂不可用，请稍后重试。', { exact: true }).isVisible()
      || await page.getByText('登录服务暂不可用，请重新登录。', { exact: true }).isVisible()
      || await page.getByText('登录状态已失效，请重新登录后继续。', { exact: true }).isVisible();
  };
  let environment: Awaited<ReturnType<typeof loadLocalEnvironment>> | undefined;
  try {
    check(process.env.TASK9_READONLY_SESSION === 'APPROVED_EXISTING_RUN_ONLY');
    check(process.env.NODE_TLS_REJECT_UNAUTHORIZED !== '0');
    check(!process.env.TASK9_RECOVER_COMMAND_ID && !process.env.TASK9_CONTINUE_RUN_ID);
    check(info.config.reporter.length === 1 && info.config.reporter[0][0].replaceAll('\\', '/').endsWith('/e2e/reporters/readonly-reporter.ts'));
    check(info.project.use.trace === 'off' && info.project.use.video === 'off' && info.project.use.screenshot === 'off' && !info.project.use.storageState);
    environment = await loadLocalEnvironment();
    evidence.journalBefore = journalHash(); check(evidence.journalBefore === COMPLETE_JOURNAL_SHA);
    browser = await chromium.launch({ headless: true }); environment.verifyBrowser(browser.version());
    const context = await browser.newContext({ ignoreHTTPSErrors: false, serviceWorkers: 'block', locale: 'zh-CN', timezoneId: 'Asia/Shanghai' });
    await context.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url());
      if (!allowReadOnlyRequest(url, request.method())) {
        evidence.blockedRequests++;
        if (url.origin === ORIGIN && !['GET', 'HEAD', 'OPTIONS'].includes(request.method())) evidence.blockedBusinessWrites++;
        await route.abort().catch(() => {}); return;
      }
      if (probing && request.method() === 'POST' && request.url() === ISSUER + '/protocol/openid-connect/token'
        && new URLSearchParams(request.postData() ?? '').get('grant_type') === 'refresh_token' && !work) {
        evidence.refreshObserved = true; boundaryPending = true;
        // Start the actual uncached checks but immediately let the refresh route
        // progress. This sequence mirrors full snapshot plus three protections.
        work = (async () => {
          try {
            await environment!.assertUnchanged(); await protect(); await protect(); await protect();
            evidence.boundaryCompleted = true;
          } catch { evidence.failureStep ??= 'BOUNDARY'; }
          finally { boundaryPending = false; }
        })();
      }
      await route.continue().catch(() => { evidence.failureStep ??= 'ROUTE'; });
    });
    page = await context.newPage();
    page.on('response', response => {
      const url = new URL(response.url());
      if (probing && response.request().method() === 'POST' && response.url() === ISSUER + '/protocol/openid-connect/token'
        && new URLSearchParams(response.request().postData() ?? '').get('grant_type') === 'refresh_token') {
        evidence.refreshStatus = response.status(); evidence.refreshDuringBoundary ||= boundaryPending;
      }
      if (probing && url.origin === ORIGIN && url.pathname === '/api/v1/admin/identity/organizations') evidence.organizationStatus = response.status();
      if (probing && url.origin === ORIGIN && url.pathname === '/api/v1/admin/identity/principals') evidence.principalStatus = response.status();
    });
    step = 'LOGIN'; await page.goto(ORIGIN + '/login');
    await page.getByRole('button', { name: '登录工作台', exact: true }).click({ timeout: 30_000 });
    await page.waitForURL(url => url.origin === new URL(ISSUER).origin && url.pathname.startsWith('/realms/local-r1/'));
    await page.locator('input[name="username"]').fill(environment.accounts.founder.username);
    await page.locator('input[name="password"]').fill(environment.accounts.founder.password);
    const self = page.waitForResponse(r => r.url() === ORIGIN + '/api/v1/session/context', { timeout: 30_000 }).catch(() => null);
    await page.locator('input[type="submit"],button[type="submit"]').click();
    const response = await self; check(response && response.status() === 200 && response.headers()['cache-control']?.includes('no-store'));
    const contextData = await response.json();
    check(contextData.state === 'READY' && contextData.selectedAppointmentId === environment.bootstrap.appointmentId && contextData.selectedOnBehalfAppointmentId === null && contextData.canEnterIdentityAdmin === true && contextData.canEnterWorkbench === false);
    step = 'ADMIN_ENTRY';
    await page.getByRole('button', { name: '进入身份管理', exact: true }).click({ timeout: 30_000 });
    await page.getByRole('button', { name: '确认本次身份', exact: true }).click({ timeout: 30_000 });
    await page.getByRole('heading', { name: '用户与身份主体', exact: true }).waitFor({ timeout: 30_000 });
    step = 'NATURAL_REFRESH_WINDOW';
    await new Promise(resolve => setTimeout(resolve, 250_000)); // No keepalive or forced token refresh.
    probing = true; step = 'ORGANIZATION_HTTP';
    const org = page.waitForResponse(r => new URL(r.url()).origin === ORIGIN && new URL(r.url()).pathname === '/api/v1/admin/identity/organizations', { timeout: 25_000 }).catch(() => null);
    await page.locator('nav a[href="/admin/identity/organizations"]').click({ timeout: 25_000 });
    const orgResponse = await org; check(orgResponse?.status() === 200 && orgResponse.headers()['cache-control']?.includes('no-store'));
    step = 'ORGANIZATION_UI'; await page.getByRole('button', { name: '新增组织', exact: true }).waitFor({ timeout: 15_000 });
    step = 'PRINCIPAL_HTTP';
    const principal = page.waitForResponse(r => new URL(r.url()).origin === ORIGIN && new URL(r.url()).pathname === '/api/v1/admin/identity/principals', { timeout: 25_000 }).catch(() => null);
    await page.locator('nav a[href="/admin/identity/principals"]').click({ timeout: 25_000 });
    const principalResponse = await principal; check(principalResponse?.status() === 200 && principalResponse.headers()['cache-control']?.includes('no-store'));
    step = 'PRINCIPAL_UI'; await page.getByRole('heading', { name: '用户与身份主体', exact: true }).waitFor({ timeout: 15_000 });
  } catch { evidence.failureStep ??= step; }
  finally {
    await work;
    try { await ui(); } catch { evidence.failureStep ??= 'SAFE_UI'; }
    try {
      if (environment) { await environment.assertUnchanged(); evidence.environmentUnchanged = true; await protect(); evidence.journalAfter = journalHash(); }
    } catch { evidence.failureStep ??= 'FINAL_BOUNDARY'; }
    try { await browser?.close(); } catch { evidence.failureStep ??= 'CLEANUP'; }
  }
  const status = readOnlyOutcome(evidence);
  const reportPath = resolve(__dirname, '../../.superpowers/sdd/2026-09-08-task9-real-user-access-plan/output', `task96e-readonly-session-${randomUUID()}.json`);
  writeFileSync(reportPath, JSON.stringify({ status, ...evidence, environmentDigest: environment?.environmentDigest ?? null,
    U01: 'NOT_EXECUTED', U02: 'NOT_EXECUTED', U03: 'NOT_EXECUTED' }), { flag: 'wx', mode: 0o600 });
  if (status !== 'PASSED_READ_ONLY_SUBSCENARIO') throw new Error('T9_READONLY_' + (status === 'NOT_TRIGGERED' ? 'NOT_TRIGGERED' : 'FAILED'));
});
test.afterEach(async ({}, info) => {
  info.errors.splice(0, info.errors.length, ...info.errors.map(() => ({ message: 'T9_READONLY_FAILED_CLOSED' })));
});
