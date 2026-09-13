import { chromium, test, type Browser } from '@playwright/test';
import { existsSync } from 'node:fs';
import { join } from 'node:path';

import { R1IsolatedBrowserAdapter } from '../fixtures/r1-isolated-browser';
import { loadR1IsolatedEnvironment, writeR1ClosedReport } from '../fixtures/r1-isolated-environment';
import { AcceptanceJournal, R1GoldenOrchestrator } from '../fixtures/r1-isolated-setup';


let browser: Browser | undefined;
let adapter: R1IsolatedBrowserAdapter | undefined;

test('R1-CONTACT-CONNECTED-GOLDEN', async ({}, info) => {
  if (info.project.use.trace !== 'off' || info.project.use.video !== 'off' || info.project.use.screenshot !== 'off' || info.project.use.ignoreHTTPSErrors !== false || info.project.use.storageState) {
    throw new Error('R1_GOLDEN_CLOSED_FAILURE');
  }
  try {
    const environment = await loadR1IsolatedEnvironment();
    browser = await chromium.launch({ headless: true }); environment.verifyBrowser(browser.version());
    adapter = await R1IsolatedBrowserAdapter.create(browser, environment);
    const journalPath = join(environment.runtime, `acceptance-${environment.operationId}-journal.json`);
    if (existsSync(journalPath) && process.env.R1_ISOLATED_CONTINUE_OPERATION_ID !== environment.operationId) throw new Error('R1_ISOLATED_BOUNDARY');
    const journal = await AcceptanceJournal.open(
      journalPath,
      { run: environment.run, operationId: environment.operationId, environmentDigest: environment.environmentDigest, sourceCommit: environment.sourceCommit },
    );
    const result = await new R1GoldenOrchestrator(journal, adapter).run();
    writeR1ClosedReport(environment, result);
  } catch { throw new Error('R1_GOLDEN_CLOSED_FAILURE'); }
});

test.afterEach(async ({}, info) => { info.errors.splice(0, info.errors.length, ...info.errors.map(() => ({ message: 'R1_GOLDEN_CLOSED_FAILURE' }))); });
test.afterAll(async () => { try { await adapter?.close(); await browser?.close(); } catch { throw new Error('R1_GOLDEN_CLOSED_FAILURE'); } });
