/*
 * Carrying the memory between devices.
 *
 * There is no server of our own: the portal only answers about its catalogue,
 * so what you watched has nowhere to live but the device. This talks to a small
 * table you own — Supabase, or anything speaking the same REST — so the
 * television and the phone can be the same viewer.
 *
 * The cycle is pull, merge, push. Merging is the whole trick and it lives in
 * core.js, shared with the Android app, so both sides resolve a conflict the
 * same way. Nothing here decides anything; it only moves the document.
 */
(function (global) {
  'use strict';

  const Core = global.TalohimCore || (typeof require === 'function' ? require('./core.js') : null);

  const CONFIG_KEY = 'talohimSyncV1';
  const TABLE = 'talohim_state';

  const sync = {
    config: null,
    /** 'off' | 'idle' | 'busy' | 'error' */
    status: 'off',
    detail: '',
    lastAt: 0
  };

  function loadConfig() {
    let saved = null;
    try { saved = JSON.parse(localStorage.getItem(CONFIG_KEY) || 'null'); } catch (e) { saved = null; }
    // A personal build can be born paired, the same way it is born signed in.
    const built = global.TalohimSync || null;
    const config = saved || built;
    if (!config || !config.url || !config.key || !config.room) {
      sync.config = null;
      sync.status = 'off';
      return null;
    }
    sync.config = {
      url: String(config.url).replace(/\/+$/, ''),
      key: String(config.key),
      room: String(config.room)
    };
    if (sync.status === 'off') sync.status = 'idle';
    return sync.config;
  }

  function saveConfig(config) {
    try { localStorage.setItem(CONFIG_KEY, JSON.stringify(config || null)); } catch (e) {}
    return loadConfig();
  }

  function headers(config, extra) {
    const out = {
      'apikey': config.key,
      'Authorization': 'Bearer ' + config.key,
      'Content-Type': 'application/json'
    };
    Object.keys(extra || {}).forEach(function (k) { out[k] = extra[k]; });
    return out;
  }

  function request(method, url, config, body, extraHeaders) {
    return new Promise(function (resolve, reject) {
      const xhr = new XMLHttpRequest();
      xhr.open(method, url, true);
      const all = headers(config, extraHeaders);
      Object.keys(all).forEach(function (k) { xhr.setRequestHeader(k, all[k]); });
      xhr.timeout = 12000;
      xhr.onload = function () {
        if (xhr.status >= 200 && xhr.status < 300) resolve(xhr.responseText || '');
        else reject(new Error('HTTP ' + xhr.status + ' ' + (xhr.responseText || '').slice(0, 200)));
      };
      xhr.onerror = function () { reject(new Error('אין חיבור לשרת הסנכרון')); };
      xhr.ontimeout = function () { reject(new Error('שרת הסנכרון לא ענה')); };
      xhr.send(body ? JSON.stringify(body) : null);
    });
  }

  function pull(config) {
    const url = config.url + '/rest/v1/' + TABLE +
      '?room=eq.' + encodeURIComponent(config.room) + '&select=doc';
    return request('GET', url, config).then(function (text) {
      const rows = JSON.parse(text || '[]');
      // No row yet is not an error: it is the first device to arrive.
      return (rows && rows[0] && rows[0].doc) || null;
    });
  }

  function push(config, doc) {
    const url = config.url + '/rest/v1/' + TABLE;
    return request('POST', url, config, [{ room: config.room, doc: doc }], {
      'Prefer': 'resolution=merge-duplicates,return=minimal'
    });
  }

  /**
   * One round trip. `local` is the document as it stands on this device; the
   * merged document comes back, to be saved and drawn.
   */
  function cycle(local) {
    const config = sync.config || loadConfig();
    if (!config) return Promise.resolve(null);
    if (sync.status === 'busy') return Promise.resolve(null);

    sync.status = 'busy';
    sync.detail = '';
    return pull(config)
      .then(function (remote) {
        const merged = Core.sanitizeDoc(Core.mergeDocs(local, remote));
        // Pushing an unchanged document is a wasted write, and this runs on a
        // television that may be on a slow line.
        const changed = !remote || JSON.stringify(remote) !== JSON.stringify(merged);
        if (!changed) return merged;
        return push(config, merged).then(function () { return merged; });
      })
      .then(function (merged) {
        sync.status = 'idle';
        sync.lastAt = Date.now();
        return merged;
      })
      .catch(function (err) {
        // A sync that fails must never cost the device its own memory.
        sync.status = 'error';
        sync.detail = (err && err.message) || 'שגיאת סנכרון';
        return null;
      });
  }

  const api = {
    state: sync,
    loadConfig: loadConfig,
    saveConfig: saveConfig,
    cycle: cycle,
    describe: function () {
      if (!sync.config) return '';
      if (sync.status === 'busy') return 'מסנכרן…';
      if (sync.status === 'error') return 'סנכרון: ' + sync.detail;
      return sync.lastAt ? 'מסונכרן' : 'ממתין לסנכרון';
    }
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  global.TalohimSyncClient = api;
})(typeof window !== 'undefined' ? window : globalThis);
