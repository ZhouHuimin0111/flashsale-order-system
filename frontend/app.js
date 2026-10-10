'use strict';
const $ = selector => document.querySelector(selector);
const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const state = { token: sessionStorage.getItem('flashsale.token'), user: null, coupons: [], register: false, view: 'discover', refreshTimer: null };
let toastTimer;
function toast(message) { $('#toast').textContent = message; $('#toast').hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => $('#toast').hidden = true, 5500); }
async function api(path, {method='GET', body} = {}) {
  const headers = {};
  if(state.token) headers.Authorization = `Bearer ${state.token}`;
  if(body) headers['Content-Type'] = 'application/json';
  const response = await fetch(`/api${path}`, {method, headers, body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(12000)});
  const data = await response.json().catch(() => ({message:'服务器响应异常'}));
  if(!response.ok) {
    if(response.status === 401) { state.token = null; state.user = null; sessionStorage.removeItem('flashsale.token'); renderAccount(); }
    throw new Error(data.message || `请求失败 (${response.status})`);
  }
  return data;
}
function requireLogin() { if(state.user) return true; $('#auth-dialog').showModal(); return false; }
function renderAccount() { $('#account').textContent = state.user ? `${state.user.username} · 退出` : '登录 / 注册 ↗'; }
async function loadCoupons() {
  try {
    state.coupons = await api('/coupons'); $('#coupon-count').textContent = state.coupons.length;
    const labels=['DAILY COFFEE','WEEKEND TABLE','DESK ESSENTIALS'], objects=['☕','bon','⌨'];
    $('#coupon-list').innerHTML=state.coupons.map((c,i) => {
      const now=Date.now(), started=now>=Date.parse(c.startsAt), ended=now>Date.parse(c.endsAt);
      const unavailable=c.availableStock<0, sold=c.availableStock===0;
      const label=unavailable?'活动准备中':ended?'活动已结束':!started?'即将开始':sold?'本期已抢完':'立即抢购';
      return `<article class="coupon-card"><div class="card-art art-${i%3+1}"><span class="category">${labels[i%3]}</span><div class="art-object" aria-hidden="true">${objects[i%3]}</div><span class="card-sequence">EDIT / ${String(i+1).padStart(2,'0')}</span></div><div class="card-info"><h3>${escapeHtml(c.title)}</h3><p class="card-subtitle">限量 ${c.initialStock} 份 · 每人限购一单</p><div class="card-price"><span class="currency">¥</span><span class="price">${(c.priceCents/100).toFixed(2)}</span><span class="price-label">专享价</span></div><div class="stock-row"><span>实时可预留库存</span><strong>${unavailable?'—':c.availableStock} / ${c.initialStock}</strong></div><progress aria-label="可预留库存" max="${Math.max(1,c.initialStock)}" value="${Math.max(0,c.availableStock)}"></progress><button class="buy-button" data-buy="${c.id}" ${unavailable||ended||!started||sold?'disabled':''}><span>${label}</span><span>↗</span></button></div></article>`;
    }).join('');
  } catch(e) { $('#coupon-list').innerHTML=`<p class="empty">${escapeHtml(e.message)}。请检查服务并刷新。</p>`; }
}
async function buy(id,button) {
  if(!requireLogin()) return;
  button.disabled=true;
  try {
    const r=await api(`/coupons/${id}/orders`,{method:'POST'});
    toast(r.duplicate?'你已提交过这张优惠券，正在查询同一订单。':'提交成功，正在确认订单…');
    await showView('orders'); await loadCoupons(); startOrderPolling();
  } catch(e) { toast(`${e.message}。如提交结果不确定，请到我的订单刷新查询。`); button.disabled=false; }
}
const statusLabels={PENDING:'正在确认',CONFIRMED:'已确认',REJECTED:'未成功'};
async function loadOrders() {
  if(!state.user) { $('#order-list').innerHTML='<p class="empty">登录后查看你的订单。</p>'; return; }
  try {
    const [confirmed,reservations]=await Promise.all([api('/orders/mine'),api('/orders/reservations')]);
    const combined=new Map(confirmed.map(o=>[o.orderId,o]));
    for(const o of reservations) combined.set(o.orderId,{...combined.get(o.orderId),...o});
    const orders=[...combined.values()];
    $('#order-list').innerHTML=orders.length?orders.map(o => `<article class="order-row"><div><h3>${escapeHtml(o.title||state.coupons.find(c=>c.id===o.couponId)?.title||`优惠券 #${o.couponId}`)}</h3><div class="order-number">${escapeHtml(o.orderId)}</div></div><span class="status ${o.status.toLowerCase()}">${statusLabels[o.status]||escapeHtml(o.status)}</span></article>`).join(''):'<p class="empty">还没有订单，去发现一份心动好物吧。</p>';
    return orders.some(o=>o.status==='PENDING');
  } catch(e) { $('#order-list').innerHTML=`<p class="empty">${escapeHtml(e.message)}</p>`; }
}
function startOrderPolling() {
  clearInterval(state.refreshTimer); let attempts=0;
  state.refreshTimer=setInterval(async()=>{
    if(state.view!=='orders'||!state.user||++attempts>30) { clearInterval(state.refreshTimer); return; }
    const pending=await loadOrders(); if(!pending) clearInterval(state.refreshTimer);
  },2000);
}
async function loadPosts() {
  try {
    const posts=await api('/social/posts');
    $('#post-list').innerHTML=posts.map(p=>`<article class="post-card"><span class="post-author">${escapeHtml(p.author)}</span><h3>${escapeHtml(p.title)}</h3><p>${escapeHtml(p.body)}</p><div class="post-actions"><button class="like-button ${p.liked?'liked':''}" data-like="${p.id}">${p.liked?'♥':'♡'} ${p.likes}</button><button class="quiet-button" data-likers="${p.id}">最早点赞的用户 ↗</button></div></article>`).join('');
  }catch(e){$('#post-list').innerHTML=`<p class="empty">${escapeHtml(e.message)}</p>`;}
}
async function loadUsers() {
  if(!state.user){$('#user-list').innerHTML='<p class="empty">登录后发现其他用户。</p>';return;}
  try {
    const users=await api('/social/users');
    $('#user-list').innerHTML=users.length?users.map(u=>`<article class="user-card"><span class="avatar">${escapeHtml(u.username[0].toUpperCase())}</span><span>${escapeHtml(u.username)}</span><div class="user-actions"><button class="button" data-follow="${u.id}" data-following="${u.following}">${u.following?'已关注':'关注 +'}</button><button class="quiet-button" data-common="${u.id}">共同关注</button></div><div class="common-result" id="common-${u.id}" hidden></div></article>`).join(''):'<p class="empty">还没有其他用户，邀请朋友来体验吧。</p>';
  }catch(e){$('#user-list').innerHTML=`<p class="empty">${escapeHtml(e.message)}</p>`;}
}
async function showView(name) {
  state.view=name; document.querySelectorAll('.view').forEach(el=>el.hidden=el.id!==name);
  document.querySelectorAll('.nav-tab').forEach(el=>el.classList.toggle('active',el.dataset.view===name));
  if(name==='orders'){await loadOrders();startOrderPolling();} if(name==='community') await Promise.all([loadPosts(),loadUsers()]);
}
document.querySelectorAll('[data-view]').forEach(b=>b.addEventListener('click',()=>showView(b.dataset.view)));
$('#refresh').addEventListener('click',loadCoupons); $('#refresh-orders').addEventListener('click',()=>{loadOrders();startOrderPolling();}); $('#refresh-users').addEventListener('click',loadUsers);
$('#account').addEventListener('click',async()=>{
  if(!state.user){$('#auth-dialog').showModal();return;}
  try {await api('/auth/logout',{method:'POST'});} catch(e) {toast(e.message);return;}
  state.token=null;state.user=null;sessionStorage.removeItem('flashsale.token');renderAccount();await showView(state.view);toast('已退出登录。');
});
$('#close-auth').addEventListener('click',()=>$('#auth-dialog').close());
$('#auth-switch').addEventListener('click',()=>{
  state.register=!state.register;$('#auth-title').textContent=state.register?'认识一下。':'欢迎回来。';
  $('#auth-description').textContent=state.register?'创建账号，开始你的限量生活提案。':'登录后，收藏属于你的日常好物。';
  $('#auth-submit').textContent=state.register?'创建账号 ↗':'登录 ↗';$('#auth-switch').textContent=state.register?'已有账号？返回登录':'还没有账号？创建账号';
  $('#password').autocomplete=state.register?'new-password':'current-password';$('#auth-error').textContent='';
});
$('#auth-form').addEventListener('submit',async e=>{
  e.preventDefault();$('#auth-submit').disabled=true;$('#auth-error').textContent='';
  try{
    const r=await api(`/auth/${state.register?'register':'login'}`,{method:'POST',body:{username:$('#username').value,password:$('#password').value}});
    state.token=r.token;state.user=r.user;sessionStorage.setItem('flashsale.token',r.token);renderAccount();$('#auth-dialog').close();$('#password').value='';
    await showView(state.view);toast(`你好，${r.user.username}。`);
  }catch(e){$('#auth-error').textContent=e.message;}finally{$('#auth-submit').disabled=false;}
});
document.addEventListener('click',async e=>{
  const button=e.target.closest('button');if(!button)return;
  if(button.dataset.buy){await buy(Number(button.dataset.buy),button);return;}
  try{
    if(button.dataset.like){if(!requireLogin())return;button.disabled=true;await api(`/social/posts/${button.dataset.like}/like`,{method:'POST'});await loadPosts();}
    if(button.dataset.likers){const users=await api(`/social/posts/${button.dataset.likers}/likers`);toast(users.length?users.map(u=>u.username).join('、'):'还没有人点赞，来做第一个吧。');}
    if(button.dataset.follow){if(!requireLogin())return;await api(`/social/follow/${button.dataset.follow}`,{method:button.dataset.following==='true'?'DELETE':'PUT'});await loadUsers();}
    if(button.dataset.common){if(!requireLogin())return;const users=await api(`/social/follow/${button.dataset.common}/common`);const el=$(`#common-${button.dataset.common}`);el.textContent=users.length?`共同关注：${users.map(u=>u.username).join('、')}`:'还没有共同关注的人。';el.hidden=false;}
  }catch(err){toast(err.message);}finally{button.disabled=false;}
});
(async()=>{if(state.token){try{state.user=await api('/auth/me');}catch(e){toast(e.message);}}renderAccount();await loadCoupons();})();
