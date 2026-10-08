# TLS rotation verification matrix

Overall: **INCOMPLETE**. No skipped/missing scenario counts as PASS.

| Requirement | Available evidence | Missing evidence |
| --- | --- | --- |
| Same-CA rotation | [Original-operation recovery PASS, six TLS targets, six consumers and authenticated forwarding](same-ca-recovery.json) | None for this same-CA fixture; overall matrix remains incomplete |
| Cross-CA rotation | Material/trust unit tests; Java trust/client identity integration | Complete real application rotation and all consumers |
| Expired old certificate forward rotation | Controller unit tests using explicit test time | Real expired-old full-stack rotation |
| Proxy new / native stale | Real TLS socket mismatch test | Injected stale native copy in complete container stack |
| Unregistered trusted-root client | [Real post-same-CA fixture: unregistered client 401, spoofed headers 401, registered SERVICE 204](same-ca-recovery.json); earlier Java ITs | Cross-CA combination remains pending |
| Crash recovery | Controller unit phase matrices | Actual process termination during full-stack rotation |
| Checkpoint restore | Exact-generation/expiry/quarantine and proxy coordinator unit tests | Real linked dual-database restore and reopening |
| Issuer blocked | [Real complete fixture: gate, containers, proxies and original operation unchanged](same-ca-recovery.json) | No real production issuer admission is claimed |
| Native/bridge/public identity hop | [All six targets and six consumers PASS after same-CA rotation](same-ca-recovery.json) | Complete business workflows and remaining scenarios |
| Account and permission preservation | [Same-CA before/after fact hashes equal; exact 7/17/20/280 inventory PASS](same-ca-recovery.json) | Cross-CA and restore preservation |
| Docker proxy profile | Real same-CA rotation and observed failure closure | Explicit rollback and other scenarios |
| systemd proxy profile | Unit state/identity checks | All live transport acceptance; not deployment-qualified |

Local detailed logs and retained private fixtures are intentionally outside Git. The report contains no private key, password, token or production credential. See [report](report.md) for the exact scope and blocking finding.
