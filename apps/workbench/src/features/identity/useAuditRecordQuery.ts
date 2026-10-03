import {useEffect,useState} from 'react';
import type {WorkbenchSession} from '../../lib/sessionTransport';
import type {AuditPage,AuditQuery,AuditRecordApi} from './auditRecordApi';
export function useAuditRecordQuery(session:WorkbenchSession,api:AuditRecordApi,query:AuditQuery){
 const identity=`${session.identityEpoch}:${session.actorScopeKey}:${session.selectedAppointmentId}`,filterKey=JSON.stringify(query),key=identity+filterKey;
 const [navigation,setNavigation]=useState<{key:string;cursors:(string|undefined)[];index:number;refresh:number}>({key,cursors:[undefined],index:0,refresh:0});
 const active=navigation.key===key?navigation:{key,cursors:[undefined],index:0,refresh:0};
 const requestKey=`${key}:${active.index}:${active.refresh}`;
 const [state,setState]=useState<{key:string;page?:AuditPage;error?:string}>({key:''});
 useEffect(()=>{if(navigation.key!==key)setNavigation(active);},[key,navigation.key]);
 useEffect(()=>{const abort=new AbortController();setState({key:requestKey});void api.list(session,{...query,limit:20,...(active.cursors[active.index]?{cursor:active.cursors[active.index]}:{})},abort.signal).then(page=>{if(!abort.signal.aborted&&session.isCurrent())setState({key:requestKey,page});},()=>{if(!abort.signal.aborted&&session.isCurrent())setState({key:requestKey,error:'审计记录暂时不可用，请重读后再试。'});});return()=>abort.abort();},[session,api,requestKey]);
 const current=state.key===requestKey?state:{key:requestKey};
 return {key:requestKey,page:current.page,error:current.error,loading:!current.page&&!current.error,canPrevious:active.index>0,canNext:!!current.page?.nextCursor,
  previous:()=>setNavigation({...active,index:Math.max(0,active.index-1)}),next:()=>{if(current.page?.nextCursor)setNavigation({...active,cursors:[...active.cursors.slice(0,active.index+1),current.page.nextCursor],index:active.index+1});},refresh:()=>setNavigation({key,cursors:[undefined],index:0,refresh:active.refresh+1})};
}
