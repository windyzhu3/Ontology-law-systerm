import {useState} from 'react';
import {minorText} from '../../lib/quotesTransport';
import type {ContractCommand,ContractContext,ContractExecutionContext} from './types';

export const executionLabels:Record<ContractExecutionContext['workflow']['stage'],string>={CHECK_CONDITIONS:'核对合同执行条件',WAIT_RECEIPT:'等待约定首款到账',READY_TRANSFER:'合同已具备转案条件',OWNER_EXCEPTION:'执行条件责任安排待处理'};

/** Confirmed P composition; uses the existing card, checkbox and action styles. */
export function ContractExecution({context,allowed,submit,onDirty,onDocument}:{context:ContractContext;allowed:(command:ContractCommand)=>boolean;submit:(command:ContractCommand,values:Record<string,unknown>)=>Promise<boolean>;onDirty:()=>void;onDocument?:()=>void}){
 const [conditions,setConditions]=useState(false),[archive,setArchive]=useState(false);
 const execution=context.execution;if(!execution)return null;
 const stage=execution.workflow.stage,gate=context.contract?.document.paymentGate;
 const canConfirm=stage==='CHECK_CONDITIONS'&&allowed('VERIFY_CONTRACT_EXECUTION_CONDITIONS');
 return <>
  <h2>{stage==='CHECK_CONDITIONS'?'确认已满足本版执行条件':stage==='WAIT_RECEIPT'?'首款条件尚未满足':stage==='READY_TRANSFER'?'执行条件已确认，下一步准备转案':'正在等待合格责任人'}</h2>
  <dl className="detail-facts"><div><dt>合同版本</dt><dd>第 {context.contract?.version} 版 · 签署已核验并归档</dd></div><div><dt>付款约定</dt><dd>{gate?.receiptRequiredBeforeTransfer?'转案前须确认到账 ¥'+minorText(gate.requiredMinor??0):'签署后按约付款，不以前置到账阻断转案'}</dd></div></dl>
  {canConfirm&&<form onSubmit={e=>{e.preventDefault();if(conditions&&archive)void submit('VERIFY_CONTRACT_EXECUTION_CONDITIONS',{expectedExecutionWorkflow:execution.workflow.selector,approvedConditionsChecked:true,archiveAndConditionsComplete:true});}}>
   <label className="checked-label contract-checkbox"><input type="checkbox" required checked={conditions} onChange={e=>{setConditions(e.target.checked);onDirty();}}/>已对照批准正文核对生效日期及其他约定条件</label>
   <label className="checked-label contract-checkbox"><input type="checkbox" required checked={archive} onChange={e=>{setArchive(e.target.checked);onDirty();}}/>本版签署归档完整，当前没有未满足的执行条件</label>
   <div className="ledger-detail-actions"><button className="primary" type="submit">确认执行条件成立</button></div>
  </form>}
  <p>{stage==='WAIT_RECEIPT'?'核实足额后，本事项继续核对其他执行条件。上传凭证不等于到账。':stage==='READY_TRANSFER'?'案管审核接收后才生成案件，再进行业务分类。':execution.workflow.message}</p>
  {onDocument&&<button className="link-button" type="button" onClick={onDocument}>查看合同及付款约定</button>}
 </>;
}
