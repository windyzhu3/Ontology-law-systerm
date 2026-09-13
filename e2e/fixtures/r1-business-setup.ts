import { expect, type Browser, type BrowserContext, type Page, type Request, type Response } from '@playwright/test';
import { createHash, randomUUID } from 'node:crypto';
import { existsSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { BUSINESS_PIN, IDENTITY_PREDECESSOR, CONTACT_WAIT_PREDECESSOR, loadBusinessEnvironment, requireContactWaitAcceptance, type BusinessEnvironment } from './business-environment';
import { BusinessJournal, BUSINESS_CASES, CONTACT_WAIT_CASE, type BusinessCaseId, type BusinessEntry, type BusinessSelectors, type BusinessStep, type CardSelectors, type RequestSelectors } from './business-journal';
import { BusinessDispatchGate, allowBusinessRequest, canonicalBusinessJson } from './business-session';
import { check, exact, ORIGIN, ISSUER, protect, sha, uuid } from './local-environment';
import { GRANTS, matchFact, NAMES } from './identity-setup';
import { businessFailureCode } from '../reporters/business-reporter';
import { candidate, parseEnvelope, sameValues } from '../../apps/workbench/src/features/workcard/contract';
import { exactHeaderTokens, workbenchETag, validateWorkbenchCache, type WorkbenchCache } from './workbench-cache';

const SELF = '/api/v1/session/context';
const CURRENT = '/api/v1/workcards/current';
const SCREEN_TIMEOUT = 30_000;
const TASK_TYPES = ['COMPLETE_LEAD_INGRESS', 'RESOLVE_LEAD_ROUTING_GAP', 'ACK_SOURCE_INTAKE_STOP_REQUEST', 'ASSIGN_LEAD', 'CONTACT_LEAD', 'REVIEW_LEAD_VALIDITY'] as const;
type Alias = 'founder' | 'intake' | 'supervisor' | 'contact' | 'delegate';
type Session = {
  alias: Alias; context: BrowserContext; page: Page; self: any; auth: Record<string, string>; appointmentId: string;
  workbenchCache?: WorkbenchCache;
  workbenchGeneration?: number; workbenchPending?: Map<number, Promise<void>>;
  identityReady?: Promise<void>; workbenchObservation?: Promise<void>;
};
type Wire = { status: number; headers: Record<string, string>; body: any };
type CardType = typeof TASK_TYPES[number];
type WorkbenchRequestSnapshot = {
  generation: number; actorScopeKey: string; appointmentId: string; authorization: string;
  completion: Promise<void>; resolve: () => void; reject: (error: unknown) => void;
};
class WorkbenchIdentityError extends Error {}

function workbenchIdentity(value: unknown): asserts value {
  if (!value) throw new WorkbenchIdentityError('T9_BUSINESS_BOUNDARY');
}

function requestSelectors(session: Session, card?: any, body?: Record<string, unknown>) {
  if (!card) return { actorAppointmentId: session.appointmentId, taskId: null, subjectRef: null, subjectRevision: null, taskETag: null, draftId: null, draftRevision: null, draftDigest: null, draftETag: null, intendedValuesSha256: null };
  const draft = card.actionDraft, values = draft?.values ?? body?.values;
  return { actorAppointmentId: session.appointmentId, taskId: card.taskId, subjectRef: card.subject.subjectRef, subjectRevision: card.subject.subjectRevision, taskETag: card.preconditions.taskETag,
    draftId: draft?.draftId ?? null, draftRevision: draft?.draftRevision ?? null, draftDigest: draft?.digest ?? null, draftETag: draft ? card.preconditions.draftETag : null,
    intendedValuesSha256: values ? sha(canonicalBusinessJson(values)) : null };
}
function selectors(session: Session, original: any, successor: any | null, draft = original?.actionDraft ?? null, successorOwner: Session | null = successor ? session : null): CardSelectors {
  check(original && uuid.test(original.taskId) && original.subject?.subjectType === 'LEAD');
  return {
    taskId: original.taskId, subjectRef: original.subject.subjectRef, subjectRevision: original.subject.subjectRevision,
    ownerAppointmentId: session.appointmentId, taskETag: original.preconditions.taskETag,
    draftETag: draft ? original.preconditions.draftETag : null, draftId: draft?.draftId ?? null,
    draftRevision: draft?.draftRevision ?? null, draftDigest: draft?.digest ?? null,
    draftValuesSha256: draft ? sha(canonicalBusinessJson(draft.values)) : null,
    successorTaskId: successor?.taskId ?? null, successorTaskType: successor?.taskType ?? null,
    successorOwnerAppointmentId: successorOwner?.appointmentId ?? null, successorSubjectRef: successor?.subject.subjectRef ?? null,
    successorSubjectRevision: successor?.subject.subjectRevision ?? null, successorTaskETag: successor?.preconditions.taskETag ?? null,
  };
}
function recoveredSubmitSelectors(session: Session, value: RequestSelectors): CardSelectors {
  check(value.actorAppointmentId === session.appointmentId && uuid.test(value.taskId ?? '') && typeof value.subjectRef === 'string');
  check(Number.isSafeInteger(value.subjectRevision) && typeof value.taskETag === 'string' && uuid.test(value.draftId ?? ''));
  check(Number.isSafeInteger(value.draftRevision) && typeof value.draftDigest === 'string' && typeof value.draftETag === 'string' && typeof value.intendedValuesSha256 === 'string');
  return {
    taskId: value.taskId!, subjectRef: value.subjectRef!, subjectRevision: value.subjectRevision!, ownerAppointmentId: session.appointmentId,
    taskETag: value.taskETag!, draftId: value.draftId!, draftRevision: value.draftRevision!, draftDigest: value.draftDigest!,
    draftETag: value.draftETag!, draftValuesSha256: value.intendedValuesSha256!, successorTaskId: null, successorTaskType: null,
    successorOwnerAppointmentId: null, successorSubjectRef: null, successorSubjectRevision: null, successorTaskETag: null,
  };
}
function draftFactRef(environment: BusinessEnvironment, session: Session, draftId: string): string {
  check(['intake', 'supervisor', 'contact'].includes(session.alias) && uuid.test(draftId));
  const principalId = environment.resources[`principal-${session.alias}` as keyof typeof environment.resources];
  check(uuid.test(environment.bootstrap.tenantId) && uuid.test(principalId) && uuid.test(session.appointmentId));
  const scope = { appointment: session.appointmentId, id: draftId, kind: 'HUMAN', onBehalfAppointment: null, onBehalfPrincipal: null,
    principal: principalId, profile: 'R1_PUBLIC_FACT_REF_V1', tenant: environment.bootstrap.tenantId, type: 'responsibility.action_draft' };
  return createHash('sha256').update(canonicalBusinessJson(scope)).digest('base64url');
}
function sameInstant(actual: unknown, expected: string): boolean {
  return typeof actual === 'string' && Number.isFinite(Date.parse(actual)) && Number.isFinite(Date.parse(expected)) && Date.parse(actual) === Date.parse(expected);
}
function sameOptionalInstant(actual: unknown, expected: string | null): boolean {
  return actual === null || expected === null ? actual === expected : sameInstant(actual, expected);
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
function predecessorStep(step: BusinessStep): BusinessStep | undefined {
  const values: Partial<Record<BusinessStep, BusinessStep>> = {
    'complete-draft': 'capture-auto', 'routing-draft': 'complete-submit', 'ack-draft': 'routing-submit',
    'assign-draft': 'capture-manual', 'contact-draft': 'assign-submit', 'review-draft': 'contact-submit',
  };
  return values[step];
}

export class BusinessSetup {
  readonly environment: BusinessEnvironment; readonly runId: string; readonly journal: BusinessJournal;
  private gate: BusinessDispatchGate; private sessions = new Map<Alias, Session>(); private http: Array<{path: string; status: number}> = [];
  private workbenchObservations = new WeakMap<Response, Promise<void>>();
  private workbenchRequests = new WeakMap<Request, WorkbenchRequestSnapshot>();
  private contactAppointment?: { effectiveFrom: string; effectiveUntil: string | null };
  private dispatchFailed = false;
  private constructor(private readonly browser: Browser, environment: BusinessEnvironment, journal: BusinessJournal, private readonly contactWait = false) {
    this.environment = environment; this.runId = process.env.TASK9_BUSINESS_RUN_ID!; this.journal = journal; this.gate = new BusinessDispatchGate(journal);
  }
  static async create(browser: Browser, loadEnvironment = loadBusinessEnvironment): Promise<BusinessSetup> {
    check(process.env.TASK9_BUSINESS_ACCEPTANCE !== 'APPROVED_CONTACT_WAIT_CHAIN');
    const environment = await loadEnvironment(); environment.verifyBrowser(browser.version());
    check(!environment.waitingPredecessor);
    const path = join(environment.runtime, 'task9-business-operation.json'), runId = process.env.TASK9_BUSINESS_RUN_ID!;
    check(!existsSync(path) || process.env.TASK9_BUSINESS_CONTINUE_RUN_ID === runId);
    const journal = await BusinessJournal.open(path, { runId, environmentDigest: environment.environmentDigest, buildSha: environment.buildSha,
      predecessorRunId: IDENTITY_PREDECESSOR.runId, predecessorSha256: IDENTITY_PREDECESSOR.journalSha256 }, environment.assertUnchanged, environment.restart);
    return new BusinessSetup(browser, environment, journal);
  }
  static async createContactWait(browser: Browser, loadEnvironment = loadBusinessEnvironment): Promise<BusinessSetup> {
    requireContactWaitAcceptance(); const environment = await loadEnvironment(); environment.verifyBrowser(browser.version());
    check(environment.waitingPredecessor && !environment.restart);
    const path = join(environment.runtime, 'task9-contact-wait-operation.json'), runId = process.env.TASK9_BUSINESS_RUN_ID!;
    check(!existsSync(path) || process.env.TASK9_BUSINESS_CONTINUE_RUN_ID === runId);
    const journal = await BusinessJournal.openContactWait(path, { runId, environmentDigest: environment.environmentDigest, buildSha: environment.buildSha,
      predecessorRunId: CONTACT_WAIT_PREDECESSOR.runId, predecessorSha256: CONTACT_WAIT_PREDECESSOR.journalSha256 }, environment.assertUnchanged);
    return new BusinessSetup(browser, environment, journal, true);
  }
  async close() { for (const session of this.sessions.values()) await session.context.close(); this.sessions.clear(); }
  private expectedAppointment(alias: Alias): string { return alias === 'founder' ? this.environment.bootstrap.appointmentId : this.environment.resources[`appointment-${alias}` as keyof typeof this.environment.resources]; }
  private async observe(request: Request, session: Session) {
    const headers = await request.allHeaders();
    if (headers.authorization) session.auth = { Authorization: headers.authorization, 'X-Appointment-Id': session.appointmentId };
    const url = new URL(request.url());
    if (url.origin === ORIGIN && url.pathname === CURRENT && request.method() === 'GET') {
      const generation = (session.workbenchGeneration ?? 0) + 1, actorScopeKey = session.self?.actorScopeKey;
      check(typeof actorScopeKey === 'string' && /^Bearer \S+$/.test(headers.authorization ?? ''));
      let resolve!: () => void, reject!: (error: unknown) => void;
      const completion = new Promise<void>((ok, fail) => { resolve = ok; reject = fail; });
      const snapshot = { generation, actorScopeKey, appointmentId: session.appointmentId, authorization: headers.authorization!, completion, resolve, reject };
      session.workbenchGeneration = generation; (session.workbenchPending ??= new Map()).set(generation, completion);
      this.workbenchRequests.set(request, snapshot);
      void completion.catch(() => { session.auth = {}; session.workbenchCache = undefined; this.dispatchFailed = true; });
      void completion.finally(() => { session.workbenchPending?.delete(generation); }).catch(() => {});
    }
  }
  private settleWorkbenchFailure(session: Session, snapshot: WorkbenchRequestSnapshot, error: unknown): void {
    const sameActor = snapshot.actorScopeKey === session.self?.actorScopeKey && snapshot.appointmentId === session.appointmentId
      && session.self?.selectedAppointmentId === snapshot.appointmentId;
    if (!(error instanceof WorkbenchIdentityError) && sameActor && snapshot.generation !== session.workbenchGeneration) snapshot.resolve();
    else snapshot.reject(error);
  }
  private async validateWorkbenchResponse(response: Response, session: Session, snapshot: WorkbenchRequestSnapshot): Promise<void> {
    await session.identityReady;
    const url = new URL(response.url()), request = response.request();
    workbenchIdentity(url.origin === ORIGIN && url.pathname === CURRENT && request.method() === 'GET');
    let requestHeaders: Record<string, string>;
    try { requestHeaders = await request.allHeaders(); } catch { throw new WorkbenchIdentityError('T9_BUSINESS_BOUNDARY'); }
    const currentActor = () => snapshot.actorScopeKey === session.self?.actorScopeKey && snapshot.appointmentId === session.appointmentId
      && session.self?.selectedAppointmentId === snapshot.appointmentId;
    workbenchIdentity(currentActor() && /^Bearer \S+$/.test(requestHeaders.authorization ?? '') && requestHeaders.authorization === snapshot.authorization
      && requestHeaders['x-appointment-id'] === snapshot.appointmentId && !requestHeaders['x-on-behalf-appointment-id']);
    if (snapshot.generation !== session.workbenchGeneration) return;
    const responseHeaders = await response.allHeaders();
    check(session.auth.Authorization === snapshot.authorization && session.auth['X-Appointment-Id'] === snapshot.appointmentId);
    check(exactHeaderTokens(responseHeaders['cache-control'], ['private', 'no-cache'])
      && exactHeaderTokens(responseHeaders.vary, ['authorization']) && workbenchETag(responseHeaders.etag));
    if (response.status() === 200) {
      const envelope = await response.json();
      check(currentActor()); if (snapshot.generation !== session.workbenchGeneration) return;
      session.workbenchCache = validateWorkbenchCache({ status: 200, headers: responseHeaders, body: envelope, actorScopeKey: snapshot.actorScopeKey, generation: snapshot.generation });
      return;
    }
    session.workbenchCache = validateWorkbenchCache({ status: response.status(), headers: responseHeaders, requestHeaders, actorScopeKey: snapshot.actorScopeKey, generation: snapshot.generation }, session.workbenchCache);
  }
  private observeWorkbenchResponse(response: Response, session: Session): Promise<void> {
    const existing = this.workbenchObservations.get(response); if (existing) return existing;
    const snapshot = this.workbenchRequests.get(response.request());
    if (!snapshot) return Promise.reject(new Error('T9_BUSINESS_BOUNDARY'));
    const validation = this.validateWorkbenchResponse(response, session, snapshot);
    void validation.then(snapshot.resolve, error => this.settleWorkbenchFailure(session, snapshot, error));
    this.workbenchObservations.set(response, snapshot.completion); session.workbenchObservation = snapshot.completion;
    return snapshot.completion;
  }
  private installWorkbenchResponseObserver(session: Session): void {
    session.page.on('response', response => {
      const url = new URL(response.url());
      if (url.origin === ORIGIN && url.pathname === CURRENT && response.request().method() === 'GET') this.observeWorkbenchResponse(response, session);
    });
    session.page.on('requestfailed', request => {
      const snapshot = this.workbenchRequests.get(request);
      if (snapshot) this.settleWorkbenchFailure(session, snapshot, new Error('T9_BUSINESS_BOUNDARY'));
    });
  }
  private async awaitWorkbenchIdle(session: Session): Promise<void> {
    try {
      while ((session.workbenchPending?.size ?? 0) > 0) {
        const pending = [...session.workbenchPending!.values()];
        await new Promise<void>((resolve, reject) => {
          const timeout = setTimeout(() => reject(new Error('T9_BUSINESS_BOUNDARY')), SCREEN_TIMEOUT);
          Promise.all(pending).then(() => { clearTimeout(timeout); resolve(); }, error => { clearTimeout(timeout); reject(error); });
        });
      }
      check(!this.dispatchFailed);
    } catch (error) {
      session.auth = {}; session.workbenchCache = undefined; this.dispatchFailed = true; throw error;
    }
  }
  private async refreshCachedSession(session: Session): Promise<void> {
    // Let the SPA validate/renew its own token before reusing an observed header after an idle phase.
    try {
      await this.awaitWorkbenchIdle(session);
      session.auth = {};
      check(!this.dispatchFailed && session.self.selectedAppointmentId === session.appointmentId);
      const pageUrl = new URL(session.page.url()); check(pageUrl.origin === ORIGIN);
      const admin = session.alias === 'founder';
      if (admin) check(/^\/admin\/identity\/(principals|organizations|appointments|authority-grants)$/.test(pageUrl.pathname));
      const path = admin ? '/api/v1' + pageUrl.pathname : CURRENT;
      const beforeGeneration = session.workbenchGeneration ?? 0;
      const [response] = await Promise.all([
        session.page.waitForResponse(r => new URL(r.url()).origin === ORIGIN && new URL(r.url()).pathname === path && r.request().method() === 'GET'
          && (admin || (this.workbenchRequests.get(r.request())?.generation ?? 0) > beforeGeneration), { timeout: SCREEN_TIMEOUT }),
        session.page.getByRole('button', { name: admin ? '刷新' : '刷新当前责任', exact: true }).click({ timeout: SCREEN_TIMEOUT }),
      ]);
      this.http.push({ path, status: response.status() });
      if (admin) {
        const responseHeaders = await response.allHeaders(), headers = await response.request().allHeaders();
        check(response.status() === 200 && exactHeaderTokens(responseHeaders['cache-control'], ['no-store']));
        check(/^Bearer \S+$/.test(headers.authorization ?? '') && headers['x-appointment-id'] === session.appointmentId && !headers['x-on-behalf-appointment-id']);
        check(session.auth.Authorization === headers.authorization && session.auth['X-Appointment-Id'] === session.appointmentId);
      } else await this.observeWorkbenchResponse(response, session);
    } catch {
      session.auth = {}; session.workbenchCache = undefined; this.dispatchFailed = true;
      throw new Error('T9_BUSINESS_BOUNDARY');
    }
  }
  private async login(alias: Alias): Promise<Session> {
    const cached = this.sessions.get(alias); if (cached) { await this.refreshCachedSession(cached); return cached; }
    await this.environment.assertUnchanged();
    const context = await this.browser.newContext({ serviceWorkers: 'block', ignoreHTTPSErrors: false, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' });
    const page = await context.newPage(), appointmentId = this.expectedAppointment(alias);
    let identityReady!: () => void;
    const session: Session = { alias, context, page, self: null, auth: {}, appointmentId,
      identityReady: new Promise(resolve => { identityReady = resolve; }) };
    this.sessions.set(alias, session); this.installWorkbenchResponseObserver(session);
    await context.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url()), method = request.method();
      try {
        if (allowBusinessRequest(url, method)) {
          await this.observe(request, session); await route.continue(); return;
        }
        check(url.origin === ORIGIN && url.pathname.startsWith('/api/') && !this.dispatchFailed && (session.workbenchPending?.size ?? 0) === 0);
        const headers = request.headers(), bytes = request.postDataBuffer(); check(bytes);
        check(headers['x-appointment-id'] === session.appointmentId && !headers['x-on-behalf-appointment-id']);
        await this.gate.dispatch({ method, path: url.pathname, bodyBytes: bytes, commandId: headers['idempotency-key'], actorScopeKey: session.self?.actorScopeKey }, async () => {
          check(!this.dispatchFailed && (session.workbenchPending?.size ?? 0) === 0); await route.continue();
        });
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
    identityReady();
    return session;
  }
  private async workbench(alias: 'intake' | 'supervisor' | 'contact'): Promise<Session> {
    const session = await this.login(alias); check(session.self.canEnterWorkbench && !session.self.canEnterIdentityAdmin);
    if (await session.page.getByRole('main', { name: '责任工作台', exact: true }).count() === 0) {
      await session.page.getByRole('button', { name: '确认本次身份', exact: true }).click();
      await expect(session.page.getByRole('main', { name: '责任工作台', exact: true })).toBeVisible({ timeout: SCREEN_TIMEOUT });
    }
    await this.awaitWorkbenchIdle(session);
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
  private async current(session: Session): Promise<any | null> { return (await this.currentEnvelope(session)).currentCard; }
  private async currentEnvelope(session: Session): Promise<ReturnType<typeof parseEnvelope>> {
    try {
      await this.awaitWorkbenchIdle(session);
      const actorScopeKey = session.self?.actorScopeKey, appointmentId = session.appointmentId;
      const authorization = session.auth.Authorization, generation = session.workbenchGeneration ?? 0;
      const unchanged = () => !this.dispatchFailed && (session.workbenchPending?.size ?? 0) === 0
        && session.workbenchGeneration === generation && session.self?.actorScopeKey === actorScopeKey
        && session.self?.selectedAppointmentId === appointmentId && session.appointmentId === appointmentId
        && session.auth.Authorization === authorization && session.auth['X-Appointment-Id'] === appointmentId;
      check(typeof actorScopeKey === 'string' && /^Bearer \S+$/.test(authorization ?? '') && unchanged());
      const response = await session.context.request.get(ORIGIN + CURRENT, { headers: session.auth, failOnStatusCode: false, maxRedirects: 0 });
      check(unchanged());
      const headers = response.headers(), status = response.status(); this.http.push({ path: CURRENT, status });
      check(status === 200 && exactHeaderTokens(headers['cache-control'], ['private', 'no-cache'])
        && exactHeaderTokens(headers.vary, ['authorization']) && workbenchETag(headers.etag));
      const body = JSON.parse(await response.text()); check(unchanged()); return parseEnvelope(body);
    } catch {
      session.auth = {}; session.workbenchCache = undefined; this.dispatchFailed = true;
      throw new Error('T9_BUSINESS_BOUNDARY');
    }
  }
  private async refreshUi(session: Session): Promise<any | null> {
    const waiting = session.page.waitForResponse(response => new URL(response.url()).origin === ORIGIN && new URL(response.url()).pathname === CURRENT && response.request().method() === 'GET');
    await session.page.getByRole('button', { name: '刷新当前责任', exact: true }).click();
    const response = await waiting; this.http.push({ path: CURRENT, status: response.status() }); check(response.status() === 200);
    const envelope = await response.json(); check(envelope && exact(envelope, ['todaySummary','currentCard','nextSummaries','waitingCount','chatComposer']));
    if (envelope.currentCard) await expect(session.page.locator('article.current-card')).toBeVisible({ timeout: SCREEN_TIMEOUT });
    else await expect(session.page.getByRole('heading', { name: envelope.waitingCount > 0 ? '当前无可处理责任，另有等待事项' : '当前暂无可处理责任', exact: true })).toBeVisible({ timeout: SCREEN_TIMEOUT });
    return envelope.currentCard;
  }
  private arm(session: Session, step: BusinessStep, method: string, path: string, body: Record<string, unknown>, card?: any) {
    check(!this.dispatchFailed && (session.workbenchPending?.size ?? 0) === 0 && session.self.actorScopeKey);
    if (session.alias !== 'founder') check(session.workbenchCache?.actorScopeKey === session.self.actorScopeKey);
    this.gate.arm({ step, method, path, body, actorScopeKey: session.self.actorScopeKey, requestSelectors: requestSelectors(session, card, body) });
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
  private requireAppointment(appointments: any[], alias: 'intake'|'supervisor'|'contact'|'delegate') {
    const resources = this.environment.resources, appointment = appointments.find(row => row.id === resources[`appointment-${alias}`]);
    const role = alias === 'intake' ? 'INTAKE_OPERATOR' : alias === 'supervisor' ? 'ROUTING_SUPERVISOR' : 'CONTACT_OPERATOR';
    check(appointment && exact(appointment, ['id','principal','organization','roleCode','effectiveFrom','effectiveUntil','state','etag'])
      && appointment.principal.id === resources[`principal-${alias}`] && appointment.state === 'ACTIVE' && appointment.organization.id === resources.organization && appointment.roleCode === role);
    const from = Date.parse(appointment.effectiveFrom), until = appointment.effectiveUntil === null ? null : Date.parse(appointment.effectiveUntil);
    check(Number.isFinite(from) && from <= Date.now() && (until === null || Number.isFinite(until) && until > Date.now()) && /^"identity\.[A-Za-z0-9_-]{43}"$/.test(appointment.etag));
    return { effectiveFrom: appointment.effectiveFrom as string, effectiveUntil: appointment.effectiveUntil as string | null };
  }
  private async verifyPredecessor() {
    const admin = await this.administrator(), resources = this.environment.resources;
    const principals = await this.rows(admin, '/api/v1/admin/identity/principals'), organizations = await this.rows(admin, '/api/v1/admin/identity/organizations');
    const appointments = await this.rows(admin, '/api/v1/admin/identity/appointments');
    let grants = await this.rows(admin, '/api/v1/admin/identity/authority-grants');
    const root = organizations.find(row => row.id === this.environment.bootstrap.rootId);
    check(root && exact(root, ['id','parentOrganizationId','code','displayName','state','etag']) && root.code === 'ROOT' && root.parentOrganizationId === null && root.state === 'ACTIVE' && /^"identity\.[A-Za-z0-9_-]{43}"$/.test(root.etag));
    const originalOrganization = organizations.find(row => row.id === resources.organization);
    check(originalOrganization && exact(originalOrganization, ['id','parentOrganizationId','code','displayName','state','etag'])
      && originalOrganization.parentOrganizationId === this.environment.bootstrap.rootId && originalOrganization.state === 'ACTIVE' && /^"identity\.[A-Za-z0-9_-]{43}"$/.test(originalOrganization.etag));
    for (const alias of ['intake','supervisor','contact','delegate'] as const) {
      check(principals.some(row => row.id === resources[`principal-${alias}`] && row.state === 'ACTIVE'));
      const appointment = this.requireAppointment(appointments, alias);
      if (alias === 'contact') this.contactAppointment = appointment;
    }
    if (this.contactWait) {
      this.verifyOriginalContactGrant(grants); grants = grants.filter(row => row.id !== this.environment.waitingPredecessor!.contactGrantId);
    }
    const expected = [...GRANTS.intake, ...GRANTS.supervisor];
    const businessSteps = ['grant-intake-0','grant-intake-1','grant-intake-2','grant-intake-3','grant-supervisor-0','grant-supervisor-1','grant-supervisor-2'] as const;
    const business = grants.filter(row => expected.includes(row.authorityCode));
    const management = ['IDENTITY_PRINCIPAL_MANAGE', 'IDENTITY_ORGANIZATION_MANAGE', 'IDENTITY_APPOINTMENT_MANAGE', 'IDENTITY_AUTHORITY_MANAGE'];
    check(grants.length === 11 && business.length === expected.length && expected.every(code => business.some(row => row.authorityCode === code && row.state === 'ACTIVE' && row.scopeOrganization.id === this.environment.bootstrap.rootId)));
    check(businessSteps.every((step, index) => business.some(row => row.id === resources[step] && row.authorityCode === expected[index]
      && row.appointment.id === resources[step.startsWith('grant-intake-') ? 'appointment-intake' : 'appointment-supervisor'])));
    check(management.every(code => grants.some(row => row.authorityCode === code && row.appointment.id === this.environment.bootstrap.appointmentId && row.scopeOrganization.id === this.environment.bootstrap.rootId && row.state === 'ACTIVE')));
    check(grants.every(row => ![resources['appointment-contact'], resources['appointment-delegate']].includes(row.appointment.id)));
  }
  private verifyOriginalContactGrant(grants: any[]) {
    const predecessor = this.environment.waitingPredecessor; check(predecessor && this.contactAppointment);
    predecessor.assertUnchanged(); check(grants.length === 12);
    const owned = grants.filter(row => row.appointment.id === this.environment.resources['appointment-contact']);
    check(owned.length === 1); const row = owned[0];
    check(exact(row, ['id','appointment','authorityCode','scopeOrganization','validFrom','validUntil','state','etag']) && row.id === predecessor.contactGrantId && row.authorityCode === 'SALES_CONTACT_OWNER' && row.state === 'ACTIVE' && row.scopeOrganization.id === this.environment.bootstrap.rootId && /^"identity\.[A-Za-z0-9_-]{43}"$/.test(row.etag));
    const from = Date.parse(row.validFrom), until = row.validUntil === null ? null : Date.parse(row.validUntil);
    check(Number.isFinite(from) && from >= Date.parse(this.contactAppointment.effectiveFrom) && from <= Date.now());
    check(until === null || Number.isFinite(until) && until > Date.now() && until > from);
    check(this.contactAppointment.effectiveUntil === null || until !== null && until <= Date.parse(this.contactAppointment.effectiveUntil));
  }
  private async requireWaitingUi(session: Session, value: unknown) {
    const envelope = parseEnvelope(value);
    check(envelope.currentCard === null && envelope.nextSummaries.length === 0 && envelope.waitingCount === 1 && envelope.chatComposer.targetTaskId === null && envelope.chatComposer.enabled === false);
    await expect(session.page.getByRole('heading', { name: '当前无可处理责任，另有等待事项', exact: true })).toBeVisible({ timeout: SCREEN_TIMEOUT });
    await expect(session.page.locator('article.current-card')).toHaveCount(0);
    await expect(session.page.locator('.next-summary')).toHaveCount(0);
    await expect(session.page.locator('.waiting-count > span').first()).toHaveText('等待 1');
    await expect(session.page.locator('.today-summary p')).toHaveText(envelope.todaySummary);
    await expect(session.page.locator('#primary-confirm')).toHaveCount(0);
    await expect(session.page.getByRole('button', { name: '保存候选', exact: true })).toBeDisabled();
  }
  private validateCard(card: any, type: CardType, session: Session, allowDraft = false) {
    check(card?.taskType === type && uuid.test(card.taskId) && card.taskRevision >= 0 && card.subject?.subjectType === 'LEAD' && typeof card.subject.subjectRef === 'string');
    check(card.versionStatus === 'CURRENT' && card.primaryCommand.enabled && (allowDraft ? !!card.actionDraft && !!card.preconditions.draftETag : card.actionDraft === null && card.preconditions.draftETag === null));
    check(/^"task\.[A-Za-z0-9_-]{43}"$/.test(card.preconditions.taskETag) && session.self.selectedAppointmentId === session.appointmentId);
  }
  private requireRecordedCard(entry: BusinessEntry, card: any, session: Session) {
    check('taskId' in entry.selectors! && entry.selectors.ownerAppointmentId === session.appointmentId);
    check(card.taskId === entry.requestSelectors.taskId && card.taskId === entry.selectors.taskId);
    check(card.subject.subjectRef === entry.requestSelectors.subjectRef && card.subject.subjectRef === entry.selectors.subjectRef);
    check(card.subject.subjectRevision === entry.requestSelectors.subjectRevision && card.subject.subjectRevision === entry.selectors.subjectRevision);
    check(card.preconditions.taskETag === entry.requestSelectors.taskETag && card.preconditions.taskETag === entry.selectors.taskETag);
    check(card.actionDraft?.draftId === entry.selectors.draftId && card.preconditions.draftETag === entry.selectors.draftETag);
    check(card.actionDraft?.draftRevision === entry.selectors.draftRevision && card.actionDraft?.digest === entry.selectors.draftDigest);
    check(sha(canonicalBusinessJson(card.actionDraft?.values)) === entry.selectors.draftValuesSha256);
  }
  private requireExpectedSuccessor(entry: BusinessEntry, card: any, session: Session) {
    check('successorTaskId' in entry.selectors! && entry.selectors.successorOwnerAppointmentId === session.appointmentId);
    check(card.taskId === entry.selectors.successorTaskId && card.taskType === entry.selectors.successorTaskType);
    check(card.subject.subjectRef === entry.selectors.successorSubjectRef && card.subject.subjectRevision === entry.selectors.successorSubjectRevision);
    check(card.preconditions.taskETag === entry.selectors.successorTaskETag);
  }
  private requireKnownSuccessor(step: BusinessStep, original: any, receipt: any, successor: any) {
    if (step === 'complete-submit') check(receipt.resultFact?.factType === 'LEAD' && successor.subject.subjectRevision === receipt.resultFact.revision);
    else if (step === 'assign-submit') {
      const revision = original.subject.subjectRevision;
      check(Number.isSafeInteger(revision) && revision >= 0 && revision < Number.MAX_SAFE_INTEGER);
      check(successor.subject.subjectRevision === revision + 1);
    }
    else check(successor.subject.subjectRevision === original.subject.subjectRevision);
    const values = successor.commandForm?.values;
    if (step === 'routing-submit') check(receipt.resultFact?.factType === 'DECISION_RECORD' && values?.causalDecisionHash === receipt.resultFact.digest && uuid.test(values.causalDecisionId));
    if (step === 'assign-submit') check(receipt.resultFact?.factType === 'LEAD_ASSIGNMENT' && receipt.resultFact.revision === 0 && values?.leadAssignmentRevision === receipt.resultFact.revision && uuid.test(values.leadAssignmentId));
    if (step === 'contact-submit') check(receipt.resultFact?.factType === 'LEAD_CONTACT_RESULT' && values?.triggeringContactResultHash === receipt.resultFact.digest && uuid.test(values.triggeringContactResultId));
  }
  private async capture(session: Session, step: 'capture-auto' | 'capture-manual', account: 'LOCAL_SYNTHETIC_AUTO' | 'LOCAL_SYNTHETIC', withEmail: boolean, expected: CardType, taskOwner: 'intake'|'supervisor') {
    if (await this.verifyRecorded(step, session)) return;
    const owner = taskOwner === session.alias ? session : await this.workbench(taskOwner);
    check(await this.current(owner) === null);
    const prefix = this.contactWait ? 'task96p' : 'task96k';
    const body: Record<string, unknown> = { sourceChannelCode: 'LOCAL_SYNTHETIC', sourceAccountCode: account, sourceRecordKey: `${prefix}-${this.runId}-${step === 'capture-auto' ? 'auto' : 'manual'}`,
      capturedAt: new Date().toISOString(), serviceCategoryCode: 'LOCAL_ACCEPTANCE', jurisdictionCode: 'CN', urgencyCode: 'NORMAL', legalNeedSummary: this.contactWait ? 'Task 9.6p synthetic acceptance lead.' : 'Task 9.6k synthetic acceptance lead.' };
    if (withEmail) body.email = `${prefix}-${this.runId}-manual@example.invalid`;
    const commandId = randomUUID(); this.arm(session, step, 'POST', '/api/v1/leads', body);
    const response = await this.fetch(session, '/api/v1/leads', { method: 'POST', body, commandId });
    const card = await this.refreshUi(owner);
    await expect(owner.page.locator('article.current-card')).toBeVisible({ timeout: SCREEN_TIMEOUT }); this.validateCard(card, expected, owner);
    check(response.body?.resultFact?.factType === 'LEAD' && response.body.resultFact.revision === card.subject.subjectRevision);
    await this.complete(step, response, response.body, selectors(owner, card, card));
  }
  private async card(session: Session, prefix: 'complete'|'routing'|'ack'|'assign'|'contact'|'review', type: CardType, values: Record<string, unknown>, successorType: CardType | null, successorAlias: 'intake'|'supervisor'|'contact'|null) {
    const draftStep = `${prefix}-draft` as BusinessStep, submitStep = `${prefix}-submit` as BusinessStep;
    if (await this.verifyRecorded(submitStep, session)) return;
    await this.verifyRecorded(draftStep, session);
    const recordedDraft = this.journal.confirmed(draftStep);
    let card = await this.current(session); this.validateCard(card, type, session, !!recordedDraft);
    const previous = predecessorStep(draftStep); check(previous); const predecessor = this.journal.confirmed(previous); check(predecessor);
    this.requireExpectedSuccessor(predecessor, card, session);
    if (recordedDraft) this.requireRecordedCard(recordedDraft, card, session);
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
      check(sameValues(card.actionDraft.values, draftBody.values)); await expect(session.page.locator('#primary-confirm')).toBeEnabled();
      await this.complete(draftStep, response, result.receipt, selectors(session, card, null, card.actionDraft));
    } else {
      check(card.actionDraft); await expect(session.page.locator('#primary-confirm')).toBeEnabled();
    }
    const original = card, draft = card.actionDraft; check(draft);
    const submitBody = { ...draft.values, draftId: draft.draftId, expectedDraftRevision: draft.draftRevision, draftDigest: draft.digest };
    const successorTarget = successorType && successorAlias ? await this.workbench(successorAlias) : null;
    if (successorTarget) check(await this.current(successorTarget) === null);
    else check(successorType === null && successorAlias === null);
    this.arm(session, submitStep, 'POST', `/api/v1/tasks/${card.taskId}/commands/${({ complete:'complete-lead-ingress', routing:'record-routing-disposition', ack:'acknowledge-source-intake-stop-request', assign:'assign-lead', contact:'record-contact-result', review:'review-lead-validity' } as const)[prefix]}`, submitBody, card);
    const waiting = session.page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname.includes(`/api/v1/tasks/${card.taskId}/commands/`));
    const successorWaiting = session.page.waitForResponse(r => r.request().method() === 'GET' && new URL(r.url()).pathname === CURRENT);
    await session.page.locator('#primary-confirm').click(); const response = await waiting, result = await response.json();
    this.http.push({ path: new URL(response.url()).pathname, status: response.status() });
    const successorResponse = await successorWaiting; this.http.push({ path: CURRENT, status: successorResponse.status() }); check(successorResponse.status() === 200);
    const originEnvelope = await successorResponse.json(); check(originEnvelope.currentCard === null);
    if (this.contactWait && submitStep === 'contact-submit') {
      await this.observeWorkbenchResponse(successorResponse, session);
      await this.requireWaitingUi(session, originEnvelope);
      await this.requireWaitingUi(session, await this.currentEnvelope(session));
    }
    let successor: any | null = null;
    if (successorType && successorTarget) {
      successor = await this.refreshUi(successorTarget);
      await expect(successorTarget.page.locator('article.current-card')).toBeVisible({ timeout: SCREEN_TIMEOUT }); this.validateCard(successor, successorType, successorTarget);
      this.requireKnownSuccessor(submitStep, original, result, successor);
    }
    await this.complete(submitStep, response, result, selectors(session, original, successor, draft, successorTarget));
  }
  private async grant() {
    const admin = await this.administrator(), path = '/api/v1/admin/identity/authority-grants', appointmentId = this.environment.resources['appointment-contact'];
    const appointment = this.contactAppointment ?? this.requireAppointment(await this.rows(admin, '/api/v1/admin/identity/appointments'), 'contact');
    if (await this.verifyRecorded('grant-contact-owner', admin)) return;
    const existing = (await this.rows(admin, path)).filter(row => row.appointment.id === appointmentId && row.authorityCode === 'SALES_CONTACT_OWNER');
    check(existing.length === 0);
    await admin.page.locator('nav a[href="/admin/identity/authority-grants"]').click();
    await admin.page.getByRole('button', { name: '新增直接授权', exact: true }).click();
    await admin.page.getByLabel('授权任职', { exact: true }).selectOption(appointmentId); await admin.page.getByLabel('组织范围', { exact: true }).selectOption(this.environment.bootstrap.rootId);
    await admin.page.getByLabel('权限', { exact: true }).selectOption('SALES_CONTACT_OWNER');
    const startMillis = Math.floor(Date.now() / 60_000) * 60_000;
    const endMillis = appointment.effectiveUntil === null ? null : Math.floor(Date.parse(appointment.effectiveUntil) / 60_000) * 60_000;
    check(startMillis >= Date.parse(appointment.effectiveFrom) && startMillis <= Date.now() && (endMillis === null || endMillis > startMillis));
    const validFrom = new Date(startMillis).toISOString(), validUntil = endMillis === null ? null : new Date(endMillis).toISOString();
    const local = (instant: string) => new Date(Date.parse(instant) + 8 * 3600_000).toISOString().slice(0, 16);
    await admin.page.getByLabel('生效时间', { exact: true }).fill(local(validFrom));
    if (validUntil) await admin.page.getByLabel('失效时间', { exact: true }).fill(local(validUntil));
    const body = { appointmentId, authorityCode: 'SALES_CONTACT_OWNER', scopeOrganizationId: this.environment.bootstrap.rootId, validFrom, validUntil };
    this.arm(admin, 'grant-contact-owner', 'POST', path, body);
    const waiting = admin.page.waitForResponse(r => new URL(r.url()).pathname === path && r.request().method() === 'POST');
    await admin.page.getByRole('button', { name: '确认创建', exact: true }).click(); const response = await waiting, result = await response.json();
    this.http.push({ path, status: response.status() });
    const id = matchFact(this.environment.bootstrap, result.resultFact, await this.rows(admin, path));
    const row = (await this.rows(admin, path)).find(x => x.id === id); check(row?.appointment.id === appointmentId && row.authorityCode === 'SALES_CONTACT_OWNER' && row.scopeOrganization.id === this.environment.bootstrap.rootId && row.state === 'ACTIVE' && sameInstant(row.validFrom, validFrom) && sameOptionalInstant(row.validUntil, validUntil));
    await this.complete('grant-contact-owner', response, result, { resourceId: id });
  }
  private async reconcilePending() {
    const pending = this.journal.pending(); if (!pending) return;
    check(process.env.TASK9_BUSINESS_CONTINUE_RUN_ID === this.runId);
    const session = stepAlias(pending.step) === 'founder' ? await this.administrator() : await this.workbench(stepAlias(pending.step) as 'intake'|'supervisor'|'contact');
    check(session.self.actorScopeKey === pending.actorScopeKey && session.appointmentId === pending.requestSelectors.actorAppointmentId);
    const receipt = await this.read(session, `/api/v1/commands/${pending.commandId}/receipt`);
    check(receipt?.commandId === pending.commandId && receipt.outcome === 'SUCCEEDED');
    if (pending.step === 'capture-auto' || pending.step === 'capture-manual') throw new Error('T9_BUSINESS_BOUNDARY');
    if (pending.step.endsWith('-submit') && successorOwner(pending.step)) throw new Error('T9_BUSINESS_BOUNDARY');
    let result: BusinessSelectors;
    if (pending.step === 'grant-contact-owner') {
      const id = matchFact(this.environment.bootstrap, receipt.resultFact, await this.rows(session, '/api/v1/admin/identity/authority-grants')); result = { resourceId: id };
    } else if (pending.step.endsWith('-submit')) {
      result = recoveredSubmitSelectors(session, pending.requestSelectors);
    } else {
      let owner = session, current = await this.current(session); const type = commandType(pending.step), target = successorOwner(pending.step);
      if (target) { owner = await this.workbench(target); current = await this.current(owner); }
      if (pending.step.endsWith('-draft')) {
        check(type); this.validateCard(current, type, session, true);
        check(current.taskId === pending.requestSelectors.taskId && current.subject.subjectRef === pending.requestSelectors.subjectRef
          && current.subject.subjectRevision === pending.requestSelectors.subjectRevision && current.preconditions.taskETag === pending.requestSelectors.taskETag);
        check(current.actionDraft.draftRevision === receipt.resultFact?.revision && receipt.resultFact?.factRef === draftFactRef(this.environment, session, current.actionDraft.draftId)
          && sha(canonicalBusinessJson(current.actionDraft.values)) === pending.requestSelectors.intendedValuesSha256);
      }
      const original = pending.step.endsWith('-draft') ? current : pending.requestSelectors.taskId ? { taskId: pending.requestSelectors.taskId, subject: { subjectType: 'LEAD', subjectRef: pending.requestSelectors.subjectRef, subjectRevision: pending.requestSelectors.subjectRevision }, preconditions: { taskETag: pending.requestSelectors.taskETag }, actionDraft: null } : current;
      check(original && (!type || pending.step.endsWith('-submit') || current?.taskType === type));
      result = selectors(owner, original, pending.step.endsWith('-draft') ? null : current, pending.step.endsWith('-draft') ? current?.actionDraft : null);
    }
    await this.journal.complete(pending.commandId, 200, receipt, result);
  }
  async stage(id: BusinessCaseId) {
    check(this.contactWait ? id === CONTACT_WAIT_CASE : BUSINESS_CASES.includes(id as typeof BUSINESS_CASES[number]));
    const predecessor = this.contactWait ? CONTACT_WAIT_PREDECESSOR : IDENTITY_PREDECESSOR;
    const at = new Date().toISOString(), reportPath = join(this.environment.runtime, `${this.contactWait ? 'task9-contact-wait' : 'task9-business'}-${this.runId}-${id}-${randomUUID()}.json`);
    let completing = false;
    try {
      await this.reconcilePending(); this.journal.requirePrevious(id); await this.environment.assertUnchanged();
      if (id === CONTACT_WAIT_CASE) {
        await this.verifyPredecessor();
        const intake = await this.workbench('intake'), supervisor = await this.workbench('supervisor'), contact = await this.workbench('contact');
        if (!this.journal.confirmed('capture-manual')) for (const session of [supervisor, contact]) {
          const envelope = await this.currentEnvelope(session); check(envelope.currentCard === null && envelope.nextSummaries.length === 0 && envelope.waitingCount === 0);
        }
        await this.capture(intake, 'capture-manual', 'LOCAL_SYNTHETIC', true, 'ASSIGN_LEAD', 'supervisor');
        await this.card(supervisor, 'assign', 'ASSIGN_LEAD', { ownerAppointmentId: this.environment.resources['appointment-contact'] }, 'CONTACT_LEAD', 'contact');
        await this.card(contact, 'contact', 'CONTACT_LEAD', { resultCode: 'NOT_CONNECTED', contactChannelCode: 'EMAIL', resultSummary: 'Task 9.6p synthetic contact not connected.' }, null, null);
        await this.requireWaitingUi(contact, await this.currentEnvelope(contact));
      } else if (id === BUSINESS_CASES[0]) {
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
        predecessorRunId: predecessor.runId, predecessorSha256: predecessor.journalSha256, apiIdentity: this.environment.apiIdentity,
        executedAt: at, caseIdentity: id, status: 'ACTIONS_VERIFIED', exitCode: null, reportPath, commands: this.journal.reportCommands(id), http,
        U01: 'NOT_EXECUTED', U02: 'NOT_EXECUTED', U03: 'NOT_EXECUTED' });
    } catch {
      if (!completing) {
        const guard = this.contactWait ? this.environment.assertUnchanged : protect;
        await guard(); writeFileSync(reportPath, JSON.stringify({ runId: this.runId, buildSha: BUSINESS_PIN.buildSha, predecessorRunId: predecessor.runId,
          predecessorSha256: predecessor.journalSha256, executedAt: at, caseIdentity: id, status: 'FAILED', exitCode: 1,
          reportPath, U01: 'NOT_EXECUTED', U02: 'NOT_EXECUTED', U03: 'NOT_EXECUTED' }), { flag: 'wx', mode: 0o600 }); await guard();
      }
      throw new Error(businessFailureCode(id));
    }
  }
}
