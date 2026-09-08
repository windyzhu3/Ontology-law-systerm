import { useCallback, useEffect, useRef, useState } from "react";
import {
  TransportError,
  type OriginalWrite,
  type WorkbenchApi,
  type WorkbenchSession,
} from "../../lib/api";
import {
  candidate,
  etag,
  isObject,
  parseEnvelope,
  sameValues,
  validDraft,
  validPreconditions,
  validReceipt,
  type Envelope,
  type Values,
  type PublicReceipt,
} from "./contract";

interface State {
  envelope: Envelope | null;
  loading: boolean;
  busy: boolean;
  error: string | null;
  message: string | null;
  pending: OriginalWrite | null;
  needsRefresh: boolean;
}
const initial: State = {
  envelope: null,
  loading: false,
  busy: false,
  error: null,
  message: null,
  pending: null,
  needsRefresh: false,
};
const ambiguous =
  "尚未确认保存结果。请查询原回执，或使用原请求重试；请勿重复发起。";
export function useCurrentCard(
  session: WorkbenchSession | null | undefined,
  api: WorkbenchApi,
) {
  const [state, setState] = useState<State>(initial);
  const stateRef = useRef(state);
  const identity = useRef(session);
  const generation = useRef(0);
  const alive = useRef(true);
  const wbTag = useRef<string | null>(null);
  const getController = useRef<AbortController | null>(null);
  const writeController = useRef<AbortController | null>(null);
  const locked = useRef(false);
  const denied = useRef(false);
  const completedTask = useRef<string | null>(null);
  const correction = useRef<{
    key: string;
    taskId: string;
    kind: OriginalWrite["kind"];
  } | null>(null);
  const update = useCallback((patch: Partial<State>) => {
    stateRef.current = { ...stateRef.current, ...patch };
    setState(stateRef.current);
  }, []);
  // Session replacement invalidates old asynchronous callbacks before the next effect.
  const sessionChanged =
    identity.current?.sessionKey !== session?.sessionKey ||
    identity.current?.accessToken !== session?.accessToken;
  if (sessionChanged) {
    identity.current = session;
    generation.current++;
    getController.current?.abort();
    writeController.current?.abort();
    wbTag.current = null;
    stateRef.current = initial;
    locked.current = false;
    denied.current = false;
    completedTask.current = null;
    correction.current = null;
  }
  const valid = (captured: WorkbenchSession) =>
    alive.current &&
    identity.current?.sessionKey === captured.sessionKey &&
    identity.current?.accessToken === captured.accessToken;
  const clearPrivate = () => {
    generation.current++;
    wbTag.current = null;
    denied.current = true;
    getController.current?.abort();
    writeController.current?.abort();
    locked.current = false;
    update({ ...initial, error: "当前内容不可用，请重新登录或联系管理员。" });
  };

  const refresh = useCallback(async () => {
    if (!session || denied.current || locked.current) return;
    const captured = session,
      currentGeneration = ++generation.current;
    getController.current?.abort();
    const controller = new AbortController();
    getController.current = controller;
    const cached = stateRef.current.envelope,
      tag = wbTag.current;
    update({ loading: true });
    try {
      const r = await api.current(captured, tag, controller.signal);
      if (!valid(captured) || currentGeneration !== generation.current) return;
      if (!etag(r.etag, "wb")) throw new Error("Invalid envelope");
      if (r.status === 304) {
        if (!cached || !tag || r.etag !== tag) throw new Error("Invalid cache");
        update({
          loading: false,
          needsRefresh: cached.currentCard?.taskId === completedTask.current,
        });
        return;
      }
      const envelope = parseEnvelope(r.data);
      wbTag.current = r.etag;
      update({
        envelope,
        loading: false,
        error: null,
        needsRefresh: envelope.currentCard?.taskId === completedTask.current,
      });
    } catch (error) {
      if (
        !valid(captured) ||
        currentGeneration !== generation.current ||
        controller.signal.aborted
      )
        return;
      if (
        error instanceof TransportError &&
        [401, 403, 404].includes(error.status)
      ) {
        clearPrivate();
        return;
      }
      wbTag.current = null;
      update({
        envelope: null,
        loading: false,
        error:
          error instanceof TransportError
            ? error.message
            : "暂时无法显示工作卡，请刷新后重试。",
        needsRefresh: true,
      });
    }
  }, [session?.sessionKey, session?.accessToken, api, update]);

  const acceptReceipt = async (
    receipt: PublicReceipt,
    original: OriginalWrite,
  ) => {
    if (correction.current?.key === original.key) correction.current = null;
    if (original.kind === "command" && receipt.outcome !== "REJECTED")
      completedTask.current = original.taskId;
    update({
      pending: null,
      message:
        receipt.outcome === "REJECTED"
          ? "本次请求未被接受，请刷新后核对。"
          : original.kind === "draft"
            ? "候选已保存，请核对后确认。"
            : "处理结果已记录，正在刷新当前责任。",
      error: null,
    });
    if (receipt.outcome === "REJECTED") update({ needsRefresh: true });
    wbTag.current = null;
  };
  const perform = async (original: OriginalWrite) => {
    if (!session || locked.current || denied.current) return;
    const captured = session;
    locked.current = true;
    generation.current++;
    getController.current?.abort();
    const controller = new AbortController();
    writeController.current = controller;
    update({ busy: true, loading: false, error: null, pending: original });
    let refreshAfter = false;
    try {
      const r = await api.write(captured, original, controller.signal);
      if (!valid(captured)) return;
      const data: unknown = r.data;
      const receipt =
        original.kind === "draft" && isObject(data) ? data.receipt : data;
      if (
        !validReceipt(receipt, original.key) ||
        !matchesFact(receipt, original)
      )
        throw new Error("Invalid response");
      if (original.kind === "draft") {
        const d: unknown = data;
        if (
          !isObject(d) ||
          !validDraft(d.draft, original.body.actionCode) ||
          !validPreconditions(d.preconditions) ||
          !etag(r.etag, "draft") ||
          d.preconditions.draftETag !== r.etag ||
          !sameValues(d.draft.values, original.body.values) ||
          receipt.outcome === "REJECTED" ||
          receipt.resultFact.factType !== "ACTION_DRAFT" ||
          !("revision" in receipt.resultFact) ||
          receipt.resultFact.revision !== d.draft.draftRevision
        )
          throw new Error("Invalid draft response");
        const envelope = stateRef.current.envelope;
        if (envelope?.currentCard?.taskId === original.taskId)
          update({
            envelope: parseEnvelope({
              ...envelope,
              currentCard: {
                ...envelope.currentCard,
                actionDraft: d.draft,
                preconditions: d.preconditions,
              },
            }),
          });
      } else refreshAfter = true;
      await acceptReceipt(receipt, original);
    } catch (error) {
      if (!valid(captured)) return;
      if (
        error instanceof TransportError &&
        [401, 403, 404].includes(error.status)
      ) {
        clearPrivate();
        return;
      }
      if (
        error instanceof TransportError &&
        error.code === "COMMAND_PAYLOAD_CONFLICT"
      )
        update({ error: "原请求存在冲突，请查询原回执核对；请勿重新发起。" });
      else if (
        error instanceof TransportError &&
        error.status < 500 &&
        error.status !== 429
      ) {
        if (error.status === 400 || error.status === 428)
          correction.current = {
            key: original.key,
            taskId: original.taskId,
            kind: original.kind,
          };
        else correction.current = null;
        update({ pending: null, error: error.message, needsRefresh: true });
      } else update({ error: ambiguous });
    } finally {
      if (valid(captured)) {
        locked.current = false;
        update({ busy: false });
        if (refreshAfter) await refresh();
      }
    }
  };
  const save = async (values: Values) => {
    const s = stateRef.current,
      card = s.envelope?.currentCard;
    if (
      !card ||
      locked.current ||
      s.pending ||
      s.needsRefresh ||
      !card.primaryCommand.enabled ||
      card.actionDraft?.editable === false
    )
      return;
    try {
      const body = candidate(card, values);
      const previous = correction.current;
      await perform({
        kind: "draft",
        key:
          previous?.taskId === card.taskId && previous.kind === "draft"
            ? previous.key
            : crypto.randomUUID(),
        taskId: card.taskId,
        headers: card.preconditions.draftETag
          ? { "If-Match": card.preconditions.draftETag }
          : { "If-None-Match": "*" },
        body,
      });
    } catch (error) {
      update({
        error: error instanceof Error ? error.message : "请核对候选内容。",
      });
    }
  };
  const submit = async (values: Values) => {
    const s = stateRef.current,
      card = s.envelope?.currentCard;
    if (
      !card ||
      locked.current ||
      s.pending ||
      s.needsRefresh ||
      !card.primaryCommand.enabled ||
      card.actionDraft?.editable === false
    )
      return;
    try {
      const body = candidate(card, values),
        draft = card.actionDraft;
      if (!draft || !sameValues(body.values, draft.values)) {
        update({ error: "请先保存当前候选，再确认处理结果。" });
        return;
      }
      const command = {
        ...draft.values,
        draftId: draft.draftId,
        expectedDraftRevision: draft.draftRevision,
        draftDigest: draft.digest,
      };
      const previous = correction.current;
      await perform({
        kind: "command",
        key:
          previous?.taskId === card.taskId && previous.kind === "command"
            ? previous.key
            : crypto.randomUUID(),
        taskId: card.taskId,
        action: body.actionCode,
        headers: { "If-Match": card.preconditions.taskETag },
        body: command,
      });
    } catch (error) {
      update({
        error: error instanceof Error ? error.message : "请核对候选内容。",
      });
    }
  };
  const recover = async () => {
    const original = stateRef.current.pending;
    if (!session || !original || locked.current || denied.current) return;
    const captured = session;
    locked.current = true;
    const controller = new AbortController();
    writeController.current = controller;
    update({ busy: true });
    let found = false;
    try {
      const r = await api.receipt(captured, original.key, controller.signal);
      if (!valid(captured)) return;
      if (!validReceipt(r.data, original.key) || !matchesFact(r.data, original))
        throw new Error("Invalid receipt");
      await acceptReceipt(r.data, original);
      found = true;
    } catch (error) {
      if (!valid(captured)) return;
      if (
        error instanceof TransportError &&
        [401, 403].includes(error.status)
      ) {
        clearPrivate();
        return;
      }
      update({
        error:
          "暂时无法查询原回执，处理结果仍未确认。可稍后查询，或使用原请求重试。",
      });
    } finally {
      if (valid(captured)) {
        locked.current = false;
        update({ busy: false });
        if (found) await refresh();
      }
    }
  };
  const replay = async () => {
    const original = stateRef.current.pending;
    if (original) await perform(original);
  };
  const refreshRef = useRef(refresh);
  refreshRef.current = refresh;
  const recoverRef = useRef(recover);
  recoverRef.current = recover;
  useEffect(() => {
    alive.current = true;
    update(initial);
    wbTag.current = null;
    denied.current = false;
    void refreshRef.current();
    let waitingAttempts = 0,
      receiptAttempts = 0;
    const tick = setInterval(() => {
      if (document.visibilityState === "hidden" || denied.current) return;
      if (stateRef.current.pending) {
        if (receiptAttempts++ < 3) void recoverRef.current();
      } else if (
        (stateRef.current.envelope?.waitingCount ?? 0) > 0 &&
        waitingAttempts++ < 6
      )
        void refreshRef.current();
    }, 30_000);
    const focus = () => {
      if (document.visibilityState !== "hidden") void refreshRef.current();
    };
    const visibility = () => {
      if (document.visibilityState === "hidden") {
        generation.current++;
        getController.current?.abort();
      } else focus();
    };
    window.addEventListener("focus", focus);
    document.addEventListener("visibilitychange", visibility);
    return () => {
      alive.current = false;
      generation.current++;
      getController.current?.abort();
      writeController.current?.abort();
      clearInterval(tick);
      window.removeEventListener("focus", focus);
      document.removeEventListener("visibilitychange", visibility);
    };
  }, [session?.sessionKey, session?.accessToken, api, update]);
  return {
    ...(sessionChanged ? initial : state),
    refresh,
    save,
    submit,
    recover,
    replay,
  };
}

function matchesFact(receipt: PublicReceipt, original: OriginalWrite) {
  if (receipt.outcome === "REJECTED") return true;
  const fact =
    original.kind === "draft"
      ? "ACTION_DRAFT"
      : original.action === "RECORD_CONTACT_RESULT"
        ? "LEAD_CONTACT_RESULT"
        : original.action === "ASSIGN_LEAD"
          ? "LEAD_ASSIGNMENT"
          : original.action === "COMPLETE_LEAD_INGRESS"
            ? "LEAD"
            : "DECISION_RECORD";
  return receipt.resultFact.factType === fact;
}
