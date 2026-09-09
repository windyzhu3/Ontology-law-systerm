import { useCallback } from "react";
import type { components } from "../../generated/api/schema";
import type { WorkbenchSession } from "../../lib/api";
import type { IdentityApi } from "./identityApi";
import { appointmentStateLabels, formatIdentityDate, formatIdentityInstant, roleLabels } from "./identityLabels";
import { DetailRow, DetailRows, DisabledActions, IdentityListPage, InfoNote, StatusBadge } from "./IdentityListPage";
import { useIdentityList } from "./useIdentityList";

type Appointment = components["schemas"]["AppointmentV1"];

export function AppointmentPage({ session, api }: { session: WorkbenchSession; api: IdentityApi }) {
  const load = useCallback(
    (query: { limit: number; cursor?: string }, signal: AbortSignal) =>
      api.listAppointments(session, query, signal) as Promise<{ data: { items: Appointment[]; nextCursor: string | null } }>,
    [api, session],
  );
  const list = useIdentityList(session, load);
  const selected = list.items?.find((item) => item.id === list.selectedId) ?? null;
  return (
    <IdentityListPage
      title="任职管理"
      description="管理身份主体在组织中的岗位任期，岗位本身不授予业务权限"
      createLabel="新建任职"
      count={list.items?.length ?? null}
      loading={list.loading}
      error={list.error}
      empty={list.items?.length === 0}
      canPrevious={list.canPrevious}
      canNext={list.canNext}
      onPrevious={list.previous}
      onNext={list.next}
      onReload={list.reload}
      wideDetail
      list={
        <table className="identity-table appointment-table">
          <thead><tr><th>人员</th><th>所属组织</th><th>岗位</th><th>生效时间</th><th>计划结束</th><th>状态</th></tr></thead>
          <tbody>{list.items?.map((appointment) => (
            <tr key={appointment.id} className={appointment.id === list.selectedId ? "selected" : undefined}>
              <td><button className="identity-row-button" onClick={() => list.setSelectedId(appointment.id)}>{appointment.principal.label}</button></td>
              <td>{appointment.organization.label}</td><td>{roleLabels[appointment.roleCode]}</td>
              <td>{formatIdentityDate(appointment.effectiveFrom)}</td><td>{appointment.effectiveUntil ? formatIdentityDate(appointment.effectiveUntil) : "长期"}</td>
              <td><StatusBadge state={appointment.state} label={appointmentStateLabels[appointment.state]} /></td>
            </tr>
          ))}</tbody>
        </table>
      }
      detail={selected && (
        <>
          <div className="identity-detail-title"><h2>{selected.principal.label}的任职</h2><StatusBadge state={selected.state} label={appointmentStateLabels[selected.state]} /></div>
          <DetailRows>
            <DetailRow label="身份主体">{selected.principal.label}</DetailRow>
            <DetailRow label="所属组织">{selected.organization.label}</DetailRow>
            <DetailRow label="岗位">{roleLabels[selected.roleCode]}</DetailRow>
            <DetailRow label="生效时间">{formatIdentityInstant(selected.effectiveFrom)}</DetailRow>
            <DetailRow label="计划结束">{selected.effectiveUntil ? formatIdentityInstant(selected.effectiveUntil) : "长期"}</DetailRow>
          </DetailRows>
          <InfoNote>岗位用于任职归属，不直接授予业务权限。</InfoNote>
          {selected.state !== "ENDED" && <DisabledActions variant="full">
            {selected.state === "ACTIVE" ? <button disabled>暂停任职</button> : <button disabled>恢复任职</button>}
            <button disabled className="danger">结束任职</button>
          </DisabledActions>}
        </>
      )}
    />
  );
}
