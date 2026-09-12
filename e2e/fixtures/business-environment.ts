import { existsSync, readFileSync } from 'node:fs';
import { basename, dirname, join } from 'node:path';
import { check, exact, invokeLocalRuntime, noLinks, runtime, sha, toolchain, uuid, validateAccounts, type Account } from './local-environment';
import { loadBusinessRestart } from './business-restart';
import { BUSINESS_CASES, BUSINESS_STEPS } from './business-journal';

export const BUSINESS_PIN = {
  origin: 'https://localhost:19444', issuer: 'https://localhost:19443/realms/local-r1',
  buildSha: '421ca57aed3d2f364fea2af64b12b5c9d6226c7d', releaseId: '6411135a52094b6ba16a80df80b025a1',
  jarSha256: 'd496603eaeda6e7893f002e4c1ade7c317369b743eec21fd39210cde43bec586',
  manifestHash: '051010dfc50c3259cb52ae5af09f5cb1013639e0a91fdba8773893ceba2d61eb',
  revision: 12, browserVersion: '153.0.8010.12', browserRevision: '1243',
} as const;
export const IDENTITY_PREDECESSOR = { runId: '74a496f6-494e-417d-9abd-69a85c94f165', journalSha256: '44c95f59853fa552d2d7ba933dcb80a4877464fe26dab7c34202d3e1fb0ad9a1' } as const;
export const CONTACT_WAIT_PREDECESSOR = Object.freeze({ runId: '9848f4ee-5612-49df-9e10-a8c40c09bd3d', journalSha256: '841a4d275bf97bdb832bbb138f70efbc310dbbe10f532d6c4dbb435380d52333' });
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
export function requireContactWaitAcceptance(): void {
  check(process.env.TASK9_LOCAL_ACCEPTANCE === 'APPROVED_SYNTHETIC_ONLY' && process.env.TASK9_BUSINESS_ACCEPTANCE === 'APPROVED_CONTACT_WAIT_CHAIN');
  const runId = process.env.TASK9_BUSINESS_RUN_ID;
  check(UUID.test(runId ?? '') && runId !== CONTACT_WAIT_PREDECESSOR.runId && runId !== IDENTITY_PREDECESSOR.runId);
  check(process.env.TASK9_BUSINESS_CONTINUE_RUN_ID === undefined || process.env.TASK9_BUSINESS_CONTINUE_RUN_ID === runId);
  for (const key of ['TASK9_BUSINESS_RESTART_SHA256','TASK9_BUSINESS_RECOVER_COMMAND_ID','TASK9_BUSINESS_RECOVER_JOURNAL_SHA256']) check(process.env[key] === undefined);
}
// Projection only: the loader authenticates the fixed raw journal and reports before calling this.
export function contactGrantResourceId(grant: { step: string; selectors: Record<string, unknown> }): string {
  const resourceId = grant.selectors.resourceId;
  check(grant.step === 'grant-contact-owner' && exact(grant.selectors, ['resourceId']) && typeof resourceId === 'string' && uuid.test(resourceId));
  return resourceId;
}
async function contactWaitPredecessor(folder: string, guard: () => Promise<unknown>) {
  await guard(); noLinks(folder);
  const path = join(folder, 'task9-business-operation.json');
  const bytes = (file: string) => { noLinks(file); return readFileSync(file); };
  const assertFiles = () => {
    check(!existsSync(path + '.pending') && !existsSync(path + '.completion.pending'));
    const raw = bytes(path); check(sha(raw) === CONTACT_WAIT_PREDECESSOR.journalSha256);
    const data = JSON.parse(raw.toString('utf8'));
    check(exact(data, ['identity','commands','stages']) && data.identity.runId === CONTACT_WAIT_PREDECESSOR.runId && data.identity.buildSha === BUSINESS_PIN.buildSha);
    check(data.identity.predecessorRunId === IDENTITY_PREDECESSOR.runId && data.identity.predecessorSha256 === IDENTITY_PREDECESSOR.journalSha256);
    check(data.commands.length === 15 && data.stages.length === 3 && data.commands.every((entry: any, index: number) => entry.step === BUSINESS_STEPS[index] && entry.status === 'CONFIRMED'));
    for (const [index, stage] of data.stages.entries()) {
      const prefix = `task9-business-${CONTACT_WAIT_PREDECESSOR.runId}-${BUSINESS_CASES[index]}-`;
      check(stage.caseIdentity === BUSINESS_CASES[index] && stage.status === 'PASSED_SUBSCENARIO' && stage.exitCode === 0 && dirname(stage.reportPath) === folder && basename(stage.reportPath).startsWith(prefix) && UUID.test(basename(stage.reportPath).slice(prefix.length, -5)) && stage.reportPath.endsWith('.json'));
      const report = bytes(stage.reportPath); check(sha(report) === stage.reportSha256);
      const evidence = JSON.parse(report.toString('utf8'));
      check(evidence.runId === CONTACT_WAIT_PREDECESSOR.runId && evidence.caseIdentity === stage.caseIdentity && evidence.status === 'ACTIONS_VERIFIED' && evidence.exitCode === null);
      check(evidence.buildSha === BUSINESS_PIN.buildSha && evidence.predecessorRunId === IDENTITY_PREDECESSOR.runId && evidence.predecessorSha256 === IDENTITY_PREDECESSOR.journalSha256);
    }
    return contactGrantResourceId(data.commands[7]);
  };
  const contactGrantId = assertFiles(); await guard(); check(assertFiles() === contactGrantId);
  return Object.freeze({ ...CONTACT_WAIT_PREDECESSOR, contactGrantId, assertUnchanged() { check(assertFiles() === contactGrantId); } });
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
  const contactWait = process.env.TASK9_BUSINESS_ACCEPTANCE === 'APPROVED_CONTACT_WAIT_CHAIN';
  if (contactWait) requireContactWaitAcceptance();
  else requireBusinessAcceptance(process.env.TASK9_LOCAL_ACCEPTANCE, process.env.TASK9_BUSINESS_ACCEPTANCE, process.env.TASK9_BUSINESS_RUN_ID, process.env.TASK9_BUSINESS_CONTINUE_RUN_ID);
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
  const waitingPredecessor = contactWait ? await contactWaitPredecessor(dependencies.runtime, () => dependencies.invokeLocalRuntime('protect')) : undefined;
  const restart = expectedRestartSha === undefined ? undefined : await loadBusinessRestart(dependencies.runtime, {
    runId: process.env.TASK9_BUSINESS_RUN_ID!, environmentDigest, buildSha: BUSINESS_PIN.buildSha,
    predecessorRunId: IDENTITY_PREDECESSOR.runId, predecessorSha256: IDENTITY_PREDECESSOR.journalSha256,
  }, snapshot.apiIdentity, BUSINESS_PIN, expectedRestartSha, async () => { await dependencies.invokeLocalRuntime('protect'); }, hasRecovery ? { commandId: recoveryId!, journalSha256: recoverySha! } : undefined);
  return {
    ...BUSINESS_PIN, environmentDigest, apiIdentity: snapshot.apiIdentity, runtime: dependencies.runtime, restart, waitingPredecessor,
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
      waitingPredecessor?.assertUnchanged();
    },
  };
}
export type BusinessEnvironment = Awaited<ReturnType<typeof loadBusinessEnvironment>>;
