# ADR-0015: Preserve Task9 delegated identity access

Status: Accepted
Semantic baseline: MVP-2026-09-08.3
Date: 2026-09-08

Authority: [approved delegated-context amendment](../superpowers/specs/2026-09-08-task9-delegated-context-amendment-design.md), sections 1–7. Task9.2a activates a static contract only; Task9.2–9.6 login, authorization, disclosure, recovery and browser acceptance remain unverified.

## Exact supersession

This replaces only ADR-0014's own-appointment-only entry transport, seven-field SessionContextV1, SELF disclosedSources maximum51, and the interpretation that an initial post-login context mismatch is already an explicit identity switch. Activate Identity V1.1, HTTP V1.5, Workbench V1.3, OpenAPI 1.4.0 and semantic MVP-2026-09-08.3. Command V1.3 retains its complete policy/permission/event registry and links to this selector rule; physical 52-plus-2-v1.2 is unchanged. Prior activation IDs and acceptance evidence remain historical.

## Decision

X-On-Behalf-Appointment-Id is an optional single UUID authentication selector paired with explicit X-Appointment-Id. All32 public Bearer operations declare it: the original11 business operations and getSessionContext accept valid HUMAN delegated context; all20 Identity management operations reject it, preserving HUMAN/own Appointment/DIRECT only. SERVICE rejects the selector; internal mTLS does not consume it or alter its static Actor. Missing header never selects delegation implicitly. Malformed or unpaired transport fails400 VALIDATION_FAILED before slot admission; a well-formed unauthorized, unknown, cross-Tenant or expired choice uniformly fails403 NOT_AUTHORIZED without fallback or existence disclosure. Existing401/503, safe errors, retryPolicy and unknown-commit semantics remain.

SessionContextV1 adds required delegatedAppointmentChoices (0..50 IdentityChoiceV1 id/label only) and required nullable UUID selectedOnBehalfAppointmentId. No own selection means empty delegated choices and null selection. Choices are current, deduplicated by on-behalf Appointment, UUID sorted and bounded; overflow fails safe503, never truncates. Selected IDs must match exactly one choice. A selected delegation yields READY, the full Actor's scope key, independently evaluated workbench entry and false management entry. JSON Schema/generated types do not enforce cross-array/state relationships: Identity V1.1 and runtime acceptance explicitly own those checks.

Identity evaluates only the selected delegate Appointment's existing effective one-hop relations with valid ACTIVE HUMAN parties, appointments, organization chains, direct source Grant, allowed original R1 business authority and covered delegation scope. A candidate is not operation authorization. Existing Runtime/Owner current evidence, both applicable DENY checks, final locked authorization decision and Owner comparison remain mandatory.

SELF remains authenticated actual Principal, SELF_IDENTITY/DIRECT/authorizationFact=null; on-behalf Audit Actor columns stay null. Shared business then identity fences remain held through Audit commit before no-store BODY. SELF disclosedSources has maximum101 exact deduplicated type/id/revision anchors: actual Principal, at most50 own Appointments and at most50 disclosed delegated Appointments. Grant IDs/evidence, Principal IDs, Tenant, expiry, tokens and permission matrices are not response content. Business audits retain the complete actual delegated Actor and current authorization evidence.

The original ask1 tuple and persistent purpose key remain stable across renewal, relogin, restart and replacement evidence for the same parties; Grant IDs and token/expiry/options ordering never enter scope identity. Recovery requires the complete original Actor, NULL-safe on-behalf pair, original metadata integrity and current authorization. Revocation never reopens an old Actor or proves noncommit. The browser keeps only the existing four-field per-tab 24-hour marker. Initial context establishment after relogin is not a confirmed identity switch. Preserve the pending marker while explicit own/delegated choice is unfinished, block writes and mismatched receipt disclosure, and never enumerate Actors to recover. Explicit different-identity abandonment requires confirmation that deleting the local clue does not cancel the operation. Epoch changes clear stale views/candidates/ETags/responses; same-Actor token renewal preserves epoch and poll budgets.

## Unchanged scope and validation

Exactly37 operations /32 public Bearer /5 internal mTLS, original nine request DTOs and their transitive schemas, seven Task types, fourteen business events, original permission sets, Owner/completion facts, lock order, database13 schemas/52+2 tables and generated physical bytes remain. No delegation CRUD, ADM-05–07, accounts, grants, new permissions, tables, services or production deployment are added. Only trusted-Origin CORS may allow the exact selector header, never wildcard.

T9-D01 requires static structural/rejection mutations, generator/compile checks and frozen-business/physical comparison. T9-D02–08 remain separate runtime gates under the approved amendment. Independent static review precedes resuming Task9.2; this ADR does not promote runtime, UAT, capacity, Task10 or release status.
