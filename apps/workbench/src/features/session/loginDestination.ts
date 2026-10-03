import { identityAdminRoutes } from '../identity/identityRoutes';

const routes = ['/workbench', '/business/leads/intake', '/management/opportunities', '/management/contracts', '/management/leads', '/management/overview', '/management/team-tasks', '/management/team-tasks/operations', ...identityAdminRoutes] as const;
type Destination = typeof routes[number];
const key = 'ols.login-destination.v1';
const allowed = (path: unknown): path is Destination => typeof path === 'string' && (routes as readonly string[]).includes(path);

// Store only a short-lived, allowlisted page address. Identity admission still runs after login.
export function rememberLoginDestination(path: string) {
  if (!allowed(path)) return;
  try { sessionStorage.setItem(key, JSON.stringify({ path, savedAt: Date.now() })); } catch { /* Login remains available without browser storage. */ }
}

export function clearLoginDestination() {
  try { sessionStorage.removeItem(key); } catch { /* Sign-out remains available without storage. */ }
}

export function consumeLoginDestination(): Destination {
  try {
    const raw = sessionStorage.getItem(key);
    sessionStorage.removeItem(key);
    const value = raw ? JSON.parse(raw) : null;
    const age = Date.now() - value?.savedAt;
    if (allowed(value?.path) && Number.isFinite(age) && age >= 0 && age <= 600_000) return value.path;
  } catch { /* Invalid or unavailable storage falls back to the workbench. */ }
  return '/workbench';
}
