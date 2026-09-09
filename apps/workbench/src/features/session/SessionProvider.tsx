import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useSyncExternalStore,
  type ReactNode,
} from "react";
import type { WorkbenchSession } from "../../lib/api";
import type { SessionController } from "./sessionController";
const SessionContext = createContext<SessionController | null>(null);
export function SessionProvider({
  children,
  controller,
}: {
  controller: SessionController;
  children: ReactNode;
}) {
  useEffect(() => {
    const detach = controller.attachBrowser();
    if (controller.getSnapshot().status === "SIGNED_OUT")
      void controller.initialize();
    return detach;
  }, [controller]);
  return (
    <SessionContext.Provider value={controller}>
      {children}
    </SessionContext.Provider>
  );
}
export function useSessionController() {
  const controller = useContext(SessionContext);
  if (!controller) throw new Error("会话服务不可用。");
  return controller;
}
export function useSessionState() {
  const controller = useSessionController();
  return useSyncExternalStore(controller.subscribe, controller.getSnapshot);
}
export function useWorkbenchSession(): WorkbenchSession | null {
  const controller = useSessionController();
  const state = useSyncExternalStore(
    controller.subscribe,
    controller.getSnapshot,
  );
  return useMemo(() => {
    const context = state.context;
    if (
      state.status !== "READY" ||
      !context?.canEnterWorkbench ||
      !context.actorScopeKey ||
      !context.selectedAppointmentId
    )
      return null;
    const identityEpoch = state.identityEpoch,
      actorScopeKey = context.actorScopeKey;
    return {
      identityEpoch,
      actorScopeKey,
      selectedAppointmentId: context.selectedAppointmentId,
      selectedOnBehalfAppointmentId: context.selectedOnBehalfAppointmentId,
      displayName: context.displayName,
      getValidAccessToken: () => controller.getValidAccessToken(),
      isCurrent: () =>
        controller.getSnapshot().identityEpoch === identityEpoch &&
        controller.getSnapshot().context?.actorScopeKey === actorScopeKey,
      invalidate: (status: number) =>
        controller.invalidate(status === 401 ? "EXPIRED" : "DENIED"),
    };
  }, [controller, state.context, state.identityEpoch, state.status]);
}
