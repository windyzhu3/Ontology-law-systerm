import {useEffect,useMemo,useRef,useState,type ReactNode} from 'react';
import {CheckCircle,User} from '@phosphor-icons/react';
import type {WorkbenchSession} from '../../lib/api';
import type {OpportunityLedgerTransport,LedgerDetail} from '../../lib/opportunityLedgerTransport';
import type {QuotesTransport} from '../../lib/quotesTransport';
import type {CustomerRequirementsTransport} from '../../lib/customerRequirementsTransport';
import type {MaterialsTransport} from '../../lib/materialsTransport';
import type {ContractsTransport} from '../../lib/contractsTransport';
import type {OpportunityClosureTransport} from '../../lib/opportunityClosureTransport';
import {FollowupAttemptCard} from './FollowupAttemptCard';
import {createFollowupAttemptsTransport} from '../../lib/followupAttemptsTransport';
import {QuoteCard} from './QuoteCard';
import {CustomerRequirementsCard} from './CustomerRequirementsCard';
import {MaterialsCard} from './MaterialsCard';
import {OpportunityClosure} from './OpportunityClosure';
import {ContractRuntimeCard} from '../contracts/ContractRuntimeCard';
import {MyTasksControl} from '../workcard/MyTasksControl';
import master from './ledgerMaster.css?inline';
import scales from './assets/Scales-green.svg';
type View='missing'|'choose'|'quote'|'customer'|'materials'|'contract'|'close'|'attempt';
export function OpportunityContinuation({session,taskId,ledgerApi,quotesApi,customerApi,materialsApi,contractsApi,closureApi,onBack,onTasks,sessionActions,initialView='choose'}:{session:WorkbenchSession;taskId:string;ledgerApi:OpportunityLedgerTransport;quotesApi:QuotesTransport;customerApi:CustomerRequirementsTransport;materialsApi:MaterialsTransport;contractsApi:ContractsTransport;closureApi:OpportunityClosureTransport;onBack:()=>void;onTasks:()=>void;sessionActions?:ReactNode;initialView?:'choose'|'customer'|'attempt'}){
 const [loaded,setLoaded]=useState<{session:WorkbenchSession;taskId:string;detail:LedgerDetail}|null>(null),[error,setError]=useState(false),[refresh,setRefresh]=useState(0),[view,setView]=useState<View>(initialView),[locked,setLocked]=useState(false);
 const attemptsApi=useMemo(()=>createFollowupAttemptsTransport(quotesApi.recovery),[quotesApi]);
 const returnTo=useRef<View>('choose');
 useEffect(()=>{const style=document.createElement('style');style.textContent=master;document.head.append(style);return()=>style.remove();},[]);
 useEffect(()=>{let live=true;const abort=new AbortController();setLoaded(null);setError(false);ledgerApi.taskContext(session,taskId,abort.signal).then(detail=>{if(live&&session.isCurrent())setLoaded({session,taskId,detail});}).catch(()=>{if(live)setError(true);});return()=>{live=false;abort.abort();};},[session,taskId,ledgerApi,refresh]);
 const detail=loaded?.session===session&&loaded.taskId===taskId?loaded.detail:null;
 const deny=()=>{setLoaded(null);setError(true);setView('choose');};
 const maintain=(next:'customer'|'materials')=>{returnTo.current=view;setView(next);};
 async function direct(){if(!detail||locked)return;setLocked(true);try{const customer=await customerApi.context(session,detail.opportunity.id,new AbortController().signal);if(!session.isCurrent())return;if(customer.confirmation)setView('contract');else{returnTo.current='contract';setView('missing');}}catch{deny();}finally{setLocked(false);}}
 const backToOrigin=()=>setView(returnTo.current);
 if(!session.isCurrent())return null;
 if(detail&&view==='attempt')return <FollowupAttemptCard session={session} opportunityId={detail.opportunity.id} customerLabel={detail.customerLabel} ownerLabel={detail.ownerLabel} api={attemptsApi} sessionActions={sessionActions} onBack={onBack} onTasks={onTasks} onDenied={deny}/>;
 if(detail&&view==='quote')return <QuoteCard session={session} detail={detail} api={quotesApi} sessionActions={sessionActions} onBack={onBack} backLabel="返回当前跟进" onTasks={onTasks} onDenied={deny} onCustomer={()=>maintain('customer')} onMaterials={()=>maintain('materials')} onContract={()=>setView('contract')}/>;
 if(detail&&view==='customer')return <CustomerRequirementsCard session={session} detail={detail} api={customerApi} sessionActions={sessionActions} backLabel="返回原事项" continueLabel="返回原事项继续办理" onBack={backToOrigin} onContinue={async()=>backToOrigin()} onDenied={deny}/>;
 if(detail&&view==='materials')return <MaterialsCard session={session} detail={detail} api={materialsApi} sessionActions={sessionActions} backLabel="返回原事项" continueLabel="返回原事项继续办理" onBack={backToOrigin} onContinue={async()=>backToOrigin()} onDenied={deny}/>;
 if(detail&&view==='contract')return <ContractRuntimeCard session={session} opportunityId={detail.opportunity.id} api={contractsApi} sessionActions={sessionActions} onBack={onBack} onTasks={onTasks} onDenied={deny}/>;
 return <><header className="app-header"><div className="brand"><img className="icon" src={scales} alt=""/>律所工作助手</div><div className="session"><button className="mode-button link-button" disabled={locked} onClick={onBack}>返回当前跟进</button><span inert={locked}>{sessionActions??session.displayName}</span></div></header><main className="workbench" aria-label="商机后续办理"><div className="today"><p className="today-copy"><CheckCircle className="icon"/><span>依据实际业务进度，接着办理当前事项。</span></p><MyTasksControl disabled={locked} onClick={onTasks}/></div><article className="work-card"><div className="context"><p className="eyebrow">当前责任</p><h1>{view==='close'?'结束本次商机':'推进或结束本次商机'}</h1>{detail&&<><p className="subject">{detail.customerLabel}</p><p className="owner-line"><User className="icon"/>{detail.ownerLabel}负责</p></>}</div><section className="card-action">{!detail?<><h2>核对当前办理依据</h2><p role="status">{error?'当前责任或权限已变化，请返回工作台核对。':'正在读取当前商机…'}</p>{error&&<button className="primary" onClick={()=>setRefresh(n=>n+1)}>重新读取</button>}</>:view==='missing'?<><h2>先确认客户与需求</h2><dl className="detail-facts"><div><dt>尚缺依据</dt><dd>委托主体、服务需求的客户确认</dd></div><div><dt>当前责任</dt><dd>商机跟进仍由你负责</dd></div></dl><p>维护资料后回到本次准备；不自动生成合同。</p><div className="action-area"><button className="primary" onClick={()=>setView('customer')}>补齐客户与需求</button></div></>:view==='close'?<OpportunityClosure session={session} opportunityId={detail.opportunity.id} opportunityRevision={detail.opportunity.revision} taskState={detail.taskState} api={closureApi} blocked={false} onLock={setLocked} onRefresh={onBack} onDenied={deny}/>:<><h2>选择本次要办理的事项</h2><p>打开入口不会结束当前跟进。提交下一事项并成功接管后，才结束原责任。</p><div className="ledger-detail-actions"><button className="link-button" disabled={locked} onClick={()=>setView('quote')}>准备报价</button><button className="link-button" disabled={locked} onClick={()=>void direct()}>申请直接准备合同</button><button className="link-button" disabled={locked} onClick={()=>setView('close')}>结束本次商机</button></div></>}</section></article></main></>;
}
