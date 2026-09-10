import { expect, type Browser, type BrowserContext, type Page, type Request, type Response } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { existsSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { BUSINESS_PIN, IDENTITY_PREDECESSOR, loadBusinessEnvironment, type BusinessEnvironment } from './business-environment';
import { BusinessJournal, BUSINESS_CASES, type BusinessCaseId, type BusinessEntry, type BusinessSelectors, type BusinessStep, type CardSelectors } from './business-journal';
import { BusinessDispatchGate, allowBusinessRequest } from './business-session';
import { check, exact, ORIGIN, ISSUER, protect, uuid } from './local-environment';
import { GRANTS, matchFact, NAMES } from './identity-setup';
import { businessFailureCode } from '../reporters/business-reporter';
import { candidate } from '../../apps/workbench/src/features/workcard/contract';

const SELF = '/api/v1/session/context';
const CURRENT = '/api/v1/workcards/current';
const SCREEN_TIMEOUT = 30_000;
const TASK_TYPES = ['COMPLETE_LEAD_INGRESS', 'RESOLVE_LEAD_ROUTING_GAP', 'ACK_SOURCE_INTAKE_STOP_REQUEST', 'ASSIGN_LEAD', 'CONTACT_LEAD', 'REVIEW_LEAD_VALIDITY'] as const;
type Alias = 'founder' | 'intake' | 'supervisor' | 'contact' | 'delegate';
type Session = { alias: Alias; context: BrowserContext; page: Page; self: any; auth: Record<string, string>; appointmentId: string };
type Wire = { status: number; headers: Record<string, string>; body: any };
type CardType = typeof TASK_TYPES[number];

function requestSelectors(session: Session, card?: any) {
  return card ? { actorAppointmentId: session.appointmentId, taskId: card.taskId, subjectRef: card.subject.subjectRef, subjectRevision: card.subject.subjectRevision, taskETag: card.preconditions.taskETag }
    : { actorAppointmentId: session.appointmentId, taskId: null, subjectRef: null, subjectRevision: null, taskETag: null };
}
function selectors(session: Session, original: any, successor: any | null, draft = original?.actionDraft ?? null): CardSelectors {
  check(original && uuid.test(original.taskId) && original.subject?.subjectType === 'LEAD');
  return {
    taskId: original.taskId, subjectRef: original.subject.subjectRef, subjectRevision: original.subject.subjectRevision,
    ownerAppointmentId: session.appointmentId, taskETag: original.preconditions.taskETag,
    draftETag: draft ? original.preconditions.draftETag : null, draftId: draft?.draftId ?? null,
    successorTaskId: successor?.taskId ?? null, successorTaskType: successor?.taskType ?? null,
  };
}
function stepAlias(step: BusinessStep): Alias {
  if (step === 'grant-contact-owner') return 'founder';
  if (step.startsWith('routing-') || step.startsWith('assign-') || step.startsWith('review-')) return 'supervisor';
  if (step.startsWith('contact-')) return 'contact';
  return 'intake';
}
function commandType(step: BusinessStep): CardType | undefined {
  return step.startsWith('complete-') ? 'COMPLETE_LEAD_INGRESS' : step.startsWith('routing-') ? 'RESOLVE_LEAD_ROUTING_GAP'
    : step.startsWith('ack-') ? 'ACK_SOURCE_INTAKE_STOP_REQUEST' : step.startsWith('assign-') ? 'ASSIGN_LEAD'
      : step.startsWith('contact-') ? 'CONTACT_LEAD' : step.startsWith('review-') ? 'REVIEW_LEAD_VALIDITY' : undefined;
}
function successorOwner(step: BusinessStep): 'intake'|'supervisor'|'contact'|null {
  return step === 'complete-submit' || step === 'contact-submit' || step === 'capture-manual' ? 'supervisor'
    : step === 'routing-submit' ? 'intake' : step === 'assign-submit' ? 'contact' : null;
}

export class BusinessSetup {
  readonly environment: BusinessEnvironment; readonly runId: string; readonly journal: BusinessJournal;
  private gate: BusinessDispatchGate; private sessions = new Map<Alias, Session>(); private http: Array<{path: string; status: number}> = [];
  private dispatchFailed = false;
  private constructor(private readonly browser: Browser, environment: BusinessEnvironment, journal: BusinessJournal) {
    this.environment = environment; this.runId = process.env.TASK9_BUSINESS_RUN_ID!; this.journal = journal; this.gate = new BusinessDispatchGate(journal);
  }
  static async create(browser: Browser): Promise<BusinessSetup> {
    const environment = await loadBusinessEnvironment(); environment.verifyBrowser(browser.version());
    const path = join(environment.runtime, 'task9-business-operation.json'), runId = process.env.TASK9_BUSINESS_RUN_ID!;
    check(!existsSync(path) || process.env.TASK9_BUSINESS_CONTINUE_RUN_ID === runId);
    const journal = await BusinessJournal.open(path, { runId, environmentDigest: environment.environmentDigest, buildSha: environment.buildSha,
      predecessorRunId: IDENTITY_PREDECESSOR.runId, predecessorSha256: IDENTITY_PREDECESSOR.journalSha256 }, environment.assertUnchanged);
    return new BusinessSetup(browser, environment, journal);
  }
  async close() { for (const session of this.sessions.values()) await session.context.close(); this.sessions.clear(); }
  private expectedAppointment(alias: Alias): string { return alias === 'founder' ? this.environment.bootstrap.appointmentId : this.environment.resources[`appointment-${alias}` as keyof typeof this.environment.resources]; }
  private async observe(request: Request, session: Session) {
    const headers = await request.allHeaders();
    if (headers.authorization) session.auth = { Authorization: headers.authorization, 'X-Appointment-Id': session.appointmentId };
  }
  private async login(alias: Alias): Promise<Session> {
    const cached = this.sessions.get(alias); if (cached) return cached;
    await this.environment.assertUnchanged();
    const context = await this.browser.newContext({ serviceWorkers: 'block', ignoreHTTPSErrors: false, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' });
    const page = await context.newPage(), appointmentId = this.expectedAppointment(alias);
    const session: Session = { alias, context, page, self: null, auth: {}, appointmentId }; this.sessions.set(alias, session);
    await context.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url()), method = request.method();
      try {
        if (url.origin === new URL(ISSUER).origin || allowBusinessRequest(url, method)) {
          await this.observe(request, session); await route.continue(); return;
        }
        check(url.origin === ORIGIN && url.pathname.startsWith('/api/') && !this.dispatchFailed);
        const headers = request.headers(), bytes = request.postDataBuffer(); check(bytes);
        check(headers['x-appointment-id'] === session.appointmentId && !headers['x-on-behalf-appointment-id']);
        await this.gate.dispatch({ method, path: url.pathname, bodyBytes: bytes, commandId: headers['idempotency-key'], actorScopeKey: session.self?.actorScopeKey }, async () => { check(!this.dispatchFailed); await route.continue(); });
      } catch { this.dispatchFailed = true; await route.abort().catch(() => {}); }
    });
    const account = this.environment.accounts[alias];
    await page.goto(ORIGIN + '/login', { waitUntil: 'domcontentloaded', timeout: SCREEN_TIMEOUT });
    await page.getByRole('button', { name: '登录工作台', exact: true }).click({ timeout: SCREEN_TIMEOUT });
    await page.waitForURL(url => url.origin === new URL(ISSUER).origin && url.pathname.startsWith('/realms/local-r1/'), { timeout: SCREEN_TIMEOUT });
    await page.locator('input[name="username"]').fill(account.username); await page.locator('input[name="password"]').fill(account.password);
    const tokenWaiting = page.waitForResponse(r => r.url() === ISSUER + '/protocol/openid-connect/token' && r.request().method() === 'POST');
    const selfWaiting = page.waitForResponse(r => new URL(r.url()).origin === ORIGIN && new URL(r.url()).pathname === SELF);
    await page.locator('input[type="submit"],button[type="submit"]').click();
    const tokenResponse = await tokenWaiting; check(tokenResponse.status() === 200);
    const token = await tokenResponse.json(), claims = JSON.parse(Buffer.from(token.access_token.split('.')[1], 'base64url').toString('utf8'));
    check(claims.iss === ISSUER && claims.preferred_username === account.username && (!account.providerUserId || claims.sub === account.providerUserId));
    const selfResponse = await selfWaiting; this.http.push({ path: SELF, status: selfResponse.status() }); check(selfResponse.status() === 200);
    session.self = await selfResponse.json();
    check(session.self.state === 'READY' && session.self.selectedAppointmentId === appointmentId && session.self.selectedOnBehalfAppointmentId === null && session.self.appointmentChoices.length === 1 && session.self.actorScopeKey.startsWith('ask1.'));
    return session;
  }
  private async workbench(alias: 'intake' | 'supervisor' | 'contact'): Promise<Session> {
    const session = await this.login(alias); check(session.self.canEnterWorkbench && !session.self.canEnterIdentityAdmin);
    if (await session.page.getByRole('main', { name: '责任工作台', exact: true }).count() === 0) {
      await session.page.getByRole('button', { name: '确认本次身份', exact: true }).click();
      await expect(session.page.getByRole('main', { name: '责任工作台', exact: true })).toBeVisible({ timeout: SCREEN_TIMEOUT });
    }
    return session;
  }
  private async administrator(): Promise<Session> {
    const session = await this.login('founder'); check(session.self.canEnterIdentityAdmin && !session.self.canEnterWorkbench);
    if (await session.page.getByRole('main', { name: '身份管理', exact: true }).count() === 0) {
      await session.page.getByRole('button', { name: '进入身份管理', exact: true }).click();
      await session.page.getByRole('button', { name: '确认本次身份', exact: true }).click();
      await expect(session.page.getByRole('main', { name: '身份管理', exact: true })).toBeVisible({ timeout: SCREEN_TIMEOUT });
      session.self = await this.read(session, SELF);
    }
    return session;
  }
  private async fetch(session: Session, path: string, init: { method?: string; body?: Record<string, unknown>; commandId?: string; headers?: Record<string, string> } = {}): Promise<Wire> {
    const result = await session.page.evaluate(async ({ path, auth, init }) => {
      const response = await fetch(path, { method: init.method ?? 'GET', headers: { ...auth, ...(init.body ? { 'Content-Type': 'application/json' } : {}), ...(init.commandId ? { 'Idempotency-Key': init.commandId } : {}), ...init.headers }, body: init.body ? JSON.stringify(init.body) : undefined, cache: 'no-store', redirect: 'manual' });
      const text = await response.text(); return { status: response.status, headers: Object.fromEntries(response.headers.entries()), body: text ? JSON.parse(text) : null };
    }, { path: ORIGIN + path, auth: session.auth, init });
    this.http.push({ path: path.split('?')[0], status: result.status }); return result;
  }
  private async read(session: Session, path: string): Promise<any> {
    const result = await this.fetch(session, path); check(result.status === 200 && result.headers['cache-control']?.includes('no-store')); return result.body;
  }
  private async rows(session: Session, path: string): Promise<any[]> {
    const data = await this.read(session, path + '?limit=50'); check(exact(data, ['items','nextCursor']) && data.nextCursor === null && Array.isArray(data.items)); return data.items;
  }
  private async current(session: Session): Promise<any | null> {
    const result = await this.fetch(session, CURRENT); check(result.status === 200 && /^"wb\.[A-Za-z0-9_-]{43}"$/.test(result.headers.etag ?? ''));
    check(result.body && exact(result.body, ['todaySummary','currentCard','nextSummaries','waitingCount','chatComposer']));
    return result.body.currentCard;
  }
  private async refreshUi(session: Session): Promise<any | null> {
    const waiting = session.page.waitForResponse(response => new URL(response.url()).origin === ORIGIN && new URL(response.url()).pathname === CURRENT && response.request().method() === 'GET');
    await session.page.getByRole('button', { name: '刷新当前责任', exact: true }).click();
    const response = await waiting; this.http.push({ path: CURRENT, status: response.status() }); check(response.status() === 200);
    const envelope = await response.json(); check(envelope && exact(envelope, ['todaySummary','currentCard','nextSummaries','waitingCount','chatComposer']));
    if (envelope.currentCard) await expect(session.page.locator('article.current-card')).toBeVisible({ timeout: SCREEN_TIMEOUT });
    else await expect(session.page.getByRole('heading', { name: '当前暂无可处理责任', exact: true })).toBeVisible({ timeout: SCREEN_TIMEOUT });
    return envelope.currentCard;
  }
  private arm(session: Session, step: BusinessStep, method: string, path: string, body: Record<string, unknown>, card?: any) {
    check(!this.dispatchFailed && session.self.actorScopeKey); this.gate.arm({ step, method, path, body, actorScopeKey: session.self.actorScopeKey, requestSelectors: requestSelectors(session, card) });
  }
  private async complete(step: BusinessStep, response: Wire | Response, result: any, selected: BusinessSelectors) {
    const entry = this.journal.pending(); check(entry?.step === step);
    const wire: any = response;
    const status: number = typeof wire.status === 'function' ? wire.status() : wire.status;
    const headers: Record<string, string> = typeof wire.allHeaders === 'function' ? await wire.allHeaders() : wire.headers;
    check((status === 200 || status === 201) && headers['cache-control']?.includes('no-store') && headers.location === `/api/v1/commands/${entry.commandId}/receipt`);
    await this.journal.complete(entry.commandId, status, result, selected); await this.environment.assertUnchanged();
  }
  private async verifyRecorded(step: BusinessStep, session: Session) {
    const entry = this.journal.confirmed(step); if (!entry) return false;
    check(entry.actorScopeKey === session.self.actorScopeKey && entry.requestSelectors.actorAppointmentId === session.appointmentId);
    const receipt = await this.read(session, `/api/v1/commands/${entry.commandId}/receipt`);
    check(receipt.commandId === entry.commandId && receipt.receiptId === entry.receiptId && receipt.outcome === 'SUCCEEDED' && JSON.stringify(receipt.resultFact) === JSON.stringify(entry.resultFact));
    if (step === 'grant-contact-owner') {
      const id = matchFact(this.environment.bootstrap, receipt.resultFact, await this.rows(session, '/api/v1/admin/identity/authority-grants'));
      check('resourceId' in entry.selectors! && entry.selectors.resourceId === id);
    }
    return true;
  }
  private async verifyPredecessor() {
    const admin = await this.administrator(), resources = this.environment.resources;
    const principals = await this.rows(admin, '/api/v1/admin/identity/principals'), organizations = await this.rows(admin, '/api/v1/admin/identity/organizations');
    const appointments = await this.rows(admin, '/api/v1/admin/identity/appointments'), grants = await this.rows(admin, '/api/v1/admin/identity/authority-grants');
    const root = organizations.find(row => row.id === this.environment.bootstrap.rootId); check(root?.code === 'ROOT' && root.state === 'ACTIVE' && root.revision === 1);
    for (const alias of ['intake','supervisor','contact','delegate'] as const) {
      check(principals.some(row => row.id === resources[`principal-${alias}`] && row.state === 'ACTIVE'));
      const appointment = appointments.find(row => row.id === resources[`appointment-${alias}`]);
      check(appointment?.principal.id === resources[`principal-${alias}`] && appointment.state === 'ACTIVE' && appointment.organization.id === resources.organization);
    }
    const expected = [...GRANTS.intake, ...GRANTS.supervisor];
    check(grants.length === expected.length && expected.every(code => grants.some(row => row.authorityCode === code && row.state === 'ACTIVE' && row.scopeOrganization.id === this.environment.bootstrap.rootId)));
    check(grants.every(row => ![resources['appointment-contact'], resources['appointment-delegate']].includes(row.appointment.id)));
  }
  private validateCard(card: any, type: CardType, session: Session, allowDraft = false) {
    check(card?.taskType === type && uuid.test(card.taskId) && card.taskRevision >= 0 && card.subject?.subjectType === 'LEAD' && typeof card.subject.subjectRef === 'string');
    check(card.versionStatus === 'CURRENT' && card.primaryCommand.enabled && (allowDraft ? !!card.actionDraft && !!card.preconditions.draftETag : card.actionDraft === null && card.preconditions.draftETag === null));
    check(/^"task\.[A-Za-z0-9_-]{43}"$/.test(card.preconditions.taskETag) && session.self.selectedAppointmentId === session.appointmentId);
  }
  private async capture(session: Session, step: 'capture-auto' | 'capture-manual', account: 'LOCAL_SYNTHETIC_AUTO' | 'LOCAL_SYNTHETIC', withEmail: boolean, expected: CardType, taskOwner: 'intake'|'supervisor') {
    if (await this.verifyRecorded(step, session)) return;
    const body: Record<string, unknown> = { sourceChannelCode: 'LOCAL_SYNTHETIC', sourceAccountCode: account, sourceRecordKey: `task96k-${this.runId}-${step === 'capture-auto' ? 'auto' : 'manual'}`,
      capturedAt: new Date().toISOString(), serviceCategoryCode: 'LOCAL_ACCEPTANCE', jurisdictionCode: 'CN', urgencyCode: 'NORMAL', legalNeedSummary: 'Task 9.6k synthetic acceptance lead.' };
    if (withEmail) body.email = `task96k-${this.runId}-manual@example.invalid`;
    const commandId = randomUUID(); this.arm(session, step, 'POST', '/api/v1/leads', body);
    const response = await this.fetch(session, '/api/v1/leads', { method: 'POST', body, commandId });
    const existed = this.sessions.has(taskOwner), owner = taskOwner === session.alias ? session : await this.workbench(taskOwner);
    const card = taskOwner === session.alias || existed ? await this.refreshUi(owner) : await this.current(owner);
    await expect(owner.page.locator('article.current-card')).toBeVisible({ timeout: SCREEN_TIMEOUT }); this.validateCard(card, expected, owner);
    await this.complete(step, response, response.body, selectors(session, card, card));
  }
  private async card(session: Session, prefix: 'complete'|'routing'|'ack'|'assign'|'contact'|'review', type: CardType, values: Record<string, unknown>, successorType: CardType | null, successorAlias: 'intake'|'supervisor'|'contact'|null) {
    const draftStep = `${prefix}-draft` as BusinessStep, submitStep = `${prefix}-submit` as BusinessStep;
    if (await this.verifyRecorded(submitStep, session)) return;
    await this.verifyRecorded(draftStep, session);
    let card = await this.current(session); this.validateCard(card, type, session, !!this.journal.confirmed(draftStep));
    if (!this.journal.confirmed(draftStep)) {
      for (const [name, value] of Object.entries(values)) {
        const selector = name === 'sourceSummary' || name === 'resultSummary' || name === 'rationaleSummary' ? '#chat-candidate' : `#candidate-${name}`;
        const locator = session.page.locator(selector); if (name.endsWith('Code') || name === 'ownerAppointmentId') await locator.selectOption(String(value)); else await locator.fill(String(value));
      }
      await expect(session.page.locator('#primary-confirm')).toBeDisabled();
      const draftBody = candidate(card, { ...card.commandForm.values, ...values });
      this.arm(session, draftStep, 'PUT', `/api/v1/tasks/${card.taskId}/draft`, draftBody, card);
      const waiting = session.page.waitForResponse(r => new URL(r.url()).pathname === `/api/v1/tasks/${card.taskId}/draft` && r.request().method() === 'PUT');
      await session.page.getByRole('button', { name: '保存候选', exact: true }).click(); const response = await waiting; const result = await response.json();
      this.http.push({ path: new URL(response.url()).pathname, status: response.status() });
      card = { ...card, actionDraft: result.draft, preconditions: result.preconditions }; check(card.taskType === type && card.actionDraft && card.preconditions.draftETag === (await response.allHeaders()).etag);
      check(JSON.stringify(card.actionDraft.values) === JSON.stringify(draftBody.values)); await expect(session.page.locator('#primary-confirm')).toBeEnabled();
      await this.complete(draftStep, response, result.receipt, selectors(session, card, null, card.actionDraft));
    } else {
      check(card.actionDraft); await expect(session.page.locator('#primary-confirm')).toBeEnabled();
    }
    const original = card, draft = card.actionDraft; check(draft);
    const submitBody = { ...draft.values, draftId: draft.draftId, expectedDraftRevision: draft.draftRevision, draftDigest: draft.digest };
    this.arm(session, submitStep, 'POST', `/api/v1/tasks/${card.taskId}/commands/${({ complete:'complete-lead-ingress', routing:'record-routing-disposition', ack:'acknowledge-source-intake-stop-request', assign:'assign-lead', contact:'record-contact-result', review:'review-lead-validity' } as const)[prefix]}`, submitBody, card);
    const waiting = session.page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname.includes(`/api/v1/tasks/${card.taskId}/commands/`));
    const successorWaiting = session.page.waitForResponse(r => r.request().method() === 'GET' && new URL(r.url()).pathname === CURRENT);
    await session.page.locator('#primary-confirm').click(); const response = await waiting, result = await response.json();
    this.http.push({ path: new URL(response.url()).pathname, status: response.status() });
    const successorResponse = await successorWaiting; this.http.push({ path: CURRENT, status: successorResponse.status() }); check(successorResponse.status() === 200);
    const originEnvelope = await successorResponse.json(); check(originEnvelope.currentCard === null);
    let successor: any | null = null;
    if (successorType && successorAlias) {
      const existed = this.sessions.has(successorAlias), target = await this.workbench(successorAlias);
      successor = existed ? await this.refreshUi(target) : await this.current(target);
      await expect(target.page.locator('article.current-card')).toBeVisible({ timeout: SCREEN_TIMEOUT }); this.validateCard(successor, successorType, target);
    }
    else check(successorType === null && successorAlias === null);
    await this.complete(submitStep, response, result, selectors(session, original, successor, draft));
  }
  private async grant() {
    const admin = await this.administrator(), path = '/api/v1/admin/identity/authority-grants', appointmentId = this.environment.resources['appointment-contact'];
    if (await this.verifyRecorded('grant-contact-owner', admin)) return;
    const existing = (await this.rows(admin, path)).filter(row => row.appointment.id === appointmentId && row.authorityCode === 'SALES_CONTACT_OWNER');
    check(existing.length === 0);
    await admin.page.locator('nav a[href="/admin/identity/authority-grants"]').click();
    await admin.page.getByRole('button', { name: '新增直接授权', exact: true }).click();
    await admin.page.getByLabel('授权任职', { exact: true }).selectOption(appointmentId); await admin.page.getByLabel('组织范围', { exact: true }).selectOption(this.environment.bootstrap.rootId);
    await admin.page.getByLabel('权限', { exact: true }).selectOption('SALES_CONTACT_OWNER');
    const local = new Date(Date.now() + 8 * 3600_000).toISOString().slice(0, 16); await admin.page.getByLabel('生效时间', { exact: true }).fill(local);
    const body = { appointmentId, authorityCode: 'SALES_CONTACT_OWNER', scopeOrganizationId: this.environment.bootstrap.rootId, validFrom: new Date(local + ':00+08:00').toISOString(), validUntil: null };
    this.arm(admin, 'grant-contact-owner', 'POST', path, body);
    const waiting = admin.page.waitForResponse(r => new URL(r.url()).pathname === path && r.request().method() === 'POST');
    await admin.page.getByRole('button', { name: '确认创建', exact: true }).click(); const response = await waiting, result = await response.json();
    this.http.push({ path, status: response.status() });
    const id = matchFact(this.environment.bootstrap, result.resultFact, await this.rows(admin, path));
    const row = (await this.rows(admin, path)).find(x => x.id === id); check(row?.appointment.id === appointmentId && row.authorityCode === 'SALES_CONTACT_OWNER' && row.scopeOrganization.id === this.environment.bootstrap.rootId && row.state === 'ACTIVE');
    await this.complete('grant-contact-owner', response, result, { resourceId: id });
  }
  private async reconcilePending() {
    const pending = this.journal.pending(); if (!pending) return;
    check(process.env.TASK9_BUSINESS_CONTINUE_RUN_ID === this.runId);
    const session = stepAlias(pending.step) === 'founder' ? await this.administrator() : await this.workbench(stepAlias(pending.step) as 'intake'|'supervisor'|'contact');
    check(session.self.actorScopeKey === pending.actorScopeKey && session.appointmentId === pending.requestSelectors.actorAppointmentId);
    const receipt = await this.read(session, `/api/v1/commands/${pending.commandId}/receipt`);
    let result: BusinessSelectors;
    if (pending.step === 'grant-contact-owner') {
      const id = matchFact(this.environment.bootstrap, receipt.resultFact, await this.rows(session, '/api/v1/admin/identity/authority-grants')); result = { resourceId: id };
    } else {
      let current = await this.current(session); const type = commandType(pending.step), target = successorOwner(pending.step);
      if (target) current = await this.current(await this.workbench(target));
      const original = pending.step.endsWith('-draft') ? current : pending.requestSelectors.taskId ? { taskId: pending.requestSelectors.taskId, subject: { subjectType: 'LEAD', subjectRef: pending.requestSelectors.subjectRef, subjectRevision: pending.requestSelectors.subjectRevision }, preconditions: { taskETag: pending.requestSelectors.taskETag }, actionDraft: null } : current;
      check(original && (!type || pending.step.endsWith('-submit') || current?.taskType === type));
      result = selectors(session, original, pending.step.endsWith('-draft') ? null : current, pending.step.endsWith('-draft') ? current?.actionDraft : null);
    }
    await this.journal.complete(pending.commandId, 200, receipt, result);
  }
  async stage(id: BusinessCaseId) {
    const at = new Date().toISOString(), reportPath = join(this.environment.runtime, `task9-business-${this.runId}-${id}-${randomUUID()}.json`);
    let completing = false;
    try {
      await this.reconcilePending(); this.journal.requirePrevious(id); await this.environment.assertUnchanged();
      if (id === BUSINESS_CASES[0]) {
        await this.verifyPredecessor(); const intake = await this.workbench('intake');
        await this.capture(intake, 'capture-auto', 'LOCAL_SYNTHETIC_AUTO', false, 'COMPLETE_LEAD_INGRESS', 'intake');
        await this.card(intake, 'complete', 'COMPLETE_LEAD_INGRESS', { email: `task96k-${this.runId}-auto@example.invalid`, sourceCode: 'OWNER_CONFIRMED', sourceSummary: 'Task 9.6k synthetic source confirmation.' }, 'RESOLVE_LEAD_ROUTING_GAP', 'supervisor');
        const supervisor = await this.workbench('supervisor');
        await this.card(supervisor, 'routing', 'RESOLVE_LEAD_ROUTING_GAP', { decisionCode: 'REQUEST_SOURCE_INTAKE_STOP', rationaleSummary: 'Task 9.6k requests source intake stop acknowledgement.' }, 'ACK_SOURCE_INTAKE_STOP_REQUEST', 'intake');
        await this.card(intake, 'ack', 'ACK_SOURCE_INTAKE_STOP_REQUEST', { rationaleSummary: 'Task 9.6k synthetic acknowledgement received.' }, null, null);
      } else if (id === BUSINESS_CASES[1]) await this.grant();
      else {
        const intake = await this.workbench('intake');
        await this.capture(intake, 'capture-manual', 'LOCAL_SYNTHETIC', true, 'ASSIGN_LEAD', 'supervisor');
        const supervisor = await this.workbench('supervisor');
        await this.card(supervisor, 'assign', 'ASSIGN_LEAD', { ownerAppointmentId: this.environment.resources['appointment-contact'] }, 'CONTACT_LEAD', 'contact');
        const contact = await this.workbench('contact');
        await this.card(contact, 'contact', 'CONTACT_LEAD', { contactChannelCode: 'EMAIL', resultCode: 'SUSPECT_INVALID', resultSummary: 'Task 9.6k synthetic invalid-contact signal.' }, 'REVIEW_LEAD_VALIDITY', 'supervisor');
        await this.card(supervisor, 'review', 'REVIEW_LEAD_VALIDITY', { decisionCode: 'CONFIRM_INVALID', rationaleSummary: 'Task 9.6k confirms the synthetic lead invalid.' }, null, null);
      }
      await this.environment.assertUnchanged(); const http = this.http; this.http = []; completing = true;
      await this.journal.finishStage(id, { runId: this.runId, environmentDigest: this.environment.environmentDigest, buildSha: this.environment.buildSha,
        predecessorRunId: IDENTITY_PREDECESSOR.runId, predecessorSha256: IDENTITY_PREDECESSOR.journalSha256, apiIdentity: this.environment.apiIdentity,
        executedAt: at, caseIdentity: id, status: 'ACTIONS_VERIFIED', exitCode: null, reportPath, commands: this.journal.reportCommands(id), http,
        U01: 'NOT_EXECUTED', U02: 'NOT_EXECUTED', U03: 'NOT_EXECUTED' });
    } catch {
      if (!completing) {
        await protect(); writeFileSync(reportPath, JSON.stringify({ runId: this.runId, buildSha: BUSINESS_PIN.buildSha, predecessorRunId: IDENTITY_PREDECESSOR.runId,
          predecessorSha256: IDENTITY_PREDECESSOR.journalSha256, executedAt: at, caseIdentity: id, status: 'FAILED', exitCode: 1,
          reportPath, U01: 'NOT_EXECUTED', U02: 'NOT_EXECUTED', U03: 'NOT_EXECUTED' }), { flag: 'wx', mode: 0o600 }); await protect();
      }
      throw new Error(businessFailureCode(id));
    }
  }
}
