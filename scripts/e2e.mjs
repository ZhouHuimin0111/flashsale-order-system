import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import { performance } from 'node:perf_hooks';

// Requires a NEW compose environment. This consumes coupon #3; never run against
// a shared/live deployment. Refuses an already used stock allocation.
const base=process.env.BASE_URL||'http://localhost:8080';
const userCount=Number(process.env.USERS||60);
const concurrency=Number(process.env.CONCURRENCY||30);
const prefix=`it${Date.now().toString(36)}`;
const instances=new Set();
async function request(path,token,method='GET',body) {
  const res=await fetch(`${base}/api${path}`,{method,headers:{...(token?{Authorization:`Bearer ${token}`}:{ }),...(body?{'Content-Type':'application/json'}:{})},body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(15000)});
  const instance=res.headers.get('x-service-instance');if(instance)instances.add(instance);
  return {status:res.status,data:await res.json()};
}
async function limited(items,fn,width=concurrency) {
  let next=0;const result=new Array(items.length);
  await Promise.all(Array.from({length:Math.min(width,items.length)},async()=>{for(;;){const i=next++;if(i>=items.length)return;result[i]=await fn(items[i],i);}}));
  return result;
}
const first=await request('/coupons/3');assert.equal(first.status,200);
assert.equal(first.data.availableStock,first.data.initialStock,'Use a fresh environment: coupon #3 has already been used');
const stock=first.data.initialStock;assert.ok(userCount>stock);
assert.equal((await request('/orders/mine')).status,401);
const users=await limited(Array.from({length:userCount},(_,i)=>i),async i=>{
  const r=await request('/auth/register',null,'POST',{username:`${prefix}_${i}`,password:'IntegrationPass2026!'});assert.equal(r.status,200,JSON.stringify(r));return r.data;
},10);
for(let i=0;i<8;i++)assert.equal((await request('/auth/me',users[0].token)).data.id,users[0].user.id);
assert.ok(instances.size>=2,'Shared token must work through at least two backend instances');
const samples=[];const start=performance.now();
const attempts=await limited(users.flatMap(u=>[u,u]),async u=>{
  const t=performance.now();const r=await request('/coupons/3/orders',u.token,'POST');samples.push(performance.now()-t);
  assert.ok([202,409].includes(r.status),JSON.stringify(r));
  if(r.status===409)assert.equal(r.data.code,'SOLD_OUT');
  return {...r,user:u};
});
const elapsed=performance.now()-start;
const accepted=attempts.filter(r=>r.status===202&&!r.data.duplicate);
const duplicates=attempts.filter(r=>r.status===202&&r.data.duplicate);
assert.equal(accepted.length,stock);assert.equal(duplicates.length,stock);
assert.equal(new Set(accepted.map(r=>r.user.user.id)).size,stock);
let confirmed=0;
for(let poll=0;poll<90;poll++){
  const results=await limited(accepted,async r=>request(`/coupons/3/orders/${r.data.orderId}`,r.user.token));
  assert.ok(results.every(r=>r.status===200));
  assert.ok(results.every(r=>['PENDING','CONFIRMED'].includes(r.data.status)),JSON.stringify(results));
  confirmed=results.filter(r=>r.data.status==='CONFIRMED').length;
  if(confirmed===stock)break;
  await new Promise(resolve=>setTimeout(resolve,1000));
}
assert.equal(confirmed,stock,'MQ must confirm every accepted reservation');
assert.equal((await request('/coupons/3')).data.availableStock,0);
const victim=accepted[0];const other=users.find(u=>u.user.id!==victim.user.user.id);
assert.equal((await request(`/coupons/3/orders/${victim.data.orderId}`,other.token)).status,404,'Cannot query another buyer order');
const own=await request('/orders/mine',victim.user.token);assert.equal(own.data.filter(o=>o.couponId===3).length,1);
const recovered=await request('/orders/reservations',victim.user.token);assert.equal(recovered.data.find(o=>o.couponId===3).orderId,victim.data.orderId);
assert.equal((await request('/coupons/1',victim.user.token,'PATCH',{title:'unauthorized'})).status,403);
const like=await request('/social/posts/1/like',users[0].token,'POST');assert.equal(like.data.liked,true);
const likers=await request('/social/posts/1/likers');assert.ok(likers.data.some(u=>u.id===users[0].user.id));
assert.equal((await request('/social/posts/ranking')).data[0].postId,1);
assert.equal((await request('/social/posts/1/like',users[0].token,'POST')).data.liked,false);
for(const u of users.slice(0,2))assert.equal((await request(`/social/follow/${users[2].user.id}`,u.token,'PUT')).data.following,true);
const common=await request(`/social/follow/${users[1].user.id}/common`,users[0].token);assert.ok(common.data.some(u=>u.id===users[2].user.id));
assert.equal((await request(`/social/follow/${users[2].user.id}`,users[0].token,'DELETE')).data.following,false);
assert.equal((await request('/auth/logout',users[0].token,'POST')).status,200);
assert.equal((await request('/auth/me',users[0].token)).status,401);
samples.sort((a,b)=>a-b);const percentile=p=>Math.round(samples[Math.min(samples.length-1,Math.floor(samples.length*p))]);
const report={timestamp:new Date().toISOString(),environment:process.env.TEST_ENVIRONMENT||'unspecified',users:userCount,requests:attempts.length,concurrency,initialStock:stock,accepted:accepted.length,duplicates:duplicates.length,soldOut:attempts.filter(r=>r.status===409).length,confirmed,backendInstances:instances.size,admissionElapsedMs:Math.round(elapsed),admissionRequestsPerSecond:Number((attempts.length*1000/elapsed).toFixed(2)),latencyMs:{p50:percentile(.5),p95:percentile(.95),p99:percentile(.99)},note:'Single-run correctness smoke test including rejected/duplicate HTTP responses; not a production capacity claim.'};
await fs.mkdir('test-results',{recursive:true});await fs.writeFile('test-results/e2e.json',JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report,null,2));
