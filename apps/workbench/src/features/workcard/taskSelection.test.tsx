import { act, renderHook, waitFor } from "@testing-library/react";
import { expect, it } from "vitest";
import { createWorkbenchApi } from "../../lib/api";
import { envelope, jsonResponse, testSession } from "../../test/fixtures";
import { useCurrentCard } from "./useCurrentCard";
it("selects a task with a fresh uncached read and returns to recommendation", async () => {
  const requests: Request[] = [];
  const api = createWorkbenchApi(async r => { requests.push(r); return jsonResponse(envelope()); });
  const session = testSession();
  const { result } = renderHook(() => useCurrentCard(session, api));
  await waitFor(() => expect(result.current.envelope).not.toBeNull());
  const id = envelope().currentCard!.taskId;
  await act(async () => { await result.current.selectTask(id); });
  expect(new URL(requests.at(-1)!.url).searchParams.get("taskId")).toBe(id);
  expect(requests.at(-1)!.headers.get("If-None-Match")).toBeNull();
  await act(async () => { await result.current.selectTask(null); });
  expect(new URL(requests.at(-1)!.url).searchParams.has("taskId")).toBe(false);
});

it("does not switch when the original write outcome is unknown", async () => {
  const requests: Request[] = [];
  const api=createWorkbenchApi(async r => { requests.push(r); if(r.method === "PUT") throw new Error("connection lost"); return jsonResponse(envelope(5)); });
  const session=testSession();
  const {result}=renderHook(() => useCurrentCard(session,api));
  await waitFor(() => expect(result.current.envelope).not.toBeNull());
  await act(async () => { await result.current.save(envelope(5).currentCard!.commandForm.values); });
  expect(result.current.pending).not.toBeNull();
  const count=requests.length;
  await act(async () => { await result.current.selectTask(null); });
  expect(requests).toHaveLength(count);
  expect(result.current.pending).not.toBeNull();
});
it("clears an unavailable selection after the server returns the authorized recommendation", async () => {
  const requests: Request[]=[];
  const api=createWorkbenchApi(async r => { requests.push(r); return jsonResponse({...envelope(), ...(new URL(r.url).searchParams.has("taskId") ? {selectionNotice:"所选事项已不可处理，已返回当前可处理事项。"} : {})}); });
  const session=testSession();
  const {result}=renderHook(() => useCurrentCard(session,api));
  await waitFor(() => expect(result.current.envelope).not.toBeNull());
  await act(async () => { await result.current.selectTask("10000000-0000-4000-8000-000000000000"); });
  expect(result.current.message).toContain("所选事项已不可处理");
  await act(async () => { await result.current.refresh(); });
  expect(new URL(requests.at(-1)!.url).searchParams.has("taskId")).toBe(false);
});

it("revalidates an intake-selected task on first entry and clears it on identity change", async () => {
 const requests: Request[] = [], id = envelope().currentCard!.taskId;
 const api = createWorkbenchApi(async request => { requests.push(request); return jsonResponse(envelope()); });
 const { result, rerender } = renderHook(({ session }) => useCurrentCard(session, api, { initialTaskId: id }), { initialProps: { session: testSession() } });
 await waitFor(() => expect(result.current.envelope).not.toBeNull());
 expect(new URL(requests[0].url).searchParams.get("taskId")).toBe(id);
 rerender({ session: testSession(2) });
 await waitFor(() => expect(requests.length).toBeGreaterThan(1));
 expect(new URL(requests.at(-1)!.url).searchParams.has("taskId")).toBe(false);
});
