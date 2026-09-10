// Test-only isolation: execute the real TS bridge launcher with a synthetic
// Python program and synthetic root, never the protected runtime or its files.
import { mkdtempSync, mkdirSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { createRequire } from 'node:module';
import { runInNewContext } from 'node:vm';
import ts from 'typescript';

export function boundaryProbe(program: string, timeout?: number, maxBuffer?: number) {
  const root = mkdtempSync(join(tmpdir(), 'task9-async-synthetic-'));
  mkdirSync(join(root, '.superpowers/sdd/2026-09-08-task9-real-user-access-plan/local-login-runtime'), { recursive: true });
  let source = readFileSync(resolve(__dirname, '../fixtures/local-environment.ts'), 'utf8');
  const start = source.indexOf('String.raw`'), end = source.indexOf('`;', start);
  source = source.slice(0, start) + JSON.stringify(program) + source.slice(end + 1);
  source += '\nexport { invoke as probe };';
  if (timeout !== undefined) source = source.replace('timeout: 60_000', `timeout: ${timeout}`);
  if (maxBuffer !== undefined) source = source.replace('maxBuffer: 2 * 1024 * 1024', `maxBuffer: ${maxBuffer}`);
  const exports: any = {};
  runInNewContext(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText,
    { exports, require: createRequire(__filename), __dirname: join(root, 'e2e/fixtures'), Buffer, Promise, Error, setTimeout, clearTimeout,
      process: { ...process, env: { TASK9_LOCAL_ACCEPTANCE: 'APPROVED_SYNTHETIC_ONLY' } } });
  return exports.probe as (mode: 'snapshot') => any;
}
