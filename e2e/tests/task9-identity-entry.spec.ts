import { test, chromium, type Browser } from '@playwright/test';
import { IdentitySetup } from '../fixtures/identity-setup';
import { CASES } from '../fixtures/operation-journal';
import { check, loadLocalEnvironment } from '../fixtures/local-environment';
import { safeFailureCode } from '../reporters/safe-reporter';

let browser: Browser | undefined;
let setup: IdentitySetup | undefined;
// Normal independent tests, never serial mode. Each later phase checks the exact
// durable predecessor; worker restart after a failure cannot silently bypass it.
for (const id of CASES) {
  test(id, async ({}, info) => {
    try {
      check(info.config.reporter.length === 1 && info.config.reporter[0][0].replaceAll('\\', '/').endsWith('/e2e/reporters/safe-reporter.ts'));
      check(info.project.use.trace === 'off' && info.project.use.screenshot === 'off' && info.project.use.video === 'off' && !info.project.use.storageState);
      if (!setup) {
        await loadLocalEnvironment(); // Approval and protection BEFORE browser launch.
        browser = await chromium.launch({ headless: true });
        setup = await IdentitySetup.create(browser);
        if (process.env.TASK9_RECOVER_COMMAND_ID) await setup.recoverPending();
      }
      await setup.stage(id);
    } catch { throw new Error(safeFailureCode(id)); }
  });
}
test.afterEach(async ({}, info) => {
  // Includes runner timeouts and matcher failures: the runner must not serialize
  // fill arguments, authorization headers, callback URLs, or DOM snapshots.
  info.errors.splice(0, info.errors.length, ...info.errors.map(() => ({ message: safeFailureCode(info.title) })));
});
test.afterAll(async () => {
  try { await setup?.close(); await browser?.close(); }
  catch { throw new Error('T9_FAILURE_CLOSED'); }
});
