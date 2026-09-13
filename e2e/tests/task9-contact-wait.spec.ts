import { chromium, test, type Browser } from '@playwright/test';
import { loadBusinessEnvironment, requireContactWaitAcceptance } from '../fixtures/business-environment';
import { CONTACT_WAIT_CASE } from '../fixtures/business-journal';
import { BusinessSetup } from '../fixtures/r1-business-setup';
import { check } from '../fixtures/local-environment';
import { businessFailureCode } from '../reporters/business-reporter';

let browser: Browser | undefined, setup: BusinessSetup | undefined;
test(CONTACT_WAIT_CASE, async ({}, info) => {
  try {
    check(info.config.reporter.length === 1 && info.config.reporter[0][0].replaceAll('\\', '/').endsWith('/e2e/reporters/business-reporter.ts'));
    check(info.project.use.trace === 'off' && info.project.use.screenshot === 'off' && info.project.use.video === 'off' && !info.project.use.storageState);
    requireContactWaitAcceptance(); await loadBusinessEnvironment();
    browser = await chromium.launch({ headless: true }); setup = await BusinessSetup.createContactWait(browser);
    await setup.stage(CONTACT_WAIT_CASE);
  } catch { throw new Error(businessFailureCode(CONTACT_WAIT_CASE)); }
});
test.afterEach(async ({}, info) => { info.errors.splice(0, info.errors.length, ...info.errors.map(() => ({ message: businessFailureCode(CONTACT_WAIT_CASE) }))); });
test.afterAll(async () => { try { await setup?.close(); await browser?.close(); } catch { throw new Error(businessFailureCode(CONTACT_WAIT_CASE)); } });
