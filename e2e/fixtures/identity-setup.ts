import type { Browser, BrowserContext, Page, Request } from '@playwright/test';
import { expect } from '@playwright/test';
import { createHash, randomUUID } from 'node:crypto';
import { existsSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { ALIASES, ISSUER, ORIGIN, check, exact, loadLocalEnvironment, protect, runtime, sha, uuid, type Alias, type LocalEnvironment } from './local-environment';
import { CASES, OperationJournal, PATH_FACT, type CaseId, type Command } from './operation-journal';
import { safeFailureCode } from '../reporters/safe-reporter';

export async function dispatchObserved(journal: OperationJournal, command: Command, send: () => Promise<void>) { journal.begin(command); await send(); }
export function requireReceiptLocationBuild(buildSha: string): void {
  check(/^[0-9a-f]{40}$/.test(buildSha) && buildSha !== '04bd695f7a8f656a5ed8fb96c5168e44a91bab8d');
}

export const NAMES = { intake: '本地合成受理', supervisor: '本地合成主管', contact: '本地合成首联', delegate: '本地合成代办' };
export const GRANTS = { intake: ['LEAD_CAPTURE', 'LEAD_INGRESS_RESOLVE', 'LEAD_INGRESS_COMPLETE', 'SOURCE_INTAKE_REQUEST_ACK'], supervisor: ['LEAD_ASSIGN', 'LEAD_ROUTING_DECIDE', 'LEAD_VALIDITY_REVIEW'] };

const SELF = '/api/v1/session/context';
const COLLECTIONS = Object.keys(PATH_FACT);
type Session = { context: BrowserContext; page: Page; self: any; auth: Record<string, string> };
type Armed = { step: string; path: string; body: Record<string, unknown> };
export function publicFactRef(actor: LocalEnvironment['bootstrap'], factType: string, id: string): string {
  check(uuid.test(id));
  const types: Record<string, string> = { IDENTITY_PRINCIPAL: 'identity.principal', ORGANIZATION_UNIT: 'identity.organization_unit', APPOINTMENT: 'identity.appointment', AUTHORITY_GRANT: 'identity.authority_grant' };
  check(Object.hasOwn(types, factType));
  // Same public canonical projection as CommandReceiptReader, with original Actor IDs.
  const scope = { appointment: actor.appointmentId, id, kind: 'HUMAN', onBehalfAppointment: null, onBehalfPrincipal: null, principal: actor.founderId, profile: 'R1_PUBLIC_FACT_REF_V1', tenant: actor.tenantId, type: types[factType] };
  return createHash('sha256').update(JSON.stringify(scope)).digest('base64url');
}
export function matchFact(actor: LocalEnvironment['bootstrap'], fact: { factType: string; factRef: string }, candidates: any[]): string {
  const matches = candidates.filter(row => uuid.test(row.id) && publicFactRef(actor, fact.factType, row.id) === fact.factRef);
  check(matches.length === 1); return matches[0].id;
}

export class IdentitySetup {
  readonly environment: LocalEnvironment;
  readonly runId: string;
  readonly journal: OperationJournal;
  private armed?: Armed;
  private dispatchFailed = false;
  private sessions: Session[] = [];
  private admin?: Session;
  private http: Array<{ path: string; status: number }> = [];
  constructor(private browser: Browser) {
    this.environment = loadLocalEnvironment(); this.environment.verifyBrowser(browser.version());
    check(uuid.test(process.env.TASK9_RUN_ID ?? '')); this.runId = process.env.TASK9_RUN_ID!;
    const path = join(runtime, 'task9-identity-operation.json');
    check(!existsSync(path) || process.env.TASK9_CONTINUE_RUN_ID === this.runId);
    this.journal = new OperationJournal(path, { runId: this.runId, environmentDigest: this.environment.environmentDigest, buildSha: this.environment.buildSha }, protect);
  }
  async close() { for (const session of this.sessions) await session.context.close(); this.sessions = []; }
  private async observe(request: Request, session: Session): Promise<void> {
    const url = new URL(request.url());
    if (url.origin === ORIGIN && url.pathname.startsWith('/api/')) {
      const headers = await request.allHeaders();
      if (headers.authorization) session.auth = { Authorization: headers.authorization, ...(headers['x-appointment-id'] ? { 'X-Appointment-Id': headers['x-appointment-id'] } : {}) };
    }
  }
  async login(alias: Alias | 'founder' | 'unmapped'): Promise<Session> {
    this.environment.assertUnchanged();
    const context = await this.browser.newContext({ serviceWorkers: 'block', ignoreHTTPSErrors: false, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' });
    const page = await context.newPage();
    const session: Session = { context, page, self: null, auth: {} }; this.sessions.push(session);
    await context.route('**/*', async route => {
      try {
        const request = route.request(), url = new URL(request.url());
        check([ORIGIN, new URL(ISSUER).origin].includes(url.origin));
        if (url.origin === new URL(ISSUER).origin) {
          check(url.pathname.startsWith('/realms/local-r1/') || url.pathname.startsWith('/resources/'));
          await route.continue(); return;
        }
        await this.observe(request, session);
        if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method())) {
          check(url.pathname.startsWith('/api/') && this.armed && !this.dispatchFailed && !this.journal.pending());
          const armed = this.armed; this.armed = undefined;
          check(request.method() === 'POST' && url.pathname === armed.path && !url.search);
          const bytes = request.postDataBuffer(); check(bytes);
          const body = JSON.parse(bytes.toString('utf8'));
          check(exact(body, Object.keys(armed.body)) && Object.keys(body).every(key => body[key] === armed.body[key]));
          const headers = await request.allHeaders();
          check(session === this.admin && session.self?.canEnterIdentityAdmin === true && session.self.selectedAppointmentId === this.environment.bootstrap.appointmentId && session.self.selectedOnBehalfAppointmentId === null);
          check(headers['x-appointment-id'] === this.environment.bootstrap.appointmentId && !headers['x-on-behalf-appointment-id']);
          this.environment.assertUnchanged();
          await dispatchObserved(this.journal, { step: armed.step, commandId: headers['idempotency-key'], method: request.method(), path: url.pathname, bodySha256: sha(bytes), actorScopeKey: session.self.actorScopeKey }, () => route.continue());
          return;
        }
        await route.continue();
      } catch { this.dispatchFailed = true; await route.abort().catch(() => {}); }
    });
    const account = this.environment.accounts[alias];
    await page.goto(ORIGIN + '/login');
    await page.getByRole('button', { name: '登录工作台', exact: true }).click();
    await page.waitForURL(url => url.origin === new URL(ISSUER).origin && url.pathname.startsWith('/realms/local-r1/'));
    await page.locator('input[name="username"]').fill(account.username);
    await page.locator('input[name="password"]').fill(account.password);
    const tokenResponse = page.waitForResponse(response => response.url() === ISSUER + '/protocol/openid-connect/token' && response.request().method() === 'POST');
    const selfResponse = page.waitForResponse(response => new URL(response.url()).origin === ORIGIN && new URL(response.url()).pathname === SELF);
    await page.locator('input[type="submit"],button[type="submit"]').click();
    const token = await tokenResponse; check(token.status() === 200);
    const tokens = await token.json();
    const claims = JSON.parse(Buffer.from(tokens.access_token.split('.')[1], 'base64url').toString('utf8'));
    check(claims.iss === ISSUER && claims.preferred_username === account.username);
    if (account.providerUserId) check(claims.sub === account.providerUserId);
    const response = await selfResponse; this.http.push({ path: SELF, status: response.status() });
    session.self = response.status() === 200 ? await response.json() : null;
    if (!session.self) {
      check(response.status() === 403);
      await expect(page.getByText('账号或任职暂不可用，请联系管理员。', { exact: true })).toBeVisible();
    }
    return session;
  }
  private async read(session: Session, path: string): Promise<any> {
    check(path === SELF || COLLECTIONS.some(p => path === p + '?limit=50') || /^\/api\/v1\/commands\/[0-9a-f-]{36}\/receipt$/.test(path));
    this.environment.assertUnchanged();
    const response = await session.context.request.get(ORIGIN + path, { headers: session.auth, maxRedirects: 0 });
    this.http.push({ path: path.split('?')[0], status: response.status() });
    check(response.status() === 200 && response.headers()['cache-control']?.includes('no-store'));
    return response.json();
  }
  private async rows(path: string): Promise<any[]> {
    check(this.admin); const data = await this.read(this.admin, path + '?limit=50');
    check(exact(data, ['items', 'nextCursor']) && data.nextCursor === null && Array.isArray(data.items) && data.items.length <= 50); return data.items;
  }
  async administrator(): Promise<Session> {
    if (this.admin) return this.admin;
    const session = await this.login('founder');
    check(session.self?.state === 'READY' && session.self.selectedAppointmentId === this.environment.bootstrap.appointmentId && session.self.canEnterIdentityAdmin && !session.self.canEnterWorkbench);
    await session.page.goto(ORIGIN + '/admin/identity/principals');
    await session.page.getByRole('button', { name: '确认本次身份', exact: true }).click();
    await expect(session.page.getByRole('heading', { name: '用户与身份主体', exact: true })).toBeVisible();
    session.self = await this.read(session, SELF); check(session.self.selectedAppointmentId === this.environment.bootstrap.appointmentId && session.self.canEnterIdentityAdmin);
    this.admin = session; return session;
  }
  private async navigate(path: string) {
    const admin = await this.administrator();
    await admin.page.locator(`nav a[href="${path.replace('/api/v1', '')}"]`).click(); return admin.page;
  }
  private async checkRecorded(step: string): Promise<string | undefined> {
    const entry = this.journal.confirmed(step); if (!entry) return undefined;
    check(this.admin && entry.actorScopeKey === this.admin.self.actorScopeKey);
    const receipt = await this.read(this.admin, `/api/v1/commands/${entry.commandId}/receipt`);
    check(receipt.commandId === entry.commandId && receipt.receiptId === entry.receiptId && receipt.outcome === 'SUCCEEDED' && ['factType', 'factRef', 'revision'].every(key => receipt.resultFact[key] === (entry.resultFact as any)?.[key]));
    const id = matchFact(this.environment.bootstrap, entry.resultFact!, await this.rows(entry.path));
    check(entry.resourcePath === entry.path + '/' + id); return id;
  }
  async recoverPending() {
    const pending = this.journal.pending();
    check(pending && process.env.TASK9_CONTINUE_RUN_ID === this.runId && process.env.TASK9_RECOVER_COMMAND_ID === pending.commandId);
    const admin = await this.administrator(); check(admin.self.actorScopeKey === pending.actorScopeKey);
    // Any 404, denial, malformed response or missing exact fact leaves PENDING.
    // No request body was retained, so this path cannot resend the write.
    const receipt = await this.read(admin, `/api/v1/commands/${pending.commandId}/receipt`);
    const id = matchFact(this.environment.bootstrap, receipt.resultFact, await this.rows(pending.path));
    this.journal.complete(pending.commandId, 200, receipt, pending.path + '/' + id);
  }
  private async submit(step: string, path: string, body: Record<string, unknown>): Promise<string> {
    // Named 9.6f prerequisite: the reviewed defective binary cannot dispatch a
    // CREATE even if someone supplies the local approval flag before rebuilding.
    requireReceiptLocationBuild(this.environment.buildSha);
    check(this.admin && !this.armed && !this.dispatchFailed && !this.journal.pending());
    const page = this.admin.page;
    this.armed = { step, path, body };
    const waiting = page.waitForResponse(response => response.url() === ORIGIN + path && response.request().method() === 'POST');
    await page.getByRole('button', { name: '确认创建', exact: true }).click();
    const response = await waiting; this.http.push({ path, status: response.status() });
    const pending = this.journal.pending(); check(pending?.step === step && !this.dispatchFailed);
    check(response.status() === 201 && response.headers()['cache-control']?.includes('no-store') && /^"identity\.[A-Za-z0-9_-]{43}"$/.test(response.headers().etag ?? ''));
    // Frozen OpenAPI ReceiptLocation. A resource URL is a production defect, not an alternate success contract.
    check(response.headers().location === `/api/v1/commands/${pending.commandId}/receipt`);
    const receipt = await response.json();
    const id = matchFact(this.environment.bootstrap, receipt.resultFact, await this.rows(path));
    const recovered = await this.read(this.admin, `/api/v1/commands/${pending.commandId}/receipt`);
    check(JSON.stringify(recovered) === JSON.stringify(receipt));
    this.journal.complete(pending.commandId, response.status(), receipt, path + '/' + id);
    await expect(page.getByRole('button', { name: '确认创建', exact: true })).toHaveCount(0);
    this.environment.assertUnchanged(); return id;
  }
  async entry() {
    const session = await this.administrator();
    await expect(session.page.getByRole('main', { name: '身份管理', exact: true })).toBeVisible();
    await expect(session.page.getByRole('main', { name: '责任工作台', exact: true })).toHaveCount(0);
    const root = (await this.rows('/api/v1/admin/identity/organizations')).find(x => x.id === this.environment.bootstrap.rootId);
    check(root?.code === 'ROOT' && root.parentOrganizationId === null && root.state === 'ACTIVE');
    const founder = (await this.rows('/api/v1/admin/identity/principals')).find(x => x.id === this.environment.bootstrap.founderId); check(founder?.state === 'ACTIVE');
  }
  async unmapped() { for (const alias of ['unmapped', ...ALIASES] as const) { const session = await this.login(alias); check(session.self === null); } }
  async bind() {
    await this.administrator();
    for (const alias of ALIASES) {
      if (await this.checkRecorded('principal-' + alias)) continue;
      const path = '/api/v1/admin/identity/principals', page = await this.navigate(path);
      await page.getByRole('button', { name: '新增身份主体', exact: true }).click();
      await page.getByLabel('完整用户名', { exact: true }).fill(this.environment.accounts[alias].username);
      const waiting = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/admin/identity/provider-users');
      await page.getByRole('button', { name: '精确查询', exact: true }).click();
      const response = await waiting; check(response.status() === 200);
      const url = new URL(response.url()); check(url.origin === ORIGIN && url.searchParams.get('search') === this.environment.accounts[alias].username);
      const candidates = await response.json(); check(candidates.nextCursor === null && candidates.items.length === 1 && candidates.items[0].label === this.environment.accounts[alias].username);
      const selector = candidates.items[0].selector; check(/^[A-Za-z0-9_-]{1,2048}$/.test(selector));
      await page.getByLabel('身份提供方用户', { exact: true }).selectOption(selector);
      await page.getByLabel('显示名称', { exact: true }).fill(NAMES[alias]);
      await this.submit('principal-' + alias, path, { providerUserSelector: selector, displayName: NAMES[alias] });
    }
  }
  async noAppointment() {
    await this.administrator();
    for (const alias of ALIASES) {
      check(await this.checkRecorded('principal-' + alias));
      const session = await this.login(alias);
      check(session.self?.state === 'NO_APPOINTMENT' && session.self.displayName === NAMES[alias] && session.self.appointmentChoices.length === 0 && !session.self.canEnterWorkbench && !session.self.canEnterIdentityAdmin);
      await expect(session.page.getByText('当前没有可用本人任职，请联系律所管理员。', { exact: true })).toBeVisible();
      await expect(session.page.getByRole('button', { name: '确认本次身份', exact: true })).toBeDisabled();
    }
  }
  private recordedId(step: string): string { const entry = this.journal.confirmed(step); check(entry?.resourcePath); const id = entry.resourcePath.split('/').at(-1)!; check(uuid.test(id)); return id; }
  async appointments() {
    await this.administrator();
    let organization = await this.checkRecorded('organization');
    if (!organization) {
      const path = '/api/v1/admin/identity/organizations', page = await this.navigate(path);
      await page.getByRole('button', { name: '新增组织', exact: true }).click();
      await page.getByLabel('显示名称', { exact: true }).fill('本地合成验收组织'); await page.getByLabel('组织代码', { exact: true }).fill('LOCAL_ACCEPTANCE');
      await page.getByLabel('上级组织', { exact: true }).selectOption(this.environment.bootstrap.rootId);
      organization = await this.submit('organization', path, { parentOrganizationId: this.environment.bootstrap.rootId, code: 'LOCAL_ACCEPTANCE', displayName: '本地合成验收组织' });
    }
    for (const alias of ALIASES) {
      const existing = await this.checkRecorded('appointment-' + alias);
      if (!existing) {
        const path = '/api/v1/admin/identity/appointments', page = await this.navigate(path);
        const principalId = this.recordedId('principal-' + alias), roleCode = alias === 'intake' ? 'INTAKE_OPERATOR' : alias === 'supervisor' ? 'ROUTING_SUPERVISOR' : 'CONTACT_OPERATOR';
        await page.getByRole('button', { name: '新建任职', exact: true }).click();
        await page.getByLabel('身份主体', { exact: true }).selectOption(principalId); await page.getByLabel('所属组织', { exact: true }).selectOption(organization);
        await page.getByLabel('岗位', { exact: true }).selectOption(roleCode);
        const start = new Date(Date.now() - 60_000).toISOString(); const local = new Date(Date.parse(start) + 8 * 3600_000).toISOString().slice(0, 16);
        await page.getByLabel('生效时间', { exact: true }).fill(local);
        await this.submit('appointment-' + alias, path, { principalId, organizationId: organization, roleCode, effectiveFrom: new Date(local + ':00+08:00').toISOString(), effectiveUntil: null });
      }
      const row = (await this.rows('/api/v1/admin/identity/appointments')).find(row => row.id === this.recordedId('appointment-' + alias));
      check(row?.principal.id === this.recordedId('principal-' + alias) && row.organization.id === organization && row.state === 'ACTIVE' && row.roleCode === (alias === 'intake' ? 'INTAKE_OPERATOR' : alias === 'supervisor' ? 'ROUTING_SUPERVISOR' : 'CONTACT_OPERATOR'));
    }
    for (const alias of ALIASES) await this.qualification(alias, false);
    const ids = ALIASES.map(alias => this.recordedId('appointment-' + alias));
    check((await this.rows('/api/v1/admin/identity/authority-grants')).every(row => !ids.includes(row.appointment.id)));
  }
  async grants() {
    await this.administrator();
    for (const alias of ['intake', 'supervisor'] as const) {
      for (const [index, authorityCode] of GRANTS[alias].entries()) {
        const step = `grant-${alias}-${index}`; if (await this.checkRecorded(step)) continue;
        const path = '/api/v1/admin/identity/authority-grants', page = await this.navigate(path), appointmentId = this.recordedId('appointment-' + alias);
        await page.getByRole('button', { name: '新增直接授权', exact: true }).click();
        await page.getByLabel('授权任职', { exact: true }).selectOption(appointmentId); await page.getByLabel('组织范围', { exact: true }).selectOption(this.environment.bootstrap.rootId);
        await page.getByLabel('权限', { exact: true }).selectOption(authorityCode);
        const local = new Date(Date.now() + 8 * 3600_000).toISOString().slice(0, 16);
        await page.getByLabel('生效时间', { exact: true }).fill(local);
        await this.submit(step, path, { appointmentId, authorityCode, scopeOrganizationId: this.environment.bootstrap.rootId, validFrom: new Date(local + ':00+08:00').toISOString(), validUntil: null });
      }
    }
    const rows = await this.rows('/api/v1/admin/identity/authority-grants');
    for (const alias of ALIASES) {
      const own = rows.filter(row => row.appointment.id === this.recordedId('appointment-' + alias));
      const expected = alias === 'intake' || alias === 'supervisor' ? GRANTS[alias] : [];
      check(own.length === expected.length && own.every(row => expected.includes(row.authorityCode) && row.scopeOrganization.id === this.environment.bootstrap.rootId && row.state === 'ACTIVE'));
    }
  }
  private async qualification(alias: Alias, business: boolean) {
    const session = await this.login(alias), id = this.recordedId('appointment-' + alias);
    check(session.self?.state === 'READY' && session.self.displayName === NAMES[alias] && session.self.selectedAppointmentId === id && session.self.selectedOnBehalfAppointmentId === null && session.self.appointmentChoices.length === 1 && session.self.appointmentChoices[0].id === id && session.self.canEnterWorkbench === business && !session.self.canEnterIdentityAdmin);
    const page = session.page;
    const waiting = business ? page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/workcards/current') : undefined;
    await page.getByRole('button', { name: '确认本次身份', exact: true }).click();
    if (waiting) {
      const response = await waiting; check(response.status() === 200); const envelope = await response.json();
      this.http.push({ path: '/api/v1/workcards/current', status: response.status() });
      check(envelope.currentCard === null && envelope.nextSummaries.length === 0 && envelope.waitingCount === 0);
      await expect(page.getByRole('heading', { name: '当前暂无可处理责任', exact: true })).toBeVisible();
    } else await expect(page.getByText('当前任职不能进入业务工作台；请联系律所管理员。', { exact: true })).toBeVisible();
  }
  async dynamic() { for (const alias of ALIASES) await this.qualification(alias, alias === 'intake' || alias === 'supervisor'); this.environment.assertUnchanged(); }
  async stage(id: CaseId) {
    let completing = false; const at = new Date().toISOString();
    const reportPath = join(runtime, `task9-${this.runId}-${id}-${randomUUID()}.json`);
    try {
      this.journal.requirePrevious(id); this.environment.assertUnchanged();
      const actions = [() => this.entry(), () => this.unmapped(), () => this.bind(), () => this.noAppointment(), () => this.appointments(), () => this.grants(), () => this.dynamic()];
      await actions[CASES.indexOf(id)](); this.environment.assertUnchanged();
      const http = this.http; this.http = []; completing = true;
      this.journal.finishStage(id, { runId: this.runId, buildSha: this.environment.buildSha, environmentDigest: this.environment.environmentDigest, apiIdentity: this.environment.apiIdentity, executedAt: at, caseIdentity: id, status: 'ACTIONS_VERIFIED', exitCode: null, reportPath, http, U01: 'NOT_EXECUTED', U02: 'NOT_EXECUTED', U03: 'NOT_EXECUTED' });
    } catch {
      if (!completing) {
        protect();
        writeFileSync(reportPath, JSON.stringify({ runId: this.runId, buildSha: this.environment.buildSha, environmentDigest: this.environment.environmentDigest, apiIdentity: this.environment.apiIdentity, executedAt: at, caseIdentity: id, status: 'FAILED', exitCode: 1, reportPath, http: this.http, U01: 'NOT_EXECUTED', U02: 'NOT_EXECUTED', U03: 'NOT_EXECUTED' }), { flag: 'wx', mode: 0o600 });
        this.http = []; protect();
      }
      throw new Error(safeFailureCode(id));
    }
  }
}
