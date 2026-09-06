# ADR-0009: P0-01 bounded automatic successor assignment

Status: ACCEPTED
Date: 2026-09-06

User authorization: “修复该合同冲突，给出当前进度”. This accepts the explained minimal exception for both P0-01 duplicate outcomes. The old Task contract forbade `current_assignment_id` updates while its automatic successor required the exact Assignment pointer and its E2E froze only one Lead revision increment.

This decision supersedes only the P0-01 unconditional pointer prohibition, resolution-only CAS column interpretation and literal evaluation-before/after-CAS ordering in R1-TASK-COMPLETION-V1 and the corresponding duplicate-command HTTP prose. It also supersedes ADR-0008's unchanged Task-version statement for this amendment. ADR-0004/0006/0007/0008 and their historical evidence remain intact; all unrelated clauses remain active.

## Controlled decisions

| Key | Value |
|---|---|
| BaselineId | MVP-2026-09-06.1 |
| PreviousBaselineId | MVP-2026-09-05.3 |
| TaskContractId | R1-TASK-COMPLETION-V1.1 |
| ConditionalAssignment | R1_DUPLICATE_AUTOMATIC_ASSIGNMENT_V1 |
| LeadRevisionDelta | 1 |
| CompletionFact | responsibility.decision_record |
| DecisionDigest | EXISTING_RESOLUTION_ONLY |
| EventCount | 1 |
| OutboxCount | 1 |
| SuccessorCount | 1 |
| HttpContractId | R1-HTTP-V1.1 |
| OpenApiVersion | 1.1.0 |
| OperationCount | 15 |
| PublicBearerOperations | 11 |
| PhysicalContract | 52-plus-2-v1.1 |

Both LINK_EXISTING_PARTY and KEEP_SEPARATE retain exact candidate Lead/ACTIVE Party verification and their existing resolution writes. Neither changes candidates, capture fields or V850 ingress fields; KEEP also preserves parsed Party/resolution. Only when the existing next-responsibility policy selects AUTOMATIC with a valid candidate may a NULL current pointer become the exact new OPEN/revision 0 Assignment created by this same command transaction, for the same Tenant and Lead and exact selected Owner. No existing OPEN Assignment may exist. Existing policy ordering, organization root, current authority/no DENY and final identity revalidation apply; arbitrary pointers, reuse, cross-Lead binding, reassignment and caller-selected automatic Owners remain forbidden. Other outcomes preserve the pointer and create zero Assignments.

Under the existing transaction, business fence, Lead/Task/slot locks and final authorization rules, evaluate the resolved prospective Lead facts, create the conditional Assignment, and perform one final controlled Lead CAS combining resolution and pointer changes: old revision to old+1. Persist the exactly one successor with the final post-CAS Lead selector. There is no intermediate committed state, second Lead CAS, +2 revision allowance or outbox-deferred business completion.

DecisionRecord remains the duplicate Task's sole completion Fact and Receipt/Event source. Its established digest input remains the old current Lead selector, exact candidate Lead/Party selectors, mandated resolution values and resulting Lead revision. LINK's `new-values` means only `parsed_party_id`, `party_resolution_code`, `disposition_code`; KEEP retains `KEEP_SEPARATE`. Assignment ID/current pointer is not added to this digest. The independent Assignment Fact is proven by the same-transaction exact Lead pointer, OPEN Assignment Tenant/Lead/Owner/revision binding and successor final selector. No new LeadAssigned event is emitted; existing one Event/one Outbox counts remain exact.

Draft confirmation, Decision, conditional Assignment, final Lead CAS, original Task DONE, exactly one successor, Receipt, Audit, Event and Outbox commit atomically. Rejection retains existing pre-slot/post-slot deltas; technical failure rolls back all writes and replay adds zero. COMMIT acknowledgement uncertainty still resolves by the original key and Receipt.

The [Task matrix](../contracts/r1/R1-TASK-COMPLETION-MATRIX.md) carries exact structured transition and assignment registries enforced by the baseline validator. This is a contract prerequisite, not implemented P0 handlers or completed R1 delivery. No physical manifest, migration V001–V850, schema, API payload, operation shape, event schema, Owner SQL boundary or runtime fence changes. The capacity run remains user-deferred and its separate impossible duplicate-to-ingress vector remains unresolved.
