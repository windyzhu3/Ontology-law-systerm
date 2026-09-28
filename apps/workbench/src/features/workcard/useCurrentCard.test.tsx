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
  it("late automatic timeout preserves the paused business card", async()=>{
    vi.useFakeTimers();const pending=deferred<Response>();let reads=0;
    const api=createWorkbenchApi(async()=>++reads===2?pending.promise:jsonResponse(envelope()));
    const view=renderHook(({paused})=>useCurrentCard(session,api,{pauseAutomaticRead:paused}),{initialProps:{paused:false}});
    await act(async()=>{await vi.advanceTimersByTimeAsync(1);});
    await act(async()=>window.dispatchEvent(new Event('focus')));view.rerender({paused:true});
    await act(async()=>{await vi.advanceTimersByTimeAsync(25001);});
    expect(view.result.current.envelope).not.toBeNull();expect(view.result.current.loading).toBe(false);
    view.unmount();
  });

  it.each([503,401,'network'])("late automatic failure %s preserves committed card except revoked access", async failure => {
    const pending=deferred<Response>();let reads=0;
    const api=createWorkbenchApi(async()=>++reads===2?pending.promise:jsonResponse(envelope()));
    const view=renderHook(({paused})=>useCurrentCard(session,api,{pauseAutomaticRead:paused}),{initialProps:{paused:false}});
    await waitFor(()=>expect(view.result.current.envelope).not.toBeNull());
    await act(async()=>window.dispatchEvent(new Event('focus')));view.rerender({paused:true});
    await act(async()=>{if(failure==='network')pending.resolve(Promise.reject(Error('network unavailable')) as unknown as Response);else pending.resolve(new Response('{}',{status:failure as number,headers:{'Content-Type':'application/json'}}));});
    if(failure===401)expect(view.result.current.envelope).toBeNull();else expect(view.result.current.envelope).not.toBeNull();
    expect(view.result.current.loading).toBe(false);
  });

  it("does not publish an automatic response started before a business receipt paused navigation", async () => {
    const pending=deferred<Response>();let reads=0;
    const api=createWorkbenchApi(async()=>++reads===2?pending.promise:jsonResponse(envelope()));
    const view=renderHook(({paused})=>useCurrentCard(session,api,{pauseAutomaticRead:paused}),{initialProps:{paused:false}});
    await waitFor(()=>expect(view.result.current.envelope).not.toBeNull());
    await act(async()=>window.dispatchEvent(new Event('focus')));
    await waitFor(()=>expect(reads).toBe(2));view.rerender({paused:true});
    await act(async()=>pending.resolve(jsonResponse({...envelope(),todaySummary:'旧自动读取返回的其他事项'})));
    expect(view.result.current.envelope?.todaySummary).not.toBe('旧自动读取返回的其他事项');
    expect(view.result.current.loading).toBe(false);
    await act(async()=>{await view.result.current.refresh();});expect(reads).toBe(3);
  });

  it("pauses timer and focus reads while an embedded business card is editing or submitting", async () => {
    vi.useFakeTimers();const fetcher=vi.fn(async()=>jsonResponse({...envelope(),waitingCount:1}));
    const api=createWorkbenchApi(fetcher);
    const view=renderHook(({paused})=>useCurrentCard(session,api,{pauseAutomaticRead:paused}),{initialProps:{paused:true}});
    await act(async()=>{await vi.advanceTimersByTimeAsync(1);});expect(fetcher).toHaveBeenCalledTimes(1);
    await act(async()=>{window.dispatchEvent(new Event('focus'));await vi.advanceTimersByTimeAsync(30000);});expect(fetcher).toHaveBeenCalledTimes(1);
    view.rerender({paused:false});await act(async()=>{await vi.advanceTimersByTimeAsync(30000);});expect(fetcher).toHaveBeenCalledTimes(2);
    view.unmount();
  });
  it("ends a stalled responsibility read, permits retry and ignores its late response", async () => {
    vi.useFakeTimers();
    const pending = deferred<Response>(); const requests: Request[] = [];
    const api = createWorkbenchApi(async request => { requests.push(request); return requests.length === 1 ? pending.promise : jsonResponse({...envelope(), todaySummary:"重试后的责任"}); });
    const view = renderHook(() => useCurrentCard(session, api));
    await act(async () => { await vi.advanceTimersByTimeAsync(25001); });
    expect(view.result.current.loading).toBe(false);
    expect(view.result.current.readFailed).toBe(true);
    expect(view.result.current.error).toBe("读取当前责任超时，请点击刷新重试。");
    expect(requests[0].signal.aborted).toBe(true);
    await act(async () => { await view.result.current.refresh(); });
    expect(view.result.current.envelope?.todaySummary).toBe("重试后的责任");
    await act(async () => pending.resolve(jsonResponse({...envelope(), todaySummary:"迟到的旧责任"})));
    expect(view.result.current.envelope?.todaySummary).toBe("重试后的责任");
    expect(requests.every(r=>r.method==="GET")).toBe(true);
    view.unmount();
  });

  it("cleans up a stalled responsibility read deadline when unmounted", async () => {
    vi.useFakeTimers();
    const pending = deferred<Response>();
    const api = createWorkbenchApi(async () => pending.promise);
    const view = renderHook(() => useCurrentCard(session, api));
    await act(async () => { await vi.advanceTimersByTimeAsync(1); });
    await act(async () => view.unmount());
    expect(vi.getTimerCount()).toBe(0);
  });

  it("coalesces focus reads and lets explicit task selection supersede a cached background read", async () => {
    const pending = deferred<Response>(); const requests: Request[] = [];
    const api = createWorkbenchApi(async request => { requests.push(request); return requests.length === 2 ? pending.promise : jsonResponse(envelope()); });
    const { result } = renderHook(() => useCurrentCard(session, api));
    await waitFor(() => expect(result.current.envelope).not.toBeNull());
    await act(async () => { window.dispatchEvent(new Event("focus")); });
    await waitFor(() => expect(requests).toHaveLength(2));
    await act(async () => { window.dispatchEvent(new Event("focus")); });
    expect(requests).toHaveLength(2);
    await act(async () => { await result.current.selectTask(envelope().currentCard!.taskId); });
    expect(requests).toHaveLength(3);
    expect(requests[1].signal.aborted).toBe(true);
    expect(result.current.loading).toBe(false);
    await act(async () => pending.resolve(jsonResponse({ ...envelope(), todaySummary: "旧后台读取" })));
    expect(result.current.envelope?.todaySummary).not.toBe("旧后台读取");
  });

  it("releases an aborted background read on hide without unlocking stale writes", async () => {
    let hidden = false;
    const visibility = vi.spyOn(document, "visibilityState", "get").mockImplementation(() => hidden ? "hidden" : "visible");
    const pending = deferred<Response>(); let reads = 0;
    const api = createWorkbenchApi(async () => ++reads === 2 ? pending.promise : jsonResponse(envelope()));
    const view = renderHook(() => useCurrentCard(session, api));
    try {
      await waitFor(() => expect(view.result.current.envelope).not.toBeNull());
      act(() => { void view.result.current.refresh(); });
      await waitFor(() => expect(view.result.current.loading).toBe(true));
      act(() => { hidden = true; document.dispatchEvent(new Event("visibilitychange")); });
      expect(view.result.current.loading).toBe(false);
      expect(view.result.current.needsRefresh).toBe(true);
      expect(view.result.current.envelope).not.toBeNull();
      await act(async () => pending.resolve(jsonResponse({ ...envelope(), todaySummary: "过期响应" })));
      expect(view.result.current.envelope?.todaySummary).not.toBe("过期响应");
      act(() => { hidden = false; document.dispatchEvent(new Event("visibilitychange")); });
      await waitFor(() => expect(view.result.current.needsRefresh).toBe(false));
      expect(view.result.current.loading).toBe(false);
    } finally { view.unmount(); visibility.mockRestore(); }
  });

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

it.each(['revision','etag','id'] as const)('refuses stale ledger %s without showing a replacement card',async(field)=>{
 const card=envelope(5,false).currentCard!;
 const fetcher=vi.fn(async()=>jsonResponse(envelope(5,false)));
 const api=createWorkbenchApi(fetcher);
 const {result}=renderHook(()=>useCurrentCard(session,api,{deferInitialRead:true}));
 const expected={id:card.taskId,revision:card.taskRevision,etag:card.preconditions.taskETag};
 if(field==='revision')expected.revision++;
 if(field==='etag')expected.etag='"task.other"';
 if(field==='id')expected.id='019c7000-0000-7000-8000-000000000099';
 await act(async()=>result.current.selectTask(expected.id,expected));
 expect(result.current.envelope).toBeNull();
 expect(result.current.error).toBe('当前待办已变化，请返回商机台账重新查询后办理。');
});
