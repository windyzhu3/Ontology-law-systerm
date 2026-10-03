import {useContext,useEffect,useRef,useState,type ReactNode} from 'react';
import type {WorkbenchSession} from '../../lib/api';
import type {TransfersTransport} from '../../lib/transfersTransport';
import {BusinessNavigation,BusinessNavigationContext} from '../workcard/BusinessNavigation';
import {ClassificationCorrectionCard} from '../transfers/ClassificationCorrectionCard';
import {IdentityDialog} from '../identity/IdentityActionConfirmation';
import scales from '../opportunities/assets/Scales-green.svg';

/** Standalone management entry reuses the original correction command and recovery. */
export function LedgerClassificationCorrection({session,opportunityId,api,onBack,sessionActions}:{session:WorkbenchSession;opportunityId:string;api:TransfersTransport;onBack:()=>void;sessionActions?:ReactNode}){
 const {registerLeaveGuard}=useContext(BusinessNavigationContext);
 const [dirty,setDirty]=useState(false),[locked,setLocked]=useState(false),[error,setError]=useState(''),[discard,setDiscard]=useState<{next:()=>void;trigger:HTMLElement|null}|null>(null);
 const leave=useRef((next:()=>void)=>next());
 leave.current=next=>{if(locked||api.recovery.read()){setError('本次更正结果尚未确认，请先核对原回执。');return;}if(dirty)setDiscard({next,trigger:document.activeElement as HTMLElement|null});else next();};
 useEffect(()=>{registerLeaveGuard?.(next=>leave.current(next));return()=>registerLeaveGuard?.(null);},[registerLeaveGuard]);
 useEffect(()=>{const warn=(e:BeforeUnloadEvent)=>{if(dirty||locked||api.recovery.read()){e.preventDefault();e.returnValue='';}};window.addEventListener('beforeunload',warn);return()=>window.removeEventListener('beforeunload',warn);},[dirty,locked,api]);
 return <><div inert={!!discard}><header className="app-header"><div className="brand"><img className="icon" src={scales} alt=""/>律所工作助手</div><div className="session">{sessionActions??session.displayName}</div></header><div className="admin-shell"><BusinessNavigation active="contracts" disabled={locked} onNavigate={next=>leave.current(next)}/><main className="admin-main">{error&&<p role="alert">{error}</p>}<ClassificationCorrectionCard session={session} opportunityId={opportunityId} api={api} onDirtyChange={setDirty} onLockedChange={setLocked} onBack={onBack}/></main></div></div>{discard&&<IdentityDialog title="舍弃未保存的修改？" onCancel={()=>setDiscard(null)} locked={false} returnFocus={discard.trigger}><p>离开会舍弃本次尚未提交的更正说明。</p><button onClick={()=>setDiscard(null)}>继续编辑</button><button onClick={()=>{const next=discard.next;setDiscard(null);setDirty(false);next();}}>舍弃并离开</button></IdentityDialog>}</>;
}
