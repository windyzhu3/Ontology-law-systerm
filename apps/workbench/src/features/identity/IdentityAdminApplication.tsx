import type { ReactNode } from "react";
import type { WorkbenchSession } from "../../lib/api";
import type { IdentityApi } from "./identityApi";
import { AppointmentPage } from "./AppointmentPage";
import { AuthorityGrantPage } from "./AuthorityGrantPage";
import { IdentityAdminLayout } from "./IdentityAdminLayout";
import type { IdentityAdminRoute } from "./identityRoutes";
import { OrganizationPage } from "./OrganizationPage";
import { PrincipalPage } from "./PrincipalPage";
import "../../styles/tokens.css";
import "../../styles/workbench.css";
import "../../styles/identity-admin.css";

export function IdentityAdminApplication({
  session,
  api,
  path,
  onNavigate,
  sessionActions,
}: {
  session: WorkbenchSession;
  api: IdentityApi;
  path: IdentityAdminRoute;
  onNavigate: (path: IdentityAdminRoute) => void;
  sessionActions: ReactNode;
}) {
  let page: ReactNode;
  switch (path) {
    case "/admin/identity/principals":
      page = <PrincipalPage session={session} api={api} />;
      break;
    case "/admin/identity/organizations":
      page = <OrganizationPage session={session} api={api} />;
      break;
    case "/admin/identity/appointments":
      page = <AppointmentPage session={session} api={api} />;
      break;
    case "/admin/identity/authority-grants":
      page = <AuthorityGrantPage session={session} api={api} />;
      break;
  }
  return (
    <IdentityAdminLayout path={path} onNavigate={onNavigate} sessionActions={sessionActions}>
      {page}
    </IdentityAdminLayout>
  );
}
