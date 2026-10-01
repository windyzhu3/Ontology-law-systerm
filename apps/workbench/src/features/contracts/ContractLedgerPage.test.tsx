import {fireEvent,render,screen,waitFor} from '@testing-library/react';
import {expect,it,vi} from 'vitest';
import {ContractLedgerPage} from './ContractLedgerPage';
import {RecoveryStore} from '../session/recoveryMarker';
import type {ContractsTransport} from '../../lib/contractsTransport';
import type {ContractContext} from './types';
import {testSession,selectorId} from '../../test/fixtures';
const correctionApi=vi.hoisted(()=>({classificationContext:vi.fn(),write:vi.fn(),receipt:vi.fn(),recovery:{read:vi.fn(()=>null)}}));
vi.mock('../../lib/transfersTransport',async original=>({...await original<typeof import('../../lib/transfersTransport')>(),createTransfersTransport:()=>correctionApi}));
const selector={id:selectorId,revision:0};
it('opens classification correction from the standalone business ledger and returns to the same selection',async()=>{
 const context={opportunity:selector,customerName:'已接收合成客户',readonly:true,contract:{selector,document:{commercial:{scope:'合成范围',lines:[],paymentTerms:'合成安排'},document:{}}},history:[],allowedActions:[],transfer:{stage:'COMPLETE',task:null,canHandle:false,canCorrectClassification:true}} as unknown as ContractContext;
 const api={recovery:new RecoveryStore(sessionStorage),list:vi.fn().mockResolvedValue({items:[{id:selectorId,opportunityId:selectorId,customerLabel:context.customerName,versionLabel:'第1版',stateLabel:'分类完成',ownerLabel:'',canHandle:false}],nextCursor:null}),context:vi.fn().mockResolvedValue(context),taskContext:vi.fn(),write:vi.fn(),receipt:vi.fn(),download:vi.fn()} satisfies ContractsTransport;
 correctionApi.classificationContext.mockResolvedValue({opportunityId:selectorId,expectedOpportunityRevision:4,expectedWorkflow:selector,customerName:context.customerName,matter:{id:selectorId,number:'原案件001'},category:'ENFORCEMENT',recipient:{id:selectorId,label:'案管'},receivers:[{id:selectorId,label:'案管'}]});
 render(<ContractLedgerPage session={testSession()} api={api}/>);
 fireEvent.click(await screen.findByRole('button',{name:context.customerName}));
 fireEvent.click(await screen.findByRole('button',{name:'更正分类及承接'}));
 await screen.findByRole('heading',{name:'核对后更正分类及承接'});
 expect(screen.getByText('原案件001')).toBeVisible();
 fireEvent.click(screen.getByRole('button',{name:'取消更正'}));
 await screen.findByRole('heading',{name:'合同台账'});
 await screen.findByRole('button',{name:'更正分类及承接'});
 expect(correctionApi.write).not.toHaveBeenCalled();
});
it('opens a completed ledger record in the existing read-only card and downloads its exact version',async()=>{
 const context:ContractContext={opportunity:selector,responsibilityBasis:selector,customerConfirmation:selector,customerName:'合成完成合同',readonly:false,contract:{selector,currentRevision:{id:'version',hash:'hash'},version:1,source:{kind:'DIRECT_AUTHORIZATION',selector},stage:'READY_FOR_SIGNATURE',document:{commercial:{currency:'CNY',scope:'合成范围',lines:[{description:'固定费用',amountMinor:100,discount:false}],conditionalFee:null,paymentTerms:'合成安排'},document:{evidenceVersionId:'file',bodySha256:'hash',templateVersionId:'template',clauseVersionIds:[]}}},draft:null,workflow:null,review:null,approvals:[],allowedActions:['FORM_CONTRACT'],blockers:[],history:[],receiptBoundary:null,documents:[{id:'file',label:'private-name.png',bodySha256:'hash'}]};
 const api={recovery:new RecoveryStore(sessionStorage),list:vi.fn().mockResolvedValue({items:[{id:selectorId,opportunityId:selectorId,customerLabel:context.customerName,versionLabel:'第 1 版',stateLabel:'签署准备已就绪',ownerLabel:'',canHandle:false}],nextCursor:null}),context:vi.fn().mockResolvedValue(context),taskContext:vi.fn(),write:vi.fn(),receipt:vi.fn(),download:vi.fn().mockResolvedValue(new Blob(['synthetic']))} satisfies ContractsTransport;
 const create=vi.spyOn(URL,'createObjectURL').mockReturnValue('blob:synthetic'),revoke=vi.spyOn(URL,'revokeObjectURL').mockImplementation(()=>{}),click=vi.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(()=>{});
 try{render(<ContractLedgerPage session={testSession()} api={api} onTasks={vi.fn()} onBack={vi.fn()}/>);fireEvent.click(await screen.findByRole('button',{name:context.customerName}));fireEvent.click(await screen.findByRole('button',{name:'查看合同内容'}));await screen.findByRole('heading',{name:'查看合同历史'});expect(screen.queryByRole('button',{name:'形成这份合同版本'})).toBeNull();expect(api.context).toHaveBeenCalledTimes(2);fireEvent.click(screen.getByRole('button',{name:'查看准确合同正文'}));await waitFor(()=>expect(api.download).toHaveBeenCalledWith(expect.anything(),selectorId,{contractId:selectorId,versionId:'version'},expect.any(AbortSignal)));expect(api.write).not.toHaveBeenCalled();await waitFor(()=>expect(click).toHaveBeenCalledOnce());expect((click.mock.instances[0] as HTMLAnchorElement).download).toBe('委托合同-第1版.png');}finally{create.mockRestore();revoke.mockRestore();click.mockRestore();}
});

