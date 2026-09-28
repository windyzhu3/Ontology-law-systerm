import {useEffect,useRef,useState} from 'react';
import type {WorkbenchSession} from '../../lib/api';
import type {ContractsTransport} from '../../lib/contractsTransport';
export function ContractSummary({session,api,id,blocked,onOpen}:{session:WorkbenchSession;api:ContractsTransport;id:string;blocked:boolean;onOpen:()=>void}){
 const [loaded,setLoaded]=useState<{session:WorkbenchSession;id:string}|null>(null),[failed,setFailed]=useState(false);const current=useRef(session);current.current=session;
 useEffect(()=>{let live=true;const controller=new AbortController();setLoaded(null);setFailed(false);api.context(session,id,controller.signal).then(()=>{if(live&&session.isCurrent()&&current.current===session)setLoaded({session,id});}).catch(()=>{if(live)setFailed(true);});return()=>{live=false;controller.abort();};},[session,api,id]);
 if(!session.isCurrent())return null;
 return <details className="history"><summary>委托合同</summary><p>{failed?'当前无法读取有权合同事项。':loaded?'查看准确合同版本、准备来源及审查审批。':'正在核对合同查看权限…'}</p>{loaded?.session===session&&loaded.id===id&&<button className="link-button" disabled={blocked} onClick={onOpen}>查看与办理合同</button>}</details>;
}
