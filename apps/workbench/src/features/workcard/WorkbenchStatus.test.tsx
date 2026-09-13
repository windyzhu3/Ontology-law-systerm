import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "../../App";
import { createWorkbenchApi } from "../../lib/api";
import { deferred, digest, envelope, jsonResponse, receipt, tags, testSession } from "../../test/fixtures";
import type { Schema } from "./contract";

afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); });

function empty(waitingCount: number): Schema["CurrentWorkCardEnvelope"] {
  return {
    todaySummary: "今日已处理八项责任。",
    currentCard: null,
    nextSummaries: [],
    waitingCount,
    chatComposer: { mode: "ACTION_DRAFT", enabled: false, targetTaskId: null, placeholder: "当前没有候选内容" },
  };
}
const refresh = () => screen.getByRole("button", { name: "刷新当前责任" });
const submit = () => screen.getByRole("button", { name: "保存联系结果" });
const tick = async (ms = 30_000) => { await act(async () => { await vi.advanceTimersByTimeAsync(ms); }); };

describe("workbench status through real App and transport", () => {
  it.each([0, 2])("does not invent zero during a slow read, then distinguishes waiting=%i", async (count) => {
    const pending = deferred<Response>();
    render(<App session={testSession()} api={createWorkbenchApi(async () => pending.promise)} />);
    expect(screen.getByText("正在读取当前责任…")).toBeVisible();
    expect(screen.queryByText("等待 0")).not.toBeInTheDocument();
    expect(screen.queryByRole("heading")).not.toBeInTheDocument();
    await act(async () => pending.resolve(jsonResponse(empty(count))));
    expect(screen.getByRole("heading", { name: count ? "当前无可处理责任，另有等待事项" : "当前暂无可处理责任" })).toBeVisible();
    expect(screen.getByText(`等待 ${count}`)).toBeVisible();
    expect(screen.getByText("今日已处理八项责任。")).toBeVisible();
    expect(screen.getByRole("button", { name: "保存候选" })).toBeDisabled();
  });

  it.each([0, 1, 2])("shows %i server next summaries without extra write actions", async (count) => {
    const data = envelope(); data.nextSummaries = data.nextSummaries.slice(0, count);
    data.todaySummary = "今日已处理八项责任。";
    render(<App session={testSession()} api={createWorkbenchApi(async () => jsonResponse(data))} />);
    await screen.findByText(data.todaySummary);
    const next = screen.getByRole("region", { name: "后续责任与等待" });
    expect(within(next).queryAllByRole("button")).toHaveLength(0);
    expect(next.querySelectorAll(".next-summary")).toHaveLength(count);
    expect(screen.getAllByRole("article")).toHaveLength(1);
    expect(screen.getAllByRole("button", { name: "保存联系结果" })).toHaveLength(1);
  });

  it("keeps an initial 503 distinct from an empty business response", async () => {
    render(<App session={testSession()} api={createWorkbenchApi(async () => jsonResponse({}, 503))} />);
    expect(await screen.findByRole("alert")).toBeVisible();
    expect(screen.queryByRole("heading")).not.toBeInTheDocument();
    expect(screen.queryByText("等待 0")).not.toBeInTheDocument();
    expect(refresh()).toBeEnabled();
  });

  it("clears confirmed-result feedback when the subsequent read denies access", async () => {
    let reads = 0;
    const api = createWorkbenchApi(async r => r.method === "POST"
      ? jsonResponse(receipt(r.headers.get("Idempotency-Key")!))
      : ++reads === 1 ? jsonResponse(envelope(5, true)) : jsonResponse({}, 403));
    render(<App session={testSession()} api={api} />);
    await screen.findByLabelText("联系说明"); fireEvent.click(submit());
    expect(await screen.findByRole("alert")).toHaveTextContent("当前内容不可用，请重新登录或联系管理员。");
    expect(screen.queryByText(/结果已记录|当前责任刷新失败/)).not.toBeInTheDocument();
    expect(screen.queryByRole("article")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "使用原请求重试" })).not.toBeInTheDocument();
  });

  it.each([503, 429, "network", 401, 403, 404] as const)("clears unavailable private data on %s without a false zero", async (failure) => {
    let reads = 0;
    const api = createWorkbenchApi(async () => {
      if (++reads === 1) return jsonResponse(envelope(5, true));
      if (failure === "network") throw new TypeError("private network detail");
      return jsonResponse({}, failure);
    });
    render(<App session={testSession()} api={api} />);
    await screen.findByLabelText("联系说明");
    fireEvent.click(refresh());
    expect(await screen.findByRole("alert")).toBeVisible();
    expect(screen.queryByText("王某 · 劳动仲裁咨询")).not.toBeInTheDocument();
    expect(screen.queryByText("等待 0")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "保存联系结果" })).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain("private network detail");
  });

  it.each(["command", "draft", "recovery"] as const)("keeps %s certainty after failed read and manual retry never writes again", async (kind) => {
    const requests: Request[] = [];
    let reads = 0;
    const api = createWorkbenchApi(async (r) => {
      requests.push(r);
      if (r.method === "PUT") return jsonResponse({ receipt: receipt(r.headers.get("Idempotency-Key")!, "ACTION_DRAFT"), draft: envelope(5, true).currentCard!.actionDraft, preconditions: tags }, 200, tags.draftETag);
      if (r.method === "POST") {
        if (kind === "recovery") throw new TypeError("lost");
        return jsonResponse(receipt(r.headers.get("Idempotency-Key")!));
      }
      if (r.url.endsWith("/receipt")) return jsonResponse(receipt(requests.find(x => x.method === "POST")!.headers.get("Idempotency-Key")!));
      return ++reads === 1 ? jsonResponse(envelope(5, true)) : reads === 2 ? jsonResponse({}, 503) : jsonResponse(empty(0));
    });
    render(<App session={testSession()} api={api} />);
    await screen.findByLabelText("联系说明");
    if (kind === "draft") {
      fireEvent.click(screen.getByRole("button", { name: "保存候选" }));
      await screen.findByText("候选已保存，请核对后确认。");
      fireEvent.click(refresh());
    } else {
      fireEvent.click(submit());
      if (kind === "recovery") fireEvent.click(await screen.findByRole("button", { name: "查询原回执" }));
    }
    await screen.findByText(kind === "draft" ? "候选已保存，当前责任刷新失败。可手动刷新。" : "结果已记录，当前责任刷新失败。可手动刷新。");
    expect(screen.queryByText(/正在刷新当前责任/)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "使用原请求重试" })).not.toBeInTheDocument();
    expect(api.recovery.read()).toBeNull();
    fireEvent.click(refresh());
    await screen.findByRole("heading", { name: "当前暂无可处理责任" });
    expect(screen.queryByText(/刷新失败|正在刷新当前责任/)).not.toBeInTheDocument();
    expect(requests.filter(r => r.method === "POST")).toHaveLength(kind === "draft" ? 0 : 1);
    expect(requests.filter(r => r.method === "PUT")).toHaveLength(kind === "draft" ? 1 : 0);
    expect(reads).toBe(3);
  });

  it("settles the refreshing notice after a successful command read", async () => {
    const pending = deferred<Response>(); let reads = 0;
    const api = createWorkbenchApi(async r => r.method === "POST" ? jsonResponse(receipt(r.headers.get("Idempotency-Key")!)) : ++reads === 1 ? jsonResponse(envelope(5, true)) : pending.promise);
    render(<App session={testSession()} api={api} />);
    await screen.findByLabelText("联系说明"); fireEvent.click(submit());
    await screen.findByText("结果已记录，正在刷新当前责任…");
    await act(async () => pending.resolve(jsonResponse(empty(0))));
    expect(screen.getByText("处理结果已记录。")).toBeVisible();
    expect(screen.queryByText(/正在刷新/)).not.toBeInTheDocument();
  });

  it("presents a recovered rejection without success styling or recorded-result wording", async () => {
    let key = "";
    const api = createWorkbenchApi(async r => {
      if (r.method === "POST") { key = r.headers.get("Idempotency-Key")!; throw new TypeError("lost"); }
      if (r.url.endsWith("/receipt")) return jsonResponse({ commandId: key, receiptId: "019c7000-0000-7000-8000-000000000004", outcome: "REJECTED", completedAt: "2026-09-08T02:10:00Z", rejectionCode: "NOT_FOUND" });
      return jsonResponse(envelope(5, true));
    });
    render(<App session={testSession()} api={api} />);
    await screen.findByLabelText("联系说明"); fireEvent.click(submit());
    fireEvent.click(await screen.findByRole("button", { name: "查询原回执" }));
    const rejected = await screen.findByText("本次请求未被接受，请刷新后核对。");
    expect(rejected).not.toHaveClass("success");
    expect(screen.queryByText(/结果已记录/)).not.toBeInTheDocument();
  });

  it.each([304, 200])("keeps dirty input, disabled completion and focus on same-scope %i", async (status) => {
    let reads = 0;
    const api = createWorkbenchApi(async () => ++reads === 1 || status === 200 ? jsonResponse(envelope(5, true)) : new Response(null, { status: 304, headers: { ETag: `"wb.${digest}"` } }));
    render(<App session={testSession()} api={api} />);
    const input = await screen.findByLabelText("联系说明");
    fireEvent.change(input, { target: { value: "尚未保存的新说明" } }); input.focus();
    fireEvent(window, new Event("focus"));
    await waitFor(() => expect(refresh()).toBeEnabled());
    expect(reads).toBe(2); expect(input).toHaveFocus(); expect(input).toHaveValue("尚未保存的新说明"); expect(submit()).toBeDisabled();
  });

  it("caps six dispatched waiting reads across hidden time, token rotation and manual refresh; resets for a new epoch", async () => {
    vi.useFakeTimers(); let hidden = false, token = "first";
    vi.spyOn(document, "visibilityState", "get").mockImplementation(() => hidden ? "hidden" : "visible");
    const requests: Request[] = [];
    const api = createWorkbenchApi(async r => { requests.push(r); return jsonResponse(empty(2)); });
    const session = { ...testSession(), getValidAccessToken: async () => token };
    const view = render(<App session={session} api={api} />); await tick(1);
    hidden = true; await tick(240_000); expect(requests).toHaveLength(1);
    hidden = false; await tick(60_000); expect(requests).toHaveLength(3);
    token = "rotated"; view.rerender(<App session={{ ...session }} api={api} />);
    fireEvent.click(refresh()); await tick(1); expect(requests).toHaveLength(4);
    await tick(120_000); expect(requests).toHaveLength(8);
    expect(requests[3].headers.get("Authorization")).toBe("Bearer rotated");
    expect(screen.getByText("自动刷新已暂停，可手动刷新。")).toBeVisible();
    await tick(120_000); expect(requests).toHaveLength(8);
    fireEvent.click(refresh()); await tick(1); await tick(60_000); expect(requests).toHaveLength(9);
    expect(screen.getByText("自动刷新已暂停，可手动刷新。")).toBeVisible();
    view.rerender(<App session={testSession(2)} api={api} />); await tick(1);
    expect(screen.queryByText("自动刷新已暂停，可手动刷新。")).not.toBeInTheDocument();
    await tick(180_000); expect(requests).toHaveLength(16);
    view.unmount(); fireEvent(window, new Event("focus")); fireEvent(document, new Event("visibilitychange")); await tick(90_000);
    expect(requests).toHaveLength(16);
  });

  it("counts only dispatched receipt lookups, retains 404 marker and allows manual query after three", async () => {
    vi.useFakeTimers(); let hidden = false; const lost = deferred<Response>();
    vi.spyOn(document, "visibilityState", "get").mockImplementation(() => hidden ? "hidden" : "visible");
    const requests: Request[] = []; let receiptReads = 0;
    const api = createWorkbenchApi(async r => {
      requests.push(r);
      if (r.method === "POST") return lost.promise;
      if (r.url.endsWith("/receipt")) { receiptReads++; return jsonResponse({}, 404); }
      return jsonResponse(envelope(5, true));
    });
    const session = testSession(); const view = render(<App session={session} api={api} />); await tick(1);
    fireEvent.click(submit()); await tick(120_000); expect(receiptReads).toBe(0);
    await act(async () => lost.resolve(jsonResponse({}, 503)));
    const marker = api.recovery.read(); expect(marker).not.toBeNull();
    hidden = true; await tick(120_000); expect(receiptReads).toBe(0);
    hidden = false; await tick(30_000); expect(receiptReads).toBe(1);
    view.rerender(<App session={{ ...session }} api={api} />);
    fireEvent.click(screen.getByRole("button", { name: "查询原回执" })); await tick(1); expect(receiptReads).toBe(2);
    await tick(60_000); expect(receiptReads).toBe(4);
    expect(screen.getByText("自动回执查询已暂停，可手动查询原回执。")).toBeVisible();
    await tick(120_000); expect(receiptReads).toBe(4);
    expect(api.recovery.read()).toEqual(marker);
    expect(requests.filter(r => r.method === "POST")).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: "查询原回执" })); await tick(1); expect(receiptReads).toBe(5);
    await tick(60_000); expect(receiptReads).toBe(5);
  });

  it("does not spend waiting quota or cancel a slow read on timer ticks", async () => {
    vi.useFakeTimers(); const slow = deferred<Response>(); const requests: Request[] = [];
    const api = createWorkbenchApi(async r => {
      requests.push(r);
      return requests.length === 2 ? slow.promise : jsonResponse(empty(2));
    });
    render(<App session={testSession()} api={api} />); await tick(1);
    fireEvent.click(refresh()); await tick(180_000);
    expect(requests).toHaveLength(2);
    expect(requests[1].signal.aborted).toBe(false);
    expect(screen.queryByText("自动刷新已暂停，可手动刷新。")).not.toBeInTheDocument();
    await act(async () => slow.resolve(jsonResponse(empty(2))));
    await tick(180_000); expect(requests).toHaveLength(8);
    expect(screen.getByText("自动刷新已暂停，可手动刷新。")).toBeVisible();
    await tick(60_000); expect(requests).toHaveLength(8);
  });

  it.each([404, "network"] as const)(
    "settles an interrupted slow read after receipt %s and resumes automatic recovery",
    async (failure) => {
      vi.useFakeTimers();
      const slow = deferred<Response>();
      const requests: Request[] = [];
      let reads = 0;
      const api = createWorkbenchApi(async r => {
        requests.push(r);
        if (r.method === "POST") throw new TypeError("lost response");
        if (r.url.endsWith("/receipt")) {
          if (failure === "network") throw new TypeError("receipt unavailable");
          return jsonResponse({}, 404);
        }
        return ++reads === 1 ? jsonResponse(envelope(5, true)) : slow.promise;
      });
      render(<App session={testSession()} api={api} />);
      await tick(1);
      fireEvent.click(submit());
      await tick(1);
      const marker = api.recovery.read();
      expect(marker).not.toBeNull();
      fireEvent.click(refresh());
      await tick(1);
      const interrupted = requests.filter(r => r.url.endsWith("/workcards/current"))[1];
      expect(refresh()).toBeDisabled();
      fireEvent.click(screen.getByRole("button", { name: "查询原回执" }));
      await tick(1);
      expect(interrupted.signal.aborted).toBe(true);
      expect.soft(refresh()).toBeEnabled();
      expect(screen.getByRole("alert")).toHaveTextContent("处理结果仍未确认");
      expect(screen.getByRole("button", { name: "使用原请求重试" })).toBeEnabled();
      expect(api.recovery.read()).toEqual(marker);
      expect(requests.filter(r => r.url.endsWith("/receipt"))).toHaveLength(1);
      await tick();
      const lookups = requests.filter(r => r.url.endsWith("/receipt"));
      expect.soft(lookups).toHaveLength(2);
      expect(lookups.every(r => r.method === "GET" && r.url.endsWith(`/commands/${marker!.commandId}/receipt`))).toBe(true);
      expect(requests.filter(r => r.method === "POST")).toHaveLength(1);
      expect(reads).toBe(2);
      expect(api.recovery.read()).toEqual(marker);
      await act(async () => slow.resolve(jsonResponse({ ...envelope(), todaySummary: "已过期的读取" })));
      expect(screen.queryByText("已过期的读取")).not.toBeInTheDocument();
    },
  );

  it("prioritizes a new unknown write over the previous saved-candidate confirmation", async () => {
    const api = createWorkbenchApi(async r => {
      if (r.method === "PUT") return jsonResponse({ receipt: receipt(r.headers.get("Idempotency-Key")!, "ACTION_DRAFT"), draft: envelope(5, true).currentCard!.actionDraft, preconditions: tags }, 200, tags.draftETag);
      if (r.method === "POST") throw new TypeError("lost");
      return jsonResponse(envelope(5, true));
    });
    render(<App session={testSession()} api={api} />);
    await screen.findByLabelText("联系说明");
    fireEvent.click(screen.getByRole("button", { name: "保存候选" }));
    await screen.findByText("候选已保存，请核对后确认。");
    fireEvent.click(submit());
    expect(await screen.findByRole("alert")).toHaveTextContent("尚未确认保存结果");
    expect(screen.queryByText(/候选已保存|结果已记录/)).not.toBeInTheDocument();
    expect(submit()).toBeDisabled();
    expect(screen.getByRole("button", { name: "使用原请求重试" })).toBeEnabled();
    expect(api.recovery.read()).not.toBeNull();
  });

  it("keeps an unknown POST above a subsequent GET failure without losing its original marker", async () => {
    const requests: Request[] = [];
    let reads = 0;
    const api = createWorkbenchApi(async r => {
      requests.push(r);
      if (r.method === "POST") throw new TypeError("lost");
      return ++reads === 1 ? jsonResponse(envelope(5, true)) : jsonResponse({}, 503);
    });
    render(<App session={testSession()} api={api} />);
    await screen.findByLabelText("联系说明"); fireEvent.click(submit());
    await screen.findByRole("alert");
    const marker = api.recovery.read();
    fireEvent.click(refresh());
    expect(await screen.findByText("结果尚未确认，当前责任暂时无法读取。请核对原回执；请勿重复发起。")).toBeVisible();
    expect(screen.getByRole("button", { name: "查询原回执" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "使用原请求重试" })).toBeEnabled();
    expect(api.recovery.read()).toEqual(marker);
    expect(requests.filter(r => r.method === "POST")).toHaveLength(1);
    expect(reads).toBe(2);
  });

  it("retains confirmed rejection when its responsibility refresh fails and retries only GET", async () => {
    let key = "", reads = 0; const requests: Request[] = [];
    const api = createWorkbenchApi(async r => {
      requests.push(r);
      if (r.method === "POST") { key = r.headers.get("Idempotency-Key")!; throw new TypeError("lost"); }
      if (r.url.endsWith("/receipt")) return jsonResponse({ commandId: key, receiptId: "019c7000-0000-7000-8000-000000000004", outcome: "REJECTED", completedAt: "2026-09-08T02:10:00Z", rejectionCode: "NOT_FOUND" });
      return ++reads === 1 ? jsonResponse(envelope(5, true)) : reads === 2 ? jsonResponse({}, 503) : jsonResponse(empty(0));
    });
    render(<App session={testSession()} api={api} />);
    await screen.findByLabelText("联系说明"); fireEvent.click(submit());
    fireEvent.click(await screen.findByRole("button", { name: "查询原回执" }));
    const rejected = await screen.findByText("本次请求未被接受，当前责任刷新失败。可手动刷新。");
    expect(rejected).not.toHaveClass("success");
    expect(screen.queryByText(/结果已记录/)).not.toBeInTheDocument();
    expect(api.recovery.read()).toBeNull();
    fireEvent.click(refresh());
    await screen.findByRole("heading", { name: "当前暂无可处理责任" });
    expect(screen.getByText("本次请求未被接受，请刷新后核对。")).not.toHaveClass("success");
    expect(requests.filter(r => r.method === "POST")).toHaveLength(1);
    expect(reads).toBe(3);
  });
});
