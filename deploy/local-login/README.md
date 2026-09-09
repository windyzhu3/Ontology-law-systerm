# Isolated local login

This helper deploys the approved synthetic login subset of Task9.6. It does not complete Task9.5, seven-card acceptance or human UAT. The browser uses the production SPA and the existing production API Jar, real Keycloak 26.7.3 and independent PostgreSQL 18 databases. No mock SELF, test Bean or HUMAN registration is used.

Prerequisites: Docker Desktop; Python; the existing `pwsh`; locked Node24.20.0/npm11.9.0 and JDK25.0.4.1 runtime under `C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb`; a packaged backend Jar and installed root npm dependencies. Run commands from the repository root. No new runtime or image version is selected by these helpers.

The ignored runtime is `.superpowers/sdd/2026-09-08-task9-real-user-access-plan/local-login-runtime/`. Before every operation the runner verifies its Windows ACL is protected and allows only the current user and SYSTEM, and verifies Git ignores it. Provision that ACL before creating any secret. See [CERTIFICATES.md](CERTIFICATES.md) for the separately approved certificate/trust procedure. `prepare` requires the listed certificate contracts; it does not change Windows trust.

Only first-time `prepare` can create a secret bundle, and only while the runtime contains the certificate directory and no other artifacts. If deployment metadata, configuration, logs or any other initialization artifact survives without the complete original bundle, preparation fails before writes. Later stages load existing keys and never generate replacements. `stop` remains available without keys so a damaged local environment can still be stopped safely.

## Historical first deployment

The sequence below records the original login installation. Do not replay it on
the initialized environment. The current release workflow later in this file
requires a saved existing deployment before `start-apps`; it is not a new
installation/bootstrap workflow.

Run each command separately and check its exit code. These are individual operator stages, not a command to run unattended through the bootstrap checkpoint.

```text
python deploy/local-login/local_login.py prepare
python deploy/local-login/local_login.py infrastructure
python deploy/local-login/local_login.py health
python deploy/local-login/local_login.py migrate
python deploy/local-login/local_login.py bootstrap-dry-run
```

The dry-run prints only the existing offline entrypoint's safe creation preview. Review the exact Tenant, ROOT, synthetic founder and four fixed ROOT DIRECT Identity administration grants before proceeding. The candidate lasts at most five minutes. Preserve the original command/manifest and all original offline/subject keys. An expired or uncertain original operation must be investigated using `bootstrap-verify`; never automatically issue a new candidate or reinitialize its database.

After that explicit local target approval, the stages are:

```text
python deploy/local-login/local_login.py bootstrap-execute
python deploy/local-login/local_login.py bootstrap-verify
python deploy/local-login/local_login.py service-fixture
python deploy/local-login/local_login.py application-config
python deploy/local-login/local_login.py start-apps
```

`service-fixture` is the separately reviewed local infrastructure operator transaction: one SERVICE principal and one SERVICE appointment under this exact initialized ROOT, zero SERVICE grants. It supplies the existing production assembly's mandatory static SERVICE/source/certificate bindings. It neither modifies HUMAN identities nor creates business rows. A dedicated private service certificate is generated, no service token is minted, and no Worker is started. Re-running initialization against partial/existing state fails; preserve it for investigation.

The API requires an ACTIVE Tenant and SERVICE configuration before startup, so offline bootstrap precedes API startup. A second synthetic Keycloak user remains unmapped to verify rejection. This ordering is necessary for the unchanged production validation.

## Addresses and retained resources

| Resource | Host binding |
|---|---|
| SPA | `https://localhost:19444` |
| Keycloak realm | `https://localhost:19443/realms/local-r1` |
| API | TLS `127.0.0.1:19445`, SPA proxies `/api/` with CA and hostname verification |
| Business PostgreSQL | TLS `127.0.0.1:19446`, JDBC `sslmode=verify-full` |
| Identity PostgreSQL | No published host port; only its internal network |

All containers/networks/volumes use `ontology-law-local-login-`. The databases use separate `-identity-db-data` and `-business-db-data` volumes. Both internal networks are retained. Docker Desktop needs separate `-identity-ingress` and `-business-ingress` bridges, with inter-container communication disabled, attached only to Keycloak and business DB respectively so the authorized loopback publishes work. No extra host port is opened. Keycloak uses the independently verified database certificate and CA. Database TLS keys are copied into containers as UID999/mode0600; Keycloak files as UID1000/mode0600.

