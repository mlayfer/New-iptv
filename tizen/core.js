/*
 * Pure logic, shared by the Tizen app and its tests, and deliberately kept
 * behaviourally identical to the Kotlin under app/src/main/java/.../data/.
 * Both implementations are checked against the same fixtures in shared/parity/,
 * so a change to one that is not mirrored in the other fails the build.
 *
 * Nothing here may touch the DOM, the network, or Tizen APIs.
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  root.TalohimCore = api;
})(typeof window !== 'undefined' ? window : globalThis, function () {

  function normalizeServer(v) {
    let s = (v || '').trim().replace(/\/+$/, '');
    if (!/^https?:\/\//i.test(s)) s = 'http://' + s;
    return s.replace(/\/player_api\.php.*$/i, '');
  }

  function attrs(s) {
    const out = {};
    const re = /([\w-]+)\s*=\s*"([^"]*)"/g;
    let m;
    while ((m = re.exec(s))) out[m[1].toLowerCase()] = m[2];
    return out;
  }

  function looksLikeSeries(name, group) {
    const v = ((name || '') + ' ' + (group || '')).toLowerCase();
    return /(series|serial|episode|season|episodes|סדרה|סדרות|עונה)/i.test(v);
  }

  function guessKind(url, group, name) {
    if (/\/(movie|series)\//i.test(url)) return 'VOD';
    if (/\.(mp4|mkv|avi|mov|m4v|flv|webm)(\?|$)/i.test(url)) return 'VOD';
    if (/(vod|movies?|series|סרט|סרטים|סדרות|סדרה)/i.test((group || '') + ' ' + (name || ''))) return 'VOD';
    return 'LIVE';
  }

  function placeholderText(name) {
    return ((name || '?').trim().slice(0, 2)).toUpperCase();
  }

  function parseM3u(textBody) {
    const lines = String(textBody).replace(/\r/g, '').split('\n');
    const out = [];
    let pending = null;
    let extgrp = null;

    for (const raw of lines) {
      const line = raw.trim();
      if (!line) continue;

      if (line.startsWith('#EXTINF')) {
        // The display name follows the first comma outside a quoted attribute.
        let rest = line.substring(line.indexOf(':') + 1), q = false, cut = -1;
        for (let i = 0; i < rest.length; i++) {
          if (rest[i] === '"') q = !q;
          else if (rest[i] === ',' && !q) { cut = i; break; }
        }
        const left = cut >= 0 ? rest.slice(0, cut) : rest;
        const name = cut >= 0 ? rest.slice(cut + 1).trim() : '';
        const a = attrs(left);
        const tvgName = (a['tvg-name'] || '').trim();
        pending = {
          name: name || tvgName || 'ללא שם',
          alias: tvgName && tvgName.toLowerCase() !== (name || '').toLowerCase() ? tvgName : null,
          group: a['group-title'] || null,
          logo: a['tvg-logo'] || null,
          tvgId: a['tvg-id'] || null,
          userAgent: null,
          referrer: null
        };
        extgrp = null;
        continue;
      }

      if (line.startsWith('#EXTGRP')) {
        extgrp = line.split(':').slice(1).join(':').trim() || null;
        continue;
      }

      if (line.startsWith('#EXTVLCOPT') || line.startsWith('#KODIPROP')) {
        const val = line.split(':').slice(1).join(':');
        const idx = val.indexOf('=');
        if (idx > 0 && pending) {
          const k = val.slice(0, idx).trim().toLowerCase();
          const v = val.slice(idx + 1).trim();
          if (k.endsWith('user-agent')) pending.userAgent = v;
          if (k.endsWith('http-referrer') || k.endsWith('referer')) pending.referrer = v;
        }
        continue;
      }

      if (line.startsWith('#')) continue;

      if (/^[a-z][a-z0-9+.-]*:\/\//i.test(line)) {
        const base = pending || { name: line };
        const group = base.group || extgrp || 'ללא קטגוריה';
        const kind = guessKind(line, group, base.name);
        out.push({
          id: 'm' + out.length,
          name: base.name,
          group: group,
          kind: kind,
          contentType: kind === 'LIVE' ? 'LIVE' : (looksLikeSeries(base.name, group) ? 'SERIES' : 'MOVIE'),
          url: line,
          alias: base.alias || null,
          logo: base.logo || null,
          tvgId: base.tvgId || null,
          userAgent: base.userAgent || null,
          referrer: base.referrer || null
        });
        pending = null;
        extgrp = null;
      }
    }
    return out;
  }

  /**
   * The same Xtream channel is reachable at several endpoints, and panels enable
   * only some: `/live/USER/PASS/ID.m3u8`, `.../ID.ts`, `.../ID` with no
   * extension. A playlist hands out one of them, so a disabled HLS output looks
   * like a dead channel — the panel answers with an HTML page and HTTP 200.
   * Only numeric stream ids are rewritten, so a real playlist name is left be.
   */
  function streamVariants(url) {
    const out = [url];
    const cut = url.search(/[?#]/);
    const base = cut === -1 ? url : url.slice(0, cut);
    const query = cut === -1 ? '' : url.slice(cut);

    const slash = base.lastIndexOf('/');
    if (slash <= 0) return out;

    const prefix = base.slice(0, slash);
    const last = base.slice(slash + 1);
    const id = last.split('.')[0];
    if (!id || !/^\d+$/.test(id)) return out;

    [id + '.ts', id, id + '.m3u8'].forEach(function (candidate) {
      const u = prefix + '/' + candidate + query;
      if (out.indexOf(u) === -1) out.push(u);
    });

    if (prefix.indexOf('/live/') !== -1) {
      const legacy = prefix.replace('/live/', '/') + '/' + id + query;
      if (out.indexOf(legacy) === -1) out.push(legacy);
    }

    return out.slice(0, 4);
  }

  /**
   * What to call an episode. Portals already write the numbering into the title
   * more often than not — "ניתוק - S01E04 - האתה שאתה" — and prefixing our own
   * produced "S1E4 · ניתוק - S01E04 - האתה שאתה" on screen. So the series name
   * is trimmed off the front when it is repeated there, and the numbering is
   * added only when the title does not already carry it.
   */
  const EPISODE_MARK = /s\s?\d{1,2}\s?e\s?\d{1,3}/i;
  const HEBREW_EPISODE_MARK = /פרק\s*\d/;

  function episodeName(title, season, number, seriesName) {
    let name = String(title || '').trim();
    const series = String(seriesName || '').trim();
    if (series && name.toLowerCase().indexOf(series.toLowerCase()) === 0) {
      name = name.slice(series.length).replace(/^\s*[-–—·:|]\s*/, '').trim();
    }
    if (!name) name = 'פרק ' + number;
    if (EPISODE_MARK.test(name) || HEBREW_EPISODE_MARK.test(name)) return name;
    return 'S' + season + 'E' + number + ' · ' + name;
  }

  /**
   * Every episode of a series, in season order. Portals return the seasons as an
   * object whose keys are strings and whose order means nothing.
   */
  function episodesFromSeriesInfo(info, source) {
    const seasons = (info && info.episodes) || {};
    const enc = encodeURIComponent;
    const credentials = enc(source.user) + '/' + enc(source.pass);
    const out = [];

    Object.keys(seasons)
      .sort(function (a, b) { return (Number(a) || 0) - (Number(b) || 0); })
      .forEach(function (season) {
        (seasons[season] || []).forEach(function (episode, index) {
          const id = episode && episode.id != null ? String(episode.id) : '';
          if (!id) return;
          const ext = episode.container_extension || 'mp4';
          const number = episode.episode_num != null ? String(episode.episode_num) : String(index + 1);
          out.push({
            id: 'e' + id,
            name: episodeName(episode.title, season, number, source.seriesName),
            group: 'עונה ' + season,
            kind: 'VOD',
            contentType: 'EPISODE',
            url: source.server + '/series/' + credentials + '/' + id + '.' + ext,
            logo: (episode.info && episode.info.movie_image) || source.logo || null
          });
        });
      });

    return out;
  }

  /**
   * What the home screen shows, decided in one place because both apps must
   * agree. A home screen is not a menu: it is the answer to "what do I watch
   * now", so it opens with what you were watching, then what you marked, then
   * what you watched last, and only then the catalogue.
   *
   * input: { items, history: [{id, at, position, duration}], favorites: [id] }
   */
  const RESUME_MIN_SECONDS = 30;
  const RESUME_MAX_RATIO = 0.95;

  function isResumable(entry) {
    if (!entry || !(entry.position > 0)) return false;
    if (entry.position < RESUME_MIN_SECONDS) return false;
    if (entry.duration > 0 && entry.position > entry.duration * RESUME_MAX_RATIO) return false;
    return true;
  }

  function progressRatio(entry) {
    if (!entry || !(entry.duration > 0) || !(entry.position > 0)) return 0;
    const r = entry.position / entry.duration;
    return r < 0 ? 0 : (r > 1 ? 1 : r);
  }

  function topGroups(pool, limit, perRow) {
    const order = [];
    const byGroup = {};
    pool.forEach(function (item) {
      const g = item.group || 'ללא קטגוריה';
      if (!byGroup[g]) { byGroup[g] = []; order.push(g); }
      byGroup[g].push(item);
    });
    // Biggest category first; ties keep the order the portal returned them in,
    // so the home screen does not reshuffle itself between loads. The original
    // positions are captured before sorting — indexOf on an array being sorted
    // answers about a half-sorted array.
    const rank = {};
    order.forEach(function (g, i) { rank[g] = i; });
    order.sort(function (a, b) {
      const d = byGroup[b].length - byGroup[a].length;
      return d !== 0 ? d : rank[a] - rank[b];
    });
    return order.slice(0, limit).map(function (g) {
      return { key: 'group:' + g, title: g, items: byGroup[g].slice(0, perRow) };
    });
  }

  function buildHomeRows(input) {
    const items = (input && input.items) || [];
    const history = (input && input.history) || [];
    const favorites = (input && input.favorites) || [];

    const byId = {};
    items.forEach(function (item) { byId[item.id] = item; });

    const ordered = history.slice().sort(function (a, b) { return (b.at || 0) - (a.at || 0); });
    const favoriteSet = {};
    favorites.forEach(function (id) { favoriteSet[id] = true; });

    const rows = [];

    const resume = [];
    ordered.forEach(function (entry) {
      const item = byId[entry.id];
      if (!item || item.kind === 'LIVE') return;
      if (!isResumable(entry)) return;
      if (resume.length < 12) {
        const copy = {};
        Object.keys(item).forEach(function (k) { copy[k] = item[k]; });
        copy.resumeAt = entry.position;
        copy.progress = progressRatio(entry);
        resume.push(copy);
      }
    });
    if (resume.length) rows.push({ key: 'continue', title: 'המשך לצפות', items: resume });

    const favs = [];
    favorites.forEach(function (id) {
      const item = byId[id];
      if (item && favs.length < 20) favs.push(item);
    });
    if (favs.length) rows.push({ key: 'favorites', title: 'המועדפים שלי', items: favs });

    const recentLive = [];
    ordered.forEach(function (entry) {
      const item = byId[entry.id];
      if (!item || item.kind !== 'LIVE' || favoriteSet[item.id]) return;
      if (recentLive.length < 12) recentLive.push(item);
    });
    if (recentLive.length) rows.push({ key: 'recentLive', title: 'ערוצים שנצפו לאחרונה', items: recentLive });

    const live = items.filter(function (x) { return x.kind === 'LIVE'; });
    topGroups(live, 3, 20).forEach(function (row) {
      rows.push({ key: 'live:' + row.title, title: row.title, items: row.items });
    });

    const movies = items.filter(function (x) { return x.kind !== 'LIVE' && x.contentType !== 'SERIES'; });
    topGroups(movies, 3, 20).forEach(function (row) {
      rows.push({ key: 'movie:' + row.title, title: row.title, items: row.items });
    });

    const series = items.filter(function (x) { return x.contentType === 'SERIES'; });
    if (series.length) rows.push({ key: 'series', title: 'סדרות', items: series.slice(0, 20) });

    return rows.filter(function (row) { return row.items.length > 0; });
  }

  /**
   * Playback arithmetic, shared so the two players behave identically: where a
   * seek lands, what the clock reads, and which item comes next in a series.
   */
  const SEEK_STEP = 10;
  const SEEK_STEP_LONG = 60;

  function seekTarget(position, delta, duration) {
    let target = (position || 0) + delta;
    if (target < 0) target = 0;
    // Landing exactly on the end restarts or stalls depending on the player, so
    // stop just short of it.
    if (duration > 0 && target > duration - 1) target = Math.max(0, duration - 1);
    return target;
  }

  function formatClock(seconds) {
    let s = Math.round(seconds || 0);
    if (!isFinite(s) || s < 0) s = 0;
    const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
    const pad = function (n) { return (n < 10 ? '0' : '') + n; };
    return h ? h + ':' + pad(m) + ':' + pad(sec) : m + ':' + pad(sec);
  }

  /** The neighbour of what is playing, or null at either end of the season. */
  function stepInList(items, currentId, step) {
    const list = items || [];
    let at = -1;
    for (let i = 0; i < list.length; i++) {
      if (list[i] && list[i].id === currentId) { at = i; break; }
    }
    if (at === -1) return null;
    const next = at + step;
    return next >= 0 && next < list.length ? list[next] : null;
  }

  /**
   * Matching a title someone half-remembers, in either language.
   *
   * Two things get in the way. Providers list a show under one name only —
   * "ניתוק" and never "Severance" — and when they do use the original name they
   * spell it in Hebrew letters ("ברייקינג באד"). The first is answered by any
   * original-title field the portal happens to send, kept next to the name. The
   * second is answered here: both sides are reduced to their consonants in one
   * shared alphabet, so a Latin query and a Hebrew spelling of the same sounds
   * meet in the middle. A translated name still cannot be guessed from the
   * other language, and nothing here pretends otherwise.
   */
  const HEBREW_SKELETON = {
    'א': '', 'ב': 'B', 'ג': 'G', 'ד': 'D', 'ה': '', 'ו': '', 'ז': 'Z', 'ח': 'X',
    'ט': 'T', 'י': '', 'כ': 'K', 'ך': 'K', 'ל': 'L', 'מ': 'M', 'ם': 'M',
    'נ': 'N', 'ן': 'N', 'ס': 'S', 'ע': '', 'פ': 'P', 'ף': 'P', 'צ': 'C',
    'ץ': 'C', 'ק': 'K', 'ר': 'R', 'ש': 'S', 'ת': 'T'
  };
  // Grammar words, and the Hebrew spellings of those same words as they appear
  // in transliterated titles ("גיים אוף ת'רונס"). Dropping them on both sides
  // keeps one spelling from hiding the other.
  const SKELETON_STOP_WORDS = {
    the: 1, a: 1, an: 1, of: 1, and: 1,
    'דה': 1, 'אוף': 1, 'אנד': 1, 'א': 1
  };
  const LATIN_SKELETON = {
    b: 'B', v: 'B', w: 'B', p: 'P', f: 'P', k: 'K', c: 'K', q: 'K', g: 'G',
    j: 'G', d: 'D', t: 'T', z: 'Z', s: 'S', r: 'R', l: 'L', m: 'M', n: 'N',
    x: 'KS'
  };
  const SKELETON_MIN = 2;
  const QUERY_MIN_FOR_SKELETON = 3;

  /**
   * Which alphabet something is written in. The sound-alike path exists to
   * cross between alphabets; inside one alphabet it only removes information —
   * "ניתוק" and "האנטי קסם" share the consonants N-T-K and nothing else.
   */
  function scriptOf(text) {
    const hebrew = /[\u0590-\u05ff]/.test(text);
    const latin = /[a-z]/i.test(text);
    if (hebrew && latin) return 'both';
    if (hebrew) return 'he';
    if (latin) return 'la';
    return 'none';
  }

  function skeleton(text) {
    let s = String(text || '').toLowerCase()
      .split(/\s+/)
      .filter(function (word) { return !SKELETON_STOP_WORDS[word]; })
      .join(' ');
    // Digraphs first: they are one sound, and the letters would map wrongly.
    // A doubled vav is the consonant v; a single one is a vowel.
    s = s.replace(/וו/g, 'ב')
      .replace(/ce/g, 'se').replace(/ci/g, 'si')
      .replace(/sh/g, 's').replace(/ch/g, 'x').replace(/kh/g, 'x')
      .replace(/tz/g, 'c').replace(/ts/g, 'c')
      .replace(/ph/g, 'f').replace(/th/g, 't').replace(/ck/g, 'k').replace(/qu/g, 'k');

    let out = '';
    for (const ch of s) {
      if (ch >= '0' && ch <= '9') { out += ch; continue; }
      if (HEBREW_SKELETON.hasOwnProperty(ch)) { out += HEBREW_SKELETON[ch]; continue; }
      if (ch === 'x') { out += 'KS'; continue; }
      const latin = LATIN_SKELETON[ch];
      if (latin) out += latin;
      // Everything else — vowels, spaces, punctuation, ה and י — carries no
      // information about how a name was transliterated.
    }
    // A doubled letter in one spelling is a single one in the other, and s
    // between vowels is heard as z: neither difference should hide a match.
    return out.replace(/Z/g, 'S').replace(/(.)\1+/g, '$1');
  }

  /** The text a search looks at: the name, its category, and any other title. */
  function searchableText(item) {
    return [item.name, item.group, item.alias].filter(Boolean).join(' ').toLowerCase();
  }

  function matchesQuery(item, query, querySkeleton) {
    const q = (query || '').trim().toLowerCase();
    if (!q) return false;
    if (searchableText(item).indexOf(q) !== -1) return true;

    // Only then the sound-alike path, and only for a query that is actually a
    // name: "i24" reduced to its consonants is the digits, which appear inside
    // half the channel numbers in a playlist.
    if (q.length < QUERY_MIN_FOR_SKELETON) return false;
    if ((q.match(/[a-z\u0590-\u05ff]/g) || []).length < QUERY_MIN_FOR_SKELETON) return false;
    const wanted = querySkeleton === undefined ? skeleton(q) : querySkeleton;
    if ((wanted.match(/[A-Z]/g) || []).length < SKELETON_MIN) return false;

    // ...and only against text written in the other alphabet. Within one
    // alphabet the plain match above is the whole truth.
    const asked = scriptOf(q);
    const text = searchableText(item);
    const parts = String(text).split(/\s+/).filter(function (word) {
      const kind = scriptOf(word);
      return kind !== 'none' && kind !== asked;
    });
    if (!parts.length) return false;
    return skeleton(parts.join(' ')).indexOf(wanted) !== -1;
  }

  /**
   * One box over the whole catalogue. A title someone remembers is not filed
   * under the section they happen to be standing in, so the search never asks
   * which one that is; results come back grouped by what they are.
   */
  const SEARCH_ROW_LIMIT = 40;
  const SEARCH_MIN_QUERY = 2;

  function searchRows(items, query, limit) {
    const q = (query || '').trim().toLowerCase();
    if (q.length < SEARCH_MIN_QUERY) return [];
    const cap = limit || SEARCH_ROW_LIMIT;

    const live = [], movies = [], series = [];
    const wanted = skeleton(q);
    (items || []).forEach(function (item) {
      if (!matchesQuery(item, q, wanted)) return;
      if (item.kind === 'LIVE') { if (live.length < cap) live.push(item); }
      else if (item.contentType === 'SERIES') { if (series.length < cap) series.push(item); }
      else if (movies.length < cap) movies.push(item);
    });

    const rows = [];
    if (series.length) rows.push({ key: 'series', title: 'סדרות', items: series });
    if (movies.length) rows.push({ key: 'movies', title: 'סרטים', items: movies });
    if (live.length) rows.push({ key: 'live', title: 'ערוצים', items: live });
    return rows;
  }

  /** Newest first, one entry per item, capped — the same list both apps store. */
  function mergeHistory(history, entry, limit) {
    const cap = limit || 60;
    const out = [{ id: entry.id, at: entry.at, position: entry.position || 0, duration: entry.duration || 0 }];
    (history || []).forEach(function (old) {
      if (old.id === entry.id) return;
      if (out.length < cap) out.push(old);
    });
    return out;
  }

  return {
    normalizeServer: normalizeServer,
    attrs: attrs,
    looksLikeSeries: looksLikeSeries,
    guessKind: guessKind,
    placeholderText: placeholderText,
    parseM3u: parseM3u,
    streamVariants: streamVariants,
    episodesFromSeriesInfo: episodesFromSeriesInfo,
    episodeName: episodeName,
    isResumable: isResumable,
    progressRatio: progressRatio,
    buildHomeRows: buildHomeRows,
    searchRows: searchRows,
    skeleton: skeleton,
    matchesQuery: matchesQuery,
    scriptOf: scriptOf,
    searchableText: searchableText,
    seekTarget: seekTarget,
    formatClock: formatClock,
    stepInList: stepInList,
    SEEK_STEP: SEEK_STEP,
    SEEK_STEP_LONG: SEEK_STEP_LONG,
    mergeHistory: mergeHistory
  };
});
