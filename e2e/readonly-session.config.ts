import { defineConfig } from '@playwright/test';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
process.env.PLAYWRIGHT_NO_COPY_PROMPT = '1';
export default defineConfig({
  testDir: './readonly', testMatch: 'session-refresh.spec.ts', workers: 1, retries: 0, fullyParallel: false,
  timeout: 420_000, expect: { timeout: 15_000 },
  outputDir: join(tmpdir(), 'ontology-law-task9-readonly-session'),
  reporter: [['./reporters/readonly-reporter.ts']],
  use: { trace: 'off', screenshot: 'off', video: 'off', serviceWorkers: 'block' },
});
