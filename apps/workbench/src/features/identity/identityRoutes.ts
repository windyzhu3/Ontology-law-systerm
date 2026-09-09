export const identityAdminRoutes = [
  "/admin/identity/principals",
  "/admin/identity/organizations",
  "/admin/identity/appointments",
  "/admin/identity/authority-grants",
] as const;

export type IdentityAdminRoute = (typeof identityAdminRoutes)[number];

export function isIdentityAdminRoute(path: string): path is IdentityAdminRoute {
  return (identityAdminRoutes as readonly string[]).includes(path);
}
