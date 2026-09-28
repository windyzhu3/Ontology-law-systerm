import { expect, expectTypeOf, it } from "vitest";
import type { components, paths } from "../generated/api/schema";
type S = components["schemas"];
it("keeps opportunity progress payload and result separate from lead forms", () => {
  const values: S["OpportunityProgressValuesV1"] = { progressTypeCode: "PHONE_CONNECTED", progressSummary: "Confirmed next follow-up", occurredAt: "2026-09-14T00:00:00Z", nextCheckAt: "2026-09-15T00:00:00Z" };
  const draft: S["SaveOpportunityProgressDraftV1"] = { actionCode: "RECORD_OPPORTUNITY_PROGRESS", schemaVersion: 1, values };
  expect(draft.values).toEqual(values);
  expectTypeOf<S["OpportunityProgressCommandReceiptV1"]["resultFact"]["factType"]>().toEqualTypeOf<"OPPORTUNITY_PROGRESS">();
  expectTypeOf<paths["/api/v1/tasks/{taskId}/opportunity-progress-draft"]["put"]>().not.toBeUnknown();
  expectTypeOf<paths["/api/v1/tasks/{taskId}/commands/record-opportunity-progress"]["post"]>().not.toBeUnknown();
});
