import React from 'react';
import {createRoot} from 'react-dom/client';
import {OwnerExceptionPage} from '../../apps/workbench/src/features/ownerExceptions/OwnerExceptionPage';
import {createOwnerExceptionTransport} from '../../apps/workbench/src/lib/ownerExceptionTransport';
import '../../apps/workbench/src/styles/tokens.css';
import '../../apps/workbench/src/styles/workbench.css';

declare global {interface Window {__t01Actor?:{identityEpoch:number;actorScopeKey:string;selectedAppointmentId:string;selectedOnBehalfAppointmentId:null;displayName?:string}}}
// Test-only bootstrap. The bearer stays in the Node process; every API response is real HTTP.
const actor=window.__t01Actor;
if(!actor)throw Error('T01 live browser actor missing');
const session={...actor,getValidAccessToken:async()=> 't01-live-test-transport',isCurrent:()=>true,invalidate:()=>{}};
createRoot(document.getElementById('root')!).render(<OwnerExceptionPage session={session} api={createOwnerExceptionTransport()} sessionActions={<span>{actor.displayName||'真实HTTP测试任职'} / 管理模式</span>}/>);
