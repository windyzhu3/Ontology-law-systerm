import type {ContractContext} from './types';
/** The user's selected duty wins over parallel sales/finance branches, including after completion. */
export function selectContractPayment(context:ContractContext,taskId?:string):ContractContext {
 if(context.payments===undefined&&context.selectedPayment===undefined)return context;
 return {...context,selectedPayment:taskId?context.payments?.find(payment=>payment.taskIds.includes(taskId))??null:context.allowedActions.length===0?context.payments?.find(payment=>payment.allowedActions.length>0)??null:null};
}
