import { defineConfig } from '@playwright/test';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
process.env.PLAYWRIGHT_NO_COPY_PROMPT = '1';
const { program } = require('playwright/lib/program') as { program: { commands: { name(): string; opts(): { project?: string[]; reporter?: string } }[] } };
const options = program.commands.find(command => command.name() === 'test')?.opts();
const selected = options?.project?.includes('approved-local-readonly-waiting') ?? false;
if (selected && options?.reporter !== undefined) throw new Error('T9_READONLY_WAITING_BOUNDARY');
export default defineConfig({
  testDir: './readonly', workers: 1, retries: 0, fullyParallel: false, timeout: 540_000,
  outputDir: join(tmpdir(), 'ontology-law-task96q-readonly-waiting'), reporter: [['./reporters/readonly-waiting-reporter.ts']],
  use: { trace: 'off', video: 'off', screenshot: 'off', storageState: undefined, serviceWorkers: 'block', ignoreHTTPSErrors: false },
  projects: [{ name: 'approved-local-readonly-waiting', testMatch: 'waiting-refresh.spec.ts', testIgnore: selected ? [] : ['**/*'] }],
});
