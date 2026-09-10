import { defineConfig } from '@playwright/test';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
process.env.PLAYWRIGHT_NO_COPY_PROMPT = '1';
const approvedSelected = process.argv.some((value, index, argv) => value === '--project=approved-local-business'
  || value === '--project' && argv[index + 1] === 'approved-local-business');
if (approvedSelected && process.argv.some(value => value === '--reporter' || value.startsWith('--reporter='))) throw new Error('T9_BUSINESS_BOUNDARY');
export default defineConfig({
  testDir: './tests', workers: 1, retries: 0, fullyParallel: false, timeout: 600_000,
  outputDir: join(tmpdir(), 'ontology-law-task96k-business'), reporter: [['./reporters/business-reporter.ts']],
  use: { trace: 'off', video: 'off', screenshot: 'off', serviceWorkers: 'block' },
  projects: [
    { name: 'offline-business', testMatch: 'task9-business-harness.spec.ts' },
    { name: 'approved-local-business', testMatch: 'task9-six-workcards.spec.ts', testIgnore: approvedSelected ? [] : ['**/*'] },
  ],
});
