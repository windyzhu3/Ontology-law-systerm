import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import type { WorkbenchSession } from "../../lib/api";
import type { IdentityApi } from "./identityApi";
import { RolePage } from "./RolePage";
import { AuditRecordPage } from './AuditRecordPage';
import { createAuditRecordApi,type AuditRecordApi } from './auditRecordApi';
import { AppointmentPage } from "./AppointmentPage";
import { AuthorityGrantPage } from "./AuthorityGrantPage";
import { IdentityAdminLayout } from "./IdentityAdminLayout";
import type { IdentityAdminRoute } from "./identityRoutes";
import { OrganizationPage } from "./OrganizationPage";
import { PrincipalPage } from "./PrincipalPage";
import "../../styles/tokens.css";
import "../../styles/workbench.css";
import "../../styles/identity-admin.css";
import { useIdentityCommand } from "./useIdentityCommand";
import { IdentityActionConfirmation, IdentityDiscardConfirmation, IdentityRecoveryConfirmation } from "./IdentityActionConfirmation";
import { IdentityBatchConfirmation } from "./IdentityBatchGrant";

export type IdentityLeaveGuard = (next: () => void) => void;

export function IdentityAdminApplication({
  session,
  api,
  path,
  onNavigate,
  sessionActions,
  registerLeaveGuard,
  onRecover,
  auditApi,
  canManageIdentity = true,
}: {
  session: WorkbenchSession;
  api: IdentityApi;
  path: IdentityAdminRoute;
  onNavigate: (path: IdentityAdminRoute) => void;
  sessionActions: ReactNode;
  registerLeaveGuard?: (guard: IdentityLeaveGuard | null) => void;
  onRecover?: () => void;
  auditApi?: AuditRecordApi;
  canManageIdentity?: boolean;
}) {
  return <IdentityAdminBoundary key={`${session.identityEpoch}:${session.actorScopeKey}:${session.selectedAppointmentId}`} {...{ session, api, path, onNavigate, sessionActions, registerLeaveGuard, onRecover, auditApi, canManageIdentity }} />;
}

function IdentityAdminBoundary(props: Parameters<typeof IdentityAdminApplication>[0]) {
  const [denied, setDenied] = useState(false);
  const guardedSession = useMemo(() => ({ ...props.session, invalidate: (status: number) => { setDenied(true); props.session.invalidate(status); } }), [props.session]);
  if (denied) return <div role="alert">身份管理数据暂时不可用，请重读后再试。登录或任职资格已变化，请重新确认身份。</div>;
  return <IdentityAdminWorkspace {...props} session={guardedSession} />;
}

function IdentityAdminWorkspace({ session, api, path, onNavigate, sessionActions, registerLeaveGuard, onRecover, auditApi,canManageIdentity }: Parameters<typeof IdentityAdminApplication>[0]) {
  const audit=useMemo(()=>auditApi??createAuditRecordApi(),[auditApi]);
  const command = useIdentityCommand(session, api);
  command.bindRecovery(onRecover);
  const latestLeave = useRef(command.leave); latestLeave.current = command.leave;
  useEffect(() => {
    registerLeaveGuard?.(next => latestLeave.current(next));
    return () => registerLeaveGuard?.(null);
  }, [registerLeaveGuard]);
  let page: ReactNode;
  switch (path) {
    case '/admin/audit-records':
      page=session.canReadAuditRecords===true?<AuditRecordPage session={session} api={audit}/>:<p role="alert">当前任职没有审计查询权限。</p>;
      break;
    case "/admin/identity/principals":
      page = <PrincipalPage session={session} api={api} command={command} />;
      break;
    case "/admin/identity/organizations":
      page = <OrganizationPage session={session} api={api} command={command} />;
      break;
    case "/admin/identity/roles":
      page = <RolePage session={session} api={api} command={command} />;
      break;
    case "/admin/identity/appointments":
      page = <AppointmentPage session={session} api={api} command={command} />;
      break;
    case "/admin/identity/authority-grants":
      page = <AuthorityGrantPage session={session} api={api} command={command} />;
      break;
  }
  return (
    <><div inert={!!command.discard || command.editor?.kind === "action" || command.recoveryConfirmation || !!command.batchPreview}>
    <IdentityAdminLayout path={path} canManageIdentity={canManageIdentity} canReadAuditRecords={session.canReadAuditRecords===true} onNavigate={next => command.leave(() => onNavigate(next))} sessionActions={sessionActions}>
      {path!=='/admin/audit-records'&&canManageIdentity===false?<p role="alert">当前任职没有身份管理权限。</p>:page}
    </IdentityAdminLayout>
    </div>
    <IdentityBatchConfirmation command={command}/>
    {command.editor?.kind === "action" && !command.recoveryConfirmation && <IdentityActionConfirmation key={`${command.editor.commandType}:${command.editor.targetId}`} command={command} />}
    <IdentityDiscardConfirmation command={command} /><IdentityRecoveryConfirmation command={command} /></>
  );
}
