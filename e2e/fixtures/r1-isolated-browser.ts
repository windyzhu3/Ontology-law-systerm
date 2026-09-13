import { createHash, randomUUID } from 'node:crypto';
import { expect, type Browser, type BrowserContext, type Page, type Request } from '@playwright/test';

import { candidate, parseEnvelope, sameValues } from '../../apps/workbench/src/features/workcard/contract';
import { closeR1GoldenCompletion, type LoadedR1Environment, UUID, boundary } from './r1-isolated-environment';
import { AcceptanceDispatchGate, WRITE_SEQUENCE, canonicalSha256, type GoldenAdapter, type WriteIntent } from './r1-isolated-setup';


const SELF = '/api/v1/session/context';
const CURRENT = '/api/v1/workcards/current';
const SCREEN_TIMEOUT = 30_000;
type Alias = 'founder'|'sales'|'supervisor'|'sourceOwner';
type Session = { alias: Alias; context: BrowserContext; page: Page; self: any; auth: Record<string,string>; appointmentId: string };
type Policy = typeof WRITE_SEQUENCE[number];
type Wire = { status: number; headers: Record<string,string>; body: any };

function exact(value: unknown, keys: string[]): value is Record<string,any> {
  return !!value && typeof value === 'object' && Object.keys(value).sort().join() === [...keys].sort().join();
}

function publicFactRef(environment: LoadedR1Environment, factType: string, id: string): string {
  boundary(UUID.test(id));
  const types: Record<string,string> = { IDENTITY_PRINCIPAL: 'identity.principal', ORGANIZATION_UNIT: 'identity.organization_unit', APPOINTMENT: 'identity.appointment', AUTHORITY_GRANT: 'identity.authority_grant' };
  boundary(Object.hasOwn(types, factType));
  const scope = { appointment: environment.bootstrap.founderAppointmentId, id, kind: 'HUMAN', onBehalfAppointment: null, onBehalfPrincipal: null,
    principal: environment.bootstrap.founderId, profile: 'R1_PUBLIC_FACT_REF_V1', tenant: environment.bootstrap.tenantId, type: types[factType] };
  return createHash('sha256').update(JSON.stringify(scope)).digest('base64url');
}

function localDateTime(instant: string): string {
  return new Date(Date.parse(instant) + 8 * 60 * 60 * 1000).toISOString().slice(0, 16);
}

export class R1IsolatedBrowserAdapter implements GoldenAdapter {
  private sessions = new Map<Alias,Session>();
  private active?: { session: Session; gate: AcceptanceDispatchGate; routed: boolean };
  private trigger?: () => Promise<void>;
  private beforeIds = new Set<string>();
  private preparedSession?: Session;
  private contactCard: any;
  private savedDraft: any;
  private constructor(private browser: Browser, readonly environment: LoadedR1Environment) {}

  static async create(browser: Browser, environment: LoadedR1Environment): Promise<R1IsolatedBrowserAdapter> {
    environment.verifyBrowser(browser.version());
    return new R1IsolatedBrowserAdapter(browser, environment);
  }

  async close(): Promise<void> {
    for (const session of this.sessions.values()) await session.context.close();
    this.sessions.clear();
  }

  private expectedAppointment(alias: Alias, resources: Record<string,string>): string {
    if (alias === 'founder') return this.environment.bootstrap.founderAppointmentId;
    const value = resources[`appointment-${alias}`]; boundary(UUID.test(value)); return value;
  }

  private async observe(request: Request, session: Session): Promise<void> {
    const url = new URL(request.url());
    if (url.origin !== this.environment.origin || !url.pathname.startsWith('/api/')) return;
    const headers = await request.allHeaders();
    if (headers.authorization) session.auth = { Authorization: headers.authorization, ...(headers['x-appointment-id'] ? { 'X-Appointment-Id': headers['x-appointment-id'] } : {}) };
  }

