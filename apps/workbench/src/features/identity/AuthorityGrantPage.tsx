import { useCallback } from "react";
import type { components } from "../../generated/api/schema";
import type { WorkbenchSession } from "../../lib/api";
import type { IdentityApi } from "./identityApi";
import { authorityLabels, authorityStateLabels, formatIdentityDate, formatIdentityInstant } from "./identityLabels";
import { DetailRow, DetailRows, IdentityActions, IdentityListPage, InfoNote, StatusBadge } from "./IdentityListPage";
import { useIdentityList } from "./useIdentityList";
import type { IdentityCommand } from "./useIdentityCommand";
import { identityDetailEditor } from "./IdentityDetailEditor";
import { IdentityCommandFeedback } from "./IdentityActionConfirmation";

type Grant = components["schemas"]["AuthorityGrantV1"];

export function AuthorityGrantPage({ session, api, command }: { session: WorkbenchSession; api: IdentityApi; command: IdentityCommand }) {
  const load = useCallback(
    (query: { limit: number; cursor?: string }, signal: AbortSignal) =>
      api.listAuthorityGrants(session, query, signal) as Promise<{ data: { items: Grant[]; nextCursor: string | null } }>,
    [api, session],
  );
  const list = useIdentityList(session, load);
  command.bindRefresh(list.reload);
  const selected = list.items?.find((item) => item.id === list.selectedId) ?? null;
  return (
    <IdentityListPage
      title="直接授权"
      description="向具体任职授予一项权限，并限定组织范围与有效期"
      createLabel="新增直接授权"
      count={list.items?.length ?? null}
      loading={list.loading}
      error={list.error}
      empty={list.items?.length === 0}
      canPrevious={list.canPrevious}
      canNext={list.canNext}
      onPrevious={() => command.leave(list.previous)}
      onNext={() => command.leave(list.next)}
      onReload={() => command.leave(() => void list.reload())}
      onCreate={() => command.open({ kind: "create", page: "AUTHORITY_GRANTS" })}
      editing={!!command.editor && command.editor.kind !== "action"}
      feedback={command.editor?.kind !== "action" && <IdentityCommandFeedback command={command} />}
      authorityDetail
      list={
        <table className="identity-table authority-table">
          <thead><tr><th>授权任职</th><th>权限</th><th>组织范围</th><th>有效期</th><th>状态</th></tr></thead>
          <tbody>{list.items?.map((grant) => (
            <tr key={grant.id} className={grant.id === list.selectedId ? "selected" : undefined}>
              <td><button className="identity-row-button" onClick={() => command.leave(() => list.setSelectedId(grant.id))}>{grant.appointment.label}</button></td>
              <td>{authorityLabels[grant.authorityCode]}</td><td>{grant.scopeOrganization.label}</td>
              <td>{formatIdentityDate(grant.validFrom)} 至 {grant.validUntil ? formatIdentityDate(grant.validUntil) : "长期"}</td>
              <td><StatusBadge state={grant.state} label={authorityStateLabels[grant.state]} /></td>
            </tr>
          ))}</tbody>
        </table>
      }
      detail={identityDetailEditor(command, session, api) ?? (selected && (
        <>
          <div className="identity-detail-title"><h2>{selected.appointment.label}的直接授权</h2><StatusBadge state={selected.state} label={authorityStateLabels[selected.state]} /></div>
          <DetailRows>
            <DetailRow label="授权任职">{selected.appointment.label}</DetailRow>
            <DetailRow label="权限">{authorityLabels[selected.authorityCode]}</DetailRow>
            <DetailRow label="组织范围">{selected.scopeOrganization.label}</DetailRow>
            <DetailRow label="生效时间">{formatIdentityInstant(selected.validFrom)}</DetailRow>
            <DetailRow label="失效时间">{selected.validUntil ? formatIdentityInstant(selected.validUntil) : "长期"}</DetailRow>
          </DetailRows>
          <InfoNote>授权记录不代表某次操作已经获准，执行时仍会重新鉴权。</InfoNote>
          {selected.state === "ACTIVE" && <IdentityActions variant="full"><button className="danger" onClick={event => { event.currentTarget.focus(); command.open({ kind: "action", commandType: "REVOKE_AUTHORITY_GRANT", targetId: selected.id, ifMatch: selected.etag, targetName: selected.appointment.label + " · " + authorityLabels[selected.authorityCode] + " · " + selected.scopeOrganization.label, label: "撤销授权", verb: "撤销", impact: "撤销后不可恢复，该直接授权将不再授予相应权限。" }); }}>撤销授权</button></IdentityActions>}
        </>
      ))}
    />
  );
}
