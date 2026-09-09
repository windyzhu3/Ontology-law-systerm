import type { SessionController } from "./sessionController";
import { LoginPage } from "./LoginPage";
import {
  SessionProvider,
  useSessionController,
  useSessionState,
  useSessionSetupReady,
} from "./SessionProvider";

// The caller owns routing and supplies an already validated, fixed OIDC configuration.
// This reusable login boundary does not select an identity or enter the workbench.
export function LoginEntry({
  controller,
}: {
  controller: SessionController | null;
}) {
  if (!controller) return <LoginPage />;
  return (
    <SessionProvider controller={controller}>
      <SessionLoginAction />
    </SessionProvider>
  );
}
function SessionLoginAction() {
  const controller = useSessionController();
  const { status, message } = useSessionState();
  const setupReady = useSessionSetupReady();
  const canLogin =
    setupReady &&
    (status === "SIGNED_OUT" ||
      status === "UNAVAILABLE" ||
      status === "EXPIRED" ||
      status === "DENIED");
  return (
    <LoginPage
      onLogin={canLogin ? () => controller.login() : undefined}
      pending={!setupReady || status === "INITIALIZING"}
      message={
        status === "UNAVAILABLE"
          ? "登录服务暂不可用，请稍后重试。"
          : status === "EXPIRED" || status === "DENIED"
            ? "请重新登录以核对当前会话。"
            : status === "READY" || status === "SELECTING"
              ? "已完成身份验证，请等待会话流程继续。"
              : (message ?? undefined)
      }
    />
  );
}