  private async login(alias: Alias, resources: Record<string,string>): Promise<Session> {
    const cached = this.sessions.get(alias); if (cached) return cached;
    const appointmentId = this.expectedAppointment(alias, resources);
    const context = await this.browser.newContext({ serviceWorkers: 'block', ignoreHTTPSErrors: false, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' });
    const page = await context.newPage();
    const session: Session = { alias, context, page, self: null, auth: {}, appointmentId }; this.sessions.set(alias, session);
    await context.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url()), method = request.method();
      try {
        if (url.origin === new URL(this.environment.issuer).origin) {
          boundary(url.pathname.startsWith('/realms/r1-e2e/') || url.pathname.startsWith('/resources/'));
          await route.continue(); return;
        }
        boundary(url.origin === this.environment.origin);
        await this.observe(request, session);
        if (['GET','HEAD','OPTIONS'].includes(method)) { await route.continue(); return; }
        const active = this.active; boundary(active && active.session === session && !active.routed);
        active.routed = true;
        const headers = await request.allHeaders(), bytes = request.postDataBuffer(); boundary(bytes);
        await active.gate.dispatch({ commandId: headers['idempotency-key'], method, path: url.pathname, bodyBytes: bytes,
          actorScopeKey: session.self?.actorScopeKey, actorAppointmentId: headers['x-appointment-id'], onBehalfAppointmentId: null }, async () => {
            boundary(headers['x-appointment-id'] === session.appointmentId && !headers['x-on-behalf-appointment-id']); await route.continue();
          });
      } catch { await route.abort().catch(() => {}); }
    });
    const account = this.environment.accounts[alias]; boundary(account);
    await page.goto(this.environment.origin + '/login', { waitUntil: 'domcontentloaded', timeout: SCREEN_TIMEOUT });
    await page.getByRole('button', { name: '登录工作台', exact: true }).click({ timeout: SCREEN_TIMEOUT });
    await page.waitForURL(url => url.origin === new URL(this.environment.issuer).origin && url.pathname.startsWith('/realms/r1-e2e/'), { timeout: SCREEN_TIMEOUT });
    await page.locator('input[name="username"]').fill(account.username); await page.locator('input[name="password"]').fill(account.password);
    const tokenWaiting = page.waitForResponse(response => response.url() === this.environment.issuer + '/protocol/openid-connect/token' && response.request().method() === 'POST');
    const selfWaiting = page.waitForResponse(response => new URL(response.url()).origin === this.environment.origin && new URL(response.url()).pathname === SELF);
    await page.locator('input[type="submit"],button[type="submit"]').click({ timeout: SCREEN_TIMEOUT });
    const token = await tokenWaiting; boundary(token.status() === 200);
    const tokenBody = await token.json(), claims = JSON.parse(Buffer.from(tokenBody.access_token.split('.')[1], 'base64url').toString('utf8'));
    boundary(claims.iss === this.environment.issuer && claims.preferred_username === account.username);
    const response = await selfWaiting; boundary(response.status() === 200); session.self = await response.json();
    boundary(session.self?.state === 'READY' && session.self.selectedAppointmentId === appointmentId && session.self.selectedOnBehalfAppointmentId === null);
    boundary(Array.isArray(session.self.appointmentChoices) && session.self.appointmentChoices.length === 1 && session.self.appointmentChoices[0].id === appointmentId);
    boundary(typeof session.self.actorScopeKey === 'string' && session.self.actorScopeKey.startsWith('ask1.'));
    return session;
  }

  private async administrator(resources: Record<string,string>): Promise<Session> {
    const session = await this.login('founder', resources);
    boundary(session.self.canEnterIdentityAdmin === true && session.self.canEnterWorkbench === false);
    if (!new URL(session.page.url()).pathname.startsWith('/admin/identity/')) {
      await session.page.getByRole('button', { name: '进入身份管理', exact: true }).click({ timeout: SCREEN_TIMEOUT });
      await expect(session.page.getByText('即将进入身份管理，请确认本次本人任职。', { exact: true })).toBeVisible({ timeout: SCREEN_TIMEOUT });
      await session.page.getByRole('button', { name: '确认本次身份', exact: true }).click({ timeout: SCREEN_TIMEOUT });
    }
    await expect(session.page.getByRole('main', { name: '身份管理', exact: true })).toBeVisible({ timeout: SCREEN_TIMEOUT });
    return session;
  }

  private async workbench(alias: Exclude<Alias,'founder'>, resources: Record<string,string>): Promise<Session> {
    const session = await this.login(alias, resources); boundary(session.self.canEnterWorkbench === true && session.self.canEnterIdentityAdmin === false);
    if (await session.page.getByRole('main', { name: '责任工作台', exact: true }).count() === 0) {
      await session.page.getByRole('button', { name: '确认本次身份', exact: true }).click({ timeout: SCREEN_TIMEOUT });
    }
    await expect(session.page.getByRole('main', { name: '责任工作台', exact: true })).toBeVisible({ timeout: SCREEN_TIMEOUT });
    return session;
  }

  private async read(session: Session, path: string): Promise<any> {
    boundary(/^\/api\/v1\/(session\/context|admin\/identity\/(principals|organizations|appointments|authority-grants)(\?limit=50)?|workcards\/current|commands\/[0-9a-f-]{36}\/receipt)$/.test(path));
    const response = await session.context.request.get(this.environment.origin + path, { headers: session.auth, failOnStatusCode: false, maxRedirects: 0 });
    const cache = response.headers()['cache-control']?.toLowerCase().split(',').map(value => value.trim()) ?? [];
    boundary(response.status() === 200 && (path === CURRENT ? cache.includes('private') && cache.includes('no-cache') : cache.includes('no-store')));
    return response.json();
  }

  private async rows(session: Session, path: string): Promise<any[]> {
    const value = await this.read(session, path + '?limit=50'); boundary(exact(value, ['items','nextCursor']) && value.nextCursor === null && Array.isArray(value.items)); return value.items;
  }

  private async adminPage(path: string, resources: Record<string,string>): Promise<Session> {
    const session = await this.administrator(resources), route = path.replace('/api/v1', '');
    if (new URL(session.page.url()).pathname !== route) await session.page.locator(`nav a[href="${route}"]`).click();
    await expect(session.page.getByRole('button', { name: /新增|新建/, exact: false }).first()).toBeVisible({ timeout: SCREEN_TIMEOUT });
    return session;
  }

  private intent(policy: Policy, session: Session, path: string, body: Record<string,unknown>, commandId?: string): WriteIntent {
    return { step: policy.step, ...(commandId ? { commandId } : {}), method: policy.method, path, body, bodySha256: canonicalSha256(body),
      actorScopeKey: session.self.actorScopeKey, actorAppointmentId: session.appointmentId, onBehalfAppointmentId: null };
  }

  async prepareWrite(policy: Policy, resources: Record<string,string>): Promise<WriteIntent> {
    this.trigger = undefined; this.preparedSession = undefined; this.beforeIds = new Set();
    if (policy.step.startsWith('principal-')) return this.preparePrincipal(policy, resources);
    if (policy.step.startsWith('organization-')) return this.prepareOrganization(policy, resources);
    if (policy.step.startsWith('appointment-')) return this.prepareAppointment(policy, resources);
    if (policy.step.startsWith('grant-')) return this.prepareGrant(policy, resources);
    if (policy.step === 'capture-R1_AUTO') return this.prepareCapture(policy, resources);
    if (policy.step === 'contact-draft') return this.prepareDraft(policy, resources);
    boundary(policy.step === 'contact-submit'); return this.prepareSubmit(policy, resources);
  }

  private async preparePrincipal(policy: Policy, resources: Record<string,string>): Promise<WriteIntent> {
    const session = await this.adminPage('/api/v1/admin/identity/principals', resources), account = policy.step.slice('principal-'.length);
    boundary(account === 'sales' || account === 'supervisor' || account === 'sourceOwner');
    this.beforeIds = new Set((await this.rows(session, '/api/v1/admin/identity/principals')).map(row => row.id));
    await session.page.getByRole('button', { name: '新增身份主体', exact: true }).click();
    await session.page.getByLabel('完整用户名', { exact: true }).fill(this.environment.usernames[account]);
    await session.page.getByRole('button', { name: '精确查询', exact: true }).click();
    const select = session.page.getByLabel('身份提供方用户', { exact: true });
    await expect(select.locator('option')).toHaveCount(2, { timeout: SCREEN_TIMEOUT });
    const selector = await select.locator('option').nth(1).getAttribute('value'); boundary(typeof selector === 'string' && /^[A-Za-z0-9_-]{1,2048}$/.test(selector));
    await select.selectOption(selector); const displayName = { sales: 'R1 Synthetic Sales', supervisor: 'R1 Synthetic Supervisor', sourceOwner: 'R1 Synthetic Source Owner' }[account]; boundary(displayName);
    await session.page.getByLabel('显示名称', { exact: true }).fill(displayName);
    this.preparedSession = session; this.trigger = async () => { await session.page.getByRole('button', { name: '确认创建', exact: true }).click(); };
    return this.intent(policy, session, String(policy.path), { providerUserSelector: selector, displayName });
  }

  private async prepareOrganization(policy: Policy, resources: Record<string,string>): Promise<WriteIntent> {
    const session = await this.adminPage('/api/v1/admin/identity/organizations', resources), code = policy.step.slice('organization-'.length);
    this.beforeIds = new Set((await this.rows(session, '/api/v1/admin/identity/organizations')).map(row => row.id));
    await session.page.getByRole('button', { name: '新增组织', exact: true }).click();
    await session.page.getByLabel('上级组织', { exact: true }).selectOption(this.environment.bootstrap.rootId);
    await session.page.getByLabel('组织代码', { exact: true }).fill(code);
    const displayName = code === 'OWNED_ROOT' ? 'R1 Owned Root' : 'R1 Empty Root';
    await session.page.getByLabel('显示名称', { exact: true }).fill(displayName);
    this.preparedSession = session; this.trigger = async () => { await session.page.getByRole('button', { name: '确认创建', exact: true }).click(); };
    return this.intent(policy, session, String(policy.path), { parentOrganizationId: this.environment.bootstrap.rootId, code, displayName });
  }

  private async prepareAppointment(policy: Policy, resources: Record<string,string>): Promise<WriteIntent> {
    const session = await this.adminPage('/api/v1/admin/identity/appointments', resources), account = policy.step.slice('appointment-'.length);
    this.beforeIds = new Set((await this.rows(session, '/api/v1/admin/identity/appointments')).map(row => row.id));
    await session.page.getByRole('button', { name: '新建任职', exact: true }).click();
    const organizationId = account === 'sales' ? resources['organization-OWNED_ROOT'] : this.environment.bootstrap.rootId;
    const roleCode = account === 'sales' ? 'CONTACT_OPERATOR' : account === 'supervisor' ? 'ROUTING_SUPERVISOR' : 'INTAKE_OPERATOR';
    const principalId = resources[`principal-${account}`]; boundary(UUID.test(principalId) && UUID.test(organizationId));
    await session.page.getByLabel('身份主体', { exact: true }).selectOption(principalId); await session.page.getByLabel('所属组织', { exact: true }).selectOption(organizationId); await session.page.getByLabel('岗位', { exact: true }).selectOption(roleCode);
    const effectiveFrom = new Date(Math.floor(Date.now() / 60_000) * 60_000 - 60_000).toISOString();
    await session.page.getByLabel('生效时间', { exact: true }).fill(localDateTime(effectiveFrom));
    this.preparedSession = session; this.trigger = async () => { await session.page.getByRole('button', { name: '确认创建', exact: true }).click(); };
    return this.intent(policy, session, String(policy.path), { principalId, organizationId, roleCode, effectiveFrom, effectiveUntil: null });
  }

  private async prepareGrant(policy: Policy, resources: Record<string,string>): Promise<WriteIntent> {
    const session = await this.adminPage('/api/v1/admin/identity/authority-grants', resources), parts = policy.step.split('-'), account = parts[1], authorityCode = parts.slice(2).join('-');
    this.beforeIds = new Set((await this.rows(session, '/api/v1/admin/identity/authority-grants')).map(row => row.id));
    await session.page.getByRole('button', { name: '新增直接授权', exact: true }).click();
    const appointmentId = resources[`appointment-${account}`];
    const scopeOrganizationId = account === 'sales' ? resources['organization-OWNED_ROOT'] : this.environment.bootstrap.rootId;
    boundary(UUID.test(appointmentId) && UUID.test(scopeOrganizationId));
    await session.page.getByLabel('授权任职', { exact: true }).selectOption(appointmentId); await session.page.getByLabel('组织范围', { exact: true }).selectOption(scopeOrganizationId); await session.page.getByLabel('权限', { exact: true }).selectOption(authorityCode);
    const validFrom = new Date(Math.floor(Date.now() / 60_000) * 60_000 - 60_000).toISOString();
    await session.page.getByLabel('生效时间', { exact: true }).fill(localDateTime(validFrom));
    this.preparedSession = session; this.trigger = async () => { await session.page.getByRole('button', { name: '确认创建', exact: true }).click(); };
    return this.intent(policy, session, String(policy.path), { appointmentId, authorityCode, scopeOrganizationId, validFrom, validUntil: null });
  }

  private async prepareCapture(policy: Policy, resources: Record<string,string>): Promise<WriteIntent> {
    const session = await this.workbench('sourceOwner', resources), commandId = randomUUID();
    const body = { sourceChannelCode: 'LOCAL_SYNTHETIC', sourceAccountCode: 'R1_AUTO', sourceRecordKey: `r1-golden-${this.environment.run}-${this.environment.operationId}`,
      capturedAt: new Date().toISOString(), serviceCategoryCode: 'LOCAL_ACCEPTANCE', jurisdictionCode: 'CN', urgencyCode: 'NORMAL', legalNeedSummary: 'R1 isolated synthetic acceptance lead.' };
    this.preparedSession = session; this.trigger = async () => {
      await session.page.evaluate(async ({ origin, auth, commandId, body }) => {
        const response = await fetch(origin + '/api/v1/leads', { method: 'POST', headers: { ...auth, 'Content-Type': 'application/json', 'Idempotency-Key': commandId }, body: JSON.stringify(body), cache: 'no-store', redirect: 'manual' });
        await response.text();
      }, { origin: this.environment.origin, auth: session.auth, commandId, body });
    };
    return this.intent(policy, session, String(policy.path), body, commandId);
  }

  private async prepareDraft(policy: Policy, resources: Record<string,string>): Promise<WriteIntent> {
    const session = await this.workbench('sales', resources); boundary(this.contactCard?.taskId === resources.contactTask && this.contactCard.taskType === 'CONTACT_LEAD');
    const values = { ...this.contactCard.commandForm.values, contactChannelCode: 'PHONE', resultCode: 'CONNECTED_VALID', resultSummary: 'R1 isolated synthetic valid contact.', legalNeed: 'R1 isolated synthetic legal need.' };
    for (const [name, value] of Object.entries(values)) {
      const locator = session.page.locator(name === 'resultSummary' ? '#chat-candidate' : `#candidate-${name}`);
      if (!await locator.count()) continue;
      if (name.endsWith('Code')) await locator.selectOption(String(value)); else await locator.fill(String(value));
    }
    const body = candidate(this.contactCard, values), path = `/api/v1/tasks/${resources.contactTask}/draft`;
    this.preparedSession = session; this.trigger = async () => { await session.page.getByRole('button', { name: '保存候选', exact: true }).click(); };
    return this.intent(policy, session, path, body);
  }

  private async prepareSubmit(policy: Policy, resources: Record<string,string>): Promise<WriteIntent> {
    const session = await this.workbench('sales', resources), card = this.contactCard; boundary(card?.taskId === resources.contactTask && card.actionDraft && sameValues(card.actionDraft.values, this.savedDraft.values));
    const body = { ...card.actionDraft.values, draftId: card.actionDraft.draftId, expectedDraftRevision: card.actionDraft.draftRevision, draftDigest: card.actionDraft.digest };
    const path = `/api/v1/tasks/${resources.contactTask}/commands/record-contact-result`;
    await expect(session.page.locator('#primary-confirm')).toBeEnabled({ timeout: SCREEN_TIMEOUT });
    this.preparedSession = session; this.trigger = async () => { await session.page.locator('#primary-confirm').click(); };
    return this.intent(policy, session, path, body);
  }

  async executeWrite(command: WriteIntent, gate: AcceptanceDispatchGate): Promise<{ response: Wire; resourceId: string }> {
    const session = this.preparedSession, trigger = this.trigger; boundary(session && trigger && !this.active);
    this.active = { session, gate, routed: false };
    try {
      const waiting = session.page.waitForResponse(response => new URL(response.url()).origin === this.environment.origin && new URL(response.url()).pathname === command.path && response.request().method() === command.method, { timeout: SCREEN_TIMEOUT });
      await trigger(); const response = await waiting; boundary(this.active.routed);
      const headers = await response.allHeaders(), raw = await response.json();
      const wire: Wire = { status: response.status(), headers, body: command.step === 'contact-draft' ? raw.receipt : raw };
      let resourceId: string;
      if (command.step.startsWith('principal-') || command.step.startsWith('organization-') || command.step.startsWith('appointment-') || command.step.startsWith('grant-')) {
        const rows = await this.rows(session, command.path), fact = wire.body?.resultFact;
        const matches = rows.filter(row => UUID.test(row.id) && !this.beforeIds.has(row.id) && publicFactRef(this.environment, fact?.factType, row.id) === fact?.factRef);
        boundary(matches.length === 1); resourceId = matches[0].id;
      } else if (command.step === 'contact-draft') {
        boundary(raw.draft && UUID.test(raw.draft.draftId)); this.savedDraft = raw.draft; resourceId = raw.draft.draftId;
      } else {
        const factRef = wire.body?.resultFact?.factRef; boundary(typeof factRef === 'string' && factRef.length >= 16 && factRef.length <= 512); resourceId = factRef;
      }
      return { response: wire, resourceId };
    } finally { this.active = undefined; this.trigger = undefined; this.preparedSession = undefined; }
  }

  async verifyIdentities(resources: Record<string,string>): Promise<void> {
    const admin = await this.administrator(resources);
    const principals = await this.rows(admin, '/api/v1/admin/identity/principals');
    const organizations = await this.rows(admin, '/api/v1/admin/identity/organizations');
    const appointments = await this.rows(admin, '/api/v1/admin/identity/appointments');
    const grants = await this.rows(admin, '/api/v1/admin/identity/authority-grants');
    const names = { sales: 'R1 Synthetic Sales', supervisor: 'R1 Synthetic Supervisor', sourceOwner: 'R1 Synthetic Source Owner' };
    for (const alias of ['sales','supervisor','sourceOwner'] as const) {
      const principal = principals.find(row => row.id === resources[`principal-${alias}`]);
      boundary(principal?.displayName === names[alias] && principal.state === 'ACTIVE');
      const appointment = appointments.find(row => row.id === resources[`appointment-${alias}`]);
      const organizationId = alias === 'sales' ? resources['organization-OWNED_ROOT'] : this.environment.bootstrap.rootId;
      const roleCode = alias === 'sales' ? 'CONTACT_OPERATOR' : alias === 'supervisor' ? 'ROUTING_SUPERVISOR' : 'INTAKE_OPERATOR';
      boundary(appointment?.principal?.id === principal.id && appointment.organization?.id === organizationId && appointment.roleCode === roleCode && appointment.state === 'ACTIVE');
    }
    for (const code of ['OWNED_ROOT','EMPTY_ROOT'] as const) {
      const organization = organizations.find(row => row.id === resources[`organization-${code}`]);
      boundary(organization?.code === code && organization.parentOrganizationId === this.environment.bootstrap.rootId && organization.state === 'ACTIVE');
    }
    boundary(!appointments.some(row => row.organization?.id === resources['organization-EMPTY_ROOT']));
    boundary(appointments.filter(row => row.roleCode === 'CONTACT_OPERATOR' && row.state === 'ACTIVE').length === 1);
    for (const [alias, authority] of [
      ['sourceOwner','LEAD_CAPTURE'], ['sourceOwner','LEAD_INGRESS_RESOLVE'], ['sourceOwner','LEAD_INGRESS_COMPLETE'], ['sourceOwner','SOURCE_INTAKE_REQUEST_ACK'],
      ['supervisor','LEAD_ASSIGN'], ['supervisor','LEAD_ROUTING_DECIDE'], ['supervisor','LEAD_VALIDITY_REVIEW'],
      ['sales','SALES_CONTACT_OWNER'],
    ] as const) {
      const grant = grants.find(row => row.id === resources[`grant-${alias}-${authority}`]);
      const scope = alias === 'sales' ? resources['organization-OWNED_ROOT'] : this.environment.bootstrap.rootId;
      boundary(grant?.appointment?.id === resources[`appointment-${alias}`] && grant.authorityCode === authority
        && grant.scopeOrganization?.id === scope && grant.state === 'ACTIVE');
    }
    boundary(grants.filter(row => row.appointment?.id === resources['appointment-sales']).length === 1);
    for (const alias of ['sourceOwner','supervisor','sales'] as const) {
      const session = await this.workbench(alias, resources);
      boundary(session.self.selectedAppointmentId === resources[`appointment-${alias}`] && session.self.selectedOnBehalfAppointmentId === null);
    }
  }

  async locateContactTask(resources: Record<string,string>): Promise<string> {
    const session = await this.workbench('sales', resources);
    const waiting = session.page.waitForResponse(response => new URL(response.url()).pathname === CURRENT && response.request().method() === 'GET');
    await session.page.getByRole('button', { name: '刷新当前责任', exact: true }).click();
    const response = await waiting; boundary(response.status() === 200); const envelope = parseEnvelope(await response.json());
    boundary(envelope.currentCard?.taskType === 'CONTACT_LEAD' && UUID.test(envelope.currentCard.taskId) && envelope.currentCard.actionDraft === null && envelope.currentCard.primaryCommand.enabled);
    this.contactCard = envelope.currentCard; return envelope.currentCard.taskId;
  }

  async reloadDraft(resources: Record<string,string>): Promise<void> {
    const session = await this.workbench('sales', resources); boundary(this.savedDraft && UUID.test(this.savedDraft.draftId));
    const waiting = session.page.waitForResponse(response => new URL(response.url()).pathname === CURRENT && response.request().method() === 'GET');
    await session.page.reload({ waitUntil: 'domcontentloaded', timeout: SCREEN_TIMEOUT });
    const response = await waiting; boundary(response.status() === 200); const envelope = parseEnvelope(await response.json()), card = envelope.currentCard;
    boundary(card?.taskId === resources.contactTask && card.taskType === 'CONTACT_LEAD');
    const reloadedDraft = card.actionDraft; boundary(reloadedDraft && reloadedDraft.draftId === this.savedDraft.draftId);
    boundary(sameValues(reloadedDraft.values, this.savedDraft.values));
    this.contactCard = card;
    for (const [name, value] of Object.entries(this.savedDraft.values)) {
      const locator = session.page.locator(name === 'resultSummary' ? '#chat-candidate' : `#candidate-${name}`); if (await locator.count()) await expect(locator).toHaveValue(String(value));
    }
    await expect(session.page.locator('#primary-confirm')).toBeEnabled({ timeout: SCREEN_TIMEOUT });
  }

  async retrieveReceipt(commandId: string): Promise<any> {
    boundary(UUID.test(commandId)); const session = this.sessions.get('sales'); boundary(session);
    return this.read(session, `/api/v1/commands/${commandId}/receipt`);
  }

  async closeCompletion(commandId: string, resultFactDigest: string, resources: Record<string,string>): Promise<{ commandId: string; counts: Record<string,number> }> {
    const owner = resources['appointment-sales'], task = resources.contactTask, draft = resources['contact-draft']; boundary(UUID.test(owner) && UUID.test(task) && UUID.test(draft));
    return closeR1GoldenCompletion(this.environment, commandId, task, draft, owner, resultFactDigest);
  }
}
