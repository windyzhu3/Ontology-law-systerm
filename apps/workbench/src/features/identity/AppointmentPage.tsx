import { useCallback } from "react";
import type { components } from "../../generated/api/schema";
import type { WorkbenchSession } from "../../lib/api";
import type { IdentityApi } from "./identityApi";
import { appointmentStateLabels, formatIdentityDate, formatIdentityInstant, roleLabels } from "./identityLabels";
import { DetailRow, DetailRows, IdentityActions, IdentityListPage, InfoNote, StatusBadge } from "./IdentityListPage";
import { useIdentityList } from "./useIdentityList";
import type { IdentityCommand } from "./useIdentityCommand";
import { identityDetailEditor } from "./IdentityDetailEditor";
import { IdentityCommandFeedback } from "./IdentityActionConfirmation";

type Appointment = components["schemas"]["AppointmentV1"];

export function AppointmentPage({ session, api, command }: { session: WorkbenchSession; api: IdentityApi; command: IdentityCommand }) {
  const load = useCallback(
    (query: { limit: number; cursor?: string }, signal: AbortSignal) =>
      api.listAppointments(session, query, signal) as Promise<{ data: { items: Appointment[]; nextCursor: string | null } }>,
    [api, session],
  );
  const list = useIdentityList(session, load);
  command.bindRefresh(list.reload);
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
      onPrevious={() => command.leave(list.previous)}
      onNext={() => command.leave(list.next)}
      onReload={() => command.leave(() => void list.reload())}
      onCreate={() => command.open({ kind: "create", page: "APPOINTMENTS" })}
      editing={!!command.editor && command.editor.kind !== "action"}
      feedback={command.editor?.kind !== "action" && <IdentityCommandFeedback command={command} />}
      wideDetail
      list={
        <table className="identity-table appointment-table">
          <thead><tr><th>人员</th><th>所属组织</th><th>岗位</th><th>生效时间</th><th>计划结束</th><th>状态</th></tr></thead>
          <tbody>{list.items?.map((appointment) => (
            <tr key={appointment.id} className={appointment.id === list.selectedId ? "selected" : undefined}>
              <td><button className="identity-row-button" onClick={() => command.leave(() => list.setSelectedId(appointment.id))}>{appointment.principal.label}</button></td>
              <td>{appointment.organization.label}</td><td>{roleLabels[appointment.roleCode]}</td>
              <td>{formatIdentityDate(appointment.effectiveFrom)}</td><td>{appointment.effectiveUntil ? formatIdentityDate(appointment.effectiveUntil) : "长期"}</td>
              <td><StatusBadge state={appointment.state} label={appointmentStateLabels[appointment.state]} /></td>
            </tr>
          ))}</tbody>
        </table>
      }
      detail={identityDetailEditor(command, session, api) ?? (selected && (
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
          {selected.state !== "ENDED" && <IdentityActions variant="full">
            <button onClick={event => { event.currentTarget.focus(); command.open({ kind: "action", commandType: selected.state === "ACTIVE" ? "SUSPEND_APPOINTMENT" : "RESUME_APPOINTMENT", targetId: selected.id, ifMatch: selected.etag, targetName: selected.principal.label + " · " + selected.organization.label + " · " + roleLabels[selected.roleCode], label: selected.state === "ACTIVE" ? "暂停任职" : "恢复任职", verb: selected.state === "ACTIVE" ? "暂停" : "恢复", impact: selected.state === "ACTIVE" ? "暂停后该任职不能继续执行相关操作。既有责任不会自动结束。" : "恢复任职资格，实际操作仍需重新验证任期和授权。" }); }}>{selected.state === "ACTIVE" ? "暂停任职" : "恢复任职"}</button>
            <button className="danger" onClick={event => { event.currentTarget.focus(); command.open({ kind: "action", commandType: "END_APPOINTMENT", targetId: selected.id, ifMatch: selected.etag, targetName: selected.principal.label + " · " + selected.organization.label + " · " + roleLabels[selected.roleCode], label: "结束任职", verb: "结束", impact: "结束后不可恢复，该任职将失去资格。OPEN／WAITING 责任等依赖须先核对。" }); }}>结束任职</button>
          </IdentityActions>}
        </>
      ))}
    />
  );
}
