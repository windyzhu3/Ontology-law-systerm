# TLS rotation verification matrix

Overall: **INCOMPLETE**. No skipped/missing scenario counts as PASS.

| Requirement | Available evidence | Missing evidence |
| --- | --- | --- |
| Same-CA rotation | Material/generation/controller unit tests | Complete real application rotation |
| Cross-CA rotation | Material/trust unit tests; Java trust/client identity integration | Complete real application rotation and all consumers |
| Expired old certificate forward rotation | Controller unit tests using explicit test time | Real expired-old full-stack rotation |
| Proxy new / native stale | Real TLS socket mismatch test | Injected stale native copy in complete container stack |
| Unregistered trusted-root client | ClientCertificateIT and ActorContextResolverIT: 14 passing ITs | Combined full application rotation scenario |
| Crash recovery | Controller unit phase matrices | Actual process termination during full-stack rotation |
| Checkpoint restore | Exact-generation/expiry/quarantine and proxy coordinator unit tests | Real linked dual-database restore and reopening |
| Issuer blocked | Source-admission tests; production issuer allowlist empty | Complete fixture confirmation that no maintenance effects occurred |
| Native/bridge/public identity hop | [Real Keycloak/Caddy/nginx TLS + OIDC discovery](identity-tls-hops.json) | Application entry, API, Worker and complete business workflow |
| Account and permission preservation | 17 generated test HUMAN accounts observed | COMPLETE initialization and unchanged 7 organizations / 20 appointments / 280 grants after rotation |
| Docker proxy profile | Real listener chain verification on fixture | Successful rotation and rollback |
| systemd proxy profile | Unit state/identity checks | All live transport acceptance; not deployment-qualified |

Local detailed logs and retained private fixtures are intentionally outside Git. The report contains no private key, password, token or production credential. See [report](report.md) for the exact scope and blocking finding.
