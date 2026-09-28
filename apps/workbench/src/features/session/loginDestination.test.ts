import { afterEach, expect, it, vi } from 'vitest';
import { consumeLoginDestination, rememberLoginDestination } from './loginDestination';

afterEach(() => { sessionStorage.clear(); vi.useRealTimers(); });
it.each(['https://example.test', '//example.test', '/management/team-tasks?token=secret', '/unknown'])('does not restore an unapproved destination %s', path => {
  sessionStorage.setItem('ols.login-destination.v1', JSON.stringify({path, savedAt:Date.now()}));
  expect(consumeLoginDestination()).toBe('/workbench');
});
it('expires an abandoned destination and consumes a valid destination only once', () => {
  vi.useFakeTimers();
  rememberLoginDestination('/management/team-tasks');
  vi.advanceTimersByTime(600_001);
  expect(consumeLoginDestination()).toBe('/workbench');
  rememberLoginDestination('/admin/identity/organizations');
  expect(consumeLoginDestination()).toBe('/admin/identity/organizations');
  expect(consumeLoginDestination()).toBe('/workbench');
});
