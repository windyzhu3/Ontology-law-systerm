# ADR-0012: Bounded R1 projection readiness protocol

Status: Accepted

Approved: 2026-09-07. Authority: [approved design](../superpowers/specs/2026-09-07-r1-projection-readiness-design.md).

This named successor activates `MVP-2026-09-07.1`, OpenAPI `1.2.0` and HTTP `R1-HTTP-V1.2`. It supersedes only ADR-0008's 15/11/4 operation inventory and the original closure specification §6.3 deployment-readiness/invalidation wording. Historical ADR-0008 and completed implementation evidence retain their original values. Existing public and internal DTOs, public errors/security, fourteen event routes and physical capability remain frozen. This unit activates static contracts only; original Tasks7/8 still implement and verify production behavior, with no backend/SPA/E2E/capacity/release promotion.

## Readiness protocol registry

| Profile | Key | Value |
|---|---|---|
| R1_PROJECTION_READINESS_V1 | BaselineId | MVP-2026-09-07.1 |
| R1_PROJECTION_READINESS_V1 | OpenApiVersion | 1.2.0 |
| R1_PROJECTION_READINESS_V1 | OperationCount | 16 |
| R1_PROJECTION_READINESS_V1 | PublicBearerOperations | 11 |
| R1_PROJECTION_READINESS_V1 | MutualTlsOperations | 5 |
| R1_PROJECTION_READINESS_V1 | ReadinessProfile | R1_PROJECTION_READINESS_V1 |
| R1_PROJECTION_READINESS_V1 | Actor | UNIQUE_TRUSTED_CERTIFICATE_TENANT_SERVICE_APPOINTMENT |
| R1_PROJECTION_READINESS_V1 | Authority | SYSTEM_PROJECTION/R1_PROJECTION_CONSUME/SYSTEM |
| R1_PROJECTION_READINESS_V1 | Grants | EFFECTIVE_DIRECT_EXACT_APPOINTMENT_INDEPENDENT_COMPLETE_PER_ORGANIZATION |
| R1_PROJECTION_READINESS_V1 | ForbiddenSubstitutes | HUMAN,ON_BEHALF,DELEGATION,OBJECT_ALLOW,RECOVERY,LEAD_CAPTURE,STITCHED_GRANTS |
| R1_PROJECTION_READINESS_V1 | CoverageUniverse | TRUSTED_R1_SOURCE_POLICY_INTAKE_UNION_RETAINED_R1_TASK_ASSIGNMENT_OWNER_ORGANIZATIONS |
| R1_PROJECTION_READINESS_V1 | RetainedLineages | DONE,CANCELLED,ASSIGNMENT,DECISION,CONTACT,OPPORTUNITY |
| R1_PROJECTION_READINESS_V1 | CoverageDerivation | OWNER_FACTS_AND_TRUSTED_POLICY_NOT_SERVICE_ORG_GRANT_ROOTS_ALL_TENANT_ORGS_OR_QUEUE_PAGE |
| R1_PROJECTION_READINESS_V1 | UnresolvedAnchor | FAIL_CLOSED |
| R1_PROJECTION_READINESS_V1 | EmptyUniverse | VALID_SERVICE_APPOINTMENT_AND_EFFECTIVE_PROJECTION_GRANT_REQUIRED |
| R1_PROJECTION_READINESS_V1 | Capability | API_QUERY_OWNER_PORTS_SQL_IN_OWNER_INTERNAL_PERSISTENCE |
| R1_PROJECTION_READINESS_V1 | LockOrder | TRUSTED_ACTOR_THEN_SHARED_R1_BUSINESS_TENANT_LOCK_THEN_SHARED_IDENTITY |
| R1_PROJECTION_READINESS_V1 | Evaluation | FINAL_LOCKED_READ_COMMITTED_FRESH_CLOCK_TIMESTAMP |
| R1_PROJECTION_READINESS_V1 | Transaction | LOCKS_THROUGH_SUCCESSFUL_READ_TRANSACTION_COMPLETION_BEFORE_HTTP_SUCCESS |
| R1_PROJECTION_READINESS_V1 | BeforeEvaluation | OBSERVE_COMMITTED_IDENTITY_POLICY_OWNER_CHANGES |
| R1_PROJECTION_READINESS_V1 | PostEvaluation | IN_FLIGHT_MUTATION_AND_NATURAL_EXPIRY_RACE_INCLUDING_RESPONSE_TRANSPORT |
| R1_PROJECTION_READINESS_V1 | Atomicity | NO_ATOMIC_CHECK_AND_CLAIM_OR_INSTANT_CROSS_PROCESS_INVALIDATION |
| R1_PROJECTION_READINESS_V1 | EventSpecificChecks | DENY_CAUSAL_BINDING_SOURCE_HASH_LEASE_MANDATORY_AT_CONSUME |
| R1_PROJECTION_READINESS_V1 | PermitOrder | ACQUIRE_AVAILABLE_EXECUTION_PERMITS_BEFORE_READINESS |
| R1_PROJECTION_READINESS_V1 | NoPermits | NO_READINESS_NO_CLAIM |
| R1_PROJECTION_READINESS_V1 | Serialization | ONE_READINESS_TO_CLAIM_SEQUENCE_PER_TENANT_BINDING |
| R1_PROJECTION_READINESS_V1 | SuccessUse | ACTIVE_REQUEST_ONLY_ONE_IMMEDIATE_CLAIM_UP_TO_PERMITS_MAX_FOUR |
| R1_PROJECTION_READINESS_V1 | Invalidation | AFTER_ONE_CLAIM_INCLUDING_ZERO_ROWS_RETRY_BINDING_CHANGE_ERROR_RESTART |
| R1_PROJECTION_READINESS_V1 | Proof | NO_CACHE_TOKEN_TTL_PERSISTENCE_OR_PREAUTHORIZED_LOCAL_QUEUE |
| R1_PROJECTION_READINESS_V1 | Timeout | EXISTING_10_SECONDS_DISCARD_LATE_OR_CANCELLED_RESPONSE |
| R1_PROJECTION_READINESS_V1 | PreflightFailure | NO_CLAIM_NO_ATTEMPT_INCREMENT |
| R1_PROJECTION_READINESS_V1 | PreflightUnauthorized | 401_403_FREEZE_BINDING |
| R1_PROJECTION_READINESS_V1 | OtherFailure | WITHHOLD_CLAIM_BOUNDED_EXISTING_BACKOFF_NO_BATCH_PROBE |
| R1_PROJECTION_READINESS_V1 | ConsumeUnauthorized | 401_403_FREEZE_NO_ACK_NO_FAILURE_CAS |
| R1_PROJECTION_READINESS_V1 | GenerationGuard | REJECT_OLDER_SUCCESS_AFTER_NEWER_AUTHORIZATION_FAILURE |
| R1_PROJECTION_READINESS_V1 | Resume | NEW_INITIATED_SUCCESS_AGAINST_REPAIRED_BINDING_ONLY |
| R1_PROJECTION_READINESS_V1 | ExistingClaims | ATTEMPT_RETAINED_REAP_BELOW_EIGHT_PENDING_AT_EIGHT_EXHAUSTED |
| R1_PROJECTION_READINESS_V1 | Reaping | FROZEN_RETRY_DELAY_NO_AUTOMATIC_EXHAUSTED_REDRIVE |
| R1_PROJECTION_READINESS_V1 | Cache | ALL_RESPONSES_NO_STORE_NO_ETAG_NO_304 |
| R1_PROJECTION_READINESS_V1 | Disclosure | SAFE_PROBLEM_ONLY_NO_ORG_GRANT_SOURCE_BUSINESS_EXCEPTION_CREDENTIAL_LOGS |
| R1_PROJECTION_READINESS_V1 | Delta | SLOT_RECEIPT_AUDIT_EVENT_OUTBOX_BUSINESS_ZERO |
| R1_PROJECTION_READINESS_V1 | Worker | EXECUTION_ONLY |
| R1_PROJECTION_READINESS_V1 | AppRole | API_OR_WORKER_EXCLUSIVE_ONE_MODULAR_MONOLITH_JAR_ONE_RESPONSIVE_SPA |
| R1_PROJECTION_READINESS_V1 | SchemaCount | 13 |
| R1_PROJECTION_READINESS_V1 | ApplicationTableCount | 52 |
| R1_PROJECTION_READINESS_V1 | TechnicalTableCount | 2 |
| R1_PROJECTION_READINESS_V1 | PhysicalCapability | 52-plus-2-v1.2 |
| R1_PROJECTION_READINESS_V1 | FrozenArtifacts | V001_V860_MANIFEST_FIELD_CONTRACT_GRANTS_JOOQ_EVENT_ROUTES_14 |

