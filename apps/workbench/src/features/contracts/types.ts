export type ContractSelector = {id:string;revision:number};
export type GeneratedContract = {pdfBase64:string;previewText:string;bodySha256:string;generationProof:string;expiresAt:string};
export type ContractCommand = 'REQUEST_CONTRACT_RECEIPT_REVIEW'|'RECORD_CONTRACT_RECEIPT_REVIEW'|'SUPPLEMENT_CONTRACT_RECEIPT'|'VERIFY_CONTRACT_EXECUTION_CONDITIONS'|'END_CONTRACT_NEGOTIATION'|'REQUEST_CONTRACT_TERMINATION_REVIEW'|'RECORD_CONTRACT_TERMINATION_REVIEW'|'SAVE_CONTRACT_SIGNATURE_DRAFT'|'CONFIRM_CONTRACT_SIGNATURE_ARRANGEMENT'|'SUBMIT_CONTRACT_SIGNATURE'|'RECORD_CONTRACT_SIGNATURE_VERIFICATION'|'ARCHIVE_CONTRACT_SIGNATURE'|'RETURN_CONTRACT_SIGNATURE_FOR_REVISION'|'RETURN_CONTRACT_FOR_REVISION'|'REQUEST_CONTRACT_PREPARATION'|'RECORD_CONTRACT_PREPARATION_DECISION'|'START_CONTRACT_PREPARATION'|'SAVE_CONTRACT_DRAFT'|'FORM_CONTRACT'|'REQUEST_CONTRACT_REVIEW'|'RECORD_CONTRACT_REVIEW'|'REQUEST_CONTRACT_APPROVAL'|'RECORD_CONTRACT_DECISION';
export type ContractCommercial = {currency:'CNY';scope:string;lines:{description:string;amountMinor:number;discount:boolean}[];conditionalFee:null|{basis:string;rateBasisPoints:number;capMinor:number};paymentTerms:string};
export type ContractDocument = {commercial:ContractCommercial;document:{evidenceVersionId:string;bodySha256:string;templateVersionId:string;clauseVersionIds:string[]};signing:{partySnapshotDigest:string;requirements:string};paymentGate:{receiptRequiredBeforeTransfer:boolean;requiredMinor:number|null}};
export type ContractStage = 'DIRECT_REQUEST'|'DIRECT_REVIEW'|'DIRECT_RETURNED'|'PREPARE'|'RETURNED'|'SUBMIT_REVIEW'|'AWAIT_REVIEW'|'REVIEW_SUPPLEMENT'|'REVIEW_BLOCKED'|'SUBMIT_APPROVAL'|'AWAIT_APPROVAL'|'READY_FOR_SIGNATURE'|'AWAITING_NEXT_STAGE'|'OWNER_EXCEPTION';
export type ContractVersion = {selector:ContractSelector;currentRevision:{id:string;hash:string}|null;version:number;document:Partial<ContractDocument>&{commercial:ContractCommercial};source:({kind:'ACCEPTED_QUOTE';selector:{id:string;hash:string}}|{kind:'DIRECT_AUTHORIZATION';selector:ContractSelector});stage:ContractStage};
export type ContractTerminationContext={selector:ContractSelector;state:'STOPPED'|'REVIEW_REQUIRED'|'CONTINUED';summary:string;occurredAt:string;task:ContractSelector|null;ownerAppointmentId:string|null;ownerLabel:string;dueAt:string|null;resumeAvailable?:boolean;independentBasis?:string;independentState?:{paymentRecorded:boolean;executionRecorded:boolean;transferRecorded:boolean};history:{selector:ContractSelector;kind:'STOP_UNSIGNED'|'REQUEST_REVIEW'|'STOP_REVIEWED'|'CONTINUE';summary:string;occurredAt:string}[]};
export type ContractPaymentContext={requiredMinor?:number|null;confirmedMinor?:number;remainingMinor?:number|null;selector:ContractSelector;request:ContractSelector;stage:"CHECK_RECEIPT"|"SUPPLEMENT_RECEIPT"|"COMPLETE"|"OWNER_EXCEPTION";targetStage:"CHECK_RECEIPT"|"SUPPLEMENT_RECEIPT"|"COMPLETE";task:ContractSelector|null;taskIds:string[];ownerAppointmentId:string|null;ownerLabel:string;dueAt:string;accountLabel:string|null;explanation:string|null;allowedActions:ContractCommand[]};
export type ContractContext = { transfer?:{stage:string;task:ContractSelector|null;canHandle:boolean;canCorrectClassification?:boolean}; payments?:ContractPaymentContext[];selectedPayment?:ContractPaymentContext|null; execution?:ContractExecutionContext|null; termination?:ContractTerminationContext|null; signatureHistory?:NonNullable<SignatureContext['historyEntries']>; signature?:SignatureContext|null; signatureUploads?:{id:string;label:string;state:string}[];
 opportunity:ContractSelector;responsibilityBasis:ContractSelector|null;customerConfirmation:ContractSelector|null;
 customerName:string;readonly:boolean;contract:ContractVersion|null;draft:{selector:ContractSelector;document:Partial<ContractDocument>}|null;
 workflow:{selector:ContractSelector;stage:ContractStage;task:ContractSelector|null;ownerAppointmentId:string|null;ownerLabel?:string;dueAt?:string|null;message?:string|null}|null;
 preparation?:{selector:ContractSelector;commercial:ContractCommercial;reason:string}|null;
 reviewPreview?:{scopeComplete:boolean;candidateCount:number;permittedOutcomes:('CLEAR'|'NEED_INFO'|'BLOCKED')[]};
 review:{status:string;summary:string;scopeHash:string;selector:ContractSelector}|null;
 approvals:{slot:string;status:string;selector:ContractSelector}[];
 allowedActions:ContractCommand[];blockers:{code:string;message:string}[];
 history:{id:string;label:string;summary:string;occurredAt:string}[];
 receiptBoundary:{state:string;source:string}|null;
 templates?:{id:string;label:string;clauseVersionIds:string[]}[];
 documents?:{id:string;label:string;bodySha256:string}[];
 partySnapshotDigest?:string|null;
};
/** Transport confirms receipts and reloads context before resolving. Unknown outcomes never resolve as committed. */
export type ContractActionResult = {status:'COMMITTED'|'PENDING'|'UNKNOWN';context?:ContractContext;message?:string};
export type ContractCardProps = {
 context:ContractContext;accessKey:string;permitted?:boolean;embedded?:boolean;draftRef?:import('react').Ref<{saveDraft:()=>Promise<boolean>}>;
 onCommand:(command:ContractCommand,values:Record<string,unknown>,context:ContractContext)=>Promise<ContractActionResult>;
 onRecover:()=>Promise<ContractActionResult>;onReload:()=>Promise<ContractContext>;
 onBack?:()=>void;backRef?:import('react').Ref<{back:()=>void}>;
 onTasks:()=>void;onDirtyChange?:(dirty:boolean)=>void;onLockedChange?:(locked:boolean)=>void;
 onDocument?:(version:ContractVersion)=>Promise<void>;
 onGenerate?:(values:Record<string,unknown>,context:ContractContext)=>Promise<{candidate:GeneratedContract;context:ContractContext}>;
 onSignatureUpload?:(file:File|undefined,uploadId:string|undefined,context:ContractContext)=>Promise<{state:string;uploadId:string;context:ContractContext}>;
 onSignatureDocument?:(materialId:string,context:ContractContext)=>Promise<void>;
 onDenied?:()=>void;
};
export type SignatureStage='ARRANGE'|'COLLECT'|'AWAIT_VERIFICATION'|'SUPPLEMENT'|'PARTIAL'|'ARCHIVE'|'SIGNATURE_COMPLETE'|'REVISION_REQUIRED'|'OWNER_EXCEPTION';
export type SignatureSlot={slotNumber:number;partyId:string;participationId?:string;templateSigningPartyId?:string;authoritySlot:string;required:boolean;signatureRequired:boolean;sealRequired:boolean;clauseBasis:string};
export type SignatureSubmission={selector:ContractSelector;slotNumber:number;materialVersionId:string;materialSha256:string;authorityMaterialVersionId:string;authorityMaterialSha256:string;signerName:string;signedAt:string;decision:string|null;reason?:string|null};
export type SignatureContext={workflow:{selector:ContractSelector|null;stage:SignatureStage;task:ContractSelector|null;ownerAppointmentId:string|null;ownerLabel?:string;dueAt?:string|null;message?:string|null};parties:{id:string;label:string;participationId?:string;templateSigningPartyId?:string}[];arrangements:SignatureSlot[];draftSelector?:ContractSelector|null;draft:Record<string,unknown>|null;historyEntries?:{id:string;label:string;summary:string;occurredAt:string;actorLabel:string;signerName?:string;signedAt?:string;reason?:string;materialVersionId?:string;authorityMaterialVersionId?:string}[];submissions:SignatureSubmission[];handoff:{state:string;message:string}|null;allowedActions?:ContractCommand[]};






export type ContractExecutionContext={handoff:ContractSelector;workflow:{selector:ContractSelector|null;stage:"CHECK_CONDITIONS"|"WAIT_RECEIPT"|"READY_TRANSFER"|"OWNER_EXCEPTION";task:ContractSelector|null;ownerAppointmentId:string|null;ownerLabel:string;dueAt:string|null;message:string}};
