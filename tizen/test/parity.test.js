const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const core = require('../core.js');
const fixtures = JSON.parse(
  fs.readFileSync(path.join(__dirname, '..', '..', 'shared', 'parity', 'fixtures.json'), 'utf8')
);

// The Kotlin app runs these same cases in app/src/test/.../ParityTest.kt.
// Both must agree, or the two apps have quietly drifted apart.

test('parses a playlist the way the contract describes', () => {
  const parsed = core.parseM3u(fixtures.m3u.playlist);
  assert.strictEqual(parsed.length, fixtures.m3u.expected.length);

  fixtures.m3u.expected.forEach((want, i) => {
    const got = parsed[i];
    assert.strictEqual(got.name, want.name);
    assert.strictEqual(got.group, want.group);
    assert.strictEqual(got.kind, want.kind);
    assert.strictEqual(got.url, want.url);
    assert.strictEqual(got.userAgent || null, want.userAgent);
  });
});

test('normalizes portal addresses', () => {
  for (const c of fixtures.servers) {
    assert.strictEqual(core.normalizeServer(c.input), c.expected, c.input);
  }
});

test('offers the same endpoint variants', () => {
  for (const c of fixtures.variants) {
    assert.deepStrictEqual(core.streamVariants(c.url), c.expected, c.url);
  }
});

test('builds the same episode list', () => {
  const f = fixtures.episodes;
  const episodes = core.episodesFromSeriesInfo(f.info, {
    server: f.server, user: f.user, pass: f.pass, logo: null,
  });

  assert.strictEqual(episodes.length, f.expected.length);
  f.expected.forEach((want, i) => {
    assert.strictEqual(episodes[i].name, want.name);
    assert.strictEqual(episodes[i].group, want.group);
    assert.strictEqual(episodes[i].url, want.url);
  });
});

test('lays out the same home screen', () => {
  const f = fixtures.home;
  const rows = core.buildHomeRows({
    items: f.items, history: f.history, favorites: f.favorites,
  });

  assert.strictEqual(rows.length, f.expected.length);
  f.expected.forEach((want, i) => {
    assert.strictEqual(rows[i].key, want.key);
    assert.strictEqual(rows[i].title, want.title);
    assert.deepStrictEqual(rows[i].items.map((x) => x.id), want.items);
    assert.deepStrictEqual(rows[i].items.map((x) => x.resumeAt || 0), want.resumeAt);
  });
});

test('keeps one history entry per item, newest first', () => {
  const merged = core.mergeHistory(
    [{ id: 'a', at: 2, position: 10, duration: 0 }, { id: 'b', at: 1, position: 0, duration: 0 }],
    { id: 'b', at: 3, position: 90, duration: 1200 },
  );
  assert.deepStrictEqual(merged.map((x) => x.id), ['b', 'a']);
  assert.strictEqual(merged[0].position, 90);
});
