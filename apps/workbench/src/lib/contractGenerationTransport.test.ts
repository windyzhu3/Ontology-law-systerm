import {it,expect,vi} from 'vitest';
import {acceptGeneratedMaterial} from './contractGenerationTransport';
import type {MaterialsTransport,MaterialContext} from './materialsTransport';
import type {ContractContext,GeneratedContract} from '../features/contracts/types';
import {testSession,selectorId} from '../test/fixtures';
const selector={id:selectorId,revision:0};
const context={opportunity:selector,responsibilityBasis:selector,customerConfirmation:selector} as ContractContext;
const candidate:GeneratedContract={bodySha256:'38523c087796e5d5dd1cf9bad1fb026781a838dd9dd2cf8af58b9f6502a46778',pdfBase64:'JVBERi0=',previewText:'body',generationProof:'proof',expiresAt:'2026-09-24T00:00:00Z'};
const name='contract-generated-'+candidate.bodySha256+'.pdf';
const material={opportunity:selector,responsibilityBasis:selector,confirmation:selector,editable:true,closed:false,versions:[],pendingUploads:[]} as MaterialContext;
it('reuses an already accepted generated material on retry without opening or accepting again',async()=>{
 const api={context:vi.fn().mockResolvedValue({...material,versions:[{selector,fileName:name,sizeBytes:5,purpose:'CONTRACT_BUSINESS',mediaType:'application/pdf'}]}),write:vi.fn(),content:vi.fn().mockResolvedValue({arrayBuffer:async()=>new TextEncoder().encode('%PDF-').buffer})} as unknown as MaterialsTransport;
 await expect(acceptGeneratedMaterial(api,testSession(),context,candidate,new AbortController().signal)).resolves.toBe(selectorId);expect(api.write).not.toHaveBeenCalled();
});
it('queries pending scan instead of resending bytes or opening a duplicate upload',async()=>{
 const upload={selector,fileName:name,state:'CHECKING',purpose:'CONTRACT_BUSINESS',confirmation:selector,previousVersion:null};const api={context:vi.fn().mockResolvedValue({...material,pendingUploads:[upload]}),status:vi.fn().mockResolvedValue(upload),write:vi.fn(),upload:vi.fn()} as unknown as MaterialsTransport;
 await expect(acceptGeneratedMaterial(api,testSession(),context,candidate,new AbortController().signal)).rejects.toThrow();expect(api.status).toHaveBeenCalledOnce();expect(api.write).not.toHaveBeenCalled();expect(api.upload).not.toHaveBeenCalled();
});
it('changed responsibility refuses generated file reception before any write',async()=>{
 const api={context:vi.fn().mockResolvedValue({...material,responsibilityBasis:{...selector,revision:2}}),write:vi.fn()} as unknown as MaterialsTransport;
 await expect(acceptGeneratedMaterial(api,testSession(),context,candidate,new AbortController().signal)).rejects.toThrow();expect(api.write).not.toHaveBeenCalled();
});

it('a same-name different body does not trap later retries on the wrong material',async()=>{
 const api={context:vi.fn().mockResolvedValue({...material,versions:[{selector,fileName:name,sizeBytes:5,purpose:'CONTRACT_BUSINESS',mediaType:'application/pdf'}]}),content:vi.fn().mockResolvedValue({arrayBuffer:async()=>new TextEncoder().encode('wrong').buffer}),write:vi.fn().mockRejectedValue(new Error('new upload attempted'))} as unknown as MaterialsTransport;
 await expect(acceptGeneratedMaterial(api,testSession(),context,candidate,new AbortController().signal)).rejects.toThrow('new upload attempted');expect(api.write).toHaveBeenCalledOnce();
});
