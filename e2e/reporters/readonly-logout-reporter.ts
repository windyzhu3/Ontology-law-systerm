import type { FullResult, Reporter, TestCase, TestResult } from '@playwright/test/reporter';
const CASE = 'T9-L09-existing-session-logout';
export default class ReadOnlyLogoutReporter implements Reporter {
  onStdOut() {}
  onStdErr() {}
  onError() {}
  onTestEnd(_test: TestCase, result: TestResult) { process.stdout.write(`${CASE} status=${result.status === 'passed' ? 'passed' : 'failed'}\n`); }
  onEnd(result: FullResult) { process.stdout.write(`${CASE} exit=${result.status === 'passed' ? 0 : 1}\n`); }
}
