import { chromium, test, type Browser } from '@playwright/test';
import { loadBusinessEnvironment } from '../fixtures/business-environment';
import { BUSINESS_CASES } from '../fixtures/business-journal';
import { BusinessSetup } from '../fixtures/r1-business-setup';
import { check } from '../fixtures/local-environment';
import { businessFailureCode } from '../reporters/business-reporter';

let browser: Browser | undefined; let setup: BusinessSetup | undefined;
for (const id of BUSINESS_CASES) test(id, async ({}, info) => {
  try {
    check(info.config.reporter.length === 1 && info.config.reporter[0][0].replaceAll('\\', '/').endsWith('/e2e/reporters/business-reporter.ts'));
    check(info.project.use.trace === 'off' && info.project.use.screenshot === 'off' && info.project.use.video === 'off' && !info.project.use.storageState);
    if (!setup) {
      await loadBusinessEnvironment(); // Approval, current release and immutable predecessor before browser launch.
      browser = await chromium.launch({ headless: true }); setup = await BusinessSetup.create(browser);
    }
    await setup.stage(id);
  } catch { throw new Error(businessFailureCode(id)); }
});
test.afterEach(async ({}, info) => { info.errors.splice(0, info.errors.length, ...info.errors.map(() => ({ message: businessFailureCode(info.title) }))); });
test.afterAll(async () => { try { await setup?.close(); await browser?.close(); } catch { throw new Error('T9_BUSINESS_FAILURE_CLOSED'); } });
