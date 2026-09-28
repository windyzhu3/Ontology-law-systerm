/** Bounded reads only; command outcome recovery remains unchanged. */
export function readWithDeadline<T>(read:(signal:AbortSignal)=>Promise<T>,signal:AbortSignal,milliseconds=30_000):Promise<T>{
 return new Promise<T>((resolve,reject)=>{
  const controller=new AbortController();let settled=false;
  const cancel=()=>{controller.abort();finish(()=>reject(new DOMException('Read cancelled','AbortError')));};
  const timer=setTimeout(()=>{controller.abort();finish(()=>reject(new Error('读取超时，请重新查询。')));},milliseconds);
  function finish(complete:()=>void){if(settled)return;settled=true;clearTimeout(timer);signal.removeEventListener('abort',cancel);complete();}
  signal.addEventListener('abort',cancel,{once:true});
  if(signal.aborted){cancel();return;}
  Promise.resolve().then(()=>read(controller.signal)).then(value=>finish(()=>resolve(value)),error=>finish(()=>reject(error)));
 });
}
