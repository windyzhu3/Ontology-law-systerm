import {afterEach,expect,it,vi} from 'vitest';
import {readWithDeadline} from './readDeadline';
afterEach(()=>vi.useRealTimers());
it('bounds a hanging read even if fetch ignores abort and ignores late results',async()=>{
 vi.useFakeTimers();let resolve!:(value:string)=>void;let dispatched!:AbortSignal;
 const result=readWithDeadline(signal=>{dispatched=signal;return new Promise<string>(r=>resolve=r);},new AbortController().signal);
 const assertion=expect(result).rejects.toThrow('读取超时');await vi.advanceTimersByTimeAsync(30_000);await assertion;expect(dispatched.aborted).toBe(true);resolve('late');expect(vi.getTimerCount()).toBe(0);
});
it('cleans deadline after success and rejects navigation cancellation',async()=>{
 vi.useFakeTimers();await expect(readWithDeadline(async()=>42,new AbortController().signal)).resolves.toBe(42);expect(vi.getTimerCount()).toBe(0);
 const c=new AbortController();const result=readWithDeadline(()=>new Promise(()=>{}),c.signal);c.abort();await expect(result).rejects.toMatchObject({name:'AbortError'});expect(vi.getTimerCount()).toBe(0);
});
