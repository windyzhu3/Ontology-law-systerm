import { useCallback } from "react";
import type { components } from "../../generated/api/schema";
import type { WorkbenchSession } from "../../lib/api";
import type { IdentityApi } from "./identityApi";
import { principalStateLabels } from "./identityLabels";
import { DisabledActions, IdentityListPage, InfoNote, StatusBadge } from "./IdentityListPage";
import { useIdentityList } from "./useIdentityList";

type Principal = components["schemas"]["IdentityPrincipalV1"];

export function PrincipalPage({ session, api }: { session: WorkbenchSession; api: IdentityApi }) {
  const load = useCallback(
    (query: { limit: number; cursor?: string }, signal: AbortSignal) =>
      api.listIdentityPrincipals(session, query, signal) as Promise<{ data: { items: Principal[]; nextCursor: string | null } }>,
    [api, session],
  );
  const list = useIdentityList(session, load);
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
      onPrevious={list.previous}
      onNext={list.next}
      onReload={list.reload}
      list={
        <table className="identity-table principal-table">
          <thead><tr><th>显示名称</th><th>状态</th></tr></thead>
          <tbody>{list.items?.map((principal) => (
            <tr key={principal.id} className={principal.id === list.selectedId ? "selected" : undefined}>
              <td><button className="identity-row-button" onClick={() => list.setSelectedId(principal.id)}>{principal.displayName}</button></td>
              <td><StatusBadge dot state={principal.state} label={principalStateLabels[principal.state]} /></td>
            </tr>
          ))}</tbody>
        </table>
      }
      detail={selected && (
        <div className="principal-detail-content">
          <h2>{selected.displayName}</h2>
          <StatusBadge dot state={selected.state} label={principalStateLabels[selected.state]} />
          <InfoNote boxed={false}>身份主体不等于任职或授权。</InfoNote>
          <DisabledActions>
            <button disabled>修改名称</button>
            {selected.state !== "DISABLED" && <button disabled className="danger">禁用身份</button>}
            {selected.state === "ACTIVE" && <button disabled>暂停使用</button>}
            {selected.state === "SUSPENDED" && <button disabled>恢复使用</button>}
          </DisabledActions>
        </div>
      )}
    />
  );
}
