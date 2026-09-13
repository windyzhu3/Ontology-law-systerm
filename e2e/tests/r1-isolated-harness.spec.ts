import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { expect, test } from '@playwright/test';

type ArmedWrite = {
  step: string; commandId: string; method: string; path: string;
  body: Record<string, unknown>; bodySha256: string; actorScopeKey: string;
  actorAppointmentId: string; onBehalfAppointmentId: null;
};
let environmentModule: any, setupModule: any;
try { environmentModule = require('../fixtures/r1-isolated-environment'); } catch { environmentModule = {}; }
try { setupModule = require('../fixtures/r1-isolated-setup'); } catch { setupModule = {}; }
const missing = () => { throw new Error('r1 isolated implementation is missing'); };
const requireR1IsolatedAcceptance = environmentModule.requireR1IsolatedAcceptance ?? missing;
const validateAcceptanceEnvironment = environmentModule.validateAcceptanceEnvironment ?? missing;
const AcceptanceDispatchGate = setupModule.AcceptanceDispatchGate ?? class { constructor() { missing(); } };
const AcceptanceJournal = setupModule.AcceptanceJournal ?? { open: missing };
const R1GoldenOrchestrator = setupModule.R1GoldenOrchestrator ?? class { constructor() { missing(); } };
const WRITE_SEQUENCE: { step: string; method: 'POST'|'PUT'; path: string|RegExp }[] = setupModule.WRITE_SEQUENCE ?? [];
const canonicalSha256 = setupModule.canonicalSha256 ?? missing;
const { R1IsolatedBrowserAdapter } = require('../fixtures/r1-isolated-browser');


const ROOT = resolve(__dirname, '../..');
const NODE = 'C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/node-v24.20.0-win-x64/node.exe';
const RUN = 'unit-r1';
const OPERATION = '00000000-0000-4000-8000-000000000901';
const ACTOR = 'ask1.' + 'A'.repeat(43);
const APPOINTMENT = '00000000-0000-4000-8000-000000000902';


test('offline config is the default and the live golden project needs exact explicit selection', () => {
  const cli = join(ROOT, 'node_modules/@playwright/test/cli.js');
  const config = join(ROOT, 'e2e/r1-isolated.config.ts');
  const clean = { ...process.env };
  for (const key of ['R1_ISOLATED_ACCEPTANCE', 'R1_E2E_RUN', 'R1_ISOLATED_OPERATION_ID']) delete clean[key];
  const defaultList = execFileSync(NODE, [cli, 'test', '--config', config, '--list'], {
    cwd: ROOT, env: clean, encoding: 'utf8', windowsHide: true,
  });
  expect(defaultList).toContain('offline-r1-isolated');
  expect(defaultList).not.toContain('approved-r1-isolated');
  const approvedList = execFileSync(NODE, [cli, 'test', '--config', config, '--project', 'approved-r1-isolated', '--list'], {
    cwd: ROOT, env: clean, encoding: 'utf8', windowsHide: true,
  });
  expect(approvedList).toContain('approved-r1-isolated');
  expect(approvedList).toContain('r1-golden-path.spec.ts');
  expect(approvedList).not.toContain('r1-isolated-harness.spec.ts');
});

test('explicit approval rejects missing run operation debug flags and Task9 controls before loading', () => {
  const base = {
    R1_ISOLATED_ACCEPTANCE: 'APPROVED_SYNTHETIC_ONLY',
    R1_E2E_RUN: RUN,
    R1_ISOLATED_OPERATION_ID: OPERATION,
  };
  expect(() => requireR1IsolatedAcceptance(base)).not.toThrow();
  for (const mutation of [
    { R1_ISOLATED_ACCEPTANCE: undefined },
    { R1_ISOLATED_ACCEPTANCE: 'yes' },
    { R1_E2E_RUN: 'old/run' },
    { R1_ISOLATED_OPERATION_ID: 'not-a-uuid' },
    { DEBUG: '1' },
    { PWDEBUG: '1' },
    { TASK9_LOCAL_ACCEPTANCE: 'APPROVED_SYNTHETIC_ONLY' },
    { TASK9_BUSINESS_RUN_ID: OPERATION },
    { PLAYWRIGHT_HTML_OPEN: 'always' },
  ]) expect(() => requireR1IsolatedAcceptance({ ...base, ...mutation })).toThrow('R1_ISOLATED_BOUNDARY');
  expect(() => requireR1IsolatedAcceptance({ ...base, R1_ISOLATED_CONTINUE_OPERATION_ID: randomUUID() })).toThrow('R1_ISOLATED_BOUNDARY');
});

