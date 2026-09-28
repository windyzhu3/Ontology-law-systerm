# R2_CAPTURE_FOLLOWUP_V1

Status: IMPLEMENTED for R2 development, within approved HF-12/HF-13. R1 acceptance remains PAUSED; full R2 acceptance is not granted.

## Exact scope

`NextSummary.subjectFactRef` is an optional string matching `^[A-Za-z0-9_-]{43}$`. Only already-authorized, owned, OPEN, actionable `myTasks` entries carry the value. It is computed using the unchanged R1_PUBLIC_FACT_REF_V1 algorithm used by CaptureLead receipts: tenant, principal, appointment, delegation, principal kind, source type and source ID. Revision is deliberately excluded; the existing revision-sensitive CurrentCard.subjectRef and all ETags are unchanged.

The new PublicFactReferences helper extracts the existing algorithm without changing receipt bytes. It is not an authorization API. Disclosure still requires the existing task, current lead, owner, dependencies and final audit checks. No endpoint, command, business status or database table is added.

## Client continuation

After confirmed capture or successful receipt recovery, keep the returned fact reference in the current component/batch memory. Query the existing authorized workcard endpoint, require myTasks and complete reference information, and match only the exact reference. Never match by name, array order, raw UUID, or recommended task.

Successful empty matching means only that the current appointment has no matching actionable task in that response. It does not prove business completion, no owner, failed routing, or waiting status. Missing association fields, read failure, and an empty authorized match have different UI messages. No unauthorized owner details are inferred.

The user explicitly opens an actual matching task. SessionApplication carries its ID only in memory tied to identity epoch/scope; workbench re-reads with the existing taskId selection parameter. Changed permissions or task status follow the existing unavailable-selection notice and authorized recommendation. No command is automatically executed.

Batch results keep each successful row's own reference, including after receipt recovery. Continuation appears only after unresolved/pending submissions are settled; invalid rows remain visibly invalid. No success row is resubmitted. Global recovery handoff requires the existing style of explicit loss confirmation, retains the shared marker and discards only in-memory originals/remaining rows; it does not cancel the business command or automatically reconstruct a batch.

## Compatibility and validation

A named exact projection strips only this optional field before the unchanged historical contract validators. Making it required or widening its shape is rejected. Historical transport remains valid. Frontend refuses incomplete association information rather than reporting no task.

Covered by reference stability/actor separation tests, actual HTTP receipt-to-task assertions, existing task deny/other-owner/WAITING tests, frontend exact matching, stale identity suppression, selected-task revalidation, manual and batch continuation, and recovery marker preservation. Browser evidence is synthetic; this profile does not complete opportunity progression, cross-owner management summaries, or the complete sales MVP.
