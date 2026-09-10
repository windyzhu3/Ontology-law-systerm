import type { TestCase, TestResult } from '@playwright/test/reporter';
import SafeReporter from './safe-reporter';
export default class ReadOnlyReporter extends SafeReporter {
  onTestEnd(_test: TestCase, result: TestResult) {
    process.stdout.write(`T9-READONLY-SESSION status=${result.status === 'passed' ? 'passed' : 'failed'}\n`);
  }
}
