# R2_LEAD_CAPTURE_NAMES_V1

Status: IMPLEMENTED in R2 development; release acceptance not granted.

CaptureLeadV1 adds optional customerName and contactName, each exactly SafeText200. capturedName keeps its historical meaning. Neither name becomes required; neither is silently copied into the other or into capturedName. No new business classification, endpoint or command is introduced.

The exact OpenAPI projection removes only these two optional references before existing frozen contract checks. Partial additions, required names, widened types and mutations of historical fields fail validation. Generated TypeScript reflects the same schema.

Backend persistence is the separately named R2 schema successor V870 (see database/schema-contract-52-plus-2/R2-SCHEMA-SUCCESSOR.md): independent ciphertext and AAD, immutable optional fields, exact current-owner authorized reading. Current card and My Tasks display the customer name first, then contact name or historical name; the current card subtitle labels the contact independently.

Validation: capture-name contract mutation tests; PostgreSQL name/security/replay tests; R1CommandHttpIT captures both names and reads both from the resulting selected task. This is not R1 acceptance or a claim that the full R2 sales chain is complete.
