import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import fs from 'node:fs/promises';
const base=process.env.BASE_URL||'http://localhost:8080';
async function api(path,token,method='GET',body){
  const r=await fetch(base+'/api'+path,{method,headers:{...(token?{Authorization:'Bearer '+token}:{}),...(body?{'Content-Type':'application/json'}:{})},body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(15000)});
  assert.ok(r.ok,`${r.status} ${await r.clone().text()}`);return r.json();
}
const docker=(...args)=>execFileSync('docker',['compose',...args],{encoding:'utf8',stdio:['ignore','pipe','pipe']});
const user=await api('/auth/register',null,'POST',{username:'recovery_'+Date.now().toString(36),password:'RecoveryPass2026!'});
let order;
try{
  docker('stop','broker');
  order=await api('/coupons/2/orders',user.token,'POST');
  assert.equal(order.status,'PENDING');
  await new Promise(resolve=>setTimeout(resolve,6000));
  assert.equal((await api(`/coupons/2/orders/${order.orderId}`,user.token)).status,'PENDING');
  const outboxLength=Number(docker('exec','-T','redis','redis-cli','XLEN','sale:{2}:outbox').trim());
  assert.ok(outboxLength>=1,'Event remains durable while MQ is stopped');
  // Restart both applications to prove pending delivery does not rely on an in-memory queue.
  docker('restart','order-1','order-2');
}finally{docker('start','broker');}
let confirmed=false;
for(let i=0;i<150;i++){
  try{const r=await api(`/coupons/2/orders/${order.orderId}`,user.token);if(r.status==='CONFIRMED'){confirmed=true;break;}}catch{ /* backend restarts may return 502 */ }
  await new Promise(resolve=>setTimeout(resolve,1000));
}
assert.ok(confirmed,'Durable pending event must recover after broker + applications restart');
const duplicate=await api('/coupons/2/orders',user.token,'POST');assert.equal(duplicate.orderId,order.orderId);assert.equal(duplicate.duplicate,true);
const report={timestamp:new Date().toISOString(),brokerOutage:true,bothApplicationsRestarted:true,orderId:order.orderId,recovered:true,duplicateReturnedSameOrder:true};
await fs.mkdir('test-results',{recursive:true});await fs.writeFile('test-results/failure-recovery.json',JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report,null,2));
