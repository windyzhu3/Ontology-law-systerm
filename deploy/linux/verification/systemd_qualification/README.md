# Fixed real-systemd proxy qualification

This package exercises the systemd transport of the existing TLS rotation helpers. It does not initialize a database, replace native/business acceptance, rotate production TLS, or admit deployment before its real Q01–Q12 report passes. Only the original authorized operator runs it on the selected systemd host.

All certificates, credentials, operation records and account fixtures are newly synthetic. The public and native original certificates deliberately differ; native expires after six minutes and external after fifteen. No clock changes. Expected run time is about six minutes after material generation; if earlier tests consume the old validity window, rollback fails closed and retains the original operation, never silently regenerating it.

Q03 also uses a sealed synthetic issuer of `127.0.0.3`, distinct from the synthetic pod source `127.0.0.2` and ordinary loopback source `127.0.0.1`. The two health routes must accept all three exact sources and reject adjacent `127.0.0.4`, wrong methods and other paths. This tests the self-address ACL used after public hairpin SNAT without altering host NAT, firewall or routes.

## Explicit blocked-maintenance admission

Only when the operator has separately authorized qualification while the original application is blocked, add `maintenanceBaseline` to the private inputs with exactly `operationId` and `snapshotSha256`. Obtain that hash using the delivered, verified bootstrap module's read-only `maintenance_snapshot(inputs, ORIGINAL_OPERATION_ID)` before starting bootstrap. Import or execute it with `__name__` other than `__main__`; do not call `main()` to obtain the snapshot. The output contains only the operation ID, status and snapshot digest.

This mode requires the original `ROLLBACK_BLOCKED` operation, its exact sealed rollback BLOCKED gate, previous-generation selection, all application/retained containers stopped, original infrastructure containers running, both databases answering a read-only query, and closed public ingress. The digest binds the original source files, operation/selection/launch/resource records, observed database schema/gate, container IDs/activity and proxy state. Every admission and continuous production check must match the operator-pinned digest. The separate configuration/unit baseline and all resource guards remain enabled. Any drift or unavailable observation stops only fixture resources. This returns `MAINTENANCE_UNCHANGED`, never application health `PASS`.

Keep the original controller used by `healthCli` unchanged throughout qualification. After all Q01–Q12 pass, privately retain the new sealed report and referenced evidence with its journal key before cleanup. Only then install the reviewed repair separately and supply the new report binding to the explicit original-operation forward recovery command. Changing gate, starting production writers or replacing the monitored source during qualification invalidates the maintenance baseline.

## Inputs and delivery

The private JSON envelope sent to `bootstrap.py` contains:

```json
{
  "archiveSha256": "EXACT_DELIVERED_ARCHIVE_DIGEST",
  "archive": "BASE64_OF_DELIVERED_TAR_GZ",
  "inputs": {
    "healthPython": "/absolute/existing/python3",
    "healthCli": "/absolute/original/deploy/linux/linux.py",
    "healthCliSha256": "READ_ONLY_SHA256_OF_THAT_ORIGINAL_CLI",
    "healthRuntime": "/absolute/original/private/runtime",
    "productionUnits": ["EXACT_EXISTING_NGINX.service", "EXACT_EXISTING_CADDY.service"],
    "nonSecretFiles": ["/absolute/original/nginx.conf", "/absolute/original/http.conf", "/absolute/original/https.conf", "/absolute/original/bridge.Caddyfile", "/absolute/original/nginx.service", "/absolute/original/caddy.service"]
  }
}
```

Use the known existing original paths; do not guess, copy production runtime, include private key paths, or install Python/packages. `health` is the sole original application command executed. Configuration observations are read-only and remain private. An existing Python 3.10+ and openssl are required. The supplied proxy binary SHA256s must match exactly.

Verify both the delivered bootstrap source and archive against the separately delivered hashes. Stream the bootstrap source as the Python `-c` argument and the private envelope to stdin using the operator's existing execution transport. Do not save another unapproved host-side package directory. Conceptually:

```text
EXISTING_PYTHON -B -c EXACT_VERIFIED_BOOTSTRAP_SOURCE < PRIVATE_ENVELOPE_STREAM
```

Bootstrap rechecks names, directories, loopback ports, current health, cgroup v2, memory pressure, ≥1GiB MemAvailable and ≥2GiB free disk on the filesystem containing `/var/lib` (not the `/run` tmpfs). Any conflict stops rather than inventing another name. Only the exact empty mountpoints and fixed slice are created before entering the capped control scope. Extraction, material generation, service installation and probes then execute inside that scope.

The scope shares a slice capped at 512MiB, MemoryHigh256MiB, zero test swap, CPU50%, Tasks128. The tmpfs is255MiB; fixed unit/drop-in files are below1MiB. Ports are loopback29843–29848 only. No users, firewall, sysctl, packages, Docker resources or production certificate changes.

## Read-only capability check and pressure fallback

Before another execution attempt, the operator may stream the same verified bootstrap and envelope with `--check-only`. This runs all read-only admission checks and returns before any fixture directory, mount, certificate or unit file is created:

```text
EXISTING_PYTHON -B -c EXACT_VERIFIED_BOOTSTRAP_SOURCE --check-only < PRIVATE_ENVELOPE_STREAM
```

The checks cover existing commands/accounts, exact binaries, systemd247+ credential support, Python/OpenSSL TLS1.3, root mount/process-inspection capabilities, cgroup v2 memory/cpu/pids controllers, kernel socket tables, names/paths/ports, original health, disk and memory. Existing SELinux enforcement requires a separate read-only review of policy for these exact test paths/ports; the runner refuses to guess or alter policy. After the capped scope is created, actual kernel memory/high/swap/CPU/tasks limits and required memory event counters are verified before generating certificates or starting test services.

