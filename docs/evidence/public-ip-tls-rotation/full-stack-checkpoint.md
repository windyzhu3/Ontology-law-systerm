# Full-stack checkpoint — 2026-10-08

Status: **INCOMPLETE / NOT DEPLOYMENT READY**.

The original isolated initialization `da6898aa6e024e288dd6cc4fd0d57d03` completed successfully against bundle descriptor `b837dbc9665ec6b7b328d387376355e8db88e2c3d064c9f578d798085d97f92a`. Its original process exited 0. The authenticated operation records contain `RUNTIME_VERIFIED` and final `COMPLETE`; the initialization inventory has 7 organizations, 17 principals, 20 appointments and 320 completed steps. No concurrent or replacement initialization was run.

The first actual same-CA rotation started from that COMPLETE instance, using generated fixture material, unchanged public origins and the registered proxy services. Its operation is `d8af209106bb46858efaff73dcd43511`. During creation of replacement runtime containers, the 32GiB test filesystem reached 100% utilization. The runner exited 1 and retained its RUNNING acceptance record and original rotation operation. The controller ended in `BLOCKED`.

Read-only checks after failure confirmed:

- gate `BLOCKED`, revision 29;
- registered outer proxy observed closed;
- no registered writer or application ingress container running;
- sealed TLS validation passed;
- original containers, original initialization and archived certificate material retained.

The failure occurred before successful activation. No successful native/bridge/public generation convergence, authenticated post-rotation forwarding, preservation comparison or recovery result is claimed. Runtime uses Docker's `vfs` storage driver, which copies filesystem layers for additional containers. Two redundant private toolchain download archives (approximately 138MiB) and the unused testcontainers/ryuk image cache were removed, but the layered test filesystem still reports zero available space. Installed tools, runtime images, original containers, private evidence and data volumes remain. There is insufficient space to complete the additional full-size runtime container, let alone a second rotation and isolated business fixture. No paid resource was added.

The acceptance driver now supports continuation bound to the exact original operation, candidate digest and previous generation. Its crash scenario requires actual subprocess SIGKILL and observed closed writers, rather than counting a mocked exception as a crash. Neither this crash scenario nor its recovery has yet been executed against the full stack. A missing or skipped scenario cannot satisfy coverage.

Continue only the retained same-CA operation after sufficient test-only capacity is available. Do not start another rotation, discard the failure, remove original containers to obtain a pass, or relabel future-time unit tests as a real expired-certificate run. Cross-CA, actual expired-old, stale-native, unregistered-client combined acceptance, real SIGKILL recovery, linked checkpoint restoration and all four business workflows remain unqualified.

All material was generated for this isolated verification instance. No production connection, production private key, credential, push, PR, merge or deployment was used.

Validation of this checkpoint: 235 Python tests passed in 31.659 seconds; `git diff --check` passed. The acceptance-driver focused subset passed 5 tests. An initial focused invocation omitted `PYTHONPATH=deploy/linux` and failed collection; the corrected invocation passed. Previous locked-runtime Node 2/2 and Java 14/14 evidence was not rerun because those sources did not change. These checks do not substitute for the missing real scenarios.

Saving this checkpoint as a Git commit was also blocked: Git could not create its temporary object (`No space left on device`). HEAD remains `406bf14bc9aeaea5f53c386c10e7841efed065e4`; driver, tests and evidence changes remain uncommitted and have an additional patch copy in the test environment temporary filesystem.

The user subsequently authorized preservation through a draft PR. A temporary Git copy on tmpfs was used to commit the checked pending source/evidence changes without modifying or relocating the runtime. This resolves source delivery only; storage and all real acceptance gaps above remain. The draft must not be merged or deployed.
