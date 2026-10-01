import {SessionActions} from './SessionActions';
import {BusinessNavigationContext,type BusinessNavigationActions} from '../workcard/BusinessNavigation';
import {BusinessOverviewPage} from '../businessOverview/BusinessOverviewPage';
import {createBusinessOverviewTransport} from '../../lib/businessOverviewTransport';
import {LeadManagementPage} from '../leadManagement/LeadManagementPage';
import {createLeadManagementTransport} from '../../lib/leadManagementTransport';
import {TeamManagementRoute} from '../teamManagement/TeamManagementRoute';
import {createTeamTransport} from '../../lib/teamTransport';
import {createOwnerExceptionTransport} from '../../lib/ownerExceptionTransport';
import {ContractLedgerPage} from '../contracts/ContractLedgerPage';
import {createContractsTransport} from '../../lib/contractsTransport';
const isLedgerRoute = (path:string) => path === "/management/opportunities";
const isOwnerManagementRoute = (path: string) => ["/management/team-tasks", "/management/team-tasks/operations"].includes(path);
import { LeadIntakeApplication, leadIntakeRoute } from "../lead-intake/LeadIntakeApplication";
import { createLeadIntakeApi } from "../lead-intake/leadIntakeApi";
import { LoginPage } from "./LoginPage";
import { consumeLoginDestination, rememberLoginDestination } from './loginDestination';
import type { SessionRuntime } from "./sessionConfiguration";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
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
import { IdentityAdminApplication, type IdentityLeaveGuard } from "../identity/IdentityAdminApplication";
import { isIdentityAdminRoute, type IdentityAdminRoute } from "../identity/identityRoutes";
export function SessionApplication({
  controller,
  api,
  identityApi,
  intakeApi,
  configurationError,
}: SessionRuntime & { identityApi?: IdentityApi; intakeApi?: ReturnType<typeof createLeadIntakeApi> }) {
  const [path, setPath] = useState(location.pathname);
  const leaveGuard = useRef<IdentityLeaveGuard | null>(null);
  const currentPath = useRef(path); currentPath.current = path;
  const registerLeaveGuard = useCallback((guard: IdentityLeaveGuard | null) => { leaveGuard.current = guard; }, []);
  const guardedLeave = useCallback((next: () => void) => { if (leaveGuard.current) leaveGuard.current(next); else next(); }, []);
  useEffect(() => {
    const changed = () => {
      const target = location.pathname;
      if (leaveGuard.current) {
        history.replaceState(null, "", currentPath.current);
        leaveGuard.current(() => { history.replaceState(null, "", target); setPath(target); });
      } else setPath(target);
    };
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
  if (!["/", "/login", "/auth/callback", "/workbench", leadIntakeRoute].includes(path) && !isIdentityAdminRoute(path) && !isOwnerManagementRoute(path) && !isLedgerRoute(path) && path!=="/management/contracts" && path!=="/management/leads" && path!=="/management/overview")
    return <LoginPage message="此入口暂不可用，请通过工作台入口继续。" />;
  if (!controller || !transport)
    return <LoginPage message={configurationError} />;
  return (
    <SessionProvider controller={controller}>
      <SessionRoutes
        controller={controller}
        api={transport}
        identityApi={identityTransport!}
        intakeApi={intakeApi}
        path={path}
        registerLeaveGuard={registerLeaveGuard}
        guardedLeave={guardedLeave}
        navigate={(next) => {
          if (next !== location.pathname) {
            if (["/login", "/auth/callback", "/"].includes(location.pathname) || next === "/login") history.replaceState(null, "", next);
            else history.pushState(null, "", next);
          }
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
  stage: "businessOverview" | "leadManagement" | "businessManagement" | "ledger" | "management" | "workbench" | "intake" | "admin" | "recovery" | "unqualified" | "choosing";
};
function SessionRoutes({
  controller,
  api,
  identityApi,
  intakeApi: providedIntakeApi,
  path,
  navigate,
  registerLeaveGuard,
  guardedLeave,
}: {
  controller: SessionController;
  api: NonNullable<SessionRuntime["api"]>;
  identityApi: IdentityApi;
  intakeApi?: ReturnType<typeof createLeadIntakeApi>;
  path: string;
  navigate: (path: "/login" | "/workbench" | typeof leadIntakeRoute | IdentityAdminRoute | "/management/team-tasks" | "/management/team-tasks/operations" | "/management/opportunities" | "/management/contracts" | "/management/leads" | "/management/overview") => void;
  registerLeaveGuard: (guard: IdentityLeaveGuard | null) => void;
  guardedLeave: IdentityLeaveGuard;
}) {
  const state = useSessionState(),
    setup = useSessionSetupReady(),
    actor = useActorSession(),
    workbench = useWorkbenchSession();
  const [leadView,setLeadView]=useState<'leads'|'sources'>('leads');
  const [intakeTask, setIntakeTask] = useState<{ id: string; epoch: number; scope: string } | null>(null);
  const [admission, setAdmission] = useState<Admission | null>(null);
  const context = state.context;
  const loginDestination = useRef<ReturnType<typeof consumeLoginDestination> | null>(null);
  const overviewIntent = path === "/management/overview";
  const overviewTransport=useMemo(()=>createBusinessOverviewTransport(),[]);
  const leadIntent = path === "/management/leads";
  const leadTransport=useMemo(()=>createLeadManagementTransport(),[]);
  const businessIntent = path === "/management/contracts";
  const teamTransport=useMemo(()=>createTeamTransport(),[]);
  const teamOwnerTransport=useMemo(()=>createOwnerExceptionTransport(controller.recovery),[controller]);
  const managementContracts = useMemo(()=>createContractsTransport(controller.recovery),[controller]);
  const ledgerIntent = isLedgerRoute(path);
  const managementIntent = isOwnerManagementRoute(path);
  const adminIntent = isIdentityAdminRoute(path);
  const intakeIntent = path === leadIntakeRoute;
  const intakeApi = useMemo(() => providedIntakeApi ?? createLeadIntakeApi(controller.recovery), [controller, providedIntakeApi]);
  const current =
    admission?.controller === controller &&
    admission.epoch === state.identityEpoch &&
    admission.scope === context?.actorScopeKey;
  const stage = current ? admission.stage : "choosing";
  useEffect(() => {
    if (!setup || state.status === "INITIALIZING") return;
    if (state.status === "READY" || state.status === "SELECTING") {
      if (path !== "/workbench" && !isIdentityAdminRoute(path) && path !== leadIntakeRoute && !managementIntent && !ledgerIntent && !businessIntent && !leadIntent && !overviewIntent) {
        loginDestination.current ??= consumeLoginDestination();
        navigate(loginDestination.current);
      }
    } else if (path !== "/login") {
      if (state.status === 'SIGNED_OUT') rememberLoginDestination(path);
      navigate("/login");
    }
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
        : overviewIntent
          ? selected.canReadBusinessOverview === true && selected.selectedOnBehalfAppointmentId === null ? "businessOverview" : "unqualified"
        : leadIntent
          ? selected.canReadLeadManagement === true && selected.selectedOnBehalfAppointmentId === null ? "leadManagement" : "unqualified"
        : businessIntent
          ? selected.canReadBusinessManagement === true && selected.selectedOnBehalfAppointmentId === null ? "businessManagement" : "unqualified"
        : ledgerIntent
          ? selected.canReadOpportunityLedger === true && selected.selectedOnBehalfAppointmentId === null ? "ledger" : "unqualified"
        : managementIntent
          ? (selected.canManageOwnerExceptions === true || path!=="/management/team-tasks/operations"&&selected.canReadTeamTasks === true) && selected.selectedOnBehalfAppointmentId === null ? "management" : "unqualified"
        : intakeIntent
          ? selected.selectedOnBehalfAppointmentId === null ? "intake" : "unqualified"
          : adminIntent
          ? selected.canEnterIdentityAdmin && selected.selectedOnBehalfAppointmentId === null
            ? "admin"
            : "unqualified"
          : selected.canEnterWorkbench
            ? "workbench"
            : selected.canReadLeadManagement === true && selected.selectedOnBehalfAppointmentId === null ? "leadManagement" : selected.canReadBusinessManagement === true ? "businessManagement" : selected.canReadTeamTasks === true && selected.selectedOnBehalfAppointmentId === null ? "management" : selected.canReadBusinessOverview === true && selected.selectedOnBehalfAppointmentId === null ? "businessOverview" : "unqualified",
      selected,
    );
  }
  function enterIdentityAdmin(
    expected: SessionContext,
    expectedEpoch: number,
  ) {
    controller.checkLifetime();
    const latest = controller.getSnapshot(),
      selected = latest.context;
    if (
      !setup ||
      latest.status !== "READY" ||
      latest.switchConfirmation ||
      latest.identityEpoch !== expectedEpoch ||
      selected !== expected ||
      !selected.actorScopeKey ||
      !selected.selectedAppointmentId ||
      selected.selectedOnBehalfAppointmentId !== null ||
      !selected.canEnterIdentityAdmin
    )
      return;
    setAdmission({
      controller,
      epoch: latest.identityEpoch,
      scope: selected.actorScopeKey,
      stage: "choosing",
    });
    navigate("/admin/identity/principals");
  }
  useEffect(() => {
    if (!current || !context || ["choosing","recovery","unqualified"].includes(stage)) return;
    const target=intakeIntent?'intake':adminIntent?'admin':overviewIntent?'businessOverview':leadIntent?'leadManagement':businessIntent?'businessManagement':ledgerIntent?'ledger':managementIntent?'management':path==='/workbench'?'workbench':null;
    if(target && target!==stage) confirmed(context);
  },[path]);
  const leaveSession=(action:()=>void)=>stage==='admin'||stage==='intake'?guardedLeave(action):action();
  const sessionActions=context?<SessionActions context={context} onSwitch={()=>leaveSession(()=>selectStage('choosing'))} onLogout={()=>leaveSession(()=>void controller.logout())}/>:undefined;
  if (!setup || !["READY", "SELECTING"].includes(state.status))
    return <LoginEntry controller={controller} />;
  if (
    stage === "admin" &&
    adminIntent &&
    context?.canEnterIdentityAdmin &&
    context.selectedOnBehalfAppointmentId === null &&
    actor
  ) {
    return (
      <IdentityAdminApplication
        session={actor}
        api={identityApi}
        path={path}
        onNavigate={navigate}
        registerLeaveGuard={registerLeaveGuard}
        sessionActions={sessionActions}
        onRecover={() => selectStage("recovery")}
      />
    );
  }
  const onOverview = context?.canReadBusinessOverview === true && context.selectedOnBehalfAppointmentId === null ? () => { navigate('/management/overview'); selectStage('businessOverview',context); } : undefined;
  const onLeads = context?.canReadLeadManagement === true && context.selectedOnBehalfAppointmentId === null ? () => { setLeadView('leads'); navigate('/management/leads'); selectStage('leadManagement',context); } : undefined;
  const navigation:BusinessNavigationActions & {registerLeaveGuard:typeof registerLeaveGuard}=context?.selectedOnBehalfAppointmentId===null?{
    registerLeaveGuard,onLeads,onOverview,
    onSources:onLeads?()=>{setLeadView('sources');navigate('/management/leads');selectStage('leadManagement',context);}:undefined,
    onOpportunities:context.canReadOpportunityLedger?()=>{navigate('/management/opportunities');selectStage('ledger',context);}:undefined,
    onContracts:context.canReadBusinessManagement?()=>{navigate('/management/contracts');selectStage('businessManagement',context);}:undefined,
    onTeam:context.canReadTeamTasks||context.canManageOwnerExceptions?()=>{navigate('/management/team-tasks');selectStage('management',context);}:undefined,
    onTasks:context.canEnterWorkbench?()=>{navigate('/workbench');selectStage('workbench',context);}:undefined,
  }:{registerLeaveGuard};
  return <BusinessNavigationContext.Provider value={navigation}>{renderBusinessRoute()}</BusinessNavigationContext.Provider>;
  function renderBusinessRoute(){
  if(stage === "businessOverview" && actor && context?.canReadBusinessOverview === true && context.selectedOnBehalfAppointmentId === null){
    return <BusinessOverviewPage session={actor} api={overviewTransport} onLeads={onLeads}
      onTasks={context.canEnterWorkbench?()=>{navigate('/workbench');selectStage('workbench',context);}:undefined}
      onOpportunities={context.canReadOpportunityLedger?()=>{navigate('/management/opportunities');selectStage('ledger',context);}:undefined}
      onContracts={context.canReadBusinessManagement?()=>{navigate('/management/contracts');selectStage('businessManagement',context);}:undefined}
      onTeam={context.canReadTeamTasks||context.canManageOwnerExceptions?()=>{navigate('/management/team-tasks');selectStage('management',context);}:undefined}
      sessionActions={sessionActions}/>;
  }
  if(stage === "leadManagement" && actor && context?.canReadLeadManagement === true && context.selectedOnBehalfAppointmentId === null){
    return <LeadManagementPage initialView={leadView} onViewChange={setLeadView} onOverview={onOverview} session={actor} api={leadTransport}
      onTask={id=>{if(!context.canEnterWorkbench)throw Error('当前任职无办理权限');setIntakeTask({id,epoch:actor.identityEpoch,scope:actor.actorScopeKey});navigate('/workbench');selectStage('workbench',context);}}
      onTasks={context.canEnterWorkbench?()=>{navigate('/workbench');selectStage('workbench',context);}:undefined}
      onOpportunities={context.canReadOpportunityLedger?()=>{navigate('/management/opportunities');selectStage('ledger',context);}:undefined}
      onContracts={context.canReadBusinessManagement?()=>{navigate('/management/contracts');selectStage('businessManagement',context);}:undefined}
      onTeam={context.canReadTeamTasks||context.canManageOwnerExceptions?()=>{navigate('/management/team-tasks');selectStage('management',context);}:undefined}
      onIntake={()=>{setIntakeTask(null);navigate(leadIntakeRoute);selectStage('intake',context);}}
      sessionActions={sessionActions}/>;
  }
  if(stage === "businessManagement" && actor && context?.canReadBusinessManagement === true && context.selectedOnBehalfAppointmentId === null){
    return <ContractLedgerPage onLeads={onLeads} onOverview={onOverview} onTeam={context.canReadTeamTasks||context.canManageOwnerExceptions?()=>{navigate("/management/team-tasks");selectStage("management",context);}:undefined} session={actor} api={managementContracts} initialView={context.businessManagementViews?.includes("contracts") ? "contracts" : "payments"} onTasks={context.canEnterWorkbench?()=>{navigate('/workbench');selectStage('workbench',context);}:undefined} onBack={context.canReadOpportunityLedger?()=>{navigate('/management/opportunities');selectStage('ledger',context);}:undefined} onManagementTask={id=>{if(!context.canEnterWorkbench)throw Error('当前任职无办理权限');setIntakeTask({id,epoch:actor.identityEpoch,scope:actor.actorScopeKey});navigate('/workbench');selectStage('workbench',context);}} sessionActions={sessionActions}/>;
  }
  if (stage === "ledger" && actor && context?.canReadOpportunityLedger === true && context.selectedOnBehalfAppointmentId === null) {
    return <App onLeads={onLeads} onOverview={onOverview} onTeam={context.canReadTeamTasks||context.canManageOwnerExceptions?()=>{navigate("/management/team-tasks");selectStage("management",context);}:undefined} session={actor} api={api} sessionActions={sessionActions} />;
  }
  if(stage === "management" && path!=="/management/team-tasks/operations" && actor && context?.canReadTeamTasks === true && context.selectedOnBehalfAppointmentId === null){
    return <TeamManagementRoute onLeads={onLeads} onOverview={onOverview} key={`${actor.actorScopeKey}:${actor.identityEpoch}`} session={actor} api={teamTransport} ownerApi={teamOwnerTransport} sessionActions={sessionActions} onTask={id=>{if(!context.canEnterWorkbench)throw Error('当前任职无办理权限');setIntakeTask({id,epoch:actor.identityEpoch,scope:actor.actorScopeKey});navigate('/workbench');selectStage('workbench',context);}} onTasks={context.canEnterWorkbench?()=>{navigate('/workbench');selectStage('workbench',context);}:undefined} onOpportunities={context.canReadOpportunityLedger?()=>{navigate('/management/opportunities');selectStage('ledger',context);}:undefined} onContracts={context.canReadBusinessManagement?()=>{navigate('/management/contracts');selectStage('businessManagement',context);}:undefined}/>;
  }
  if (stage === "management" && managementIntent && actor && context?.canManageOwnerExceptions === true && context.selectedOnBehalfAppointmentId === null) {
    return <App onLeads={onLeads} onOverview={onOverview} onTeam={context.canReadTeamTasks||context.canManageOwnerExceptions?()=>{navigate("/management/team-tasks");selectStage("management",context);}:undefined} session={actor} api={api} sessionActions={sessionActions} />;
  }
  if (stage === "intake" && intakeIntent && actor && context?.selectedOnBehalfAppointmentId === null) {
    return <LeadIntakeApplication returnLabel={onLeads?"返回客户与线索":undefined} session={actor} api={intakeApi} recovery={controller.recovery}
      registerLeaveGuard={registerLeaveGuard}
      onReturn={() => { if(onLeads)onLeads();else {navigate("/workbench"); selectStage(context.canEnterWorkbench ? "workbench" : "choosing");} }}
      onRecover={() => selectStage("recovery")}
      onOpenTask={id => { if (actor.isCurrent()) { setIntakeTask({ id, epoch: actor.identityEpoch, scope: actor.actorScopeKey }); navigate("/workbench"); selectStage("workbench", context); } }}
      sessionActions={sessionActions} />;
  }
  // Admission is an entry boundary, not a subscription to live App writes.
  // Keeping this branch first preserves its in-memory OriginalWrite and editor.
  if (stage === "workbench" && workbench && context) {
    return (
      <App
        onLeads={onLeads} onOverview={onOverview}
        onTeam={context.canReadTeamTasks||context.canManageOwnerExceptions?()=>{navigate("/management/team-tasks");selectStage("management",context);}:undefined}
        onBusinessManagement={context.canReadBusinessManagement?()=>{navigate("/management/contracts");selectStage("businessManagement",context);}:undefined}
        initialTaskId={intakeTask?.epoch === state.identityEpoch && intakeTask.scope === context.actorScopeKey ? intakeTask.id : undefined}
        onIntake={context.selectedOnBehalfAppointmentId === null ? () => { setIntakeTask(null); navigate(leadIntakeRoute); selectStage("intake", context); } : undefined}
        session={workbench}
        api={api}
        sessionActions={sessionActions}
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
              ? overviewIntent
                ? context.canReadBusinessOverview === true && context.selectedOnBehalfAppointmentId === null ? "businessOverview" : "unqualified"
              : leadIntent
                ? context.canReadLeadManagement === true && context.selectedOnBehalfAppointmentId === null ? "leadManagement" : "unqualified"
              : businessIntent
                ? context.canReadBusinessManagement === true && context.selectedOnBehalfAppointmentId === null ? "businessManagement" : "unqualified"
              : ledgerIntent
                ? context.canReadOpportunityLedger === true && context.selectedOnBehalfAppointmentId === null ? "ledger" : "unqualified"
              : managementIntent
                ? (context.canManageOwnerExceptions === true || path!=="/management/team-tasks/operations"&&context.canReadTeamTasks === true) && context.selectedOnBehalfAppointmentId === null ? "management" : "unqualified"
              : intakeIntent
                ? context.selectedOnBehalfAppointmentId === null ? "intake" : "unqualified"
                : adminIntent
                ? context.canEnterIdentityAdmin && context.selectedOnBehalfAppointmentId === null
                  ? "admin"
                  : "unqualified"
                : context.canEnterWorkbench
                  ? "workbench"
                  : context.canReadLeadManagement === true && context.selectedOnBehalfAppointmentId === null ? "leadManagement" : context.canReadBusinessManagement === true ? "businessManagement" : context.canReadTeamTasks === true && context.selectedOnBehalfAppointmentId === null ? "management" : context.canReadBusinessOverview === true && context.selectedOnBehalfAppointmentId === null ? "businessOverview" : "unqualified"
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
      onEnterIdentityAdmin={adminIntent ? undefined : enterIdentityAdmin}
      entryMessage={
        stage === "unqualified"
          ? overviewIntent
            ? "当前任职不能查看经营概览，请确认本人任职具备相关查询权限。"
              : leadIntent
            ? "当前任职不能查询客户与线索，请确认本人任职具备查看权限。"
          : managementIntent
            ? "当前任职不能进入团队待办，请确认本人任职具备管理资格。"
          : adminIntent
            ? "当前任职不能进入身份管理；请确认本人任职具备管理资格。"
            : context?.canEnterIdentityAdmin
              ? "当前任职不能进入业务工作台；具备管理资格时可使用身份管理地址。"
              : "当前任职不能进入业务工作台；请联系律所管理员。"
          : adminIntent
            ? "即将进入身份管理，请确认本次本人任职。"
          : undefined
      }
    />
  );
  }
}
