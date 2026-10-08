# Public TLS rotation verification — incomplete

Status: **INCOMPLETE / NOT DEPLOYMENT READY**. No production connection, production material access, push, PR, merge or deployment occurred.

The isolated build used source commit `450490a8ecc9d9c016690e38cc3d8c5d5545fbe1`. Its SPA and executable JAR were rebuilt from the exact Git archive, with the existing locked Node 24.20.0/npm 11.9.0/JDK 25.0.4.1 toolchain. Bundle descriptor: `b837dbc9665ec6b7b328d387376355e8db88e2c3d064c9f578d798085d97f92a`. The initial npm install failed; the original build directory was retained, and both builds subsequently succeeded offline using existing package caches. Separately, forwarding the existing environment proxy produced HTTP 200 from the registry, without disabling TLS verification.

Earlier controller checks after the bounded review corrections: 217 Python tests passed, zero failures/errors/skips. The existing Node tests passed 2/2 inside the locked runtime image. Earlier Java ClientCertificateIT/ActorContextResolverIT evidence records 14 integration tests passed; backend source/tests have not changed since that run. These results do not establish complete rotation acceptance.

## Earlier container checkpoint (superseded below)

A new, explicitly marked verification instance contains two owned PostgreSQL databases, Keycloak 26.7.3, nginx 1.20.1 and Caddy 2.10.2. Only generated test certificates and test credentials were used. The original initialization operation is retained in `IDENTITY_IMPORT_UNKNOWN`; it has not been marked COMPLETE or replaced.

[Identity TLS hop evidence](identity-tls-hops.json) proves actual certificate-chain/hostname/fingerprint verification and HTTP 200 OIDC discovery with the expected issuer through:

- direct native Keycloak;
- Caddy using the original sealed internal certificate for its listener and strict upstream TLS;
- public nginx using the test public certificate and verified TLS to Caddy.

The test identity database contains 17 HUMAN accounts. This is **not** proof of completed organizations/appointments/grants, an application-entry route, a business workflow or a successful certificate rotation. No rotation has been executed against a COMPLETE full application instance.

The fixture's loopback public origin is reachable from the host but not from the pod network namespace. This prevents the fixture's original initialization from completing. Its exact operation and resources are preserved; no cleanup/new-ID pass was substituted. A corrected container-reachable test origin requires an explicitly recorded fixture revision.

## Earlier design finding (implementation addressed; acceptance pending)

The implemented maintenance procedure stops the whole registered outer nginx before restarting API and checking runtime readiness. However, `HumanCredentialVerifier.healthy()` retrieves JWKS and posts introspection to the original public issuer. `R1ApiDeployment` requires that check to pass during API construction. The added native-routing helper changes Node requests only; it does not reroute Java HttpClient traffic.

Therefore, with the public identity endpoint served by that stopped nginx, the planned pre-opening API startup/readiness cannot succeed. This is a code-supported design finding, not a claim that a complete rotation was experimentally run and failed.

Resolving it needs a reviewed choice between a tightly bounded maintenance path that remains reachable only by approved internal consumers, or an explicitly configured Java identity transport preserving the original issuer and strict peer verification. This report does not authorize either architecture, new listeners, kernel routing changes, TLS bypasses or altered identity contracts. The current branch must not be deployed while this finding remains open.

During real fixture setup, a separate `tls_generation` local-import shadowing error was reproduced and fixed with a regression test. The original fixture operation was resumed. Bridge probe expectations were also corrected to allow an explicitly registered original internal TLS identity; native/public endpoints still require the public generation and original public host identity.

## Remaining work

See the [verification matrix](verification-matrix.md). The planned all-scenario acceptance driver, complete application fixture, same-CA/cross-CA/expired-old rotations, real failure recovery and linked checkpoint restoration are not complete. systemd transport has no live-environment acceptance evidence. The [independent review and bounded corrections](independent-review.md) record the completed one-review/one-fix pass. Public HTTP forwarding and individual consumer proof remain open, alongside the architecture blocker. No successful integration status should be inferred from unit coverage or this report.

## Authorized maintenance follow-up

The parent authorized a minimal restricted-maintenance implementation. See [the follow-up and evidence](maintenance-follow-up.md). The dependency fix is implemented and the rendered nginx boundary passed real HTTP/TLS checks; full API-under-maintenance, rotation and business acceptance remain pending. The original initialization progressed beyond bootstrap and both administrator real PKCE ceremonies passed. Earlier fixture failure evidence is retained rather than replaced by a new initialization ID.

The earlier checkpoint had 233 Python tests passing. The original initialization has now completed and the first real rotation has encountered test-storage exhaustion; see the current checkpoint below.

## Current checkpoint — 2026-10-08 12:05 UTC

[Full initialization and first rotation attempt](full-stack-checkpoint.md) supersedes the partial initialization status above. The original initialization is COMPLETE. The first same-CA rotation is BLOCKED with observed closed ingress and stopped writers after the test disk filled. This is failure-closure evidence, not a successful rotation or recovery qualification.
