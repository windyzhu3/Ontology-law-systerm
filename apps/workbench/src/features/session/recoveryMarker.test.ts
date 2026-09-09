import { beforeEach, expect, it } from "vitest";
import { markerKey, RecoveryStore } from "./recoveryMarker";
const now = Date.parse("2026-09-09T10:00:00Z");
const marker = {
  commandId: "019c7000-0000-7000-8000-000000000001",
  commandType: "RECORD_CONTACT_RESULT",
  actorScopeKey: "ask1.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  recordedAt: "2026-09-09T09:00:00Z",
};
beforeEach(() => sessionStorage.clear());
it("persists only the four recovery fields before a write and refuses another command", () => {
  const store = new RecoveryStore(sessionStorage, () => now);
  store.reserve(marker);
  expect(JSON.parse(sessionStorage.getItem(markerKey)!)).toEqual(marker);
  expect(() =>
    store.reserve({
      ...marker,
      commandId: "019c7000-0000-7000-8000-000000000002",
    }),
  ).toThrow();
  expect(store.read()).toEqual(marker);
});
it.each([
  { ...marker, payload: "private input" },
  { ...marker, commandType: "REOPEN_DUE_CONTACT_TASK" },
  { ...marker, actorScopeKey: "unknown" },
  { ...marker, recordedAt: "2026-09-09T11:00:00Z" },
  { ...marker, recordedAt: "2026-09-08T10:00:00Z" },
  { ...marker, commandId: "invalid" },
  { ...marker, recordedAt: "2026-02-30T09:00:00Z" },
])(
  "rejects invalid or expired persisted clues without permitting new writes",
  (bad) => {
    sessionStorage.setItem(markerKey, JSON.stringify(bad));
    const store = new RecoveryStore(sessionStorage, () => now);
    expect(() => store.read()).toThrow();
    expect(() => store.reserve(marker)).toThrow();
  },
);
it("does not clear another scope or command after a late response", () => {
  sessionStorage.setItem(markerKey, JSON.stringify(marker));
  const store = new RecoveryStore(sessionStorage, () => now);
  store.clear({
    ...marker,
    actorScopeKey: "ask1.bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  });
  expect(store.read()).toEqual(marker);
  store.clear(marker);
  expect(store.read()).toBeNull();
});
it("fails closed on storage read, write and removal failure", () => {
  const broken = {
    getItem() {
      throw Error();
    },
    setItem() {
      throw Error();
    },
    removeItem() {
      throw Error();
    },
  } as unknown as Storage;
  expect(() => new RecoveryStore(broken).read()).toThrow();
  expect(() => new RecoveryStore(broken).reserve(marker)).toThrow();
  expect(() => new RecoveryStore(broken).clear(marker)).toThrow();
});
it("requires explicit confirmation to abandon even an expired clue without treating it as command cancellation", () => {
  sessionStorage.setItem(
    markerKey,
    JSON.stringify({ ...marker, recordedAt: "2026-09-01T00:00:00Z" }),
  );
  const store = new RecoveryStore(sessionStorage, () => now);
  expect(() => store.abandon(false)).toThrow();
  expect(sessionStorage.getItem(markerKey)).not.toBeNull();
  store.abandon(true);
  expect(sessionStorage.getItem(markerKey)).toBeNull();
});
