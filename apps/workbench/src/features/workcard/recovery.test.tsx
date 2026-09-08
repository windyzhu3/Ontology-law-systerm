import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { createWorkbenchApi } from "../../lib/api";
import { useCurrentCard } from "./useCurrentCard";
import { deferred, envelope, jsonResponse, receipt } from "../../test/fixtures";
const session = { sessionKey: "actor", accessToken: "test-only" };
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
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
