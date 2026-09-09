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
  remembered: {},
  // Set while a series is open; its episodes replace the browsing pool.
  episodeContext: null,
  // The endpoint ladder for whatever is playing, and how far down it we are.
  candidates: [],
  candidateIndex: 0,
  // Catalogues the portal refused, shown instead of quietly missing.
  notes: [],
  // Windowed rendering: which item has focus, and where each window starts.
  liveIndex: 0,
  liveStart: 0,
  vodRow: 0,
  vodCol: 0,
  vodRowStart: 0,
  vodData: []
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
function normalizeServer(v){ return Core.normalizeServer(v); }
function enc(v){ return encodeURIComponent(v); }
async function getText(url){
  const r = await fetch(url, {method:'GET'});
  if(!r.ok) throw new Error('HTTP ' + r.status);
  return await r.text();
}
// Parsing, kind detection, endpoint variants and episode building all live in
// core.js, which the Kotlin app mirrors and the parity tests check against the
// same fixtures. Nothing below may reimplement them.
const Core = (typeof window !== 'undefined' ? window : globalThis).TalohimCore;
const attrs = Core.attrs;
const looksLikeSeries = Core.looksLikeSeries;
const guessKind = Core.guessKind;
const placeholderText = Core.placeholderText;
const parseM3u = Core.parseM3u;
const streamVariants = Core.streamVariants;
const episodesFromSeriesInfo = Core.episodesFromSeriesInfo;

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
    const notes = [];

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
      } catch(e) { notes.push('ספריית הסרטים לא נטענה: ' + (e.message || 'שגיאה')); }

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
      } catch(e) { notes.push('רשימת הסדרות לא נטענה: ' + (e.message || 'שגיאה')); }
    }

    if(!items.length) throw new Error('השרת לא החזיר תוכן');
    state.notes = notes;
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
  $('#liveScreen').classList.add('hidden');
  $('#vodScreen').classList.add('hidden');
  $('#goHome').classList.add('hidden');
  $('#backToSetup').classList.remove('hidden');
  text($('#itemName'), 'בחר ערוץ');
  text($('#itemMeta'), 'רשימת הערוצים מימין · אישור מפעיל');
  playerMessage('בחר ערוץ מהרשימה');
  stopPlayback();
  setTimeout(() => setFocus($('#cardLive')), 60);
}

// A TV renders a few dozen elements comfortably and tens of thousands not at
// all: building 19,000 rows with 19,000 logos freezes the app before the screen
// even repaints. Everything below draws a window around the focused item and
// moves that window, so the DOM stays small no matter how large the playlist.
const LIVE_WINDOW = 24;
const VOD_ROW_WINDOW = 3;
const VOD_COL_WINDOW = 12;

function currentPool(){
  if(state.episodeContext) return state.episodeContext.episodes;
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

function renderNotes(){
  const box = $('#notes');
  if(!box) return;
  const notes = state.notes || [];
  box.textContent = notes.join(' · ');
  box.style.display = notes.length ? 'block' : 'none';
}

function renderGroups(){
  const box = $('#groups');
  if(!box) return;
  box.innerHTML = '';
  groupList().slice(0, 40).forEach((g, idx) => {
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
      setFocus(visible('[data-nav="item"]')[0] || visible('[data-nav="group"]')[idx] || $('#search'));
    });
    box.appendChild(btn);
  });
}

function itemMeta(item){
  if(item.kind === 'LIVE') return 'שידור חי · ' + item.group;
  if(item.contentType === 'SERIES') return 'סדרה · ' + item.group;
  if(item.contentType === 'EPISODE') return item.group;
  return 'סרט · ' + item.group;
}

function searchText(){
  const box = state.mode === 'VOD' ? $('#vodSearch') : $('#search');
  return ((box && box.value) || '').trim().toLowerCase();
}

function applyFilter(){
  const q = searchText();
  const pool = currentPool();
  state.filtered = pool.filter(item => {
    const matchGroup = state.mode === 'VOD' || state.group === 'הכל' || item.group === state.group;
    const matchText = !q || item.name.toLowerCase().includes(q) || item.group.toLowerCase().includes(q);
    return matchGroup && matchText;
  });
  state.liveIndex = 0;
  state.liveStart = 0;
  state.vodRow = 0;
  state.vodCol = 0;
  state.vodRowStart = 0;
  if(state.mode === 'VOD') renderVodRows(); else renderItems();
}

