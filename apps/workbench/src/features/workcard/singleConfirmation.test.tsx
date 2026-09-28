import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { createWorkbenchApi } from "../../lib/api";
import { deferred, digest, envelope, jsonResponse, receipt, tags, testSession } from "../../test/fixtures";
import { opportunityEnvelope, opportunityValues } from "../../test/opportunityFixtures";
import { useCurrentCard } from "./useCurrentCard";

const session = testSession();

function savedResult(key: string, values: Record<string, unknown>, revision = 7) {
  const draft = {
    ...envelope(5, true).currentCard!.actionDraft!,
    draftRevision: revision,
    values,
    digest,
  };
  return jsonResponse({
    receipt: { ...receipt(key, "ACTION_DRAFT"), resultFact: { factType: "ACTION_DRAFT", factRef: "safe-result-reference", revision } },
    draft,
    preconditions: { ...tags, draftETag: tags.draftETag },
  }, 201, tags.draftETag);
}

describe("single confirmation chain", () => {
  it("saves an unsaved R1 candidate then submits the exact returned draft version", async () => {
    const requests: Request[] = [];
    const values = envelope(5).currentCard!.commandForm.values;
    const api = createWorkbenchApi(async (r) => {
      requests.push(r);
      if (r.method === "PUT") return savedResult(r.headers.get("Idempotency-Key")!, values, 7);
      if (r.method === "POST") return jsonResponse(receipt(r.headers.get("Idempotency-Key")!));
      return jsonResponse(envelope(5));
    });
    const { result } = renderHook(() => useCurrentCard(session, api));
    await waitFor(() => expect(result.current.envelope).not.toBeNull());
    await act(async () => { await result.current.confirm(values); });
    expect(requests.filter((r) => r.method === "PUT" || r.method === "POST").map((r) => r.method)).toEqual(["PUT", "POST"]);
    expect(await requests.find((r) => r.method === "POST")!.clone().json()).toMatchObject({ expectedDraftRevision: 7, draftDigest: digest });
  });

  it("saves an unsaved R2 candidate then submits it", async () => {
    const requests: Request[] = [];
    const values = opportunityValues();
    const api = createWorkbenchApi(async (r) => {
      requests.push(r);
      if (r.method === "PUT") {
        const key = r.headers.get("Idempotency-Key")!;
        const draft = { ...opportunityEnvelope(values).currentCard!.actionDraft!, draftRevision: 7 };
        return jsonResponse({ receipt: { ...receipt(key, "ACTION_DRAFT"), resultFact: { factType: "ACTION_DRAFT", factRef: "safe-result-reference", revision: 7 } }, draft, preconditions: tags }, 201, tags.draftETag);
      }
      if (r.method === "POST") return jsonResponse(receipt(r.headers.get("Idempotency-Key")!, "OPPORTUNITY_PROGRESS"));
      return jsonResponse(opportunityEnvelope());
    });
    const { result } = renderHook(() => useCurrentCard(session, api));
    await waitFor(() => expect(result.current.envelope).not.toBeNull());
    await act(async () => { await result.current.confirm(values); });
    expect(requests.filter((r) => ["PUT", "POST"].includes(r.method)).map((r) => r.method)).toEqual(["PUT", "POST"]);
  });

  it("does not submit when the draft save result is unknown and blocks concurrent chain calls", async () => {
    const gate = deferred<Response>();
    const requests: Request[] = [];
    const values = envelope(5).currentCard!.commandForm.values;
    const api = createWorkbenchApi(async (r) => {
      requests.push(r);
      if (r.method === "PUT") return gate.promise;
      return jsonResponse(envelope(5));
    });
    const { result } = renderHook(() => useCurrentCard(session, api));
    await waitFor(() => expect(result.current.envelope).not.toBeNull());
    let first!: Promise<void>;
    act(() => { first = result.current.confirm(values); void result.current.confirm(values); void result.current.refresh(); });
    expect(result.current.busy).toBe(true);
    await waitFor(() => expect(requests.filter((r) => r.method === "PUT")).toHaveLength(1));
    await act(async () => { gate.resolve(new Response(null, { status: 502 })); await first; });
    expect(requests.filter((r) => r.method === "POST")).toHaveLength(0);
  });

  it("returns true from save only for an exact usable draft", async () => {
    const values = envelope(5).currentCard!.commandForm.values;
    let malformed = false;
    const api = createWorkbenchApi(async (r) => {
      if (r.method === "PUT") return malformed ? new Response(null, { status: 502 }) : savedResult(r.headers.get("Idempotency-Key")!, values);
      return jsonResponse(envelope(5));
    });
    const { result } = renderHook(() => useCurrentCard(session, api));
    await waitFor(() => expect(result.current.envelope).not.toBeNull());
    let ok = false;
    await act(async () => { ok = await result.current.save(values); });
    expect(ok).toBe(true);
    malformed = true;
    await act(async () => { ok = await result.current.save(values); });
    expect(ok).toBe(false);
  });

  it("treats an exact but non-editable saved draft as unusable and never continues confirmation", async () => {
    const values = envelope(5).currentCard!.commandForm.values;
    const requests: Request[] = [];
    const api = createWorkbenchApi(async (r) => {
      requests.push(r);
      if (r.method === "PUT") {
        const response = savedResult(r.headers.get("Idempotency-Key")!, values);
        const data = await response.clone().json();
        data.draft.editable = false;
        return jsonResponse(data, 201, tags.draftETag);
      }
      if (r.method === "POST") return jsonResponse(receipt(r.headers.get("Idempotency-Key")!));
      return jsonResponse(envelope(5));
    });
    const { result } = renderHook(() => useCurrentCard(session, api));
    await waitFor(() => expect(result.current.envelope).not.toBeNull());
    let saved = true;
    await act(async () => { saved = await result.current.save(values); });
    expect(saved).toBe(false);

    const secondApi = createWorkbenchApi(async (r) => {
      requests.push(r);
      if (r.method === "PUT") {
        const response = savedResult(r.headers.get("Idempotency-Key")!, values);
        const data = await response.clone().json();
        data.draft.editable = false;
        return jsonResponse(data, 201, tags.draftETag);
      }
      if (r.method === "POST") return jsonResponse(receipt(r.headers.get("Idempotency-Key")!));
      return jsonResponse(envelope(5));
    });
    const confirmation = renderHook(() => useCurrentCard(session, secondApi));
    await waitFor(() => expect(confirmation.result.current.envelope).not.toBeNull());
    await act(async () => { await confirmation.result.current.confirm(values); });
    expect(requests.filter((r) => r.method === "POST")).toHaveLength(0);
  });

  it("does not continue to command after the session is replaced during save", async () => {
    const gate = deferred<Response>();
    const requests: Request[] = [];
    const values = envelope(5).currentCard!.commandForm.values;
    const api = createWorkbenchApi(async (r) => {
      requests.push(r);
      if (r.method === "PUT") return gate.promise;
      return jsonResponse(envelope(5));
    });
    const { result, rerender } = renderHook(({ currentSession }) => useCurrentCard(currentSession, api), {
      initialProps: { currentSession: session },
    });
    await waitFor(() => expect(result.current.envelope).not.toBeNull());
    let completion!: Promise<void>;
    act(() => { completion = result.current.confirm(values); });
    await waitFor(() => expect(requests.filter((r) => r.method === "PUT")).toHaveLength(1));
    rerender({ currentSession: testSession(2) });
    await act(async () => { gate.resolve(savedResult(requests.find((r) => r.method === "PUT")!.headers.get("Idempotency-Key")!, values)); await completion; });
    expect(requests.filter((r) => r.method === "POST")).toHaveLength(0);
  });

  it("performs zero writes for incomplete values", async () => {
    const requests: Request[] = [];
    const api = createWorkbenchApi(async (r) => { requests.push(r); return jsonResponse(envelope(5)); });
    const { result } = renderHook(() => useCurrentCard(session, api));
    await waitFor(() => expect(result.current.envelope).not.toBeNull());
    await act(async () => { await result.current.confirm({}); });
    expect(requests.filter((r) => r.method !== "GET")).toHaveLength(0);
  });
});
