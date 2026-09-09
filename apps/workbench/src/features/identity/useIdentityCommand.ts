import { useEffect, useRef, useState } from "react";
import type { components } from "../../generated/api/schema";
import { TransportError, type WorkbenchSession } from "../../lib/sessionTransport";
import type { IdentityApi, IdentityOriginalWrite } from "./identityApi";

type WithoutKey<T> = T extends unknown ? Omit<T, "key"> : never;
export type IdentityWriteDraft = WithoutKey<IdentityOriginalWrite>;
export type IdentityCreatePage = components["schemas"]["IdentityAdminPageV1"];
export type IdentityLifecycleType = Exclude<IdentityOriginalWrite["commandType"], `CREATE_${string}` | `RENAME_${string}`>;
export type IdentityAction = { commandType: IdentityLifecycleType; targetId: string; ifMatch: string; targetName: string; label: string; verb: string; impact: string };
export type IdentityEditor = { kind: "create"; page: IdentityCreatePage } | { kind: "rename"; commandType: "RENAME_IDENTITY_PRINCIPAL" | "RENAME_ORGANIZATION_UNIT"; targetId: string; ifMatch: string; displayName: string } | ({ kind: "action" } & IdentityAction);
type Phase = "idle" | "sending" | "unknown" | "proven" | "complete";

export function identityRejectionMessage(code?: string) {
  switch (code) {
    case "IDENTITY_SELF_LOCKOUT": return "不能执行会使当前管理员失去管理资格的操作。请联系其他管理员核对。";
    case "IDENTITY_LAST_ADMIN": return "不能移除最后一位可用管理员。请联系管理员核对管理资格。";
    case "IDENTITY_ORGANIZATION_DEPENDENCY": return "存在有效子组织或未结束任职等组织依赖，暂不能完成操作。需管理员处理后重新核对。";
    case "IDENTITY_RESPONSIBILITY_DEPENDENCY": return "存在未结束任职或 OPEN／WAITING 责任等依赖，暂不能完成操作。需管理员处理后重新核对。";
    case "IDENTITY_BINDING_CONFLICT": return "该身份绑定已存在冲突，不能重复建立。请联系管理员核对。";
    case "IDENTITY_STATE_CONFLICT": return "目标状态已变化，请重新读取并核对可用操作。";
    case "STALE_IDENTITY": return "目标版本已变化，请重新读取并核对目标。";
    case "IDENTITY_PRECONDITION_REQUIRED": return "请求缺少有效版本，请重新核对。";
    case "VALIDATION_FAILED": return "请求内容未通过校验，请核对填写内容。";
    case "UNAUTHENTICATED": return "登录状态已失效，请重新登录后查询原回执。";
    case "NOT_AUTHORIZED": case "APPOINTMENT_INACTIVE": return "当前任职已不能执行此操作，请联系管理员核对。";
    default: return "本次操作未获准，请重新核对或联系管理员。";
  }
}