// ---- Live TV ----------------------------------------------------------------

function renderItems(){
  const box = $('#items');
  if(!box) return;
  box.innerHTML = '';

  const count = $('#liveCount');
  if(count) count.textContent = state.filtered.length ? state.filtered.length + ' ערוצים' : '';

  if(!state.filtered.length){
    const empty = document.createElement('div');
    empty.className = 'channelRow';
    empty.innerHTML = '<div class="channelText"><div class="channelName">לא נמצאו ערוצים</div><div class="channelMeta">נסה חיפוש אחר או קטגוריה אחרת</div></div>';
    box.appendChild(empty);
    return;
  }

  const start = state.liveStart;
  state.filtered.slice(start, start + LIVE_WINDOW).forEach((item, offset) => {
    const index = start + offset;
    const btn = document.createElement('button');
    btn.className = 'focusable channelRow' + (state.current && state.current.id === item.id ? ' playing' : '');
    btn.dataset.nav = 'item';
    btn.dataset.index = String(index);
    btn.innerHTML = '<div class="channelNumber"></div>' +
      '<div class="channelLogo"></div>' +
      '<div class="channelText"><div class="channelName"></div><div class="channelMeta"></div></div>';
    $('.channelNumber', btn).textContent = String(index + 1);
    $('.channelName', btn).textContent = item.name;
    $('.channelMeta', btn).textContent = itemMeta(item);
    const logo = $('.channelLogo', btn);
    if(item.logo){
      const img = document.createElement('img');
      img.src = item.logo;
      img.alt = '';
      img.onerror = () => { logo.textContent = placeholderText(item.name); img.remove(); };
      logo.appendChild(img);
    } else {
      logo.textContent = placeholderText(item.name);
    }
    btn.addEventListener('click', () => {
      state.liveIndex = index;
      activateItem(item);
      renderItems();
      focusLive();
    });
    box.appendChild(btn);
  });
}

/** Keeps the focused channel inside the rendered window, scrolling it if needed. */
function focusLive(){
  const index = Math.max(0, Math.min(state.liveIndex, state.filtered.length - 1));
  state.liveIndex = index;
  if(index < state.liveStart || index >= state.liveStart + LIVE_WINDOW){
    state.liveStart = Math.max(0, index - Math.floor(LIVE_WINDOW / 2));
    renderItems();
  }
  const el = $('[data-nav="item"][data-index="' + index + '"]');
  if(el) setFocus(el);
}

function moveLive(delta){
  if(!state.filtered.length) return;
  state.liveIndex = Math.max(0, Math.min(state.liveIndex + delta, state.filtered.length - 1));
  focusLive();
}

// ---- Movies and series ------------------------------------------------------

function vodGroups(){
  const rows = [];
  const byGroup = {};
  state.filtered.forEach(item => {
    if(!byGroup[item.group]){ byGroup[item.group] = []; rows.push({name: item.group, items: byGroup[item.group]}); }
    byGroup[item.group].push(item);
  });
  return rows;
}

function renderVodRows(){
  const box = $('#vodRows');
  if(!box) return;
  box.innerHTML = '';
  state.vodData = vodGroups();

  const count = $('#vodCount');
  if(count) count.textContent = state.filtered.length ? state.filtered.length + ' פריטים' : '';

  if(!state.vodData.length){
    const empty = document.createElement('div');
    empty.className = 'vodRowTitle';
    empty.textContent = 'לא נמצאו פריטים';
    box.appendChild(empty);
    return;
  }

  state.vodData.slice(state.vodRowStart, state.vodRowStart + VOD_ROW_WINDOW).forEach((row, offset) => {
    const rowIndex = state.vodRowStart + offset;
    const wrap = document.createElement('div');
    wrap.className = 'vodRow';
    const title = document.createElement('div');
    title.className = 'vodRowTitle';
    title.textContent = row.name + ' · ' + row.items.length;
    const track = document.createElement('div');
    track.className = 'vodRowTrack';

    const colStart = rowIndex === state.vodRow ? Math.max(0, state.vodCol - 2) : 0;
    row.items.slice(colStart, colStart + VOD_COL_WINDOW).forEach((item, colOffset) => {
      const colIndex = colStart + colOffset;
      const card = document.createElement('button');
      card.className = 'focusable vodCard' + (state.current && state.current.id === item.id ? ' playing' : '');
      card.dataset.nav = 'vodCard';
      card.dataset.row = String(rowIndex);
      card.dataset.col = String(colIndex);
      card.innerHTML = '<div class="vodPoster"></div><div class="vodCardTitle"></div>';
      $('.vodCardTitle', card).textContent = item.name;
      const poster = $('.vodPoster', card);
      if(item.logo){
        const img = document.createElement('img');
        img.src = item.logo;
        img.alt = '';
        img.onerror = () => { poster.textContent = placeholderText(item.name); img.remove(); };
        poster.appendChild(img);
      } else {
        poster.textContent = placeholderText(item.name);
      }
      card.addEventListener('click', () => {
        state.vodRow = rowIndex;
        state.vodCol = colIndex;
        activateItem(item);
      });
      track.appendChild(card);
    });

    wrap.appendChild(title);
    wrap.appendChild(track);
    box.appendChild(wrap);
  });
}

