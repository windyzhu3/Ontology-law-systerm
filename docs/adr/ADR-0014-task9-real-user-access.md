# ADR-0014: Task9 real user access contract successor

Status: Accepted
Date: 2026-09-08
Semantic baseline: MVP-2026-09-08.2
HTTP contract: R1-HTTP-V1.4
Command contract: R1-COMMAND-POLICY-EVENT-V1.3
Workbench contract: R1-WORKBENCH-V1.2
Identity contract: R1-IDENTITY-ACCESS-V1.0
OpenAPI version: 1.3.0
Physical capability: 52-plus-2-v1.2

## Decision and exact supersessions

The approved [Task9 design](../superpowers/specs/2026-09-08-task9-real-user-access-design.md) activates the closed [Identity contract](../contracts/r1/R1-IDENTITY-ACCESS-CONTRACT.md) and its exact OpenAPI operations/DTOs. This is contract activation only; it does not claim implemented endpoints, a deployed IdP, new accounts or runtime acceptance.

1. CURRENT-MVP-BASELINE application-topology's identity-production-CRUD exclusion and Workbench V1.1 introduction/implementation boundary are superseded only for ADM-01–04 HUMAN create/read/controlled lifecycle and real login. ADM-05–07, SERVICE management, HR, dynamic policy and R2 remain excluded.
2. ADR-0004/ADR-0008/ADR-0012/ADR-0013 inventory and HTTP operation counts are superseded by exactly37 operations,32 public Bearer and5 internal mTLS, including the approved21 additions. Existing16 methods/paths/security are preserved. Keycloak protocol endpoints and offline bootstrap are excluded from the business OpenAPI count.
3. Existing per-HUMAN deployment Registration is superseded by fixed issuer/provider→Tenant binding and database Principal subject-HMAC mapping. SERVICE/mTLS retains exact registrations. Arbitrary Tenant/role claims and first-login auto-binding remain forbidden.
4. ADR-0006 Actor's mandatory Appointment remains mandatory for business/admin commands. Only the new R1_AUTHENTICATED_IDENTITY_V1 self query runs without an Appointment, with physically nullable Audit appointment and an exact authenticated-own-Principal disclosure exception. No fake business Actor or synthetic Principal is introduced.
5. HTTP blanket internal-selector prohibition is narrowed only to named admin ID selectors in closed DTOs/projections. No Tenant/caller authority input is allowed, including optional/nested fields. Principal has no organization; root management scope is required for Tenant-level Principal and new-Principal candidates.
6. ADR-0007 command allowlist/envelope exclusion is superseded only by fourteen static INTERNAL_ADMIN/HUMAN/DIRECT Identity commands; grant targets are the exact eight R1 HUMAN codes. Original nine business DTOs, seven Task types/completion Facts and fourteen event descriptors remain unchanged. Identity writes emit no R1 event or Worker route.
7. ADR-0013 receipt metadata/current authorization is extended only by the named Identity receipt profile and typed Identity Fact results. Original Actor, current management authority/scope, Audit-before-disclosure and bounded metadata rules remain mandatory. NOT_FOUND is restored in TerminalRejectionCode. Old business receipt recovery remains governed by ADR-0013; no backfill or repair endpoint.
8. The frontend memory-only credential boundary remains. Its old memory-only recovery clue boundary is superseded solely by the closed four-field sessionStorage marker (one per tab,24 hours), with no payload/ETag/credential. Same-Actor token rotation preserves identity epoch and in-flight state; identity change clears it.
9. Runtime Identity writes join the exclusive R1_BUSINESS_TENANT_LOCK→command UUID fence→identity exclusive→ordered Identity rows protocol. Existing business lock order and rollback/commit decision point remain unchanged. Owner dependency reads never authorize Identity SQL into business tables or Task repair.
10. R1_IDENTITY_BOOTSTRAP_V1 is an offline SYSTEM authorization-origin exception on the newly bound HUMAN founder, not a new Principal kind or general SYSTEM bypass. Exact initial delta, self-grant exception, operator assertion and durable Slot/Receipt/Audit closure are in the Identity contract. It requires neither new tables nor new GRANTs; it never creates a public endpoint or recovery backdoor.
11. The single business database statement now means one application database with13 schemas and52 application+2 technical tables. Keycloak separately owns external identity storage and credentials. This explicit infrastructure dependency is not a second business SPA, third APP_ROLE or added business table. APP_ROLE=api|worker remains exclusive in one modular-monolith Jar.

## Physical compatibility and operation boundary

Existing V010 Identity rows, V020 nullable audit appointment, V040 exact Receipt Fact and V830 COMMAND/QUERY/AUDIT capabilities support the frozen shapes. AuthorityGrant's nonnull granted_by_appointment_id is the newly created founder Appointment for the offline initial four grants. Bootstrap Audit identifies the newly bound founder target and actual operator assertion; it does not misrepresent a pre-existing authenticated browser Actor. Same complete-manifest replay is verification with zero additions. Old migrations/manifest/jOOQ and physical52-plus-2-v1.2 remain byte-identical; no forward GRANT is necessary. Any later missing capability or need for a new table stops implementation for a separately named decision.

OIDC PKCE, in-memory tokens, rotation,5/30/480-minute limits,2-second uncached introspection and exact recovery storage bounds are frozen by the Identity contract and [deployment lock](../../deploy/identity/README.md). Deploy only pinned versions/digests, fixed TLS/Origin/CSP and injected secrets. This ADR does not claim their runtime validation.

## Acceptance and consequences

Static tests parse actual paths/security and require37/32/5 and unique approved21 additions; mutation tests reject operation/security/Tenant/terminal-code/authority/self-profile drift. Previous business DTO/event equality, migration bytes and generated transport compile remain required. Keycloak adds a measured external dependency and operational responsibility; no implementation may hide this inside the old one-database count.

Task9.0 historical75 tests and earlier API evidence remain historical. Expanded Task9 needs all [acceptance cases](../acceptance/2026-09-08-task9-real-user-access-acceptance.md), real login/admin flows and human UAT. This contract-only step does not complete Task9, Task10, capacity, production login or R1 release.
