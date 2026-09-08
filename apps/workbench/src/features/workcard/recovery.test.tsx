import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { createWorkbenchApi } from "../../lib/api";
import { useCurrentCard } from "./useCurrentCard";
import {
  deferred,
  envelope,
  jsonResponse,
  receipt,
  selectorId,
} from "../../test/fixtures";
const session = { sessionKey: "actor", accessToken: "test-only" };
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});
const retryTransitions = [
  [400, "VALIDATION_FAILED", 412, "STALE_DRAFT", 5],
  [428, "TASK_PRECONDITION_REQUIRED", 412, "STALE_DRAFT", 5],
  [400, "VALIDATION_FAILED", 409, "DRAFT_DIGEST_MISMATCH", 5],
  [428, "TASK_PRECONDITION_REQUIRED", 409, "DRAFT_DIGEST_MISMATCH", 5],
  [400, "VALIDATION_FAILED", 422, "SUPERVISOR_UNRESOLVED", 5],
  [428, "TASK_PRECONDITION_REQUIRED", 422, "SUPERVISOR_UNRESOLVED", 5],
  [400, "VALIDATION_FAILED", 422, "SOURCE_INTAKE_OWNER_UNRESOLVED", 3],
  [428, "TASK_PRECONDITION_REQUIRED", 422, "SOURCE_INTAKE_OWNER_UNRESOLVED", 3],
] as const;
it.each(retryTransitions)(
  "replaces correction key after %i %s then %i %s and explicit repaired refresh",
  async (firstStatus, firstCode, nextStatus, nextCode, index) => {
    const writes: Request[] = [];
    const data = envelope(index, true);
    const api = createWorkbenchApi(async (request) => {
      if (request.method !== "POST") return jsonResponse(data);
      writes.push(request);
      if (writes.length === 1)
        return jsonResponse({ code: firstCode }, firstStatus);
      if (writes.length === 2)
        return jsonResponse({ code: nextCode }, nextStatus);
      return jsonResponse(
        receipt(
          request.headers.get("Idempotency-Key")!,
          index === 3 ? "DECISION_RECORD" : "LEAD_CONTACT_RESULT",
        ),
      );
    });
    const { result } = renderHook(() => useCurrentCard(session, api));
    await waitFor(() => expect(result.current.envelope).not.toBeNull());
    for (let attempt = 0; attempt < 2; attempt++) {
      await act(async () => {
        await result.current.submit(data.currentCard!.actionDraft!.values);
      });
      expect(writes).toHaveLength(attempt + 1);
      expect(result.current.needsRefresh).toBe(true);
      // The next explicit refresh models the required version/admin repair boundary.
      await act(async () => {
        await result.current.refresh();
      });
    }
    expect(writes[0].headers.get("Idempotency-Key")).toBe(
      writes[1].headers.get("Idempotency-Key"),
    );
    await act(async () => {
      await result.current.submit(data.currentCard!.actionDraft!.values);
    });
    expect(writes).toHaveLength(3);
    expect(writes[2].headers.get("Idempotency-Key")).not.toBe(
      writes[1].headers.get("Idempotency-Key"),
    );
  },
);
it("replaces the Draft correction key after a later stale rejection", async () => {
  const writes: Request[] = [];
  const api = createWorkbenchApi(async (request) => {
    if (request.method !== "PUT") return jsonResponse(envelope());
    writes.push(request);
    return writes.length === 1
      ? jsonResponse({ code: "VALIDATION_FAILED" }, 400)
      : jsonResponse({ code: "STALE_DRAFT" }, 412);
  });
  const { result } = renderHook(() => useCurrentCard(session, api));
  await waitFor(() => expect(result.current.envelope).not.toBeNull());
  for (let attempt = 0; attempt < 3; attempt++) {
    await act(async () => {
      await result.current.save({
        ...envelope().currentCard!.commandForm.values,
        resultSummary: `明确修正${attempt}`,
      });
    });
    if (attempt < 2)
      await act(async () => {
        await result.current.refresh();
      });
  }
  expect(writes).toHaveLength(3);
  expect(writes[0].headers.get("Idempotency-Key")).toBe(
    writes[1].headers.get("Idempotency-Key"),
  );
  expect(writes[2].headers.get("Idempotency-Key")).not.toBe(
    writes[1].headers.get("Idempotency-Key"),
  );
});
it("recovers the original CONTACT Evidence terminal NOT_FOUND receipt without automatic resubmission", async () => {
  const data = envelope(5, true);
  const card = data.currentCard!;
  card.commandForm.values = {
    ...card.commandForm.values,
    evidenceSubmissionId: selectorId,
  } as typeof card.commandForm.values;
  const draft = card.actionDraft!;
  draft.values = {
    ...draft.values,
    evidenceSubmissionId: selectorId,
  } as typeof draft.values;
  const writes: Request[] = [];
  let reads = 0,
    gets = 0;
  const api = createWorkbenchApi(async (request) => {
    if (request.method === "POST") {
      writes.push(request);
      throw new TypeError("lost response");
    }
    if (request.url.includes("/receipt")) {
      reads++;
      return jsonResponse({
        commandId: writes[0].headers.get("Idempotency-Key"),
        receiptId: selectorId,
        completedAt: "2026-09-08T02:10:00Z",
        outcome: "REJECTED",
        rejectionCode: "NOT_FOUND",
      });
    }
    gets++;
    return jsonResponse(
      gets === 1
        ? data
        : {
            ...data,
            currentCard: null,
            waitingCount: 0,
            chatComposer: {
              ...data.chatComposer,
              enabled: false,
              targetTaskId: null,
            },
          },
    );
  });
  const { result } = renderHook(() => useCurrentCard(session, api));
  await waitFor(() => expect(result.current.envelope).not.toBeNull());
  await act(async () => {
    await result.current.submit(card.actionDraft!.values);
  });
  expect(result.current.pending).not.toBeNull();
  expect((await writes[0].clone().json()).evidenceSubmissionId).toBe(
    selectorId,
  );
  await act(async () => {
    await result.current.recover();
  });
  expect(result.current.pending).toBeNull();
  expect(result.current.error).toBeNull();
  expect(result.current.message).toBe("本次请求未被接受，请刷新后核对。");
  expect(result.current.envelope?.currentCard).toBeNull();
  expect(gets).toBe(2);
  expect(reads).toBe(1);
  expect(writes).toHaveLength(1);
});
it("retains the same key after server validation refusal and an explicit correction", async () => {
  const writes: Request[] = [];
  const api = createWorkbenchApi(async (r) => {
    if (r.method === "PUT") {
      writes.push(r);
      return jsonResponse({ code: "VALIDATION_FAILED" }, 400);
    }
    return jsonResponse(envelope());
  });
  const { result } = renderHook(() => useCurrentCard(session, api));
  await waitFor(() => expect(result.current.envelope).not.toBeNull());
  await act(async () => {
    await result.current.save(envelope().currentCard!.commandForm.values);
    await result.current.refresh();
  });
  await act(async () => {
    await result.current.save({
      ...envelope().currentCard!.commandForm.values,
      resultSummary: "修改后重试",
    });
  });
  expect(writes).toHaveLength(2);
  expect(writes[0].headers.get("Idempotency-Key")).toBe(
    writes[1].headers.get("Idempotency-Key"),
  );
});
it("keeps payload conflicts recoverable without replacing the original key", async () => {
  const api = createWorkbenchApi(async (r) =>
    r.method === "POST"
      ? jsonResponse({ code: "COMMAND_PAYLOAD_CONFLICT" }, 409)
      : jsonResponse(envelope(5, true)),
  );
  const { result } = renderHook(() => useCurrentCard(session, api));
  await waitFor(() => expect(result.current.envelope).not.toBeNull());
  await act(async () => {
    await result.current.submit(envelope().currentCard!.commandForm.values);
  });
  expect(result.current.pending).not.toBeNull();
});
it("drops a revoked envelope and does not reuse its cache tag", async () => {
  let gets = 0;
  const api = createWorkbenchApi(async () =>
    ++gets === 1
      ? jsonResponse(envelope())
      : jsonResponse({ code: "NOT_AUTHORIZED" }, 403),
  );
  const { result } = renderHook(() => useCurrentCard(session, api));
  await waitFor(() => expect(result.current.envelope).not.toBeNull());
  await act(async () => {
    await result.current.refresh();
  });
  expect(result.current.envelope).toBeNull();
  expect(result.current.pending).toBeNull();
  await act(async () => {
    await result.current.refresh();
  });
  expect(gets).toBe(2);
});
it("cannot reuse a 304 response without a session cache", async () => {
  const api = createWorkbenchApi(
    async () =>
      new Response(null, {
        status: 304,
        headers: { ETag: '"wb.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"' },
      }),
  );
  const { result } = renderHook(() => useCurrentCard(session, api));
  await waitFor(() => expect(result.current.error).not.toBeNull());
  expect(result.current.envelope).toBeNull();
});
it("ignores an in-flight old session write after actor replacement", async () => {
  const lost = deferred<Response>();
  const api = createWorkbenchApi(async (r) =>
    r.method === "POST" ? lost.promise : jsonResponse(envelope(5, true)),
  );
  const { result, rerender } = renderHook(({ s }) => useCurrentCard(s, api), {
    initialProps: { s: session },
  });
  await waitFor(() => expect(result.current.envelope).not.toBeNull());
  let completion: Promise<void>;
  act(() => {
    completion = result.current.submit(
      envelope().currentCard!.commandForm.values,
    );
  });
  const key = result.current.pending!.key;
  rerender({ s: { sessionKey: "other", accessToken: "other-test-only" } });
  await act(async () => {
    lost.resolve(jsonResponse(receipt(key)));
    await completion;
  });
  expect(result.current.message).toBeNull();
  expect(result.current.pending).toBeNull();
});
it("bounds receipt polling without interpreting 404 as failure", async () => {
  vi.useFakeTimers();
  let reads = 0;
  const api = createWorkbenchApi(async (r) => {
    if (r.method === "POST") throw new TypeError("lost");
    if (r.url.includes("receipt")) {
      reads++;
      return jsonResponse({ code: "NOT_FOUND" }, 404);
    }
    return jsonResponse(envelope(5, true));
  });
  const { result } = renderHook(() => useCurrentCard(session, api));
  await act(async () => {
    await vi.advanceTimersByTimeAsync(1);
  });
  await act(async () => {
    await result.current.submit(envelope().currentCard!.commandForm.values);
    await vi.advanceTimersByTimeAsync(180000);
  });
  expect(reads).toBe(3);
  expect(result.current.pending).not.toBeNull();
});
it("suspends waiting polls while hidden and refreshes when visible", async () => {
  vi.useFakeTimers();
  let hidden = true,
    gets = 0;
  vi.spyOn(document, "visibilityState", "get").mockImplementation(() =>
    hidden ? "hidden" : "visible",
  );
  const api = createWorkbenchApi(async () => {
    gets++;
    return jsonResponse(envelope());
  });
  renderHook(() => useCurrentCard(session, api));
  await act(async () => {
    await vi.advanceTimersByTimeAsync(90001);
  });
  expect(gets).toBe(1);
  hidden = false;
  await act(async () => {
    document.dispatchEvent(new Event("visibilitychange"));
  });
  expect(gets).toBe(2);
});
it("blocks a second new command when a success refresh still returns the completed task", async () => {
  let posts = 0;
  const api = createWorkbenchApi(async (r) => {
    if (r.method === "POST") {
      posts++;
      return jsonResponse(receipt(r.headers.get("Idempotency-Key")!));
    }
    return jsonResponse(envelope(5, true));
  });
  const { result } = renderHook(() => useCurrentCard(session, api));
  await waitFor(() => expect(result.current.envelope).not.toBeNull());
  await act(async () => {
    await result.current.submit(envelope().currentCard!.commandForm.values);
  });
  await act(async () => {
    await result.current.submit(envelope().currentCard!.commandForm.values);
  });
  expect(posts).toBe(1);
  expect(result.current.needsRefresh).toBe(true);
});
it("rejects a successful receipt with a different completion fact as unconfirmed", async () => {
  const api = createWorkbenchApi(async (r) =>
    r.method === "POST"
      ? jsonResponse(
          receipt(r.headers.get("Idempotency-Key")!, "DECISION_RECORD"),
        )
      : jsonResponse(envelope(5, true)),
  );
  const { result } = renderHook(() => useCurrentCard(session, api));
  await waitFor(() => expect(result.current.envelope).not.toBeNull());
  await act(async () => {
    await result.current.submit(envelope().currentCard!.commandForm.values);
  });
  expect(result.current.pending).not.toBeNull();
  expect(result.current.message).toBeNull();
});