A missing host PSI node is explicitly recorded as `psi: null`, `pressureMode: vmstat-fallback`; it is never treated as zero pressure. Fallback requires both direct-reclaim and allocation-stall counters. Admission requires at least1GiB MemAvailable at both ends of the five-second sample and **no increase** in swap pages, direct reclaim or allocation stalls. Missing/malformed/unreadable required counters, counter reset, or a changed observation capability rejects admission or stops tests.

During execution, MemAvailable below512MiB stops tests on the next five-second sample. Any test cgroup cap/OOM event stops tests. Three consecutive pressure samples stop tests: in fallback mode **any** swap/direct-reclaim/allocation-stall growth qualifies; cgroup high events and PSI above2%, when actually available, also qualify. With host PSI available, the original2%/4MiB-per-second swap gates apply. The production-health thread remains separate from uninterrupted resource sampling; production drift, sustained load, or observation failure stops only test resources. This is a conservative counter-based safety gate, not a fabricated PSI measurement or a claim that the metrics are identical.

## Outcomes and interruption

Each genuine completed case prints its ID, PASS and original operation ID. The final sealed report is `/run/ols-tls-qualification/runtime/verification/systemd-proxy-qualification.json`. Per-case evidence and original intents remain under this synthetic runtime. Absence of the final report, a failed check, or a process error means NOT PASSED. Resource/health guard failures stop only the test services.

The Q07/Q08 child really receives SIGKILL after the committed Caddy start and before its response. The surviving guarded supervisor resumes the **same** sealed operation and proves the process was not restarted twice. These statements are made only after actual assertions succeed. No signal is sent to production or the supervisor.

An unrelated interruption or failed case retains the runtime and is not automatically restarted from scratch. Stop only the three named fixture services if necessary, preserve the private evidence, and continue the same original operation through a targeted reviewed recovery. Do not rerun bootstrap or generate replacement certificates to obtain PASS. The provided runner deliberately refuses to overwrite a started run.

## Exact cleanup

The runner stops its three services on exit and keeps evidence/mounts. After the original scope exits, use the same existing Python in the fixed scope:

```sh
systemd-run --scope --quiet --unit=ols-tls-qualification-control.scope --slice=ols-tls-qualification.slice EXISTING_PYTHON -B /run/ols-tls-qualification/package/verification/systemd_qualification/cleanup.py export
```

Privately download the emitted `private-export.tar.gz` and verify its SHA256 off-host. It contains only synthetic runtime material, original operation receipts, and private non-secret production observations. Never upload it publicly. Then:

```sh
systemd-run --scope --quiet --unit=ols-tls-qualification-control.scope --slice=ols-tls-qualification.slice EXISTING_PYTHON -B /run/ols-tls-qualification/package/verification/systemd_qualification/cleanup.py remove-units --retained-sha256 EXACT_DOWNLOADED_DIGEST
```

Cleanup checks empty service cgroups and closed ports, removes only matching registered unit files and the fixed matching Caddy drop-in, then daemon-reloads. Any foreign contents or metadata drift blocks deletion.

After that scope exits, run `teardown.py` with the existing Python. It checks the cleanup receipt, empty aggregate cgroup and exact tmpfs/bind mount identities, unmounts the state bind first and the tmpfs second, then only `rmdir`s the empty mountpoints. Pre-existing parent slices, accounts, binaries and all production resources remain untouched.

Partial bootstrap/setup failures do not authorize broad cleanup. Preserve the failure and use the exact ownership receipts to review the partial state.

## Typed manager observations and the pre-prepare amendment

Existing `busctl` with systemd252-compatible `--json=short get-property` support is required. Complex properties are read using typed D-Bus replies: ordered executable argv arrays, credential source pairs, environment-file and bind-mount arrays. No credential contents are requested. Empty arrays must be explicitly present. Scalar properties use `systemctl show --all`; duplicate, missing or unprintable scalar values remain errors. Saved startup bindings now preserve argv boundaries; earlier string bindings cannot silently qualify.

The separate `verification/systemd_partial_resume.py` is only for the exact 00548def bootstrap failure **before** the first prepare. It is streamed with the existing Python inside the fixed capped control scope. Default mode is read-only; `--apply` replaces only the three explicitly reviewed source/documentation files and proceeds to the first prepare, then runs its newly created original operation. It neither calls bootstrap nor resets any operation/materials. Original input/package receipts remain unchanged; a new private amendment intent records the replacement digests and blocks automatic repeat after interruption.

The private JSON envelope has exactly `originalInputs` (SHA256s of the three existing input receipts) and `replacementFiles` (the three paths below, each with `sha256` and base64 `data`). Pin its complete bytes with `--envelope-sha256`. Allowed replacements are `ols_linux/tls_systemd.py`, `verification/systemd_qualification/bootstrap.py`, and this README. The operator verifies all replacement hashes against the reviewed source manifest before sending the envelope. Neither the envelope nor host observations belong in the public repository.

The amendment requires the original 44-file package and exact receipts, empty state/evidence, no material/runtime directories, no test services/drop-ins/listeners, exact original mounts and slice, actual resource limits, only its own control process, and fresh read-only admission using the reviewed parser in memory. Any disagreement stops before modification. After an interrupted amendment, retain the intent and partial state for targeted reconciliation; do not rerun it or use normal cleanup without the installed registry.
