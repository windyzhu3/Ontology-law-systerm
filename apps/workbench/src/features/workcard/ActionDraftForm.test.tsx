import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ActionDraftForm } from "./ActionDraftForm";
import { candidate, parseEnvelope, validReceipt } from "./contract";
import { envelope, selectorId, receipt, digest } from "../../test/fixtures";
describe("static candidate boundaries", () => {
  it("rejects receipt selectors of the wrong kind and unknown terminal outcomes", () => {
    const base = receipt(selectorId);
    expect(
      validReceipt(
        {
          ...base,
          resultFact: {
            factType: "LEAD_CONTACT_RESULT",
            factRef: "safe-result-reference",
            revision: 1,
          },
        },
        selectorId,
      ),
    ).toBe(false);
    expect(
      validReceipt(
        {
          ...base,
          resultFact: {
            factType: "LEAD",
            factRef: "safe-result-reference",
            digest,
          },
        },
        selectorId,
      ),
    ).toBe(false);
    expect(
      validReceipt(
        {
          commandId: selectorId,
          receiptId: selectorId,
          completedAt: "2026-09-08T02:10:00Z",
          outcome: "REJECTED",
          rejectionCode: "UNKNOWN",
        },
        selectorId,
      ),
    ).toBe(false);
  });
  it.each([0, 1, 2, 3, 4, 5, 6])(
    "saves the complete valid DTO for static form %i",
    (index) => {
      const card = envelope(index).currentCard!;
      expect(candidate(card, card.commandForm.values)).toEqual({
        actionCode: card.commandForm.actionCode,
        schemaVersion: 1,
        values: card.commandForm.values,
      });
    },
  );
  it("rejects an unoffered assignee and preserves fixed duplicate selectors", () => {
    const assign = envelope(2).currentCard!;
    expect(() =>
      candidate(assign, {
        ownerAppointmentId: "019c7000-0000-7000-8000-000000000099",
      }),
    ).toThrow(/选项/);
    const duplicate = envelope(0).currentCard!;
    expect(
      candidate(duplicate, {
        ...duplicate.commandForm.values,
        candidateLeadId: "untrusted",
      }).values,
    ).toHaveProperty("candidateLeadId", selectorId);
  });
  it("keeps authorized evidence invisible and unchanged", () => {
    const card = envelope(5).currentCard!;
    card.commandForm.values = {
      ...card.commandForm.values,
      evidenceSubmissionId: selectorId,
    } as typeof card.commandForm.values;
    render(
      <ActionDraftForm
        card={card}
        values={card.commandForm.values}
        disabled={false}
        onChange={() => {}}
      />,
    );
    expect(screen.queryByLabelText("联系证据")).not.toBeInTheDocument();
    expect(
      candidate(card, {
        ...card.commandForm.values,
        evidenceSubmissionId: "untrusted",
      }).values,
    ).toHaveProperty("evidenceSubmissionId", selectorId);
  });
  it.each([
    {
      phone: "13812345678",
      sourceCode: "CUSTOMER_PROVIDED",
      sourceSummary: "已确认",
    },
    {
      email: "broken",
      sourceCode: "CUSTOMER_PROVIDED",
      sourceSummary: "已确认",
    },
    { sourceCode: "CUSTOMER_PROVIDED", sourceSummary: "已确认" },
  ])("blocks incomplete or malformed ingress contact %j", (values) => {
    expect(() => candidate(envelope(1).currentCard!, values)).toThrow();
  });
  it("rejects control characters and oversized legal need", () => {
    const card = envelope(5).currentCard!;
    expect(() =>
      candidate(card, {
        ...card.commandForm.values,
        resultSummary: "bad\nvalue",
      }),
    ).toThrow();
    expect(() =>
      candidate(card, {
        ...card.commandForm.values,
        resultCode: "CONNECTED_VALID",
        legalNeed: "法".repeat(2001),
      }),
    ).toThrow();
  });
  it("removes forbidden legalNeed after changing away from connected", () => {
    const card = envelope(5).currentCard!;
    expect(
      candidate(card, { ...card.commandForm.values, legalNeed: "旧需求" })
        .values,
    ).not.toHaveProperty("legalNeed");
  });
  it.each(["tenantId", "unknownField"])(
    "rejects extra envelope field %s",
    (field) =>
      expect(() =>
        parseEnvelope({ ...envelope(), [field]: "private" }),
      ).toThrow(),
  );
  it("rejects a mismatched action, malformed revision, wrong ETag kind and unrecognized field", () => {
    for (const mutate of [
      (v: ReturnType<typeof envelope>) => {
        v.currentCard!.primaryCommand.code = "ASSIGN_LEAD";
      },
      (v: ReturnType<typeof envelope>) => {
        v.currentCard!.taskRevision = Number.MAX_SAFE_INTEGER + 1;
      },
      (v: ReturnType<typeof envelope>) => {
        v.currentCard!.preconditions.taskETag =
          v.currentCard!.preconditions.subjectETag;
      },
      (v: ReturnType<typeof envelope>) => {
        (v.currentCard!.commandForm.fields[0] as { name: string }).name =
          "secret";
      },
    ]) {
      const data = envelope();
      mutate(data);
      expect(() => parseEnvelope(data)).toThrow();
    }
  });
  it("rejects missing required static controls rather than silently hiding input", () => {
    const data = envelope();
    data.currentCard!.commandForm.fields =
      data.currentCard!.commandForm.fields.filter(
        (f) => f.name !== "resultCode",
      );
    expect(() => parseEnvelope(data)).toThrow();
  });
  it("rejects command completion fact mismatch", () => {
    const data = envelope();
    data.currentCard!.expectedCompletionFact = "DECISION_RECORD";
    expect(() => parseEnvelope(data)).toThrow();
  });
  it("refuses a saved Draft whose fixed selector no longer matches the authorized form", () => {
    const card = envelope(5, true).currentCard!;
    card.commandForm.values = {
      ...card.commandForm.values,
      leadAssignmentRevision: 7,
    } as typeof card.commandForm.values;
    expect(() => candidate(card, card.actionDraft!.values)).toThrow(/刷新/);
  });
});