test('environment projection rejects wrong run process inventory credentials and digest', () => {
  const valid = environment();
  expect(() => validateAcceptanceEnvironment(valid, RUN, OPERATION)).not.toThrow();
  for (const mutate of [
    (value: any) => value.run = 'other-run',
    (value: any) => delete value.processes.worker,
    (value: any) => value.credentialReferences.sales = '../sales-password.txt',
    (value: any) => value.environmentDigest = '0'.repeat(64),
    (value: any) => value.usernames.revokedAppointment = value.usernames.sales,
  ]) {
    const changed = structuredClone(valid); mutate(changed);
    expect(() => validateAcceptanceEnvironment(changed, RUN, OPERATION)).toThrow('R1_ISOLATED_BOUNDARY');
  }
});

test('fixed write policy preserves the historic 17 then appends ingress and contact writes', () => {
  expect(WRITE_SEQUENCE.map(entry => entry.step)).toEqual([
    'principal-sales', 'principal-supervisor', 'principal-sourceOwner',
    'organization-OWNED_ROOT', 'organization-EMPTY_ROOT',
    'appointment-sales', 'appointment-supervisor', 'appointment-sourceOwner',
    'grant-sourceOwner-LEAD_CAPTURE', 'grant-sourceOwner-LEAD_INGRESS_RESOLVE',
    'grant-sourceOwner-LEAD_INGRESS_COMPLETE', 'grant-sourceOwner-SOURCE_INTAKE_REQUEST_ACK',
    'grant-supervisor-LEAD_ASSIGN', 'grant-supervisor-LEAD_ROUTING_DECIDE',
    'grant-supervisor-LEAD_VALIDITY_REVIEW', 'grant-sales-SALES_CONTACT_OWNER',
    'capture-R1_AUTO', 'ingress-draft', 'ingress-submit', 'contact-draft', 'contact-submit',
  ]);
  expect(WRITE_SEQUENCE.slice(0, 15).every(entry => entry.method === 'POST' && typeof entry.path === 'string' && entry.path.startsWith('/api/v1/admin/identity/'))).toBe(true);
  expect(WRITE_SEQUENCE[15]).toEqual({ step: 'grant-sales-SALES_CONTACT_OWNER', method: 'POST', path: '/api/v1/admin/identity/authority-grants' });
  expect(WRITE_SEQUENCE[16]).toEqual({ step: 'capture-R1_AUTO', method: 'POST', path: '/api/v1/leads' });
  expect(WRITE_SEQUENCE[17].path).toEqual(/^\/api\/v1\/tasks\/[0-9a-f-]{36}\/draft$/);
  expect(WRITE_SEQUENCE[18].path).toEqual(/^\/api\/v1\/tasks\/[0-9a-f-]{36}\/commands\/complete-lead-ingress$/);
  expect(WRITE_SEQUENCE[19].path).toEqual(/^\/api\/v1\/tasks\/[0-9a-f-]{36}\/draft$/);
  expect(WRITE_SEQUENCE[20].path).toEqual(/^\/api\/v1\/tasks\/[0-9a-f-]{36}\/commands\/record-contact-result$/);
});

test('both first draft creates require HTTP 201 and reject update status 200', async () => {
  for (const index of [17, 19]) {
    const folder = mkdtempSync(join(tmpdir(), 'r1-first-draft-status-'));
    try {
      const journal = await AcceptanceJournal.open(join(folder, 'journal.json'), identity());
      await confirmPrefix(journal, index);
      const task = randomUUID();
      const command = armed(WRITE_SEQUENCE[index].step, `/api/v1/tasks/${task}/draft`, { marker: WRITE_SEQUENCE[index].step });
      command.method = 'PUT';
      const gate = new AcceptanceDispatchGate(journal); gate.arm(command); await gate.dispatch(actual(command), async () => {});
      const response = (status: number) => ({
        status, headers: { location: `/api/v1/commands/${command.commandId}/receipt`, 'cache-control': 'no-store' },
        body: { commandId: command.commandId, receiptId: randomUUID(), outcome: 'SUCCEEDED', resultFact: { factType: 'ACTION_DRAFT', factRef: randomUUID(), revision: 0 } },
      });
      await expect(journal.confirm(command.commandId, response(200), { resourceId: randomUUID() })).rejects.toThrow('R1_ISOLATED_BOUNDARY');
      await expect(journal.confirm(command.commandId, response(201), { resourceId: randomUUID() })).resolves.toBeUndefined();
    } finally { rmSync(folder, { recursive: true, force: true }); }
  }
});

