import { defineConfig } from '@playwright/test';
import { resolve } from 'node:path';
process.env.PLAYWRIGHT_NO_COPY_PROMPT = '1';
const { program } = require('playwright/lib/program') as { program: { commands: { name(): string; opts(): { project?: string[]; reporter?: string } }[] } };
const options = program.commands.find(command => command.name() === 'test')?.opts();
const selected = options?.project?.includes('approved-local-readonly-logout') ?? false;
if (selected && options?.reporter !== undefined) throw new Error('T9_READONLY_LOGOUT_BOUNDARY');
export default defineConfig({
  testDir: './readonly', workers: 1, retries: 0, fullyParallel: false, timeout: 540_000,
  outputDir: resolve(__dirname, '../.superpowers/sdd/2026-09-08-task9-real-user-access-plan/output/task96r-playwright'), reporter: [['./reporters/readonly-logout-reporter.ts']],
  use: { trace: 'off', video: 'off', screenshot: 'off', storageState: undefined, serviceWorkers: 'block', ignoreHTTPSErrors: false },
  projects: [{ name: 'approved-local-readonly-logout', testMatch: 'logout.spec.ts', testIgnore: selected ? [] : ['**/*'] }],
});
