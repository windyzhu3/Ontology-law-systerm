import {ListChecks} from '@phosphor-icons/react/ListChecks';
import '../../styles/workbenchControls.css';
export function MyTasksControl({summary=false,count,disabled,onClick}:{summary?:boolean;count?:number;disabled?:boolean;onClick?:()=>void}) {
 const content=<><ListChecks size={20} aria-hidden="true"/><span>我的待办{count===undefined?'':`（${count}）`}</span></>;
 return summary?<summary className="workbench-control">{content}</summary>:<button type="button" className="workbench-control" disabled={disabled} onClick={onClick}>{content}</button>;
}