test('browser prepares the approved ingress candidate on the sourceOwner card', async () => {
  const filled: Record<string,string> = {}, selected: Record<string,string> = {};
  const ingressTask = '00000000-0000-4000-8000-000000000041';
  const session: any = { self: { actorScopeKey: ACTOR }, appointmentId: APPOINTMENT, page: {
    locator(selector: string) { return { count: async () => 1, fill: async (value: string) => { filled[selector] = value; }, selectOption: async (value: string) => { selected[selector] = value; } }; },
    getByRole() { return { click: async () => {} }; },
  } };
  const adapter: any = Object.create(R1IsolatedBrowserAdapter.prototype);
  adapter.workbench = async (alias: string) => { expect(alias).toBe('sourceOwner'); return session; };
  adapter.ingressCard = {
    taskId: ingressTask, taskType: 'COMPLETE_LEAD_INGRESS', actionDraft: null,
    commandForm: { actionCode: 'COMPLETE_LEAD_INGRESS', schemaVersion: 1, values: {}, fields: [
      { name: 'phone', label: '电话', control: 'TEL', required: false, readOnly: false, options: [] },
      { name: 'email', label: '邮箱', control: 'EMAIL', required: false, readOnly: false, options: [] },
      { name: 'sourceCode', label: '来源确认', control: 'SELECT', required: true, readOnly: false, options: [{ value: 'OWNER_CONFIRMED', label: '负责人确认', disabled: false }] },
      { name: 'sourceSummary', label: '来源摘要', control: 'TEXTAREA', required: true, readOnly: false, options: [] },
    ] },
  };
  const intent = await adapter.prepareWrite(WRITE_SEQUENCE[17], { ingressTask });
  expect(intent).toMatchObject({ method: 'PUT', path: `/api/v1/tasks/${ingressTask}/draft`, actorAppointmentId: APPOINTMENT, body: {
    actionCode: 'COMPLETE_LEAD_INGRESS', schemaVersion: 1,
    values: { phone: '+12025550100', sourceCode: 'OWNER_CONFIRMED', sourceSummary: 'R1 isolated synthetic ingress completion.' },
  } });
  expect(filled).toMatchObject({ '#candidate-phone': '+12025550100', '#chat-candidate': 'R1 isolated synthetic ingress completion.' });
  expect(selected['#candidate-sourceCode']).toBe('OWNER_CONFIRMED');
});

test('browser preparation binds the added sales authority to OWNED_ROOT rather than ROOT', async () => {
  const selected: Record<string,string> = {}, filled: Record<string,string> = {};
  const session: any = {
    self: { actorScopeKey: ACTOR }, appointmentId: APPOINTMENT,
    context: { request: { async get() { return { status: () => 200, headers: () => ({ 'cache-control': 'no-store' }), json: async () => ({ items: [], nextCursor: null }) }; } } },
    page: {
      getByRole() { return { click: async () => {} }; },
      getByLabel(label: string) { return { selectOption: async (value: string) => { selected[label] = value; }, fill: async (value: string) => { filled[label] = value; } }; },
    },
  };
  const adapter: any = Object.create(R1IsolatedBrowserAdapter.prototype);
  adapter.environment = { origin: 'https://localhost:29444', bootstrap: { rootId: '00000000-0000-4000-8000-000000000002' } };
  adapter.adminPage = async () => session;
  const resources = {
    'appointment-sales': '00000000-0000-4000-8000-000000000021',
    'organization-OWNED_ROOT': '00000000-0000-4000-8000-000000000022',
  };
  const intent = await adapter.prepareWrite(WRITE_SEQUENCE[15], resources);
  expect(intent.body).toMatchObject({
    appointmentId: resources['appointment-sales'], authorityCode: 'SALES_CONTACT_OWNER',
    scopeOrganizationId: resources['organization-OWNED_ROOT'], validUntil: null,
  });
  expect(selected).toMatchObject({
    '授权任职': resources['appointment-sales'], '组织范围': resources['organization-OWNED_ROOT'], '权限': 'SALES_CONTACT_OWNER',
  });
  expect(filled['生效时间']).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/);
});

test('journal saves original key digest and Actor before dispatch and confirms only its exact receipt', async () => {
  const folder = mkdtempSync(join(tmpdir(), 'r1-acceptance-journal-'));
  try {
    const path = join(folder, 'journal.json');
    const journal = await AcceptanceJournal.open(path, identity());
    const command = write('principal-sales', '/api/v1/admin/identity/principals', { providerUserSelector: 'opaque', displayName: 'Synthetic Sales' });
    await journal.begin(command);
    const stored = JSON.parse(readFileSync(path, 'utf8'));
    expect(stored.commands[0]).toMatchObject({ commandId: command.commandId, bodySha256: command.bodySha256, actorScopeKey: ACTOR, actorAppointmentId: APPOINTMENT, onBehalfAppointmentId: null, status: 'PENDING' });
    expect(stored.commands[0]).not.toHaveProperty('body');
    const fact = { factType: 'IDENTITY_PRINCIPAL', factRef: 'opaque-fact', revision: 0 };
    await expect(journal.confirm(command.commandId, {
      status: 201,
      headers: { location: `/api/v1/commands/${randomUUID()}/receipt`, 'cache-control': 'no-store' },
      body: { commandId: command.commandId, receiptId: randomUUID(), outcome: 'SUCCEEDED', resultFact: fact },
    }, { resourceId: randomUUID() })).rejects.toThrow('R1_ISOLATED_BOUNDARY');
    expect(journal.pending()?.commandId).toBe(command.commandId);
    const receiptId = randomUUID();
    await journal.confirm(command.commandId, {
      status: 201,
      headers: { location: `/api/v1/commands/${command.commandId}/receipt`, 'cache-control': 'no-store', etag: `"identity.${'A'.repeat(43)}"` },
      body: { commandId: command.commandId, receiptId, outcome: 'SUCCEEDED', resultFact: fact },
    }, { resourceId: randomUUID() });
    expect(journal.pending()).toBeUndefined();
    expect(journal.confirmed('principal-sales')).toMatchObject({ receiptId, resultFact: fact, status: 'CONFIRMED' });
  } finally { rmSync(folder, { recursive: true, force: true }); }
});

