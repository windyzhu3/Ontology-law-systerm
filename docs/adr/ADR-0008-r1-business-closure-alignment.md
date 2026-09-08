# ADR-0008: R1 business closure alignment

Status: ACCEPTED
Date: 2026-09-05

This decision supersedes only ADR-0004's 13-operation count and absent due/consume endpoints, ADR-0006's CommandType-only capture envelope, and ADR-0007's HUMAN-only capture and unnamed projection acknowledgement. All other clauses remain active. It adds contracts only; no production Handler is registered and no business delivery state advances.

## Mechanically verified decisions

| Key | Value |
|---|---|
| BaselineId | MVP-2026-09-05.3 |
| OpenApiVersion | 1.1.0 |
| OperationCount | 15 |
| PublicBearerOperations | 11 |
| MutualTlsOperations | 4 |
| CommandPolicyKey | CommandType,PrincipalKind |
| ProjectionStorage | NONE |
| DisclosureClass | R1_CURRENT_WORKCARD_DISCLOSURE_V1 |
| DisclosureReturn | AFTER_AUDIT_COMMIT |
| SensitiveResponseModes | BODY,CACHE_REVALIDATED |
| BusinessFence | R1_BUSINESS_TENANT_LOCK |
| LockOrder | R1_BUSINESS_TENANT_LOCK,LEAD,TASK,COMMAND_SLOT,identity-shared |
| EventRouteCount | 14 |
| ProjectionQueueOwner | R1_PROJECTION |
| ProjectionAuthority | SYSTEM_PROJECTION,R1_PROJECTION_CONSUME,SYSTEM |
| MaxConcurrency | 4 |
| ClaimBatchSize | 4 |
| PollIntervalSeconds | 1 |
| LeaseSeconds | 60 |
| HttpTimeoutSeconds | 10 |
| MaxAttempts | 8 |
| RetryDelays | 1s,5s,30s,2m,10m,30m,2h |
| CapacityProfile | R1-CAPACITY-V1 |

SERVICE capture uses the exact static `R1_TRUSTED_SERVICE_SOURCE_BINDING_V1` binding and an instance envelope included in replay comparison. CurrentCard's seven nonempty cards are typed disclosures: every returned Task, Lead, Owner Appointment/Principal/OrganizationUnit and actual option/fact is a separate source anchor. BODY and CACHE_REVALIDATED responses return only after the corresponding Audit commit. Cache policy is `private, no-cache` with `Vary: Authorization`; ETags depend on Actor scope and source revisions. Clients use request generations/AbortController so an older response cannot overwrite a newer envelope.

Workers require complete `R1_WORKER_TENANT_BINDING_V1` tenant readiness before claiming. Due pagination orders by `(resume_due_at, task_id)`, freezes an observed cutoff, uses an Actor-scoped opaque cursor, and derives a stable UUIDv5 recovery key. Projection consumption re-reads current Owner facts behind the shared business fence, accepts exactly the frozen fourteen event routes, never materializes a projection, and advances only the technical Outbox through fenced CAS. A claim increments revision, fencing token and attempt; expired claims are reaped to PENDING or EXHAUSTED, with eight cumulative attempts and the frozen retry delays above. DELIVERED means successful API validation followed by a valid Worker CAS, not browser refresh or business completion.
