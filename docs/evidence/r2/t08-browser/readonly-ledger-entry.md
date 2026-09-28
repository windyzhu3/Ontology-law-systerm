# Completed contract read entry

2026-09-21. Frontend-only follow-up to the real C40 completed-contract ledger check.

The existing ledger now exposes a secondary “查看合同内容” action independently of canHandle. It refetches the exact opportunity context in ContractRuntimeCard with viewOnly enabled. This applies readonly and removes all offered write actions, including after reload, while using the existing server-authorized exact-version download endpoint. No task, schema, backend route, or permission was added.

Empty owner labels and their separator are hidden in the list/detail; the contract card does not invent an owner for a workflow without an active task or disclosed label.

Regression: completed-record entry was RED before implementation. Three component test files, 23 tests PASS in `.local/t08-readonly-entry-tests.log`. The page integration test selects the completed row, enters the existing readonly card, confirms no form command despite supplied action data, downloads the exact contract/version, verifies the safe PNG filename, and verifies no write call.

Configured review build passed in `.local/t08-readonly-entry-build.log`. The deployable SPA directory is `apps/workbench/dist`. Deployment and browser acceptance remain with the coordinating agent.

Final verification: 70 frontend test files / 795 tests PASS. SPA backed up and copied at 19:15 on 2026-09-21. Real task9-local-contact browser selected C40, opened “查看合同内容”, and displayed the immutable terms, exact document action, passed review, approved legal slot, and explicit unsigned boundary. No editable fields, no write action, no empty owner; viewport width and scrollWidth both 722. Screenshot: `c40-ready-readonly.png`.