## API decision and coverage

GET `/internal/v1/projections/r1/readiness` (`checkR1ProjectionReadiness`) accepts only the existing unique mTLS certificate binding; no body, parameters, Tenant or authority selector. It returns 204 without a body. Every response has `Cache-Control: no-store`; ETag and 304 are forbidden. Its safe existing Problem codes are exactly 400 VALIDATION_FAILED, 401 UNAUTHENTICATED, 403 NOT_AUTHORIZED, 429 RATE_LIMITED, 500 INTERNAL_ERROR, 503 SERVICE_UNAVAILABLE. Missing Grant, incomplete coverage and inactive/expired SERVICE, Appointment or organization are 403. Malformed configuration or technical evaluation failure never becomes success. Responses and logs expose no organization/Grant identifiers, source selectors, business fields, raw exceptions or credentials. No new command, authority or slot is created, and Slot/Receipt/Audit/Event/Outbox/business deltas are zero.

After trusted Actor validation, API takes shared Tenant `R1_BUSINESS_TENANT_LOCK`, then shared identity lock, and re-reads authority and coverage with fresh database `clock_timestamp()` under READ COMMITTED. Locks last through successful read-transaction completion; HTTP success follows completion. Existing QUERY capability reaches narrow Fact Owner ports; SQL and generated types stay in each Owner's internal.persistence. Worker receives only the HTTP outcome.

