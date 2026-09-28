import {useState,type ReactNode} from 'react';
import {TeamManagementPage} from './TeamManagementPage';
import type {TeamView} from './TeamLedger';
import type {TeamTransport} from '../../lib/teamTransport';
import type {WorkbenchSession} from '../../lib/sessionTransport';
import type {OwnerExceptionTransport} from '../../lib/ownerExceptionTransport';
import {OwnerExceptionPage} from '../ownerExceptions/OwnerExceptionPage';
import scales from '../opportunities/assets/Scales-green.svg';

/** The selected original exception remains in the existing recovery-aware flow. */
export function TeamManagementRoute(p:{session:WorkbenchSession;api:TeamTransport;ownerApi:OwnerExceptionTransport;sessionActions:ReactNode;onTask:(id:string)=>void;onTasks?:()=>void;onLeads?:()=>void;onOverview?:()=>void;onOpportunities?:()=>void;onContracts?:()=>void}){
 const [view,setView]=useState<TeamView>('tasks'),[exception,setException]=useState<string|null>(null);
 if(exception)return <OwnerExceptionPage session={p.session} api={p.ownerApi} sessionActions={p.sessionActions} initialExceptionId={exception} onReturn={()=>setException(null)}/>;
 return <><header className="app-header"><div className="brand"><img className="icon" src={scales} alt="" aria-hidden="true"/>律所工作助手</div><div className="session">{p.sessionActions}</div></header><TeamManagementPage session={p.session} api={p.api} view={view} onView={setView} onTask={p.onTask} onException={setException} onTasks={p.onTasks} onLeads={p.onLeads} onOverview={p.onOverview} onOpportunities={p.onOpportunities} onContracts={p.onContracts}/></>;
}