test('unknown dispatch retains the original pending row and blocks every later write', async () => {
  const folder = mkdtempSync(join(tmpdir(), 'r1-acceptance-unknown-'));
  try {
    const journal = await AcceptanceJournal.open(join(folder, 'journal.json'), identity());
    const gate = new AcceptanceDispatchGate(journal);
    const first = armed('principal-sales', '/api/v1/admin/identity/principals', { providerUserSelector: 'opaque', displayName: 'Synthetic Sales' });
    gate.arm(first);
    let dispatched = false;
    await gate.dispatch(actual(first), async () => { dispatched = true; });
    expect(dispatched).toBe(true);
    expect(journal.pending()?.commandId).toBe(first.commandId);
    const second = armed('principal-supervisor', '/api/v1/admin/identity/principals', { providerUserSelector: 'other', displayName: 'Synthetic Supervisor' });
    gate.arm(second);
    await expect(gate.dispatch(actual(second), async () => {})).rejects.toThrow('R1_ISOLATED_BOUNDARY');
    expect(journal.pending()?.commandId).toBe(first.commandId);
    expect(JSON.parse(readFileSync(join(folder, 'journal.json'), 'utf8')).commands).toHaveLength(1);
  } finally { rmSync(folder, { recursive: true, force: true }); }
});

test('durable management stage prevents confirmed management commands from being repeated', async () => {
  const folder = mkdtempSync(join(tmpdir(), 'r1-acceptance-stage-'));
  try {
    const path = join(folder, 'journal.json');
    const journal = await AcceptanceJournal.open(path, identity());
    for (const policy of WRITE_SEQUENCE.slice(0, 15)) {
      const command = write(policy.step, String(policy.path), { marker: policy.step });
      await journal.begin(command);
      await journal.confirm(command.commandId, {
        status: 201,
        headers: { location: `/api/v1/commands/${command.commandId}/receipt`, 'cache-control': 'no-store', etag: `"identity.${'A'.repeat(43)}"` },
        body: { commandId: command.commandId, receiptId: randomUUID(), outcome: 'SUCCEEDED', resultFact: { factType: 'IDENTITY', factRef: randomUUID(), revision: 0 } },
      }, { resourceId: randomUUID() });
    }
    await journal.completeStage('MANAGEMENT_COMPLETED');
    const reopened = await AcceptanceJournal.open(path, identity());
    expect(reopened.hasStage('MANAGEMENT_COMPLETED')).toBe(true);
    await expect(reopened.begin(write('principal-sales', '/api/v1/admin/identity/principals', { marker: 'repeat' }))).rejects.toThrow('R1_ISOLATED_BOUNDARY');
  } finally { rmSync(folder, { recursive: true, force: true }); }
});

test('historic 17-command capture checkpoint appends only four ingress and contact writes in the same operation', async () => {
  const folder = mkdtempSync(join(tmpdir(), 'r1-acceptance-continuation-'));
  try {
    const path = join(folder, 'journal.json');
    const journal = await AcceptanceJournal.open(path, identity());
    await confirmPrefix(journal, 15); await journal.completeStage('MANAGEMENT_COMPLETED');
    await confirmPrefix(journal, 16); await journal.completeStage('SALES_AUTHORITY_COMPLETED');
    await journal.completeStage('IDENTITIES_VERIFIED');
    await confirmPrefix(journal, 17); await journal.completeStage('CAPTURE_COMPLETED');
    const historicCommands = JSON.parse(readFileSync(path, 'utf8')).commands;
    const seen: string[] = [], receipts = new Map<string, any>();
    await new R1GoldenOrchestrator(await AcceptanceJournal.open(path, identity()), goldenAdapter(seen, receipts)).run();
    expect(seen).toEqual(['ingress-draft', 'ingress-submit', 'contact-draft', 'contact-submit']);
    const stored = JSON.parse(readFileSync(path, 'utf8'));
    expect(stored.commands.slice(0, 17)).toEqual(historicCommands);
    expect(stored.stages).toEqual(['MANAGEMENT_COMPLETED', 'SALES_AUTHORITY_COMPLETED', 'IDENTITIES_VERIFIED', 'CAPTURE_COMPLETED', 'INGRESS_DRAFT_RELOADED', 'INGRESS_COMPLETED', 'DRAFT_RELOADED', 'GOLDEN_COMPLETED']);
  } finally { rmSync(folder, { recursive: true, force: true }); }
});