The selected images come from the existing platform digest locks. Keycloak's supported disabled feature name is `parameterized-scopes`; the formerly configured `dynamic-scopes` was rejected by the pinned 26.7.3 binary. The dedicated regression runs that actual binary offline without networking. The original failed Keycloak container is retained as `ontology-law-local-login-keycloak-failed-feature` for this run's investigation; it is stopped and has no database volume.

Open `https://localhost:19444/login`. Read the synthetic `founder` or `unmapped` entry from the protected `browser-credentials.json` locally; never copy that file into Git, logs, screenshots or reports. Founder SELF is `READY`, with Identity administration allowed and workbench entry disallowed. This demonstrates login and qualification boundaries, not business-card authorization.

## Verification and operations

```text
python -m unittest discover -s deploy/local-login/tests -v
node --test deploy/local-login/tests/server.test.mjs
python deploy/local-login/tests/keycloak_features.py -v
python deploy/local-login/local_login.py health
python deploy/local-login/local_login.py protocol-check
python deploy/local-login/local_login.py protocol-check-unmapped
python deploy/local-login/local_login.py stop
python deploy/local-login/local_login.py resume
```

Use the locked Node executable for `node` above. The protocol checks use real Code+PKCE with a separate short diagnostic session, assert exact trust/claim/SELF outcomes, and log out that session. Tokens and subjects remain in process memory. Actual browser acceptance is separate. The proxy logs only a fixed SELF status counter, never request query/headers/body. SPA navigation fallbacks are exact; `/api/` cannot become HTML, and callback query parameters are preserved. TLS errors are never bypassed.

`stop` verifies the saved process IDs, executable, exact argument list and recorded creation time before stopping SPA/API, then stops the three named containers. `stop-apps` stops only API/SPA and leaves Keycloak and both databases running. `resume` reuses existing infrastructure, waits for Keycloak readiness, then consumes the verified current release. Neither `start-apps` nor `resume` builds. Before a release snapshot exists, the legacy Jar drift check still refuses changed bytes and startup requires `snapshot-release`. Historical stop/resume and 21-secret hash observations refer only to the earlier login deployment, not this new release implementation.

The saved legacy Jar predates the original-bootstrap verifier correction and may reject a Tenant expanded by the approved SERVICE fixture. Current source includes the correction, but source tests do not prove which Jar is deployed. A candidate built from the reviewed current source must pass `bootstrap-verify-current-release` after activation. That command derives `operator-current-release.json`, changes only database expected release/manifest, and invokes only `verify` with the complete original manifest and existing keys. The original operator and manifest remain byte-for-byte historical records. After a snapshot, the old bootstrap wrapper refuses to rewrite the operator. A legacy Jar rollback does not acquire the corrected verifier: its original-closure check needs a separately reviewed current production verifier artifact with that rollback's expected gate; this helper does not silently substitute one.

Do not run Docker environment dumps, print protected configuration/diagnostic files wholesale, remove database volumes or regenerate keys on restart. If any stage fails, its detailed subprocess output stays in the protected runtime and the console shows only its name and exit status. Permission, retention and certificate expiry remain local operator responsibilities.

## Controlled local release (Task 9.6b)

This is a bounded existing-environment API/SPA artifact switch. It does not enable
a Worker, authorize SERVICE/HUMAN identities, alter schema, or complete real-chain
acceptance. Independent review precedes actual operations. All commands below
run from the worktree root with the pinned Python; no helper builds an artifact.

The write-once `local-login-runtime/releases/<32hex-id>/` store is outside the
live `backend/target` and `apps/workbench/dist` output directories, under the
original ignored runtime ACL. Every operational entry verifies the ignored
boundary, current-user/SYSTEM ACLs throughout its tree, and absence of links or
junctions. Packages are never overwritten; descriptors and every saved file are
rehashed before use. Configurations containing secrets stay in this protected
store. New Worker files can be added later; all recorded original material paths
must still have their original hashes.

