(() => {
'use strict';

const $ = (s, root=document) => root.querySelector(s);
const $$ = (s, root=document) => Array.from(root.querySelectorAll(s));
const state = {
  sourceTab: 'xtream',
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
  // Tiles, or one channel per line with its schedule beside it.
  guideLayout: 'grid',
  // The channel list, open over a channel that is still playing.
  watchOpen: false,
  watchIndex: 0,
  watchStart: 0,
  // Which channel the schedule under the picture is currently drawn for.
  scheduleFor: null,
  vodRow: 0,
  vodCol: 0,
  vodRowStart: 0,
  vodData: [],
  overlayTimer: null,
  // The field the on-screen keyboard is open on, if any, and how it was left.
  editingEl: null,
  editExit: null,
  // Playback: whether the controls are showing, and a jump not yet committed.
  controlsOpen: false,
  paused: false,
  pendingSeek: null,
  seekTimer: null,
  trackType: null,
  selectedTrack: {},
  // What you watched and what you marked — the home screen is built from these.
  history: [],
  favorites: [],
  // Prepared answers for the search, filled in after the catalogue arrives.
  searchIndex: null,
  // Titles and episodes ticked off by hand, and the set derived from those
  // plus whatever was played to the end.
  marks: [],
  seen: {},
  home: {rows: [], row: 0, col: 0, rowStart: 0},
  search: {rows: [], row: 0, col: 0, rowStart: 0},
  playerReturn: 'home',
  resumeAt: 0,
  position: 0,
  duration: 0,
  progressTimer: null
};

function text(el, value){ if(el) el.textContent = value; }
/**
 * Whether an element is on screen, asked cheaply.
 *
 * This runs for every candidate on every press of the remote. Asking
 * getComputedStyle costs a style recalculation each time; the geometry below
 * answers the same question for display:none anywhere up the tree, and stops at
 * the first property that is non-zero. getClientRects covers the fixed-position
 * player, whose children have no offset parent.
 */
function isVisible(el){
  if(!el) return false;
  if(!(el.offsetWidth || el.offsetHeight || el.getClientRects().length)) return false;
  // The one thing the app hides without taking it out of the layout: the
  // browsing screen, while the player is over it.
  if(document.body.classList.contains('playerOpen')){
    const browse = document.getElementById('browseScreen');
    if(browse && browse.contains(el)) return false;
  }
  return true;
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
function setError(msg=''){
  text($('#error'), msg);
  // A failure has to be readable: never leave the splash over the message.
  if(msg && msg.indexOf('שגיאה') === 0) hideSplash();
}

function hideSplash(){
  const sp = document.getElementById('splashScreen');
  if(!sp || sp.classList.contains('hiddenSplash')) return;
  sp.classList.add('hiddenSplash');
  setTimeout(() => { sp.style.display = 'none'; }, 420);
}

// ---- what you watched, and what you marked ---------------------------------
// Kept on the TV, in the same shape the Android app stores, so the home screen
// means the same thing in both.
const HISTORY_KEY = 'talohimHistoryV1';
const FAVORITES_KEY = 'talohimFavoritesV1';
const WATCHED_KEY = 'talohimWatchedV1';
const LAYOUT_KEY = 'talohimGuideLayoutV1';

function loadPrefs(){
  try { state.history = JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]') || []; } catch(e) { state.history = []; }
  try { state.favorites = JSON.parse(localStorage.getItem(FAVORITES_KEY) || '[]') || []; } catch(e) { state.favorites = []; }
  try { state.marks = JSON.parse(localStorage.getItem(WATCHED_KEY) || '[]') || []; } catch(e) { state.marks = []; }
  try { state.guideLayout = localStorage.getItem(LAYOUT_KEY) === 'video' ? 'video' : 'grid'; } catch(e) { state.guideLayout = 'grid'; }
  refreshSeen();
}
function savePrefs(){
  try {
    localStorage.setItem(HISTORY_KEY, JSON.stringify(state.history.slice(0, 60)));
    localStorage.setItem(FAVORITES_KEY, JSON.stringify(state.favorites.slice(0, 200)));
    localStorage.setItem(WATCHED_KEY, JSON.stringify(state.marks.slice(0, 4000)));
  } catch(e) {}
  refreshSeen();
}

/**
 * One answer to "have I seen this", recomputed whenever the memory changes, so
 * a tick can be drawn on a card without walking the history for every tile.
 */
function refreshSeen(){
  state.seen = Core.watchedSet(state.history, state.marks);
}

function isSeen(item){ return !!(item && state.seen && state.seen[item.id]); }

/** Marking by hand, the way you tick an episode off a list. */
function toggleWatched(item){
  if(!item || !item.id) return false;
  const at = state.marks.indexOf(item.id);
  if(at === -1) {
    state.marks.push(item.id);
  } else {
    state.marks.splice(at, 1);
    // Playing it to the end also counts as seen, so unticking has to forget
    // that too — otherwise the tick comes straight back.
    state.history = state.history.map(function (entry) {
      if(entry.id !== item.id) return entry;
      const copy = {};
      Object.keys(entry).forEach(function (k) { copy[k] = entry[k]; });
      copy.position = 0;
      return copy;
    });
  }
  savePrefs();
  return at === -1;
}

/** A history entry carries a copy of the card, so an episode you were watching
 *  still appears after a reload even though episodes are not in the catalogue. */
function noteWatched(item, position, duration){
  if(!item || !item.id) return;
  const card = {
    id: item.id, name: item.name, group: item.group, kind: item.kind,
    contentType: item.contentType, logo: item.logo || null, url: item.url || '',
    seriesId: item.seriesId, isSeriesStub: item.isSeriesStub,
    server: item.server, user: item.user, pass: item.pass
  };
  state.history = Core.mergeHistory(state.history, {
    id: item.id,
    at: Date.now(),
    position: Math.round(position || 0),
    duration: Math.round(duration || 0),
    card: card
  }, 60);
  savePrefs();
}

function isFavorite(item){ return item && state.favorites.indexOf(item.id) !== -1; }
function toggleFavorite(item){
  if(!item || !item.id) return;
  const at = state.favorites.indexOf(item.id);
  if(at === -1) state.favorites.unshift(item.id); else state.favorites.splice(at, 1);
  savePrefs();
  return at === -1;
}

/**
 * The catalogue plus anything remembered that is no longer in it.
 *
 * Copying twenty-three thousand items and building a map of their ids is not
 * something to do twice for the same catalogue, so the answer is kept until the
 * catalogue or the history actually changes.
 */
let homePoolCache = null;

/**
 * What each windowed screen last put on the page.
 *
 * Moving one card sideways does not change which rows are on screen, but the
 * screens rebuilt all of them anyway — three rows of twenty cards, with an
 * image apiece, thrown away and made again for every press of the remote. The
 * record below is how a move can tell whether anything actually has to be
 * drawn; the guide has always done this, and now the others do too.
 */
const drawn = { home: null, search: null, vod: null };

function needsDraw(key, rows, start){
  const at = drawn[key];
  return !(at && at.rows === rows && at.start === start);
}

function markDrawn(key, rows, start){ drawn[key] = { rows: rows, start: start }; }

function homePool(){
  if(homePoolCache && homePoolCache.items === state.items &&
     homePoolCache.history === state.history){
    return homePoolCache.pool;
  }
  const seen = {};
  const pool = state.items.slice();
  pool.forEach(x => seen[x.id] = true);
  state.history.forEach(entry => {
    if(entry.card && !seen[entry.card.id]){ seen[entry.card.id] = true; pool.push(entry.card); }
  });
  homePoolCache = { items: state.items, history: state.history, pool: pool };
  return pool;
}
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

/**
 * Panels differ on where they put the original title, and most send none.
 * Whichever of these turns up is kept beside the name, so a show listed as
 * "ניתוק" is still found by typing "Severance".
 */
const ALIAS_FIELDS = ['o_name', 'original_name', 'orig_name', 'name_en', 'english_name', 'title'];
function aliasOf(item, name){
  for(const field of ALIAS_FIELDS){
    const value = (item[field] || '').toString().trim();
    if(value && value.toLowerCase() !== (name || '').toLowerCase()) return value;
  }
  return null;
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
        alias: aliasOf(x, x.name),
        group: liveCats[String(x.category_id)] || 'ערוצים',
        kind: 'LIVE',
        contentType: 'LIVE',
        url: `${server}/live/${enc(user)}/${enc(pass)}/${id}.m3u8`,
        logo: x.stream_icon || null,
        streamId: id,
        // How many days of this channel the portal keeps. Zero means it keeps
        // none, and there is nothing to wind back to.
        archiveDays: Number(x.tv_archive) === 1 ? Math.max(1, Number(x.tv_archive_duration) || 0) : 0
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
            streamId: id,
            contentType: looksLikeSeries(x.name, vodCats[String(x.category_id)] || '') ? 'SERIES' : 'MOVIE',
            url: `${server}/movie/${enc(user)}/${enc(pass)}/${id}.${ext}`,
            logo: x.stream_icon || null,
            alias: aliasOf(x, x.name)
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
            alias: aliasOf(x, x.name),
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
    return true;
  }catch(e){ setError('שגיאה: ' + e.message); return false; }
}

/**
 * Prepare the search index without stalling the screen.
 *
 * Working it out costs about a fifth of a second on a desktop and several times
 * that on a television — one long freeze if it is done in a single go. So it is
 * built a couple of thousand items at a time, between frames. A search that
 * arrives before it is ready simply walks the catalogue itself: slower, and
 * exactly as correct.
 */
const INDEX_CHUNK = 2000;
let indexTimer = null;

function buildSearchIndexSoon(){
  if(indexTimer) clearTimeout(indexTimer);
  state.searchIndex = null;
  const items = state.items || [];
  const out = [];
  let at = 0;
  const step = function(){
    const slice = items.slice(at, at + INDEX_CHUNK);
    if(!slice.length){
      state.searchIndex = out;
      indexTimer = null;
      return;
    }
    Core.buildSearchIndex(slice).forEach(function (row) { out.push(row); });
    at += INDEX_CHUNK;
    indexTimer = setTimeout(step, 0);
  };
  indexTimer = setTimeout(step, 0);
}

function finishLoad(items){
  state.items = items;
  buildSearchIndexSoon();
  state.current = null;
  state.mode = null;
  state.group = 'הכל';
  $('#setupScreen').classList.add('hidden');
  $('#browseScreen').classList.remove('hidden');
  $('#liveScreen').classList.add('hidden');
  $('#vodScreen').classList.add('hidden');
  $('#backToSetup').classList.remove('hidden');
  $('#navLive').classList.remove('hidden');
  $('#navVod').classList.remove('hidden');
  $('#navSeries').classList.remove('hidden');
  $('#navSearch').classList.remove('hidden');
  stopPlayback();
  renderCatalogSummary();
  showChooser();
  hideSplash();
}

// A TV renders a few dozen elements comfortably and tens of thousands not at
// all: building 19,000 rows with 19,000 logos freezes the app before the screen
// even repaints. Everything below draws a window around the focused item and
// moves that window, so the DOM stays small no matter how large the playlist.
const LIVE_WINDOW = 24;
const VOD_ROW_WINDOW = 3;
const VOD_COL_WINDOW = 12;

/** Films and series share a screen shape but never share a list. */
function isVodMode(){ return state.mode === 'MOVIES' || state.mode === 'SERIES'; }

function currentPool(){
  if(state.episodeContext) return state.episodeContext.episodes;
  if(state.mode === 'LIVE') return state.items.filter(x => x.kind === 'LIVE');
  if(state.mode === 'MOVIES') return state.items.filter(x => x.kind !== 'LIVE' && x.contentType !== 'SERIES');
  if(state.mode === 'SERIES') return state.items.filter(x => x.contentType === 'SERIES');
  return [];
}

function groupList(){
  const pool = currentPool();
  const counts = {};
  pool.forEach(x => counts[x.group] = (counts[x.group] || 0) + 1);
  return ['הכל', ...Object.keys(counts).sort((a,b) => counts[b] - counts[a])];
}

/**
 * How much of each catalogue actually arrived. A title someone expects and
 * cannot find is either missing from the portal or lost on the way in, and a
 * count is the difference between the two.
 */
function renderCatalogSummary(){
  const el = $('#catalogSummary');
  if(!el) return;
  if(!state.items.length){ el.textContent = 'Samsung Tizen TV'; el.classList.remove('warn'); return; }
  const live = state.items.filter(x => x.kind === 'LIVE').length;
  const series = state.items.filter(x => x.contentType === 'SERIES').length;
  const movies = state.items.length - live - series;
  const parts = [live + ' ערוצים', movies + ' סרטים', series + ' סדרות'];
  const notes = state.notes || [];
  el.textContent = parts.join(' · ') + (notes.length ? ' · ' + notes.join(' · ') : '');
  el.classList.toggle('warn', notes.length > 0);
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
  const box = isVodMode() ? $('#vodSearch') : $('#search');
  return ((box && box.value) || '').trim().toLowerCase();
}

function applyFilter(){
  const q = searchText();
  const pool = currentPool();
  // The same matcher the search screen uses, so a name typed in Latin letters
  // finds a channel the portal spells in Hebrew.
  const wanted = q ? Core.skeleton(q) : '';
  state.filtered = pool.filter(item => {
    const matchGroup = isVodMode() || state.group === 'הכל' || item.group === state.group;
    const matchText = !q || Core.matchesQuery(item, q, wanted);
    return matchGroup && matchText;
  });
  state.liveIndex = 0;
  state.liveStart = 0;
  state.vodRow = 0;
  state.vodCol = 0;
  state.vodRowStart = 0;
  if(isVodMode()){
    renderVodRows();
  } else {
    renderItems();
  }
  if(state.watchOpen) renderWatchList();
}

// ---- Home: rows of content, the way a streaming app opens -----------------

const HOME_ROW_WINDOW = 3;
const HOME_COL_WINDOW = 12;

function buildHome(){
  // The library's home is the library's alone; channels have a guide of their own.
  const pool = homePool().filter(x => x.kind !== 'LIVE');
  state.home.rows = Core.buildHomeRows({
    items: pool,
    history: state.history,
    favorites: state.favorites,
    marks: state.marks,
    now: Date.now()
  });
  if(state.home.row >= state.home.rows.length){ state.home.row = 0; state.home.col = 0; state.home.rowStart = 0; }
}

/** Artwork when the portal gives any, initials when it does not. */
function fillArt(box, item){
  if(!box) return;
  box.innerHTML = '';
  if(item && item.logo){
    const img = document.createElement('img');
    img.src = item.logo;
    img.alt = '';
    img.onerror = () => { box.textContent = placeholderText(item.name); img.remove(); };
    box.appendChild(img);
  } else if(item){
    box.textContent = placeholderText(item.name);
  }
}

function makeCard(item, navName, row, col){
  const card = document.createElement('button');
  const live = item.kind === 'LIVE';
  const playing = state.current && state.current.id === item.id;
  card.className = 'focusable ' + (live ? 'wideCard' : 'posterCard homePoster') + (playing ? ' playing' : '');
  card.dataset.nav = navName;
  card.dataset.row = String(row);
  card.dataset.col = String(col);
  card.innerHTML = (live ? '<div class="wideArt"></div>' : '<div class="vodPoster"></div>') +
    '<div class="cardName"></div>';
  fillArt($(live ? '.wideArt' : '.vodPoster', card), item);
  $('.cardName', card).textContent = (isFavorite(item) ? '★ ' : '') + item.name;
  if(isSeen(item)){
    card.classList.add('seen');
    const tick = document.createElement('div');
    tick.className = 'seenTick';
    tick.textContent = '✓';
    $(live ? '.wideArt' : '.vodPoster', card).appendChild(tick);
  }
  if(item.progress > 0){
    const bar = document.createElement('div');
    bar.className = 'cardProgress';
    const fill = document.createElement('div');
    fill.className = 'cardProgressFill';
    fill.style.width = Math.round(item.progress * 100) + '%';
    bar.appendChild(fill);
    card.appendChild(bar);
  }
  card.addEventListener('click', () => {
    const cursor = navName === 'searchCard' ? state.search : state.home;
    cursor.row = row; cursor.col = col;
    activateFromHome(item);
  });
  return card;
}

function renderHome(){
  const box = $('#homeRows');
  if(!box) return;
  box.innerHTML = '';
  const data = state.home.rows;
  markDrawn('home', data, state.home.rowStart);

  if(!data.length){
    const empty = document.createElement('div');
    empty.className = 'cardRowTitle';
    empty.textContent = 'אין תוכן להצגה';
    box.appendChild(empty);
    return;
  }

  data.slice(state.home.rowStart, state.home.rowStart + HOME_ROW_WINDOW).forEach((row, offset) => {
    const rowIndex = state.home.rowStart + offset;
    const wrap = document.createElement('div');
    const allLive = row.items.every(x => x.kind === 'LIVE');
    wrap.className = 'cardRow ' + (allLive ? 'liveRow' : 'posterRow');

    const title = document.createElement('div');
    title.className = 'cardRowTitle';
    title.textContent = row.title;

    const track = document.createElement('div');
    track.className = 'cardRowTrack';
    const colStart = rowIndex === state.home.row ? Math.max(0, state.home.col - 2) : 0;
    row.items.slice(colStart, colStart + HOME_COL_WINDOW).forEach((item, colOffset) => {
      track.appendChild(makeCard(item, 'homeCard', rowIndex, colStart + colOffset));
    });

    wrap.appendChild(title);
    wrap.appendChild(track);
    box.appendChild(wrap);
  });
}

function homeItemAt(row, col){
  const r = state.home.rows[row];
  return r ? r.items[col] : null;
}

function describeHome(item){
  if(!item){
    text($('#homeKicker'), 'טלוהים');
    text($('#homeTitle'), 'מה נצפה עכשיו?');
    text($('#homeMeta'), 'בחר מהשורות למטה · אישור מפעיל');
    return;
  }
  const row = state.home.rows[state.home.row];
  text($('#homeKicker'), row ? row.title : 'טלוהים');
  text($('#homeTitle'), item.name);
  const resume = item.resumeAt ? ' · המשך מ-' + formatClock(item.resumeAt) : '';
  const marked = isFavorite(item) ? ' · במועדפים' : '';
  text($('#homeMeta'), itemMeta(item) + resume + marked + ' · אישור להפעלה');

  const art = $('#homeArt');
  if(art){
    art.classList.toggle('poster', item.kind !== 'LIVE');
    fillArt(art, item);
  }
  const back = $('#homeHeroBack');
  if(back) back.style.backgroundImage = item.logo ? `url("${item.logo}")` : '';
}

function focusHome(){
  const data = state.home.rows;
  if(!data.length){ describeHome(null); return; }
  state.home.row = Math.max(0, Math.min(state.home.row, data.length - 1));
  const row = data[state.home.row];
  state.home.col = Math.max(0, Math.min(state.home.col, row.items.length - 1));
  state.home.rowStart = Math.max(0, Math.min(state.home.row - 1, Math.max(0, data.length - HOME_ROW_WINDOW)));
  if(needsDraw('home', data, state.home.rowStart)) renderHome();
  describeHome(homeItemAt(state.home.row, state.home.col));
  const el = $('[data-nav="homeCard"][data-row="' + state.home.row + '"][data-col="' + state.home.col + '"]');
  if(el) setFocus(el);
}

function moveHome(dRow, dCol){
  const data = state.home.rows;
  if(!data.length) return;
  if(dRow){ state.home.row = Math.max(0, Math.min(state.home.row + dRow, data.length - 1)); state.home.col = 0; }
  if(dCol) state.home.col = Math.max(0, state.home.col + dCol);
  focusHome();
}

/**
 * Two worlds, and the app asks which one before it shows anything else. A
 * provider's channel guide and a streaming library are different products with
 * different manners; mixing them into one screen served neither.
 */
function showChooser(){
  state.world = null;
  state.mode = null;
  state.episodeContext = null;
  ['#homeScreen', '#liveScreen', '#vodScreen', '#searchScreen', '#detailScreen']
    .forEach(sel => $(sel).classList.add('hidden'));
  $('#chooseScreen').classList.remove('hidden');
  $('#goHome').classList.add('hidden');
  ['#navLive', '#modeSwitch', '#navVod', '#navSeries', '#navSearch'].forEach(sel => $(sel).classList.add('hidden'));

  const live = state.items.filter(x => x.kind === 'LIVE').length;
  const series = state.items.filter(x => x.contentType === 'SERIES').length;
  text($('#worldLiveCount'), live ? live.toLocaleString('he-IL') + ' ערוצים' : '');
  text($('#worldVodCount'), (state.items.length - live - series).toLocaleString('he-IL') +
    ' סרטים · ' + series.toLocaleString('he-IL') + ' סדרות');

  renderNotes();
  setTimeout(() => setFocus($('#worldLive')), 60);
}

/** Entering a world shows only what belongs to it. */
function enterWorld(world){
  state.world = world;
  $('#chooseScreen').classList.add('hidden');
  $('#goHome').classList.remove('hidden');
  $('#navSearch').classList.remove('hidden');
  if(world === 'LIVE'){
    $('#navLive').classList.remove('hidden');
    $('#modeSwitch').classList.remove('hidden');
    renderLayoutPills();
    $('#navVod').classList.add('hidden');
    $('#navSeries').classList.add('hidden');
    showMode('LIVE');
    return;
  }
  $('#navLive').classList.add('hidden');
  $('#modeSwitch').classList.add('hidden');
  $('#navVod').classList.remove('hidden');
  $('#navSeries').classList.remove('hidden');
  showHome();
}

function showHome(){
  state.mode = null;
  state.episodeContext = null;
  $('#liveScreen').classList.add('hidden');
  $('#vodScreen').classList.add('hidden');
  $('#searchScreen').classList.add('hidden');
  $('#detailScreen').classList.add('hidden');
  $('#homeScreen').classList.remove('hidden');
  $('#modeSwitch').classList.add('hidden');
  renderNotes();
  buildHome();
  state.home.row = 0; state.home.col = 0; state.home.rowStart = 0;
  setTimeout(() => {
    if(state.home.rows.length) focusHome();
    else setFocus($('#navLive'));
  }, 60);
}

/** A card knows what it is, so the home screen needs no separate menus. */
function activateFromHome(item){
  if(!item) return;
  // A poster opens the title first, the way a streaming app does. A channel or
  // an episode is a thing to watch now, so it plays.
  if(item.kind !== 'LIVE' && item.contentType !== 'EPISODE'){
    openDetail(item, currentNavMode());
    return;
  }
  if(item.kind === 'LIVE'){
    // Line up the live pool so channel up/down works straight from the home row.
    state.mode = 'LIVE';
    state.group = 'הכל';
    state.episodeContext = null;
    if($('#search')) $('#search').value = '';
    applyFilter();
    const at = state.filtered.findIndex(x => x.id === item.id);
    if(at >= 0) state.liveIndex = at;
  }
  activateItem(item, resumeFor(item));
}

function resumeFor(item){
  const entry = state.history.find(x => x.id === item.id);
  return entry && Core.isResumable(entry) ? entry.position : 0;
}

const formatClock = Core.formatClock;

// ---- A title, before it plays ----------------------------------------------

const detail = { item: null, info: null, seasons: [], season: null, episodes: [], focus: 'action', index: 0, returnTo: 'home' };

/** Shared with the Android app: a portal field that may or may not be base64. */
const decodeMaybeBase64 = Core.decodeMaybeBase64;

function detailApi(action, key, id){
  const src = state.source || {};
  if(src.type !== 'xtream') return null;
  return `${src.server}/player_api.php?username=${enc(src.user)}&password=${enc(src.pass)}` +
    `&action=${action}&${key}=${enc(id)}`;
}

function factsOf(info){
  const facts = [];
  const year = (info.releasedate || info.releaseDate || '').toString().slice(0, 4);
  if(year) facts.push(year);
  if(info.genre) facts.push(String(info.genre).split(',')[0].trim());
  const minutes = String(info.duration || info.episode_run_time || '').trim();
  if(minutes && /^\d+$/.test(minutes)) facts.push(minutes + ' דק׳');
  else if(minutes && /:/.test(minutes)) facts.push(minutes);
  if(info.rating && Number(info.rating) > 0) facts.push('★ ' + Number(info.rating).toFixed(1));
  if(info.cast) facts.push(String(info.cast).split(',').slice(0, 3).join(', ').trim());
  return facts.join(' · ');
}

async function openDetail(item, returnTo){
  detail.item = item;
  detail.info = null;
  detail.seasons = [];
  detail.episodes = [];
  detail.season = null;
  detail.focus = 'action';
  detail.index = 0;
  detail.returnTo = returnTo || currentNavMode();

  ['#homeScreen', '#vodScreen', '#searchScreen'].forEach(sel => $(sel).classList.add('hidden'));
  $('#detailScreen').classList.remove('hidden');

  text($('#detailTitle'), item.name);
  text($('#detailFacts'), itemMeta(item));
  text($('#detailPlot'), '');
  fillArt($('#detailPoster'), item);
  $('#detailBackdrop').style.backgroundImage = item.logo ? `url("${item.logo}")` : '';
  $('#seasonStrip').classList.add('hidden');
  $('#episodeStrip').classList.add('hidden');
  renderDetailActions();
  setTimeout(() => setFocus($('#detailPlay')), 50);

  const series = item.contentType === 'SERIES';
  const url = detailApi(series ? 'get_series_info' : 'get_vod_info',
    series ? 'series_id' : 'vod_id', item.streamId || item.seriesId || '');
  if(!url) return;

  try {
    const data = JSON.parse(await getText(url));
    if(detail.item !== item) return;   // the viewer moved on while this arrived
    const info = data.info || (data.movie_data && data.movie_data.info) || {};
    detail.info = info;

    const plot = decodeMaybeBase64(info.plot || info.description || '');
    if(plot) text($('#detailPlot'), plot);
    const facts = factsOf(info);
    if(facts) text($('#detailFacts'), itemMeta(item) + ' · ' + facts);

    const backdrop = (Array.isArray(info.backdrop_path) ? info.backdrop_path[0] : info.backdrop_path) ||
      info.movie_image || info.cover || item.logo;
    if(backdrop) $('#detailBackdrop').style.backgroundImage = `url("${backdrop}")`;

    if(series && data.episodes){
      detail.seasons = Object.keys(data.episodes)
        .sort(function(a, b){ return (Number(a) || 0) - (Number(b) || 0); });
      detail.all = Core.episodesFromSeriesInfo(data, {
        server: item.server, user: item.user, pass: item.pass, logo: item.logo, seriesName: item.name
      });
      selectSeason(detail.seasons[0]);
    }
  } catch(e) {
    text($('#detailPlot'), 'לא הצלחתי לטעון את פרטי הכותר.');
  }
}

function selectSeason(season){
  detail.season = season;
  detail.episodes = (detail.all || []).filter(function(ep){ return ep.group === 'עונה ' + season; });
  state.episodeContext = { series: detail.item, episodes: detail.all || [] };
  renderSeasons();
  renderEpisodes();
}

function renderSeasons(){
  const strip = $('#seasonStrip');
  strip.innerHTML = '';
  if(detail.seasons.length < 1){ strip.classList.add('hidden'); return; }
  strip.classList.remove('hidden');
  detail.seasons.forEach(function(season){
    const chip = document.createElement('button');
    chip.className = 'focusable seasonChip' + (season === detail.season ? ' active' : '');
    chip.dataset.nav = 'season';
    chip.textContent = 'עונה ' + season;
    chip.addEventListener('click', () => selectSeason(season));
    strip.appendChild(chip);
  });
}

function renderEpisodes(){
  const strip = $('#episodeStrip');
  strip.innerHTML = '';
  if(!detail.episodes.length){ strip.classList.add('hidden'); return; }
  strip.classList.remove('hidden');
  detail.episodes.slice(0, 14).forEach(function(episode, index){
    const card = document.createElement('button');
    card.className = 'focusable episodeCard';
    card.dataset.nav = 'episode';
    card.dataset.index = String(index);
    card.innerHTML = '<div class="episodeArt"><div class="episodeNum"></div></div>' +
      '<div class="episodeName"></div><div class="episodeMeta"></div>';
    const seen = isSeen(episode);
    if(seen) card.classList.add('seen');
    // The still the portal sends for the episode, and the series poster when it
    // sends none — a card with a hole in it is worse than a repeated picture.
    const art = $('.episodeArt', card);
    const num = $('.episodeNum', art);
    if(episode.logo){
      const img = document.createElement('img');
      img.src = episode.logo;
      img.alt = '';
      img.addEventListener('error', () => { img.remove(); });
      art.insertBefore(img, num);
    }
    num.textContent = (seen ? '✓ ' : '') + 'פרק ' + (index + 1);
    $('.episodeName', card).textContent =
      Core.episodeLabel(episode.name, state.episodeContext && state.episodeContext.name);
    const entry = state.history.find(x => x.id === episode.id);
    $('.episodeMeta', card).textContent = seen ? 'נצפה'
      : (entry && Core.isResumable(entry) ? 'המשך מ-' + formatClock(entry.position) : episode.group);
    card.addEventListener('click', () => activateItem(episode));
    strip.appendChild(card);
  });
  // The episodes arrive after the page is drawn, and the tick's label depends
  // on them — a series is ticked off by the season, a film by itself.
  if(detail.item && detail.item.contentType === 'SERIES') renderDetailActions();
}

function renderDetailActions(){
  const item = detail.item;
  if(!item) return;
  const resume = resumeFor(item);
  text($('#detailPlay'), item.contentType === 'SERIES'
    ? 'צפה בפרק הראשון'
    : (resume ? 'המשך מ-' + formatClock(resume) : 'צפה'));
  text($('#detailFavorite'), isFavorite(item) ? 'הסר מהמועדפים' : 'הוסף למועדפים');
  // A series is ticked off episode by episode, so the button says so.
  const everyEpisode = item.contentType === 'SERIES' && detail.episodes.length;
  const done = everyEpisode
    ? detail.episodes.every(function (ep) { return isSeen(ep); })
    : isSeen(item);
  text($('#detailWatched'), done
    ? (everyEpisode ? 'סמן את העונה כלא נצפתה' : 'סמן כלא נצפה')
    : (everyEpisode ? 'סמן את כל העונה כנצפתה' : 'סמן כנצפה'));
}

/** The tick from the title page: one title, or a whole season at once. */
function markFromDetail(){
  const item = detail.item;
  if(!item) return;
  if(item.contentType === 'SERIES' && detail.episodes.length){
    const done = detail.episodes.every(function (ep) { return isSeen(ep); });
    detail.episodes.forEach(function (ep) { if(isSeen(ep) === done) toggleWatched(ep); });
  } else {
    toggleWatched(item);
  }
  renderDetailActions();
  renderEpisodes();
}

function playFromDetail(){
  const item = detail.item;
  if(!item) return;
  if(item.contentType === 'SERIES'){
    const first = (detail.episodes[0]) || (detail.all || [])[0];
    if(first) activateItem(first);
    return;
  }
  activateItem(item);
}

function closeDetail(){
  $('#detailScreen').classList.add('hidden');
  state.episodeContext = null;
  if(detail.returnTo === 'vod'){ $('#vodScreen').classList.remove('hidden'); focusVod(); return; }
  if(detail.returnTo === 'search'){ $('#searchScreen').classList.remove('hidden'); focusSearch(); return; }
  $('#homeScreen').classList.remove('hidden');
  focusHome();
}

/**
 * Up and down across a grid that wraps.
 *
 * How many cards fit on a line is a question only the layout can answer, so it
 * is asked of the layout: the cards are grouped by the line they landed on, and
 * the move goes to whichever card in the next line sits closest across.
 */
function stepGrid(items, active, dir){
  if(!items.length) return null;
  const rows = [];
  items.forEach(function(el){
    const top = Math.round(el.offsetTop);
    let row = rows.find(function(r){ return Math.abs(r.top - top) < 8; });
    if(!row){ row = { top: top, items: [] }; rows.push(row); }
    row.items.push(el);
  });
  rows.sort(function(a, b){ return a.top - b.top; });

  const at = rows.findIndex(function(r){ return r.items.indexOf(active) !== -1; });
  if(at === -1) return null;
  const target = rows[at + (dir === 'down' ? 1 : -1)];
  if(!target) return null;

  const x = active.offsetLeft;
  return target.items.reduce(function(best, el){
    return Math.abs(el.offsetLeft - x) < Math.abs(best.offsetLeft - x) ? el : best;
  }, target.items[0]);
}

function navDetail(active, dir){
  const type = active && active.dataset ? active.dataset.nav : null;
  const actions = visible('[data-nav="detailAction"]');
  const seasons = visible('[data-nav="season"]');
  const episodes = visible('[data-nav="episode"]');

  if(type === 'detailAction'){
    if(dir === 'left' || dir === 'right') return moveRtlRow(actions, active, dir) || active;
    if(dir === 'down') return seasons[0] || episodes[0] || active;
    return active;
  }
  if(type === 'season'){
    // The seasons are a column now, so up and down walk them; left leads out to
    // the episodes, which sit beside them.
    if(dir === 'up' || dir === 'down'){
      const at = seasons.indexOf(active);
      const next = seasons[at + (dir === 'down' ? 1 : -1)];
      if(next) return next;
      return dir === 'up' ? (actions[0] || active) : active;
    }
    if(dir === 'left') return episodes[0] || active;
    return active;
  }
  if(type === 'episode'){
    if(dir === 'left' || dir === 'right'){
      const next = moveRtlRow(episodes, active, dir);
      // Off the right-hand end of a line is out of the grid altogether.
      if(next) return next;
      return dir === 'right' ? (seasons[0] || actions[0] || active) : active;
    }
    const across = stepGrid(episodes, active, dir);
    if(across) return across;
    if(dir === 'up') return seasons[0] || actions[0] || active;
    return active;
  }
  return actions[0] || active;
}

// ---- Search: one box over everything --------------------------------------

function renderSearch(){
  const box = $('#searchRows');
  if(!box) return;
  const query = ($('#globalSearch') && $('#globalSearch').value) || '';
  state.search.rows = Core.searchRows(state.items, query, undefined, state.searchIndex);
  box.innerHTML = '';
  markDrawn('search', state.search.rows, state.search.rowStart);

  const count = $('#searchCount');
  const total = state.search.rows.reduce((n, r) => n + r.items.length, 0);
  if(count){
    count.textContent = query.trim().length < 2 ? 'הקלד שתי אותיות לפחות'
      : (total ? total + ' תוצאות' : 'לא נמצא כלום בשם הזה');
  }

  state.search.rows.slice(state.search.rowStart, state.search.rowStart + HOME_ROW_WINDOW)
    .forEach((row, offset) => {
      const rowIndex = state.search.rowStart + offset;
      const wrap = document.createElement('div');
      wrap.className = 'cardRow ' + (row.items.every(x => x.kind === 'LIVE') ? 'liveRow' : 'posterRow');
      const title = document.createElement('div');
      title.className = 'cardRowTitle';
      title.textContent = row.title + ' · ' + row.items.length;
      const track = document.createElement('div');
      track.className = 'cardRowTrack';
      const colStart = rowIndex === state.search.row ? Math.max(0, state.search.col - 2) : 0;
      row.items.slice(colStart, colStart + HOME_COL_WINDOW).forEach((item, colOffset) => {
        track.appendChild(makeCard(item, 'searchCard', rowIndex, colStart + colOffset));
      });
      wrap.appendChild(title);
      wrap.appendChild(track);
      box.appendChild(wrap);
    });
}

function searchItemAt(row, col){
  const r = state.search.rows[row];
  return r ? r.items[col] : null;
}

function focusSearch(){
  const data = state.search.rows;
  if(!data.length){ renderSearch(); return; }
  state.search.row = Math.max(0, Math.min(state.search.row, data.length - 1));
  const row = data[state.search.row];
  state.search.col = Math.max(0, Math.min(state.search.col, row.items.length - 1));
  state.search.rowStart = Math.max(0, Math.min(state.search.row - 1, Math.max(0, data.length - HOME_ROW_WINDOW)));
  if(needsDraw('search', data, state.search.rowStart)) renderSearch();
  const el = $('[data-nav="searchCard"][data-row="' + state.search.row + '"][data-col="' + state.search.col + '"]');
  if(el) setFocus(el);
}

function moveSearch(dRow, dCol){
  const data = state.search.rows;
  if(!data.length) return;
  if(dRow){ state.search.row = Math.max(0, Math.min(state.search.row + dRow, data.length - 1)); state.search.col = 0; }
  if(dCol) state.search.col = Math.max(0, state.search.col + dCol);
  focusSearch();
}

function showSearch(){
  state.mode = null;
  state.episodeContext = null;
  $('#homeScreen').classList.add('hidden');
  $('#liveScreen').classList.add('hidden');
  $('#vodScreen').classList.add('hidden');
  $('#searchScreen').classList.remove('hidden');
  $('#goHome').classList.remove('hidden');
  $('#modeSwitch').classList.add('hidden');
  state.search.row = 0; state.search.col = 0; state.search.rowStart = 0;
  renderSearch();
  setTimeout(() => setFocus($('#globalSearch')), 60);
}

// ---- What is on right now --------------------------------------------------
//
// A provider's guide always answers two questions before you press anything:
// what is playing, and what follows. The portal gives us a short EPG per
// channel; we cache it, because moving across the grid asks for it constantly.

const epgCache = {};

function clockOfDay(stamp){
  const when = new Date(stamp);
  if(isNaN(when.getTime())) return '';
  return ('0' + when.getHours()).slice(-2) + ':' + ('0' + when.getMinutes()).slice(-2);
}

/** Milliseconds for an Xtream EPG entry, which dates them two different ways. */
function epgTime(value, fallback){
  if(value == null || value === '') return fallback;
  const asNumber = Number(value);
  if(!isNaN(asNumber) && asNumber > 100000) return asNumber * 1000;
  const parsed = Date.parse(String(value).replace(' ', 'T'));
  return isNaN(parsed) ? fallback : parsed;
}

function epgFor(item){
  const key = item && item.streamId;
  if(!key) return Promise.resolve(null);
  if(epgCache[key]) return Promise.resolve(epgCache[key]);
  // The whole table, not the next few: catching up needs what has already been
  // broadcast, and get_short_epg starts at now. A panel that will not answer for
  // the table still answers for the short one, so that is the fallback rather
  // than an empty guide.
  const table = detailApi('get_simple_data_table', 'stream_id', key);
  const short = detailApi('get_short_epg', 'stream_id', key);
  if(!table && !short) return Promise.resolve(null);

  const read = function(raw){
    const body = JSON.parse(raw) || {};
    return (body.epg_listings || body.epg_listing || []).map(function(row){
      return {
        title: decodeMaybeBase64(row.title),
        description: decodeMaybeBase64(row.description),
        start: epgTime(row.start_timestamp || row.start, 0),
        stop: epgTime(row.stop_timestamp || row.end, 0)
      };
    }).filter(function(row){ return row.title; });
  };

  return getText(table).then(read).then(function(list){
    if(list.length) return list;
    return getText(short + '&limit=4').then(read);
  }).then(function(list){
    epgCache[key] = list;
    return list;
  }).catch(function(){ epgCache[key] = []; return []; });
}

/** The entry covering `at`, plus the one after it — shared with the Android app. */
const nowOn = Core.nowOn;

function epgLine(row){
  if(!row) return '';
  const at = row.start ? clockOfDay(row.start) : '';
  return (at ? at + ' · ' : '') + row.title;
}

/** Fill the strip above the grid for whichever channel is focused. */
function renderNowNext(){
  const strip = $('#nowNext');
  if(!strip) return;
  const item = state.filtered[state.liveIndex];
  if(!item || !item.streamId){ strip.classList.add('hidden'); return; }

  const token = item.id;
  strip.dataset.channel = token;
  fillArt($('#nnLogo'), item);
  text($('#nnNow'), 'טוען לוח שידורים…');
  text($('#nnNext'), '');
  const fill = $('#nnFill');
  if(fill) fill.style.width = '0%';
  strip.classList.remove('hidden');

  epgFor(item).then(function(list){
    // The focus may have moved on while the portal was answering.
    if(strip.dataset.channel !== token) return;
    // A portal with no EPG for this channel should not leave an empty shelf.
    if(!list || !list.length){ strip.classList.add('hidden'); return; }
    const at = Date.now();
    const slot = nowOn(list, at);
    text($('#nnNow'), slot.current ? slot.current.title : item.name);
    text($('#nnNext'), slot.next ? 'אחר כך · ' + epgLine(slot.next) : '');
    if(fill){
      const row = slot.current;
      const span = row && row.stop > row.start ? row.stop - row.start : 0;
      const done = span ? Math.max(0, Math.min((at - row.start) / span, 1)) : 0;
      fill.style.width = (done * 100).toFixed(1) + '%';
    }
  });
}

// ---- Live TV: a guide of channel tiles, then full-screen playback ----------

const OVERLAY_MS = 4500;

/**
 * A wall of logos is the fastest way to find a channel you already know, and it
 * cannot answer the other question — what is on. The list gives each channel a
 * line long enough to carry its schedule, which means one across instead of
 * five, and more of them down the screen.
 */
const GRID_COLS = 5;
// Three rows, not four. The tile grew a line when it started saying what is on
// the channel, and four rows of the taller tile run off the bottom of the
// screen — a row cut in half is worse than a row fewer.
const GRID_ROWS = 3;

function liveCols(){ return GRID_COLS; }
function liveRowWindow(){ return GRID_ROWS; }

/** How many programmes ahead a line of the guide carries. */
const UPCOMING = 2;

function videoMode(){ return state.guideLayout === 'video'; }

/**
 * The two ways of reading the same channels.
 *
 * Tiles is a wall of logos, which is the fastest way to find one you already
 * know. Video is the other way: you keep watching while you look, with the
 * picture in the corner and the channels down the side. The top bar stays up in
 * both, so either is one press from the other.
 */
function setGuideLayout(layout){
  const wanted = layout === 'video' ? 'video' : 'grid';
  state.guideLayout = wanted;
  try { localStorage.setItem(LAYOUT_KEY, wanted); } catch(e) {}
  renderLayoutPills();

  if(wanted === 'video'){
    document.body.classList.add('videoMode');
    // Something has to be playing for there to be a picture; the channel under
    // the cursor is the obvious candidate.
    const item = state.current || state.filtered[state.liveIndex];
    if(!item) return;
    if(!state.current || state.current.id !== item.id) activateItem(item);
    openWatchList();
    return;
  }

  document.body.classList.remove('videoMode');
  closeWatchList();
  if(!$('#playerScreen').classList.contains('hidden')) closePlayer();
  state.liveStart = 0;
  renderItems();
  if(state.filtered.length) focusChannel();
}

function renderLayoutPills(){
  const grid = $('#navGrid');
  const video = $('#navVideo');
  // The filled half is the one you are in — a single button that renamed itself
  // said what it would do next but never where you were.
  if(grid) grid.classList.toggle('active', !videoMode());
  if(video) video.classList.toggle('active', videoMode());
}

function renderItems(){
  const box = $('#items');
  if(!box) return;
  box.className = 'channelGrid';
  box.innerHTML = '';

  const count = $('#liveCount');
  if(count) count.textContent = state.filtered.length ? state.filtered.length + ' ערוצים' : '';

  if(!state.filtered.length){
    const empty = document.createElement('div');
    empty.className = 'tileMeta';
    empty.textContent = 'לא נמצאו ערוצים';
    box.appendChild(empty);
    const strip = $('#nowNext');
    if(strip) strip.classList.add('hidden');
    return;
  }

  const cols = liveCols();
  const size = cols * liveRowWindow();
  const start = Math.floor(state.liveStart / cols) * cols;
  state.filtered.slice(start, start + size).forEach((item, offset) => {
    const index = start + offset;
    box.appendChild(channelTile(item, index));
  });
}

function channelTile(item, index){
  const tile = document.createElement('button');
  tile.className = 'focusable channelTile' + (state.current && state.current.id === item.id ? ' playing' : '');
  tile.dataset.nav = 'item';
  tile.dataset.index = String(index);
  tile.innerHTML = '<div class="tileLogo"></div><div class="tileName"></div>' +
    '<div class="tileNow"></div><div class="tileMeta"></div>';
  $('.tileName', tile).textContent = (isFavorite(item) ? '★ ' : '') + item.name;
  $('.tileMeta', tile).textContent = (index + 1) + ' · ' + item.group;
  fillArt($('.tileLogo', tile), item);

  // A tile that says only what a channel is called is a list of names; what is
  // on it is what makes a wall of them a guide. Only the tiles on screen are
  // built, so only they are asked about.
  epgFor(item).then(function(list){
    if(!tile.isConnected) return;
    const slot = Core.nowOn(list, Date.now());
    if(slot.current) text($('.tileNow', tile), slot.current.title);
  });
  tile.addEventListener('click', () => {
    state.liveIndex = index;
    activateItem(item);
  });
  return tile;
}

function focusChannel(){
  if(!state.filtered.length) return;
  const index = Math.max(0, Math.min(state.liveIndex, state.filtered.length - 1));
  state.liveIndex = index;

  const cols = liveCols();
  const size = cols * liveRowWindow();
  const start = Math.floor(state.liveStart / cols) * cols;
  if(index < start || index >= start + size){
    // Keep the focused row one row into the window, so there is always context.
    const row = Math.floor(index / cols);
    state.liveStart = Math.max(0, (row - 1) * cols);
    renderItems();
  }

  const el = $('[data-nav="item"][data-index="' + index + '"]');
  if(el) setFocus(el);
  renderNowNext();
}

// ---- The channel list, over a channel that is still playing ---------------
//
// Looking for the next thing while the current thing carries on is the one
// thing a television does that a streaming app forgot. Choosing here changes
// the channel and leaves the list up, because nobody finds what they want on
// the first try; Back is what closes it.

/** How many channels fit beside the picture. */
const WATCH_ROWS = 10;

/**
 * Where the picture goes. The set draws the video on a plane of its own, so
 * moving it is an API call and not a stylesheet — the CSS only follows along
 * for the HTML video element used where avplay is not available.
 */
const CHROME_HEIGHT = 150;

function pictureRect(){
  if(!state.watchOpen) return [0, 0, 1920, 1080];
  // In video mode the top bar stays up, so the picture starts under it.
  return videoMode() ? [48, CHROME_HEIGHT + 16, 860, 484] : [48, 48, 860, 484];
}

function applyPictureRect(){
  const rect = pictureRect();
  try {
    if(window.webapis && webapis.avplay && webapis.avplay.getState() !== 'NONE'){
      webapis.avplay.setDisplayRect(rect[0], rect[1], rect[2], rect[3]);
    }
  } catch(e) {}
}

function openWatchList(){
  if(!state.filtered.length) return;
  state.watchOpen = true;
  closeControls();
  document.body.classList.add('watching');
  $('#watchList').classList.remove('hidden');
  applyPictureRect();

  // The schedule is drawn by focusWatchRow below, once there is a cursor for it
  // to follow; drawing it here would draw it for wherever the cursor was last.
  const at = state.filtered.findIndex(x => state.current && x.id === state.current.id);
  state.watchIndex = at >= 0 ? at : 0;
  state.watchStart = 0;
  renderWatchGroups();
  renderWatchList();
  focusWatchRow();
  showOverlay();
}

function closeWatchList(){
  if(!state.watchOpen) return;
  state.watchOpen = false;
  state.scheduleFor = null;
  document.body.classList.remove('watching');
  $('#watchList').classList.add('hidden');
  $('#watchSchedule').classList.add('hidden');
  applyPictureRect();
  // setFocus ignores a null, so the highlight has to be taken off by hand or it
  // stays lit on a row that is no longer on the screen.
  if(state.focusEl){
    state.focusEl.classList.remove('focused');
    state.focusEl = null;
  }
  showOverlay();
}

/** How many programmes ahead the schedule under the picture has room for. */
const SCHEDULE_AHEAD = 5;
/** And how far back it reaches, where there is an archive to reach into. */
const SCHEDULE_BEHIND = 3;
/**
 * How much of the channel winding back asks for, and how much more it asks for
 * beyond the live edge so that playing on does not run out of stream.
 */
const ARCHIVE_WINDOW = 30;
const ARCHIVE_RUN_ON = 240;

/** A channel the portal keeps, or null. */
function archiveOf(item){
  if(!item || item.kind !== 'LIVE') return null;
  return item.archiveDays > 0 ? item : null;
}

/** The channel being watched, if the portal keeps it. Winding the picture back
 *  is about the picture, so this is the one the rewind acts on. */
function archiveChannel(){ return archiveOf(state.current); }

/**
 * The channel the schedule is drawn for: the one under the cursor.
 *
 * It used to be whatever was playing, so moving through the list told you
 * nothing about what you were moving towards — which is the whole question the
 * list is there to answer.
 */
function scheduleChannel(){
  return state.filtered[state.watchIndex] || state.current;
}

/** How long to ask the archive for. A programme with no end gets an hour. */
function minutesOf(entry){
  const span = (entry.stop || 0) - (entry.start || 0);
  if(span <= 0) return 60;
  return Math.max(1, Math.min(Math.round(span / 60000), 6 * 60));
}

/**
 * Play a stretch of a channel's archive.
 *
 * What comes out is not a channel: it is a finite, seekable recording with a
 * beginning and an end. So it is made into one — a VOD item, which is what
 * gives it a scrubber and keeps the channel keys from carrying you out of it.
 */
function playCatchUp(channel, title, startMillis, minutes, resumeAt){
  const src = state.source || {};
  if(src.type !== 'xtream' || !channel || !channel.streamId) return;
  const urls = Core.catchupVariants(
    { server: src.server, user: src.user, pass: src.pass },
    channel.streamId, startMillis, minutes
  );
  if(!urls.length) return;

  // Choosing a programme is choosing what to watch, not more browsing: the list
  // goes and the picture comes back to the whole screen. Choosing a channel is
  // the other thing, and that one leaves the list up.
  closeWatchList();

  activateItem({
    id: 'catchup:' + channel.id + ':' + startMillis,
    name: title ? title + ' · ' + channel.name : channel.name,
    group: channel.group,
    kind: 'VOD',
    contentType: 'MOVIE',
    url: urls[0],
    logo: channel.logo
  }, resumeAt || 0);
}

/**
 * Wind the live picture back, without picking a programme out of a list.
 *
 * Half an hour of the channel, with the playhead dropped just behind the live
 * edge — so the first press is a rewind of a few seconds, which is what a
 * rewind is, and every press after it is an ordinary seek inside an ordinary
 * recording.
 */
function rewindLive(){
  const channel = archiveChannel();
  if(!channel) return;
  const at = Date.now();
  const from = at - ARCHIVE_WINDOW * 60000;
  epgFor(channel).then(function(rows){
    const slot = Core.nowOn(rows, at);
    playCatchUp(channel, slot.current ? slot.current.title : '', from,
      ARCHIVE_WINDOW + ARCHIVE_RUN_ON, ARCHIVE_WINDOW * 60 - Core.SEEK_STEP);
  });
}

/**
 * The rest of this channel's evening, under the picture.
 *
 * Drawn for whatever is playing, not for whatever is highlighted: the list
 * beside it already answers the second question, and a panel that changed every
 * time the highlight moved would be unreadable.
 */
function renderWatchSchedule(){
  const box = $('#watchSchedule');
  const list = $('#watchScheduleList');
  if(!box || !list) return;
  const item = scheduleChannel();
  if(!state.watchOpen || !item){ box.classList.add('hidden'); return; }

  // The cursor moves faster than the portal answers, so an answer that arrives
  // for a channel nobody is looking at any more is dropped.
  const token = item.id;
  state.scheduleFor = token;
  text($('#watchScheduleFor'), item.name);
  list.innerHTML = '';
  box.classList.remove('hidden');

  epgFor(item).then(function(rows){
    if(!state.watchOpen || state.scheduleFor !== token) return;
    const at = Date.now();
    const slot = Core.nowOn(rows, at);
    const archive = archiveOf(item);
    // Winding back is a thing the portal either keeps or does not, and a
    // feature that is simply absent looks like one that is broken. So the
    // screen says which it is.
    const note = $('#watchScheduleNote');
    if(note) note.classList.toggle('hidden', !!archive);
    // What is behind is only worth listing where it can be played back.
    const behind = archive ? Core.alreadyOn(rows, at, SCHEDULE_BEHIND) : [];
    const ahead = Core.upcoming(rows, at, SCHEDULE_AHEAD);
    const all = behind.concat(slot.current ? [slot.current] : []).concat(ahead);
    if(!all.length){ box.classList.add('hidden'); return; }

    list.innerHTML = '';
    all.forEach(function(entry){
      const past = !!archive && entry.stop <= at;
      // A row you can play is a button; one you cannot is a line of text.
      const row = document.createElement(past ? 'button' : 'div');
      row.className = 'watchScheduleRow' + (entry === slot.current ? ' onAir' : '') +
        (past ? ' focusable canPlay' : '');
      if(past){
        row.dataset.nav = 'watchPast';
        row.addEventListener('click', function(){
          playCatchUp(archive, entry.title, entry.start, minutesOf(entry));
        });
      }
      row.innerHTML = '<div class="wsTime"></div><div class="wsName"></div>';
      text($('.wsTime', row), entry.start ? clockOfDay(entry.start) : '');
      text($('.wsName', row), entry.title);
      list.appendChild(row);
    });
  });
}

/** The categories, reachable by going up from the top of the channel list —
 *  the same way the guide screen reaches its own. */
function renderWatchGroups(){
  const box = $('#watchGroups');
  if(!box) return;
  box.innerHTML = '';
  groupList().slice(0, 40).forEach((g, idx) => {
    const chip = document.createElement('button');
    chip.className = 'focusable groupChip' + (g === state.group ? ' active' : '');
    chip.dataset.nav = 'watchGroup';
    chip.dataset.index = String(idx);
    chip.textContent = g;
    chip.addEventListener('click', () => {
      state.group = g;
      state.watchIndex = 0;
      state.watchStart = 0;
      renderWatchGroups();
      applyFilter();
      focusWatchRow();
    });
    box.appendChild(chip);
  });
}

function renderWatchList(){
  const box = $('#watchItems');
  if(!box) return;
  box.innerHTML = '';
  text($('#watchCount'), state.filtered.length ? state.filtered.length + ' ערוצים' : '');

  const start = Math.max(0, Math.min(state.watchStart, Math.max(0, state.filtered.length - WATCH_ROWS)));
  state.watchStart = start;
  state.filtered.slice(start, start + WATCH_ROWS).forEach((item, offset) => {
    const index = start + offset;
    const row = document.createElement('button');
    row.className = 'focusable watchRow' + (state.current && state.current.id === item.id ? ' playing' : '');
    row.dataset.nav = 'watchItem';
    row.dataset.index = String(index);
    row.innerHTML = '<div class="watchLogo"></div>' +
      '<div class="watchText"><div class="watchName"></div><div class="watchNow"></div></div>' +
      '<div class="watchTime"></div>' +
      '<div class="watchOn">משודר</div>';
    $('.watchName', row).textContent = (index + 1) + ' · ' + (isFavorite(item) ? '★ ' : '') + item.name;
    $('.watchNow', row).textContent = item.group || '';
    fillArt($('.watchLogo', row), item);
    row.addEventListener('click', () => {
      // Pressing the one you are already watching is not "watch it again" —
      // there is nothing else it could mean but "give it the whole screen".
      if(state.current && state.current.id === item.id){
        closeWatchList();
        return;
      }
      state.watchIndex = index;
      state.liveIndex = index;
      // The list stays up: changing channel is not the same as being finished
      // with the list.
      activateItem(item);
      renderWatchList();
      renderWatchSchedule();
      focusWatchRow();
    });
    box.appendChild(row);

    epgFor(item).then(function(list){
      if(!row.isConnected) return;
      const slot = Core.nowOn(list, Date.now());
      if(!slot.current) return;
      text($('.watchNow', row), slot.current.title);
      text($('.watchTime', row), slot.current.start
        ? clockOfDay(slot.current.start) + '–' + clockOfDay(slot.current.stop) : '');
    });
  });
}

function focusWatchRow(){
  if(!state.filtered.length) return;
  const index = Math.max(0, Math.min(state.watchIndex, state.filtered.length - 1));
  state.watchIndex = index;
  if(index < state.watchStart || index >= state.watchStart + WATCH_ROWS){
    // Keep the highlight one row inside the window, so there is always context.
    state.watchStart = Math.max(0, index - (index < state.watchStart ? 1 : WATCH_ROWS - 2));
    renderWatchList();
  }
  const el = $('[data-nav="watchItem"][data-index="' + index + '"]');
  if(el) setFocus(el);
  renderWatchSchedule();
}

function moveWatch(delta){
  if(!state.filtered.length) return;
  state.watchIndex = Math.max(0, Math.min(state.watchIndex + delta, state.filtered.length - 1));
  focusWatchRow();
}

/**
 * Three things are on this screen and the remote has four arrows.
 *
 * The channel list is the middle of it; up from the top of it reaches the
 * categories, and left reaches the schedule under the picture — which is on the
 * left, so left is where it is. Right comes back.
 */
function navWatch(active, dir){
  wakePlayerUi();
  const type = active && active.dataset ? active.dataset.nav : null;
  const past = visible('[data-nav="watchPast"]');
  const chips = visible('[data-nav="watchGroup"]');

  if(type === 'watchGroup'){
    if(dir === 'left' || dir === 'right') return moveRtlRow(chips, active, dir) || active;
    if(dir === 'down'){ focusWatchRow(); return null; }
    return active;
  }

  if(type === 'watchPicture'){
    // The schedule is drawn under the picture, so it is what is below it.
    if(dir === 'down') return past[0] || active;
    if(dir === 'right'){ focusWatchRow(); return null; }
    return active;
  }

  if(type === 'watchPast'){
    if(dir === 'up'){
      const at = past.indexOf(active);
      return at > 0 ? past[at - 1] : ($('#watchPicture') || active);
    }
    if(dir === 'down'){
      const at = past.indexOf(active);
      return past[at + 1] || active;
    }
    if(dir === 'right'){ focusWatchRow(); return null; }
    return active;
  }

  if(dir === 'up'){
    if(state.watchIndex === 0) return chips[0] || active;
    moveWatch(-1);
    return null;
  }
  if(dir === 'down'){ moveWatch(1); return null; }
  // The picture is on the left, with its schedule under it.
  if(dir === 'left') return $('#watchPicture') || past[0] || active;
  return active;
}

function moveChannel(dRow, dCol){
  if(!state.filtered.length) return;
  const last = state.filtered.length - 1;
  let index = state.liveIndex;
  if(dCol) index += dCol;
  if(dRow) index += dRow * liveCols();
  state.liveIndex = Math.max(0, Math.min(index, last));
  focusChannel();
}

/** Channel Up/Down, and the D-pad while watching. */
function zap(delta){
  if(!state.filtered.length) return;
  const index = state.filtered.findIndex(x => state.current && x.id === state.current.id);
  const next = Math.max(0, Math.min((index === -1 ? 0 : index) + delta, state.filtered.length - 1));
  state.liveIndex = next;
  activateItem(state.filtered[next]);
}

function showOverlay(){
  const bar = $('#liveOverlay');
  if(!bar) return;
  bar.classList.remove('faded');
  if(state.overlayTimer) clearTimeout(state.overlayTimer);
  // Nothing fades while it is being used, or while playback is stopped — and
  // the name of what is playing has to stay put while the list beside it is
  // being read.
  if(state.controlsOpen || state.trackType || state.paused || state.watchOpen) return;
  state.overlayTimer = setTimeout(() => bar.classList.add('faded'), OVERLAY_MS);
}

/**
 * A channel and a film both take the whole screen — a small player pane inside a
 * browsing screen is what a set-top box did, not what a streaming app does.
 */
function openPlayer(item, returnTo){
  state.playerReturn = returnTo;
  state.controlsOpen = false;
  state.pendingSeek = null;
  closeTrackPanel();
  document.body.classList.add('playerOpen');
  $('#playerScreen').classList.remove('hidden');
  describePlayer(item);
  renderControls();
  showOverlay();
}

function onDemand(){ return !!state.current && state.current.kind !== 'LIVE'; }

function describePlayer(item){
  text($('#itemName'), item.name);
  text($('#itemMeta'), itemMeta(item));
  fillArt($('#ovLogo'), item);
  const live = item.kind === 'LIVE';
  $('#ovBadge').classList.toggle('hidden', !live);
  $('#scrubRow').classList.toggle('hidden', live);
  text($('#ovHint'), live
    ? 'מעלה/מטה — ערוץ · ימין/שמאל — מה עוד משודר · אישור — הפקדים · Back — יציאה'
    : 'ימין/שמאל — דילוג · מטה — הפקדים · אישור — נגן/השהה · Back — יציאה');
  if(live) describeLiveNow(item);
  renderProgress();
}

/**
 * The scrubber is the whole point of a player: it has to say where you are,
 * where a pending jump would land, and how much is left.
 */
/** While a channel plays, the meta line carries the programme, not the group. */
function describeLiveNow(item){
  const token = item.id;
  epgFor(item).then(function(list){
    if(!state.current || state.current.id !== token) return;
    const slot = nowOn(list, Date.now());
    if(!slot.current) return;
    const parts = ['עכשיו · ' + slot.current.title];
    if(slot.next) parts.push('אחר כך · ' + epgLine(slot.next));
    text($('#itemMeta'), parts.join('   '));
  });
}

function renderProgress(){
  if(!onDemand()) return;
  const duration = state.duration > 0 ? state.duration : 0;
  const shown = state.pendingSeek !== null ? state.pendingSeek : state.position;
  const ratio = duration > 0 ? Math.max(0, Math.min(shown / duration, 1)) : 0;
  const percent = (ratio * 100).toFixed(2) + '%';

  $('#scrubPlayed').style.width = percent;
  $('#scrubThumb').style.left = percent;
  $('#scrubBuffered').style.width = duration > 0
    ? Math.min(100, ratio * 100 + 6).toFixed(2) + '%' : '0%';
  $('.scrubTrack').classList.toggle('seeking', state.pendingSeek !== null);

  text($('#ovElapsed'), formatClock(shown));
  text($('#ovRemaining'), duration > 0 ? '-' + formatClock(Math.max(0, duration - shown)) : '');
}

// ---- controls ---------------------------------------------------------------

function episodeNeighbour(step){
  if(!state.episodeContext || !state.current) return null;
  return Core.stepInList(state.episodeContext.episodes, state.current.id, step);
}

function renderControls(){
  const row = $('#controlRow');
  if(!row) return;
  // Whenever the bar is up the buttons are on it. Down (or OK) reaches them;
  // hiding them until then meant nobody knew a player had controls at all.
  row.classList.remove('hidden');

  const live = !onDemand();
  const show = (act, visible) => {
    const btn = $('[data-act="' + act + '"]', row);
    if(btn) btn.classList.toggle('hidden', !visible);
  };
  show('restart', !live);
  show('back10', !live);
  show('fwd10', !live);
  // A film has no other channels to flick through.
  show('channels', live);
  // And only a channel the portal keeps can be wound back.
  show('rewind', live && !!archiveChannel());
  show('prevEp', !!episodeNeighbour(-1));
  show('nextEp', !!episodeNeighbour(1));
  setToggleIcon(state.paused);
}

const PLAY_ICON = '<path d="M8 5v14l11-7L8 5z"/>';
const PAUSE_ICON = '<rect x="6" y="5" width="4" height="14" rx="1.2"/>' +
  '<rect x="14" y="5" width="4" height="14" rx="1.2"/>';

/**
 * The main button is a shape, not a word, so it is swapped rather than
 * relabelled — writing text into it would throw the icon away. The name it
 * carries for the remote and for the focused label changes with it.
 */
function setToggleIcon(paused){
  const btn = $('#btnToggle');
  if(!btn) return;
  const icon = $('#toggleIcon', btn);
  if(icon) icon.innerHTML = paused ? PLAY_ICON : PAUSE_ICON;
  const label = paused ? 'נגן' : 'השהה';
  btn.setAttribute('aria-label', label);
  text($('.ctrlName', btn), label);
}

function openControls(){
  state.controlsOpen = true;
  renderControls();
  showOverlay();
  const first = $('#btnToggle');
  if(first && !first.classList.contains('hidden')) setFocus(first);
}

/** Any key wakes the bar; that is where you see what the player can do. */
function wakePlayerUi(){
  renderControls();
  showOverlay();
}

function closeControls(){
  state.controlsOpen = false;
  closeTrackPanel();
  renderControls();
  if(state.focusEl) state.focusEl.classList.remove('focused');
  state.focusEl = null;
  showOverlay();
}

function setPaused(paused){
  state.paused = paused;
  try {
    if(window.webapis && webapis.avplay && webapis.avplay.getState() !== 'NONE'){
      if(paused) webapis.avplay.pause(); else webapis.avplay.play();
    } else {
      const v = $('#htmlVideo');
      if(paused) v.pause(); else v.play().catch(() => {});
    }
  } catch(e) {}
  renderControls();
  showOverlay();
}

function togglePlay(){ setPaused(!state.paused); }

/**
 * Presses on the remote arrive faster than a stream can seek, so they are
 * gathered into one jump: the bar follows every press, the player moves once.
 */
function nudgeSeek(delta){
  if(!onDemand()) return;
  const from = state.pendingSeek !== null ? state.pendingSeek : state.position;
  state.pendingSeek = Core.seekTarget(from, delta, state.duration);
  renderProgress();
  showOverlay();
  if(state.seekTimer) clearTimeout(state.seekTimer);
  state.seekTimer = setTimeout(commitSeek, 450);
}

function commitSeek(){
  if(state.pendingSeek === null) return;
  const target = state.pendingSeek;
  state.pendingSeek = null;
  seekTo(target);
}

function seekTo(seconds){
  const target = Core.seekTarget(seconds, 0, state.duration);
  try {
    if(window.webapis && webapis.avplay && webapis.avplay.getState() !== 'NONE'){
      webapis.avplay.seekTo(Math.round(target * 1000));
    } else {
      $('#htmlVideo').currentTime = target;
    }
  } catch(e) {}
  state.position = target;
  renderProgress();
  rememberPosition();
}

function playNeighbourEpisode(step){
  const next = episodeNeighbour(step);
  if(!next) return false;
  state.vodCol = Math.max(0, state.vodCol + step);
  activateItem(next, 0);
  return true;
}

function runControl(act){
  showOverlay();
  if(act === 'toggle'){ togglePlay(); return; }
  if(act === 'restart'){ seekTo(0); setPaused(false); return; }
  if(act === 'back10'){ nudgeSeek(-Core.SEEK_STEP); return; }
  if(act === 'fwd10'){ nudgeSeek(Core.SEEK_STEP); return; }
  if(act === 'prevEp'){ playNeighbourEpisode(-1); return; }
  if(act === 'nextEp'){ playNeighbourEpisode(1); return; }
  if(act === 'channels'){ closeControls(); openWatchList(); return; }
  if(act === 'rewind'){ closeControls(); rewindLive(); return; }
  if(act === 'audio'){ openTrackPanel('AUDIO'); return; }
  if(act === 'subs'){ openTrackPanel('TEXT'); return; }
}

// ---- audio and subtitle tracks ---------------------------------------------

function availableTracks(type){
  try {
    if(window.webapis && webapis.avplay && webapis.avplay.getState() !== 'NONE'){
      const all = webapis.avplay.getTotalTrackInfo() || [];
      return all.filter(t => t.type === type).map(t => {
        let label = '';
        try { label = JSON.parse(t.extra_info || '{}').track_lang || ''; } catch(e) {}
        return { index: t.index, label: (label || '').trim() || ('רצועה ' + t.index) };
      });
    }
  } catch(e) {}
  return [];
}

function openTrackPanel(type){
  const panel = $('#trackPanel');
  const list = $('#trackList');
  if(!panel || !list) return;
  state.trackType = type;
  text($('#trackTitle'), type === 'AUDIO' ? 'שפת שמע' : 'כתוביות');
  list.innerHTML = '';

  const tracks = availableTracks(type);
  if(!tracks.length){
    const empty = document.createElement('div');
    empty.className = 'trackEmpty';
    empty.textContent = 'הפריט הזה לא מציע רצועות לבחירה';
    list.appendChild(empty);
  } else {
    tracks.forEach(track => {
      const btn = document.createElement('button');
      btn.className = 'focusable trackBtn' + (state.selectedTrack[type] === track.index ? ' active' : '');
      btn.dataset.nav = 'track';
      btn.textContent = track.label;
      btn.addEventListener('click', () => {
        try { webapis.avplay.setSelectTrack(type, track.index); } catch(e) {}
        state.selectedTrack[type] = track.index;
        openTrackPanel(type);
      });
      list.appendChild(btn);
    });
  }

  panel.classList.remove('hidden');
  const first = $('.trackBtn', list);
  if(first) setFocus(first);
}

function closeTrackPanel(){
  const panel = $('#trackPanel');
  if(panel) panel.classList.add('hidden');
  state.trackType = null;
}

function rememberPosition(){
  const item = state.current;
  if(!item) return;
  // A film that never started must not overwrite the position it had before:
  // a failed attempt would otherwise erase where you actually stopped.
  if(item.kind !== 'LIVE' && !(state.position > 0)) return;
  noteWatched(item, item.kind === 'LIVE' ? 0 : state.position, state.duration);
}

function closePlayer(){
  if(state.seekTimer){ clearTimeout(state.seekTimer); state.seekTimer = null; }
  state.pendingSeek = null;
  state.controlsOpen = false;
  state.paused = false;
  closeTrackPanel();
  closeWatchList();
  document.body.classList.remove('playerOpen');
  rememberPosition();
  stopPlayback();
  if(state.progressTimer){ clearInterval(state.progressTimer); state.progressTimer = null; }
  const item = state.current;
  state.current = null;
  state.position = 0;
  state.duration = 0;
  $('#playerScreen').classList.add('hidden');

  // Back from playback lands where it started: the title's page, the guide,
  // the catalogue or the home of whichever world is open.
  if(state.playerReturn === 'detail' && detail.item){
    $('#detailScreen').classList.remove('hidden');
    renderDetailActions();
    renderEpisodes();
    setTimeout(() => setFocus($('#detailPlay')), 40);
  } else if(state.playerReturn === 'live'){
    renderItems();
    focusChannel();
  } else if(state.playerReturn === 'vod'){
    focusVod();
  } else if(state.playerReturn === 'search'){
    focusSearch();
  } else {
    buildHome();
    focusHome();
  }
  return item;
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
  markDrawn('vod', state.vodData, state.vodRowStart);

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
      card.className = 'focusable posterCard' + (state.current && state.current.id === item.id ? ' playing' : '');
      card.dataset.nav = 'vodCard';
      card.dataset.row = String(rowIndex);
      card.dataset.col = String(colIndex);
      card.innerHTML = '<div class="vodPoster"></div><div class="vodCardTitle"></div>';
      $('.vodCardTitle', card).textContent = (isFavorite(item) ? '★ ' : '') + item.name;
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
        // Inside a series the cards are episodes, and an episode plays.
        if(state.episodeContext){ activateItem(item); return; }
        activateFromHome(item);
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
  text($('#vodHeroMeta'), itemMeta(item) + ' · אישור להפעלה');
  const hero = $('#vodHero');
  if(hero) hero.style.backgroundImage = item.logo ? `url("${item.logo}")` : '';
}

function focusVod(){
  const data = state.vodData || [];
  if(!data.length) return;
  state.vodRow = Math.max(0, Math.min(state.vodRow, data.length - 1));
  const row = data[state.vodRow];
  state.vodCol = Math.max(0, Math.min(state.vodCol, row.items.length - 1));

  // Keep the focused row as the second one on screen, the way a TV grid scrolls.
  state.vodRowStart = Math.max(0, Math.min(state.vodRow - 1, Math.max(0, data.length - VOD_ROW_WINDOW)));
  if(needsDraw('vod', data, state.vodRowStart)) renderVodRows();
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
  $('#searchScreen').classList.add('hidden');
  $('#goHome').classList.remove('hidden');
  renderNotes();

  if(mode === 'LIVE'){
    $('#vodScreen').classList.add('hidden');
    $('#liveScreen').classList.remove('hidden');
    text($('#modeHeader'), 'טלוויזיה בלייב');
    $('#modeSwitch').classList.remove('hidden');
    renderLayoutPills();
    $('#search').value = '';
    renderGroups();
    applyFilter();
    setTimeout(() => {
      const first = visible('[data-nav="item"]')[0];
      if(first){ state.liveIndex = 0; focusChannel(); } else { setFocus($('#search')); }
    }, 60);
    return;
  }

  $('#liveScreen').classList.add('hidden');
  $('#vodScreen').classList.remove('hidden');
  $('#modeSwitch').classList.add('hidden');
  const isSeries = mode === 'SERIES';
  text($('#vodHeroTitle'), isSeries ? 'סדרות' : 'סרטים');
  text($('#vodHeroMeta'), isSeries ? 'בחר סדרה כדי לראות את הפרקים' : 'בחר שורה ופריט');
  $('#vodSearch').placeholder = isSeries ? 'חיפוש סדרה' : 'חיפוש סרט';
  $('#vodSearch').value = '';
  applyFilter();
  setTimeout(() => { focusVod(); }, 60);
}

function closeSeries(){
  state.episodeContext = null;
  text($('#vodHeroTitle'), state.mode === 'SERIES' ? 'סדרות' : 'סרטים');
  text($('#vodHeroMeta'), state.mode === 'SERIES' ? 'בחר סדרה כדי לראות את הפרקים' : 'בחר שורה ופריט');
  applyFilter();
  focusVod();
}

function backToHome(){
  if(!$('#playerScreen').classList.contains('hidden')){
    rememberPosition();
    stopPlayback();
    document.body.classList.remove('playerOpen');
    $('#playerScreen').classList.add('hidden');
    state.current = null;
  }
  if(state.world === 'LIVE'){ showMode('LIVE'); return; }
  if(state.world === 'VOD'){ showHome(); return; }
  showChooser();
}

function resetToSetup(){
  stopPlayback();
  document.body.classList.remove('playerOpen');
  $('#playerScreen').classList.add('hidden');
  $('#navLive').classList.add('hidden');
  $('#modeSwitch').classList.add('hidden');
  $('#navVod').classList.add('hidden');
  $('#navSeries').classList.add('hidden');
  $('#navSearch').classList.add('hidden');
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

/**
 * Put the player back where a new stream can be opened.
 *
 * AVPlay's states run NONE → open → IDLE → prepare → READY → play → PLAYING,
 * and stop() only walks back as far as IDLE. open() is legal from NONE alone,
 * so stopping without closing leaves the next open() throwing InvalidAccessError
 * — which is every play after the first, and every rung of the address ladder
 * after the first, meaning anything that needed a second address never played.
 */
function stopPlayback(){
  document.documentElement.classList.remove('avplayOn');
  try {
    if(window.webapis && webapis.avplay){
      if(webapis.avplay.getState() !== 'NONE'){
        try { webapis.avplay.stop(); } catch(e) {}
      }
      if(webapis.avplay.getState() !== 'NONE'){
        try { webapis.avplay.close(); } catch(e) {}
      }
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
      server: item.server, user: item.user, pass: item.pass, logo: item.logo,
      seriesName: item.name
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
  v.onloadedmetadata = () => {
    state.duration = v.duration || 0;
    if(state.resumeAt > 0){ try { v.currentTime = state.resumeAt; } catch(e) {} state.resumeAt = 0; }
  };
  v.ontimeupdate = () => { state.position = v.currentTime || 0; state.duration = v.duration || state.duration; renderProgress(); };
  v.onended = () => { rememberPosition(); if(!playNeighbourEpisode(1)) playerMessage('ההפעלה הסתיימה'); };
  v.onpause = () => { state.paused = true; renderControls(); };
  v.onplay = () => { state.paused = false; renderControls(); };
  v.src = url;
  v.play().catch(() => nextCandidate('הנגן המובנה לא הצליח לנגן את התוכן'));
}

/** Where you stopped is written down while you watch, not only when you leave. */
function startProgressTicker(){
  if(state.progressTimer) clearInterval(state.progressTimer);
  state.progressTimer = setInterval(() => {
    if(!state.current) return;
    if(state.current.kind !== 'LIVE' && state.position > 0) rememberPosition();
  }, 15000);
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
  const tried = (state.candidates || []).length;
  playerMessage((reason || 'לא הצלחתי לנגן את הפריט') +
    (tried > 1 ? ' · נוסו ' + tried + ' כתובות' : ''));
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
      // The page must stop painting where the video plane is, or the viewer
      // gets sound and a black screen.
      document.documentElement.classList.add('avplayOn');
      const rect = pictureRect();
      webapis.avplay.setDisplayRect(rect[0], rect[1], rect[2], rect[3]);
      try { webapis.avplay.setDisplayMethod('PLAYER_DISPLAY_MODE_LETTER_BOX'); } catch(e) {}
      webapis.avplay.setListener({
        onbufferingstart: () => playerMessage('טוען...'),
        onbufferingcomplete: () => playerMessage(''),
        onstreamcompleted: () => {
          rememberPosition();
          // A finished episode rolls into the next one, the way a season is watched.
          if(!playNeighbourEpisode(1)) playerMessage('ההפעלה הסתיימה');
        },
        onerror: err => nextCandidate('שגיאת ניגון: ' + err),
        onevent: () => {},
        oncurrentplaytime: ms => { state.position = (ms || 0) / 1000; renderProgress(); },
        ondrmevent: () => {}
      });
      webapis.avplay.prepareAsync(
        () => {
          playerMessage('');
          try { state.duration = (webapis.avplay.getDuration() || 0) / 1000; } catch(e) {}
          if(state.resumeAt > 0){
            try { webapis.avplay.seekTo(Math.round(state.resumeAt * 1000)); } catch(e) {}
            state.resumeAt = 0;
          }
          webapis.avplay.play();
          renderProgress();
        },
        err => nextCandidate('שגיאת הכנה: ' + err)
      );
    }catch(e){
      nextCandidate('שגיאת ניגון: ' + (e.message || e));
    }
    return;
  }

  playHtml(url);
}

async function activateItem(item, resumeAt){
  if(item.isSeriesStub){ await openSeries(item); return; }
  if(!item.url){ playerMessage('אין כתובת ניגון לפריט זה'); return; }

  const returnTo = state.current ? state.playerReturn : currentNavMode();
  state.current = item;
  state.paused = false;
  state.pendingSeek = null;
  state.selectedTrack = {};
  // Anything picked anywhere resumes, not only what the home row offers.
  state.resumeAt = resumeAt === undefined ? resumeFor(item) : (resumeAt || 0);
  state.position = state.resumeAt;
  state.duration = 0;
  openPlayer(item, returnTo === 'player' ? state.playerReturn : returnTo);
  noteWatched(item, state.resumeAt, 0);
  startProgressTicker();
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

function navChoose(active, dir){
  const worlds = visible('[data-nav="world"]');
  const actions = visible('[data-nav="topAction"]');
  const type = active && active.dataset ? active.dataset.nav : null;
  if(type === 'world'){
    if(dir === 'left' || dir === 'right') return moveRtlRow(worlds, active, dir) || active;
    if(dir === 'up') return actions[0] || active;
    return active;
  }
  if(type === 'topAction'){
    if(dir === 'left' || dir === 'right') return moveRtlRow(actions, active, dir) || active;
    if(dir === 'down') return worlds[0] || active;
    return active;
  }
  return worlds[0] || active;
}

function navHome(active, dir){
  const actions = visible('[data-nav="topAction"]');
  const type = active && active.dataset ? active.dataset.nav : null;

  if(type === 'topAction'){
    if(dir === 'left' || dir === 'right') return moveRtlRow(actions, active, dir) || active;
    if(dir === 'down'){ if(state.home.rows.length){ focusHome(); return null; } return active; }
    return active;
  }
  if(type === 'homeCard'){
    if(dir === 'up' && state.home.row === 0) return actions[0] || active;
    if(dir === 'up'){ moveHome(-1, 0); return null; }
    if(dir === 'down'){ moveHome(1, 0); return null; }
    // The rows read right to left, so right moves back through the row.
    if(dir === 'right'){ moveHome(0, -1); return null; }
    if(dir === 'left'){ moveHome(0, 1); return null; }
    return active;
  }
  if(state.home.rows.length){ focusHome(); return null; }
  return actions[0] || active;
}

function navLive(active, dir){
  const type = active && active.dataset ? active.dataset.nav : null;
  const groups = visible('[data-nav="group"]');
  const actions = visible('[data-nav="topAction"]');

  if(type === 'item'){
    const cols = liveCols();
    const col = state.liveIndex % cols;
    if(dir === 'up' && state.liveIndex < cols) return groups[0] || $('#search');
    if(dir === 'up'){ moveChannel(-1, 0); return null; }
    if(dir === 'down'){ moveChannel(1, 0); return null; }
    // Right moves back along an RTL row, left moves forward. A list is one
    // channel wide, so neither goes anywhere.
    if(dir === 'right'){ if(col > 0) moveChannel(0, -1); return null; }
    if(dir === 'left'){ if(col < cols - 1) moveChannel(0, 1); return null; }
    return active;
  }
  if(type === 'search'){
    if(dir === 'down') return groups[0] || visible('[data-nav="item"]')[0] || active;
    if(dir === 'up') return actions[0] || active;
    return active;
  }
  if(type === 'group'){
    if(dir === 'left' || dir === 'right') return moveRtlRow(groups, active, dir) || active;
    if(dir === 'down'){ if(state.filtered.length){ focusChannel(); return null; } return active; }
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

/**
 * While watching, the D-pad belongs to the content: channels zap up and down, a
 * film seeks, and Down brings up the controls the way a streaming app does.
 * Once the controls are open the same D-pad moves between them.
 */
function navPlayer(active, dir){
  wakePlayerUi();
  const live = !onDemand();

  if(state.watchOpen) return navWatch(active, dir);

  if(state.trackType){
    const tracks = visible('[data-nav="track"]');
    if(dir === 'up' || dir === 'down'){
      const at = tracks.indexOf(active);
      const next = tracks[at + (dir === 'down' ? 1 : -1)];
      return next || active;
    }
    if(dir === 'left' || dir === 'right'){ closeTrackPanel(); openControls(); return null; }
    return active;
  }

  if(state.controlsOpen){
    // The row reads left to right, so the arrows move through it that way.
    const buttons = visible('#controlRow .ctrlBtn');
    if(dir === 'left' || dir === 'right'){
      const at = buttons.indexOf(active);
      return buttons[at + (dir === 'right' ? 1 : -1)] || active;
    }
    if(dir === 'down') return active;
    if(dir === 'up'){ closeControls(); return null; }
    return active;
  }

  // On a channel the D-pad stays what it is on a television: up and down are
  // the channel. On a film there is nothing to zap, so down opens the controls.
  if(live){
    // Channel up and down stay what they are on a television. The buttons are
    // on the bar in front of you; OK reaches them.
    if(dir === 'up') zap(-1);
    if(dir === 'down') zap(1);
    // Sideways is what every set-top box means by "what else is on".
    if(dir === 'left' || dir === 'right') openWatchList();
    return null;
  }
  if(dir === 'down'){ openControls(); return null; }
  // The timeline is left to right even in a right-to-left interface, so the
  // arrows follow it and not the text: left goes back, right goes forward.
  if(dir === 'left') nudgeSeek(-Core.SEEK_STEP);
  if(dir === 'right') nudgeSeek(Core.SEEK_STEP);
  if(dir === 'up') showOverlay();
  return null;
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
  if(!$('#playerScreen').classList.contains('hidden')) return 'player';
  if(!$('#setupScreen').classList.contains('hidden')) return 'setup';
  if(!$('#chooseScreen').classList.contains('hidden')) return 'choose';
  if(!$('#detailScreen').classList.contains('hidden')) return 'detail';
  if(!$('#homeScreen').classList.contains('hidden')) return 'home';
  if(!$('#searchScreen').classList.contains('hidden')) return 'search';
  if(!$('#liveScreen').classList.contains('hidden')) return 'live';
  return 'vod';
}

function navSearch(active, dir){
  const type = active && active.dataset ? active.dataset.nav : null;
  const actions = visible('[data-nav="topAction"]');

  if(type === 'searchCard'){
    if(dir === 'up' && state.search.row === 0) return $('#globalSearch');
    if(dir === 'up'){ moveSearch(-1, 0); return null; }
    if(dir === 'down'){ moveSearch(1, 0); return null; }
    if(dir === 'right'){ moveSearch(0, -1); return null; }
    if(dir === 'left'){ moveSearch(0, 1); return null; }
    return active;
  }
  if(type === 'globalSearch'){
    if(dir === 'down'){ if(state.search.rows.length){ focusSearch(); return null; } return active; }
    if(dir === 'up') return actions[0] || active;
    return active;
  }
  if(type === 'topAction'){
    if(dir === 'left' || dir === 'right') return moveRtlRow(actions, active, dir) || active;
    if(dir === 'down') return $('#globalSearch');
    return active;
  }
  return $('#globalSearch');
}

function moveFocus(dir){
  const active = state.focusEl || document.activeElement;
  let next = null;
  const mode = currentNavMode();
  if(mode === 'setup') next = navSetup(active, dir);
  else if(mode === 'choose') next = navChoose(active, dir);
  else if(mode === 'detail') next = navDetail(active, dir);
  else if(mode === 'home') next = navHome(active, dir);
  else if(mode === 'player') next = navPlayer(active, dir);
  else if(mode === 'search') next = navSearch(active, dir);
  else if(mode === 'live') next = navLive(active, dir);
  else next = navVod(active, dir);
  // A null answer means the screen moved its own selection already.
  if(next) setFocus(next);
}

/**
 * Typing on a TV is a mode of its own. The on-screen keyboard reads the same
 * D-pad this app navigates with, so while it is open every key belongs to it —
 * otherwise the arrows move the highlight out of the field mid-word and the
 * text simply stops arriving. Fields stay read-only (which keeps the keyboard
 * from erupting on every pass) until OK opens them, and Back closes them.
 */
function editableFields(){
  return $$('input[type="text"], input[type="password"]');
}

function isEditing(){
  return !!state.editingEl && document.activeElement === state.editingEl;
}

function beginEdit(el){
  if(!el || el.tagName !== 'INPUT') return;
  el.removeAttribute('readonly');
  // Focus first: focusing this field blurs the previous one, and that blur
  // must not be the thing that turns editing back off.
  try { el.focus(); } catch(e) {}
  state.editingEl = el;
  // Put the caret after what is already there rather than over it.
  try { const v = el.value; el.value = ''; el.value = v; } catch(e) {}
}

/**
 * Leaving a field. `advance` hands over the next one to fill in, which is what
 * closing the keyboard means in practice — the text is already in the field, so
 * there is nothing to cancel by staying on it.
 */
function endEdit(advance){
  const el = state.editingEl;
  state.editingEl = null;
  if(!el) return;
  state.editExit = 'handled';
  el.setAttribute('readonly', 'readonly');
  try { el.blur(); } catch(e) {}
  state.editExit = null;
  if(advance === false){ setFocus(el); return; }
  focusNextField(el);
}

/** The next thing to fill in, or the button that uses what was filled in. */
function focusNextField(el){
  const fields = editableFields().filter(isVisible);
  const next = fields[fields.indexOf(el) + 1];
  if(next){ setFocus(next); return; }
  const submit = visible('.bigBtn')[0];
  if(submit){ setFocus(submit); return; }
  setFocus(el);
  moveFocus('down');
}

/**
 * The on-screen keyboard closes itself when its "done" key is pressed, and all
 * the app sees is a blur. That is the moment to move on: nobody types a server
 * address in order to stand on it afterwards.
 */
function afterKeyboardClosed(el){
  if(state.editExit === 'handled') return;
  setTimeout(() => {
    if(state.editingEl) return;
    focusNextField(el);
  }, 60);
}

function onEnter(){
  // In the player, OK is play/pause until the controls are open.
  if(currentNavMode() === 'player' && !state.controlsOpen && !state.trackType && !state.watchOpen){
    if(onDemand()) togglePlay(); else openControls();
    return;
  }
  const a = state.focusEl || document.activeElement;
  if(!a) return;
  if(a.tagName === 'BUTTON') { a.click(); return; }
  if(a.tagName === 'INPUT') { beginEdit(a); return; }
}

function handleBack(){
  if(!$('#setupScreen').classList.contains('hidden')) return;
  if(!$('#playerScreen').classList.contains('hidden')){
    // Back closes what is open on top of the picture before leaving it.
    if(state.trackType){ closeTrackPanel(); openControls(); return; }
    if(state.controlsOpen){ closeControls(); return; }
    if(state.watchOpen){ closeWatchList(); return; }
    closePlayer();
    return;
  }
  // The title's page is on top of whatever opened it, so it closes first — a
  // series shown there also holds an episode list, and that is not a screen of
  // its own to back out of.
  if(!$('#detailScreen').classList.contains('hidden')){ closeDetail(); return; }
  // Inside a series in the catalogue, Back returns to the list of series.
  if(state.episodeContext){
    closeSeries();
    return;
  }
  // At the door, Back leaves for the source form; inside a world it returns
  // to the world's own home, and from there to the door.
  if(!$('#chooseScreen').classList.contains('hidden')){ resetToSetup(); return; }
  if(!$('#homeScreen').classList.contains('hidden') ||
     (state.world === 'LIVE' && !$('#liveScreen').classList.contains('hidden') && !state.episodeContext)){
    showChooser();
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
  // The switch is one object to look at and two buttons to walk along, so it
  // is the halves that the remote knows about, not the box around them.
  ['#goHome', '#navLive', '#navGrid', '#navVideo', '#navVod', '#navSeries', '#navSearch', '#backToSetup']
    .forEach(sel => { $(sel).dataset.nav = 'topAction'; });
  $('#worldLive').dataset.nav = 'world';
  $('#worldVod').dataset.nav = 'world';
  $('#worldLive').addEventListener('click', () => enterWorld('LIVE'));
  $('#worldVod').addEventListener('click', () => enterWorld('VOD'));
  $('#detailPlay').addEventListener('click', playFromDetail);
  $('#detailFavorite').addEventListener('click', () => {
    if(!detail.item) return;
    toggleFavorite(detail.item);
    renderDetailActions();
  });
  $('#detailWatched').addEventListener('click', markFromDetail);
  $('#globalSearch').dataset.nav = 'globalSearch';

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
  $('#navLive').addEventListener('click', () => showMode('LIVE'));
  $('#navGrid').addEventListener('click', () => setGuideLayout('grid'));
  $('#navVideo').addEventListener('click', () => setGuideLayout('video'));
  $('#watchPicture').dataset.nav = 'watchPicture';
  $('#watchPicture').addEventListener('click', closeWatchList);
  $('#navVod').addEventListener('click', () => showMode('MOVIES'));
  $('#navSeries').addEventListener('click', () => showMode('SERIES'));
  $('#navSearch').addEventListener('click', showSearch);
  $$('#controlRow .ctrlBtn').forEach(btn => {
    btn.dataset.nav = 'control';
    btn.addEventListener('click', () => runControl(btn.dataset.act));
  });
  $('#globalSearch').addEventListener('input', () => {
    state.search.row = 0; state.search.col = 0; state.search.rowStart = 0;
    renderSearch();
  });
  $('#goHome').addEventListener('click', backToHome);
  $('#backToSetup').addEventListener('click', resetToSetup);

  editableFields().forEach(el => {
    el.setAttribute('readonly', 'readonly');
    el.addEventListener('blur', () => {
      // Only the field being edited ends editing; a neighbour losing focus
      // because this one gained it is not the end of anything.
      const wasEditing = state.editingEl === el;
      if(wasEditing) state.editingEl = null;
      el.setAttribute('readonly', 'readonly');
      if(wasEditing) afterKeyboardClosed(el);
    });
    // A pointer (or a remote's cursor mode) should open the keyboard too.
    el.addEventListener('click', () => { if(!isEditing()) beginEdit(el); });
  });

  document.addEventListener('keydown', (e) => {
    const code = e.keyCode;

    // While the keyboard is open it owns the remote, except for the way out.
    //
    // A Samsung keyboard does not report that it closed — no blur, no event —
    // so the app cannot wait for one. What it does send is 65376 when "done"
    // is pressed and 65385 when the keyboard is cancelled, and Back always
    // arrives. All three end the field; only an explicit cancel stays on it.
    if(isEditing()){
      if(code === 65385){ endEdit(false); e.preventDefault(); return; }
      if(code === 65376 || code === 10009 || e.key === 'Escape'){
        endEdit(true);
        e.preventDefault();
        return;
      }
      return;
    }
    if(e.key === 'ArrowLeft' || code === 37){ moveFocus('left'); e.preventDefault(); return; }
    if(e.key === 'ArrowRight' || code === 39){ moveFocus('right'); e.preventDefault(); return; }
    if(e.key === 'ArrowUp' || code === 38){ moveFocus('up'); e.preventDefault(); return; }
    if(e.key === 'ArrowDown' || code === 40){ moveFocus('down'); e.preventDefault(); return; }
    if(e.key === 'Enter' || code === 13){ onEnter(); e.preventDefault(); return; }
    if(e.key === 'Backspace' || code === 10009){ handleBack(); e.preventDefault(); return; }
    // The yellow key on a Samsung remote, and F on a desktop keyboard.
    if(code === 405 || e.key === 'f' || e.key === 'F'){ favoriteFocused(); e.preventDefault(); return; }
    // The transport keys, which a Samsung remote sends whatever is focused.
    if(currentNavMode() === 'player'){
      if(code === 415 || code === 10252){ togglePlay(); e.preventDefault(); return; }   // Play / PlayPause
      if(code === 19){ setPaused(true); e.preventDefault(); return; }                    // Pause
      if(code === 413){ closePlayer(); e.preventDefault(); return; }                     // Stop
      if(code === 417){ nudgeSeek(Core.SEEK_STEP_LONG); e.preventDefault(); return; }    // FastForward
      if(code === 412){ nudgeSeek(-Core.SEEK_STEP_LONG); e.preventDefault(); return; }   // Rewind
    }
    if(state.mode === 'LIVE' && (code === 427 || code === 33)){
      zap(1);
      e.preventDefault();
      return;
    }
    if(state.mode === 'LIVE' && (code === 428 || code === 34)){
      zap(-1);
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
      ['MediaPlayPause','MediaPlay','MediaPause','MediaStop','MediaRewind','MediaFastForward',
       'ChannelUp','ChannelDown','ColorF2Yellow']
        .forEach(k => { try { tizen.tvinputdevice.registerKey(k); } catch(e) {} });
    }
  } catch(e) {}
}

/** Whatever is focused — or playing — goes in or out of the favourites row. */
function favoriteFocused(){
  const mode = currentNavMode();
  let item = null;
  if(mode === 'player') item = state.current;
  else if(mode === 'home') item = homeItemAt(state.home.row, state.home.col);
  else if(mode === 'search') item = searchItemAt(state.search.row, state.search.col);
  else if(mode === 'live') item = state.filtered[state.liveIndex];
  else if(mode === 'vod') item = vodItemAt(state.vodRow, state.vodCol);
  if(!item) return;

  const added = toggleFavorite(item);
  if(mode === 'home'){
    buildHome();
    focusHome();
  } else if(mode === 'live'){
    renderItems();
    focusChannel();
  } else if(mode === 'search'){
    focusSearch();
  } else if(mode === 'vod'){
    focusVod();
  } else {
    text($('#itemMeta'), itemMeta(item) + (added ? ' · נוסף למועדפים' : ' · הוסר מהמועדפים'));
  }
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

loadPrefs();
wireStatic();
registerKeys();

/**
 * ?test=1 exposes the player's internals so a browser test can drive them
 * without a real stream — a TV app cannot be checked any other way from here.
 * Nothing reads this in a normal launch.
 */
if(/[?&]test=1/.test(location.search)){
  window.__talohim = {
    state: state,
    nudgeSeek: nudgeSeek,
    seekTo: seekTo,
    runControl: runControl,
    openControls: openControls,
    closeControls: closeControls,
    renderProgress: renderProgress,
    renderControls: renderControls,
    playNeighbourEpisode: playNeighbourEpisode
  };
}

const demo = /[?&]demo=(\d+)/.exec(location.search);
if(demo){
  state.source = {type: 'demo'};
  finishLoad(demoItems(Math.min(Number(demo[1]) || 0, 50000)));
}
loadSavedSource();
setSourceTab(state.sourceTab);

/**
 * A TV app that asks you to log in every time you turn it on is a form, not an
 * app: when a source is already saved, connect to it and open on the content.
 * The splash stays up while that happens, so the login form does not flash by.
 */
/**
 * A build that carries its own details signs itself in. Two portals of the same
 * account are tried in turn, because one of them is regularly the one that is
 * down — and a TV app should not answer that with a login form.
 */
async function connectDev(dev){
  const servers = (dev.servers && dev.servers.length ? dev.servers : [dev.server]).filter(Boolean);
  setSourceTab('xtream');
  $('#xtUser').value = dev.user;
  $('#xtPass').value = dev.pass;
  $('#xtVod').checked = dev.includeVod !== false;

  for(let i = 0; i < servers.length; i++){
    $('#xtServer').value = servers[i];
    setError('מתחבר ל-' + servers[i] + '...');
    if(await loadXtream()) return true;
  }
  hideSplash();
  return false;
}

function autoConnect(){
  // A development build can carry its own sign-in details and skip the form.
  // ?setup=1 asks for the form on purpose, so a build that signs itself in can
  // still be pointed at another source (and so the tests can reach the form).
  const wantsForm = /[?&]setup=1/.test(location.search);
  const dev = (typeof window !== 'undefined' && window.TalohimDev) || null;
  const devServers = dev && (dev.servers || dev.server);
  if(!wantsForm && !state.source && dev && devServers && dev.user && dev.pass){
    connectDev(dev);
    return true;
  }

  const saved = state.source;
  if(!saved) return false;
  if(saved.type === 'm3u' && $('#m3uUrl').value.trim()){ loadM3u(); return true; }
  if(saved.type === 'xtream' && $('#xtServer').value.trim()){ loadXtream(); return true; }
  return false;
}

const connecting = demo ? false : autoConnect();
setTimeout(() => {
  // Only when the form is still what is on screen: the home screen sets its own.
  if(!$('#setupScreen').classList.contains('hidden')) {
    setFocus(visible('[data-nav="setupTab"]')[0] || $('#m3uUrl'));
  }
}, 150);
setTimeout(hideSplash, connecting ? 9000 : 1300);


})();
