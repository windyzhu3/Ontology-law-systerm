# T07 runtime integration agreement

K2 approved; no new navigation/style or business scope. Shared current worktree; no commits/resets/push. Parent owns command authorization, HTTP wiring, integrated QA. Business implementer owns quote transaction service + schema additions + task persistence; frontend implementer owns quote workcard + API client + UI integration. All collaborators coordinate interface changes here first.

## Wire contract

GET /api/v1/opportunities/{id}/quotes -> context map:
`opportunity:{id,revision}`, `responsibilityBasis:{id,revision}`, `customerConfirmation:{id,revision}|null`, `draft:{selector:{id,revision},document}|null`, `quote:{selector:{id,hash},version,document,totalMinor,validUntil}|null`, `workflow:{selector:{id,revision},stage,ownerAppointmentId,task:{id,revision}|null,nextCheckAt?,message?}|null`, `allowedActions:string[]`, `history:array`, `materials:array` (exact T06 selectors), `approvers:array` (exact appointed people), `customerName`, `readonly`, `notice?`.
Stages: PREPARE, SUBMIT_APPROVAL, AWAIT_APPROVAL, DELIVER, AWAIT_REPLY, FOLLOW_UP, CLARIFY_REPLY, SALES_DISPOSITION, ACCEPTED, RETURNED, OWNER_EXCEPTION. Null workflow = prepare.

POST same base plus /draft, /form, /approval-requests, /decisions, /deliveries, /responses. Header Idempotency-Key as current commands. Body all contain `expectedOpportunityRevision`, `responsibilityBasis`, `customerConfirmation`, `expectedDraft`, `expectedQuote`, `expectedWorkflow`, `values`. Mutable/immutable revision selectors use {id,revision}; expectedQuote uses {id,hash}; nullable expected refs. Path adds opportunityId to command payload. values for draft/form = commercial draft document (form may empty to use saved exact draft); request approval values empty; decision values {decision:APPROVED|RETURNED,reason}; delivery values {recipientParticipation:{id,revision},recipient,channel,occurredAt,evidence:{id,revision}}; reply values {kind:ACCEPTED|NOT_ACCEPTED|REJECTED|AMBIGUOUS,statement,occurredAt,nextCheckAt,evidence:{id,revision}}. Response uses normal command receipt {commandId,outcome,resultFact...} (frontend existing receipt parser) and refresh context only after confirmed result. Unknown only polls original receipt.

Draft document keys currency(CNY), scope, lines:[{description,amountMinor,discount}], paymentTerms, validUntil(UTC instant), conditionalFee:null|{basis,rateBasisPoints,capMinor}. Partial drafts allowed. Full form requires QuotePackage validation. Conditional fees distinct from fixed total. No arbitrary approval thresholds.

GET /api/v1/opportunities/{id}/quotes/{quoteId}/document returns authorized downloadable representation of exact quote. Download != delivery.

## Backend service seam

Public `QuoteWorkflowService` in opportunity package with `databaseBacked(protection,codec,ports)` or explicit class constructor. Provide `context(Connection,Actor,UUID)` -> Map, `execute(Connection,String,Actor,Map<String,Object>)` -> Subject, `protectedFacts(Connection,UUID,UUID)` -> List<Subject> without decrypting. Parent maps returned exact fact to event and command receipt. Parent performs authorization before service reads/decrypts; service rechecks current actor/owner/state and all refs under opportunity lock. Use full transaction rollback on failure. Business may request interface edits via parent before implementing incompatible choices. Command type names: SAVE_QUOTE_DRAFT, FORM_QUOTE, REQUEST_QUOTE_APPROVAL, RECORD_QUOTE_DECISION, RECORD_QUOTE_DELIVERY, RECORD_QUOTE_RESPONSE. Quote draft form and all subsequent commands expose exact append-only result fact, no generic JSON workflow substitute for original quote truth. Task changes use original TaskOccurrence, preserve WAITING SLA/history and no duplicate active responsibility.

Scope: accepted quote produces traceable contract preparation source boundary (no premature contract task); contract drafting/signing is later T08. Refusal not automatic opportunity closure. No AI/automatic provider sends/new management navigation.


2026-09-20 integration clarification: API-owned QuoteWorkflowPorts composes Responsibility, Execution and Evidence public owner ports; Opportunity must not depend on those modules. records is an immutable approval/delivery/response history array with exact quote hash, result selector, actor and event/recording times. Manual delivery chooses an explicit CLIENT participation and stable channel code IN_PERSON or MANUAL_ELECTRONIC. Quote formation restores the K2 full review step; delivery and response remain single-confirmation operations.
