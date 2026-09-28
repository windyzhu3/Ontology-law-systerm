import {it,expect} from 'vitest';
import {currentContractTask} from './currentContractTask';
import type {ContractContext} from './types';
it('ledger dispatch selects current signature task instead of completed preparation task',()=>{expect(currentContractTask({signature:{workflow:{task:{id:'signature-task',revision:0}}},workflow:{task:{id:'old-preparation',revision:0}}} as ContractContext)?.id).toBe('signature-task');});
it('signature owner exception never falls back to old preparation task',()=>{expect(currentContractTask({signature:{workflow:{task:null}},workflow:{task:{id:'old-preparation',revision:0}}} as ContractContext)).toBeNull();});

it('ledger dispatch selects the authorized transfer task after execution',()=>{expect(currentContractTask({transfer:{stage:'PREPARE',canHandle:true,task:{id:'transfer-task',revision:0}},execution:{workflow:{task:null}},workflow:{task:{id:'old',revision:0}}} as unknown as ContractContext)?.id).toBe('transfer-task');});
it('completed transfer never revives an old contract task',()=>{expect(currentContractTask({transfer:{stage:'COMPLETE',canHandle:false,task:null},workflow:{task:{id:'old',revision:0}}} as unknown as ContractContext)).toBeNull();});
