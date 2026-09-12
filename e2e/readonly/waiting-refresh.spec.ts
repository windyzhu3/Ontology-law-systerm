import { chromium, test } from '@playwright/test';
import { performance } from 'node:perf_hooks';
import { loadReadOnlyWaitingEnvironment, READONLY_WAITING_PIN } from '../fixtures/business-environment';
import { assertReadOnlyWaitingConfig, READONLY_WAITING_CASE, runReadOnlyWaiting } from '../fixtures/readonly-waiting';

test(READONLY_WAITING_CASE, async ({}, info) => {
  const deadline = performance.now() + 510_000;
  try {
    assertReadOnlyWaitingConfig(info);
    const environment = await loadReadOnlyWaitingEnvironment();
    // The due-time refusal produces NOT_EXECUTED evidence without launching a browser.
    const browser = Date.parse(READONLY_WAITING_PIN.dueAt) - Date.now() < 600_000 ? undefined : await chromium.launch({ headless: true });
    const result = await runReadOnlyWaiting(browser, environment, { clock: { now: () => performance.now(), wallNow: () => Date.now(), sleep: ms => new Promise(resolve => setTimeout(resolve, ms)), deadline } });
    if (result.status !== 'PASSED_READ_ONLY_SUBSCENARIO') throw new Error();
  } catch { throw new Error('T9_READONLY_WAITING_FAILED_CLOSED'); }
});
test.afterEach(async ({}, info) => {
  info.errors.splice(0, info.errors.length, ...info.errors.map(() => ({ message: 'T9_READONLY_WAITING_FAILED_CLOSED' })));
});
