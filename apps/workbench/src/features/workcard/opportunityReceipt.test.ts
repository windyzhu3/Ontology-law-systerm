import { describe, expect, it } from "vitest";
import { validReceipt } from "./contract";
const receipt = { commandId: "01900000-0000-7000-8000-000000000001", receiptId: "01900000-0000-7000-8000-000000000002", completedAt: "2026-09-14T00:00:00Z", outcome: "SUCCEEDED", resultFact: { factType: "OPPORTUNITY_PROGRESS", factRef: "fact-progress-reference", digest: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" } };
const isReceipt = (value: unknown) => validReceipt(value, receipt.commandId);
describe("R2 opportunity receipt", () => {
  it("accepts the opaque immutable progress reference", () => expect(isReceipt(receipt)).toBe(true));
  it("rejects revision selectors and copied business text", () => {
    expect(isReceipt({ ...receipt, resultFact: { ...receipt.resultFact, revision: 0 } })).toBe(false);
    expect(isReceipt({ ...receipt, resultFact: { ...receipt.resultFact, summary: "private progress" } })).toBe(false);
  });
});
