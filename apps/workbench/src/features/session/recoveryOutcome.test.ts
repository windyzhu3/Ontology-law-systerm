import { expect, it } from "vitest";
import { provenWriteOutcome } from "./recoveryOutcome";
const marker = {
  commandId: "019c7000-0000-7000-8000-000000000001",
  commandType: "RECORD_CONTACT_RESULT",
  actorScopeKey: "ask1.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  recordedAt: new Date().toISOString(),
};
const validation = {
  type: "https://example.test/problems/validation-failed",
  title: "校验失败",
  detail: "请核对内容。",
  instance: "/problems/occurrence",
  status: 400,
  code: "VALIDATION_FAILED",
  retryPolicy: "SAME_KEY_AFTER_FIX",
  fieldErrors: [{ pointer: "/resultCode", code: "REQUIRED", detail: "必填" }],
};
it("recognizes exact pre-slot validation and terminal stale key policies", () => {
  expect(provenWriteOutcome(validation, 400, marker)).toBe(true);
  const { fieldErrors, ...base } = validation;
  expect(
    provenWriteOutcome(
      {
        ...base,
        status: 412,
        code: "STALE_TASK",
        retryPolicy: "NEW_KEY_AFTER_REFRESH",
        currentETag: {
          resourceKind: "TASK",
          value: '"task.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"',
        },
      },
      412,
      marker,
    ),
  ).toBe(true);
});
it.each([
  { code: "VALIDATION_FAILED" },
  { ...validation, retryPolicy: "NO" },
  { ...validation, status: 200 },
  { ...validation, unknown: "data" },
  { ...validation, fieldErrors: [] },
  {
    ...validation,
    fieldErrors: [{ pointer: "bad", code: "REQUIRED", detail: "必填" }],
  },
])("does not infer noncommit from malformed Problems", (value) =>
  expect(provenWriteOutcome(value, 400, marker)).toBe(false),
);
it.each([401, 403, 404, 429, 500, 503])(
  "does not clear an unresolved operation on %i",
  (status) =>
    expect(provenWriteOutcome({ ...validation, status }, status, marker)).toBe(
      false,
    ),
);
it("does not treat a Problem from another command's error registry as proof", () => {
  const { fieldErrors, ...base } = validation;
  expect(
    provenWriteOutcome(
      {
        ...base,
        status: 422,
        code: "SOURCE_INTAKE_OWNER_UNRESOLVED",
        retryPolicy: "NEW_KEY_AFTER_ADMIN_FIX",
      },
      422,
      marker,
    ),
  ).toBe(false);
  expect(
    provenWriteOutcome(
      {
        ...base,
        status: 428,
        code: "DRAFT_PRECONDITION_REQUIRED",
        retryPolicy: "SAME_KEY_AFTER_FIX",
        currentETag: {
          resourceKind: "DRAFT",
          value: '"draft.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"',
        },
      },
      428,
      marker,
    ),
  ).toBe(false);
});