test('confirmed contact draft checkpoint reopens before reload and never reissues that draft', async () => {
  const folder = mkdtempSync(join(tmpdir(), 'r1-contact-draft-checkpoint-'));
  try {
    const path = join(folder, 'journal.json');
    const journal = await AcceptanceJournal.open(path, identity());
    await confirmPrefix(journal, 15); await journal.completeStage('MANAGEMENT_COMPLETED');
    await confirmPrefix(journal, 16); await journal.completeStage('SALES_AUTHORITY_COMPLETED');
    await journal.completeStage('IDENTITIES_VERIFIED');
    await confirmPrefix(journal, 17); await journal.completeStage('CAPTURE_COMPLETED');
    await confirmPrefix(journal, 18); await journal.completeStage('INGRESS_DRAFT_RELOADED');
    await confirmPrefix(journal, 19); await journal.completeStage('INGRESS_COMPLETED');
    await confirmPrefix(journal, 20);
    const checkpoint = JSON.parse(readFileSync(path, 'utf8'));
    expect(checkpoint.commands).toHaveLength(20);
    expect(checkpoint.commands[19]).toMatchObject({ step: 'contact-draft', status: 'CONFIRMED', httpStatus: 201 });
    expect(checkpoint.stages.at(-1)).toBe('INGRESS_COMPLETED');

    const seen: string[] = [], receipts = new Map<string, any>(), checkpoints: Array<{ name: string; count: number }> = [];
    const ingress = checkpoint.commands[18];
    receipts.set(ingress.commandId, { commandId: ingress.commandId, receiptId: ingress.receiptId, outcome: 'SUCCEEDED', resultFact: ingress.resultFact });
    await new R1GoldenOrchestrator(await AcceptanceJournal.open(path, identity()), goldenAdapter(seen, receipts, checkpoints)).run();
    expect(seen).toEqual(['contact-submit']);
    expect(checkpoints.map(entry => entry.name)).toEqual(['ingress', 'contact', 'reload', 'closure']);
    const completed = JSON.parse(readFileSync(path, 'utf8'));
    expect(completed.commands.slice(0, 20)).toEqual(checkpoint.commands);
    expect(completed.commands).toHaveLength(21);
  } finally { rmSync(folder, { recursive: true, force: true }); }
});

test('added sales authority pending blocks continuation without replaying the historic 15', async () => {
  const folder = mkdtempSync(join(tmpdir(), 'r1-acceptance-sales-pending-'));
  try {
    const path = join(folder, 'journal.json');
    const journal = await AcceptanceJournal.open(path, identity());
    await confirmPrefix(journal, 15);
    await journal.completeStage('MANAGEMENT_COMPLETED');
    const pending = armed('grant-sales-SALES_CONTACT_OWNER', '/api/v1/admin/identity/authority-grants', { marker: 'sales-authority' });
    const gate = new AcceptanceDispatchGate(journal); gate.arm(pending); await gate.dispatch(actual(pending), async () => {});
    const seen: string[] = [];
    await expect(new R1GoldenOrchestrator(await AcceptanceJournal.open(path, identity()), goldenAdapter(seen, new Map())).run()).rejects.toThrow('R1_ISOLATED_BOUNDARY');
    expect(seen).toEqual([]);
    expect(JSON.parse(readFileSync(path, 'utf8')).commands).toHaveLength(16);
  } finally { rmSync(folder, { recursive: true, force: true }); }
});

test('journal rejects a historic capture at index 15 and malformed stage ordering', async () => {
  const folder = mkdtempSync(join(tmpdir(), 'r1-acceptance-malformed-'));
  try {
    const path = join(folder, 'journal.json');
    const journal = await AcceptanceJournal.open(path, identity());
    await confirmPrefix(journal, 15); await journal.completeStage('MANAGEMENT_COMPLETED');
    const original = JSON.parse(readFileSync(path, 'utf8'));
    const oldCapture = armed('capture-R1_AUTO', '/api/v1/leads', { marker: 'old-capture' });
    const oldCaptureJournal = structuredClone(original); oldCaptureJournal.commands.push({ ...oldCapture, status: 'PENDING' }); writeFileSync(path, JSON.stringify(oldCaptureJournal));
    await expect(AcceptanceJournal.open(path, identity())).rejects.toThrow('R1_ISOLATED_BOUNDARY');
    const malformedStages = structuredClone(original); malformedStages.stages = ['MANAGEMENT_COMPLETED', 'IDENTITIES_VERIFIED']; writeFileSync(path, JSON.stringify(malformedStages));
    await expect(AcceptanceJournal.open(path, identity())).rejects.toThrow('R1_ISOLATED_BOUNDARY');
    writeFileSync(path, JSON.stringify(original));
    await expect(AcceptanceJournal.open(path, { ...identity(), operationId: randomUUID() })).rejects.toThrow('R1_ISOLATED_BOUNDARY');
    await expect(AcceptanceJournal.open(path, { ...identity(), environmentDigest: 'c'.repeat(64) })).rejects.toThrow('R1_ISOLATED_BOUNDARY');
  } finally { rmSync(folder, { recursive: true, force: true }); }
});

