# T01 isolated browser acceptance

`OwnerExceptionBrowserIT` creates isolated PostgreSQL fixture data, starts the actual Spring HTTP adapters and runs the production React management component through `tests/t01-owner-exception-browser.mjs`. The driver starts its own local Vite server on an ephemeral port and closes it after the test. It does not depend on the review server on port 5177.

The Java fixture passes the short-lived test bearer and selected appointment through the child process's stdin. The bearer stays in Node memory. The browser route forwards requests to the actual authenticated API using Playwright's request context; no API response is mocked. This covers the rendered component and real business HTTP transaction, not production OAuth login. `R2OwnerExceptionSessionHttpIT` separately covers the real Keycloak management-only entry and permission revocation.

The browser reads the real exception, opens detail, selects the qualified receiver, enters a reason and confirms once. It requires HTTP 200/SUCCEEDED, a refreshed RESOLVED record, and a terminal detail with no second transfer action. The ledger preserves resolved history; an empty list is not the success condition. Java then verifies one handoff, one OPEN task for the receiver, the unchanged frozen opportunity owner and zero invented progress records.

Run with the repository's pinned Java/Maven/Node dependencies and installed Chrome:

```powershell
./mvnw.cmd -f backend/pom.xml test-compile failsafe:integration-test failsafe:verify '-Dit.test=OwnerExceptionBrowserIT' '-Dt01.browser.node=C:/absolute/path/to/node.exe'
```

The explicit Node property enables this browser test. The normal backend suite skips it when no browser runtime was supplied. The T01 acceptance run supplies the property, and its report must show one execution with zero skips.

Evidence is written under `output/t01-live-browser`: screenshots, a closed JSON report with HTTP method/path/status and success booleans, and safe process logs. Raw request/response bodies, bearer tokens, browser storage state and traces are not persisted. All records are synthetic and are removed with their test database. This run does not authorize production activation, modify the retained R1 golden environment or satisfy R1 release acceptance.

`OwnerExceptionWorkerAssemblyIT` additionally builds the real API jar/Worker composition over TLS and PostgreSQL. Only its isolated configuration enables `ols.worker.owner-exception-observation-enabled`; it verifies OWNER_EXCEPTION checkpoint creation, absence of INITIAL/DUE scheduling and loss of readiness after DISCOVER is revoked. Application default remains disabled.
