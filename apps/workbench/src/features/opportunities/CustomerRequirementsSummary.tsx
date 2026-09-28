import {useEffect,useState} from 'react';
import type {WorkbenchSession} from '../../lib/api';
import type {CustomerContext,CustomerRequirementsTransport} from '../../lib/customerRequirementsTransport';
import {TransportError} from '../../lib/sessionTransport';
export function CustomerRequirementsSummary({session,id,api,blocked,onOpen,onDenied}:{session:WorkbenchSession;id:string;api:CustomerRequirementsTransport;blocked:boolean;onOpen:()=>void;onDenied:()=>void}){
 const [data,setData]=useState<CustomerContext|null>(null),[error,setError]=useState(false);
 useEffect(()=>{const c=new AbortController();setData(null);setError(false);api.context(session,id,c.signal).then(x=>{if(!c.signal.aborted&&session.isCurrent())setData(x);}).catch(e=>{if(c.signal.aborted)return;if(e instanceof TransportError&&[401,403,404].includes(e.status))onDenied();else setError(true);});return()=>c.abort();},[session,id,api]);
 if(!session.isCurrent())return null;
 return <details className="history"><summary>客户与需求资料</summary>{data?<><p>{data.confirmation?data.confirmation.document.matterName:'尚未确认客户与需求'}</p>{data.confirmation&&<p>{data.confirmation.document.serviceScope}</p>}{data.draft&&!data.draftConsumed&&<p>另有未确认草稿</p>}<button disabled={blocked} onClick={onOpen}>{data.editable&&!data.closed?'维护客户与需求':'查看客户与需求'}</button></>:<><p>{error?'资料读取失败':'正在读取资料…'}</p>{error&&<button disabled={blocked} onClick={onOpen}>重新读取客户与需求</button>}</>}</details>;
}
