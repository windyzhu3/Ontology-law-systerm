import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import { closeSync, fsyncSync, lstatSync, openSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';


export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
export const HASH = /^[0-9a-f]{64}$/;
const COMMIT = /^[0-9a-f]{40}$/;
const PROCESS_NAMES = ['api', 'worker', 'spa'] as const;
const ACCOUNT_NAMES = ['founder', 'sales', 'supervisor', 'sourceOwner'] as const;
const ROOT = resolve(__dirname, '../..');
const PYTHON = 'D:/soft/python3/python.exe';
export const BROWSER = { revision: '1243', version: '153.0.8010.12', playwright: '1.63.0' } as const;

export function boundary(value: unknown): asserts value {
  if (!value) throw new Error('R1_ISOLATED_BOUNDARY');
}

function exact(value: unknown, keys: readonly string[]): value is Record<string, any> {
  return !!value && typeof value === 'object' && Object.keys(value).sort().join() === [...keys].sort().join();
}

export function canonicalSha256(value: unknown): string {
  const canonical = (item: any): any => Array.isArray(item) ? item.map(canonical)
    : item && typeof item === 'object' ? Object.fromEntries(Object.keys(item).sort().map(key => [key, canonical(item[key])])) : item;
  return createHash('sha256').update(JSON.stringify(canonical(value))).digest('hex');
}

export function requireR1IsolatedAcceptance(environment: Record<string, string | undefined> = process.env): {
  run: string; operationId: string;
} {
  boundary(environment.R1_ISOLATED_ACCEPTANCE === 'APPROVED_SYNTHETIC_ONLY');
  const run = environment.R1_E2E_RUN, operationId = environment.R1_ISOLATED_OPERATION_ID;
  boundary(typeof run === 'string' && /^[a-z0-9][a-z0-9-]{0,31}$/.test(run));
  boundary(typeof operationId === 'string' && UUID.test(operationId));
  boundary(!environment.R1_ISOLATED_CONTINUE_OPERATION_ID || environment.R1_ISOLATED_CONTINUE_OPERATION_ID === operationId);
  for (const key of Object.keys(environment)) {
    const upper = key.toUpperCase();
    if (!environment[key]) continue;
    boundary(!upper.startsWith('TASK9_'));
    boundary(!['DEBUG', 'PWDEBUG', 'PW_TEST_DEBUG', 'PLAYWRIGHT_HTML_OPEN', 'PLAYWRIGHT_JSON_OUTPUT_NAME'].includes(upper));
  }
  return { run, operationId };
}

export interface AcceptanceEnvironment {
  profile: 'R1_ISOLATED_ACCEPTANCE_INPUT_V1'; run: string; operationId: string;
  origin: string; apiOrigin: string; issuer: string; sourceCommit: string;
  bootstrapSource: 'original' | 'continued';
  bootstrap: { tenantId: string; rootId: string; founderId: string; founderAppointmentId: string; originalGrantIds: string[] };
  usernames: Record<'founder'|'sales'|'supervisor'|'sourceOwner'|'revokedAppointment', string>;
  credentialReferences: Record<typeof ACCOUNT_NAMES[number], string>;
  artifacts: { jarSha256: string; spaSha256: string; schemaSha256: string };
  configurationSha256: string; sourceSha256: string;
  processes: Record<typeof PROCESS_NAMES[number], { pid: number; created: string; identitySha256: string }>;
  environmentDigest: string;
}

export function validateAcceptanceEnvironment(value: unknown, run: string, operationId: string): asserts value is AcceptanceEnvironment {
  boundary(exact(value, ['profile','run','operationId','origin','apiOrigin','issuer','sourceCommit','bootstrapSource','bootstrap','usernames','credentialReferences','artifacts','configurationSha256','sourceSha256','processes','environmentDigest']));
  boundary(value.profile === 'R1_ISOLATED_ACCEPTANCE_INPUT_V1' && value.run === run && value.operationId === operationId);
  boundary(value.origin === 'https://localhost:29444' && value.apiOrigin === 'https://localhost:29445' && value.issuer === 'https://localhost:29443/realms/r1-e2e');
  boundary(COMMIT.test(value.sourceCommit) && ['original','continued'].includes(value.bootstrapSource));
  boundary(exact(value.bootstrap, ['tenantId','rootId','founderId','founderAppointmentId','originalGrantIds']));
  const ids = [value.bootstrap.tenantId, value.bootstrap.rootId, value.bootstrap.founderId, value.bootstrap.founderAppointmentId, ...value.bootstrap.originalGrantIds];
  boundary(value.bootstrap.originalGrantIds.length === 4 && new Set(ids).size === 8 && ids.every(id => UUID.test(id)));
  boundary(exact(value.usernames, ['founder','sales','supervisor','sourceOwner','revokedAppointment']));
  const expectedNames = { founder: `r1-${run}-founder`, sales: `r1-${run}-sales`, supervisor: `r1-${run}-supervisor`, sourceOwner: `r1-${run}-source-owner`, revokedAppointment: `r1-${run}-revoked` };
  boundary(Object.entries(expectedNames).every(([key, expected]) => value.usernames[key] === expected));
  boundary(new Set(Object.values(value.usernames)).size === 5);
  boundary(exact(value.credentialReferences, ACCOUNT_NAMES));
  boundary(ACCOUNT_NAMES.every(account => value.credentialReferences[account] === `secrets/${account}-password.txt`));
  boundary(exact(value.artifacts, ['jarSha256','spaSha256','schemaSha256']) && Object.values(value.artifacts).every((hash: any) => HASH.test(hash)));
  boundary(HASH.test(value.configurationSha256) && HASH.test(value.sourceSha256));
  boundary(exact(value.processes, PROCESS_NAMES));
  for (const name of PROCESS_NAMES) {
    const process = value.processes[name];
    boundary(exact(process, ['pid','created','identitySha256']) && Number.isSafeInteger(process.pid) && process.pid > 0);
    boundary(typeof process.created === 'string' && Number.isFinite(Date.parse(process.created)) && HASH.test(process.identitySha256));
  }
  const copy = { ...value }; delete copy.environmentDigest;
  boundary(HASH.test(value.environmentDigest) && canonicalSha256(copy) === value.environmentDigest);
}

function noLinks(path: string): void {
  let current = resolve(path);
  for (;;) {
    boundary(!lstatSync(current).isSymbolicLink());
    const parent = dirname(current); if (parent === current) return; current = parent;
  }
}

function toolchain(): void {
  boundary(process.version === 'v24.20.0');
  const playwright = JSON.parse(readFileSync(join(ROOT, 'node_modules/@playwright/test/package.json'), 'utf8'));
  const browsers = JSON.parse(readFileSync(join(ROOT, 'node_modules/playwright-core/browsers.json'), 'utf8'));
  const chromium = browsers.browsers.find((entry: any) => entry.name === 'chromium');
  boundary(playwright.version === BROWSER.playwright && chromium?.revision === BROWSER.revision && chromium?.browserVersion === BROWSER.version);
}

async function bridge(run: string, operationId: string): Promise<unknown> {
  return new Promise((resolveValue, reject) => execFile(PYTHON, [
    '-B', join(ROOT, 'e2e/runtime/r1_acceptance.py'), '--root', ROOT,
    'environment', run, '--operation-id', operationId,
  ], { cwd: ROOT, encoding: 'utf8', windowsHide: true, timeout: 120_000, maxBuffer: 1024 * 1024 }, (error, stdout) => {
    if (error) { reject(new Error('R1_ISOLATED_BOUNDARY')); return; }
    try { resolveValue(JSON.parse(stdout)); } catch { reject(new Error('R1_ISOLATED_BOUNDARY')); }
  }));
}

async function completionBridge(environment: AcceptanceEnvironment, commandId: string, taskId: string, draftId: string, ownerId: string): Promise<unknown> {
  return new Promise((resolveValue, reject) => execFile(PYTHON, [
    '-B', join(ROOT, 'e2e/runtime/r1_acceptance.py'), '--root', ROOT,
    'completion', environment.run, '--operation-id', environment.operationId,
    '--command-id', commandId, '--task-id', taskId, '--draft-id', draftId,
    '--owner-appointment-id', ownerId,
  ], { cwd: ROOT, encoding: 'utf8', windowsHide: true, timeout: 120_000, maxBuffer: 1024 * 1024 }, (error, stdout) => {
    if (error) { reject(new Error('R1_ISOLATED_BOUNDARY')); return; }
    try { resolveValue(JSON.parse(stdout)); } catch { reject(new Error('R1_ISOLATED_BOUNDARY')); }
  }));
}

export async function closeR1GoldenCompletion(environment: AcceptanceEnvironment, commandId: string, taskId: string, draftId: string, ownerId: string) {
  boundary([commandId, taskId, draftId, ownerId].every(value => UUID.test(value)));
  const value: any = await completionBridge(environment, commandId, taskId, draftId, ownerId);
  boundary(value?.profile === 'R1_GOLDEN_COMPLETION_V1' && value.tenantId === environment.bootstrap.tenantId && value.commandId === commandId);
  const singles = ['contactResults','opportunities','tasks','drafts','receipts','audits'];
  boundary(singles.every(key => Array.isArray(value[key]) && value[key].length === 1));
  boundary(Array.isArray(value.events) && value.events.length === 2 && Array.isArray(value.outboxes) && value.outboxes.length === 2);
  return { commandId, counts: { contactResult: 1, opportunity: 1, event: 2, outbox: 2, receipt: 1, audit: 1 } };
}

export async function loadR1IsolatedEnvironment(invoke: (run: string, operationId: string) => Promise<unknown> = bridge) {
  const { run, operationId } = requireR1IsolatedAcceptance();
  toolchain();
  const value = await invoke(run, operationId); validateAcceptanceEnvironment(value, run, operationId);
  const runtime = join(ROOT, '.artifacts/r1-e2e', run); noLinks(ROOT); noLinks(runtime);
  const accounts: Record<string, { username: string; password: string }> = {};
  for (const account of ACCOUNT_NAMES) {
    const relative = value.credentialReferences[account];
    const path = join(runtime, relative); noLinks(path);
    const password = readFileSync(path, 'utf8').trim(); boundary(password.length > 0 && !/[\r\n]/.test(password));
    accounts[account] = { username: value.usernames[account], password };
  }
  return { ...value, runtime, accounts, verifyBrowser(version: string) { boundary(version === BROWSER.version); } };
}

export type LoadedR1Environment = Awaited<ReturnType<typeof loadR1IsolatedEnvironment>>;

export function writeR1ClosedReport(
  environment: LoadedR1Environment,
  result: { managementCommandCount: 15; goldenCommandId: string; resultFact: Record<string,unknown>; counts: Record<string,number> },
): string {
  const counts = { contactResult: 1, opportunity: 1, event: 2, outbox: 2, receipt: 1, audit: 1 };
  boundary(result.managementCommandCount === 15 && UUID.test(result.goldenCommandId));
  boundary(exact(result.resultFact, ['factType','factRef','revision']) || exact(result.resultFact, ['factType','factRef','digest']));
  boundary(result.resultFact.factType === 'LEAD_CONTACT_RESULT' && typeof result.resultFact.factRef === 'string');
  boundary(JSON.stringify(result.counts) === JSON.stringify(counts));
  const report = {
    profile: 'R1_ISOLATED_ACCEPTANCE_REPORT_V1', run: environment.run,
    operationId: environment.operationId, status: 'GOLDEN_VERIFIED',
    environmentDigest: environment.environmentDigest, managementCommandCount: 15,
    goldenCommandId: result.goldenCommandId, resultFact: result.resultFact, counts,
  };
  const path = join(environment.runtime, `acceptance-${environment.operationId}-report.json`);
  noLinks(environment.runtime);
  const descriptor = openSync(path, 'wx', 0o600);
  try { writeFileSync(descriptor, JSON.stringify(report)); fsyncSync(descriptor); } finally { closeSync(descriptor); }
  return path;
}