test('browser identity verification requires exactly the OWNED_ROOT sales authority', async () => {
  const root = '00000000-0000-4000-8000-000000000002';
  const resources: Record<string,string> = {
    'principal-sales': '00000000-0000-4000-8000-000000000031',
    'principal-supervisor': '00000000-0000-4000-8000-000000000032',
    'principal-sourceOwner': '00000000-0000-4000-8000-000000000033',
    'organization-OWNED_ROOT': '00000000-0000-4000-8000-000000000034',
    'organization-EMPTY_ROOT': '00000000-0000-4000-8000-000000000035',
    'appointment-sales': '00000000-0000-4000-8000-000000000036',
    'appointment-supervisor': '00000000-0000-4000-8000-000000000037',
    'appointment-sourceOwner': '00000000-0000-4000-8000-000000000038',
  };
  const grants = [
    ...['LEAD_CAPTURE','LEAD_INGRESS_RESOLVE','LEAD_INGRESS_COMPLETE','SOURCE_INTAKE_REQUEST_ACK'].map((authority, index) => ({ alias: 'sourceOwner', authority, id: `00000000-0000-4000-8010-${String(index + 1).padStart(12, '0')}`, scope: root })),
    ...['LEAD_ASSIGN','LEAD_ROUTING_DECIDE','LEAD_VALIDITY_REVIEW'].map((authority, index) => ({ alias: 'supervisor', authority, id: `00000000-0000-4000-8020-${String(index + 1).padStart(12, '0')}`, scope: root })),
    { alias: 'sales', authority: 'SALES_CONTACT_OWNER', id: '00000000-0000-4000-8030-000000000001', scope: resources['organization-OWNED_ROOT'] },
  ];
  for (const grant of grants) resources[`grant-${grant.alias}-${grant.authority}`] = grant.id;
  const rows: Record<string,any[]> = {
    principals: [
      { id: resources['principal-sales'], displayName: 'R1 Synthetic Sales', state: 'ACTIVE' },
      { id: resources['principal-supervisor'], displayName: 'R1 Synthetic Supervisor', state: 'ACTIVE' },
      { id: resources['principal-sourceOwner'], displayName: 'R1 Synthetic Source Owner', state: 'ACTIVE' },
    ],
    organizations: [
      { id: resources['organization-OWNED_ROOT'], code: 'OWNED_ROOT', parentOrganizationId: root, state: 'ACTIVE' },
      { id: resources['organization-EMPTY_ROOT'], code: 'EMPTY_ROOT', parentOrganizationId: root, state: 'ACTIVE' },
    ],
    appointments: [
      { id: resources['appointment-sales'], principal: { id: resources['principal-sales'] }, organization: { id: resources['organization-OWNED_ROOT'] }, roleCode: 'CONTACT_OPERATOR', state: 'ACTIVE' },
      { id: resources['appointment-supervisor'], principal: { id: resources['principal-supervisor'] }, organization: { id: root }, roleCode: 'ROUTING_SUPERVISOR', state: 'ACTIVE' },
      { id: resources['appointment-sourceOwner'], principal: { id: resources['principal-sourceOwner'] }, organization: { id: root }, roleCode: 'INTAKE_OPERATOR', state: 'ACTIVE' },
    ],
    'authority-grants': grants.map(grant => ({ id: grant.id, appointment: { id: resources[`appointment-${grant.alias}`] }, authorityCode: grant.authority, scopeOrganization: { id: grant.scope }, state: 'ACTIVE' })),
  };
  const adapter: any = Object.create(R1IsolatedBrowserAdapter.prototype);
  adapter.environment = { bootstrap: { rootId: root } };
  adapter.administrator = async () => ({});
  adapter.rows = async (_session: any, path: string) => rows[path.split('/').at(-1)!];
  adapter.workbench = async (alias: string) => ({ self: { selectedAppointmentId: resources[`appointment-${alias}`], selectedOnBehalfAppointmentId: null } });
  await expect(adapter.verifyIdentities(resources)).resolves.toBeUndefined();
  const salesGrant = rows['authority-grants'].at(-1); salesGrant.scopeOrganization.id = root;
  await expect(adapter.verifyIdentities(resources)).rejects.toThrow('R1_ISOLATED_BOUNDARY');
  salesGrant.scopeOrganization.id = resources['organization-OWNED_ROOT'];
  rows['authority-grants'].push({ ...rows['authority-grants'].at(-1), id: randomUUID() });
  await expect(adapter.verifyIdentities(resources)).rejects.toThrow('R1_ISOLATED_BOUNDARY');
});

