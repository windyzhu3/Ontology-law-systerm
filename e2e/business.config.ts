import { defineConfig } from '@playwright/test';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
process.env.PLAYWRIGHT_NO_COPY_PROMPT = '1';
// Read the pinned CLI's parsed ownership: option values and tokens after -- are not project authority.
// Workers do not parse the CLI; stable project identities below allow the selected test to reload there.
const { program } = require('playwright/lib/program') as {
  program: { commands: { name(): string; opts(): { project?: string[]; reporter?: string } }[] };
};
const testOptions = program.commands.find(command => command.name() === 'test')?.opts();
const approvedSelected = testOptions?.project?.includes('approved-local-business') ?? false;
if (approvedSelected && testOptions?.reporter !== undefined) throw new Error('T9_BUSINESS_BOUNDARY');
export default defineConfig({
  testDir: './tests', workers: 1, retries: 0, fullyParallel: false, timeout: 600_000,
  outputDir: join(tmpdir(), 'ontology-law-task96k-business'), reporter: [['./reporters/business-reporter.ts']],
  use: { trace: 'off', video: 'off', screenshot: 'off', serviceWorkers: 'block' },
  projects: [
    { name: 'offline-business', testMatch: ['task9-business-harness.spec.ts', 'task9-business-restart.spec.ts'] },
    { name: 'approved-local-business', testMatch: 'task9-six-workcards.spec.ts', testIgnore: approvedSelected ? [] : ['**/*'] },
  ],
});
