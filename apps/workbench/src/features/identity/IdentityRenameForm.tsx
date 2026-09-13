import { useEffect, useRef, useState } from "react";
import { safeText } from "../workcard/contract";
import { IdentityField } from "./IdentityFormFields";
import type { IdentityCommand, IdentityEditor } from "./useIdentityCommand";

export function IdentityRenameForm({ editor, command }: { editor: Extract<IdentityEditor, { kind: "rename" }>; command: IdentityCommand }) {
  const [name, setName] = useState(editor.displayName), [error, setError] = useState<string>();
  const form = useRef<HTMLFormElement>(null);
  useEffect(() => { form.current?.querySelector("input")?.focus(); }, []);
  return <form ref={form} className="identity-edit-form" noValidate onSubmit={event => {
    event.preventDefault();
    if (!safeText(name.trim(), 200)) { setError("请输入 1～200 个字符的安全显示名称。"); return; }
    setError(undefined);
    command.submit({ commandType: editor.commandType, targetId: editor.targetId, ifMatch: editor.ifMatch, body: { displayName: name.trim() } });
  }}>
    <h2>修改名称</h2><p>正在修改：{editor.displayName}</p>
    <IdentityField label="显示名称" value={name} error={error} disabled={!command.canSubmit} onChange={value => { setName(value); command.setDirty(value !== editor.displayName); }} />
    <p className="identity-form-help">仅修改显示名称，既有代码、上级、岗位与任期不可在此修改。</p>
    <div className="identity-form-actions"><button className="identity-primary" disabled={!command.canSubmit}>保存名称</button><button type="button" disabled={command.locked} onClick={command.cancel}>取消</button></div>
  </form>;
}
