import {useEffect,useState} from 'react';
import type {WorkbenchSession} from '../../lib/api';
import type {MaterialContext,MaterialsTransport} from '../../lib/materialsTransport';
import {TransportError} from '../../lib/sessionTransport';
export function MaterialsSummary({session,id,api,blocked,onOpen,onDenied}:{session:WorkbenchSession;id:string;api:MaterialsTransport;blocked:boolean;onOpen:()=>void;onDenied:()=>void}){
 const [data,setData]=useState<MaterialContext|null>(null),[error,setError]=useState(false);
 useEffect(()=>{const c=new AbortController();setData(null);setError(false);api.context(session,id,c.signal).then(x=>{if(!c.signal.aborted&&session.isCurrent())setData(x);}).catch(e=>{if(c.signal.aborted)return;if(e instanceof TransportError&&[401,403,404].includes(e.status))onDenied();else setError(true);});return()=>c.abort();},[session,id,api]);
 if(!session.isCurrent())return null;
 const count=data?new Set(data.versions.map(v=>v.itemId)).size:0;
 return <details className="history"><summary>业务材料</summary>{data?<><p>已收 {count} 份{data.pendingUploads.length?' · 另有待核对上传':''}</p><button disabled={blocked} onClick={onOpen}>{data.editable&&!data.closed?'查看与接收材料':'查看业务材料'}</button></>:<><p>{error?'材料读取失败':'正在读取材料…'}</p>{error&&<button disabled={blocked} onClick={onOpen}>重新读取业务材料</button>}</>}</details>;
}
