# R2_LEAD_INTAKE_SOURCES_V1

Exact additive successor to the frozen R1 transport and R2 task selection profile.

GET `/api/v1/leads/intake-sources` (`getLeadIntakeSources`, Leads tag) returns only configured metadata for sources freshly authorized for the active own HUMAN appointment under LEAD_CAPTURE. It rejects delegated and SERVICE actors. It never reads or exposes Lead facts and performs no capture command.

The closed `LeadIntakeSourcesV1` envelope contains required `sources` (0..50). Every closed `LeadIntakeSourceV1` item requires `sourceAccountCode`, `displayName`, `sourceChannelCode`, `serviceCategoryCode`, `jurisdictionCode`, and `urgencyCode`. sourceAccountCode preserves registered mixed-case ASCII identifiers with `^[A-Za-z][A-Za-z0-9_]{0,63}$`; the other four codes reuse uppercase Code64 and displayName reuses SafeText200. Appointment and on-behalf headers reuse existing components; Bearer is the only security scheme. Successful responses declare Cache-Control no-store; the implementation applies no-store to all responses. Malformed selectors use the existing 400 validation response. Existing 400/401/403/503 error components are reused.

`scripts/baseline/r2_intake_sources_contract.py` validates the complete path and both schemas by type-sensitive exact equality before removing them. Partial activation fails closed. The task-selection projection composes this validator before unchanged frozen R1 hashes and inventories. No frozen hash or original schema is changed. Historical documents with neither addition remain accepted. Mutation tests exercise source limits, authority, partial activation, no-store, unknown fields, scalar types, and unchanged R1 protections.
