import { parseEnvelope, type Envelope } from '../../apps/workbench/src/features/workcard/contract';
import { check } from './local-environment';

export const lowerHeaders = (headers: Record<string, string>) => Object.fromEntries(Object.entries(headers).map(([key, value]) => [key.toLowerCase(), value]));
export function exactHeaderTokens(value: string | undefined, expected: readonly string[]): boolean {
  if (!value) return false;
  const actual = new Set(value.split(',').map(token => token.trim().toLowerCase()).filter(Boolean));
  return actual.size === expected.length && expected.every(token => actual.has(token));
}
export function workbenchETag(value: string | undefined): value is string { return /^"wb\.[A-Za-z0-9_-]{43}"$/.test(value ?? ''); }
export type WorkbenchCache = { actorScopeKey: string; etag: string; generation: number; envelope: Envelope };
// Pure wire contract only. Callers own request identity capture and late-response guards.
export function validateWorkbenchCache(input: { status: number; headers: Record<string, string>; requestHeaders?: Record<string, string>; body?: unknown; actorScopeKey: string; generation: number }, prior?: WorkbenchCache): WorkbenchCache {
  const headers = lowerHeaders(input.headers), request = lowerHeaders(input.requestHeaders ?? {});
  check(exactHeaderTokens(headers['cache-control'], ['private','no-cache']) && exactHeaderTokens(headers.vary, ['authorization']) && workbenchETag(headers.etag));
  check(typeof input.actorScopeKey === 'string' && /^ask1\.[A-Za-z0-9_-]{43}$/.test(input.actorScopeKey) && Number.isSafeInteger(input.generation) && input.generation > 0);
  if (input.status === 200) return { actorScopeKey: input.actorScopeKey, etag: headers.etag, generation: input.generation, envelope: structuredClone(parseEnvelope(input.body)) };
  check(input.status === 304 && (input.body === undefined || input.body === '') && prior && prior.actorScopeKey === input.actorScopeKey && prior.generation < input.generation && request['if-none-match'] === prior.etag && headers.etag === prior.etag);
  return { ...prior, generation: input.generation, envelope: structuredClone(prior.envelope) };
}
