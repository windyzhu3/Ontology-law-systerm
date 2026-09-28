import {useRef,useState} from 'react';

/** The owning card supplies exact selectors, authority and original-receipt recovery.
 * True means confirmed success; false means a known rejection; throw means uncertain. */
export function useTransferSubmission<T>(submit:(input:T)=>Promise<boolean>,disabled:boolean){
 const active=useRef(false),settled=useRef(false);
 const [locked,setLocked]=useState(false),[error,setError]=useState('');
 async function send(build:()=>T){
  if(disabled||active.current||settled.current)return;
  let input:T;try{input=build();}catch(e){setError(e instanceof Error?e.message:'请检查本次办理信息');return;}
  active.current=true;setLocked(true);setError('');
  try{settled.current=await submit(input);}
  catch{settled.current=true;setError('提交结果尚未确认，请通过当前工作卡核对原提交结果。');}
  finally{active.current=false;setLocked(settled.current);}
 }
 return {send,locked:disabled||locked,error};
}

export function transferText(value:FormDataEntryValue|null):string{
 const text=typeof value==='string'?value.trim():'';
 if(!text||[...text].length>4000)throw new Error('请填写处理说明，最多 4000 字。');
 return text;
}
