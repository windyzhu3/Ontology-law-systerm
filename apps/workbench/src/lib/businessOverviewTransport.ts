import {createSessionTransport,TransportError,type WorkbenchSession} from './sessionTransport';
export const overviewMetrics={
 leads:{label:'新增线索',basis:'本期首次接入的不同线索；重复导入不重复计数'},
 opportunities:{label:'有效商机',basis:'本期经有效联系形成的不同商机；后续终止不改写形成事实'},
 signedContracts:{label:'签署归档合同',basis:'本期首次完成必需签署核验与归档；版本不重复计数'},
 acceptedMatters:{label:'已接收案件',basis:'本期首次接收产生的唯一案件；分类更正不增加数量'},
 overdueTasks:{label:'当前逾期待办',basis:'截至查询时未完成且已过原期限；等待按原 SLA 计入'}
} as const;
export type OverviewMetric=keyof typeof overviewMetrics;
export type OverviewSummary={month:string;asOf:string;metrics:({key:OverviewMetric;label:string}&({status:'AVAILABLE';count:number}|{status:'FORBIDDEN';count:null}))[]};
export type OverviewPage={month:string;asOf:string;metric:OverviewMetric;items:{id:string;customerLabel:string;occurredAt:string;stateLabel:string}[];nextCursor:string|null};
export type BusinessOverviewTransport={summary:(s:WorkbenchSession,month:string|undefined,signal:AbortSignal)=>Promise<OverviewSummary>;details:(s:WorkbenchSession,metric:OverviewMetric,month:string,cursor:string|undefined,signal:AbortSignal)=>Promise<OverviewPage>};
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v);
const text=(v:unknown):v is string=>typeof v==='string';
const exact=(v:Record<string,unknown>,keys:string[])=>Object.keys(v).sort().join()===keys.sort().join();
const month=(v:unknown):v is string=>text(v)&&/^\d{4}-(0[1-9]|1[0-2])$/.test(v);
const instant=(v:unknown)=>text(v)&&/^\d{4}-\d{2}-\d{2}T/.test(v)&&!Number.isNaN(Date.parse(v));
const metric=(v:unknown):v is OverviewMetric=>text(v)&&Object.hasOwn(overviewMetrics,v);
export function createBusinessOverviewTransport(fetcher:typeof fetch=fetch):BusinessOverviewTransport {
 const {auth,assertCurrent,checked}=createSessionTransport(false);
 async function read(s:WorkbenchSession,path:string,signal:AbortSignal){const headers=await auth(s,signal);const response=await fetcher(path,{method:'GET',headers,signal,cache:'no-store'});assertCurrent(s,signal);const data:unknown=await response.json();assertCurrent(s,signal);if(response.status===403)throw new TransportError(403,'NOT_AUTHORIZED');checked(s,signal,{response,data,error:data});return data;}
 return {
  async summary(s,period,signal){
   if(period!==undefined&&!month(period))throw Error('业务期间无法核对');const d=await read(s,'/api/v1/business-overview'+(period?'?'+new URLSearchParams({month:period}):''),signal);
   if(!object(d)||!exact(d,['month','asOf','metrics'])||!month(d.month)||period!==undefined&&d.month!==period||!instant(d.asOf)||!Array.isArray(d.metrics)||d.metrics.length!==5||!d.metrics.every(v=>object(v)&&exact(v,['key','label','status','count'])&&metric(v.key)&&v.label===overviewMetrics[v.key].label&&(v.status==='FORBIDDEN'?v.count===null:v.status==='AVAILABLE'&&Number.isSafeInteger(v.count)&&Number(v.count)>=0&&Number(v.count)<=5000))||new Set(d.metrics.map(v=>v.key)).size!==5)throw Error('概览数字无法核对');return d as OverviewSummary;
  },
  async details(s,key,period,cursor,signal){
   if(!metric(key)||!month(period))throw Error('指标或业务期间无法核对');const q=new URLSearchParams({month:period,limit:'20'});if(cursor)q.set('cursor',cursor);const d=await read(s,'/api/v1/business-overview/'+key+'?'+q,signal);
   if(!object(d)||!exact(d,['month','asOf','metric','items','nextCursor'])||d.month!==period||d.metric!==key||!instant(d.asOf)||!Array.isArray(d.items)||d.items.length>20||!d.items.every(v=>object(v)&&exact(v,['id','customerLabel','occurredAt','stateLabel'])&&text(v.id)&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v.id)&&text(v.customerLabel)&&instant(v.occurredAt)&&text(v.stateLabel))||new Set(d.items.map(v=>v.id)).size!==d.items.length||!(d.nextCursor===null||text(d.nextCursor)&&d.nextCursor.length<=512))throw Error('概览明细无法核对');return d as OverviewPage;
  }
 };
}
