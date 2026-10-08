# Independent whole-branch review and bounded corrections

Status: **NOT DEPLOYMENT READY**. One fresh-context, read-only reviewer examined baseline `34cb7490a6443682c58c72df5ab1334e7e186d76` through `98a72547cb274241fe0e918c44983e7fe36fd259`. The reviewer ran 52 targeted tests successfully and reproduced additional defects using disposable fixtures. No production system or private acceptance materials were inspected.

The implementer accepted the findings below and made one bounded correction pass. There was no second independent review. The corrections are controller-level evidence, not real container failure-recovery acceptance.

| Finding | Severity and disposition |
| --- | --- |
| Stopped public nginx prevents Java issuer health checks during startup and rollback | Critical, OPEN. Requires parent approval of a restricted maintenance path or explicit Java transport routing preserving issuer and TLS identity. |
| Existing active pointer cannot reconcile a persisted new selection marker after interruption | Important, corrected. Only the exact pending operation's intent permits reconciliation; the authenticated parent pointer and generation bytes are checked before selecting the child. Ordinary resolution still refuses mismatch. |
| Nonstandard aliases hide extra Java trust roots | Important, corrected. Enumerate all aliases and entry types using fixed-language verbose keytool output; compare count and complete alias inventory, then every expected DER certificate. An unapproved root inserted during partial import is rejected. |
| Public TLS handshakes do not prove HTTP forwarding; aggregate readiness does not independently prove every consumer | Important, OPEN. Require separate public discovery, exact SPA and authenticated forwarded API checks, plus actual Node upstream trust observations. A correct-certificate proxy returning 502 must fail. This is part of unfinished task 11, not covered by the identity-hop evidence. |
| Expired restored certificate prevents interrupted activation cleanup | Important, corrected. Late activation phases enter/reconcile the original failure cleanup before returning expiry refusal. A close-response-loss test preserves the original operation, retries closure, stops writers and records BLOCKED. |
| A later candidate can implicitly remove an already admitted root | Important, corrected. Verify and retain the active generation's complete trust anchors before adding candidate roots. Regression covers retaining a cross-CA root when a later candidate returns to the original CA. |
| Expiry monitoring considers leaf expiry only | Minor, DEFERRED. Limiting chain expiry and separate internal-certificate warning coverage remain required follow-up; no claim of complete expiry monitoring. |

The new selection test initially failed in fixture setup because it reused a certificate with the same expiry. After isolating the selection boundary, it reproduced the old implementation's actual `Selected TLS reference conflicts` failure and passed with the correction. The other three initial regression tests failed on their intended assertions before their corrections. The additional interrupted-close test exercises the existing cleanup path through the new late-expiry entry point.

See [the report](report.md) and [verification matrix](verification-matrix.md) for real-environment evidence and outstanding acceptance scenarios. No full TLS rotation or full business-flow acceptance has passed.

Final correction-pass verification: `PYTHONPATH=deploy/linux:deploy/linux/tests python -m unittest discover -s deploy/linux/tests` passed **217 tests**, zero failures/errors/skips (29.085 seconds). `git diff --check` passed. Node 2/2 and Java 14/14 evidence remains from the previously recorded runs; these corrections changed no Node or Java source.
