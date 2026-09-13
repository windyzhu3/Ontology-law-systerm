import { check, exact, ISSUER, ORIGIN, sha } from './local-environment';

export const COMPLETE_JOURNAL_SHA = '44c95f59853fa552d2d7ba933dcb80a4877464fe26dab7c34202d3e1fb0ad9a1';
export function validateReadOnlyJournal(bytes: Buffer, environment: { buildSha: string; environmentDigest: string }): string {
  const digest = sha(bytes); check(digest === COMPLETE_JOURNAL_SHA);
  // Parse the same already-hashed bytes, never reread or instantiate a journal.
  try {
    const { identity } = JSON.parse(bytes.toString('utf8'));
    check(exact(identity, ['runId', 'buildSha', 'environmentDigest']));
    check(identity.runId === '74a496f6-494e-417d-9abd-69a85c94f165'
      && identity.buildSha === environment.buildSha && identity.environmentDigest === environment.environmentDigest);
  } catch { throw new Error('T9_BOUNDARY'); }
  return digest;
}
export function allowReadOnlyRequest(url: URL, method: string): boolean {
  const read = ['GET', 'HEAD', 'OPTIONS'].includes(method);
  if (url.origin === ORIGIN) return read;
  if (url.origin !== new URL(ISSUER).origin) return false;
  if (read) return url.pathname.startsWith('/realms/local-r1/') || url.pathname.startsWith('/resources/');
  return method === 'POST' && (url.pathname === '/realms/local-r1/protocol/openid-connect/token' || url.pathname === '/realms/local-r1/login-actions/authenticate');
}
export interface ReadOnlyEvidence {
  refreshObserved: boolean; refreshStatus: number | null; refreshDuringBoundary: boolean;
  organizationStatus: number | null; principalStatus: number | null;
  adminVisible: boolean; loginVisible: boolean; sessionNoticeVisible: boolean;
  blockedBusinessWrites: number; blockedRequests: number; boundaryCompleted: boolean;
  journalBefore: string; journalAfter: string; environmentUnchanged: boolean;
  failureStep: string | null;
}
export function readOnlyOutcome(e: ReadOnlyEvidence): 'PASSED_READ_ONLY_SUBSCENARIO' | 'FAILED' | 'NOT_TRIGGERED' {
  if (e.failureStep !== null || e.blockedRequests !== 0 || e.blockedBusinessWrites !== 0 || !e.environmentUnchanged
    || e.journalBefore !== COMPLETE_JOURNAL_SHA || e.journalAfter !== COMPLETE_JOURNAL_SHA
    || !e.adminVisible || e.loginVisible || e.sessionNoticeVisible
    || (e.organizationStatus !== null && e.organizationStatus !== 200) || (e.principalStatus !== null && e.principalStatus !== 200)) return 'FAILED';
  if (!e.refreshObserved) return 'NOT_TRIGGERED';
  return e.failureStep === null && e.refreshStatus === 200 && e.refreshDuringBoundary && e.boundaryCompleted
    && e.organizationStatus === 200 && e.principalStatus === 200 && e.adminVisible && !e.loginVisible && !e.sessionNoticeVisible
    && e.blockedBusinessWrites === 0 && e.blockedRequests === 0 && e.environmentUnchanged
    && e.journalBefore === COMPLETE_JOURNAL_SHA && e.journalAfter === COMPLETE_JOURNAL_SHA
    ? 'PASSED_READ_ONLY_SUBSCENARIO' : 'FAILED';
}
