import {useEffect,useId,useRef,useState} from 'react';
import {aiLabels,createAiCandidateTransport,type AiCandidate,type AiCandidateTransport,type AiField,type AiTarget,type AiTask} from '../../lib/aiCandidateTransport';
import {TransportError,type WorkbenchSession} from '../../lib/sessionTransport';
const defaultApi=createAiCandidateTransport();
const titles:Record<AiTask,string>={FIELDS:'字段建议',SUMMARY:'跟进摘要',MATERIALS:'材料缺项提示'};
type Props={session:WorkbenchSession;target:AiTarget;task:AiTask;sourceVersion:string;draftVersion:string;disabled?:boolean;api?:AiCandidateTransport;onApply?:(field:AiField,value:string)=>void};
export function AiCandidatePanel(props:Props){
 const {session,target,task,sourceVersion,disabled=false,api=defaultApi}=props,id=useId();
 const [candidate,setCandidate]=useState<AiCandidate|null>(null),[edits,setEdits]=useState<Partial<Record<AiField,string>>>({}),[busy,setBusy]=useState(false),[stale,setStale]=useState(false),[message,setMessage]=useState('');
 const current=useRef(props);current.current=props;const operation=useRef<AbortController|null>(null),serial=useRef(0),basis=useRef('');
 const targetKey=JSON.stringify(target),identityKey=session.actorScopeKey+':'+session.identityEpoch;
 useEffect(()=>{++serial.current;operation.current?.abort();setCandidate(null);setEdits({});setStale(false);setBusy(false);setMessage('');return()=>{++serial.current;operation.current?.abort();};},[session,identityKey,targetKey,task]);
 useEffect(()=>{if(candidate&&basis.current!==sourceVersion){operation.current?.abort();++serial.current;setBusy(false);setStale(true);setMessage('来源已更新，本次建议不能直接采纳。请重新生成并核对。');}},[sourceVersion,candidate]);
 function start(){operation.current?.abort();const control=new AbortController();operation.current=control;const ticket=++serial.current;setBusy(true);return {control,ticket,timer:window.setTimeout(()=>control.abort(),55000)};}
 function valid(ticket:number,control:AbortController){return ticket===serial.current&&!control.signal.aborted&&current.current.session===session&&session.isCurrent()&&JSON.stringify(current.current.target)===targetKey&&current.current.task===task;}
 function fail(error:unknown){
  if(error instanceof TransportError&&[401,403,404].includes(error.status)){setCandidate(null);setEdits({});setMessage('当前依据不可查看，建议已清除。原表单仍按现有权限办理。');}
  else if(error instanceof TransportError&&[409,412].includes(error.status)){setStale(true);setMessage('来源已更新，本次建议不能直接采纳。请重新生成并核对。');}
  else setMessage('暂时无法生成或核对建议。已填写内容保留，可以继续手工办理。');
 }
 async function generate(){
  if(disabled||busy||!session.isCurrent())return;const op=start();setCandidate(null);setEdits({});setStale(false);setMessage('');const version=sourceVersion;
  try{const result=await api.generate(session,target,task,op.control.signal);if(!valid(op.ticket,op.control))return;basis.current=version;setCandidate(result);setEdits(Object.fromEntries(result.items.filter(x=>x.status==='CANDIDATE').map(x=>[x.field,x.value??''])));if(current.current.sourceVersion!==version)setStale(true);}
  catch(error){if(op.ticket===serial.current&&current.current.session===session&&session.isCurrent())fail(error);}
  finally{clearTimeout(op.timer);if(op.ticket===serial.current)setBusy(false);}
 }
 async function adopt(field:AiField){
  if(!candidate||disabled||busy||stale||!session.isCurrent())return;const value=edits[field]??'';if(task!=='MATERIALS'&&!value.trim())return;
  const op=start(),draftVersion=current.current.draftVersion,version=current.current.sourceVersion;
  try{
   await api.recheck(session,target,candidate,op.control.signal);if(!valid(op.ticket,op.control)||current.current.disabled)return;
   if(current.current.sourceVersion!==version){setStale(true);setMessage('来源已更新，请重新生成并核对。');return;}
   if(current.current.draftVersion!==draftVersion){setMessage('原表单已修改，本次未覆盖。请重新核对后再采纳。');return;}
   if(task==='MATERIALS')setMessage('已人工核对这条提示，材料状态保持不变；请按原材料流程补充或核验。');
   else{current.current.onApply?.(field,value);setMessage('已放入原表单，尚未确认提交。请继续人工核对。');}
  }catch(error){if(op.ticket===serial.current&&current.current.session===session&&session.isCurrent())fail(error);}
  finally{clearTimeout(op.timer);if(op.ticket===serial.current)setBusy(false);}
 }
 function ignore(){++serial.current;operation.current?.abort();setCandidate(null);setEdits({});setStale(false);setBusy(false);setMessage('已忽略本次建议，原表单保持不变。');}
 if(!session.isCurrent()||session.selectedOnBehalfAppointmentId!==null)return null;
 return <section className="compact-confirm" aria-label={'AI '+titles[task]}><h3>AI {titles[task]}</h3><p className="help">建议待人工确认，可修改或忽略；原有手工流程始终可用。</p>
  <button type="button" disabled={disabled||busy} onClick={()=>void generate()}>{busy?'正在核对…':stale?'重新生成并核对':'生成'+titles[task]}</button>
  {candidate&&<><details className="history"><summary>查看本次依据</summary>{candidate.sources.map(s=><div key={s.id}><strong>{s.label}</strong><p style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>{s.text}</p></div>)}</details>
   {candidate.items.map(item=><div className="form-field" key={item.field}>
    {item.status==='CANDIDATE'?<><label htmlFor={id+'-'+item.field}>候选{aiLabels[item.field]}</label><textarea id={id+'-'+item.field} value={edits[item.field]??''} maxLength={item.field==='contactPhone'?50:['contactName','customerName'].includes(item.field)?200:2000} disabled={disabled||busy||stale} onChange={e=>setEdits(v=>({...v,[item.field]:e.target.value}))}/></>:<p>{aiLabels[item.field]}：{item.status==='MISSING'?'未找到足够依据，请人工补充':'依据有冲突，请人工核对'}</p>}
    <ul>{item.citations.map((c,i)=><li key={i}><span>{candidate.sources.find(s=>s.id===c.sourceId)?.label}：</span><q>{c.quote}</q></li>)}</ul>
    {(item.status==='CANDIDATE'||task==='MATERIALS')&&(item.field==='customerName'?<p className="help">请在原表单查找并核实准确主体，名称不自动建立或选择委托方。</p>:<button type="button" disabled={disabled||busy||stale||(task!=='MATERIALS'&&!edits[item.field]?.trim())} onClick={()=>void adopt(item.field)}>{task==='MATERIALS'?'已核对'+aiLabels[item.field]+'提示':'采用'+aiLabels[item.field]+'到原表单'}</button>)}
   </div>)}<button type="button" disabled={disabled} onClick={ignore}>忽略建议</button></>}
  {message&&<p role={stale?'alert':'status'}>{message}</p>}{task==='SUMMARY'&&<p className="help">历史摘要仅作草稿，请结合本次真实进展修改；进展类型与后续时间仍需本人填写。</p>}
 </section>;
}
