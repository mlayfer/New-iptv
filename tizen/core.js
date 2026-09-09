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
        pending = {
          name: name || a['tvg-name'] || 'ללא שם',
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
          const title = (episode.title || '').trim() || ('פרק ' + number);
          out.push({
            id: 'e' + id,
            name: 'S' + season + 'E' + number + ' · ' + title,
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

  return {
    normalizeServer: normalizeServer,
    attrs: attrs,
    looksLikeSeries: looksLikeSeries,
    guessKind: guessKind,
    placeholderText: placeholderText,
    parseM3u: parseM3u,
    streamVariants: streamVariants,
    episodesFromSeriesInfo: episodesFromSeriesInfo
  };
});
