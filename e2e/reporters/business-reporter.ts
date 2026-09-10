import type { FullResult, Reporter, TestCase, TestResult } from '@playwright/test/reporter';
import { BUSINESS_CASES } from '../fixtures/business-journal';
export function businessFailureCode(stage: string): string { const index = BUSINESS_CASES.indexOf(stage as any); return index < 0 ? 'T9_BUSINESS_FAILURE_CLOSED' : `T9_BUSINESS_FAILURE_${index + 1}`; }
export default class BusinessReporter implements Reporter {
  printsToStdio() { return true; }
  onStdOut() {}
  onStdErr() {}
  onError() { process.stdout.write('T9-BUSINESS phase=runner status=failed code=T9_BUSINESS_FAILURE_CLOSED\n'); }
  onTestEnd(test: TestCase, result: TestResult) { const id = BUSINESS_CASES.find(x => x === test.title) ?? 'T9-BUSINESS-OFFLINE'; const status = result.status === 'passed' ? 'passed' : 'failed'; process.stdout.write(`${id} phase=test status=${status}${status === 'passed' ? '' : ` code=${businessFailureCode(id)}`}\n`); }
  onEnd(result: FullResult) { process.stdout.write(`T9-BUSINESS phase=exit status=${result.status} exit=${result.status === 'passed' ? 0 : 1}\n`); }
}
