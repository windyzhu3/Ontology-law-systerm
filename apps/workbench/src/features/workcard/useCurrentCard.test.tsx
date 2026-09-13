import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createWorkbenchApi } from "../../lib/api";
import { useCurrentCard } from "./useCurrentCard";
import {
  deferred,
  envelope,
  jsonResponse,
  digest,
  receipt,
  tags,
} from "../../test/fixtures";
import { testSession } from "../../test/fixtures";
const session = testSession();
afterEach(() => vi.useRealTimers());
describe("workcard concurrency and recovery", () => {
  it("ignores an older GET arriving after a newer GET", async () => {
    const old = deferred<Response>();
    let n = 0;
    const api = createWorkbenchApi(async () =>
      ++n === 1
        ? old.promise
        : jsonResponse({ ...envelope(), todaySummary: "最新摘要" }),
    );
    const { result } = renderHook(() => useCurrentCard(session, api));
    await waitFor(() => expect(n).toBe(1));
    await act(async () => {
      await result.current.refresh();
    });
    expect(result.current.envelope?.todaySummary).toBe("最新摘要");
    await act(async () => old.resolve(jsonResponse(envelope())));
    expect(result.current.envelope?.todaySummary).toBe("最新摘要");
  });
  it("retains the authorized envelope on 304 with the Workbench tag only", async () => {
    const requests: Request[] = [];
    const api = createWorkbenchApi(async (r) => {
      requests.push(r);
      return requests.length === 1
        ? jsonResponse(envelope())
        : new Response(null, {
            status: 304,
            headers: { ETag: `"wb.${digest}"` },
          });
    });
    const { result } = renderHook(() => useCurrentCard(session, api));
    await waitFor(() => expect(result.current.envelope).not.toBeNull());
    await act(async () => {
      await result.current.refresh();
    });
    expect(result.current.envelope?.waitingCount).toBe(1);
    expect(requests[1].headers.get("If-None-Match")).toBe(`"wb.${digest}"`);
  });
  it("uses Draft ETag for updates and Task ETag plus exact saved values for completion", async () => {
    const requests: Request[] = [];
    const api = createWorkbenchApi(async (r) => {
      requests.push(r);
      if (r.method === "PUT")
        return jsonResponse(
          {
            receipt: receipt(r.headers.get("Idempotency-Key")!, "ACTION_DRAFT"),
            draft: envelope(5, true).currentCard!.actionDraft,
            preconditions: tags,
          },
          200,
          tags.draftETag,
        );
      if (r.method === "POST")
        return jsonResponse(receipt(r.headers.get("Idempotency-Key")!));
      return jsonResponse(envelope(5, true));
    });
    const { result } = renderHook(() => useCurrentCard(session, api));
    await waitFor(() => expect(result.current.envelope).not.toBeNull());
    await act(async () => {
      await result.current.save(envelope(5).currentCard!.commandForm.values);
    });
    expect(
      requests.find((r) => r.method === "PUT")!.headers.get("If-Match"),
    ).toBe(tags.draftETag);
    await act(async () => {
      await result.current.submit(envelope(5).currentCard!.commandForm.values);
    });
    const post = requests.find((r) => r.method === "POST")!;
    expect(post.headers.get("If-Match")).toBe(tags.taskETag);
    expect(await post.clone().json()).toEqual({
      ...envelope(5, true).currentCard!.actionDraft!.values,
      draftId: envelope(5, true).currentCard!.actionDraft!.draftId,
      expectedDraftRevision: 2,
      draftDigest: digest,
    });
  });
  it("blocks double submit and recovers a lost response by original receipt without new key", async () => {
    const requests: Request[] = [];
    const api = createWorkbenchApi(async (r) => {
      requests.push(r);
      if (r.method === "POST") throw new TypeError("lost");
      if (r.url.includes("/receipt"))
        return jsonResponse(
          receipt(
            requests
              .find((x) => x.method === "POST")!
              .headers.get("Idempotency-Key")!,
          ),
        );
      return jsonResponse(envelope(5, true));
    });
    const { result } = renderHook(() => useCurrentCard(session, api));
    await waitFor(() => expect(result.current.envelope).not.toBeNull());
    await act(async () => {
      await Promise.all([
        result.current.submit(envelope(5).currentCard!.commandForm.values),
        result.current.submit(envelope(5).currentCard!.commandForm.values),
      ]);
    });
    expect(requests.filter((r) => r.method === "POST")).toHaveLength(1);
    expect(result.current.pending).not.toBeNull();
    await act(async () => {
      await result.current.recover();
    });
    expect(result.current.pending).toBeNull();
    expect(requests.filter((r) => r.method === "POST")).toHaveLength(1);
  });
  it("failed receipt lookup preserves the complete original request for explicit same-key replay", async () => {
    const requests: Request[] = [];
    let posts = 0;
    const api = createWorkbenchApi(async (r) => {
      requests.push(r);
      if (r.method === "POST") {
        if (++posts === 1) throw new TypeError("lost");
        return jsonResponse(receipt(r.headers.get("Idempotency-Key")!));
      }
      if (r.url.includes("/receipt"))
        return jsonResponse({ code: "SERVICE_UNAVAILABLE" }, 503);
      return jsonResponse(envelope(5, true));
    });
    const { result } = renderHook(() => useCurrentCard(session, api));
    await waitFor(() => expect(result.current.envelope).not.toBeNull());
    await act(async () => {
      await result.current.submit(envelope(5).currentCard!.commandForm.values);
      await result.current.recover();
    });
    expect(result.current.pending).not.toBeNull();
    await act(async () => {
      await result.current.replay();
    });
    const writes = requests.filter((r) => r.method === "POST");
    expect(writes).toHaveLength(2);
    expect(writes[0].headers.get("Idempotency-Key")).toBe(
      writes[1].headers.get("Idempotency-Key"),
    );
    expect(await writes[0].clone().text()).toBe(await writes[1].clone().text());
    expect(writes[0].headers.get("If-Match")).toBe(
      writes[1].headers.get("If-Match"),
    );
  });
  it("lost Draft save is replayed with original creation precondition and key", async () => {
    const requests: Request[] = [];
    let puts = 0;
    const api = createWorkbenchApi(async (r) => {
      requests.push(r);
      if (r.method === "PUT") {
        if (++puts === 1) throw new TypeError("lost");
        return jsonResponse(
          {
            receipt: receipt(r.headers.get("Idempotency-Key")!, "ACTION_DRAFT"),
            draft: envelope(5, true).currentCard!.actionDraft,
            preconditions: tags,
          },
          201,
          tags.draftETag,
        );
      }
      return jsonResponse(envelope());
    });
    const { result } = renderHook(() => useCurrentCard(session, api));
    await waitFor(() => expect(result.current.envelope).not.toBeNull());
    await act(async () => {
      await result.current.save(envelope().currentCard!.commandForm.values);
    });
    await act(async () => {
      await result.current.replay();
    });
    const writes = requests.filter((r) => r.method === "PUT");
    expect(writes).toHaveLength(2);
    expect(writes[0].headers.get("Idempotency-Key")).toBe(
      writes[1].headers.get("Idempotency-Key"),
    );
    expect(writes[1].headers.get("If-None-Match")).toBe("*");
    expect(
      result.current.envelope?.currentCard?.actionDraft?.draftRevision,
    ).toBe(2);
  });
  it("bounds waiting polling and cleans timers after unmount", async () => {
    vi.useFakeTimers();
    let gets = 0;
    const api = createWorkbenchApi(async () => {
      gets++;
      return jsonResponse(envelope());
    });
    const { unmount } = renderHook(() => useCurrentCard(session, api));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(180001);
    });
    expect(gets).toBe(7);
    unmount();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(180001);
    });
    expect(gets).toBe(7);
  });
});
