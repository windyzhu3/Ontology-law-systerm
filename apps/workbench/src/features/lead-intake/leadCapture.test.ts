import { expect, it } from "vitest";
import { captureBody } from "./leadCapture";
const source = { sourceAccountCode: "sales", displayName: "手工录入", sourceChannelCode: "MANUAL", serviceCategoryCode: "GENERAL_INTAKE", jurisdictionCode: "CN", urgencyCode: "NORMAL" };
it("keeps the two names separate and normalizes a Chinese mobile without creating a case category", () => {
  const body = captureBody(source, { contactName: "王女士", customerName: "华启制造", phone: "138 0000 0000", legalNeedSummary: "委托需求" }, "stable-key", new Date("2026-09-14T00:00:00Z"));
  expect(body).toMatchObject({ contactName: "王女士", customerName: "华启制造", phone: "+8613800000000", sourceRecordKey: "stable-key" });
  expect(body).not.toHaveProperty("capturedName");
  expect(body).not.toHaveProperty("caseType");
  expect(body.capturedAt).toBe("2026-09-14T00:00:00.000000Z");
});
it("allows missing contact details but rejects missing needs, controls and ambiguous phones", () => {
  expect(captureBody(source, { legalNeedSummary: "待联系" }, "key")).not.toHaveProperty("phone");
  for (const fields of [{ legalNeedSummary: " " }, { legalNeedSummary: "需求", contactName: "姓名\n" }, { legalNeedSummary: "需求", phone: "12345" }])
    expect(() => captureBody(source, fields, "key")).toThrow();
});
