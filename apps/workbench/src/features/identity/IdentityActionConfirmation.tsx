import { useEffect, useRef, useState, type ReactNode } from "react";
import { IdentityField } from "./IdentityFormFields";
import type { IdentityCommand } from "./useIdentityCommand";

function IdentityDialog({ title, children, onCancel, locked, returnFocus }: { title: string; children: ReactNode; onCancel: () => void; locked: boolean; returnFocus: HTMLElement | null }) {
  const dialog = useRef<HTMLDivElement>(null);
  const cancel = useRef(onCancel); cancel.current = onCancel;
  const blocked = useRef(locked); blocked.current = locked;
  useEffect(() => {
    // Captured by the opening event, before applying inert can blur the trigger.
    const trigger = returnFocus;
    const node = dialog.current!;
    const focusable = () => [...node.querySelectorAll<HTMLElement>("button:not(:disabled),select:not(:disabled),input:not(:disabled),[tabindex='0']")];
    (focusable()[0] ?? node).focus();
    const keydown = (event: KeyboardEvent) => {
      if (event.key === "Escape") { event.preventDefault(); if (!blocked.current) cancel.current(); }
      if (event.key === "Tab") {
        const elements = focusable(), first = elements[0] ?? node, last = elements.at(-1) ?? node;
        if (!elements.length || (event.shiftKey ? document.activeElement === first || !node.contains(document.activeElement) : document.activeElement === last || !node.contains(document.activeElement))) { event.preventDefault(); (event.shiftKey ? last : first).focus(); }
      }
    };
    document.addEventListener("keydown", keydown);
    return () => { document.removeEventListener("keydown", keydown); queueMicrotask(() => { if (trigger?.isConnected && !trigger.closest("[inert]")) trigger.focus(); }); };
  }, []);
  return <div className="identity-dialog-backdrop"><div ref={dialog} className="identity-dialog" role="dialog" aria-modal="true" aria-label={title} tabIndex={-1}><h2>{title}</h2>{children}</div></div>;
}

export function IdentityActionConfirmation({ command }: { command: IdentityCommand }) {
  const [reason, setReason] = useState("");
  const editor = command.editor;
  if (!editor || editor.kind !== "action") return null;
  return <IdentityDialog title={editor.label} onCancel={command.cancel} locked={command.locked} returnFocus={command.dialogTrigger}>
    <p>操作对象：<strong>{editor.targetName}</strong></p>
    <p>{editor.impact}</p>
    <p>依赖检查由服务端裁定；本操作不会自动转派或结束相关责任。</p>
    <IdentityField label="操作原因" value={reason} onChange={setReason} disabled={!command.canSubmit}><option value="">请主动选择原因</option><option value="ADMINISTRATIVE_ACTION">行政调整</option><option value="SECURITY_RESPONSE">安全处置</option></IdentityField>
    <IdentityCommandFeedback command={command} />
    <div className="identity-form-actions"><button className="identity-danger" disabled={!command.canSubmit || !["ADMINISTRATIVE_ACTION", "SECURITY_RESPONSE"].includes(reason)} onClick={() => command.submit({ commandType: editor.commandType, targetId: editor.targetId, ifMatch: editor.ifMatch, body: { reasonCode: reason as "ADMINISTRATIVE_ACTION" | "SECURITY_RESPONSE" } })}>确认{editor.verb}</button><button disabled={command.locked} onClick={command.cancel}>取消</button></div>
  </IdentityDialog>;
}

export function IdentityDiscardConfirmation({ command }: { command: IdentityCommand }) {
  if (!command.discard) return null;
  return <IdentityDialog title="舍弃未保存的修改？" onCancel={command.cancelDiscard} locked={false} returnFocus={command.dialogTrigger}><p>离开后，本页填写内容将丢失。</p><div className="identity-form-actions"><button onClick={command.cancelDiscard}>继续编辑</button><button className="identity-danger" onClick={command.confirmDiscard}>确认舍弃</button></div></IdentityDialog>;
}

export function IdentityRecoveryConfirmation({ command }: { command: IdentityCommand }) {
  if (!command.recoveryConfirmation) return null;
  return <IdentityDialog title="离开本页并核对恢复线索？" onCancel={command.cancelRecovery} locked={false} returnFocus={command.dialogTrigger}><p>本页保存的原请求正文将丢失。共享恢复线索保持原样，之后只能按线索查询原回执，不能重新构造或自动重发原请求。</p><p>离开不代表取消原操作，也不证明原操作未提交。</p><div className="identity-form-actions"><button onClick={command.cancelRecovery}>留在本页</button><button onClick={command.confirmRecovery}>确认前往恢复</button></div></IdentityDialog>;
}

export function IdentityCommandFeedback({ command }: { command: IdentityCommand }) {
  if (!command.message) return null;
  return <section className="identity-command-feedback" aria-label="操作结果"><p role={command.phase === "unknown" || command.phase === "proven" || command.refreshFailed ? "alert" : "status"}>{command.message}</p>
    {command.phase === "unknown" && <div className="identity-form-actions">{command.policy !== "NO" && <button onClick={command.retry}>重试原请求</button>}<button onClick={command.receipt}>查询原回执</button>{command.canRecover && <button onClick={command.requestRecovery}>前往恢复入口</button>}</div>}
    {command.phase === "proven" && (command.policy === "NEW_KEY_AFTER_REFRESH" || command.policy === "NEW_KEY_AFTER_ADMIN_FIX") && <><p>{command.policy === "NEW_KEY_AFTER_ADMIN_FIX" ? "请先联系管理员处理依赖，再重新读取并核对。" : "需要重新读取目标与版本，再开始新请求。"}</p><button onClick={() => void command.recheck()}>重新读取并核对</button></>}
    {command.refreshFailed && <button onClick={() => void command.recheck()}>重读列表</button>}
  </section>;
}
