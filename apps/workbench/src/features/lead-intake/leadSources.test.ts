import { beforeEach, expect, it } from "vitest";
import { testSession, taskId } from "../../test/fixtures";
import { RecoveryStore } from "../session/recoveryMarker";
import { createLeadIntakeApi } from "./leadIntakeApi";

const source = { sourceAccountCode: "FIXTURE", displayName: "客户转介绍", sourceChannelCode: "MANUAL", serviceCategoryCode: "CONSULTATION", jurisdictionCode: "CN", urgencyCode: "NORMAL" };
const reply = (body: unknown, cache = "no-store") => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json", "Cache-Control": cache } });
beforeEach(() => sessionStorage.clear());
it("reads configured sources with current credentials without changing an unresolved command", async () => {
  const store = new RecoveryStore(sessionStorage);
  store.reserveWrite(taskId, "CAPTURE_LEAD", testSession().actorScopeKey, {});
  const before = store.read();
  const requests: Request[] = [];
  const api = createLeadIntakeApi(store, async request => { requests.push(request); return reply({ sources: [source] }); }, "https://law.test");
  expect(await api.sources(testSession(), new AbortController().signal)).toEqual({sources:[source],sourceSelection:"SELECTABLE"});
  expect(requests).toHaveLength(1);
  expect(requests[0].method).toBe("GET");
  expect(requests[0].url).toBe("https://law.test/api/v1/leads/intake-sources");
  expect(requests[0].headers.get("Authorization")).toBe("Bearer test-only");
  expect(requests[0].cache).toBe("no-store");
  expect(store.read()).toEqual(before);
});
it.each([
  {}, { sources: [source], unexpected: true }, { sources: [{ ...source, urgencyCode: null }] },
  { sources: [source, source] }, { sources: [{ ...source, displayName: " " }] },
  { sources: [{ ...source, displayName: "bad\nlabel" }] }, { sources: [{ ...source, sourceAccountCode: "bad value" }] },
  { sources: [{ ...source, unexpected: true }] }, { sources: Array(51).fill(source) },
  { sources: [{ ...source, sourceChannelCode: "manual" }] },
])("refuses malformed or ambiguous source metadata %#", async body => {
  const api = createLeadIntakeApi(new RecoveryStore(sessionStorage), async () => reply(body), "https://law.test");
  await expect(api.sources(testSession(), new AbortController().signal)).rejects.toThrow();
});
it("refuses cached responses and stale sessions, and accepts an empty catalog", async () => {
  const store = new RecoveryStore(sessionStorage);
  const cached = createLeadIntakeApi(store, async () => reply({ sources: [source] }, "public"), "https://law.test");
  await expect(cached.sources(testSession(), new AbortController().signal)).rejects.toThrow();
  let current = true;
  const api = createLeadIntakeApi(store, async () => { current = false; return reply({ sources: [source] }); }, "https://law.test");
  await expect(api.sources({ ...testSession(), isCurrent: () => current }, new AbortController().signal)).rejects.toThrow();
  const empty = createLeadIntakeApi(store, async () => reply({ sources: [] }), "https://law.test");
  expect(await empty.sources(testSession(), new AbortController().signal)).toEqual({sources:[],sourceSelection:"SELECTABLE"});
  const mixed = { ...source, sourceAccountCode: "sales_intake" };
  const named = createLeadIntakeApi(store, async () => reply({ sources: [mixed] }), "https://law.test");
  expect(await named.sources(testSession(), new AbortController().signal)).toEqual({sources:[mixed],sourceSelection:"SELECTABLE"});
});
