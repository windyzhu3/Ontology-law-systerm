import { beforeEach, expect, it } from "vitest";
import {
  createWorkbenchApi,
  matchesReceipt,
  type WorkbenchSession,
  type OriginalWrite,
} from "../../lib/api";
import { markerKey } from "./recoveryMarker";
import {
  deferred,
  envelope,
  jsonResponse,
  receipt,
  tags,
  taskId,
} from "../../test/fixtures";
const scope = "ask1.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const session = {
  identityEpoch: 1,
  actorScopeKey: scope,
  selectedAppointmentId: taskId,
  selectedOnBehalfAppointmentId: null,
  getValidAccessToken: async () => "current-token",
  isCurrent: () => true,
  invalidate: () => {},
} as unknown as WorkbenchSession;
const original: OriginalWrite = {
  kind: "command",
  action: "RECORD_CONTACT_RESULT",
  key: taskId,
  taskId,
  headers: { "If-Match": tags.taskETag },
  body: {
    ...envelope(5, true).currentCard!.actionDraft!.values,
    draftId: taskId,
    expectedDraftRevision: 2,
    draftDigest: "a".repeat(43),
  } as Extract<OriginalWrite, { kind: "command" }>["body"],
};
beforeEach(() => sessionStorage.clear());
it("takes current credentials and paired selectors on each original-key replay", async () => {
  const writes: Request[] = [];
  let token = "new-one";
  const s = {
    ...session,
    getValidAccessToken: async () => token,
    selectedOnBehalfAppointmentId: "019c7000-0000-7000-8000-000000000002",
  };
  const api = createWorkbenchApi(async (r) => {
    writes.push(r);
    throw Error("response lost");
  });
  await expect(
    api.write(s, original, new AbortController().signal),
  ).rejects.toThrow();
  token = "new-two";
  await expect(
    api.write(s, original, new AbortController().signal),
  ).rejects.toThrow();
  expect(writes.map((r) => r.headers.get("Authorization"))).toEqual([
    "Bearer new-one",
    "Bearer new-two",
  ]);
  expect(writes[1].headers.get("X-Appointment-Id")).toBe(taskId);
  expect(writes[1].headers.get("X-On-Behalf-Appointment-Id")).toBe(
    "019c7000-0000-7000-8000-000000000002",
  );
  expect(writes[1].headers.get("If-Match")).toBe(tags.taskETag);
  expect(await writes[0].clone().json()).toEqual(
    await writes[1].clone().json(),
  );
});
it("blocks any other new write across separate transports while a clue is unresolved", async () => {
  const writes: Request[] = [];
  const fetcher = async (r: Request) => {
    writes.push(r);
    throw Error("lost");
  };
  await expect(
    createWorkbenchApi(fetcher).write(
      session,
      original,
      new AbortController().signal,
    ),
  ).rejects.toThrow();
  await expect(
    createWorkbenchApi(fetcher).write(
      session,
      { ...original, key: "019c7000-0000-7000-8000-000000000002" },
      new AbortController().signal,
    ),
  ).rejects.toThrow();
  expect(writes).toHaveLength(1);
  expect(JSON.parse(sessionStorage.getItem(markerKey)!).commandId).toBe(taskId);
});
it("never dispatches when marker persistence fails", async () => {
  const writes: Request[] = [];
  const storage = {
    getItem: () => null,
    setItem() {
      throw Error("disabled");
    },
    removeItem() {},
  } as unknown as Storage;
  const { RecoveryStore } = await import("./recoveryMarker");
  const api = createWorkbenchApi(
    async (r) => {
      writes.push(r);
      return jsonResponse(receipt(taskId));
    },
    window.location.origin,
    new RecoveryStore(storage),
  );
  await expect(
    api.write(session, original, new AbortController().signal),
  ).rejects.toThrow();
  expect(writes).toHaveLength(0);
});
it("retains clue for unknown error or malformed success and only clears accurate terminal receipt", async () => {
  let response = jsonResponse({ code: "invented" }, 400);
  const api = createWorkbenchApi(async () => response);
  await expect(
    api.write(session, original, new AbortController().signal),
  ).rejects.toThrow();
  expect(sessionStorage.getItem(markerKey)).not.toBeNull();
  response = jsonResponse({ status: "success" });
  await api.write(session, original, new AbortController().signal);
  expect(sessionStorage.getItem(markerKey)).not.toBeNull();
  response = jsonResponse(receipt(taskId));
  await api.write(session, original, new AbortController().signal);
  expect(sessionStorage.getItem(markerKey)).toBeNull();
});
it("does not send an old captured request after credential acquisition changes scope", async () => {
  const token = deferred<string>();
  let current = true;
  const requests: Request[] = [];
  const s = {
    ...session,
    getValidAccessToken: () => token.promise,
    isCurrent: () => current,
  };
  const api = createWorkbenchApi(async (r) => {
    requests.push(r);
    return jsonResponse(receipt(taskId));
  });
  const work = api
    .write(s, original, new AbortController().signal)
    .catch(() => "refused");
  current = false;
  token.resolve("late");
  expect(await work).toBe("refused");
  expect(requests).toHaveLength(0);
});
it("refuses recovery queries with a different actor scope and retains the clue", async () => {
  sessionStorage.setItem(
    markerKey,
    JSON.stringify({
      commandId: taskId,
      commandType: "RECORD_CONTACT_RESULT",
      actorScopeKey: "ask1.bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      recordedAt: new Date().toISOString(),
    }),
  );
  const requests: Request[] = [];
  const api = createWorkbenchApi(async (r) => {
    requests.push(r);
    return jsonResponse(receipt(taskId));
  });
  await expect(
    api.receipt(session, taskId, new AbortController().signal),
  ).rejects.toThrow();
  expect(requests).toHaveLength(0);
  expect(sessionStorage.getItem(markerKey)).not.toBeNull();
});
it.each([
  ["CREATE_IDENTITY_PRINCIPAL", "IDENTITY_PRINCIPAL"],
  ["RENAME_IDENTITY_PRINCIPAL", "IDENTITY_PRINCIPAL"],
  ["SUSPEND_IDENTITY_PRINCIPAL", "IDENTITY_PRINCIPAL"],
  ["RESUME_IDENTITY_PRINCIPAL", "IDENTITY_PRINCIPAL"],
  ["DISABLE_IDENTITY_PRINCIPAL", "IDENTITY_PRINCIPAL"],
  ["CREATE_ORGANIZATION_UNIT", "ORGANIZATION_UNIT"],
  ["RENAME_ORGANIZATION_UNIT", "ORGANIZATION_UNIT"],
  ["CLOSE_ORGANIZATION_UNIT", "ORGANIZATION_UNIT"],
  ["CREATE_APPOINTMENT", "APPOINTMENT"],
  ["SUSPEND_APPOINTMENT", "APPOINTMENT"],
  ["RESUME_APPOINTMENT", "APPOINTMENT"],
  ["END_APPOINTMENT", "APPOINTMENT"],
  ["CREATE_AUTHORITY_GRANT", "AUTHORITY_GRANT"],
  ["REVOKE_AUTHORITY_GRANT", "AUTHORITY_GRANT"],
])(
  "validates recovered %s against its exact public Fact",
  (commandType, factType) => {
    const marker = {
      commandId: taskId,
      commandType,
      actorScopeKey: scope,
      recordedAt: new Date().toISOString(),
    };
    const value = {
      ...receipt(taskId),
      resultFact: {
        factType,
        factRef: "opaque-identity-reference",
        revision: 0,
      },
    };
    expect(matchesReceipt(value, marker)).toBe(true);
    expect(
      matchesReceipt(
        {
          ...value,
          resultFact: { factType: "LEAD", factRef: "opaque", revision: 0 },
        },
        marker,
      ),
    ).toBe(false);
  },
);
it("does not replay a reconstructed body after another transport loses the original", async () => {
  const requests: Request[] = [];
  const first = createWorkbenchApi(async (r) => {
    requests.push(r);
    throw Error("lost");
  });
  await expect(
    first.write(session, original, new AbortController().signal),
  ).rejects.toThrow();
  const restored = createWorkbenchApi(async (r) => {
    requests.push(r);
    return jsonResponse(receipt(taskId));
  });
  await expect(
    restored.write(session, { ...original }, new AbortController().signal),
  ).rejects.toThrow();
  expect(requests).toHaveLength(1);
});
