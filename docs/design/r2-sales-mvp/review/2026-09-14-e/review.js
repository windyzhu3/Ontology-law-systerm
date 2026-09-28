'use strict';
const frame=document.querySelector('#preview'),scene=document.querySelector('#scene'),response=document.querySelector('#response');
const send=data=>frame.contentWindow.postMessage(data,location.origin);
scene.onchange=()=>send({type:'scene',value:scene.value});
response.onchange=()=>send({type:'outcome',value:response.value});
document.querySelector('#mobile').onclick=e=>{const pressed=e.currentTarget.getAttribute('aria-pressed')!=='true';e.currentTarget.setAttribute('aria-pressed',String(pressed));e.currentTarget.textContent=pressed?'查看桌面':'查看小屏';document.querySelector('.review-stage').classList.toggle('mobile',pressed);};
window.addEventListener('message',e=>{if(e.origin!==location.origin||e.source!==frame.contentWindow)return;if(e.data.type==='scene-state')scene.value=e.data.value;if(e.data.type==='busy'){scene.disabled=e.data.value;response.disabled=e.data.value;}});

frame.addEventListener('load',()=>send({type:'review-ready'}));
send({type:'review-ready'});