it('dispatches an actionable contract to the shared workbench and switches ledgers from the sidebar',async()=>{
 const context:ContractContext={opportunity:selector,responsibilityBasis:selector,customerConfirmation:selector,customerName:'合成完成合同',readonly:false,contract:{selector,currentRevision:{id:'version',hash:'hash'},version:1,source:{kind:'DIRECT_AUTHORIZATION',selector},stage:'READY_FOR_SIGNATURE',document:{commercial:{currency:'CNY',scope:'合成范围',lines:[{description:'固定费用',amountMinor:100,discount:false}],conditionalFee:null,paymentTerms:'合成安排'},document:{evidenceVersionId:'file',bodySha256:'hash',templateVersionId:'template',clauseVersionIds:[]}}},draft:null,workflow:null,review:null,approvals:[],allowedActions:['FORM_CONTRACT'],blockers:[],history:[],receiptBoundary:null,documents:[{id:'file',label:'private-name.png',bodySha256:'hash'}]};
 const api={recovery:new RecoveryStore(sessionStorage),list:vi.fn().mockResolvedValue({items:[{id:selectorId,opportunityId:selectorId,customerLabel:context.customerName,versionLabel:'第 1 版',stateLabel:'签署准备已就绪',ownerLabel:'',canHandle:true}],nextCursor:null}),context:vi.fn().mockResolvedValue(context),taskContext:vi.fn(),write:vi.fn(),receipt:vi.fn(),download:vi.fn().mockResolvedValue(new Blob(['synthetic']))} satisfies ContractsTransport;
 const onHandle=vi.fn(),onBack=vi.fn();render(<ContractLedgerPage session={testSession()} api={api} onTasks={vi.fn()} onBack={onBack} onHandle={onHandle}/>);expect(screen.queryByRole('button',{name:'返回商机台账'})).toBeNull();expect(screen.getByRole('button',{name:'合同台账'})).toHaveAttribute('aria-current','page');fireEvent.click(await screen.findByRole('button',{name:context.customerName}));fireEvent.click(await screen.findByRole('button',{name:'前往办理'}));await waitFor(()=>expect(onHandle).toHaveBeenCalledWith(context));expect(screen.queryByRole('heading',{name:'查看合同历史'})).toBeNull();fireEvent.click(screen.getByRole('button',{name:'商机台账'}));expect(onBack).toHaveBeenCalledOnce();
});

it('searches beyond the loaded contract page and resets its cursor before rendering server results',async()=>{
 const first={id:selectorId,opportunityId:selectorId,customerLabel:'当前第一页客户',versionLabel:'第 1 版',stateLabel:'待形成合同',ownerLabel:'',canHandle:false};
 const found={...first,customerLabel:'远方客户'};
 const list=vi.fn().mockResolvedValueOnce({items:[first],nextCursor:'page-two'}).mockResolvedValueOnce({items:[],nextCursor:'page-three'}).mockResolvedValue({items:[found],nextCursor:null});
 const api={recovery:new RecoveryStore(sessionStorage),list,context:vi.fn(),taskContext:vi.fn(),write:vi.fn(),receipt:vi.fn(),download:vi.fn()} satisfies ContractsTransport;
 render(<ContractLedgerPage session={testSession()} api={api}/>);
 await screen.findByRole('button',{name:first.customerLabel});fireEvent.click(screen.getByRole('button',{name:'下一页'}));
 await waitFor(()=>expect(list).toHaveBeenCalledTimes(2));
 fireEvent.change(screen.getByRole('textbox',{name:'搜索客户'}),{target:{value:'远方客户'}});
 await screen.findByRole('button',{name:found.customerLabel});
 expect(list.mock.calls.at(-1)?.slice(2)).toEqual([undefined,{search:'远方客户',state:''}]);
 expect(screen.queryByRole('button',{name:'返回第一页'})).toBeNull();
});

it.each(['contracts','payments','transfer'] as const)('returns from %s to the authorized team view without opening a workcard',async initialView=>{
 vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response(JSON.stringify({items:[],nextCursor:null}),{status:200,headers:{'Content-Type':'application/json'}})));
 const api={recovery:new RecoveryStore(sessionStorage),list:vi.fn().mockResolvedValue({items:[],nextCursor:null}),context:vi.fn(),taskContext:vi.fn(),write:vi.fn(),receipt:vi.fn(),download:vi.fn()} satisfies ContractsTransport;
 const onTeam=vi.fn();render(<ContractLedgerPage session={testSession()} api={api} initialView={initialView} onTeam={onTeam}/>);
 fireEvent.click(await screen.findByRole('button',{name:'团队待办'}));expect(onTeam).toHaveBeenCalledOnce();expect(api.write).not.toHaveBeenCalled();expect(api.taskContext).not.toHaveBeenCalled();expect(screen.queryByRole('button',{name:'我的待办'})).toBeNull();
});
