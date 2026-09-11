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
    server: f.server, user: f.user, pass: f.pass, logo: null, seriesName: f.seriesName,
  });

  assert.strictEqual(episodes.length, f.expected.length);
  f.expected.forEach((want, i) => {
    assert.strictEqual(episodes[i].name, want.name);
    assert.strictEqual(episodes[i].group, want.group);
    assert.strictEqual(episodes[i].url, want.url);
  });
});

test('strips the same noise off an episode name', () => {
  fixtures.episodeLabels.cases.forEach((c) => {
    assert.strictEqual(core.episodeLabel(c.name, c.series), c.expected, c.name);
  });
});

test('lays out the same home screen', () => {
  const f = fixtures.home;
  const rows = core.buildHomeRows({
    items: f.items, history: f.history, favorites: f.favorites,
    marks: f.marks, now: f.now,
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

test('searches the whole catalogue the same way', () => {
  const f = fixtures.search;
  for (const c of f.cases) {
    const rows = core.searchRows(f.items, c.query);
    assert.strictEqual(rows.length, c.expected.length, c.query);
    c.expected.forEach((want, i) => {
      assert.strictEqual(rows[i].key, want.key, c.query);
      assert.strictEqual(rows[i].title, want.title, c.query);
      assert.deepStrictEqual(rows[i].items.map((x) => x.id), want.items, c.query);
    });
  }
});

test('does the same playback arithmetic', () => {
  const f = fixtures.playback;
  for (const c of f.seeks) {
    assert.strictEqual(core.seekTarget(c.position, c.delta, c.duration), c.expected,
      `${c.position}${c.delta >= 0 ? '+' : ''}${c.delta} of ${c.duration}`);
  }
  for (const c of f.clocks) {
    assert.strictEqual(core.formatClock(c.seconds), c.expected, String(c.seconds));
  }
  for (const c of f.steps) {
    const got = core.stepInList(c.ids.map((id) => ({ id })), c.id, c.step);
    assert.strictEqual(got ? got.id : null, c.expected, `${c.id} ${c.step}`);
  }
});

test('reads a name the same way in both alphabets', () => {
  const f = fixtures.matching;
  for (const c of f.skeletons) {
    assert.strictEqual(core.skeleton(c.text), c.expected, c.text);
  }
  for (const c of f.cases) {
    const item = { name: c.name, group: c.group || '', alias: c.alias || null };
    assert.strictEqual(core.matchesQuery(item, c.query), c.expected,
      `${c.query} vs ${c.name}`);
  }
});

test('agrees on what has been seen, and on what to suggest next', () => {
  const f = fixtures.taste;
  const input = { items: f.items, history: f.history, marks: f.marks, now: f.now };

  assert.deepStrictEqual(
    Object.keys(core.watchedSet(f.history, f.marks)).sort(),
    f.expected.watched.slice().sort(),
  );
  assert.deepStrictEqual(core.taste(input).order, f.expected.order);
  assert.deepStrictEqual(
    core.recommend(Object.assign({ limit: f.limit }, input)).map((x) => x.id),
    f.expected.recommend,
  );

  const because = core.becauseYouWatched(input);
  assert.strictEqual(because.seed.id, f.expected.becauseSeed);
  assert.deepStrictEqual(because.items.map((x) => x.id), f.expected.because);
});

test('suggests nothing at all before there is anything to go on', () => {
  const f = fixtures.taste;
  assert.deepStrictEqual(
    core.recommend({ items: f.items, history: [], marks: [], now: f.now }), [],
  );
  assert.strictEqual(core.becauseYouWatched({ items: f.items, history: [], marks: [] }), null);
});

test('the prepared search answers exactly what walking the catalogue does', () => {
  const names = ['ניתוק (2022)', 'Severance', 'ברייקינג באד', 'Breaking Bad', 'פאודה',
    'Fauda', 'i24NEWS', 'כאן 11', 'ספורט 1', 'Game of Thrones', 'גיים אוף ת׳רונס',
    'שטיסל', 'The Crown', 'הכתר', 'Moana 2', 'מוואנה 2', 'X-Men', 'אקס מן'];
  const items = names.map((name, i) => ({
    id: 'x' + i, name,
    group: i % 2 ? 'דרמה' : 'Action',
    kind: i % 5 === 0 ? 'LIVE' : 'VOD',
    contentType: i % 3 ? 'MOVIE' : 'SERIES',
  }));
  const index = core.buildSearchIndex(items);
  const ids = (rows) => rows.map((r) => [r.key, r.items.map((x) => x.id)]);

  // Queries in both alphabets, one that mixes them, and ones that match nothing.
  ['ניתוק', 'sev', 'severance', 'breaking', 'באד', 'i24', 'game', 'גיים',
    'moana', 'מוואנה', 'x-men', 'אקס', 'הכתר', 'crown', 'zzz', 'ab', 'דרמה',
  ].forEach((q) => {
    assert.deepStrictEqual(
      ids(core.searchRows(items, q, undefined, index)),
      ids(core.searchRows(items, q)),
      `the two paths disagreed on ${JSON.stringify(q)}`,
    );
  });
});
