import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { lstatSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';

export const ORIGIN = 'https://localhost:19444';
export const ISSUER = 'https://localhost:19443/realms/local-r1';
export const ALIASES = ['intake', 'supervisor', 'contact', 'delegate'] as const;
export type Alias = typeof ALIASES[number];
export const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export const sha = (data: string | Buffer) => createHash('sha256').update(data).digest('hex');
export function check(value: unknown): asserts value { if (!value) throw new Error('T9_BOUNDARY'); }
export const exact = (value: object, keys: string[]) => Object.keys(value).sort().join() === [...keys].sort().join();
export const PIN = {
  origin: ORIGIN, issuer: ISSUER, buildSha: '04bd695f7a8f656a5ed8fb96c5168e44a91bab8d',
  releaseId: 'aabce4e3252946e4952cbcb41ff280d1', jarSha256: 'b8af135f74cdafcb8396ee4d55e0526cec359ce65ee801ddd91f058e3bc2513a',
  manifestHash: '8478c05d4d783f85b7d49340e05ac667edb3ea081915e71c12efa511b5a62a25', revision: 9,
  browserVersion: '153.0.8010.12', browserRevision: '1243',
} as const;
export function requireLocalAcceptance(value: string | undefined): void { check(value === 'APPROVED_SYNTHETIC_ONLY'); }
export function validateEnvironment(value: unknown): void {
  check(value && typeof value === 'object');
  for (const [key, expected] of Object.entries(PIN)) check((value as Record<string, unknown>)[key] === expected);
}
export interface Account { username: string; password: string; email?: string; firstName?: string; lastName?: string; providerUserId?: string }
export function validateAccounts(original: any, credentials: any, operation: any): void {
  check(exact(original, ['founder', 'unmapped']));
  for (const alias of ['founder', 'unmapped']) {
    check(exact(original[alias], ['username', 'password']));
    check(original[alias].username === `synthetic-${alias}` && typeof original[alias].password === 'string' && original[alias].password.length > 0);
  }
  check(exact(credentials, ['runId', 'accounts']) && /^[A-Za-z0-9_-]{1,128}$/.test(credentials.runId) && operation.runId === credentials.runId && operation.stage === 'COMPLETE');
  check(exact(credentials.accounts, [...ALIASES]) && exact(operation.accounts, [...ALIASES]));
  for (const flag of ['temporaryClientDeleted', 'temporaryCredentialRejected', 'temporaryTokenRejected', 'originalUsersUnchanged', 'realmPublicKeysUnchanged', 'directoryReadOnlyUnchanged', 'temporaryClientUserAndRolesAbsent', 'temporaryRecoveryContainerRemoved']) check(operation[flag] === true);
  const ids = new Set();
  for (const alias of ALIASES) {
    const account = credentials.accounts[alias], saved = operation.accounts[alias];
    check(exact(account, ['username', 'password', 'email', 'firstName', 'lastName', 'providerUserId']));
    check(exact(saved, ['username', 'email', 'firstName', 'lastName', 'providerUserId']));
    check(account.username === `task9-local-${alias}` && uuid.test(account.providerUserId));
    check(typeof account.password === 'string' && account.password.length > 0 && account.email.endsWith('@example.invalid'));
    for (const key of Object.keys(saved)) check(saved[key] === account[key]);
    ids.add(account.providerUserId);
  }
  check(ids.size === 4);
}

// Also applied to synthetic directories. Windows junctions are reported as links by lstat.
export function noLinks(path: string): void {
  let current = resolve(path);
  for (;;) {
    const info = lstatSync(current); check(!info.isSymbolicLink());
    const parent = dirname(current); if (current === parent) return; current = parent;
  }
}
const root = resolve(__dirname, '../..');
export const runtime = join(root, '.superpowers/sdd/2026-09-08-task9-real-user-access-plan/local-login-runtime');

// Only the controller executes this bridge. Existing public adapters perform all ACL,
// Git-ignore, release-byte and exact process ownership checks. No SQL is invoked.
const bridge = String.raw`
import sys,json,hashlib
from pathlib import Path
sys.path.insert(0,str(Path(sys.argv[1])/'deploy/local-login'))
import local_login as runner
from local_release import RuntimeBoundary,LocalRelease,read_json,regular,encoded,digest
boundary=RuntimeBoundary(runner)
boundary.protect()
if sys.argv[2]=='protect':
    print('{}');sys.exit(0)
release=LocalRelease(runner.ROOT,runner.RUNTIME,boundary)
current=release.current();record=release.load(current['id']);package=release.package(current['id'])
processes=boundary.processes()
assert len(processes)==3 and all(p is not None for p in processes)
saved=read_json(regular(runner.RUNTIME/'processes.json'))
assert isinstance(saved['api'],dict) and 'created' in saved['api']
deployment=read_json(regular(package/'deployment.json'))
assert current['gate']['operating_mode']=='ACTIVE' and current['gate']['schema_contract_version']=='52-plus-2-v1.2'
assert deployment['releaseDigest']==current['gate']['active_release_digest'] and deployment['manifestHash']==current['gate']['active_manifest_hash']
assert (runner.RUNTIME/'application.properties').read_bytes()==(package/'application.properties').read_bytes()
assert read_json(regular(runner.RUNTIME/'deployment.json'))==deployment
assert record['kind']=='controlled-local-source-release'
assert read_json(regular(package/'release-manifest.json'))==record['sourceRelease']
assert digest(encoded(record['sourceRelease']))==current['gate']['active_manifest_hash']
assert record['sourceRelease']['binaryProvenance']==record['provenance']
assert digest(regular(package/'app.jar').read_bytes())==record['provenance']['jarSha256']
result={'origin':runner.ORIGIN,'issuer':runner.ISSUER,'buildSha':record['provenance']['sourceCommit'],
 'releaseId':current['id'],'jarSha256':record['provenance']['jarSha256'],
 'manifestHash':current['gate']['active_manifest_hash'],'revision':current['gate']['revision'],
 'apiIdentity':digest(encoded(saved['api'])),'releaseIdentity':digest(encoded(current))}
if sys.argv[2]=='load':
    original=read_json(regular(runner.RUNTIME/'original-manifest.json'))
    operator=read_json(regular(runner.RUNTIME/'operator.json'))
    plan=read_json(regular(runner.RUNTIME/'worker/grants.json'))
    facts=plan['original'];b=facts['bootstrap'];r=facts['root'];f=facts['founder'];a=facts['founderAppointment']
    assert original['rootCode']=='ROOT' and original['issuer']==runner.ISSUER
    assert facts['originalSlot']['command_id']==original['commandId']
    assert facts['originalReceipt']['command_execution_slot_id']==facts['originalSlot']['command_execution_slot_id']
    assert b['rootOrganizationId']==r['organization_unit_id'] and b['founderPrincipalId']==f['principal_id'] and b['appointmentId']==a['appointment_id']
    assert a['principal_id']==f['principal_id'] and a['organization_unit_id']==r['organization_unit_id'] and a['role_code']=='IDENTITY_ADMIN'
    assert r['parent_organization_unit_id'] is None and r['unit_code']=='ROOT' and f['principal_kind']=='HUMAN'
    assert all(x['tenant_id']==operator['tenantId']==deployment['tenantId'] and x['state']=='ACTIVE' for x in [r,f,a])
    result['bootstrap']={'tenantId':operator['tenantId'],'rootId':b['rootOrganizationId'],'founderId':b['founderPrincipalId'],'appointmentId':b['appointmentId']}
    result['original']=read_json(regular(runner.RUNTIME/'browser-credentials.json'))
    result['credentials']=read_json(regular(runner.RUNTIME/'task9-browser-credentials.json'))
    result['operation']=read_json(regular(runner.RUNTIME/'task9-test-account-operation.json'))
print(json.dumps(result))
`;
function invoke(mode: 'protect' | 'snapshot' | 'load'): any {
  requireLocalAcceptance(process.env.TASK9_LOCAL_ACCEPTANCE);
  noLinks(root); noLinks(runtime);
  const result = spawnSync('D:/soft/python3/python.exe', ['-B', '-c', bridge, root, mode], { encoding: 'utf8', windowsHide: true, timeout: 60_000, maxBuffer: 2 * 1024 * 1024 });
  // Never expose child stderr/stdout on failure (including Python local variables).
  check(!result.error && result.status === 0);
  try { return JSON.parse(result.stdout); } catch { throw new Error('T9_BOUNDARY'); }
}
export const protect = () => { invoke('protect'); };
function toolchain(): { browserRevision: string; browserVersion: string } {
  check(process.version === 'v24.20.0');
  for (const [file, digest] of [
    ['deploy/identity/identity-toolchain.lock.json', '79cee0549c7186406f485b61825f6334496f9c1910b163a2cee65776d8654ca2'],
    ['database/schema-contract-52-plus-2/runtime/toolchain.lock.json', 'dab3192899a4000cc68b13549a77cf8fadcef0b1128c4612562df01aa7ed4b52'],
  ]) check(sha(readFileSync(join(root, file))) === digest);
  const lock = JSON.parse(readFileSync(join(root, 'package-lock.json'), 'utf8'));
  const expected = JSON.parse(readFileSync(join(root, 'deploy/identity/identity-toolchain.lock.json'), 'utf8')).browserTests;
  check(lock.packages['node_modules/@playwright/test'].version === expected.version && lock.packages['node_modules/@playwright/test'].integrity === expected.integrity);
  check(JSON.parse(readFileSync(join(root, 'node_modules/@playwright/test/package.json'), 'utf8')).version === expected.version);
  const browser = JSON.parse(readFileSync(join(root, 'node_modules/playwright-core/browsers.json'), 'utf8')).browsers.find((x: any) => x.name === 'chromium');
  return { browserRevision: browser.revision, browserVersion: browser.browserVersion };
}
export function loadLocalEnvironment() {
  requireLocalAcceptance(process.env.TASK9_LOCAL_ACCEPTANCE);
  check(!process.env.DEBUG && !process.env.PWDEBUG && !process.env.PW_TEST_DEBUG);
  const tools = toolchain();
  const snapshot = invoke('snapshot'); validateEnvironment({ ...snapshot, ...tools });
  const loaded = invoke('load'); validateEnvironment({ ...loaded, ...tools });
  check(snapshot.apiIdentity === loaded.apiIdentity && snapshot.releaseIdentity === loaded.releaseIdentity);
  validateAccounts(loaded.original, loaded.credentials, loaded.operation);
  const environmentDigest = sha(JSON.stringify({ ...snapshot, ...tools }));
  return {
    ...PIN, environmentDigest, apiIdentity: snapshot.apiIdentity,
    bootstrap: loaded.bootstrap as { tenantId: string; rootId: string; founderId: string; appointmentId: string },
    accounts: { ...loaded.original, ...loaded.credentials.accounts } as Record<Alias | 'founder' | 'unmapped', Account>,
    assertUnchanged() {
      const now = invoke('snapshot'); validateEnvironment({ ...now, ...tools });
      check(sha(JSON.stringify({ ...now, ...tools })) === environmentDigest);
    },
    verifyBrowser(actual: string) { check(actual === PIN.browserVersion); },
  };
}
export type LocalEnvironment = ReturnType<typeof loadLocalEnvironment>;
