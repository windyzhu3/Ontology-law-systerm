import {SquaresFour} from '@phosphor-icons/react/SquaresFour';
import './styles/sharedControls.css';
import {BusinessNavigationContext} from './features/workcard/BusinessNavigation';
import {ClassificationCorrectionCard} from './features/transfers/ClassificationCorrectionCard';
import {TransferRuntimeCard} from './features/transfers/TransferRuntimeCard';
import {createTransfersTransport} from './lib/transfersTransport';
import {OpportunityContinuation} from './features/opportunities/OpportunityContinuation';
import {ContractRuntimeCard} from './features/contracts/ContractRuntimeCard';
import {createContractsTransport,type ContractsTransport} from './lib/contractsTransport';
import {QuoteTaskCard} from './features/opportunities/QuoteTaskCard';
import {isQuoteCard,isContractCard,isTransferCard} from './features/workcard/contract';
import {createQuotesTransport,type QuotesTransport} from './lib/quotesTransport';
import {createMaterialsTransport} from './lib/materialsTransport';
import {createCustomerRequirementsTransport} from './lib/customerRequirementsTransport';
import {createOpportunityClosureTransport} from './lib/opportunityClosureTransport';
import { OpportunityLedgerPage } from "./features/opportunities/OpportunityLedgerPage";
import { createOpportunityLedgerTransport, type OpportunityLedgerTransport } from "./lib/opportunityLedgerTransport";
import { OwnerExceptionPage } from "./features/ownerExceptions/OwnerExceptionPage";
import { createOwnerExceptionTransport } from "./lib/ownerExceptionTransport";
import { IdentityDialog } from "./features/identity/IdentityActionConfirmation";
import { useContext, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { Scales } from "@phosphor-icons/react/Scales";
import { CheckCircle } from "@phosphor-icons/react/CheckCircle";
import { ArrowClockwise } from "@phosphor-icons/react/ArrowClockwise";
import {
  createWorkbenchApi,
  type WorkbenchApi,
  type WorkbenchSession,
} from "./lib/api";
import { MyTasks } from "./features/workcard/MyTasks";
import { CurrentCard, type CurrentCardHandle } from "./features/workcard/CurrentCard";
import { WaitingSummary } from "./features/workcard/WaitingSummary";
import { WorkbenchStatus } from "./features/workcard/WorkbenchStatus";
import { useCurrentCard } from "./features/workcard/useCurrentCard";
import "./styles/tokens.css";
import "./styles/workbench.css";

export function App(props: Parameters<typeof WorkbenchApp>[0]) {
  const ownerApi = useMemo(() => createOwnerExceptionTransport(props.api?.recovery), [props.api]);
  if (props.session && ["/management/team-tasks", "/management/team-tasks/operations"].includes(window.location.pathname)) return <OwnerExceptionPage session={props.session} api={ownerApi} sessionActions={props.sessionActions} operations={window.location.pathname.endsWith("/operations")} />;
  return <WorkbenchApp {...props} />;
}
function WorkbenchApp({
  session,
  api,
  sessionActions,
  sessionNotice,
  onIntake,
  onBusinessManagement,
  onTeam,
  onLeads,onOverview,
  initialTaskId,
  ledgerApi,
  quotesApi,
  contractsApi,
}: {
  session?: WorkbenchSession | null;
  api?: WorkbenchApi;
  sessionActions?: ReactNode;
  sessionNotice?: string | null;
  onIntake?: () => void;
  onBusinessManagement?: () => void;
  onTeam?: () => void;
  onLeads?: () => void;onOverview?:()=>void;
  initialTaskId?: string;
  ledgerApi?: OpportunityLedgerTransport;
  quotesApi?: QuotesTransport;
  contractsApi?: ContractsTransport;
}) {
  const [contractReceiptConfirmed,setContractReceiptConfirmed]=useState(false);
  const [contractRevision,setContractRevision]=useState<{session:WorkbenchSession;opportunityId:string}|null>(null);
  const [correction,setCorrection]=useState<{session:WorkbenchSession;opportunityId:string}|null>(null);
  const [continuation,setContinuation]=useState<{session:WorkbenchSession;taskId:string;view:'choose'|'customer'|'attempt'}|null>(null);
  useEffect(()=>{setCorrection(null);setContractRevision(null);setContractReceiptConfirmed(false);},[session]);
  const [dismissedQuote,setDismissedQuote]=useState<string|null>(null);
  const [ledger, setLedger] = useState(window.location.pathname === '/management/opportunities');
  const ledgerTransport = useMemo(() => ledgerApi ?? createOpportunityLedgerTransport(), [ledgerApi]);
  const leaveDestination = useRef<(() => void) | null>(null);
  const navigateLedger = (next:boolean) => {setCorrection(null);setContractRevision(null);setContractReceiptConfirmed(false);history.replaceState(null,'',next?'/management/opportunities':'/workbench');setLedger(next);};
  const transport = useMemo(() => api ?? createWorkbenchApi(), [api]);
  const contractsTransport=useMemo(()=>contractsApi??createContractsTransport(transport.recovery),[transport,contractsApi]);
  const transfersTransport=useMemo(()=>createTransfersTransport(transport.recovery),[transport]);
  const quotesTransport = useMemo(() => quotesApi ?? createQuotesTransport(transport.recovery), [transport,quotesApi]);
  const materialsTransport = useMemo(() => createMaterialsTransport(transport.recovery), [transport]);
  const customerTransport = useMemo(() => createCustomerRequirementsTransport(transport.recovery), [transport]); const closureTransport = useMemo(() => createOpportunityClosureTransport(transport.recovery), [transport]);
  const [dirty, setDirty] = useState(false);
  const [contractLocked,setContractLocked]=useState(false);
  const activeContract=useRef(false);
  const work = useCurrentCard(session, transport, { initialTaskId, deferInitialRead: window.location.pathname === "/management/opportunities", pauseAutomaticRead:contractReceiptConfirmed||(dirty&&activeContract.current)||contractLocked||!!continuation||!!correction||!!contractRevision });
  const envelope = session ? work.envelope : null;
  const canSaveCurrentDraft=!contractRevision&&!correction&&(!envelope?.currentCard||!isTransferCard(envelope.currentCard));
  activeContract.current=!!contractRevision||!!correction||(!!envelope?.currentCard&&(isContractCard(envelope.currentCard)||isTransferCard(envelope.currentCard)));
  const [taskQueueRequest,setTaskQueueRequest]=useState(0);
  const [intakeDiscard, setIntakeDiscard] = useState<HTMLElement | null>(null);
  const draftRef = useRef<CurrentCardHandle>(null);
  const [taskDialogOpen, setTaskDialogOpen] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const [leaveError, setLeaveError] = useState("");
  const leaveLock = useRef(false);
  const activeSession = useRef(session); activeSession.current = session;
  const openBusinessManagement=onLeads??(session?.canReadOpportunityLedger?()=>navigateLedger(true):onBusinessManagement??onTeam??onOverview);
  const protectedWrite = contractLocked || (work.loading && !work.envelope) || work.busy || !!work.pending || !!work.recoveryMarker || work.recoveryBlocked;
  const {registerLeaveGuard}=useContext(BusinessNavigationContext);
  useEffect(()=>{
    registerLeaveGuard?.(next=>{
      if(protectedWrite||leaving||taskDialogOpen||intakeDiscard)return;
      if(dirty){leaveDestination.current=next;setLeaveError('');setIntakeDiscard(document.activeElement as HTMLElement);}
      else next();
    });
    return()=>registerLeaveGuard?.(null);
  },[registerLeaveGuard,protectedWrite,leaving,taskDialogOpen,intakeDiscard,dirty]);
  const saveBeforeIntake = async () => {
    if (leaveLock.current || protectedWrite) return;
    const captured = session;
    leaveLock.current = true; setLeaving(true); setLeaveError("");
    try {
      const saved = await draftRef.current?.saveDraft();
      if (saved && captured?.isCurrent() && activeSession.current?.identityEpoch === captured.identityEpoch && activeSession.current?.actorScopeKey === captured.actorScopeKey) {
        setDirty(false); setIntakeDiscard(null); (leaveDestination.current ?? onIntake)?.();
      } else setLeaveError("草稿尚未确认保存，请留在当前事项核对。");
    } finally {leaveLock.current=false; setLeaving(false);}
  };
  useEffect(() => {
    const protect = (event: BeforeUnloadEvent) => {if (dirty || work.pending || work.recoveryMarker || work.busy) {event.preventDefault(); event.returnValue="";}};
    window.addEventListener("beforeunload",protect);
    return () => window.removeEventListener("beforeunload",protect);
  }, [dirty,work.pending,work.recoveryMarker,work.busy]);
  const logicalFocus = useRef<string | null>(null);
  useLayoutEffect(() => {
    if (document.activeElement === document.body && logicalFocus.current)
      document.getElementById(logicalFocus.current)?.focus();
  }, [envelope]);
  const allowedRoute =
    window.location.pathname === "/workbench" ||
    window.location.pathname === "/" || window.location.pathname === "/management/opportunities";
  if(!contractRevision&&!correction&&session&&continuation?.session===session&&!ledger)return <OpportunityContinuation key={continuation.taskId} session={session} taskId={continuation.taskId} initialView={continuation.view} ledgerApi={ledgerTransport} quotesApi={quotesTransport} customerApi={customerTransport} materialsApi={materialsTransport} contractsApi={contractsTransport} closureApi={closureTransport} sessionActions={sessionActions} onBack={()=>{setContinuation(null);void work.refresh();}} onTasks={()=>{setContinuation(null);setTaskQueueRequest(n=>n+1);void work.refresh();}}/>;
  if(!contractRevision&&!correction&&session && !ledger && taskQueueRequest===0 && envelope?.currentCard && isQuoteCard(envelope.currentCard) && dismissedQuote!==envelope.currentCard.taskId) return <QuoteTaskCard session={session} taskId={envelope.currentCard.taskId} api={quotesTransport} sessionActions={sessionActions} onTasks={()=>{setTaskQueueRequest(n=>n+1);setDismissedQuote(envelope.currentCard!.taskId);void work.refresh();}} onLedger={session.canReadOpportunityLedger?()=>navigateLedger(true):undefined}/>;
  return (
    <>
      {session && <OpportunityLedgerPage onTeam={!protectedWrite&&!dirty?onTeam:undefined} onLeads={!protectedWrite&&!dirty?onLeads:undefined} onOverview={!protectedWrite&&!dirty?onOverview:undefined} onContractRevision={id=>{if(protectedWrite||!session.isCurrent())return;setDirty(false);setContinuation(null);setTaskQueueRequest(0);navigateLedger(false);setContractRevision({session,opportunityId:id});void work.selectTask(null);}} onCorrectClassification={id=>{if(!session||protectedWrite||!session.isCurrent())return;setDirty(false);setContinuation(null);setTaskQueueRequest(0);navigateLedger(false);setCorrection({session,opportunityId:id});void work.selectTask(null);}} session={session} api={ledgerTransport} closureApi={closureTransport} customerApi={customerTransport} materialsApi={materialsTransport} quotesApi={quotesTransport} contractsApi={contractsTransport} recoveryActions={(work.recoveryMarker||work.recoveryBlocked)&&<section aria-label="核对原操作结果"><WorkbenchStatus work={work}/>{work.recoveryMarker&&<button className="primary" disabled={work.busy} onClick={()=>void work.recover()}>核对本次结果</button>}</section>} onContractTask={taskId=>{if(protectedWrite||!session.isCurrent())return;setTaskQueueRequest(0);setDismissedQuote(null);setDirty(false);navigateLedger(false);void work.selectTask(taskId);}} active={ledger} blocked={protectedWrite || dirty} sessionActions={sessionActions} onReturn={session.canEnterWorkbench === true ? () => {setTaskQueueRequest(n=>n+1);navigateLedger(false); if(!envelope)void work.selectTask(null);} : undefined} onHandle={detail => {
        if(protectedWrite || !detail.canHandle || !detail.task || !session.isCurrent())return;
        setTaskQueueRequest(0);
        const next=()=>{setDirty(false);navigateLedger(false);void work.selectTask(detail.task!.id, detail.task!);};
        if(dirty){leaveDestination.current=next;setLeaveError('');setIntakeDiscard(document.activeElement as HTMLElement);}else next();
      }} />}
      <div hidden={ledger}>
      <header className="app-header" inert={!!intakeDiscard || taskDialogOpen}>
        <div className="brand">
          <Scales size={30} aria-hidden="true" />
          <span>律所工作助手</span>
        </div>
        <div className="workbench-header-actions">{openBusinessManagement&&<button className="business-management-entry" disabled={protectedWrite||!!intakeDiscard||taskDialogOpen} onClick={event=>{leaveDestination.current=openBusinessManagement;if(dirty){setLeaveError('');setIntakeDiscard(event.currentTarget);}else openBusinessManagement();}}><SquaresFour size={20} aria-hidden="true"/>业务管理</button>}{onIntake && <button className="intake-shortcut" disabled={protectedWrite || !!intakeDiscard || taskDialogOpen} onClick={event => { leaveDestination.current=onIntake ?? null; if (dirty) {setLeaveError("");setIntakeDiscard(event.currentTarget);} else onIntake(); }}>录入线索</button>}
        {sessionActions ??
          (session?.displayName && <span>{session.displayName}</span>)}</div>
      </header>
      <main
        className="workbench"
        inert={!!intakeDiscard || taskDialogOpen}
        aria-label="责任工作台"
        onFocusCapture={(event) => {
          logicalFocus.current = event.target.id || null;
        }}
      >
        {sessionNotice && (
          <p className="feedback" role="status">
            {sessionNotice}
          </p>
        )}
        {!allowedRoute ? (
          <section className="empty-state">
            <h1>此入口暂不可用</h1>
            <p>请通过工作台入口处理当前责任。</p>
          </section>
        ) : !session ? (
          <section className="empty-state">
            <h1>工作台暂不可用</h1>
            <p>登录服务尚未接入，接入后即可安全查看当前责任。</p>
          </section>
        ) : (
          <>
            <div className="task-header"><div className="today-summary">
              <CheckCircle size={25} aria-hidden="true" />
              <p aria-live="polite">
                {envelope?.todaySummary ??
                  (work.loading ? "正在读取当前责任…" : "当前责任暂不可用")}
              </p>
              <button
                className="refresh-button workbench-control"
                onClick={() => void work.refresh()}
                disabled={work.loading || work.busy || contractLocked || ((!!correction||!!contractRevision)&&dirty) || (!!envelope?.currentCard && (isContractCard(envelope.currentCard)||isTransferCard(envelope.currentCard)) && dirty)}
                aria-label="刷新当前责任"
              >
                <ArrowClockwise size={20} aria-hidden="true" />
                <span>刷新</span>
              </button>
            </div>
            {envelope?.myTasks && <MyTasks openRequest={taskQueueRequest} tasks={envelope.myTasks} recommendedTaskId={envelope.recommendedTaskId} selectedTaskId={contractRevision||correction||dismissedQuote===envelope.currentCard?.taskId?undefined:envelope.currentCard?.taskId} dirty={dirty} blocked={protectedWrite} saveDraft={canSaveCurrentDraft?() => draftRef.current?.saveDraft() ?? Promise.resolve(false):undefined} onDialogChange={setTaskDialogOpen} select={(taskId) => { setContractReceiptConfirmed(false);setContractRevision(null);setCorrection(null);setTaskQueueRequest(0);setDismissedQuote(null); setDirty(false); void work.selectTask(taskId); }} />}
            </div>
            <WorkbenchStatus work={work} />
            {!work.busy && (work.pending || work.recoveryMarker) && <div className="recovery-actions"><button className="primary-action" disabled={work.busy} onClick={() => void work.recover()}>核对本次结果</button>{work.pending && <details className="recovery-options"><summary>其他核对方式</summary><p>仅重试已确认过的原请求，保留同一请求标识和内容。</p><button disabled={work.busy} onClick={() => void work.replay()}>使用原请求重试</button></details>}</div>}

            {!ledger&&session&&contractRevision?.session===session ? <ContractRuntimeCard key={contractRevision.opportunityId} embedded onDirtyChange={setDirty} onLockedChange={setContractLocked} session={session} opportunityId={contractRevision.opportunityId} api={contractsTransport} onContinueTask={id=>{setContractRevision(null);setDirty(false);setTaskQueueRequest(0);setDismissedQuote(null);void work.selectTask(id);}} onTasks={()=>{setContractRevision(null);setDirty(false);setTaskQueueRequest(n=>n+1);void work.refresh();}}/> : !ledger&&session&&correction?.session===session ? <ClassificationCorrectionCard session={session} opportunityId={correction.opportunityId} api={transfersTransport} onDirtyChange={setDirty} onLockedChange={setContractLocked} onBack={()=>{setDirty(false);navigateLedger(true);void work.refresh();}}/> : !ledger && session && envelope?.currentCard && isTransferCard(envelope.currentCard) && taskQueueRequest===0 && dismissedQuote!==envelope.currentCard.taskId ? <TransferRuntimeCard key={envelope.currentCard.taskId} session={session} taskId={envelope.currentCard.taskId} api={transfersTransport} onDirtyChange={setDirty} onLockedChange={setContractLocked} onTasks={()=>{setContractReceiptConfirmed(false);setDirty(false);setTaskQueueRequest(n=>n+1);setDismissedQuote(envelope.currentCard!.taskId);void work.refresh();}}/> : !ledger && session && envelope?.currentCard && isContractCard(envelope.currentCard) && taskQueueRequest===0 && dismissedQuote!==envelope.currentCard.taskId ? <ContractRuntimeCard key={envelope.currentCard.taskId} embedded onReceiptConfirmed={()=>setContractReceiptConfirmed(true)} draftRef={draftRef} onDirtyChange={setDirty} onLockedChange={setContractLocked} session={session} taskId={envelope.currentCard.taskId} api={contractsTransport} onContinueTask={nextId=>{setContractReceiptConfirmed(false);setDirty(false);setTaskQueueRequest(0);setDismissedQuote(null);void work.selectTask(nextId);}} onTasks={()=>{setContractReceiptConfirmed(false);setDirty(false);setTaskQueueRequest(n=>n+1);setDismissedQuote(envelope.currentCard!.taskId);void work.refresh();}}/> : envelope?.currentCard && (isQuoteCard(envelope.currentCard)||isContractCard(envelope.currentCard)||isTransferCard(envelope.currentCard)) ? <section className="empty-state"><h1>{envelope.currentCard.businessPurpose.label}</h1><p>选择我的待办，或继续核对当前事项。</p><button className="primary" disabled={protectedWrite} onClick={()=>{setTaskQueueRequest(0);setDismissedQuote(null);}}>{isQuoteCard(envelope.currentCard)?"继续办理报价事项":isTransferCard(envelope.currentCard)?"继续办理转案事项":"继续办理合同事项"}</button></section> : envelope?.currentCard ? (
              <CurrentCard
                session={session}
                key={envelope.currentCard.taskId}
                card={envelope.currentCard}
                draftRef={draftRef}
                onBusinessContinue={session?view=>{if(protectedWrite||!session.isCurrent())return;const next=()=>{setDirty(false);setContinuation({session,taskId:envelope.currentCard!.taskId,view});};if(dirty){leaveDestination.current=next;setLeaveError('');setIntakeDiscard(document.activeElement as HTMLElement);}else next();}:undefined}
                onDirtyChange={setDirty}
                composer={envelope.chatComposer}
                busy={work.busy}
                blocked={
                  !!work.pending || !!work.recoveryMarker || work.recoveryBlocked || work.needsRefresh
                }
                save={work.save}
                submit={work.confirm}
              />
            ) : (
              envelope && (
                <section className="empty-state">
                  <CheckCircle size={36} aria-hidden="true" />
                  <h1>
                    {envelope.waitingCount > 0
                      ? "当前无可处理责任，另有等待事项"
                      : "当前暂无可处理责任"}
                  </h1>
                  <p>新的责任出现后会在这里显示。</p>
                </section>
              )
            )}
            {envelope && (
              <WaitingSummary
                next={envelope.nextSummaries}
                count={envelope.waitingCount}
              />
            )}
          </>
        )}
      </main>
      </div>
      {intakeDiscard && <IdentityDialog title="离开当前待办？" locked={leaving} returnFocus={intakeDiscard} onCancel={() => setIntakeDiscard(null)}><p>{canSaveCurrentDraft?'当前有未保存内容，可以保存草稿后继续。':'当前有未提交内容，可继续填写，或放弃后离开。'}</p>{leaveError && <p role="alert">{leaveError}</p>}<div className="identity-form-actions"><button disabled={leaving} onClick={() => setIntakeDiscard(null)}>继续填写</button><button disabled={protectedWrite || leaving} onClick={() => { setDirty(false); setIntakeDiscard(null); (leaveDestination.current ?? onIntake)?.(); }}>放弃并继续</button><>{canSaveCurrentDraft&&<button className="identity-primary" disabled={protectedWrite || leaving} onClick={() => void saveBeforeIntake()}>{leaving ? "正在保存草稿…" : "保存草稿并继续"}</button>}</></div></IdentityDialog>}
    </>
  );
}