1. Save the original Jar, complete sorted SPA file set, API configuration,
   deployment metadata, original-material hashes, complete gate and schema
   catalog/Flyway history **before stopping or rebuilding anything**:

   ```powershell
   & D:/soft/python3/python.exe deploy/local-login/local_login.py snapshot-release
   & D:/soft/python3/python.exe deploy/local-login/local_login.py stop-apps
   ```

   The baseline server from commit
   `01213b1acfeb164c271c430f6561f87f570250c4` is preserved verbatim as
   `legacy-server.mjs`. Its original hardcoded paths cannot serve an immutable
   dist copy. The approved rollback therefore uses the separately hash-pinned
   new `server.mjs` bridge with the exact saved old Jar/dist/config/gate values.
   This is artifact rollback, not restoration of historical host behavior.
   Both the legacy gate's old toolchain-only manifest and that limitation are
   explicitly recorded; the old manifest is not whole-chain release evidence.

2. After reviewed changes and controller documentation are committed, fix the
   actual HEAD and record source inputs before building:

   ```powershell
   $releaseCommit = git rev-parse HEAD
   & D:/soft/python3/python.exe deploy/local-login/local_login.py capture-build-inputs $releaseCommit
   ```

   `build-inputs.json` includes that exact commit, pinned toolchain identifiers,
   the four exact public Vite values and source SHA-256s. Dirty production,
   hosting or build inputs are refused (unrelated controller records may be
   dirty). The named sources are the existing OpenAPI YAML, generated
   schema-contract manifest, CommandEnvelope, ActorContextResolver,
   R1CommandPolicy/R1EventPolicy, CommandHandler event definitions,
   R1SourcePolicyRegistry and API/Worker assemblies. Complete production Java
   (including `backend/src/generated/jooq`), SPA source, HTML entry, TypeScript
   configuration and generated schema file sets plus Maven/npm/Vite build
   files and the Windows Maven wrapper are also hashed. The inventory and Git
   modified/untracked checks share these same bounded input definitions,
   so indirect resolver/router/policy implementations are covered. No new
   business metadata contract is invented.

3. The controller separately runs the already approved Jar/package and SPA build
   commands, with JDK `25.0.4.1+1`, Node `24.20.0`, npm `11.9.0`, and retains
   their actual commands, version outputs, stdout/stderr and exit codes in
   protected evidence. The required SPA environment is:

   ```powershell
   $env:JAVA_HOME = 'C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/jdk-25.0.4.1+1'
   $env:VITE_OIDC_ISSUER = 'https://localhost:19443/realms/local-r1'
   $env:VITE_OIDC_CLIENT_ID = 'local-r1-spa'
   $env:VITE_OIDC_AUDIENCE = 'local-r1-api'
   $env:VITE_APP_ORIGIN = 'https://localhost:19444'
   $env:PATH = 'C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/npm-11.9.0/node_modules/.bin;C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/node-v24.20.0-win-x64;' + $env:PATH
   $releaseRuntime = Join-Path (Get-Location) '.superpowers/sdd/2026-09-08-task9-real-user-access-plan/local-login-runtime'
   & .\mvnw.cmd -B -f backend/pom.xml '-DskipTests' package > (Join-Path $releaseRuntime 'candidate-jar-build.stdout') 2> (Join-Path $releaseRuntime 'candidate-jar-build.stderr')
   $jarBuildExit = $LASTEXITCODE
   & C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/node-v24.20.0-win-x64/node.exe C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/npm-11.9.0/node_modules/npm/bin/npm-cli.js run build > (Join-Path $releaseRuntime 'candidate-spa-build.stdout') 2> (Join-Path $releaseRuntime 'candidate-spa-build.stderr')
   $spaBuildExit = $LASTEXITCODE
   & D:/soft/python3/python.exe deploy/local-login/local_login.py describe-candidate $releaseCommit $jarBuildExit $spaBuildExit
   & D:/soft/python3/python.exe deploy/local-login/local_login.py stage-release
   ```

   The consumed files are exactly
   `backend/target/ontology-law-system-0.1.0-SNAPSHOT.jar` and
   `apps/workbench/dist/`; arbitrary input directories are not accepted. Preserve
   the actual build logs before recording their results. The helper checks
   unchanged before/after source inputs, supplied successful build results,
   expected Jar SHA-256, sorted SPA file hashes/aggregate, and copied host bytes.
   Operator-supplied exit codes and toolchain declarations alone are **not**
   independent proof of source-to-binary provenance or the build environment.
   The controller's reviewed real build evidence supplies that relationship.
   `candidate-release.json` is the reviewable expectation captured immediately
   after the builds; changes after capture or during copying fail closed.
   The package example relies on the controller's completed backend tests;
   `-DskipTests` does not claim a new test run. Do not proceed on a failed build.

