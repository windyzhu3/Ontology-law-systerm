import type {ContractContext,ContractSelector} from './types';
/** A pending signature responsibility must not reopen the completed preparation task. */
export function currentContractTask(context:ContractContext):ContractSelector|null {return context.selectedPayment?context.selectedPayment.task:context.transfer?(context.transfer.canHandle?context.transfer.task:null):context.execution?context.execution.workflow.task:context.signature?context.signature.workflow.task:context.workflow?.task??null;}