/** Page-memory command lifecycle; the API alone owns the shared recovery marker. */
export function useIdentityCommand(session: WorkbenchSession, api: IdentityApi) {
  const [editor, setEditor] = useState<IdentityEditor | null>(null);
  const [dirty, setDirty] = useState(false);
  const [phase, setPhase] = useState<Phase>("idle");
  const [message, setMessage] = useState<string | null>(null);
  const [policy, setPolicy] = useState<string | undefined>();
  const [discard, setDiscard] = useState<(() => void) | null>(null);
  const [refreshFailed, setRefreshFailed] = useState(false);
  const [recoveryConfirmation, setRecoveryConfirmation] = useState(false);
  const original = useRef<IdentityOriginalWrite | null>(null);
  const busy = useRef(false);
  const mounted = useRef(true);
  const currentSession = useRef(session);
  currentSession.current = session;
  const request = useRef<AbortController | null>(null);
  const refresh = useRef<() => Promise<boolean>>(async () => false);
  const recoveryEntry = useRef<(() => void) | undefined>(undefined);
  const dialogTrigger = useRef<HTMLElement | null>(null);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; request.current?.abort(); }; }, []);
  useEffect(() => {
    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (dirty || phase === "sending" || phase === "unknown") { event.preventDefault(); event.returnValue = ""; }
    };
    window.addEventListener("beforeunload", beforeUnload);
    return () => window.removeEventListener("beforeunload", beforeUnload);
  }, [dirty, phase]);
  const reset = () => { setEditor(null); setDirty(false); original.current = null; setPhase("idle"); setMessage(null); setPolicy(undefined); setRefreshFailed(false); };
  const leave = (next: () => void) => {
    if (busy.current && phase === "complete") return;
    if (busy.current || phase === "unknown") { setMessage("结果尚未确认，请先重试原请求或查询原回执。"); return; }
    if (phase === "proven" && (policy === "NEW_KEY_AFTER_REFRESH" || policy === "NEW_KEY_AFTER_ADMIN_FIX")) return;
    if (dirty) { dialogTrigger.current = document.activeElement as HTMLElement; setDiscard(() => () => { reset(); next(); }); return; }
    reset(); next();
  };
  const open = (next: IdentityEditor) => {
    const trigger = document.activeElement as HTMLElement;
    leave(() => { dialogTrigger.current = trigger; setEditor(next); });
  };
  const finish = async (outcome: "SUCCEEDED" | "NO_CHANGE" | "REJECTED", rejectionCode?: string) => {
    original.current = null;
    setEditor(null); setDirty(false); setPolicy(undefined); setPhase("complete");
    if (outcome === "REJECTED") {
      setPhase("proven");
      setPolicy(rejectionCode === "STALE_IDENTITY" || rejectionCode === "IDENTITY_STATE_CONFLICT" ? "NEW_KEY_AFTER_REFRESH" : rejectionCode === "IDENTITY_ORGANIZATION_DEPENDENCY" || rejectionCode === "IDENTITY_RESPONSIBILITY_DEPENDENCY" ? "NEW_KEY_AFTER_ADMIN_FIX" : "NO");
      setMessage(`操作已拒绝。${identityRejectionMessage(rejectionCode)}`); return;
    }
    setMessage(outcome === "NO_CHANGE" ? "结果已记录，名称未变化。" : "结果已记录，正在重新读取当前页。新建记录可能不在当前页。 ");
    const ok = await refresh.current();
    if (!mounted.current || !currentSession.current.isCurrent()) return;
    setRefreshFailed(!ok);
    setMessage(!ok ? "结果已记录，列表刷新失败。请重读列表，无需再次提交。" : outcome === "NO_CHANGE" ? "结果已记录，名称未变化。" : "结果已记录，当前页已重新读取。新建记录可能不在当前页。");
  };
  const dispatch = async (value: IdentityOriginalWrite, receiptOnly = false) => {
    if (busy.current) return;
    busy.current = true; setPhase("sending"); setMessage(receiptOnly ? "正在查询原回执…" : "正在提交，请勿重复操作…");
    const controller = new AbortController(); request.current = controller;
    const actor = currentSession.current;
    try {
      const result = receiptOnly ? await api.receipt(actor, value.key, controller.signal) : await api.write(actor, value, controller.signal);
      if (!mounted.current || controller.signal.aborted || !actor.isCurrent()) return;
      await finish(result.data.outcome, result.data.outcome === "REJECTED" ? result.data.rejectionCode : undefined);
    } catch (error) {
      if (!mounted.current || controller.signal.aborted || !actor.isCurrent()) return;
      const transport = error instanceof TransportError ? error : null;
      // A receipt 404 or failed query never proves the original write was absent.
      if (!receiptOnly && transport?.provenOutcome) {
        setPhase("proven"); setPolicy(transport.retryPolicy);
        setMessage(`操作未提交。${identityRejectionMessage(transport.code)}`);
      } else {
        setPhase("unknown");
        // Query errors describe the GET, and cannot relax the original write's policy.
        if (!receiptOnly) setPolicy(transport?.retryPolicy);
        let retained = false;
        try { const marker = api.recovery.read(); retained = marker?.commandId === value.key && marker.actorScopeKey === actor.actorScopeKey; } catch { /* The shared recovery page owns unavailable storage. */ }
        setMessage(retained ? "结果尚未确认。原请求与恢复线索已保留；回执暂不可用不代表未提交。" : "结果尚未确认。原请求仍在本页；共享恢复线索不可用或另有未决操作，请通过恢复入口核对。");
      }
    } finally { busy.current = false; }
  };
  const submit = (draft: IdentityWriteDraft) => {
    if (busy.current || (phase !== "idle" && !(phase === "proven" && policy === "SAME_KEY_AFTER_FIX"))) return;
    // Only a fully proven non-submission may edit a body with its previous key.
    const value = { ...draft, key: original.current?.key ?? crypto.randomUUID() } as IdentityOriginalWrite;
    original.current = value;
    void dispatch(value);
  };
  const recheck = async () => {
    if (busy.current) return;
    busy.current = true;
    const ok = await refresh.current();
    busy.current = false;
    if (!mounted.current || !currentSession.current.isCurrent()) return;
    if (ok) { reset(); setMessage("已重新读取，请重新选择并核对目标后提交。"); }
    else setMessage(refreshFailed ? "结果已记录，列表刷新失败。请重读列表，无需再次提交。" : "重新读取失败，不能开始新请求。请稍后重读。");
  };
  return {
    editor, dirty, phase, message, policy, discard, refreshFailed, recoveryConfirmation, dialogTrigger: dialogTrigger.current,
    setDirty, open, leave, submit,
    bindRefresh: (load: () => Promise<boolean>) => { refresh.current = load; },
    bindRecovery: (enter: (() => void) | undefined) => { recoveryEntry.current = enter; },
    canRecover: !!recoveryEntry.current,
    requestRecovery: () => { if (!busy.current && phase === "unknown" && recoveryEntry.current) { dialogTrigger.current = document.activeElement as HTMLElement; setRecoveryConfirmation(true); } },
    cancelRecovery: () => setRecoveryConfirmation(false),
    confirmRecovery: () => { if (!busy.current && recoveryConfirmation && currentSession.current.isCurrent()) recoveryEntry.current?.(); },
    cancel: () => leave(() => {}),
    confirmDiscard: () => { const next = discard; setDiscard(null); next?.(); },
    cancelDiscard: () => setDiscard(null),
    retry: () => { if (original.current && phase === "unknown" && policy !== "NO") void dispatch(original.current); },
    receipt: () => { if (original.current && phase === "unknown") void dispatch(original.current, true); },
    recheck,
    canSubmit: phase === "idle" || (phase === "proven" && policy === "SAME_KEY_AFTER_FIX"),
    locked: phase === "sending" || phase === "unknown",
  };
}
export type IdentityCommand = ReturnType<typeof useIdentityCommand>;