function vodItemAt(row, col){
  const data = state.vodData || [];
  const r = data[row];
  return r ? r.items[col] : null;
}

function describeVod(item){
  if(!item) return;
  text($('#vodHeroTitle'), item.name);
  text($('#vodHeroMeta'), itemMeta(item));
}

function focusVod(){
  const data = state.vodData || [];
  if(!data.length) return;
  state.vodRow = Math.max(0, Math.min(state.vodRow, data.length - 1));
  const row = data[state.vodRow];
  state.vodCol = Math.max(0, Math.min(state.vodCol, row.items.length - 1));

  // Keep the focused row as the second one on screen, the way a TV grid scrolls.
  state.vodRowStart = Math.max(0, Math.min(state.vodRow - 1, Math.max(0, data.length - VOD_ROW_WINDOW)));
  renderVodRows();
  describeVod(vodItemAt(state.vodRow, state.vodCol));

  const el = $('[data-nav="vodCard"][data-row="' + state.vodRow + '"][data-col="' + state.vodCol + '"]');
  if(el) setFocus(el);
}

function moveVod(dRow, dCol){
  const data = state.vodData || [];
  if(!data.length) return;
  if(dRow){
    state.vodRow = Math.max(0, Math.min(state.vodRow + dRow, data.length - 1));
    state.vodCol = 0;
  }
  if(dCol) state.vodCol = Math.max(0, state.vodCol + dCol);
  focusVod();
}

// ---- Screens ----------------------------------------------------------------

function showMode(mode){
  state.mode = mode;
  state.group = 'הכל';
  state.lastGroupFocus = 0;
  state.episodeContext = null;
  $('#homeScreen').classList.add('hidden');
  $('#goHome').classList.remove('hidden');
  renderNotes();

  if(mode === 'LIVE'){
    $('#vodScreen').classList.add('hidden');
    $('#liveScreen').classList.remove('hidden');
    text($('#modeHeader'), 'טלוויזיה בלייב');
    $('#search').value = '';
    renderGroups();
    applyFilter();
    setTimeout(() => setFocus(visible('[data-nav="item"]')[0] || $('#search')), 60);
    return;
  }

  $('#liveScreen').classList.add('hidden');
  $('#vodScreen').classList.remove('hidden');
  text($('#vodHeroTitle'), 'סרטים וסדרות');
  text($('#vodHeroMeta'), 'בחר שורה ופריט');
  $('#vodSearch').value = '';
  applyFilter();
  setTimeout(() => { focusVod(); }, 60);
}

function closeSeries(){
  state.episodeContext = null;
  text($('#vodHeroTitle'), 'סרטים וסדרות');
  text($('#vodHeroMeta'), 'בחר שורה ופריט');
  applyFilter();
  focusVod();
}

