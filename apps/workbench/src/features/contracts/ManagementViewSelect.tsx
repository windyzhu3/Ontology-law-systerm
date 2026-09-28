import type {ManagementView} from './ManagementLedger';
/** R frozen shared selector for all three contract-management views. */
export function ManagementViewSelect({value,onChange,views=['contracts','payments','transfer']}:{views?:readonly (ManagementView|'contracts')[];value:ManagementView|'contracts';onChange:(view:ManagementView|'contracts')=>void}){
 return <label className="field"><span className="field-label">查看内容</span><select aria-label="查看内容" value={value} onChange={e=>onChange(e.target.value as ManagementView|'contracts')}>{views.map(view=><option key={view} value={view}>{view==='contracts'?'合同':view==='payments'?'收款':'转案与案件'}</option>)}</select></label>;
}
