import React from 'react';
import {createRoot} from 'react-dom/client';
import {App} from '../../apps/workbench/src/App';
import {createWorkbenchApi} from '../../apps/workbench/src/lib/api';
import {parseEnvelope,etag} from '../../apps/workbench/src/features/workcard/contract';
declare global {interface Window {__t03Actor?:{identityEpoch:number;actorScopeKey:string;selectedAppointmentId:string;selectedOnBehalfAppointmentId:null;displayName?:string};__t03Diagnostic?:unknown}}
const actor=window.__t03Actor;if(!actor)throw Error('T03 isolated actor missing');
const session={...actor,canReadOpportunityLedger:true,canEnterWorkbench:true,getValidAccessToken:async()=> 't03-isolated-transport',isCurrent:()=>true,invalidate:()=>{}};
const api=createWorkbenchApi();const read=api.current;api.current=async(...args)=>{const response=await read(...args);try{parseEnvelope(response.data);window.__t03Diagnostic={envelopeValid:true,etagValid:etag(response.etag,'wb')};}catch(error){window.__t03Diagnostic={envelopeValid:false,etagValid:etag(response.etag,'wb'),contractLocations:error instanceof Error?error.stack?.split('\n').filter(line=>line.includes('/contract.ts')).map(line=>line.match(/contract.ts:\d+:\d+/)?.[0]):[]};}return response;};
history.replaceState({},'', '/management/opportunities');
createRoot(document.getElementById('root')!).render(<App session={session} api={api}/>);
