/*
 * The browser a television has is not the browser this was written in.
 *
 * A 2022 Samsung set runs Chromium 85. An unknown CSS property is dropped in
 * silence — no error, no warning — so `inset:0` cost a release: the whole
 * playback UI collapsed into a corner and only a photograph of the screen
 * revealed it. This test reads the sources for anything newer than the browser
 * the app declares support for, so that class of bug fails here instead.
 */
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const TIZEN = path.join(__dirname, '..');
const read = (name) => fs.readFileSync(path.join(TIZEN, name), 'utf8');

// tizen/config.xml declares required_version 6.5, which is Chromium 85.
const CHROMIUM = 85;

// [pattern, the Chromium version that shipped it, what to use instead]
const CSS_RULES = [
  [/(^|[;{\s])inset\s*:/, 87, 'top/right/bottom/left'],
  [/(^|[;{\s])inset-(inline|block)/, 87, 'top/right/bottom/left'],
  [/aspect-ratio\s*:/, 88, 'a fixed height, or padding-top'],
  [/:is\(/, 88, 'listing the selectors'],
  [/:where\(/, 88, 'listing the selectors'],
  [/:has\(/, 105, 'a class set from JavaScript'],
  [/accent-color\s*:/, 93, 'styling the control itself'],
  [/color-mix\(/, 111, 'a literal colour'],
  [/\b(dvh|svh|lvh|dvw|svw|lvw)\b/, 108, 'vh/vw'],
  [/text-wrap\s*:/, 114, 'width limits'],
  [/scrollbar-gutter\s*:/, 94, 'padding'],
  [/(^|[;{\s])(translate|rotate|scale)\s*:/, 104, 'transform'],
  [/@container/, 105, 'a width class set from JavaScript'],
  [/@layer/, 99, 'plain rule order'],
];

const JS_RULES = [
  [/\.at\(/, 92, 'indexing with length - 1'],
  [/\.replaceChildren\(/, 86, 'innerHTML = "" and appendChild'],
  [/structuredClone\(/, 98, 'JSON.parse(JSON.stringify(x))'],
  [/Object\.hasOwn\(/, 93, 'Object.prototype.hasOwnProperty.call'],
  [/\.findLast(Index)?\(/, 97, 'a reverse loop'],
  [/\.toSorted\(|\.toReversed\(|\.toSpliced\(|\.with\(/, 110, 'slice() first'],
  [/\.group(By)?\(/, 117, 'a plain object'],
  [/AbortSignal\.timeout\(/, 103, 'setTimeout with an AbortController'],
  [/\bnavigator\.clipboard\b/, 66, null],
  [/#[A-Za-z_]\w*\s*[=;(]/, 74, null],
];

function offenders(source, rules) {
  const found = [];
  source.split('\n').forEach((line, index) => {
    // Comments describe the problem; they are not the problem.
    const code = line.replace(/\/\*.*?\*\//g, '').replace(/^\s*(\/\/|\*).*$/, '');
    rules.forEach(([pattern, since, instead]) => {
      if (since <= CHROMIUM) return;
      if (pattern.test(code)) {
        found.push(`line ${index + 1}: ${line.trim()}\n    needs Chromium ${since}` +
          (instead ? `, use ${instead}` : ''));
      }
    });
  });
  return found;
}

test('the stylesheet stays inside what the television can render', () => {
  const found = offenders(read('style.css'), CSS_RULES);
  assert.deepStrictEqual(found, [], `Chromium ${CHROMIUM} cannot read:\n  ${found.join('\n  ')}`);
});

test('the scripts stay inside what the television can run', () => {
  // Every script that ships, found rather than listed: a list is a thing you
  // forget to add a new file to, and the file that slips past is exactly the
  // one nobody checked.
  const scripts = fs.readdirSync(TIZEN).filter((f) => f.endsWith('.js'));
  assert.ok(scripts.length >= 4, `only found ${scripts.join(', ')}`);
  for (const name of scripts) {
    const found = offenders(read(name), JS_RULES);
    assert.deepStrictEqual(found, [], `${name}: Chromium ${CHROMIUM} cannot run:\n  ${found.join('\n  ')}`);
  }
});

test('the declared platform version is the one the checks assume', () => {
  const config = read('config.xml');
  const version = /required_version="([\d.]+)"/.exec(config);
  assert.ok(version, 'config.xml must declare required_version');
  assert.strictEqual(version[1], '6.5',
    'if the required version changes, the Chromium baseline in this test changes with it');
});