function backToHome(){
  state.episodeContext = null;
  state.mode = null;
  state.current = null;
  stopPlayback();
  $('#liveScreen').classList.add('hidden');
  $('#vodScreen').classList.add('hidden');
  $('#homeScreen').classList.remove('hidden');
  $('#goHome').classList.add('hidden');
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

/**
 * Opening a series shows its episodes, the way the Android app does — the
 * seasons become the category strip, so the existing navigation keeps working.
 */
async function openSeries(item){
  text($('#vodHeroTitle'), item.name);
  text($('#vodHeroMeta'), 'טוען פרקים...');
  try{
    const api = `${item.server}/player_api.php?username=${enc(item.user)}&password=${enc(item.pass)}&action=get_series_info&series_id=${enc(item.seriesId)}`;
    const info = JSON.parse(await getText(api));
    const episodes = episodesFromSeriesInfo(info, {
      server: item.server, user: item.user, pass: item.pass, logo: item.logo
    });
    if(!episodes.length) throw new Error('לא נמצאו פרקים לסדרה הזו');

    // The seasons become the rows, so the same grid renders the episodes.
    state.episodeContext = { series: item, episodes };
    text($('#vodHeroMeta'), episodes.length + ' פרקים · חזרה עם Back');
    applyFilter();
    focusVod();
  }catch(e){
    text($('#vodHeroMeta'), e.message || 'שגיאה בטעינת סדרה');
  }
}

function playHtml(url){
  const v = $('#htmlVideo');
  $('#avPlayer').style.display = 'none';
  v.style.display = 'block';
  v.onerror = () => nextCandidate('הנגן המובנה לא הצליח לנגן את התוכן');
  v.src = url;
  v.play().catch(() => nextCandidate('הנגן המובנה לא הצליח לנגן את התוכן'));
}

/**
 * Providers disable endpoints without saying so: the URL a playlist hands out
 * can answer with an error page while the same channel plays fine as .ts. Each
 * failure moves to the next candidate before the channel is called dead.
 */
function nextCandidate(reason){
  if(state.candidateIndex + 1 < (state.candidates || []).length){
    state.candidateIndex += 1;
    playCandidate();
    return;
  }
  playerMessage(reason || 'לא הצלחתי לנגן את הפריט באף כתובת שניסיתי');
}

function playCandidate(){
  const url = (state.candidates || [])[state.candidateIndex];
  if(!url){ playerMessage('אין כתובת ניגון לפריט זה'); return; }

  const item = state.current || {};
  playerMessage('טוען...');
  stopPlayback();

  if(window.webapis && webapis.avplay){
    $('#htmlVideo').style.display = 'none';
    $('#avPlayer').style.display = 'block';
    try{
      webapis.avplay.open(url);
      try { if(item.userAgent) webapis.avplay.setStreamingProperty('USER_AGENT', item.userAgent); } catch(e) {}
      try { if(item.referrer) webapis.avplay.setStreamingProperty('REFERER', item.referrer); } catch(e) {}
      webapis.avplay.setDisplayRect(594, 152, 1224, 682);
      webapis.avplay.setListener({
        onbufferingstart: () => playerMessage('טוען...'),
        onbufferingcomplete: () => playerMessage(''),
        onstreamcompleted: () => playerMessage('ההפעלה הסתיימה'),
        onerror: err => nextCandidate('שגיאת ניגון: ' + err),
        onevent: () => {}, oncurrentplaytime: () => {}, ondrmevent: () => {}
      });
      webapis.avplay.prepareAsync(
        () => { playerMessage(''); webapis.avplay.play(); },
        err => nextCandidate('שגיאת הכנה: ' + err)
      );
    }catch(e){
      nextCandidate('שגיאת ניגון: ' + (e.message || e));
    }
    return;
  }

  playHtml(url);
}

async function activateItem(item){
  if(item.isSeriesStub){ await openSeries(item); return; }
  if(!item.url){ playerMessage('אין כתובת ניגון לפריט זה'); return; }

  state.current = item;
  text($('#itemName'), item.name);
  text($('#itemMeta'), itemMeta(item));
  state.candidates = streamVariants(item.url);
  state.candidateIndex = 0;
  playCandidate();
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

function navLive(active, dir){
  const type = active && active.dataset ? active.dataset.nav : null;
  const groups = visible('[data-nav="group"]');
  const actions = visible('[data-nav="topAction"]');

  if(type === 'item'){
    if(dir === 'up' && state.liveIndex === 0) return $('#search');
    if(dir === 'up'){ moveLive(-1); return null; }
    if(dir === 'down'){ moveLive(1); return null; }
    return active;
  }
  if(type === 'search'){
    if(dir === 'down') return groups[0] || visible('[data-nav="item"]')[0] || active;
    if(dir === 'up') return actions[0] || active;
    return active;
  }
  if(type === 'group'){
    if(dir === 'left' || dir === 'right') return moveRtlRow(groups, active, dir) || active;
    if(dir === 'down') return visible('[data-nav="item"]')[0] || active;
    if(dir === 'up') return $('#search');
    return active;
  }
  if(type === 'topAction'){
    if(dir === 'left' || dir === 'right') return moveRtlRow(actions, active, dir) || active;
    if(dir === 'down') return $('#search');
    return active;
  }
  return visible('[data-nav="item"]')[0] || $('#search');
}

function navVod(active, dir){
  const type = active && active.dataset ? active.dataset.nav : null;
  const actions = visible('[data-nav="topAction"]');

  if(type === 'vodCard'){
    if(dir === 'up' && state.vodRow === 0) return $('#vodSearch');
    if(dir === 'up'){ moveVod(-1, 0); return null; }
    if(dir === 'down'){ moveVod(1, 0); return null; }
    // The rows read right to left, so right moves back through the row.
    if(dir === 'right'){ moveVod(0, -1); return null; }
    if(dir === 'left'){ moveVod(0, 1); return null; }
    return active;
  }
  if(type === 'vodSearch'){
    if(dir === 'down'){ focusVod(); return null; }
    if(dir === 'up') return actions[0] || active;
    return active;
  }
  if(type === 'topAction'){
    if(dir === 'left' || dir === 'right') return moveRtlRow(actions, active, dir) || active;
    if(dir === 'down') return $('#vodSearch');
    return active;
  }
  return $('#vodSearch');
}

function currentNavMode(){
  if(!$('#setupScreen').classList.contains('hidden')) return 'setup';
  if(!$('#homeScreen').classList.contains('hidden')) return 'home';
  if(!$('#liveScreen').classList.contains('hidden')) return 'live';
  return 'vod';
}

function moveFocus(dir){
  const active = state.focusEl || document.activeElement;
  let next = null;
  const mode = currentNavMode();
  if(mode === 'setup') next = navSetup(active, dir);
  else if(mode === 'home') next = navHome(active, dir);
  else if(mode === 'live') next = navLive(active, dir);
  else next = navVod(active, dir);
  // A null answer means the screen moved its own selection already.
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
  // Inside a series, Back returns to the series list rather than leaving the mode.
  if(state.episodeContext){
    closeSeries();
    return;
  }
  if(!$('#browseScreen').classList.contains('hidden') && !$('#homeScreen').classList.contains('hidden')) {
    resetToSetup();
    return;
  }
  backToHome();
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
  $('#vodSearch').dataset.nav = 'vodSearch';
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
  $('#vodSearch').addEventListener('input', applyFilter);
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
    if(state.mode === 'LIVE' && (code === 427 || code === 33)){
      moveLive(1);
      const item = state.filtered[state.liveIndex];
      if(item) activateItem(item);
      e.preventDefault();
      return;
    }
    if(state.mode === 'LIVE' && (code === 428 || code === 34)){
      moveLive(-1);
      const item = state.filtered[state.liveIndex];
      if(item) activateItem(item);
      e.preventDefault();
      return;
    }
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

/**
 * `?demo=19000` fills the app with generated items. It exists so the layout and
 * the windowed rendering can be exercised at real playlist sizes in a desktop
 * browser, without a subscription. It never runs unless the URL asks for it.
 */
function demoItems(count){
  const groups = ['ישראל', 'ספורט', 'חדשות', 'ילדים', 'סרטים', 'סדרות', 'מוזיקה'];
  const out = [];
  for(let i = 0; i < count; i++){
    const group = groups[i % groups.length];
    const vod = group === 'סרטים' || group === 'סדרות';
    out.push({
      id: 'd' + i,
      name: (vod ? 'כותר ' : 'ערוץ ') + (i + 1),
      group: group,
      kind: vod ? 'VOD' : 'LIVE',
      contentType: group === 'סדרות' ? 'SERIES' : (vod ? 'MOVIE' : 'LIVE'),
      url: 'http://example.invalid/' + i + '.m3u8',
      logo: null
    });
  }
  return out;
}

wireStatic();
registerKeys();

const demo = /[?&]demo=(\d+)/.exec(location.search);
if(demo){
  state.source = {type: 'demo'};
  finishLoad(demoItems(Math.min(Number(demo[1]) || 0, 50000)));
}
loadSavedSource();
setSourceTab(state.sourceTab);
setTimeout(() => setFocus(visible('[data-nav="setupTab"]')[0] || $('#m3uUrl')), 150);
setTimeout(() => { const sp = document.getElementById('splashScreen'); if (sp) { sp.classList.add('hiddenSplash'); setTimeout(() => { sp.style.display='none'; }, 420); } }, 1300);


})();
