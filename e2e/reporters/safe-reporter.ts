import type { FullResult, Reporter, TestCase, TestResult } from '@playwright/test/reporter';
import { CASES } from '../fixtures/operation-journal';

export function safeFailureCode(stage: string): string {
  return CASES.includes(stage as any) ? `T9_FAILURE_${CASES.indexOf(stage as any) + 1}` : 'T9_FAILURE_CLOSED';
}
export default class SafeReporter implements Reporter {
  printsToStdio() { return true; }
  onStdOut() {}
  onStdErr() {}
  onError() { process.stdout.write('T9-HARNESS phase=runner status=FAILED code=T9_FAILURE_CLOSED\n'); }
  onTestEnd(test: TestCase, result: TestResult) {
    const id = CASES.find(id => test.title === id) ?? 'T9-OFFLINE';
    const status = ['passed', 'failed', 'timedOut', 'interrupted', 'skipped'].includes(result.status) ? result.status : 'failed';
    process.stdout.write(`${id} phase=test status=${status}${status === 'passed' ? '' : ` code=${safeFailureCode(id)}`}\n`);
  }
  onEnd(result: FullResult) {
    process.stdout.write(`T9-HARNESS phase=exit status=${result.status} exit=${result.status === 'passed' ? 0 : 1}\n`);
  }
}
