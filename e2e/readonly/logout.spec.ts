import { chromium, test } from '@playwright/test';
import { performance } from 'node:perf_hooks';
import { loadReadOnlyLogoutEnvironment } from '../fixtures/business-environment';
import { assertReadOnlyLogoutConfig, READONLY_LOGOUT_CASE, runReadOnlyLogout } from '../fixtures/readonly-logout';

test(READONLY_LOGOUT_CASE, async ({}, info) => {
  const deadline = performance.now() + 510_000;
  try {
    assertReadOnlyLogoutConfig(info);
    const environment = await loadReadOnlyLogoutEnvironment();
    const result = await runReadOnlyLogout(() => chromium.launch({ headless: true }), environment, { clock: { now: () => performance.now(), wallNow: () => Date.now(), sleep: ms => new Promise(resolve => setTimeout(resolve, ms)), deadline } });
    if (result.status !== 'PASSED_READ_ONLY_SUBSCENARIO') throw Error();
  } catch { throw new Error('T9_READONLY_LOGOUT_FAILED_CLOSED'); }
});
test.afterEach(async ({}, info) => {
  info.errors.splice(0, info.errors.length, ...info.errors.map(() => ({ message: 'T9_READONLY_LOGOUT_FAILED_CLOSED' })));
});
