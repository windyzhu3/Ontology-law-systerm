import type { FullResult, Reporter, TestCase, TestResult } from '@playwright/test/reporter';
const CASE = 'T9-W06-W08-readonly-waiting-refresh';
export default class ReadOnlyWaitingReporter implements Reporter {
  onStdOut() {}
  onStdErr() {}
  onError() {}
  onTestEnd(_test: TestCase, result: TestResult) { process.stdout.write(`${CASE} status=${result.status === 'passed' ? 'passed' : 'failed'}\n`); }
  onEnd(result: FullResult) { process.stdout.write(`${CASE} exit=${result.status === 'passed' ? 0 : 1}\n`); }
}
