import { afterEach, describe, expect, expectTypeOf, it, vi } from "vitest";
import type { Client } from "openapi-fetch";

import type { components, paths } from "../generated/api/schema";
import { apiClient } from "./api";

type Schemas = components["schemas"];

const uuid = "01955c2a-7b14-7a12-8f45-4f6e8c9a1002";
const digest = "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD";
const fields: Schemas["FormField"][] = [
  {
    name: "resultCode",
    label: "联系结果",
    control: "SELECT",
    required: true,
    readOnly: false,
    options: [{ value: "CONNECTED_VALID", label: "有效接通", disabled: false }],
  },
];

describe("apiClient", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("exposes the canonical OpenAPI paths through openapi-fetch", () => {
    expectTypeOf(apiClient).toMatchTypeOf<Client<paths>>();
  });

  it("keeps complete-ingress contact alternatives typed", () => {
    expectTypeOf<Schemas["CompleteLeadIngressV1"]>().not.toBeUnknown();
    expectTypeOf<Schemas["CompleteLeadIngressValuesV1"]>().not.toBeUnknown();

    const phoneOnly = {
      draftId: uuid,
      expectedDraftRevision: 1,
      draftDigest: digest,
      phone: "+8613800138000",
      sourceCode: "OWNER_CONFIRMED",
      sourceSummary: "已核验电话。",
    } satisfies Schemas["CompleteLeadIngressV1"];
    const emailOnly = {
      draftId: uuid,
      expectedDraftRevision: 1,
      draftDigest: digest,
      email: "client@example.cn",
      sourceCode: "CUSTOMER_PROVIDED",
      sourceSummary: "客户提供邮箱。",
    } satisfies Schemas["CompleteLeadIngressV1"];
    const both = {
      ...phoneOnly,
      email: "client@example.cn",
    } satisfies Schemas["CompleteLeadIngressV1"];

    // @ts-expect-error At least one of phone or email is required.
    const withoutContact: Schemas["CompleteLeadIngressV1"] = {
      draftId: uuid,
      expectedDraftRevision: 1,
      draftDigest: digest,
      sourceCode: "OWNER_CONFIRMED",
      sourceSummary: "缺少联系方式。",
    };

    expectTypeOf(phoneOnly).toMatchTypeOf<Schemas["CompleteLeadIngressV1"]>();
    expectTypeOf(emailOnly).toMatchTypeOf<Schemas["CompleteLeadIngressV1"]>();
    expectTypeOf(both).toMatchTypeOf<Schemas["CompleteLeadIngressV1"]>();
    void withoutContact;
  });

  it("discriminates legalNeed by contact result", () => {
    expectTypeOf<Schemas["RecordContactResultV1"]>().not.toBeUnknown();
    expectTypeOf<Schemas["RecordContactResultValuesV1"]>().not.toBeUnknown();

    const connected = {
      draftId: uuid,
      expectedDraftRevision: 1,
      draftDigest: digest,
      leadAssignmentId: uuid,
      leadAssignmentRevision: 0,
      contactChannelCode: "PHONE",
      resultCode: "CONNECTED_VALID",
      legalNeed: "请评估劳动合同解除赔偿。",
    } satisfies Schemas["RecordContactResultV1"];
    const notConnected = {
      draftId: uuid,
      expectedDraftRevision: 1,
      draftDigest: digest,
      leadAssignmentId: uuid,
      leadAssignmentRevision: 0,
      contactChannelCode: "PHONE",
      resultCode: "NOT_CONNECTED",
    } satisfies Schemas["RecordContactResultV1"];
    const suspectInvalid = {
      draftId: uuid,
      expectedDraftRevision: 1,
      draftDigest: digest,
      leadAssignmentId: uuid,
      leadAssignmentRevision: 0,
      contactChannelCode: "EMAIL",
      resultCode: "SUSPECT_INVALID",
    } satisfies Schemas["RecordContactResultV1"];

    // @ts-expect-error CONNECTED_VALID requires legalNeed.
    const connectedWithoutLegalNeed: Schemas["RecordContactResultV1"] = {
      draftId: uuid,
      expectedDraftRevision: 1,
      draftDigest: digest,
      leadAssignmentId: uuid,
      leadAssignmentRevision: 0,
      contactChannelCode: "PHONE",
      resultCode: "CONNECTED_VALID",
    };
    // @ts-expect-error Other results forbid legalNeed.
    const notConnectedWithLegalNeed: Schemas["RecordContactResultV1"] = {
      ...notConnected,
      legalNeed: "不应提交。",
    };
    // @ts-expect-error SUSPECT_INVALID also forbids legalNeed.
    const suspectInvalidWithLegalNeed: Schemas["RecordContactResultV1"] = {
      ...suspectInvalid,
      legalNeed: "不应提交。",
    };

    expectTypeOf(connected).toMatchTypeOf<Schemas["RecordContactResultV1"]>();
    expectTypeOf(notConnected).toMatchTypeOf<Schemas["RecordContactResultV1"]>();
    expectTypeOf(suspectInvalid).toMatchTypeOf<Schemas["RecordContactResultV1"]>();
    void connectedWithoutLegalNeed;
    void notConnectedWithLegalNeed;
    void suspectInvalidWithLegalNeed;
  });

  it("keeps all seven command-form values constructible and closed", () => {
    const forms = [
      {
        actionCode: "RESOLVE_DUPLICATE_LEAD",
        schemaVersion: 1,
        values: {
          candidateLeadId: uuid,
          candidateLeadRevision: 1,
          partyId: uuid,
          partyRevision: 1,
        },
        fields,
      },
      {
        actionCode: "COMPLETE_LEAD_INGRESS",
        schemaVersion: 1,
        values: {},
        fields,
      },
      {
        actionCode: "ASSIGN_LEAD",
        schemaVersion: 1,
        values: {},
        fields,
      },
      {
        actionCode: "RECORD_ROUTING_DISPOSITION",
        schemaVersion: 1,
        values: {},
        fields,
      },
      {
        actionCode: "ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST",
        schemaVersion: 1,
        values: { causalDecisionId: uuid, causalDecisionHash: digest },
        fields,
      },
      {
        actionCode: "RECORD_CONTACT_RESULT",
        schemaVersion: 1,
        values: { leadAssignmentId: uuid, leadAssignmentRevision: 0 },
        fields,
      },
      {
        actionCode: "REVIEW_LEAD_VALIDITY",
        schemaVersion: 1,
        values: { triggeringContactResultId: uuid, triggeringContactResultHash: digest },
        fields,
      },
    ] satisfies Schemas["CommandForm"][];

    void forms;
  });

  it("narrows successful receipts to each operation's completion fact", () => {
    type CaptureReceipt = paths["/api/v1/leads"]["post"]["responses"][201]["content"]["application/json"];
    type ContactReceipt = paths["/api/v1/tasks/{taskId}/commands/record-contact-result"]["post"]["responses"][200]["content"]["application/json"];
    type ReopenReceipt = paths["/internal/v1/tasks/commands/reopen-due-contact-tasks"]["post"]["responses"][200]["content"]["application/json"];

    expectTypeOf<CaptureReceipt["outcome"]>().toEqualTypeOf<"SUCCEEDED" | "NO_CHANGE">();
    expectTypeOf<CaptureReceipt["resultFact"]["factType"]>().toEqualTypeOf<"LEAD">();
    expectTypeOf<ContactReceipt["resultFact"]["factType"]>().toEqualTypeOf<"LEAD_CONTACT_RESULT">();
    expectTypeOf<ReopenReceipt["resultFact"]["factType"]>().toEqualTypeOf<"TASK_OCCURRENCE">();
    expectTypeOf<Extract<CaptureReceipt, { outcome: "REJECTED" }>>().toBeNever();

    type RecoveredReceipt = Schemas["CommandReceipt"];
    type RecoveredSuccess = Extract<RecoveredReceipt, { outcome: "SUCCEEDED" | "NO_CHANGE" }>;
    type RecoveredFact = RecoveredSuccess["resultFact"];
    expectTypeOf<Extract<RecoveredReceipt, { outcome: "REJECTED" }>>().not.toBeNever();

    const recoveredLead = { factType: "LEAD", factRef: "lead-ref-opaque", revision: 2 } satisfies RecoveredFact;
    const recoveredDecision = { factType: "DECISION_RECORD", factRef: "decision-ref-opaque", digest } satisfies RecoveredFact;
    const recoveredRejection = {
      commandId: uuid,
      receiptId: uuid,
      outcome: "REJECTED",
      completedAt: "2026-09-05T00:00:00Z",
      rejectionCode: "STALE_TASK",
    } satisfies RecoveredReceipt;
    // @ts-expect-error LEAD completion facts require a revision, never a digest.
    const leadWithDigest: RecoveredFact = { factType: "LEAD", factRef: "lead-ref-opaque", digest };
    // @ts-expect-error A completion fact carries exactly one revision-or-digest selector.
    const leadWithBoth: RecoveredFact = { factType: "LEAD", factRef: "lead-ref-opaque", revision: 2, digest };
    // @ts-expect-error Pre-slot validation failures never create a terminal receipt.
    const preSlotRejection: RecoveredReceipt = { ...recoveredRejection, rejectionCode: "VALIDATION_FAILED" };

    void recoveredLead;
    void recoveredDecision;
    void recoveredRejection;
    void leadWithDigest;
    void leadWithBoth;
    void preSlotRejection;
  });

  it("exposes the dedicated routing-review recovery operation", () => {
    type RoutingReopen = paths["/internal/v1/tasks/commands/reopen-due-routing-review-tasks"]["post"];
    expectTypeOf<RoutingReopen>().not.toBeUnknown();
  });

  it("round-trips the maximum safe revision through fetch response and request serialization", async () => {
    const maximumSafeRevision = 9007199254740991;
    const fetchStub = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(input, init);
      expect(await request.clone().json()).toEqual({
        taskId: uuid,
        expectedTaskRevision: maximumSafeRevision,
        waitReceiptId: uuid,
        waitReceiptHash: digest,
        dueCutoff: "2026-09-05T00:00:00Z",
      });
      return new Response(JSON.stringify({
        commandId: uuid,
        receiptId: uuid,
        outcome: "SUCCEEDED",
        completedAt: "2026-09-05T00:00:00Z",
        resultFact: { factType: "TASK_OCCURRENCE", factRef: "task-ref", revision: maximumSafeRevision },
      }), { status: 200, headers: { "content-type": "application/json" } });
    });
    vi.stubGlobal("fetch", fetchStub);

    const { data } = await apiClient.POST("/internal/v1/tasks/commands/reopen-due-contact-tasks", {
      baseUrl: "https://api.example.test",
      fetch: fetchStub,
      params: { header: { "Idempotency-Key": uuid } },
      body: {
        taskId: uuid,
        expectedTaskRevision: maximumSafeRevision,
        waitReceiptId: uuid,
        waitReceiptHash: digest,
        dueCutoff: "2026-09-05T00:00:00Z",
      },
    });

    expect(data?.resultFact.revision).toBe(maximumSafeRevision);
  });
});
