import { useCallback } from "react";
import type { components } from "../../generated/api/schema";
import type { WorkbenchSession } from "../../lib/api";
import type { IdentityApi } from "./identityApi";
import { principalStateLabels } from "./identityLabels";
import { IdentityActions, IdentityListPage, InfoNote, StatusBadge } from "./IdentityListPage";
import { useIdentityList } from "./useIdentityList";
import type { IdentityCommand } from "./useIdentityCommand";
import { identityDetailEditor } from "./IdentityDetailEditor";
import { IdentityCommandFeedback } from "./IdentityActionConfirmation";

type Principal = components["schemas"]["IdentityPrincipalV1"];

export function PrincipalPage({ session, api, command }: { session: WorkbenchSession; api: IdentityApi; command: IdentityCommand }) {
  const load = useCallback(
    (query: { limit: number; cursor?: string }, signal: AbortSignal) =>
      api.listIdentityPrincipals(session, query, signal) as Promise<{ data: { items: Principal[]; nextCursor: string | null } }>,
    [api, session],
  );
  const list = useIdentityList(session, load);
  command.bindRefresh(list.reload);
  const selected = list.items?.find((item) => item.id === list.selectedId) ?? null;
  return (
    <IdentityListPage
      title="用户与身份主体"
      description="管理已接入的人员身份，不保存密码或令牌"
      createLabel="新增身份主体"
      count={list.items?.length ?? null}
      loading={list.loading}
      error={list.error}
      empty={list.items?.length === 0}
      canPrevious={list.canPrevious}
      canNext={list.canNext}
      onPrevious={() => command.leave(list.previous)}
      onNext={() => command.leave(list.next)}
      onReload={() => command.leave(() => void list.reload())}
      onCreate={() => command.open({ kind: "create", page: "PRINCIPALS" })}
      editing={!!command.editor && command.editor.kind !== "action"}
      feedback={command.editor?.kind !== "action" && <IdentityCommandFeedback command={command} />}
      list={
        <table className="identity-table principal-table">
          <thead><tr><th>显示名称</th><th>状态</th></tr></thead>
          <tbody>{list.items?.map((principal) => (
            <tr key={principal.id} className={principal.id === list.selectedId ? "selected" : undefined}>
              <td><button className="identity-row-button" onClick={() => command.leave(() => list.setSelectedId(principal.id))}>{principal.displayName}</button></td>
              <td><StatusBadge dot state={principal.state} label={principalStateLabels[principal.state]} /></td>
            </tr>
          ))}</tbody>
        </table>
      }
      detail={identityDetailEditor(command, session, api) ?? (selected && (
        <div className="principal-detail-content">
          <h2>{selected.displayName}</h2>
          <StatusBadge dot state={selected.state} label={principalStateLabels[selected.state]} />
          <InfoNote boxed={false}>身份主体不等于任职或授权。</InfoNote>
          <IdentityActions>
            <button onClick={() => command.open({ kind: "rename", commandType: "RENAME_IDENTITY_PRINCIPAL", targetId: selected.id, ifMatch: selected.etag, displayName: selected.displayName })}>修改名称</button>
            {selected.state !== "DISABLED" && <button className="danger" onClick={event => { event.currentTarget.focus(); command.open({ kind: "action", commandType: "DISABLE_IDENTITY_PRINCIPAL", targetId: selected.id, ifMatch: selected.etag, targetName: selected.displayName, label: "禁用身份", verb: "禁用", impact: "禁用后不可恢复，将失去相关任职资格。未结束任职与责任等依赖需先核对。" }); }}>禁用身份</button>}
            {selected.state === "ACTIVE" && <button onClick={event => { event.currentTarget.focus(); command.open({ kind: "action", commandType: "SUSPEND_IDENTITY_PRINCIPAL", targetId: selected.id, ifMatch: selected.etag, targetName: selected.displayName, label: "暂停使用", verb: "暂停", impact: "暂停将影响该用户所有相关任职资格。" }); }}>暂停使用</button>}
            {selected.state === "SUSPENDED" && <button onClick={event => { event.currentTarget.focus(); command.open({ kind: "action", commandType: "RESUME_IDENTITY_PRINCIPAL", targetId: selected.id, ifMatch: selected.etag, targetName: selected.displayName, label: "恢复使用", verb: "恢复", impact: "恢复身份使用资格，具体任职和授权仍由服务端重新验证。" }); }}>恢复使用</button>}
          </IdentityActions>
        </div>
      ))}
    />
  );
}
