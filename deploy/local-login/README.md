# Isolated local login

This helper deploys the approved synthetic login subset of Task9.6. It does not complete Task9.5, seven-card acceptance or human UAT. The browser uses the production SPA and the existing production API Jar, real Keycloak 26.7.3 and independent PostgreSQL 18 databases. No mock SELF, test Bean or HUMAN registration is used.

Prerequisites: Docker Desktop; Python; the existing `pwsh`; locked Node24.20.0/npm11.9.0 and JDK25.0.4.1 runtime under `C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb`; a packaged backend Jar and installed root npm dependencies. Run commands from the repository root. No new runtime or image version is selected by these helpers.

The ignored runtime is `.superpowers/sdd/2026-09-08-task9-real-user-access-plan/local-login-runtime/`. Before every operation the runner verifies its Windows ACL is protected and allows only the current user and SYSTEM, and verifies Git ignores it. Provision that ACL before creating any secret. See [CERTIFICATES.md](CERTIFICATES.md) for the separately approved certificate/trust procedure. `prepare` requires the listed certificate contracts; it does not change Windows trust.

## First deployment

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

`stop` verifies the saved process IDs still belong to the expected local executables/artifacts before stopping SPA/API, then stops the three named containers. It does not remove containers, volumes, runtime files or Windows certificates. `resume` reuses the existing resources and secrets, waits for Keycloak readiness, and rebuilds the SPA with the same fixed public configuration. It refuses a Jar different from the locally activated release hash. After the browser checkpoint, the actual stop/resume cycle succeeded and all21secret-file hashes remained unchanged; protocol founder/unmapped checks were repeated. The build PATH places the pinned npm `.bin` before the Node directory so nested `npm run` also uses npm11.9.0.

The current offline bootstrap verifier checks the entire initial Tenant snapshot: exactly one principal and one appointment. It passed immediately after founder bootstrap. After the approved SERVICE principal/appointment is added, `bootstrap-verify` returns exit2 because those Tenant-wide counts are now two. This is a discovered verifier limitation after separately authorized identity expansion; do not interpret it as evidence of restart failure or a missing original commit. A separate read-only audit confirmed the original targeted HUMAN/admin/four-grant shapes and slot/receipt/audit closure, but does not replace full original verification. Preserve the original manifest/keys/rows; never rerun initialization, remove the SERVICE rows, or replace the candidate to force it to pass. This limitation must be resolved before wider identity expansion/recovery acceptance. Live health/protocol checks verify this bounded local login only.

Do not run Docker environment dumps, print protected configuration/diagnostic files wholesale, remove database volumes or regenerate keys on restart. If any stage fails, its detailed subprocess output stays in the protected runtime and the console shows only its name and exit status. Permission, retention and certificate expiry remain local operator responsibilities.
