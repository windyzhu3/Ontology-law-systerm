import type {TransferPreparationInput} from './TransferPreparation';
import type {TransferCorrectionInput} from './TransferCorrection';
import type {MatterClassificationInput} from './MatterClassification';
export type TransferReturnRequirement={id:string;requirement:string};
/** Preserve the exact human-confirmed base; the server revalidates every material and business basis. */
export function correctionPayload(basis:TransferPreparationInput,items:TransferReturnRequirement[],input:TransferCorrectionInput){
 const ids=new Set(input.corrections.map(c=>c.returnItemId));
 if(!items.length||ids.size!==input.corrections.length||items.length!==ids.size||items.some(i=>!ids.has(i.id)))throw new Error('请重新读取本次完整退回项目。');
 const result={...basis,corrections:input.corrections};
 for(const item of items){const correction=input.corrections.find(c=>c.returnItemId===item.id)!;
  if(item.requirement!=='HANDOVER_EXPLANATION'&&!correction.material)throw new Error('请补充本次退回项目要求的材料。');
  if(item.requirement==='CLIENT_IDENTITY')result.clientIdentity=correction.material!;
  if(item.requirement==='SIGNATURE_ARCHIVE')result.signatureArchive=correction.material!;
  if(item.requirement==='HANDOVER_EXPLANATION')result.explanation=correction.response;
 }
 return result;
}
export const classificationPayload=(input:MatterClassificationInput)=>({matterId:input.matterId,category:input.classification,recipient:input.receivingAppointmentId,explanation:input.explanation});