test('golden orchestrator runs fixed management before identities capture reloaded draft and one completion', async () => {
  const folder = mkdtempSync(join(tmpdir(), 'r1-acceptance-orchestrator-'));
  try {
    const journal = await AcceptanceJournal.open(join(folder, 'journal.json'), identity());
    const seen: string[] = [], checkpoints: Array<{ name: string; count: number }> = [];
    const receipts = new Map<string, any>();
    const adapter = goldenAdapter(seen, receipts, checkpoints);
    const orchestrator = new R1GoldenOrchestrator(journal, adapter);
    const report = await orchestrator.run();
    expect(seen).toEqual(WRITE_SEQUENCE.map(entry => entry.step));
    expect(checkpoints.map(entry => entry.name)).toEqual(['identities','ingress','ingress-reload','contact','reload','closure']);
    expect(journal.hasStage('MANAGEMENT_COMPLETED')).toBe(true);
    expect(journal.hasStage('SALES_AUTHORITY_COMPLETED')).toBe(true);
    expect(journal.hasStage('IDENTITIES_VERIFIED')).toBe(true);
    expect(journal.hasStage('CAPTURE_COMPLETED')).toBe(true);
    expect(journal.hasStage('INGRESS_DRAFT_RELOADED')).toBe(true);
    expect(journal.hasStage('INGRESS_COMPLETED')).toBe(true);
    expect(journal.hasStage('DRAFT_RELOADED')).toBe(true);
    expect(journal.hasStage('GOLDEN_COMPLETED')).toBe(true);
    expect(report).toMatchObject({ flowProfile: 'CAPTURE_INGRESS_AUTOASSIGN_CONTACT_V1', managementCommandCount: 16, counts: { contactResult: 1, opportunity: 1, event: 2, outbox: 2, receipt: 1, audit: 1 } });
  } finally { rmSync(folder, { recursive: true, force: true }); }
});


function identity() {
  return { run: RUN, operationId: OPERATION, environmentDigest: 'a'.repeat(64), sourceCommit: 'b'.repeat(40) };
}

function write(step: string, path: string, body: Record<string, unknown>) {
  return {
    step, commandId: randomUUID(), method: 'POST', path,
    bodySha256: canonicalSha256(body), actorScopeKey: ACTOR,
    actorAppointmentId: APPOINTMENT, onBehalfAppointmentId: null,
  };
}

function armed(step: string, path: string, body: Record<string, unknown>): ArmedWrite {
  const value = write(step, path, body);
  return { ...value, body };
}

function actual(command: ArmedWrite) {
  return {
    commandId: command.commandId, method: command.method, path: command.path,
    bodyBytes: Buffer.from(JSON.stringify(command.body)), actorScopeKey: command.actorScopeKey,
    actorAppointmentId: command.actorAppointmentId, onBehalfAppointmentId: null,
  };
}

async function confirmPrefix(journal: any, length: number) {
  const start = journal.resources ? Object.keys(journal.resources()).length : 0;
  for (const policy of WRITE_SEQUENCE.slice(start, length)) {
    const task = randomUUID();
    const path = typeof policy.path === 'string' ? policy.path : policy.step.endsWith('-draft') ? `/api/v1/tasks/${task}/draft`
      : policy.step === 'ingress-submit' ? `/api/v1/tasks/${task}/commands/complete-lead-ingress`
      : `/api/v1/tasks/${task}/commands/record-contact-result`;
    const command = write(policy.step, path, { marker: policy.step }); command.method = policy.method;
    const leadFactRef = 'lead-' + 'L'.repeat(43);
    const resultFact = policy.step === 'capture-R1_AUTO' ? { factType: 'LEAD', factRef: leadFactRef, revision: 0 }
      : policy.step === 'ingress-submit' ? { factType: 'LEAD', factRef: leadFactRef, revision: 1 }
      : policy.step === 'contact-submit' ? { factType: 'LEAD_CONTACT_RESULT', factRef: randomUUID(), digest: 'A'.repeat(43) }
      : { factType: 'IDENTITY', factRef: randomUUID(), revision: 0 };
    await journal.begin(command);
    await journal.confirm(command.commandId, {
      status: policy.method === 'PUT' ? 201 : ['ingress-submit','contact-submit'].includes(policy.step) ? 200 : 201,
      headers: { location: `/api/v1/commands/${command.commandId}/receipt`, 'cache-control': 'no-store' },
      body: { commandId: command.commandId, receiptId: randomUUID(), outcome: 'SUCCEEDED', resultFact },
    }, { resourceId: randomUUID() });
  }
}

