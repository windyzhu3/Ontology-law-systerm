import { check, exact, invokeLocalRuntime, runtime, toolchain, validateAccounts, type Account } from './local-environment';
import { loadBusinessRestart } from './business-restart';

export const BUSINESS_PIN = {
  origin: 'https://localhost:19444', issuer: 'https://localhost:19443/realms/local-r1',
  buildSha: '421ca57aed3d2f364fea2af64b12b5c9d6226c7d', releaseId: '6411135a52094b6ba16a80df80b025a1',
  jarSha256: 'd496603eaeda6e7893f002e4c1ade7c317369b743eec21fd39210cde43bec586',
  manifestHash: '051010dfc50c3259cb52ae5af09f5cb1013639e0a91fdba8773893ceba2d61eb',
  revision: 12, browserVersion: '153.0.8010.12', browserRevision: '1243',
} as const;
export const IDENTITY_PREDECESSOR = { runId: '74a496f6-494e-417d-9abd-69a85c94f165', journalSha256: '44c95f59853fa552d2d7ba933dcb80a4877464fe26dab7c34202d3e1fb0ad9a1' } as const;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[4][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const HASH = /^[0-9a-f]{64}$/;
export const IDENTITY_RESOURCE_STEPS = [
  'principal-intake', 'principal-supervisor', 'principal-contact', 'principal-delegate', 'organization',
  'appointment-intake', 'appointment-supervisor', 'appointment-contact', 'appointment-delegate',
  'grant-intake-0', 'grant-intake-1', 'grant-intake-2', 'grant-intake-3',
  'grant-supervisor-0', 'grant-supervisor-1', 'grant-supervisor-2',
] as const;

export function requireBusinessAcceptance(local: string | undefined, business: string | undefined, runId: string | undefined, continueRunId: string | undefined): void {
  check(local === 'APPROVED_SYNTHETIC_ONLY' && business === 'APPROVED_SIX_CARD_CHAIN' && UUID.test(runId ?? ''));
  check(continueRunId === undefined || continueRunId === runId);
}

export function validateBusinessEnvironment(value: unknown): void {
  validateBusinessArtifact(value); const data = value as Record<string, any>;
  const predecessor = data.predecessor;
  check(predecessor && exact(predecessor, ['runId', 'journalSha256', 'commandCount', 'stageCount', 'pendingCount']));
  check(predecessor.runId === IDENTITY_PREDECESSOR.runId && predecessor.journalSha256 === IDENTITY_PREDECESSOR.journalSha256);
  check(predecessor.commandCount === 16 && predecessor.stageCount === 7 && predecessor.pendingCount === 0);
}
export function validateBusinessArtifact(value: unknown): void {
  check(value && typeof value === 'object'); const data = value as Record<string, any>;
  for (const [key, expected] of Object.entries(BUSINESS_PIN)) check(data[key] === expected);
}

export async function loadBusinessEnvironment(dependencies = { invokeLocalRuntime, runtime, toolchain }) {
  requireBusinessAcceptance(process.env.TASK9_LOCAL_ACCEPTANCE, process.env.TASK9_BUSINESS_ACCEPTANCE, process.env.TASK9_BUSINESS_RUN_ID, process.env.TASK9_BUSINESS_CONTINUE_RUN_ID);
  check(!process.env.DEBUG && !process.env.PWDEBUG && !process.env.PW_TEST_DEBUG);
  const expectedRestartSha = process.env.TASK9_BUSINESS_RESTART_SHA256;
  const recoveryId = process.env.TASK9_BUSINESS_RECOVER_COMMAND_ID, recoverySha = process.env.TASK9_BUSINESS_RECOVER_JOURNAL_SHA256;
  const hasRecovery = recoveryId !== undefined || recoverySha !== undefined;
  if (expectedRestartSha !== undefined || hasRecovery) check(HASH.test(expectedRestartSha ?? '') && process.env.TASK9_BUSINESS_CONTINUE_RUN_ID === process.env.TASK9_BUSINESS_RUN_ID);
  if (hasRecovery) check(UUID.test(recoveryId ?? '') && HASH.test(recoverySha ?? ''));
  const tools = dependencies.toolchain();
  const snapshot = await dependencies.invokeLocalRuntime('snapshot');
  validateBusinessArtifact({ ...snapshot, ...tools });
  const loaded = await dependencies.invokeLocalRuntime('business');
  validateBusinessEnvironment({ ...loaded, ...tools });
  for (const key of ['apiIdentity', 'processIdentity', 'releaseIdentity']) check(HASH.test(snapshot[key]) && snapshot[key] === loaded[key]);
  validateAccounts(loaded.original, loaded.credentials, loaded.operation);
  check(loaded.bootstrap && exact(loaded.bootstrap, ['tenantId', 'rootId', 'founderId', 'appointmentId']));
  check(loaded.resources && exact(loaded.resources, [...IDENTITY_RESOURCE_STEPS]));
  for (const id of Object.values(loaded.resources)) check(typeof id === 'string' && /^[0-9a-f-]{36}$/.test(id));
  const current: Record<string, unknown> = { ...BUSINESS_PIN, ...tools, apiIdentity: snapshot.apiIdentity, processIdentity: snapshot.processIdentity, releaseIdentity: snapshot.releaseIdentity };
  const environmentDigest = (await import('node:crypto')).createHash('sha256').update(JSON.stringify(current)).digest('hex');
  const restart = expectedRestartSha === undefined ? undefined : await loadBusinessRestart(dependencies.runtime, {
    runId: process.env.TASK9_BUSINESS_RUN_ID!, environmentDigest, buildSha: BUSINESS_PIN.buildSha,
    predecessorRunId: IDENTITY_PREDECESSOR.runId, predecessorSha256: IDENTITY_PREDECESSOR.journalSha256,
  }, snapshot.apiIdentity, BUSINESS_PIN, expectedRestartSha, async () => { await dependencies.invokeLocalRuntime('protect'); }, hasRecovery ? { commandId: recoveryId!, journalSha256: recoverySha! } : undefined);
  return {
    ...BUSINESS_PIN, environmentDigest, apiIdentity: snapshot.apiIdentity, runtime: dependencies.runtime, restart,
    bootstrap: loaded.bootstrap as { tenantId: string; rootId: string; founderId: string; appointmentId: string },
    accounts: { ...loaded.original, ...loaded.credentials.accounts } as Record<'founder' | 'unmapped' | 'intake' | 'supervisor' | 'contact' | 'delegate', Account>,
    resources: loaded.resources as Record<typeof IDENTITY_RESOURCE_STEPS[number], string>,
    predecessor: structuredClone(loaded.predecessor) as { runId: string; journalSha256: string; commandCount: 16; stageCount: 7; pendingCount: 0 },
    verifyBrowser(actual: string) { check(actual === BUSINESS_PIN.browserVersion); },
    async assertUnchanged() {
      const now = await dependencies.invokeLocalRuntime('business'); validateBusinessEnvironment({ ...now, ...tools });
      for (const key of ['apiIdentity', 'processIdentity', 'releaseIdentity']) check(now[key] === current[key]);
      check(JSON.stringify(now.resources) === JSON.stringify(loaded.resources));
      restart?.assertUnchanged();
    },
  };
}
export type BusinessEnvironment = Awaited<ReturnType<typeof loadBusinessEnvironment>>;
