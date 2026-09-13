import { useCallback, useEffect, useMemo, useState } from "react";
import { Buildings, CaretDown, CaretRight } from "@phosphor-icons/react";
import type { components } from "../../generated/api/schema";
import type { WorkbenchSession } from "../../lib/api";
import type { IdentityApi } from "./identityApi";
import { organizationStateLabels } from "./identityLabels";
import { DetailRow, DetailRows, IdentityActions, IdentityListPage, InfoNote, StatusBadge } from "./IdentityListPage";
import { useIdentityList } from "./useIdentityList";
import type { IdentityCommand } from "./useIdentityCommand";
import { identityDetailEditor } from "./IdentityDetailEditor";
import { IdentityCommandFeedback } from "./IdentityActionConfirmation";

type Organization = components["schemas"]["OrganizationUnitV1"];

function safeDepth(item: Organization, byId: Map<string, Organization>): number {
  const visited = new Set([item.id]);
  let parent = item.parentOrganizationId;
  let depth = 0;
  while (parent && byId.has(parent) && depth < byId.size) {
    if (visited.has(parent)) return 0;
    visited.add(parent);
    depth += 1;
    parent = byId.get(parent)?.parentOrganizationId ?? null;
  }
  return depth;
}

export function OrganizationPage({ session, api, command }: { session: WorkbenchSession; api: IdentityApi; command: IdentityCommand }) {
  const load = useCallback(
    (query: { limit: number; cursor?: string }, signal: AbortSignal) =>
      api.listOrganizationUnits(session, query, signal) as Promise<{ data: { items: Organization[]; nextCursor: string | null } }>,
    [api, session],
  );
  const list = useIdentityList(session, load);
  command.bindRefresh(list.reload);
  const byId = useMemo(() => new Map(list.items?.map((item) => [item.id, item]) ?? []), [list.items]);
  const loadedParentIds = useMemo(
    () => new Set(list.items?.map((item) => item.parentOrganizationId).filter((id): id is string => !!id && byId.has(id)) ?? []),
    [byId, list.items],
  );
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  useEffect(() => setExpanded(new Set(loadedParentIds)), [loadedParentIds]);
  const visible = useCallback((item: Organization) => {
    const visited = new Set([item.id]);
    let parent = item.parentOrganizationId;
    while (parent && byId.has(parent)) {
      if (visited.has(parent)) return true;
      if (!expanded.has(parent)) return false;
      visited.add(parent);
      parent = byId.get(parent)?.parentOrganizationId ?? null;
    }
    return true;
  }, [byId, expanded]);
  const selected = list.items?.find((item) => item.id === list.selectedId) ?? null;
  const parent = selected?.parentOrganizationId ? byId.get(selected.parentOrganizationId) : null;
  return (
    <IdentityListPage
      title="组织架构"
      description="维护租户内的组织层级，组织节点不代表任职或授权"
      createLabel="新增组织"
      count={list.items?.length ?? null}
      loading={list.loading}
      error={list.error}
      empty={list.items?.length === 0}
      canPrevious={list.canPrevious}
      canNext={list.canNext}
      onPrevious={() => command.leave(list.previous)}
      onNext={() => command.leave(list.next)}
      onReload={() => command.leave(() => void list.reload())}
      onCreate={() => command.open({ kind: "create", page: "ORGANIZATIONS" })}
      editing={!!command.editor && command.editor.kind !== "action"}
      feedback={command.editor?.kind !== "action" && <IdentityCommandFeedback command={command} />}
      organization
      list={
        <div className="organization-list">
          <header><h2>已加载组织层级</h2><p>仅展示已加载且有权限的组织<br />未展示不代表不存在</p></header>
          {list.items?.filter(visible).map((organization) => {
            const hasLoadedChildren = loadedParentIds.has(organization.id);
            const isExpanded = expanded.has(organization.id);
            return (
              <div
                key={organization.id}
                className={`organization-row${organization.id === list.selectedId ? " selected" : ""}`}
                style={{ paddingInlineStart: `${14 + safeDepth(organization, byId) * 24}px` }}
              >
                {hasLoadedChildren ? (
                  <button
                    className="organization-toggle"
                    aria-label={`${isExpanded ? "收起" : "展开"}${organization.displayName}的已加载下级`}
                    onClick={() => setExpanded((current) => {
                      const next = new Set(current);
                      if (next.has(organization.id)) next.delete(organization.id);
                      else next.add(organization.id);
                      return next;
                    })}
                  >
                    {isExpanded ? <CaretDown size={18} aria-hidden="true" /> : <CaretRight size={18} aria-hidden="true" />}
                  </button>
                ) : (
                  <span className="organization-indent" />
                )}
                {organization.parentOrganizationId === null && <Buildings size={19} aria-hidden="true" />}
                <button className="organization-select" onClick={() => command.leave(() => list.setSelectedId(organization.id))}>
                  {organization.displayName}
                </button>
              </div>
            );
          })}
        </div>
      }
      detail={identityDetailEditor(command, session, api) ?? (selected && (
        <>
          <div className="identity-detail-title"><h2>{selected.displayName}</h2><StatusBadge state={selected.state} label={organizationStateLabels[selected.state]} /></div>
          <DetailRows>
            <DetailRow label="组织代码">{selected.code}</DetailRow>
            <DetailRow label="上级组织">{selected.parentOrganizationId ? (parent?.displayName ?? "上级组织未加载") : "无上级组织"}</DetailRow>
            <DetailRow label="层级说明">下级情况未完整加载</DetailRow>
          </DetailRows>
          <InfoNote>组织节点仅用于结构归属，不等于任职或授权。</InfoNote>
          {selected.state === "ACTIVE" && <IdentityActions variant="inline"><button onClick={() => command.open({ kind: "rename", commandType: "RENAME_ORGANIZATION_UNIT", targetId: selected.id, ifMatch: selected.etag, displayName: selected.displayName })}>修改组织名称</button><button className="danger" onClick={event => { event.currentTarget.focus(); command.open({ kind: "action", commandType: "CLOSE_ORGANIZATION_UNIT", targetId: selected.id, ifMatch: selected.etag, targetName: selected.displayName, label: "关闭组织", verb: "关闭", impact: "关闭后不可恢复。有效子组织、未结束任职及责任等依赖须由服务端检查。" }); }}>关闭组织</button></IdentityActions>}
        </>
      ))}
    />
  );
}
