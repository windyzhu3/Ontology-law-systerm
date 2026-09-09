import { defineConfig } from '@playwright/test';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// The pinned runner otherwise writes DOM snapshots and raw error context, even
// with screenshots/trace disabled. No credential page is captured automatically.
process.env.PLAYWRIGHT_NO_COPY_PROMPT = '1';

export default defineConfig({
  testDir: './e2e/tests', workers: 1, retries: 0, fullyParallel: false,
  timeout: 600_000, expect: { timeout: 15_000 },
  outputDir: join(tmpdir(), 'ontology-law-task9-playwright-output'),
  reporter: [['./e2e/reporters/safe-reporter.ts']],
  use: { trace: 'off', video: 'off', screenshot: 'off', serviceWorkers: 'block' },
  projects: [
    { name: 'offline-harness', testMatch: 'task9-harness.spec.ts' },
    { name: 'approved-local', testMatch: 'task9-identity-entry.spec.ts' },
  ],
});
