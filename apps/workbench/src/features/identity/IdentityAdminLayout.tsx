import type { ReactNode } from "react";
import { Briefcase, Scales, ShieldCheck, TreeStructure, User } from "@phosphor-icons/react";
import type { IdentityAdminRoute } from "./identityRoutes";

const navigation: Array<{
  path: IdentityAdminRoute;
  label: string;
  icon: typeof User;
}> = [
  { path: "/admin/identity/principals", label: "身份主体", icon: User },
  { path: "/admin/identity/organizations", label: "组织架构", icon: TreeStructure },
  { path: "/admin/identity/appointments", label: "任职管理", icon: Briefcase },
  { path: "/admin/identity/authority-grants", label: "直接授权", icon: ShieldCheck },
];

export function IdentityAdminLayout({
  path,
  onNavigate,
  sessionActions,
  children,
}: {
  path: IdentityAdminRoute;
  onNavigate: (path: IdentityAdminRoute) => void;
  sessionActions: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="identity-admin-shell">
      <header className="identity-admin-header">
        <div className="brand">
          <Scales size={30} aria-hidden="true" />
          <span>律所工作助手</span>
        </div>
        {sessionActions}
      </header>
      <div className="identity-admin-body">
        <aside className="identity-admin-sidebar">
          <p>身份与组织</p>
          <nav aria-label="身份管理">
            {navigation.map(({ path: target, label, icon: Icon }) => (
              <a
                key={target}
                href={target}
                aria-current={path === target ? "page" : undefined}
                onClick={(event) => {
                  event.preventDefault();
                  onNavigate(target);
                }}
              >
                <Icon size={21} aria-hidden="true" />
                <span>{label}</span>
              </a>
            ))}
          </nav>
        </aside>
        <main className="identity-admin-main" aria-label="身份管理">
          {children}
        </main>
      </div>
    </div>
  );
}
