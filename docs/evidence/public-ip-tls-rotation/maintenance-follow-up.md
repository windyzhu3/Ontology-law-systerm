# Restricted maintenance follow-up

The parent authorized fixing the startup dependency within the existing issuer, trust and authorization boundaries. This implements the maintenance/probe distinction already specified in design section 7; the approved specification itself is unchanged.

The qualified profile uses the existing Docker nginx and Caddy containers and original two IPv4 listeners. During activation, nginx serves only GET `/realms/<original>/protocol/openid-connect/certs` and POST `/realms/<original>/protocol/openid-connect/token/introspect`, restricted to host loopback and the owned application's exact pod IPv4 address. All other paths, including identity administration, browser login and application routes, return 503. Existing introspection client authentication and both proxy TLS checks remain enabled. Neither public issuer nor Java HTTP/TLS hostname changes; no root, client authorization, listener or subnet is added.

Before native consumers start, the controller validates the fixed maintenance configuration, observes nginx stopped, installs it and starts the same registered container. Exact nginx entrypoint/argv must reference the registered configuration. Nested runtime mounts are refused, and the effective configuration read inside the container must match. Stop/start removes old workers instead of relying on a reload acknowledgement. Failures retain the original operation and use the existing full proxy-stop/writer-stop/BLOCKED cleanup. Forward rotation, original rollback and linked restore use this maintenance path; reopening replaces it with the exact saved full configuration only after native checks.

Unsupported listener syntax, shared identity/application port profiles, alternate nginx wrappers and systemd maintenance profiles are refused. They must not inherit Docker qualification. Production configuration is untouched and still requires separate deployment authorization and preflight.

## Targeted review and observed evidence

A targeted read-only review examined this delta, not another full-branch review. It found alternate startup-config and nested-mount risks. Both were addressed with exact argv checks, nested-mount rejection, effective container-byte verification and regression tests. Review did not qualify restore or the full live rotation scenario.

[Real maintenance HTTP evidence](maintenance-http.json) records nginx 1.20.1 with strict public TLS and strict upstream TLS to the existing Caddy/Keycloak fixture: allowed JWKS 200; disallowed exact source 403 even with a spoofed forwarding header; wrong method 405; unauthenticated introspection 401; discovery/admin/application routes 503. Test listeners were isolated additional test ports, not controller-created production listeners. This proves the rendered boundary, not yet API startup under that boundary.

Public controller HTTP checks now use a separate sealed original-origin helper, so managed TLS routing cannot silently bypass nginx. Public discovery issuer, exact SPA bytes and a forwarded API authentication-rejection contract are checked in addition to TLS fingerprints. Node upstream evidence comes from a real native-entry API response matching the direct API contract, not the aggregate readiness flag. This rejection probe is **not an authenticated business request**; authenticated forwarded requests and business workflows remain acceptance requirements.

## Fixture continuity and environment

Original initialization operation `da6898aa6e024e288dd6cc4fd0d57d03` and its databases were preserved. An isolated, fixed-destination TCP relay connects the pod's original loopback public origins to the host fixture nginx, with a host-side exact pod-address restriction. TLS terminates at the original nginx and issuer stays unchanged. This networking accommodation is test-only.

The workspace Docker driver uses `vfs`. Creating the full locked browser image exhausted the 32 GiB disk; failed diagnostics were retained and only reproducible caches/images were removed. Browser verification instead used the locked Playwright 1.63.0 and downloaded Chromium 153.0.8010.12, revision 1243, on the workspace with a private NSS trust store, without TLS bypass. Its callback-only test server used an isolated test port through the existing Caddy, restored afterward. Both administrator password ceremonies and real PKCE passed, and password grant was rejected. This is native-browser evidence, not a successful run of the original browser container image.

Docker-generated test certificate/trust-store outputs were owned by the container-mapped UID. The isolated fixture repaired ownership and runs certificate setup tools as the fixture UID; no production material was changed. The original initialization continued past bootstrap to the HUMAN-session stage. Full runtime and rotation results are recorded separately as they become available; none are implied here.

## Saved checkpoint

The focused follow-up also found two restore issues, now corrected with regressions: exact signed maintenance bytes are closed and reconciled to the checkpoint's desired configuration **before** strict asset validation (checkpoint hash checks were not weakened); restored generation/checkpoint bridge targets take precedence over pre-restore targets. The targeted reviewer did not rerun the resulting real restore path.

Latest full Python run: **233 tests passed**, zero failures/errors/skips, 32.472 seconds. The acceptance driver now refuses production fixtures and missing/partial coverage and provides real same-CA/cross-CA/actually-expired-old runners with stable identity/business fact comparison and authenticated public forwarding. Other matrix scenarios remain unimplemented in that driver; it does not claim all-scenario completion.

At this checkpoint, the original initialization is still running in `IDENTITY_VERIFIED`: 7 organizations, 14 HUMAN principal mappings, 15 appointment mappings and 203 completed initialization steps observed. These are partial controller progress counts, not completed roster acceptance. Real rotation has **not** run. The running initialization is deliberately left active for continuation.
