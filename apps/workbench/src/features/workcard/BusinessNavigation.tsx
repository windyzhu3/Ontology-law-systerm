import {createContext,useContext} from 'react';
import {Users} from '@phosphor-icons/react/Users';
import {ChartBar} from '@phosphor-icons/react/ChartBar';
import {TreeStructure} from '@phosphor-icons/react/TreeStructure';
import {Briefcase} from '@phosphor-icons/react/Briefcase';
import {FileText} from '@phosphor-icons/react/FileText';
import {UsersThree} from '@phosphor-icons/react/UsersThree';
import './businessNavigation.css';
import {MyTasksControl} from './MyTasksControl';
export type BusinessNavigationActions={onOpportunities?:()=>void;onContracts?:()=>void;onTeam?:()=>void;onLeads?:()=>void;onOverview?:()=>void;onSources?:()=>void;onTasks?:()=>void};
// Session admission supplies the same authorized destinations to every business page.
export const BusinessNavigationContext=createContext<BusinessNavigationActions & {registerLeaveGuard?:(guard:((next:()=>void)=>void)|null)=>void}>({});
export function BusinessNavigation(props:BusinessNavigationActions & {active:'opportunities'|'contracts'|'team'|'leads'|'sources'|'overview';disabled?:boolean;onNavigate?:(next:()=>void)=>void}) {
 const shared=useContext(BusinessNavigationContext);
 const {active,disabled=false,onNavigate}=props;
 const entries=[
  ['leads','客户与线索',Users,props.onLeads??shared.onLeads],
  ['overview','经营概览',ChartBar,props.onOverview??shared.onOverview],
  ['sources','来源与责任',TreeStructure,props.onSources??shared.onSources],
  ['opportunities','商机台账',Briefcase,props.onOpportunities??shared.onOpportunities],
  ['contracts','合同台账',FileText,props.onContracts??shared.onContracts],
  ['team','团队待办',UsersThree,props.onTeam??shared.onTeam],
 ] as const;
 const visit=(next:(()=>void)|undefined)=>{if(!disabled&&next){if(onNavigate)onNavigate(next);else next();}};
 const tasks=props.onTasks??shared.onTasks;
 return <aside className="sidebar business-navigation" aria-label="业务管理"><p>业务管理</p>{entries.map(([key,label,Icon,next])=>(next||active===key)&&<button key={key} type="button" className={active===key?'active':undefined} aria-current={active===key?'page':undefined} disabled={disabled} onClick={()=>visit(next)}><Icon className="icon" size={20} aria-hidden="true"/><span>{label}</span></button>)}{tasks&&<MyTasksControl disabled={disabled} onClick={()=>visit(tasks)}/>}</aside>;
}
