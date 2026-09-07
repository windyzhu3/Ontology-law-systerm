# R1 projection readiness — approved bounded amendment

Date: 2026-09-07. User approved the concrete fifth-internal-operation and authorization-race amendment, and requested completion followed by continuation of the existing business closure plan. This document records that approved design; it is not a new product proposal.

## 1. Scope and authority

Add ADR-0012 with a named supersession of ADR-0008's operation count and closure specification §6.3 deployment-readiness/invalidation wording. Activate `MVP-2026-09-07.1`, OpenAPI `1.2.0`, and `R1_PROJECTION_READINESS_V1`. The approved HTTP inventory becomes **11 public + 5 internal = 16 operations**. Existing public and internal request/response DTO shapes remain unchanged.

One responsive SPA, one OpenAPI, one modular monolith Jar; `APP_ROLE=api|worker` is exclusive. Worker retains execution-only module/DB capability. Keep 13 schemas, 52 application + 2 technical tables, physical capability `52-plus-2-v1.2`, all V001–V860 bytes, manifest, field contract, database grants and generated jOOQ scope unchanged. No projection store, proof store, message bus, credential issuer, admin UI, new business command, Provider sending, AI, ADM-01–07 or R2+.

Tasks 1–6 remain locally completed; the amendment itself does not implement Task7, expose production HTTP, or promote backend/SPA/E2E/capacity/release evidence. Historical evidence is retained, not relabelled. Capacity environment remains deferred by the user.

## 2. Exact transport

Add `checkR1ProjectionReadiness`: GET `/internal/v1/projections/r1/readiness` in the existing OpenAPI, using only existing `internalMutualTls`. No request body or query parameters, no caller Tenant or authorization selector. The exact Tenant/SERVICE Principal/Appointment comes from the existing unique trusted certificate binding.

Success is HTTP 204, no body, with `Cache-Control: no-store`. Errors use existing safe Problem schemas and codes only: 400 VALIDATION_FAILED, 401 UNAUTHENTICATED, 403 NOT_AUTHORIZED, 429 RATE_LIMITED, 500 INTERNAL_ERROR, 503 SERVICE_UNAVAILABLE. All responses are non-cacheable; no ETag/304. Missing Grant, incomplete scope, inactive or expired SERVICE/Appointment/organization are 403; malformed configuration or failed technical evaluation must not become success. No organization/Grant identifiers, source selectors, business fields, raw exceptions or credentials enter responses/logs.

This is a read-only authorization-preflight operation, not a business command or event consumer. It uses `SYSTEM_PROJECTION / R1_PROJECTION_CONSUME / SYSTEM` with effective direct Grants of the exact SERVICE Appointment. HUMAN, onBehalf, delegation, OBJECT ALLOW, recovery Grants and LEAD_CAPTURE cannot substitute. No new authority code/slot is created. Slot/Receipt/Audit/Event/Outbox and business deltas are all zero.

## 3. API-owned coverage and decision point

The API uses existing QUERY capability and narrow Owner ports. SQL/generated types remain in each Fact Owner's internal.persistence; Worker receives only the HTTP outcome. API first checks the trusted Actor, then takes Tenant shared `R1_BUSINESS_TENANT_LOCK`, then identity shared, and re-reads current coverage facts and authority with fresh database `clock_timestamp()` under READ COMMITTED. Locks remain held through successful read-transaction completion; no HTTP success before that completion.

The coverage universe is the deduplicated union of current trusted R1 source-policy intake organizations and real R1 Task/Assignment Owner organizations required by the fourteen frozen event routes, including retained DONE/CANCELLED Tasks and retained Assignment/Decision/Contact/Opportunity lineages that late notifications can reference. Derive it through Owner facts and trusted policy resolution, never from the SERVICE's own organization, selected Grant roots, all Tenant organizations indiscriminately, or merely the current first queue page. Unresolvable required anchors fail closed. New source/Owner organizations committed before evaluation must be observed.

For each organization, at least one independently complete effective direct Grant of the exact Appointment must cover that organization and the projection authority/slot. Several grants may each fully cover different organizations; fields of incomplete grants must never be stitched. Even an empty fact set cannot bypass a valid SERVICE/Appointment and an effective projection Grant. This proves organization-level coverage at the final locked evaluation point, not event-specific DENY, causal binding, source/hash integrity, or lease validity. Those remain mandatory in consumeR1Projection with its existing fourteen routes and zero-business-delta semantics.

## 4. One check per claim call; explicit temporal boundary

Worker acquires available execution permits before requesting readiness. Each Tenant binding has one serialized readiness-to-claim sequence. Only a successful response from that active request may enable its immediately following bounded claim call (up to the acquired permits, never more than four). A success is discarded after that one call even if it claims zero rows. No proof/cache/token survives another batch, retry, binding change, error, or process restart. No local queue may hold pre-authorized batches. No permits means neither readiness call nor claim.

The authorization decision point is the API's final locked database-time evaluation, not Worker receipt time or the later claim commit. Changes committed before that point must be seen; locked writers arriving later wait for the API transaction. Identity/policy/Owner changes and natural expiry after that point, including during response transport before claim, are explicitly a possible in-flight race. This amendment does not promise instantaneous cross-process invalidation or atomic check-and-claim. HTTP remains bounded by the existing 10-second timeout, and late responses after timeout/cancellation are discarded. No reusable TTL attestation is introduced.

Every later batch obtains a new current decision, so no old success authorizes later batches. Every claimed event still receives full current authorization and integrity checks on consume. Failed/timed-out readiness performs no claim and increments no attempt; 401/403 freezes the binding, and other failures withhold claiming with bounded existing backoff. The current batch is never sent as a substitute readiness probe.

Consume 401/403 immediately freezes further claims and performs neither ack nor failure-CAS. An in-process generation/freeze guard rejects an earlier successful readiness response after a newer authorization failure. Resumption requires a newly initiated successful check against the repaired binding; previous successes cannot unfreeze it. Existing claims still consume their incremented attempt and are reaped normally: below eight to PENDING with the frozen retry delay; at eight to EXHAUSTED. No automatic EXHAUSTED redrive or waived authorization-failure attempt.

## 5. Delivery ownership and verification

Contract amendment unit: ADR/baseline/HTTP/OpenAPI/static profile and exact inventory validators; mutation tests; generated OpenAPI Java/TS compatibility. Preserve all existing DTOs and public security/error inventories. Do not claim runtime behavior from document/string checks.

Original Task7: API coverage service and minimal Owner read ports; real mTLS client method; Worker preclaim gate/generation guard; due discovery, scheduler, fourteen-event consumer and real Outbox state machine. Tests use real PostgreSQL QUERY/WORKER capabilities and real transport fixtures. Required cases: exact vs other Appointment, full vs stitched grants, scope ancestry, inactive/expired identity, distinct Tenant isolation, retained facts and new/removed policy/Owner organizations; before-evaluation mutation versus post-evaluation race and natural expiry; permits first, one success/one claim, zero-result invalidation, timeout/lost/late response, restart, stale success after freeze, fresh resumption; seventh/eighth-attempt failure and zero business delta.

Original Task8: production mTLS resolver, controller/advice and mutually exclusive API/Worker assembly, all sixteen HTTP operations and production fail-closed configuration. Task7 may not hide an unfinished readiness evaluator behind a constant/callback-only test; Task8 assembly is a distinct later acceptance layer.

Original Tasks9–10: existing SPA and real E2E/CI/capacity plan. Only operation inventory and readiness/race assertions receive this named amendment. No unrelated feature additions, merge, push, deployment or readiness promotion is implied.