4. Review the staged ID and its protected `release-manifest.json`. Activate
   with both owned processes stopped:

   ```powershell
   & D:/soft/python3/python.exe deploy/local-login/local_login.py activate-release <staged-32hex-id>
   & D:/soft/python3/python.exe deploy/local-login/local_login.py start-apps
   & D:/soft/python3/python.exe deploy/local-login/local_login.py bootstrap-verify-current-release
   & D:/soft/python3/python.exe deploy/local-login/local_login.py protocol-check
   & D:/soft/python3/python.exe deploy/local-login/local_login.py protocol-check-unmapped
   ```

   Activation does not start processes or claim readiness. Check actual TLS,
   API/SPA responses and all four exact admin navigation URLs separately.
   The API consumes the current Jar release digest and manifest hash. Future
   Worker wiring must use this same `current-release.json`/verified `paths()`
   contract; the release helper refuses an unknown extra process registry entry,
   so Worker lifecycle must be integrated before switching while it is present.

The migrator's existing protected secret and CA are mounted read-only into a
one-shot client using the already locked PostgreSQL image. Only
`law_schema_migrator`, verified to own deployment_state, can run the transaction.
CAS compares all seven old gate fields including timestamp and revision,
asserts exactly one updated row, and advances revision by one. The same
transaction compares the saved schema catalog and Flyway history. There is no
postgres fallback, role/table creation, migration, retry against newly read
values, or business-fact update. Release and schema-source changes cannot be
combined in this helper.

## Interrupted switch and rollback checkpoints

The protected journal records `CAS_PENDING` before the database call,
`FILES_PENDING` before local config/pointer replacement, and `COMPLETE` only
after exact gate, schema, files and original material verification. File writes
and the database transaction are separate. A missing response is not success.
Start/resume refuses an incomplete journal; inspect with:

```powershell
& D:/soft/python3/python.exe deploy/local-login/local_login.py release-status
```

This emits only phase, old/new/recovery/other gate relationship and schema-match
status. With owned apps stopped, `recover-release complete` finishes saved new
file installation only when the entire observed gate equals the journal's
known new gate. If CAS never applied, use `recover-release rollback`; it restores
the saved old files without a new database update. If CAS did apply, the same
explicit rollback uses a separately journaled reverse CAS and preserves revision
monotonicity. An unknown gate, changed schema or changed original/saved bytes
refuses recovery for investigation. No result is silently retried with new
expectations.

```powershell
& D:/soft/python3/python.exe deploy/local-login/local_login.py recover-release complete
# OR, after reviewing the observed state:
& D:/soft/python3/python.exe deploy/local-login/local_login.py recover-release rollback
```

For a completed activation, the normal rollback is:

```powershell
& D:/soft/python3/python.exe deploy/local-login/local_login.py stop-apps
& D:/soft/python3/python.exe deploy/local-login/local_login.py rollback-release
& D:/soft/python3/python.exe deploy/local-login/local_login.py start-apps
```

Rollback restores only the saved predecessor package and gate values, with a
new revision/timestamp. It never reverses business/Identity/bootstrap facts.
If candidate build/staging fails before activation, the current pointer still
names the saved old package: `start-apps` resumes that package without using
the now changed build output. Failed `.pending` package directories are retained
and are never candidates. An interrupted initial snapshot has no active pointer
and cannot be promoted: retain its directory for inspection, verify the original
live inputs still match the old gate, and have the controller relocate that
failed store inside the protected runtime before an explicit new snapshot.

`release-operation.lock` serializes switches and app lifecycle operations. After
an abrupt interpreter termination, `release-status` remains available. A stale
lock is not automatically deleted: verify the recorded Python PID has exited,
inspect the journal, and explicitly remove only that exact protected lock file.
`apps-start.pending` similarly records a possibly interrupted process launch.
Inspect its registry and exact executable/arguments/creation times, stop only
proven owned processes using `stop-apps`, confirm no unrecorded owned process
survives, then explicitly remove that marker before another start. Never infer
ownership from a process name or a path substring. No Keycloak/DB stop is needed
for artifact switching. Operational execution and real-chain acceptance remain
controller work after review.
