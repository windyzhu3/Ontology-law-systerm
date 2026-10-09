# Fixed real-systemd proxy qualification

This package exercises the systemd transport of the existing TLS rotation helpers. It does not initialize a database, replace native/business acceptance, rotate production TLS, or admit deployment before its real Q01–Q12 report passes. Only the original authorized operator runs it on the selected systemd host.

All certificates, credentials, operation records and account fixtures are newly synthetic. The public and native original certificates deliberately differ; native expires after six minutes and external after fifteen. No clock changes. Expected run time is about six minutes after material generation; if earlier tests consume the old validity window, rollback fails closed and retains the original operation, never silently regenerating it.

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
