import {it,expect} from 'vitest';
import {selectContractPayment} from './paymentSelection';
import {currentContractTask} from './currentContractTask';
import type {ContractContext} from './types';
const finance={selector:{id:'finance',revision:0},request:{id:'request',revision:0},stage:'COMPLETE',targetStage:'COMPLETE',task:null,taskIds:['old-finance-task'],ownerAppointmentId:null,ownerLabel:'财务',dueAt:'2026-09-27T09:00:00Z',accountLabel:'基本户',explanation:null,allowedActions:[]};
const context={payments:[finance],execution:{workflow:{task:{id:'sales-execution',revision:1}}}} as unknown as ContractContext;
it('a completed selected finance duty never falls back to the parallel sales execution card',()=>{const selected=selectContractPayment(context,'old-finance-task');expect(selected.selectedPayment?.selector.id).toBe('finance');expect(currentContractTask(selected)).toBeNull();});
it('selecting the sales task does not select an unrelated finance chain',()=>{const selected=selectContractPayment(context,'sales-execution');expect(selected.selectedPayment).toBeNull();expect(currentContractTask(selected)?.id).toBe('sales-execution');});

it('ledger entry selects an authorized finance duty when sales actions are absent',()=>{const selected=selectContractPayment({...context,allowedActions:[],payments:[{...finance,stage:'CHECK_RECEIPT',targetStage:'CHECK_RECEIPT',allowedActions:['RECORD_CONTRACT_RECEIPT_REVIEW']}] as ContractContext['payments']});expect(selected.selectedPayment?.selector.id).toBe('finance');});
it('ledger entry preserves available sales work alongside finance',()=>{const selected=selectContractPayment({...context,allowedActions:['VERIFY_CONTRACT_EXECUTION_CONDITIONS'],payments:[{...finance,stage:'CHECK_RECEIPT',targetStage:'CHECK_RECEIPT',allowedActions:['RECORD_CONTRACT_RECEIPT_REVIEW']}] as ContractContext['payments']});expect(selected.selectedPayment).toBeNull();});
