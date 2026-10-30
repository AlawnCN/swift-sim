'use strict';
const $ = s => document.querySelector(s);
const M = () => $('#main');
const esc = v => v == null ? '' : String(v).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const fmtT = t => t ? new Date(t).toLocaleString('zh-CN', {hour12:false}) : '';
let ME = null, PARTS = [], logTimer = null;

async function api(path, opt = {}) {
  const o = {method: opt.method || 'GET', headers: {}};
  if (o.method !== 'GET') o.headers['X-Sim-Admin'] = '1';
  if (opt.body !== undefined) { o.headers['Content-Type'] = 'application/json'; o.body = JSON.stringify(opt.body); }
  const r = await fetch(path, o);
  if (r.status === 401) { location.href = '/admin/login.html'; throw new Error('login'); }
  const ct = r.headers.get('content-type') || '';
  const data = ct.includes('json') ? await r.json() : await r.text();
  if (!r.ok && !opt.allowError) { toast('错误 ' + r.status + ': ' + (data.error || JSON.stringify(data)).slice(0, 400)); throw new Error(data.error || r.status); }
  return data;
}
function toast(t) { const e = $('#toast'); e.textContent = t; e.style.display = 'block'; clearTimeout(e._t); e._t = setTimeout(() => e.style.display = 'none', 6000); }
function q(obj) { const p = new URLSearchParams(); for (const [k, v] of Object.entries(obj)) if (v !== '' && v != null) p.set(k, v); return p.toString(); }
function partOpts(sel, withEmpty = true) { return (withEmpty ? '<option value="">(任意)</option>' : '') + PARTS.map(p => `<option value="${esc(p.id)}" ${p.id === sel ? 'selected' : ''}>${esc(p.id)} ${esc(p.bic)}${p.sut ? ' [SUT]' : ''}</option>`).join(''); }
function kv(obj, keys) { return '<table class="kv">' + (keys || Object.keys(obj)).filter(k => obj[k] != null && obj[k] !== '' && typeof obj[k] !== 'object').map(k => `<tr><td>${esc(k)}</td><td>${esc(obj[k])}</td></tr>`).join('') + '</table>'; }
function findingsTable(f) {
  if (!f || !f.length) return '<div class="muted">无发现 (no findings)</div>';
  return '<table><tr><th>级别</th><th>代码</th><th>说明</th><th>证据</th></tr>' + f.map(x => `<tr><td class="sev-${esc(x.severity)}">${esc(x.severity)}</td><td>${esc(x.code)}${x.field ? '<br><span class="muted">:' + esc(x.field) + ':</span>' : ''}</td><td>${esc(x.message)}</td><td>${(x.evidence || []).map(i => `<a href="#msgs/${i}">#${i}</a>`).join(' ')}</td></tr>`).join('') + '</table>';
}
function msgRow(m) {
  return `<tr class="click" onclick="location.hash='#msgs/${m.id}'"><td>${m.id}</td><td class="small">${fmtT(m.createdAt)}</td><td class="${esc(m.direction)}">${esc(m.direction)}</td><td>${esc(m.msgType)}</td><td>${esc(m.sender)}</td><td>${esc(m.receiver)}</td><td>${esc(m.txRef)}</td><td>${esc(m.relatedRef)}</td><td class="st-${esc(m.status)}">${esc(m.status)}</td><td class="b-${esc(m.businessStatus)}">${esc(m.businessStatus)}${m.businessReject ? ' <span class="badge red">业务拒绝报文</span>' : ''}</td></tr>`;
}
const MSGHEAD = '<tr><th>#</th><th>时间</th><th>方向</th><th>类型</th><th>发报行</th><th>收报行</th><th>20</th><th>21</th><th>网关</th><th>业务</th></tr>';

// ---------------------------------------------------------------------------------------------- router
const routes = {dash, msgs, ledger, review, parts, scen, compose, logs, audit, tools};
async function route() {
  if (logTimer) { clearInterval(logTimer); logTimer = null; }
  const [name, arg] = (location.hash.slice(1) || 'dash').split('/');
  document.querySelectorAll('#side a[href^="#"]').forEach(a => a.classList.toggle('on', a.getAttribute('href') === '#' + name));
  try { await (routes[name] || dash)(arg ? decodeURIComponent(arg) : null); } catch (e) { if (e.message !== 'login') console.error(e); }
}
window.addEventListener('hashchange', route);
async function boot() {
  ME = await api('/admin/api/me');
  $('#who').textContent = ME.user;
  PARTS = await api('/api/participants');
  route();
  refreshRvc(); setInterval(refreshRvc, 15000);
}
async function refreshRvc() { try { const l = await api('/api/review-queue'); $('#rvc').innerHTML = l.length ? `<span class="badge red">${l.length}</span>` : ''; } catch (e) {} }
async function logout() { await api('/admin/api/logout', {method: 'POST'}); location.href = '/admin/login.html'; }

// ---------------------------------------------------------------------------------------------- dashboard
async function dash() {
  const d = await api('/admin/api/dashboard');
  const led = Object.entries(d.ledgers).map(([k, v]) => `<div class="stat"><span class="muted">${{UNDERTAKING: '保函/备用证', LC: '信用证', COLL: '托收'}[k]}</span><b>${v.total}</b>${Object.entries(v.byStatus).map(([s, n]) => `<span class="badge">${esc(s)} ${n}</span>`).join('')}</div>`).join('');
  M().innerHTML = `<h2>概览 <span class="muted small">营业日 ${esc(d.businessDate)}${d.businessDateOverride ? ' (已覆盖)' : ''}</span></h2>
  <div class="grid">
   <div class="stat"><span class="muted">报文总数</span><b>${d.messages.total}</b><span class="badge red">NAK ${d.messages.nak}</span><span class="badge red">业务拒绝/不一致 ${d.messages.businessRejected}</span></div>
   <div class="stat"><span class="muted">待审批</span><b><a href="#review">${d.reviewPending}</a></b>审批模式机构: ${d.reviewModeParticipants.map(esc).join(', ') || '无'}</div>
   <div class="stat"><span class="muted">计划中的自动回复</span><b>${d.scheduledResponses}</b>机构数 ${d.participants}</div>${led}</div>
  <div class="split" style="margin-top:12px"><div class="card"><h3>FIN 会话 / 序号 (ISN/OSN)</h3><table><tr><th>BIC</th><th>会话</th><th>ISN</th><th>OSN</th></tr>${d.sessions.map(s => `<tr><td>${esc(s.bic)}</td><td>${s.sessionNo}</td><td>${s.lastIsn}</td><td>${s.lastOsn}</td></tr>`).join('')}</table></div>
  <div class="card"><h3>当前激活场景</h3>${Object.entries(d.activeScenarios).map(([g, s]) => `<div><span class="muted">${esc(g)}</span>: ${esc(s)}</div>`).join('')}</div></div>
  <div class="card"><h3>最近报文</h3><table>${MSGHEAD}${d.recent.map(msgRow).join('')}</table></div>`;
}

// ---------------------------------------------------------------------------------------------- messages
let msgFilter = {};
async function msgs(id) {
  if (id) return msgDetail(id);
  const f = msgFilter;
  M().innerHTML = `<h2>报文</h2><div class="card"><div class="row">
   机构 <select id="f_bank">${partOpts(f.bank)}</select> 方向 <select id="f_dir"><option value="">(全部)</option>${['IN', 'OUT', 'INTERNAL', 'SYS'].map(x => `<option ${f.direction === x ? 'selected' : ''}>${x}</option>`).join('')}</select>
   类型 <input id="f_type" size="10" placeholder="MT760 / tsrv" value="${esc(f.type || '')}"> 编号 <input id="f_ref" size="16" value="${esc(f.ref || '')}">
   网关状态 <select id="f_status"><option value="">(全部)</option>${['ACK', 'NAK', 'DELIVERED'].map(x => `<option ${f.status === x ? 'selected' : ''}>${x}</option>`).join('')}</select>
   业务 <select id="f_biz"><option value="">(全部)</option>${[['BIZREJECT', '业务拒绝/不一致'], ['APPLIED', 'APPLIED'], ['PENDING_REVIEW', '待审批'], ['DUPLICATE_IGNORED', '重复忽略']].map(([v, l]) => `<option value="${v}" ${f.business === v ? 'selected' : ''}>${l}</option>`).join('')}</select>
   日期 <input id="f_d1" type="date" value="${esc(f.dateFrom || '')}">–<input id="f_d2" type="date" value="${esc(f.dateTo || '')}">
   <label><input id="f_sys" type="checkbox" ${f.includeSys ? 'checked' : ''}>含 ACK/NAK/DLV</label> <button onclick="msgSearch()">查询</button> <button class="sec" onclick="msgFilter={};msgs()">清空</button></div>
   <div class="scroll"><table id="mt">${MSGHEAD}</table></div><div class="row" style="margin-top:6px"><button class="sec" id="more" style="display:none">更多…</button></div></div>`;
  await msgLoad(false);
}
function msgSearch() {
  msgFilter = {bank: $('#f_bank').value, direction: $('#f_dir').value, type: $('#f_type').value.trim(), ref: $('#f_ref').value.trim(), status: $('#f_status').value,
    business: $('#f_biz').value, dateFrom: $('#f_d1').value, dateTo: $('#f_d2').value, includeSys: $('#f_sys').checked ? 'true' : ''};
  msgLoad(false);
}
async function msgLoad(more) {
  const p = Object.assign({limit: 100}, msgFilter);
  if (more) p.beforeId = more;
  const r = await api('/admin/api/messages?' + q(p));
  const t = $('#mt');
  if (!more) t.innerHTML = MSGHEAD;
  t.insertAdjacentHTML('beforeend', r.items.map(msgRow).join('') || (more ? '' : '<tr><td colspan="10" class="muted">无数据</td></tr>'));
  const b = $('#more'); b.style.display = r.nextBeforeId ? '' : 'none'; b.onclick = () => msgLoad(r.nextBeforeId);
}
async function msgDetail(id) {
  const d = await api('/admin/api/messages/' + id);
  const m = d.message, v = d.validation || {};
  const fields = d.fields ? '<table class="kv">' + Object.entries(d.fields).map(([k, x]) => `<tr><td>${esc(k)}</td><td><pre style="margin:0;background:none;color:inherit;padding:0">${esc(x)}</pre></td></tr>`).join('') + '</table>' : '<div class="muted">系统报文</div>';
  M().innerHTML = `<h2><a href="#msgs">报文</a> / #${m.id} ${esc(m.msgType)} <span class="${esc(m.direction)}">${esc(m.direction)}</span></h2>
  <div class="split"><div><div class="card"><h3>概要</h3>${kv(m)}${d.business ? `<div class="row" style="margin-top:6px">业务: <a href="#ledger/${encodeURIComponent(d.business.ref)}">${esc(d.business.family)} ${esc(d.business.ref)}</a></div>` : ''}
   ${d.inReplyTo ? `<div>回复报文: <a href="#msgs/${d.inReplyTo}">#${d.inReplyTo}</a></div>` : ''}${d.replies && d.replies.length ? '<div>后续报文: ' + d.replies.map(r => `<a href="#msgs/${r.id}">#${r.id} ${esc(r.msgType)} ${esc(r.status)}</a>`).join(', ') + '</div>' : ''}
   <div class="row" style="margin-top:8px"><button class="sec" onclick="reuse(${m.id})">复制到发报页</button></div></div>
   <div class="card"><h3>校验结果 ${v.syntaxValid === false ? '<span class="badge red">语法错误</span>' : v.syntaxValid ? '<span class="badge on">语法通过</span>' : ''}</h3>${findingsTable(v.findings)}</div>
   <div class="card"><h3>解析字段</h3>${fields}</div></div>
  <div><div class="card"><h3>原文 (发送方提交)</h3><pre>${esc(d.raw)}</pre>${d.deliveredRaw ? `<h3>投递给收报方 (输出格式)</h3><pre>${esc(d.deliveredRaw)}</pre>` : ''}</div></div></div>`;
  window._lastRaw = {raw: d.raw, from: PARTS.find(p => p.bic === m.sender)?.id || m.sender, to: PARTS.find(p => p.bic === m.receiver)?.id || m.receiver};
}
function reuse() { window._compose = window._lastRaw; location.hash = '#compose'; }

// ---------------------------------------------------------------------------------------------- ledgers
let ledFam = '';
async function ledger(ref) {
  if (ref) return ledgerDetail(ref);
  const l = await api('/admin/api/business?' + q({family: ledFam}));
  M().innerHTML = `<h2>业务台账</h2><div class="card"><div class="row">${[['', '全部'], ['UNDERTAKING', '保函/备用证'], ['LC', '信用证'], ['COLL', '托收']].map(([v, t]) => `<button class="${ledFam === v ? '' : 'sec'}" onclick="ledFam='${v}';ledger()">${t}</button>`).join('')}
   <input id="lq" placeholder="编号过滤" oninput="ledFilter()"></div><div class="scroll"><table id="lt"><tr><th>类别</th><th>编号</th><th>状态</th><th>币种</th><th>金额</th><th>余额/可用</th><th>到期</th><th>开立行</th><th>参与方</th><th>更新</th></tr>
   ${l.map(b => `<tr class="click" data-ref="${esc(b.ref)}" onclick="location.hash='#ledger/'+encodeURIComponent(this.dataset.ref)"><td>${esc(b.family)}</td><td>${esc(b.ref)}</td><td>${esc(b.status)}</td><td>${esc(b.currency)}</td><td>${esc(b.amount)}</td><td>${esc(b.outstanding)}</td><td>${esc(b.expiryDate)}</td><td>${esc(b.issuerBic)}</td><td class="small">${(b.parties || []).map(esc).join('<br>')}</td><td class="small">${fmtT(b.updatedAt)}</td></tr>`).join('')}</table></div></div>`;
}
function ledFilter() { const v = $('#lq').value.toLowerCase(); document.querySelectorAll('#lt tr[data-ref]').forEach(r => r.style.display = r.dataset.ref.toLowerCase().includes(v) ? '' : 'none'); }
async function ledgerDetail(ref) {
  const [b, r] = await Promise.all([api('/admin/api/business/' + encodeURIComponent(ref)), api('/admin/api/business/' + encodeURIComponent(ref) + '/reconcile')]);
  const items = ['amendments', 'demands', 'presentations', 'movements', 'parties'].filter(k => b[k] && b[k].length).map(k => `<h3>${{amendments: '修改', demands: '索赔', presentations: '交单/索偿', movements: '金额变动', parties: '参与方'}[k]} (${b[k].length})</h3>
    <div class="scroll" style="max-height:260px"><table><tr>${Object.keys(b[k][0]).filter(c => c !== 'id' && !c.endsWith('Id') || c === 'messageId').map(c => `<th>${esc(c)}</th>`).join('')}</tr>${b[k].map(x => `<tr>${Object.keys(b[k][0]).filter(c => c !== 'id' && !c.endsWith('Id') || c === 'messageId').map(c => `<td class="small">${c === 'messageId' && x[c] ? `<a href="#msgs/${x[c]}">#${x[c]}</a>` : esc(x[c])}</td>`).join('')}</tr>`).join('')}</table></div>`).join('');
  const hist = (b.history || []).map(e => `<div><b>${esc(e.eventType)}</b> <span class="muted">${fmtT(e.at)} 营业日 ${esc(e.businessDate)}</span> ${esc(e.fromStatus || '')} → ${esc(e.toStatus || '')} ${e.messageId ? `<a href="#msgs/${e.messageId}">#${e.messageId} ${esc(e.msgType)}</a>` : ''}<br><span class="small">${esc(e.detail)}</span></div>`).join('');
  M().innerHTML = `<h2><a href="#ledger">业务台账</a> / ${esc(b.family)} ${esc(ref)} <span class="badge">${esc(b.status)}</span> ${b.status_zh ? `<span class="muted small">${esc(b.status_zh)}</span>` : ''}</h2>
  <div class="split"><div><div class="card"><h3>核对结果 <span class="badge ${r.summary.ok ? 'on' : 'red'}">${esc(r.summary.text_zh)}</span> <span class="muted small">ERROR ${r.summary.counts.ERROR} / WARN ${r.summary.counts.WARN} / INFO ${r.summary.counts.INFO}</span></h3>${findingsTable(r.findings)}</div><div class="card"><h3>当前状态</h3>${kv(b)}<div class="small" style="margin-top:6px">预期下一步: ${(b.expectedNext || []).map(x => `<span class="badge">${esc(x)}</span>`).join('') || '—'}</div>
   ${b.pendingReview && b.pendingReview.length ? `<div>待审批: ${b.pendingReview.map(i => `<a href="#review/${i}">#${i}</a>`).join(' ')}</div>` : ''}
   <div class="row" style="margin-top:8px"><a href="/admin/api/tools/export?ref=${encodeURIComponent(ref)}"><button class="sec">导出 JSON</button></a></div></div>
   <div class="card">${items || '<span class="muted">无明细</span>'}</div></div>
  <div><div class="card"><h3>事件时间线</h3><div class="tl">${hist || '<span class="muted">无</span>'}</div></div>
   <div class="card"><h3>关联报文</h3><table><tr><th>#</th><th>时间</th><th>方向</th><th>类型</th><th>发→收</th><th>网关</th><th>业务</th></tr>${(b.messages || []).map(m => `<tr class="click" onclick="location.hash='#msgs/${m.id}'"><td>${m.id}</td><td class="small">${fmtT(m.createdAt)}</td><td class="${esc(m.direction)}">${esc(m.direction)}</td><td>${esc(m.msgType)}</td><td class="small">${esc(m.senderBic)}→${esc(m.receiverBic)}</td><td class="st-${esc(m.status)}">${esc(m.status)}</td><td class="b-${esc(m.businessStatus)}">${esc(m.businessStatus)}</td></tr>`).join('')}</table></div></div></div>`;
}

// ---------------------------------------------------------------------------------------------- review queue
let rvStatus = 'PENDING_REVIEW';
async function review(id) {
  if (id) return reviewDetail(id);
  const l = await api('/api/review-queue?' + q({status: rvStatus}));
  M().innerHTML = `<h2>审批队列 <span class="muted small">虚拟机构处于"审批模式"时，收到的报文在此等待人工/AI 决定</span></h2><div class="card"><div class="row">${[['PENDING_REVIEW', '待处理'], ['RESPONDED', '已处理'], ['DISMISSED', '已忽略'], ['ALL', '全部']].map(([v, t]) => `<button class="${rvStatus === v ? '' : 'sec'}" onclick="rvStatus='${v}';review()">${t}</button>`).join('')}</div>
  <table><tr><th>#</th><th>时间</th><th>审批机构</th><th>报文</th><th>编号</th><th>发报行</th><th>台账判断</th><th>建议动作</th><th>状态</th></tr>${l.map(i => `<tr class="click" onclick="location.hash='#review/${i.id}'"><td>${i.id}</td><td class="small">${fmtT(i.createdAt)}</td><td>${esc(i.participantId)}</td><td>#${i.messageId} ${esc(i.msgType)}</td><td>${esc(i.txRef)}</td><td>${esc(i.senderBic)}</td><td class="b-${esc(i.ledgerVerdict)}">${esc(i.ledgerVerdict)}</td><td class="small">${esc(i.suggestedActions)}</td><td>${esc(i.status)} ${esc(i.responseAction || '')}</td></tr>`).join('') || '<tr><td colspan="9" class="muted">无</td></tr>'}</table></div>`;
}
async function reviewDetail(id) {
  const d = await api('/api/review-queue/' + id);
  const it = d.item, m = d.message || {};
  const ctx = d.undertaking || d.documentaryCredit || d.collection;
  const pending = it.status === 'PENDING_REVIEW';
  M().innerHTML = `<h2><a href="#review">审批队列</a> / #${it.id} <span class="badge">${esc(it.status)}</span></h2>
  <div class="split"><div><div class="card"><h3>待审批报文 <a href="#msgs/${it.messageId}">#${it.messageId}</a> ${esc(it.msgType)} (${esc(it.senderBic)} → ${esc(it.participantId)})</h3>
   <div>台账判断: <b class="b-${esc(it.ledgerVerdict)}">${esc(it.ledgerVerdict)}</b></div>${it.ledgerErrors ? `<pre>${esc(it.ledgerErrors)}</pre>` : ''}<pre>${esc(m.raw)}</pre></div>
   ${ctx ? `<div class="card"><h3>业务上下文</h3><div class="scroll" style="max-height:300px">${kv(ctx)}</div></div>` : ''}</div>
  <div><div class="card"><h3>处理决定</h3>${pending ? `
   <div class="row">动作 <select id="rv_a">${d.actions.map(a => `<option>${esc(a)}</option>`).join('')}<option>CUSTOM</option></select>
    金额 <input id="rv_amt" size="12" placeholder="可选"> 新到期日 <input id="rv_exp" type="date"></div>
   <div class="row">理由 (每行一条，拒绝/拒付时使用)</div><textarea id="rv_rs" rows="3"></textarea>
   <div class="row" style="margin-top:6px"><button class="sec" onclick="rvPreview(${it.id})">① 生成草稿预览</button><button onclick="rvRespond(${it.id})">② 按此决定发送</button>
    <button class="warn" onclick="rvDo(${it.id},'BUSINESS_REJECT')">业务拒绝</button><button class="danger" onclick="rvDo(${it.id},'DISMISS')">忽略(不回复)</button></div>
   <h3>草稿 (可编辑后以 CUSTOM 方式发送)</h3><textarea id="rv_raw" rows="16" placeholder="点击“生成草稿预览”"></textarea>
   <div class="row" style="margin-top:6px"><button class="sec" onclick="rvValidate()">校验草稿</button><button onclick="rvCustom(${it.id})">编辑后发送 (CUSTOM)</button></div><div id="rv_out"></div>`
    : `<div>已处理: ${esc(it.responseAction)} ${fmtT(it.respondedAt)}</div><div>发出报文: ${(it.responseMessageIds || '').split(',').filter(Boolean).map(i => `<a href="#msgs/${i}">#${i}</a>`).join(' ')}</div><div class="muted">${esc(it.operatorNote || '')}</div>`}</div></div></div>`;
}
function rvBody(action) {
  const b = {action: action || $('#rv_a').value};
  const rs = $('#rv_rs').value.split('\n').map(s => s.trim()).filter(Boolean); if (rs.length) b.reasons = rs;
  if ($('#rv_amt').value.trim()) b.amount = $('#rv_amt').value.trim();
  if ($('#rv_exp').value) b.newExpiry = $('#rv_exp').value;
  return b;
}
async function rvPreview(id) {
  const r = await api(`/admin/api/review-queue/${id}/preview`, {method: 'POST', body: rvBody()});
  const drafts = r.drafts || [];
  $('#rv_raw').value = drafts.length ? drafts[0].raw || '' : '';
  $('#rv_out').innerHTML = drafts.map((d, i) => `<div class="small">草稿 ${i + 1}: ${esc(d.template)} ${esc(d.from)}→${esc(d.to)} (${esc(d.timing)})${d.error ? ' <span class="sev-ERROR">' + esc(d.error) + '</span>' : ''}</div>${i > 0 ? `<pre>${esc(d.raw)}</pre>` : ''}`).join('') || '<div class="muted">此决定不发送报文</div>';
}
async function rvRespond(id) { if (!confirm('确认以动作 ' + $('#rv_a').value + ' 处理并发送？')) return; const r = await api(`/api/review-queue/${id}/respond`, {method: 'POST', body: rvBody()}); toast('已处理: ' + JSON.stringify(r.sent).slice(0, 300)); review(id); refreshRvc(); }
async function rvDo(id, a) { if (!confirm('确认 ' + a + '？')) return; const r = await api(`/api/review-queue/${id}/respond`, {method: 'POST', body: rvBody(a)}); toast('已处理 ' + a); review(id); refreshRvc(); }
async function rvCustom(id) { const raw = $('#rv_raw').value.trim(); if (!raw) return toast('草稿为空'); if (!confirm('确认发送编辑后的报文？')) return; const r = await api(`/api/review-queue/${id}/respond`, {method: 'POST', body: {action: 'CUSTOM', raw, note: 'edited in admin console'}}); toast('已发送: ' + JSON.stringify(r.sent).slice(0, 300)); review(id); refreshRvc(); }
async function rvValidate() { const r = await api('/admin/api/validate', {method: 'POST', body: {raw: $('#rv_raw').value}}); $('#rv_out').innerHTML = `<div><b>${esc(r.verdict)}</b> ${esc(r.verdict_zh)}</div>` + findingsTable(r.findings); }

// ---------------------------------------------------------------------------------------------- participants
async function parts() {
  PARTS = await api('/api/participants');
  M().innerHTML = `<h2>机构 <span class="muted small">打开"审批模式"后，该虚拟行收到的报文不再自动回复，而是进入审批队列</span></h2><div class="card"><table><tr><th>ID</th><th>名称</th><th>BIC</th><th>角色</th><th>SUT</th><th>Webhook</th><th>审批模式</th></tr>
  ${PARTS.map(p => `<tr><td>${esc(p.id)}</td><td>${esc(p.name)}</td><td>${esc(p.bic)}</td><td>${esc(p.role)}</td><td>${p.sut ? '<span class="badge on">被测系统</span>' : ''}</td><td class="small">${esc(p.webhookUrl || '')}</td>
   <td>${p.sut ? '—' : `<label><input type="checkbox" ${p.reviewMode ? 'checked' : ''} onchange="setRv('${esc(p.id)}',this.checked)"> ${p.reviewMode ? '开' : '关'}</label>`}</td></tr>`).join('')}</table></div>`;
}
async function setRv(id, on) { await api(`/api/participants/${encodeURIComponent(id)}/review-mode`, {method: 'POST', body: {enabled: on}}); toast(id + ' 审批模式 ' + (on ? '开' : '关')); parts(); }

// ---------------------------------------------------------------------------------------------- scenarios
async function scen() {
  const l = await api('/admin/api/scenarios');
  const groups = {};
  l.forEach(s => (groups[s.group || 'default'] = groups[s.group || 'default'] || []).push(s));
  M().innerHTML = `<h2>模拟场景 <span class="muted small">每组一个激活场景决定虚拟行如何回应；带"发起"的场景可由虚拟行主动发起</span></h2>
  <div class="card"><div class="row">发起参数: 格式 <select id="sc_fmt"><option>MT</option><option>MX</option></select> 编号(可选) <input id="sc_ref" size="18"> 发报方 <select id="sc_from">${partOpts('')}</select> 收报方 <select id="sc_to">${partOpts('')}</select>
   <input id="sc_q" placeholder="搜索场景" oninput="scFilter()"></div></div>
  ${Object.entries(groups).map(([g, ss]) => `<div class="card group"><h3>${esc(g)}</h3>${ss.map(s => `<div class="scen ${s.active ? 'active' : ''}" data-q="${esc((s.id + ' ' + s.name + ' ' + (s.description || '')).toLowerCase())}">
   <b>${esc(s.id)}</b> ${esc(s.name)} ${s.active ? '<span class="badge on">激活</span>' : ''}${s.fallback ? '<span class="badge">兜底</span>' : ''} <span class="small muted">响应 ${esc((s.reactsTo || []).join(', '))}</span>
   <span style="float:right">${s.active ? '' : `<button class="sec" onclick="scAct('${esc(s.id)}')">激活</button>`} ${s.hasKickoff ? `<button onclick="scTrig('${esc(s.id)}')">发起</button>` : ''}</span>
   <div class="small muted">${esc(s.description || '')}</div></div>`).join('')}</div>`).join('')}`;
}
function scFilter() { const v = $('#sc_q').value.toLowerCase(); document.querySelectorAll('.scen').forEach(e => e.style.display = e.dataset.q.includes(v) ? '' : 'none'); }
async function scAct(id) { await api(`/api/scenarios/${encodeURIComponent(id)}/activate`, {method: 'POST'}); toast('已激活 ' + id); scen(); }
async function scTrig(id) {
  const r = await api(`/api/scenarios/${encodeURIComponent(id)}/trigger?` + q({format: $('#sc_fmt').value, ref: $('#sc_ref').value.trim(), from: $('#sc_from').value, to: $('#sc_to').value}), {method: 'POST', body: {}});
  toast('已发起 ' + id + ':\n' + (r.scheduled || []).join('\n'));
}

// ---------------------------------------------------------------------------------------------- compose
async function compose() {
  const c = window._compose || {}; window._compose = null;
  const samples = await api('/api/samples').catch(() => []);
  M().innerHTML = `<h2>发报 / 校验 <span class="muted small">以任一机构(被测系统或虚拟行)身份发报，经过正常网关校验、序号、ACK/NAK、投递、台账与场景</span></h2>
  <div class="split"><div class="card"><div class="row">发报方 <select id="c_from">${partOpts(c.from)}</select> 收报方 <select id="c_to">${partOpts(c.to)}</select> MT类型(仅裸 block 4) <input id="c_mt" size="5" placeholder="760"></div>
   <div class="row">样例 <select id="c_s"><option value="">(选择样例)</option>${samples.map(s => `<option>${esc(s)}</option>`).join('')}</select> <button class="sec" onclick="loadSample()">载入</button>
    强制场景 <input id="c_sc" size="16" placeholder="可选"></div>
   <textarea id="c_raw" rows="22" placeholder="完整 MT (FIN) / MX (XML+AppHdr) 报文，或以 : 开头的 MT block 4">${esc(c.raw || '')}</textarea>
   <div class="row" style="margin-top:6px"><button class="sec" onclick="cValidate()">只校验 (不发送)</button><button onclick="cSend()">发送</button></div></div>
  <div class="card"><h3>结果</h3><div id="c_out" class="muted">—</div></div></div>`;
}
async function loadSample() { const n = $('#c_s').value; if (!n) return; $('#c_raw').value = await api('/api/samples/' + encodeURIComponent(n)); }
function cBody() { return {raw: $('#c_raw').value, from: $('#c_from').value, to: $('#c_to').value, mtType: $('#c_mt').value.trim(), scenario: $('#c_sc').value.trim()}; }
async function cValidate() {
  const r = await api('/admin/api/validate', {method: 'POST', body: cBody()});
  $('#c_out').innerHTML = `<div class="row"><b>${esc(r.verdict)}</b> ${esc(r.verdict_zh)}</div>${kv(r, ['format', 'msgType', 'sender', 'receiver', 'direction', 'txRef', 'relatedRef'])}
   ${r.business ? `<div class="small">业务检查: ${esc(JSON.stringify(r.business))}</div>` : ''}<h3>发现</h3>${findingsTable(r.findings)}<div class="small muted">${esc(r.note)}</div>`;
}
async function cSend() {
  if (!confirm('确认发送？(将消耗序号并进入正常业务流程)')) return;
  const r = await api('/admin/api/send', {method: 'POST', body: cBody(), allowError: true});
  $('#c_out').innerHTML = `<div class="row"><b class="st-${esc(r.status)}">${esc(r.status)}</b> ${r.id ? `<a href="#msgs/${r.id}">报文 #${r.id}</a>` : ''}</div>${kv(r)}${(r.errors || []).length ? '<h3>错误</h3><pre>' + esc(r.errors.join('\n')) + '</pre>' : ''}${(r.warnings || []).length ? '<h3>警告</h3><pre>' + esc(r.warnings.join('\n')) + '</pre>' : ''}<pre>${esc(r.ack)}</pre>`;
}

// ---------------------------------------------------------------------------------------------- logs
let logOff = -1;
async function logs() {
  M().innerHTML = `<h2>运行日志</h2><div class="card"><div class="row">级别 <select id="lg_lv"><option value="">全部</option><option>WARN</option><option>ERROR</option></select> 关键字 <input id="lg_q" size="20">
   <button onclick="logReload()">刷新</button> <label><input type="checkbox" id="lg_auto" checked> 自动追踪 (2 秒)</label> <span id="lg_f" class="muted small"></span></div><div class="log" id="lg"></div></div>`;
  $('#lg_lv').onchange = logReload; $('#lg_q').onchange = logReload;
  logOff = -1; await logPoll(true);
  logTimer = setInterval(() => { if ($('#lg_auto') && $('#lg_auto').checked) logPoll(false); }, 2000);
}
function logReload() { logOff = -1; $('#lg').innerHTML = ''; logPoll(true); }
async function logPoll(first) {
  const r = await api('/admin/api/logs?' + q({offset: logOff, level: $('#lg_lv').value, q: $('#lg_q').value}));
  logOff = r.offset; $('#lg_f').textContent = r.file;
  const el = $('#lg'); const atBottom = el.scrollTop + el.clientHeight >= el.scrollHeight - 30;
  el.insertAdjacentHTML('beforeend', (r.lines || []).map(l => `<div class="${l.includes(' ERROR ') ? 'e' : l.includes(' WARN ') ? 'w' : ''}">${esc(l)}</div>`).join(''));
  while (el.childElementCount > 5000) el.firstChild.remove();
  if (first || atBottom) el.scrollTop = el.scrollHeight;
}

// ---------------------------------------------------------------------------------------------- audit
async function audit() {
  const l = await api('/admin/api/audit?limit=500');
  M().innerHTML = `<h2>审计日志 <span class="muted small">管理端的所有修改操作、登录，以及 AI operator 的操作/被拒请求 (${esc(ME.auditLog)})</span></h2><div class="card scroll"><table><tr><th>时间</th><th>操作者</th><th>通道</th><th>方法</th><th>路径</th><th>详情</th><th>状态</th></tr>
  ${l.map(a => `<tr><td class="small">${fmtT(a.ts)}</td><td>${esc(a.actor)}</td><td>${esc(a.channel)}</td><td>${esc(a.method)}</td><td>${esc(a.path)}</td><td class="small">${esc(a.detail || '')}</td><td class="${a.status >= 400 ? 'sev-ERROR' : ''}">${esc(a.status)}</td></tr>`).join('')}</table></div>`;
}

// ---------------------------------------------------------------------------------------------- tools
async function tools() {
  const [bd, bk] = await Promise.all([api('/api/admin/business-date'), api('/admin/api/tools/backups')]);
  M().innerHTML = `<h2>管理工具</h2><div class="split"><div>
  <div class="card"><h3>营业日 / 到期</h3><div class="row">当前营业日 <b>${esc(bd.businessDate)}</b> ${bd.override ? '<span class="badge red">已覆盖</span>' : ''}</div>
   <div class="row"><input type="date" id="t_bd"> <button onclick="setBd()">设置营业日 (并执行到期扫描)</button> <button class="sec" onclick="resetBd()">恢复真实日期</button> <button class="sec" onclick="sweep()">立即到期扫描</button></div></div>
  <div class="card"><h3>配置</h3><div class="row"><button onclick="reloadCfg()">重新加载 config/ (机构、场景、模板)</button></div></div>
  <div class="card"><h3>备份 / 导出</h3><div class="row"><button onclick="backup()">在线备份数据库</button> <a href="/admin/api/tools/export"><button class="sec">导出全部 JSON</button></a></div>
   <table><tr><th>备份文件</th><th>大小</th><th>时间</th></tr>${bk.map(b => `<tr><td><a href="/admin/api/tools/backups/${encodeURIComponent(b.name)}">${esc(b.name)}</a></td><td>${b.bytes}</td><td class="small">${fmtT(b.modified)}</td></tr>`).join('') || '<tr><td colspan="3" class="muted">无</td></tr>'}</table></div>
  <div class="card"><h3>AI 接入</h3><div class="small">AI API: <code>http://127.0.0.1:${ME.ports.ai}/ai/v1</code> · MCP: <code>http://127.0.0.1:${ME.ports.ai}/ai/v1/mcp</code> · OpenAPI: <code>/ai/v1/openapi.json</code></div>
   <div class="small">Key 文件: <code>${esc(ME.aiKeysFile)}</code> (修改后自动生效)</div><table><tr><th>名称</th><th>权限</th><th>前缀</th></tr>${ME.aiKeys.map(k => `<tr><td>${esc(k.name)}</td><td>${esc(k.scope)}</td><td>${esc(k.keyPrefix)}</td></tr>`).join('')}</table>
   <div class="small muted">管理端账号文件: <code>${esc(ME.credentialsFile)}</code></div></div></div>
  <div><div class="card"><h3>清理测试数据 <span class="badge red">危险</span></h3>
   <div class="small muted">删除所选业务编号的台账记录、相关报文、审批项与计划回复。不会修改 FIN 会话 / ISN / OSN。受保护编号 (${ME.protectedRefs.map(esc).join(', ')}) 默认不会被选中，除非在"包含受保护编号"中明确填写。</div>
   <div class="row" style="margin-top:6px">编号 (逗号/空格分隔) <input id="p_refs" size="40"></div><div class="row">或前缀 <input id="p_pre" size="14"> <label><input type="checkbox" id="p_all"> 全部业务</label> <label><input type="checkbox" id="p_nak"> 未关联业务的 NAK 报文</label></div>
   <div class="row">包含受保护编号 <input id="p_prot" size="30" placeholder="通常留空"></div>
   <div class="row"><button class="sec" onclick="purge(true)">① 预览 (dry run)</button></div><div id="p_out"></div></div></div></div>`;
}
async function setBd() { const d = $('#t_bd').value; if (!d) return; const r = await api('/api/admin/business-date', {method: 'POST', body: {date: d}}); toast('营业日 ' + r.businessDate + '，到期 ' + r.expiredNow); tools(); }
async function resetBd() { await api('/api/admin/business-date', {method: 'DELETE'}); tools(); }
async function sweep() { const r = await api('/api/admin/expiry-sweep', {method: 'POST'}); toast('到期处理 ' + r.expired + ' 笔'); }
async function reloadCfg() { const r = await api('/api/admin/reload', {method: 'POST'}); toast('已重新加载: 机构 ' + r.participants + '，场景 ' + r.scenarios); PARTS = await api('/api/participants'); }
async function backup() { const r = await api('/admin/api/tools/backup', {method: 'POST'}); toast('备份完成 ' + r.file); tools(); }
function purgeBody() { return {refs: $('#p_refs').value, prefix: $('#p_pre').value.trim(), all: $('#p_all').checked, nakMessages: $('#p_nak').checked, includeProtected: $('#p_prot').value}; }
async function purge(dry, token) {
  const b = purgeBody(); b.dryRun = dry; if (!dry) b.confirm = token;
  const r = await api('/admin/api/tools/purge', {method: 'POST', body: b});
  if (dry) {
    $('#p_out').innerHTML = `<pre>${esc(JSON.stringify(r, null, 1))}</pre><div class="row">输入确认码 <b>${esc(r.confirmToken)}</b> 以执行: <input id="p_tok" size="12"> <button class="danger" onclick="purgeGo('${esc(r.confirmToken)}')">② 确认删除</button></div>`;
  } else { $('#p_out').innerHTML = `<div class="sev-INFO">已删除</div><pre>${esc(JSON.stringify(r, null, 1))}</pre>`; }
}
function purgeGo(t) { if ($('#p_tok').value.trim() !== t) return toast('确认码不一致'); purge(false, t); }

boot();