function goldenAdapter(seen: string[], receipts: Map<string, any>, checkpoints: Array<{ name: string; count: number }> = []) {
  const leadFactRef = 'lead-' + 'L'.repeat(43);
  return {
    async prepareWrite(policy: any, resources: Record<string,string>) {
      if (policy.step.startsWith('ingress-')) expect(resources.ingressTask).toMatch(/^[0-9a-f-]{36}$/);
      if (policy.step.startsWith('contact-')) expect(resources.contactTask).toMatch(/^[0-9a-f-]{36}$/);
      const task = policy.step.startsWith('ingress-') ? resources.ingressTask : resources.contactTask;
      const path = policy.step.endsWith('-draft') ? `/api/v1/tasks/${task}/draft`
        : policy.step === 'ingress-submit' ? `/api/v1/tasks/${task}/commands/complete-lead-ingress`
        : policy.step === 'contact-submit' ? `/api/v1/tasks/${task}/commands/record-contact-result` : String(policy.path);
      const command = armed(policy.step, path, { marker: policy.step });
      command.method = policy.method; return command;
    },
    async executeWrite(command: ArmedWrite, gate: any) {
      seen.push(command.step); await gate.dispatch(actual(command), async () => {});
      const resultFact = command.step === 'capture-R1_AUTO' ? { factType: 'LEAD', factRef: leadFactRef, revision: 0 }
        : command.step === 'ingress-submit' ? { factType: 'LEAD', factRef: leadFactRef, revision: 1 }
        : command.step === 'contact-submit'
        ? { factType: 'LEAD_CONTACT_RESULT', factRef: randomUUID(), digest: 'A'.repeat(43) }
        : { factType: 'R1_FIXTURE', factRef: randomUUID(), revision: 0 };
      const response = { status: command.method === 'PUT' ? 201 : ['ingress-submit','contact-submit'].includes(command.step) ? 200 : 201,
        headers: { location: `/api/v1/commands/${command.commandId}/receipt`, 'cache-control': 'no-store' },
        body: { commandId: command.commandId, receiptId: randomUUID(), outcome: 'SUCCEEDED', resultFact } };
      receipts.set(command.commandId, response.body);
      return { response, resourceId: command.step === 'contact-submit' ? resultFact.factRef : randomUUID() };
    },
    async verifyIdentities(resources: Record<string,string>) { checkpoints.push({ name: 'identities', count: Object.keys(resources).length }); },
    async locateIngressTask(resources: Record<string,string>) { checkpoints.push({ name: 'ingress', count: Object.keys(resources).length }); return randomUUID(); },
    async reloadIngressDraft(resources: Record<string,string>) { checkpoints.push({ name: 'ingress-reload', count: Object.keys(resources).length }); },
    async locateContactTask(resources: Record<string,string>) { checkpoints.push({ name: 'contact', count: Object.keys(resources).length }); return randomUUID(); },
    async reloadDraft(resources: Record<string,string>) { checkpoints.push({ name: 'reload', count: Object.keys(resources).length }); },
    async retrieveReceipt(_alias: string, commandId: string) { return receipts.get(commandId); },
    async closeCompletion(commandId: string, resultFactDigest: string, resources: Record<string,string>) {
      expect(resultFactDigest).toBe('A'.repeat(43)); checkpoints.push({ name: 'closure', count: Object.keys(resources).length });
      return { commandId, counts: { contactResult: 1, opportunity: 1, event: 2, outbox: 2, receipt: 1, audit: 1 } };
    },
  };
}

function environment() {
  const value: any = {
    profile: 'R1_ISOLATED_ACCEPTANCE_INPUT_V1', run: RUN, operationId: OPERATION,
    origin: 'https://localhost:29444', apiOrigin: 'https://localhost:29445', issuer: 'https://localhost:29443/realms/r1-e2e',
    sourceCommit: 'a'.repeat(40), bootstrapSource: 'original',
    bootstrap: { tenantId: '00000000-0000-4000-8000-000000000001', rootId: '00000000-0000-4000-8000-000000000002', founderId: '00000000-0000-4000-8000-000000000003', founderAppointmentId: '00000000-0000-4000-8000-000000000004', originalGrantIds: [1,2,3,4].map(index => `00000000-0000-4000-8010-${String(index).padStart(12, '0')}`) },
    usernames: { founder: `r1-${RUN}-founder`, sales: `r1-${RUN}-sales`, supervisor: `r1-${RUN}-supervisor`, sourceOwner: `r1-${RUN}-source-owner`, revokedAppointment: `r1-${RUN}-revoked` },
    credentialReferences: { founder: 'secrets/founder-password.txt', sales: 'secrets/sales-password.txt', supervisor: 'secrets/supervisor-password.txt', sourceOwner: 'secrets/sourceOwner-password.txt' },
    artifacts: { jarSha256: '1'.repeat(64), spaSha256: '2'.repeat(64), schemaSha256: '3'.repeat(64) },
    configurationSha256: '4'.repeat(64), sourceSha256: '5'.repeat(64),
    processes: Object.fromEntries(['api','worker','spa'].map((name, index) => [name, { pid: index + 1, created: '2026-09-13T00:00:01Z', identitySha256: String(index + 6).repeat(64) }])),
  };
  value.environmentDigest = canonicalSha256(value);
  return value;
}
