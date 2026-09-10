import { check, exact, sha, uuid, ORIGIN, ISSUER } from './local-environment';
import { BusinessJournal, type BusinessStep, type RequestSelectors } from './business-journal';

const READS = [
  '/api/v1/session/context', '/api/v1/workcards/current',
  '/api/v1/admin/identity/principals', '/api/v1/admin/identity/organizations',
  '/api/v1/admin/identity/appointments', '/api/v1/admin/identity/authority-grants',
  '/api/v1/admin/identity/options', '/api/v1/admin/identity/provider-users',
] as const;
export function allowBusinessRequest(url: URL, method: string): boolean {
  if (url.origin === new URL(ISSUER).origin) {
    if (!(url.pathname.startsWith('/realms/local-r1/') || url.pathname.startsWith('/resources/'))) return false;
    return ['GET', 'HEAD', 'OPTIONS'].includes(method) || (method === 'POST' && url.pathname.startsWith('/realms/local-r1/'));
  }
  if (url.origin !== ORIGIN) return false;
  if (!url.pathname.startsWith('/api/')) return ['GET', 'HEAD', 'OPTIONS'].includes(method);
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) return false;
  return READS.some(path => url.pathname === path) || /^\/api\/v1\/commands\/[0-9a-f-]{36}\/receipt$/.test(url.pathname);
}
export interface ArmedBusinessWrite { step: BusinessStep; method: string; path: string; body: Record<string, unknown>; actorScopeKey: string; requestSelectors: RequestSelectors }
export interface ObservedBusinessWrite { method: string; path: string; bodyBytes: Buffer; commandId: string; actorScopeKey: string }
export function canonicalBusinessJson(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonicalBusinessJson).join(',')}]`;
  if (value && typeof value === 'object') return `{${Object.entries(value as Record<string, unknown>).sort(([a], [b]) => a.localeCompare(b)).map(([key, item]) => `${JSON.stringify(key)}:${canonicalBusinessJson(item)}`).join(',')}}`;
  return JSON.stringify(value);
}
export class BusinessDispatchGate {
  private armed?: ArmedBusinessWrite;
  private poisoned = false;
  constructor(private readonly journal: BusinessJournal) {}
  get failed() { return this.poisoned; }
  arm(value: ArmedBusinessWrite): void {
    check(!this.armed && !this.poisoned && !this.journal.pending());
    check(value && exact(value, ['step', 'method', 'path', 'body', 'actorScopeKey', 'requestSelectors']) && value.body && typeof value.body === 'object' && !Array.isArray(value.body));
    this.armed = structuredClone(value);
  }
  async dispatch(observed: ObservedBusinessWrite, send: () => Promise<void>): Promise<void> {
    // Consume synchronously: a duplicate route cannot wait behind protection.
    const armed = this.armed; this.armed = undefined;
    if (!armed || this.poisoned) { this.poisoned = true; throw new Error('T9_BUSINESS_BOUNDARY'); }
    try {
      check(exact(observed, ['method', 'path', 'bodyBytes', 'commandId', 'actorScopeKey']));
      check(observed.method === armed.method && observed.path === armed.path && observed.actorScopeKey === armed.actorScopeKey && uuid.test(observed.commandId));
      const parsed = JSON.parse(observed.bodyBytes.toString('utf8'));
      check(parsed && typeof parsed === 'object' && !Array.isArray(parsed) && canonicalBusinessJson(parsed) === canonicalBusinessJson(armed.body));
      await this.journal.begin({ step: armed.step, commandId: observed.commandId, method: observed.method, path: observed.path, bodySha256: sha(observed.bodyBytes), actorScopeKey: observed.actorScopeKey, requestSelectors: armed.requestSelectors });
      check(!this.poisoned); await send();
    } catch (error) { this.poisoned = true; throw error; }
  }
}