Coverage is the deduplicated union of current trusted R1 source-policy intake organizations and real R1 Task/Assignment Owner organizations needed by all fourteen frozen event routes. It includes retained DONE/CANCELLED Tasks and retained Assignment/Decision/Contact/Opportunity lineages referenced by late notifications. Owner facts and trusted policy resolution determine the universe; the SERVICE organization, selected Grant roots, every Tenant organization indiscriminately or the current first queue page cannot replace it. Required unresolved anchors fail closed. New source/Owner organizations committed before final evaluation must be observed.

Each organization requires at least one independently complete, effective direct Grant of the exact SERVICE Appointment for `SYSTEM_PROJECTION / R1_PROJECTION_CONSUME / SYSTEM`. Different complete Grants may cover different organizations; incomplete fields cannot be stitched. HUMAN, onBehalf, delegation, OBJECT ALLOW, recovery and LEAD_CAPTURE are not substitutes. An empty universe still requires a valid SERVICE/Appointment and effective projection Grant. This proves organization coverage only; event-specific DENY, causal binding, source/hash integrity and lease validity remain mandatory at consume, with fourteen routes and zero business delta.

## Worker use and temporal limit

Worker first acquires available execution permits. No permits means no readiness request and no claim. Each Tenant binding serializes readiness through its following bounded claim. Only success of the active request permits one immediate claim call for at most the acquired permits and never more than four rows. Discard that success after the call, including zero rows, and on retry, binding change, failure or restart. No reusable proof, cache, token, TTL, persistence or local queue of pre-authorized batches exists.

The decision point is the final locked database-time evaluation, not Worker receipt or claim commit. Committed earlier changes must be visible; later locked writers wait for API transaction completion. Identity/policy/Owner changes and natural expiry afterward, including during response transport, remain an explicitly accepted in-flight race. There is no atomic check-and-claim or instantaneous cross-process invalidation guarantee. Each later batch needs a new decision; every claimed event receives full current consume authorization and integrity checks. Existing HTTP timeout remains ten seconds; late/cancelled responses are discarded.

Failed/timed-out readiness causes no claim and no attempt increment. Readiness 401/403 freezes the binding; other failures withhold claiming with bounded existing backoff. A current batch is never used as the readiness probe. Consume 401/403 freezes further claims immediately, without ack or failure-CAS. An in-process generation/freeze guard rejects an older success after a newer authorization failure. Only a newly initiated successful check against the repaired binding resumes work. Existing claims keep their incremented attempts and are reaped normally: below eight to PENDING with frozen retry delay; eight to EXHAUSTED. No automatic EXHAUSTED redrive or waived authorization-failure attempt.

Original Task7 owns the evaluator, Owner read ports, real mTLS client and Worker gate; Task8 owns production resolver/controller/security/role assembly. Static validation of this registry proves contract consistency only, not those runtime implementations.
