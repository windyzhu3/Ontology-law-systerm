import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { OpportunityProgressForm } from "./OpportunityProgressForm";
import { opportunityCandidate, opportunityConfirmation, validOpportunityDraft } from "./opportunityProgress";
import { digest, taskId } from "../../test/fixtures";

const now = new Date("2026-09-14T08:00:00Z");
const values = { progressTypeCode: "PHONE_CONNECTED" as const, progressSummary: "客户确认范围", occurredAt: "2026-09-14T07:00:00Z", nextCheckAt: "2026-09-15T07:00:00Z" };
const draft = { draftId: taskId, draftRevision: 2, actionCode: "RECORD_OPPORTUNITY_PROGRESS", schemaVersion: 1, values, digest, updatedAt: now.toISOString(), editable: true };

describe("opportunity progress candidate", () => {
  it("normalizes equivalent time representations without losing microseconds", () => {
    const result = opportunityCandidate({ ...values, occurredAt: "2026-09-14T07:00:00.12Z", nextCheckAt: "2026-09-15T07:00:00.000000Z" }, now);
    expect(result.values.occurredAt).toBe("2026-09-14T07:00:00.120Z");
    expect(result.values.nextCheckAt).toBe(values.nextCheckAt);
    expect(() => opportunityConfirmation(draft, { ...values, occurredAt: "2026-09-14T07:00:00.000Z" }, now)).not.toThrow();
    expect(() => opportunityCandidate({ ...values, occurredAt: "2026-09-14T08:00:00.000001Z" }, now)).toThrow();
  });
  it("normalizes human input without changing the exact occurrence time", () => {
    const result = opportunityCandidate({ ...values, progressSummary: "  客户\r\n确认范围  ", occurredAt: "2026-09-14T07:00:00.123456Z" }, now);
    expect(result.values.progressSummary).toBe("客户\n确认范围");
    expect(result.values.occurredAt).toBe("2026-09-14T07:00:00.123456Z");
  });
  it.each([
    { progressTypeCode: "NOT_CONNECTED" }, { progressTypeCode: "QUOTE_SENT" },
    { businessCategory: "execution" }, { progressSummary: " " },
    { progressSummary: "文".repeat(2001) }, { progressSummary: "\ud800" },
    { occurredAt: "2026-09-14T09:00:00Z" }, { nextCheckAt: now.toISOString() },
    { occurredAt: "2026-02-30T07:00:00Z" }, { occurredAt: "2026-09-14T07:00:00.1234567Z" },
  ])("blocks incomplete or out-of-scope values %j", patch => {
    expect(() => opportunityCandidate({ ...values, ...patch }, now)).toThrow();
  });
  it("confirms only the exact editable saved draft and refuses changed values", () => {
    expect(opportunityConfirmation(draft, values, now)).toEqual({ ...values, draftId: taskId, expectedDraftRevision: 2, draftDigest: digest });
    expect(() => opportunityConfirmation(draft, { ...values, progressSummary: "changed" }, now)).toThrow(/保存/);
    expect(() => opportunityConfirmation({ ...draft, editable: false }, values, now)).toThrow();
    expect(() => opportunityConfirmation(draft, values, new Date("2026-09-16T08:00:00Z"))).toThrow();
  });
  it("reads old drafts without treating an elapsed follow-up as malformed", () => {
    expect(validOpportunityDraft(draft)).toBe(true);
    expect(validOpportunityDraft({ ...draft, draftRevision: Number.MAX_SAFE_INTEGER + 1 })).toBe(false);
    expect(validOpportunityDraft({ ...draft, secret: "private" })).toBe(false);
    expect(validOpportunityDraft({ ...draft, values: { ...values, progressSummary: " padded " } })).toBe(false);
  });
});

it("uses the approved three business controls, with no category or internal selectors", () => {
  const onChange = vi.fn();
  const { rerender } = render(<OpportunityProgressForm values={values} disabled={false} onChange={onChange} />);
  expect(screen.getAllByRole("option")).toHaveLength(6);
  expect(screen.queryByLabelText(/业务分类|draftId|发生时间/)).not.toBeInTheDocument();
  expect(screen.queryByRole("button")).not.toBeInTheDocument();
  fireEvent.change(screen.getByLabelText(/进展摘要/), { target: { value: "客户确认材料" } });
  expect(onChange).toHaveBeenCalledWith("progressSummary", "客户确认材料");
  const next = screen.getByLabelText(/下一次跟进时间/);
  expect(next).toHaveAttribute("type", "datetime-local");
  fireEvent.change(next, { target: { value: "2026-09-16T15:30" } });
  expect(onChange).toHaveBeenLastCalledWith("nextCheckAt", new Date(2026, 8, 16, 15, 30).toISOString());
  rerender(<OpportunityProgressForm values={values} disabled onChange={onChange} />);
  expect(screen.getByRole("combobox")).toBeDisabled();
  expect(screen.getByRole("textbox")).toBeDisabled();
  expect(next).toBeDisabled();
});
