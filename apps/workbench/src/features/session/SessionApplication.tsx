import { LoginPage } from "./LoginPage";
import type { SessionRuntime } from "./sessionConfiguration";
import { useEffect, useMemo, useState } from "react";
import {
  SessionProvider,
  useActorSession,
  useSessionState,
  useSessionSetupReady,
  useWorkbenchSession,
} from "./SessionProvider";
import { LoginEntry } from "./LoginEntry";
import { AppointmentChooser } from "./AppointmentChooser";
import { RecoveryPage } from "./RecoveryPage";
import { App } from "../../App";
import { createWorkbenchApi } from "../../lib/api";
import type { SessionContext, SessionController } from "./sessionController";
import { createIdentityApi, type IdentityApi } from "../identity/identityApi";
import { IdentityAdminApplication } from "../identity/IdentityAdminApplication";
import { isIdentityAdminRoute, type IdentityAdminRoute } from "../identity/identityRoutes";
export function SessionApplication({
  controller,
  api,
  identityApi,
  configurationError,
}: SessionRuntime & { identityApi?: IdentityApi }) {
  const [path, setPath] = useState(location.pathname);
  useEffect(() => {
    const changed = () => setPath(location.pathname);
    window.addEventListener("popstate", changed);
    return () => window.removeEventListener("popstate", changed);
  }, []);
  const transport = useMemo(
    () =>
      api ??
      (controller
        ? createWorkbenchApi(undefined, location.origin, controller.recovery)
        : undefined),
    [api, controller],
  );
  const identityTransport = useMemo(
    () => identityApi ?? (controller ? createIdentityApi(controller.recovery, undefined, location.origin) : undefined),
    [controller, identityApi],
  );
  if (!["/", "/login", "/auth/callback", "/workbench"].includes(path) && !isIdentityAdminRoute(path))
    return <LoginPage message="此入口暂不可用，请通过工作台入口继续。" />;
  if (!controller || !transport)
    return <LoginPage message={configurationError} />;
  return (
    <SessionProvider controller={controller}>
      <SessionRoutes
        controller={controller}
        api={transport}
        identityApi={identityTransport!}
        path={path}
        navigate={(next) => {
          history.replaceState(null, "", next);
          setPath(next);
        }}
      />
    </SessionProvider>
  );
}
type Admission = {
  controller: SessionController;
  epoch: number;
  scope: string | null;
  stage: "workbench" | "admin" | "recovery" | "unqualified" | "choosing";
};
function SessionRoutes({
  controller,
  api,
  identityApi,
  path,
  navigate,
}: {
  controller: SessionController;
  api: NonNullable<SessionRuntime["api"]>;
  identityApi: IdentityApi;
  path: string;
  navigate: (path: "/login" | "/workbench" | IdentityAdminRoute) => void;
}) {
  const state = useSessionState(),
    setup = useSessionSetupReady(),
    actor = useActorSession(),
    workbench = useWorkbenchSession();
  const [admission, setAdmission] = useState<Admission | null>(null);
  const context = state.context;
  const adminIntent = isIdentityAdminRoute(path);
  const current =
    admission?.controller === controller &&
    admission.epoch === state.identityEpoch &&
    admission.scope === context?.actorScopeKey;
  const stage = current ? admission.stage : "choosing";
  useEffect(() => {
    if (!setup || state.status === "INITIALIZING") return;
    if (state.status === "READY" || state.status === "SELECTING") {
      if (path !== "/workbench" && !isIdentityAdminRoute(path)) navigate("/workbench");
    } else if (path !== "/login") navigate("/login");
  }, [setup, state.status, path]);
  function selectStage(next: Admission["stage"], expected?: SessionContext) {
    controller.checkLifetime();
    const latest = controller.getSnapshot(),
      selected = latest.context;
    if (
      setup &&
      next === "choosing" &&
      latest.status === "SELECTING" &&
      selected
    ) {
      setAdmission({
        controller,
        epoch: latest.identityEpoch,
        scope: selected.actorScopeKey,
        stage: next,
      });
      return;
    }
    if (
      !setup ||
      latest.status !== "READY" ||
      latest.switchConfirmation ||
      !selected?.actorScopeKey ||
      (expected && expected !== selected)
    )
      return;
    setAdmission({
      controller,
      epoch: latest.identityEpoch,
      scope: selected.actorScopeKey,
      stage: next,
    });
  }
  function confirmed(selected: SessionContext) {
    let pending = true;
    try {
      pending = !!api.recovery.read();
    } catch {
      /* Recovery page owns explicit invalid-clue cleanup. */
    }
    selectStage(
      pending
        ? "recovery"
        : adminIntent
          ? selected.canEnterIdentityAdmin && selected.selectedOnBehalfAppointmentId === null
            ? "admin"
            : "unqualified"
          : selected.canEnterWorkbench
            ? "workbench"
            : "unqualified",
      selected,
    );
  }
  if (!setup || !["READY", "SELECTING"].includes(state.status))
    return <LoginEntry controller={controller} />;
  if (
    stage === "admin" &&
    adminIntent &&
    context?.canEnterIdentityAdmin &&
    context.selectedOnBehalfAppointmentId === null &&
    actor
  ) {
    const own = context.appointmentChoices.find((choice) => choice.id === context.selectedAppointmentId)?.label;
    return (
      <IdentityAdminApplication
        session={actor}
        api={identityApi}
        path={path}
        onNavigate={navigate}
        sessionActions={<div className="session-actions"><span>{context.displayName} · {own} / 管理模式</span><button onClick={() => selectStage("choosing")}>切换任职</button><button onClick={() => void controller.logout()}>退出</button></div>}
      />
    );
  }
  // Admission is an entry boundary, not a subscription to live App writes.
  // Keeping this branch first preserves its in-memory OriginalWrite and editor.
  if (stage === "workbench" && workbench && context) {
    const own = context.appointmentChoices.find(
      (c) => c.id === context.selectedAppointmentId,
    )?.label;
    const delegated = context.delegatedAppointmentChoices.find(
      (c) => c.id === context.selectedOnBehalfAppointmentId,
    )?.label;
    return (
      <App
        session={workbench}
        api={api}
        sessionActions={
          <div className="session-actions">
            <span>
              {context.displayName} · {own}
              {delegated ? `（代办：${delegated}）` : ""}
            </span>
            <button onClick={() => selectStage("choosing")}>切换任职</button>
            <button onClick={() => void controller.logout()}>退出</button>
          </div>
        }
        sessionNotice={
          state.warning
            ? "会话即将到期，请及时核对当前操作；到期后需重新登录。"
            : null
        }
      />
    );
  }
  let invalidStorage = false;
  try {
    api.recovery.read();
  } catch {
    invalidStorage = true;
  }
  if ((stage === "recovery" || invalidStorage) && context)
    return (
      <RecoveryPage
        controller={controller}
        api={api}
        onChooseIdentity={() => selectStage("choosing")}
        onReady={() => {
          if (controller.getSnapshot().identityEpoch !== state.identityEpoch)
            return;
          try {
            if (api.recovery.read()) return;
          } catch {
            return;
          }
          selectStage(
            stage === "recovery"
              ? adminIntent
                ? context.canEnterIdentityAdmin && context.selectedOnBehalfAppointmentId === null
                  ? "admin"
                  : "unqualified"
                : context.canEnterWorkbench
                  ? "workbench"
                  : "unqualified"
              : "choosing",
            context,
          );
        }}
      />
    );
  return (
    <AppointmentChooser
      controller={controller}
      onConfirmed={confirmed}
      entryMessage={
        stage === "unqualified"
          ? adminIntent
            ? "当前任职不能进入身份管理；请确认本人任职具备管理资格。"
            : context?.canEnterIdentityAdmin
              ? "当前任职不能进入业务工作台；具备管理资格时可使用身份管理地址。"
              : "当前任职不能进入业务工作台；请联系律所管理员。"
          : undefined
      }
    />
  );
}
