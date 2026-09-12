import { expect, test, type Browser } from '@playwright/test';
import { existsSync, mkdtempSync, readFileSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { BUSINESS_PIN, IDENTITY_PREDECESSOR, IDENTITY_RESOURCE_STEPS, loadBusinessEnvironment } from '../fixtures/business-environment';
import { BusinessJournal, BUSINESS_CASES, BUSINESS_STEPS, type BusinessCommand, type BusinessEvidence, type BusinessRunIdentity } from '../fixtures/business-journal';
import { loadBusinessRestart } from '../fixtures/business-restart';
import { BusinessSetup } from '../fixtures/r1-business-setup';
import { sha } from '../fixtures/local-environment';

const runId = '9848f4ee-5612-49df-9e10-a8c40c09bd3d';
const oldIdentity: BusinessRunIdentity = { runId, environmentDigest: 'a'.repeat(64), buildSha: BUSINESS_PIN.buildSha, predecessorRunId: IDENTITY_PREDECESSOR.runId, predecessorSha256: IDENTITY_PREDECESSOR.journalSha256 };
const activeIdentity = { ...oldIdentity, environmentDigest: 'b'.repeat(64) };
const oldApi = 'c'.repeat(64), activeApi = 'd'.repeat(64);
const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const taskId = id(1), appointmentId = id(2), taskETag = '"task.' + 'e'.repeat(43) + '"';
function command(index: number): BusinessCommand {
  const step = BUSINESS_STEPS[index], noTask = [0, 7, 8].includes(index), draft = step.endsWith('-draft');
  const suffix: Record<string, string> = { complete: 'complete-lead-ingress', routing: 'record-routing-disposition', ack: 'acknowledge-source-intake-stop-request', assign: 'assign-lead', contact: 'record-contact-result', review: 'review-lead-validity' };
  return { step, commandId: id(100 + index), method: draft ? 'PUT' : 'POST',
    path: noTask ? index === 7 ? '/api/v1/admin/identity/authority-grants' : '/api/v1/leads' : `/api/v1/tasks/${taskId}/${draft ? 'draft' : 'commands/' + suffix[step.split('-')[0]]}`,
    bodySha256: 'f'.repeat(64), actorScopeKey: 'ask1.' + 'g'.repeat(43), requestSelectors: {
      actorAppointmentId: appointmentId, taskId: noTask ? null : taskId, subjectRef: noTask ? null : 'synthetic-lead-ref-0001', subjectRevision: noTask ? null : 0, taskETag: noTask ? null : taskETag,
      draftId: noTask || draft ? null : id(3), draftRevision: noTask || draft ? null : 0, draftDigest: noTask || draft ? null : 'h'.repeat(43), draftETag: noTask || draft ? null : '"draft.' + 'i'.repeat(43) + '"', intendedValuesSha256: noTask ? null : 'a'.repeat(64),
    } };
}
async function confirm(journal: BusinessJournal, index: number) {
  const value = command(index); await journal.begin(value);
  const types = ['LEAD','ACTION_DRAFT','LEAD','ACTION_DRAFT','DECISION_RECORD','ACTION_DRAFT','DECISION_RECORD','AUTHORITY_GRANT','LEAD','ACTION_DRAFT','LEAD_ASSIGNMENT','ACTION_DRAFT','LEAD_CONTACT_RESULT','ACTION_DRAFT','DECISION_RECORD'];
  const factType = types[index];
  await journal.complete(value.commandId, 200, { commandId: value.commandId, receiptId: id(200 + index), completedAt: '2026-09-12T00:00:00.000Z', outcome: 'SUCCEEDED', resultFact: { factType, factRef: 'synthetic-fact-ref-0001', ...(['DECISION_RECORD','LEAD_CONTACT_RESULT'].includes(factType) ? { digest: 'k'.repeat(43) } : { revision: 0 }) } }, index === 7 ? { resourceId: id(4) } : {
    taskId, subjectRef: 'synthetic-lead-ref-0001', subjectRevision: 0, ownerAppointmentId: appointmentId, taskETag,
    draftETag: null, draftId: null, draftRevision: null, draftDigest: null, draftValuesSha256: null,
    successorTaskId: null, successorTaskType: null, successorOwnerAppointmentId: null, successorSubjectRef: null, successorSubjectRevision: null, successorTaskETag: null,
  });
}
function evidence(folder: string, journal: BusinessJournal, index: number, identity = oldIdentity, apiIdentity = oldApi): BusinessEvidence {
  return { ...identity, apiIdentity, executedAt: '2026-09-12T00:00:00.000Z', caseIdentity: BUSINESS_CASES[index], status: 'ACTIONS_VERIFIED', exitCode: null,
    reportPath: join(folder, `task9-business-${runId}-${BUSINESS_CASES[index]}-${id(300 + index)}.json`), commands: journal.reportCommands(BUSINESS_CASES[index]), http: [], U01: 'NOT_EXECUTED', U02: 'NOT_EXECUTED', U03: 'NOT_EXECUTED' };
}
async function fixture() {
  const folder = mkdtempSync(join(tmpdir(), 'task96n-restart-')), path = join(folder, 'task9-business-operation.json');
  const journal = await BusinessJournal.open(path, oldIdentity, () => {});
  for (let index = 0; index < 9; index++) {
    await confirm(journal, index);
    if (index === 6 || index === 7) { const stage = index === 6 ? 0 : 1; await journal.finishStage(BUSINESS_CASES[stage], evidence(folder, journal, stage)); }
  }
  const checkpoint = readFileSync(path), checkpointPath = join(folder, 'task9-business-pre-restart.json');
  writeFileSync(checkpointPath, checkpoint, { flag: 'wx' });
  const proofPath = join(folder, 'task96n-idp-addition-proof.json');
  const proof = { profile: 'TASK9_USER_CONFIRMED_IDP_ADDITION_V1', approval: 'synthetic-only', baselineSha256: '1'.repeat(64), excludedUserId: id(700),
    currentIdentity: Object.fromEntries(Array.from({ length: 84 }, (_, index) => [`synthetic_table_${index}`, { count: 0, sha256: '2'.repeat(64) }])),
    originalRunId: runId, originalJournalSha256: sha(checkpoint), confirmedCommands: 9, stageCount: 2, originalIdentityTableCount: 84, originalIdentityAfterExactExclusion: true, releaseAndMaterialsUnchanged: true };
  writeFileSync(proofPath, JSON.stringify(proof), { flag: 'wx' });
  const record = { profile: 'TASK9_SAME_BUILD_RESTART_V1', runId, buildSha: BUSINESS_PIN.buildSha, releaseId: BUSINESS_PIN.releaseId, jarSha256: BUSINESS_PIN.jarSha256, manifestHash: BUSINESS_PIN.manifestHash, gateRevision: BUSINESS_PIN.revision,
    checkpointSha256: sha(checkpoint), originalEnvironmentDigest: oldIdentity.environmentDigest, originalApiIdentity: oldApi, activeEnvironmentDigest: activeIdentity.environmentDigest, activeApiIdentity: activeApi, idpAdditionProofSha256: sha(readFileSync(proofPath)), preservedCommandCount: 9, preservedStageCount: 2, continuationCase: 'T9-W01-assign-contact-review' };
  const credentialPath = join(folder, 'task9-business-restart.json'); writeFileSync(credentialPath, JSON.stringify(record), { flag: 'wx' });
  return { folder, path, checkpoint, checkpointPath, credentialPath, proofPath, record };
}

test('restart without credential rejects changed process environment while original environment opens', async () => {
  const f = await fixture();
  await expect(BusinessJournal.open(f.path, oldIdentity, () => {})).resolves.toBeInstanceOf(BusinessJournal);
  await expect(BusinessJournal.open(f.path, activeIdentity, () => {})).rejects.toThrow('T9_BOUNDARY');
  expect(readFileSync(f.path)).toEqual(f.checkpoint);
});

test('named verified restart preserves original journal and permits only the next assign draft', async () => {
  const f = await fixture();
  const restart = await load(f);
  const journal = await BusinessJournal.open(f.path, activeIdentity, () => {}, restart);
  expect(readFileSync(f.path)).toEqual(f.checkpoint);
  await journal.begin(command(9));
  const after = JSON.parse(readFileSync(f.path, 'utf8')), before = JSON.parse(f.checkpoint.toString());
  expect(after.identity).toEqual(before.identity); expect(after.commands.slice(0, 9)).toEqual(before.commands); expect(after.stages).toEqual(before.stages);
  expect(after.commands[9]).toMatchObject({ step: 'assign-draft', status: 'PENDING' });
});

type Fixture = Awaited<ReturnType<typeof fixture>>;
const load = (f: Fixture, identity = activeIdentity, api = activeApi, digest = sha(readFileSync(f.credentialPath))) => loadBusinessRestart(f.folder, identity, api, BUSINESS_PIN, digest, () => {});
function rewriteRecord(f: Fixture, patch: Record<string, unknown>) { writeFileSync(f.credentialPath, JSON.stringify({ ...f.record, ...patch })); }

for (const [name, patch] of Object.entries({
  profile: { profile: 'OTHER' }, run: { runId: id(999) }, build: { buildSha: '0'.repeat(40) }, release: { releaseId: '0'.repeat(32) }, jar: { jarSha256: '0'.repeat(64) }, manifest: { manifestHash: '0'.repeat(64) }, revision: { gateRevision: 13 },
  checkpointHash: { checkpointSha256: '0'.repeat(64) }, originalEnvironment: { originalEnvironmentDigest: '0'.repeat(64) }, activeEnvironment: { activeEnvironmentDigest: '0'.repeat(64) }, sameEnvironment: { activeEnvironmentDigest: oldIdentity.environmentDigest }, originalApi: { originalApiIdentity: '0'.repeat(64) }, activeApi: { activeApiIdentity: '0'.repeat(64) },
  commandCount: { preservedCommandCount: 8 }, stageCount: { preservedStageCount: 1 }, continuationCase: { continuationCase: BUSINESS_CASES[0] }, extraField: { token: 'synthetic-forbidden' }, proofHash: { idpAdditionProofSha256: '0'.repeat(64) },
})) test(`restart credential rejects ${name} mismatch without changing journal`, async () => {
  const f = await fixture(); rewriteRecord(f, patch);
  await expect(load(f)).rejects.toThrow(); expect(readFileSync(f.path)).toEqual(f.checkpoint);
});

test('restart requires explicit correct raw credential SHA and exact active identity', async () => {
  const f = await fixture();
  for (const digest of ['', 'invalid', '0'.repeat(64)]) await expect(load(f, activeIdentity, activeApi, digest)).rejects.toThrow();
  for (const patch of [{ runId: id(555) }, { predecessorRunId: id(556) }, { predecessorSha256: '0'.repeat(64) }, { buildSha: '0'.repeat(40) }]) await expect(load(f, { ...activeIdentity, ...patch })).rejects.toThrow();
  await expect(load(f, activeIdentity, oldApi)).rejects.toThrow();
  await expect(loadBusinessRestart(f.folder, activeIdentity, activeApi, { ...BUSINESS_PIN, revision: 13 } as any, sha(readFileSync(f.credentialPath)), () => {})).rejects.toThrow();
});

for (const target of ['checkpoint', 'proof', 'old-report', 'prefix', 'identity', 'pending-entry', 'pending-file', 'completion-file']) test(`restart rejects changed ${target} on new process open`, async () => {
  const f = await fixture(), saved = JSON.parse(f.checkpoint.toString());
  if (target === 'checkpoint') writeFileSync(f.checkpointPath, Buffer.concat([f.checkpoint, Buffer.from(' ')]));
  if (target === 'proof') writeFileSync(f.proofPath, '{}');
  if (target === 'old-report') writeFileSync(saved.stages[0].reportPath, '{}');
  if (target === 'prefix') { saved.commands[8].bodySha256 = '0'.repeat(64); writeFileSync(f.path, JSON.stringify(saved)); }
  if (target === 'identity') { saved.identity.environmentDigest = activeIdentity.environmentDigest; writeFileSync(f.path, JSON.stringify(saved)); }
  if (target === 'pending-entry') { const original = await BusinessJournal.open(f.path, oldIdentity, () => {}); await original.begin(command(9)); }
  if (target === 'pending-file') writeFileSync(f.path + '.pending', '{}');
  if (target === 'completion-file') writeFileSync(f.path + '.completion.pending', '{}');
  const before = readFileSync(f.path); await expect(load(f)).rejects.toThrow(); expect(readFileSync(f.path)).toEqual(before);
});

for (const target of ['credential', 'checkpoint', 'proof', 'old-report']) test(`restart rechecks ${target} before later writes`, async () => {
  const f = await fixture(), restart = await load(f), journal = await BusinessJournal.open(f.path, activeIdentity, () => {}, restart);
  const expectedSha = sha(readFileSync(f.credentialPath));
  const path = target === 'credential' ? f.credentialPath : target === 'checkpoint' ? f.checkpointPath : target === 'proof' ? f.proofPath : JSON.parse(f.checkpoint.toString()).stages[0].reportPath;
  writeFileSync(path, Buffer.concat([readFileSync(path), Buffer.from(' ')]));
  await expect(journal.begin(command(9))).rejects.toThrow(); expect(readFileSync(f.path)).toEqual(f.checkpoint);
  await expect(load(f, activeIdentity, activeApi, expectedSha)).rejects.toThrow();
});

test('restart cannot replay successful stages or skip assign draft', async () => {
  const f = await fixture(), restart = await load(f), journal = await BusinessJournal.open(f.path, activeIdentity, () => {}, restart);
  expect(() => journal.requirePrevious(BUSINESS_CASES[0])).toThrow(); expect(() => journal.requirePrevious(BUSINESS_CASES[1])).toThrow();
  journal.requirePrevious(BUSINESS_CASES[2]); await expect(journal.begin(command(10))).rejects.toThrow(); expect(readFileSync(f.path)).toEqual(f.checkpoint);
});

test('restart third evidence uses current environment and API while preserving both historical reports', async () => {
  const f = await fixture(), old = JSON.parse(f.checkpoint.toString());
  const oldReports = old.stages.map((stage: any) => readFileSync(stage.reportPath));
  let journal = await BusinessJournal.open(f.path, activeIdentity, () => {}, await load(f));
  for (let i = 9; i < 15; i++) await confirm(journal, i);
  for (const [identity, api] of [[oldIdentity, activeApi], [activeIdentity, oldApi]] as const) {
    await expect(journal.finishStage(BUSINESS_CASES[2], evidence(f.folder, journal, 2, identity, api))).rejects.toThrow();
    journal = await BusinessJournal.open(f.path, activeIdentity, () => {}, await load(f));
  }
  const report = evidence(f.folder, journal, 2, activeIdentity, activeApi); await journal.finishStage(BUSINESS_CASES[2], report);
  expect(JSON.parse(readFileSync(report.reportPath, 'utf8'))).toMatchObject({ environmentDigest: activeIdentity.environmentDigest, apiIdentity: activeApi, commands: [{ step: 'capture-manual' }, { step: 'assign-draft' }, { step: 'assign-submit' }, { step: 'contact-draft' }, { step: 'contact-submit' }, { step: 'review-draft' }, { step: 'review-submit' }] });
  const after = JSON.parse(readFileSync(f.path, 'utf8')); expect(after.commands).toHaveLength(15); expect(after.stages).toHaveLength(3); expect(after.identity).toEqual(old.identity); expect(after.commands.slice(0, 9)).toEqual(old.commands); expect(after.stages.slice(0, 2)).toEqual(old.stages);
  old.stages.forEach((stage: any, index: number) => expect(readFileSync(stage.reportPath)).toEqual(oldReports[index]));
  await expect(BusinessJournal.open(f.path, activeIdentity, () => {}, await load(f))).resolves.toBeInstanceOf(BusinessJournal);
});

function syntheticEnvironment(folder: string) {
  const accounts = Object.fromEntries(['intake','supervisor','contact','delegate'].map((alias, index) => [alias, { username: `task9-local-${alias}`, password: 'synthetic-only', email: `${alias}@example.invalid`, firstName: alias, lastName: 'Synthetic', providerUserId: id(400 + index) }]));
  const saved = Object.fromEntries(Object.entries(accounts).map(([alias, { password: _, ...value }]) => [alias, value]));
  const snapshot = { ...BUSINESS_PIN, apiIdentity: activeApi, processIdentity: 'e'.repeat(64), releaseIdentity: 'f'.repeat(64) };
  const loaded = { ...snapshot, predecessor: { ...IDENTITY_PREDECESSOR, commandCount: 16, stageCount: 7, pendingCount: 0 },
    original: { founder: { username: 'synthetic-founder', password: 'synthetic-only' }, unmapped: { username: 'synthetic-unmapped', password: 'synthetic-only' } }, credentials: { runId: 'synthetic', accounts },
    operation: { runId: 'synthetic', stage: 'COMPLETE', accounts: saved, temporaryClientDeleted: true, temporaryCredentialRejected: true, temporaryTokenRejected: true, originalUsersUnchanged: true, realmPublicKeysUnchanged: true, directoryReadOnlyUnchanged: true, temporaryClientUserAndRolesAbsent: true, temporaryRecoveryContainerRemoved: true },
    bootstrap: { tenantId: id(500), rootId: id(501), founderId: id(502), appointmentId: id(503) }, resources: Object.fromEntries(IDENTITY_RESOURCE_STEPS.map((step, index) => [step, id(600 + index)])) };
  let protectedCalls = 0;
  return { loaded, snapshot, get protectedCalls() { return protectedCalls; }, dependencies: {
    runtime: folder, toolchain: () => ({ browserVersion: BUSINESS_PIN.browserVersion, browserRevision: BUSINESS_PIN.browserRevision }),
    async invokeLocalRuntime(mode: 'protect' | 'snapshot' | 'load' | 'business') { if (mode === 'protect') { protectedCalls++; return {}; } if (mode === 'snapshot') return structuredClone(snapshot); if (mode === 'business') return structuredClone(loaded); throw new Error('unexpected synthetic boundary mode'); },
  } };
}
async function withApproval(f: Fixture, action: () => Promise<void>, expectedSha: string | undefined = sha(readFileSync(f.credentialPath))) {
  const patch = { TASK9_LOCAL_ACCEPTANCE: 'APPROVED_SYNTHETIC_ONLY', TASK9_BUSINESS_ACCEPTANCE: 'APPROVED_SIX_CARD_CHAIN', TASK9_BUSINESS_RUN_ID: runId, TASK9_BUSINESS_CONTINUE_RUN_ID: runId, TASK9_BUSINESS_RESTART_SHA256: expectedSha };
  const saved = Object.fromEntries(Object.keys(patch).map(key => [key, process.env[key]]));
  try { for (const [key, value] of Object.entries(patch)) { if (value === undefined) delete process.env[key]; else process.env[key] = value; } await action(); }
  finally { for (const [key, value] of Object.entries(saved)) { if (value === undefined) delete process.env[key]; else process.env[key] = value; } }
}

test('environment consumer computes actual digest injects bridge into setup and rechecks immutable files', async () => {
  const f = await fixture(), source = syntheticEnvironment(f.folder);
  const currentDigest = sha(JSON.stringify(source.snapshot)); rewriteRecord(f, { activeEnvironmentDigest: currentDigest });
  await withApproval(f, async () => {
    // Dependency seam replaces only external process reads, never journal validation or persistence.
    const environment = await loadBusinessEnvironment(source.dependencies);
    expect(environment.environmentDigest).toBe(currentDigest); expect(environment.environmentDigest).not.toBe(oldIdentity.environmentDigest);
    expect(source.protectedCalls).toBeGreaterThan(0);
    const setup = await BusinessSetup.create({ version: () => BUSINESS_PIN.browserVersion } as Browser, () => Promise.resolve(environment));
    await setup.journal.begin(command(9)); expect(JSON.parse(readFileSync(f.path, 'utf8')).commands[9].step).toBe('assign-draft');
    writeFileSync(f.proofPath, '{}'); await expect(environment.assertUnchanged()).rejects.toThrow();
  });
});

test('environment without explicit credential retains strict original behavior and invalid explicit SHA cannot fall back', async () => {
  const f = await fixture(), source = syntheticEnvironment(f.folder);
  await withApproval(f, async () => {
    delete process.env.TASK9_BUSINESS_RESTART_SHA256;
    const environment = await loadBusinessEnvironment(source.dependencies);
    expect(environment.restart).toBeUndefined(); await environment.assertUnchanged();
    await expect(BusinessSetup.create({ version: () => BUSINESS_PIN.browserVersion } as Browser, () => Promise.resolve(environment))).rejects.toThrow();
    for (const digest of ['', 'invalid', '0'.repeat(64)]) {
      process.env.TASK9_BUSINESS_RESTART_SHA256 = digest;
      await expect(loadBusinessEnvironment(source.dependencies)).rejects.toThrow();
    }
    expect(readFileSync(f.path)).toEqual(f.checkpoint);
  });
});

test('environment restart still requires explicit original run continuation', async () => {
  const f = await fixture(), source = syntheticEnvironment(f.folder); rewriteRecord(f, { activeEnvironmentDigest: sha(JSON.stringify(source.snapshot)) });
  await withApproval(f, async () => {
    delete process.env.TASK9_BUSINESS_CONTINUE_RUN_ID;
    await expect(loadBusinessEnvironment(source.dependencies)).rejects.toThrow();
    process.env.TASK9_BUSINESS_CONTINUE_RUN_ID = id(999);
    await expect(loadBusinessEnvironment(source.dependencies)).rejects.toThrow();
  });
});

for (const field of ['apiIdentity', 'processIdentity', 'releaseIdentity', 'jarSha256', 'resources'] as const) test(`environment restart retains current ${field} drift fence`, async () => {
  const f = await fixture(), source = syntheticEnvironment(f.folder); rewriteRecord(f, { activeEnvironmentDigest: sha(JSON.stringify(source.snapshot)) });
  await withApproval(f, async () => {
    const environment = await loadBusinessEnvironment(source.dependencies);
    if (field === 'resources') source.loaded.resources['principal-intake'] = id(999);
    else (source.loaded as Record<string, unknown>)[field] = '0'.repeat(64);
    await expect(environment.assertUnchanged()).rejects.toThrow();
    await expect(BusinessSetup.create({ version: () => BUSINESS_PIN.browserVersion } as Browser, () => Promise.resolve(environment))).rejects.toThrow();
    expect(readFileSync(f.path)).toEqual(f.checkpoint);
  });
});

for (const target of ['credential', 'checkpoint'] as const) test(`environment assertUnchanged rechecks ${target} bytes`, async () => {
  const f = await fixture(), source = syntheticEnvironment(f.folder); rewriteRecord(f, { activeEnvironmentDigest: sha(JSON.stringify(source.snapshot)) });
  await withApproval(f, async () => {
    const environment = await loadBusinessEnvironment(source.dependencies), path = target === 'credential' ? f.credentialPath : f.checkpointPath;
    writeFileSync(path, Buffer.concat([readFileSync(path), Buffer.from(' ')]));
    await expect(environment.assertUnchanged()).rejects.toThrow();
  });
});

test('restart context cannot be forged mutated or reused for another path or active identity', async () => {
  const f = await fixture(), identity = { ...activeIdentity }, restart = await load(f, identity);
  identity.environmentDigest = '0'.repeat(64);
  expect(() => { (restart.activeIdentity as any).environmentDigest = '0'.repeat(64); }).toThrow();
  expect(() => { (restart.checkpoint.commands[0] as any).bodySha256 = '0'.repeat(64); }).toThrow();
  await expect(BusinessJournal.open(f.path, activeIdentity, () => {}, { ...restart })).rejects.toThrow();
  await expect(BusinessJournal.open(f.path, identity, () => {}, restart)).rejects.toThrow();
  await expect(BusinessJournal.open(join(f.folder, 'other.json'), activeIdentity, () => {}, restart)).rejects.toThrow();
  await expect(BusinessJournal.open(f.path, activeIdentity, () => {}, restart)).resolves.toBeInstanceOf(BusinessJournal);
});

test('restart rejects linked runtime and checkpoint pending fences', async () => {
  const f = await fixture(), link = join(mkdtempSync(join(tmpdir(), 'task96n-link-')), 'runtime'); symlinkSync(f.folder, link, 'junction');
  await expect(loadBusinessRestart(link, activeIdentity, activeApi, BUSINESS_PIN, sha(readFileSync(f.credentialPath)), () => {})).rejects.toThrow();
  for (const suffix of ['.pending', '.completion.pending']) {
    const next = await fixture(); writeFileSync(next.checkpointPath + suffix, '{}'); await expect(load(next)).rejects.toThrow();
  }
});

test('restart rejects a changed proof binding even when its new hash is explicitly supplied', async () => {
  const f = await fixture(), proof = JSON.parse(readFileSync(f.proofPath, 'utf8')); proof.originalRunId = id(999);
  writeFileSync(f.proofPath, JSON.stringify(proof)); rewriteRecord(f, { idpAdditionProofSha256: sha(readFileSync(f.proofPath)) });
  await expect(load(f)).rejects.toThrow();
});

test('restart retains exclusive mutation and original journal byte compare before publication', async () => {
  const f = await fixture(), restart = await load(f);
  const first = await BusinessJournal.open(f.path, activeIdentity, () => {}, restart), stale = await BusinessJournal.open(f.path, activeIdentity, () => {}, restart);
  await confirm(first, 9); const committed = readFileSync(f.path);
  await expect(stale.begin(command(9))).rejects.toThrow(); expect(readFileSync(f.path)).toEqual(committed); expect(existsSync(f.path + '.pending')).toBe(true);
  await expect(load(f)).rejects.toThrow();
});

for (const completion of [false, true]) test(`restart late credential change preserves ${completion ? 'completion' : 'command'} pending fence`, async () => {
  const f = await fixture(), restart = await load(f); let armed = false, calls = 0;
  const journal = await BusinessJournal.open(f.path, activeIdentity, () => {
    if (armed && ++calls === 2) writeFileSync(f.credentialPath, Buffer.concat([readFileSync(f.credentialPath), Buffer.from(' ')]));
  }, restart);
  if (completion) for (let index = 9; index < 15; index++) await confirm(journal, index);
  const before = readFileSync(f.path); armed = true;
  await expect(completion ? journal.finishStage(BUSINESS_CASES[2], evidence(f.folder, journal, 2, activeIdentity, activeApi)) : journal.begin(command(9))).rejects.toThrow();
  expect(readFileSync(f.path)).toEqual(before); expect(existsSync(f.path + (completion ? '.completion.pending' : '.pending'))).toBe(true);
  await expect(load(f)).rejects.toThrow();
});
