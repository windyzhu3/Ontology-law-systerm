import { existsSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { BUSINESS_PIN, IDENTITY_PREDECESSOR, validateBusinessArtifact } from './business-environment';
import { BusinessJournal, type BusinessRunIdentity } from './business-journal';
import { check, exact, noLinks, protect, sha } from './local-environment';

const RUN = '9848f4ee-5612-49df-9e10-a8c40c09bd3d';
const HASH = /^[0-9a-f]{64}$/;
const verified = new WeakSet<object>();
type Checkpoint = { identity: BusinessRunIdentity; commands: readonly unknown[]; stages: readonly unknown[] };
type ReadonlyCheckpoint = { readonly identity: Readonly<BusinessRunIdentity>; readonly commands: readonly unknown[]; readonly stages: readonly unknown[] };
export interface BusinessRestart {
  readonly checkpoint: ReadonlyCheckpoint;
  readonly originalIdentity: Readonly<BusinessRunIdentity>;
  readonly activeIdentity: Readonly<BusinessRunIdentity>;
  readonly originalApiIdentity: string;
  readonly activeApiIdentity: string;
  assertUnchanged(): void;
  assertJournal(path: string, identity: BusinessRunIdentity, data: Checkpoint): void;
}
export function requireVerifiedBusinessRestart(value: BusinessRestart): void { check(verified.has(value)); }
function freeze<T>(value: T): T {
  if (value && typeof value === 'object') { for (const child of Object.values(value)) freeze(child); Object.freeze(value); }
  return value;
}

// This credential is a controller-pinned integrity bridge for one approved run,
// never product authorization or an importer for arbitrary journal history.
export async function loadBusinessRestart(runtime: string, activeIdentity: BusinessRunIdentity, activeApiIdentity: string,
  artifact: typeof BUSINESS_PIN, expectedSha: string, guard: () => void | Promise<void> = protect): Promise<BusinessRestart> {
  activeIdentity = structuredClone(activeIdentity);
  await guard(); noLinks(runtime); check(HASH.test(expectedSha)); validateBusinessArtifact(artifact);
  const credentialPath = join(runtime, 'task9-business-restart.json'), checkpointPath = join(runtime, 'task9-business-pre-restart.json');
  const proofPath = join(runtime, 'task96n-idp-addition-proof.json'), journalPath = join(runtime, 'task9-business-operation.json');
  const bytes = (path: string) => { noLinks(path); return readFileSync(path); };
  const credential = bytes(credentialPath); check(sha(credential) === expectedSha);
  const record = JSON.parse(credential.toString('utf8'));
  check(record && exact(record, ['profile','runId','buildSha','releaseId','jarSha256','manifestHash','gateRevision','checkpointSha256','originalEnvironmentDigest','originalApiIdentity','activeEnvironmentDigest','activeApiIdentity','idpAdditionProofSha256','preservedCommandCount','preservedStageCount','continuationCase']));
  check(record.profile === 'TASK9_SAME_BUILD_RESTART_V1' && record.runId === RUN && record.preservedCommandCount === 9 && record.preservedStageCount === 2 && record.continuationCase === 'T9-W01-assign-contact-review');
  for (const key of ['buildSha','releaseId','jarSha256','manifestHash'] as const) check(record[key] === artifact[key]);
  check(record.gateRevision === artifact.revision);
  for (const key of ['checkpointSha256','originalEnvironmentDigest','originalApiIdentity','activeEnvironmentDigest','activeApiIdentity','idpAdditionProofSha256']) check(typeof record[key] === 'string' && HASH.test(record[key]));
  check(record.originalEnvironmentDigest !== record.activeEnvironmentDigest && record.activeApiIdentity === activeApiIdentity);
  const originalIdentity = { ...activeIdentity, environmentDigest: record.originalEnvironmentDigest };
  check(activeIdentity.runId === RUN && activeIdentity.buildSha === artifact.buildSha && activeIdentity.predecessorRunId === IDENTITY_PREDECESSOR.runId && activeIdentity.predecessorSha256 === IDENTITY_PREDECESSOR.journalSha256 && activeIdentity.environmentDigest === record.activeEnvironmentDigest);
  const checkpointBytes = bytes(checkpointPath); check(sha(checkpointBytes) === record.checkpointSha256);
  const checkpoint = JSON.parse(checkpointBytes.toString('utf8'));
  check(checkpoint && Array.isArray(checkpoint.commands) && checkpoint.commands.length === 9 && checkpoint.commands.every((entry: any) => entry.status === 'CONFIRMED') && Array.isArray(checkpoint.stages) && checkpoint.stages.length === 2);
  check(JSON.stringify(checkpoint.identity) === JSON.stringify(originalIdentity));
  // Reuse all existing command, selector, evidence, report-path and pending fences.
  await BusinessJournal.open(checkpointPath, originalIdentity, guard);
  const proofBytes = bytes(proofPath); check(sha(proofBytes) === record.idpAdditionProofSha256);
  const proof = JSON.parse(proofBytes.toString('utf8'));
  check(proof.profile === 'TASK9_USER_CONFIRMED_IDP_ADDITION_V1' && proof.originalRunId === RUN && proof.originalJournalSha256 === record.checkpointSha256 && proof.confirmedCommands === 9 && proof.stageCount === 2 && proof.originalIdentityTableCount === 84 && proof.originalIdentityAfterExactExclusion === true && proof.releaseAndMaterialsUnchanged === true);
  const immutable = [[credentialPath, expectedSha], [checkpointPath, record.checkpointSha256], [proofPath, record.idpAdditionProofSha256]];
  for (const stage of checkpoint.stages) {
    const reportBytes = bytes(stage.reportPath); check(sha(reportBytes) === stage.reportSha256 && JSON.parse(reportBytes.toString('utf8')).apiIdentity === record.originalApiIdentity);
    immutable.push([stage.reportPath, stage.reportSha256]);
  }
  const context: BusinessRestart = freeze({
    checkpoint, originalIdentity: structuredClone(originalIdentity), activeIdentity: structuredClone(activeIdentity), originalApiIdentity: record.originalApiIdentity, activeApiIdentity,
    assertUnchanged() {
      for (const [path, digest] of immutable) check(sha(bytes(path)) === digest);
      check(!existsSync(checkpointPath + '.pending') && !existsSync(checkpointPath + '.completion.pending'));
    },
    assertJournal(path: string, identity: BusinessRunIdentity, data: Checkpoint) {
      check(resolve(path) === resolve(journalPath) && JSON.stringify(identity) === JSON.stringify(activeIdentity));
      check(JSON.stringify(data.identity) === JSON.stringify(checkpoint.identity) && JSON.stringify(data.commands.slice(0, 9)) === JSON.stringify(checkpoint.commands) && JSON.stringify(data.stages.slice(0, 2)) === JSON.stringify(checkpoint.stages));
    },
  });
  verified.add(context); context.assertUnchanged(); noLinks(journalPath);
  await BusinessJournal.open(journalPath, activeIdentity, guard, context);
  return context;
}
