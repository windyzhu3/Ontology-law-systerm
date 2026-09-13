import { defineConfig } from '@playwright/test';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

process.env.PLAYWRIGHT_NO_COPY_PROMPT = '1';
const { program } = require('playwright/lib/program') as { program: { commands: { name(): string; opts(): { project?: string[]; reporter?: string } }[] } };
const options = program.commands.find(command => command.name() === 'test')?.opts();
const approved = options?.project?.includes('approved-r1-isolated') ?? false;
if (approved && options?.reporter !== undefined) throw new Error('R1_ISOLATED_BOUNDARY');

export default defineConfig({
  testDir: './tests', workers: 1, retries: 0, fullyParallel: false, timeout: 600_000,
  outputDir: join(tmpdir(), 'ontology-law-r1-isolated'), reporter: [['line']],
  use: { trace: 'off', video: 'off', screenshot: 'off', serviceWorkers: 'block', ignoreHTTPSErrors: false },
  projects: [
    { name: 'offline-r1-isolated', testMatch: 'r1-isolated-harness.spec.ts' },
    { name: 'approved-r1-isolated', testMatch: 'r1-golden-path.spec.ts', testIgnore: approved ? [] : ['**/*'] },
  ],
});
