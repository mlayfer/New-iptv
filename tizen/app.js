(() => {
'use strict';

const $ = (s, root=document) => root.querySelector(s);
const $$ = (s, root=document) => Array.from(root.querySelectorAll(s));
const state = {
  sourceTab: 'm3u',
  source: null,
  items: [],
  mode: null,
  group: 'הכל',
  filtered: [],
  current: null,
  focusEl: null,
  lastGroupFocus: 0,
  lastItemFocus: 0,
  remembered: {}
};

function text(el, value){ if(el) el.textContent = value; }
function isVisible(el){
  if(!el) return false;
  const s = getComputedStyle(el);
  if(s.display === 'none' || s.visibility === 'hidden') return false;
  const r = el.getBoundingClientRect();
  return r.width > 0 && r.height > 0;
}
function focusable(el){ return el && isVisible(el) && (el.classList.contains('focusable') || el.dataset.nav); }
function setFocus(el){
  if(!focusable(el)) return;
  if(state.focusEl && state.focusEl !== el) state.focusEl.classList.remove('focused');
  state.focusEl = el;
  el.classList.add('focused');
  try { el.focus({preventScroll:true}); } catch(e) { try { el.focus(); } catch(_) {} }
  try { el.scrollIntoView({block:'nearest', inline:'nearest'}); } catch(e) {}
}
function visible(selector, root=document){ return $$(selector, root).filter(isVisible); }
function saveSource(){ localStorage.setItem('maskhaiSourceV11', JSON.stringify(state.source || null)); }
function loadSavedSource(){
  try {
    const saved = JSON.parse(localStorage.getItem('maskhaiSourceV11') || 'null');
    if(!saved) return;
    state.source = saved;
    if(saved.type === 'm3u') {
      setSourceTab('m3u');
      $('#m3uUrl').value = saved.url || '';
    } else if(saved.type === 'xtream') {
      setSourceTab('xtream');
      $('#xtServer').value = saved.server || '';
      $('#xtUser').value = saved.user || '';
      $('#xtPass').value = saved.pass || '';
      $('#xtVod').checked = saved.includeVod !== false;
    }
  } catch(e) {}
}
function setError(msg=''){ text($('#error'), msg); }
function normalizeServer(v){ let s=(v||'').trim().replace(/\/+$/,''); if(!/^https?:\/\//i.test(s)) s='http://'+s; return s.replace(/\/player_api\.php.*$/i,''); }
function enc(v){ return encodeURIComponent(v); }
async function getText(url){
  const r = await fetch(url, {method:'GET'});
  if(!r.ok) throw new Error('HTTP ' + r.status);
  return await r.text();
}
function attrs(s){
  const out={}; const re=/([\w-]+)\s*=\s*"([^"]*)"/g; let m;
  while((m=re.exec(s))) out[m[1].toLowerCase()] = m[2];
  return out;
}
function looksLikeSeries(name='', group=''){
  const v = (name + ' ' + group).toLowerCase();
  return /(series|serial|episode|season|episodes|סדרה|סדרות|עונה)/i.test(v);
}
function guessKind(url, group='', name=''){
  if(/\/(movie|series)\//i.test(url)) return 'VOD';
  if(/\.(mp4|mkv|avi|mov|m4v|flv|webm)(\?|$)/i.test(url)) return 'VOD';
  if(/(vod|movies?|series|סרט|סרטים|סדרות|סדרה)/i.test(group + ' ' + name)) return 'VOD';
  return 'LIVE';
}
function placeholderText(name=''){
  return (name || '?').trim().slice(0,2).toUpperCase();
}
function parseM3u(textBody){
  const lines = textBody.replace(/\r/g,'').split('\n');
  const out = [];
  let pending = null;
  let extgrp = null;
  for(const raw of lines){
    const line = raw.trim();
    if(!line) continue;
    if(line.startsWith('#EXTINF')){
      let rest=line.substring(line.indexOf(':')+1), q=false, cut=-1;
      for(let i=0;i<rest.length;i++){
        if(rest[i] === '"') q = !q;
        else if(rest[i] === ',' && !q){ cut = i; break; }
      }
      const left = cut >= 0 ? rest.slice(0, cut) : rest;
      const name = cut >= 0 ? rest.slice(cut+1).trim() : '';
      const a = attrs(left);
      pending = {
        name: name || a['tvg-name'] || 'ללא שם',
        group: a['group-title'] || null,
        logo: a['tvg-logo'] || null,
        userAgent: null,
        referrer: null
      };
      extgrp = null;
      continue;
    }
    if(line.startsWith('#EXTGRP')){ extgrp = line.split(':').slice(1).join(':').trim() || null; continue; }
    if(line.startsWith('#EXTVLCOPT') || line.startsWith('#KODIPROP')){
      const val = line.split(':').slice(1).join(':');
      const idx = val.indexOf('=');
      if(idx > 0 && pending){
        const k = val.slice(0, idx).trim().toLowerCase();
        const v = val.slice(idx+1).trim();
        if(k.endsWith('user-agent')) pending.userAgent = v;
        if(k.endsWith('http-referrer') || k.endsWith('referer')) pending.referrer = v;
      }
      continue;
    }
    if(line.startsWith('#')) continue;
    if(/^[a-z][a-z0-9+.-]*:\/\//i.test(line)){
      const base = pending || {name: line};
      const group = base.group || extgrp || 'ללא קטגוריה';
      const kind = guessKind(line, group, base.name);
      out.push({
        id: 'm' + out.length,
        name: base.name,
        group,
        kind,
        contentType: kind === 'LIVE' ? 'LIVE' : (looksLikeSeries(base.name, group) ? 'SERIES' : 'MOVIE'),
        url: line,
        logo: base.logo || null,
        userAgent: base.userAgent || null,
        referrer: base.referrer || null,
      });
      pending = null;
      extgrp = null;
    }
  }
  return out;
}

async function loadM3u(){
  try{
    setError('טוען רשימה...');
    const url = $('#m3uUrl').value.trim();
    if(!url) throw new Error('נא להזין כתובת M3U');
    const parsed = parseM3u(await getText(url));
    if(!parsed.length) throw new Error('לא נמצאו פריטים ברשימה');
    state.source = {type:'m3u', url};
    saveSource();
    finishLoad(parsed);
    setError('');
  }catch(e){ setError('שגיאה: ' + e.message); }
}

async function loadXtream(){
  try{
    setError('מתחבר לשרת...');
    const server = normalizeServer($('#xtServer').value);
    const user = $('#xtUser').value.trim();
    const pass = $('#xtPass').value;
    const includeVod = $('#xtVod').checked;
    if(!server || !user || !pass) throw new Error('חסרים פרטי התחברות');

    const base = `${server}/player_api.php?username=${enc(user)}&password=${enc(pass)}`;
    const account = JSON.parse(await getText(base));
    if(account.user_info && String(account.user_info.auth) === '0') throw new Error('שם משתמש או סיסמה שגויים');

    const items = [];

    const liveCats = {};
    try { (JSON.parse(await getText(base + '&action=get_live_categories')) || []).forEach(x => liveCats[String(x.category_id)] = x.category_name); } catch(e) {}
    const live = JSON.parse(await getText(base + '&action=get_live_streams')) || [];
    live.forEach(x => {
      const id = String(x.stream_id || ''); if(!id) return;
      items.push({
        id: 'l' + id,
        name: x.name || ('ערוץ ' + id),
        group: liveCats[String(x.category_id)] || 'ערוצים',
        kind: 'LIVE',
        contentType: 'LIVE',
        url: `${server}/live/${enc(user)}/${enc(pass)}/${id}.m3u8`,
        logo: x.stream_icon || null
      });
    });

    if(includeVod){
      const vodCats = {};
      try { (JSON.parse(await getText(base + '&action=get_vod_categories')) || []).forEach(x => vodCats[String(x.category_id)] = x.category_name); } catch(e) {}
      try {
        const vod = JSON.parse(await getText(base + '&action=get_vod_streams')) || [];
        vod.forEach(x => {
          const id = String(x.stream_id || ''); if(!id) return;
          const ext = x.container_extension || 'mp4';
          items.push({
            id: 'v' + id,
            name: x.name || ('סרט ' + id),
            group: vodCats[String(x.category_id)] || 'סרטים',
            kind: 'VOD',
            contentType: looksLikeSeries(x.name, vodCats[String(x.category_id)] || '') ? 'SERIES' : 'MOVIE',
            url: `${server}/movie/${enc(user)}/${enc(pass)}/${id}.${ext}`,
            logo: x.stream_icon || null
          });
        });
      } catch(e) {}

      const seriesCats = {};
      try { (JSON.parse(await getText(base + '&action=get_series_categories')) || []).forEach(x => seriesCats[String(x.category_id)] = x.category_name); } catch(e) {}
      try {
        const series = JSON.parse(await getText(base + '&action=get_series')) || [];
        series.forEach(x => {
          const sid = String(x.series_id || x.stream_id || ''); if(!sid) return;
          items.push({
            id: 's' + sid,
            name: x.name || ('סדרה ' + sid),
            group: seriesCats[String(x.category_id)] || 'סדרות',
            kind: 'VOD',
            contentType: 'SERIES',
            url: '',
            logo: x.cover || x.stream_icon || null,
            seriesId: sid,
            isSeriesStub: true,
            server, user, pass
          });
        });
      } catch(e) {}
    }

    if(!items.length) throw new Error('השרת לא החזיר תוכן');
    state.source = {type:'xtream', server, user, pass, includeVod};
    saveSource();
    finishLoad(items);
    setError('');
  }catch(e){ setError('שגיאה: ' + e.message); }
}

function finishLoad(items){
  state.items = items;
  state.current = null;
  state.mode = null;
  state.group = 'הכל';
  $('#setupScreen').classList.add('hidden');
  $('#browseScreen').classList.remove('hidden');
  $('#homeScreen').classList.remove('hidden');
  $('#browserScreen').classList.add('hidden');
  $('#goHome').classList.add('hidden');
  $('#backToSetup').classList.remove('hidden');
  text($('#itemName'), 'בחר פריט');
  text($('#itemMeta'), 'בחר ערוץ, סרט או סדרה כדי להתחיל');
  playerMessage('בחר טלוויזיה בלייב או סרטים וסדרות');
  stopPlayback();
  setTimeout(() => setFocus($('#cardLive')), 60);
}

function currentPool(){
  if(state.mode === 'LIVE') return state.items.filter(x => x.kind === 'LIVE');
  if(state.mode === 'VOD') return state.items.filter(x => x.kind !== 'LIVE');
  return [];
}

function groupList(){
  const pool = currentPool();
  const counts = {};
  pool.forEach(x => counts[x.group] = (counts[x.group] || 0) + 1);
  return ['הכל', ...Object.keys(counts).sort((a,b) => counts[b] - counts[a])];
}

function renderGroups(){
  const box = $('#groups');
  box.innerHTML = '';
  groupList().forEach((g, idx) => {
    const btn = document.createElement('button');
    btn.className = 'focusable groupChip' + (g === state.group ? ' active' : '');
    btn.dataset.nav = 'group';
    btn.dataset.index = String(idx);
    btn.textContent = g;
    btn.addEventListener('click', () => {
      state.group = g;
      state.lastGroupFocus = idx;
      renderGroups();
      applyFilter();
      const first = visible('[data-nav="item"]')[0] || visible('[data-nav="group"]')[idx] || $('#search');
      setFocus(first);
    });
    box.appendChild(btn);
  });
}

function posterHtml(item){
  if(item.logo) return `<div class="poster"><img src="${item.logo}" alt=""/></div>`;
  return `<div class="poster">${placeholderText(item.name)}</div>`;
}

function itemMeta(item){
  if(item.kind === 'LIVE') return 'שידור חי · ' + item.group;
  if(item.contentType === 'SERIES') return 'סדרה · ' + item.group;
  return 'סרט / VOD · ' + item.group;
}

function applyFilter(){
  const q = ($('#search').value || '').trim().toLowerCase();
  const pool = currentPool();
  state.filtered = pool.filter(item => {
    const matchGroup = state.group === 'הכל' || item.group === state.group;
    const matchText = !q || item.name.toLowerCase().includes(q) || item.group.toLowerCase().includes(q);
    return matchGroup && matchText;
  });
  renderItems();
}

function renderItems(){
  const box = $('#items');
  box.innerHTML = '';
  if(!state.filtered.length){
    const empty = document.createElement('div');
    empty.className = 'itemCard';
    empty.innerHTML = `<div class="itemText"><div class="itemCardTitle">לא נמצאו תוצאות</div><div class="itemCardMeta">נסה לחפש משהו אחר או לעבור קטגוריה</div></div>`;
    box.appendChild(empty);
    return;
  }
  state.filtered.forEach((item, idx) => {
    const btn = document.createElement('button');
    btn.className = 'focusable itemCard' + (state.current && state.current.id === item.id ? ' playing' : '');
    btn.dataset.nav = 'item';
    btn.dataset.index = String(idx);
    btn.innerHTML = `${posterHtml(item)}<div class="itemText"><div class="itemCardTitle"></div><div class="itemCardMeta"></div></div>`;
    $('.itemCardTitle', btn).textContent = item.name;
    $('.itemCardMeta', btn).textContent = itemMeta(item);
    btn.addEventListener('click', async () => {
      state.lastItemFocus = idx;
      await activateItem(item);
      renderItems();
      const refocus = visible('[data-nav="item"]')[Math.min(idx, visible('[data-nav="item"]').length - 1)] || btn;
      setFocus(refocus);
    });
    box.appendChild(btn);
  });
}

function showMode(mode){
  state.mode = mode;
  state.group = 'הכל';
  state.lastGroupFocus = 0;
  state.lastItemFocus = 0;
  $('#homeScreen').classList.add('hidden');
  $('#browserScreen').classList.remove('hidden');
  $('#goHome').classList.remove('hidden');
  text($('#modeHeader'), mode === 'LIVE' ? 'טלוויזיה בלייב' : 'סרטים וסדרות');
  $('#search').value = '';
  renderGroups();
  applyFilter();
  setTimeout(() => setFocus($('#search')), 80);
}

function backToHome(){
  $('#browserScreen').classList.add('hidden');
  $('#homeScreen').classList.remove('hidden');
  $('#goHome').classList.add('hidden');
  state.mode = null;
  state.group = 'הכל';
  playerMessage('בחר טלוויזיה בלייב או סרטים וסדרות');
  setTimeout(() => setFocus($('#cardLive')), 60);
}

function resetToSetup(){
  stopPlayback();
  $('#browseScreen').classList.add('hidden');
  $('#setupScreen').classList.remove('hidden');
  $('#goHome').classList.add('hidden');
  $('#backToSetup').classList.add('hidden');
  setTimeout(() => setFocus(visible('[data-nav="setupTab"]')[0] || $('#m3uUrl')), 60);
}

function playerMessage(msg){
  const el = $('#playerMessage');
  text(el, msg || '');
  el.style.display = msg ? 'flex' : 'none';
}

function stopPlayback(){
  try {
    if(window.webapis && webapis.avplay){
      const s = webapis.avplay.getState();
      if(s !== 'NONE') webapis.avplay.stop();
    }
  } catch(e) {}
  const v = $('#htmlVideo');
  try { v.pause(); v.removeAttribute('src'); v.load(); } catch(e) {}
}

async function resolveSeriesEpisode(item){
  if(!item.isSeriesStub) return item;
  try {
    const api = `${item.server}/player_api.php?username=${enc(item.user)}&password=${enc(item.pass)}&action=get_series_info&series_id=${enc(item.seriesId)}`;
    const info = JSON.parse(await getText(api));
    const episodes = info.episodes || {};
    const seasons = Object.keys(episodes).sort((a,b) => Number(a) - Number(b));
    for(const season of seasons){
      const eps = episodes[season] || [];
      if(eps.length){
        const ep = eps[0];
        const ext = ep.container_extension || 'mp4';
        return Object.assign({}, item, {
          name: `${item.name} · פרק 1`,
          url: `${item.server}/series/${enc(item.user)}/${enc(item.pass)}/${ep.id}.${ext}`,
          isSeriesStub: false
        });
      }
    }
    throw new Error('לא נמצאו פרקים לניגון');
  } catch(e) {
    throw new Error('שגיאה בטעינת סדרה');
  }
}

function playHtml(url){
  const v = $('#htmlVideo');
  $('#avPlayer').style.display = 'none';
  v.style.display = 'block';
  v.src = url;
  v.play().catch(() => playerMessage('הנגן המובנה לא הצליח לנגן את התוכן'));
}

async function activateItem(item){
  try {
    let target = item;
    if(item.isSeriesStub) target = await resolveSeriesEpisode(item);
    if(!target.url) throw new Error('אין כתובת ניגון לפריט זה');
    state.current = target;
    text($('#itemName'), target.name);
    text($('#itemMeta'), itemMeta(target));
    playerMessage('טוען...');
    stopPlayback();

    if(window.webapis && webapis.avplay){
      $('#htmlVideo').style.display = 'none';
      $('#avPlayer').style.display = 'block';
      webapis.avplay.open(target.url);
      try { if(target.userAgent) webapis.avplay.setStreamingProperty('USER_AGENT', target.userAgent); } catch(e) {}
      try { if(target.referrer) webapis.avplay.setStreamingProperty('REFERER', target.referrer); } catch(e) {}
      webapis.avplay.setDisplayRect(594, 152, 1224, 682);
      webapis.avplay.setListener({
        onbufferingstart: () => playerMessage('טוען...'),
        onbufferingcomplete: () => playerMessage(''),
        onstreamcompleted: () => playerMessage('ההפעלה הסתיימה'),
        onerror: err => playerMessage('שגיאת ניגון: ' + err),
        onevent: () => {}, oncurrentplaytime: () => {}, ondrmevent: () => {}
      });
      webapis.avplay.prepareAsync(() => { playerMessage(''); webapis.avplay.play(); }, err => {
        playerMessage('שגיאת הכנה: ' + err);
      });
    } else {
      playHtml(target.url);
    }
  } catch(e) {
    playerMessage(e.message || 'שגיאה בניגון');
  }
}

function setSourceTab(type){
  state.sourceTab = type;
  visible('.sourceTab').forEach(btn => btn.classList.toggle('active', btn.dataset.source === type));
  $('#m3uForm').classList.toggle('hidden', type !== 'm3u');
  $('#xtreamForm').classList.toggle('hidden', type !== 'xtream');
}

function moveRtlRow(list, active, dir){
  const idx = list.indexOf(active);
  if(idx < 0) return null;
  const delta = dir === 'left' ? 1 : -1;
  const next = idx + delta;
  return list[next] || null;
}

function navSetup(active, dir){
  const tabs = visible('[data-nav="setupTab"]');
  const fields = visible('[data-nav="setupField"]');
  const type = active?.dataset.nav;
  if(type === 'setupTab'){
    if(dir === 'left' || dir === 'right') return moveRtlRow(tabs, active, dir) || active;
    if(dir === 'down') return fields[0] || active;
    return active;
  }
  if(type === 'setupField'){
    const idx = fields.indexOf(active);
    if(dir === 'up') return idx <= 0 ? tabs[0] || active : fields[idx-1];
    if(dir === 'down') return fields[idx+1] || active;
    return active;
  }
  return tabs[0] || fields[0] || active;
}

function navHome(active, dir){
  const actions = visible('[data-nav="topAction"]');
  const cards = visible('[data-nav="homeCard"]');
  const type = active?.dataset.nav;
  if(type === 'topAction'){
    if(dir === 'left' || dir === 'right') return moveRtlRow(actions, active, dir) || active;
    if(dir === 'down') return cards[0] || active;
    return active;
  }
  if(type === 'homeCard'){
    if(dir === 'left' || dir === 'right') return moveRtlRow(cards, active, dir) || active;
    if(dir === 'up') return actions[actions.length - 1] || active;
    return active;
  }
  return cards[0] || actions[0] || active;
}

function navBrowser(active, dir){
  const actions = visible('[data-nav="topAction"]');
  const search = $('#search');
  const groups = visible('[data-nav="group"]');
  const items = visible('[data-nav="item"]');
  const type = active?.dataset.nav;

  if(type === 'topAction'){
    if(dir === 'left' || dir === 'right') return moveRtlRow(actions, active, dir) || active;
    if(dir === 'down') return search;
    return active;
  }
  if(type === 'search'){
    if(dir === 'up') return actions[actions.length - 1] || active;
    if(dir === 'down') return groups[0] || items[0] || active;
    return active;
  }
  if(type === 'group'){
    const moved = (dir === 'left' || dir === 'right') ? moveRtlRow(groups, active, dir) : null;
    if(moved) return moved;
    if(dir === 'down') return items[0] || active;
    if(dir === 'up') return search;
    return active;
  }
  if(type === 'item'){
    const idx = items.indexOf(active);
    if(dir === 'down') return items[idx + 1] || active;
    if(dir === 'up') return idx <= 0 ? (groups[state.lastGroupFocus] || search) : items[idx - 1];
    return active;
  }
  return search || groups[0] || items[0] || active;
}

function currentNavMode(){
  if(!$('#setupScreen').classList.contains('hidden')) return 'setup';
  if(!$('#browseScreen').classList.contains('hidden') && !$('#homeScreen').classList.contains('hidden')) return 'home';
  return 'browser';
}

function moveFocus(dir){
  const active = state.focusEl || document.activeElement;
  let next = null;
  const mode = currentNavMode();
  if(mode === 'setup') next = navSetup(active, dir);
  else if(mode === 'home') next = navHome(active, dir);
  else next = navBrowser(active, dir);
  if(next) setFocus(next);
}

function onEnter(){
  const a = state.focusEl || document.activeElement;
  if(!a) return;
  if(a.tagName === 'BUTTON') { a.click(); return; }
  if(a.tagName === 'INPUT') {
    try { a.focus(); } catch(e) {}
  }
}

function handleBack(){
  if(!$('#setupScreen').classList.contains('hidden')) return;
  if(!$('#browseScreen').classList.contains('hidden') && !$('#homeScreen').classList.contains('hidden')) {
    resetToSetup();
    return;
  }
  if(!$('#browserScreen').classList.contains('hidden')) {
    backToHome();
  }
}

function wireStatic(){
  visible('.sourceTab').forEach(btn => {
    btn.dataset.nav = 'setupTab';
    btn.addEventListener('click', () => {
      setSourceTab(btn.dataset.source);
      setTimeout(() => {
        const target = btn.dataset.source === 'm3u' ? $('#m3uUrl') : $('#xtServer');
        setFocus(target);
      }, 30);
    });
  });

  ['#m3uUrl','#loadM3u','#xtServer','#xtUser','#xtPass','#loadXtream'].forEach(sel => {
    const el = $(sel); if(el) el.dataset.nav = 'setupField';
  });

  $('#search').dataset.nav = 'search';
  $('#goHome').dataset.nav = 'topAction';
  $('#backToSetup').dataset.nav = 'topAction';
  $('#cardLive').dataset.nav = 'homeCard';
  $('#cardVod').dataset.nav = 'homeCard';

  $$('.serverSuggestion').forEach(btn => {
    btn.dataset.nav = 'setupField';
    btn.addEventListener('click', () => {
      $('#xtServer').value = btn.dataset.server || '';
      setFocus($('#xtUser'));
    });
  });

  $('#xtServer').addEventListener('input', () => {
    const q = $('#xtServer').value.trim().toLowerCase();
    $$('.serverSuggestion').forEach(btn => {
      const value = (btn.dataset.server || '').toLowerCase();
      btn.classList.toggle('hidden', !!q && !value.includes(q));
    });
  });

  $('#loadM3u').addEventListener('click', loadM3u);
  $('#loadXtream').addEventListener('click', loadXtream);
  $('#search').addEventListener('input', applyFilter);
  $('#cardLive').addEventListener('click', () => showMode('LIVE'));
  $('#cardVod').addEventListener('click', () => showMode('VOD'));
  $('#goHome').addEventListener('click', backToHome);
  $('#backToSetup').addEventListener('click', resetToSetup);

  document.addEventListener('keydown', (e) => {
    const code = e.keyCode;
    if(e.key === 'ArrowLeft' || code === 37){ moveFocus('left'); e.preventDefault(); return; }
    if(e.key === 'ArrowRight' || code === 39){ moveFocus('right'); e.preventDefault(); return; }
    if(e.key === 'ArrowUp' || code === 38){ moveFocus('up'); e.preventDefault(); return; }
    if(e.key === 'ArrowDown' || code === 40){ moveFocus('down'); e.preventDefault(); return; }
    if(e.key === 'Enter' || code === 13){ onEnter(); e.preventDefault(); return; }
    if(e.key === 'Backspace' || code === 10009){ handleBack(); e.preventDefault(); return; }
    if(state.current && (code === 427 || code === 33)){
      const idx = state.filtered.findIndex(x => state.current && x.id === state.current.id);
      if(idx >= 0 && idx < state.filtered.length - 1) activateItem(state.filtered[idx + 1]);
      e.preventDefault();
      return;
    }
    if(state.current && (code === 428 || code === 34)){
      const idx = state.filtered.findIndex(x => state.current && x.id === state.current.id);
      if(idx > 0) activateItem(state.filtered[idx - 1]);
      e.preventDefault();
    }
  });
}

function registerKeys(){
  try {
    if(window.tizen && tizen.tvinputdevice){
      ['MediaPlayPause','MediaStop','ChannelUp','ChannelDown'].forEach(k => { try { tizen.tvinputdevice.registerKey(k); } catch(e) {} });
    }
  } catch(e) {}
}

wireStatic();
registerKeys();
loadSavedSource();
setSourceTab(state.sourceTab);
setTimeout(() => setFocus(visible('[data-nav="setupTab"]')[0] || $('#m3uUrl')), 150);
setTimeout(() => { const sp = document.getElementById('splashScreen'); if (sp) { sp.classList.add('hiddenSplash'); setTimeout(() => { sp.style.display='none'; }, 420); } }, 1300);


})();
