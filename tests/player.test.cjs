const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const assets = path.join(__dirname, '../app/src/main/assets');

test('ad transition mutes and masks until the main source returns', () => {
  const contents = fs.readFileSync(path.join(assets, 'player.js'), 'utf8');
  const body = contents.slice(contents.indexOf('  let shell;'), contents.indexOf('  let pending = false;'));
  let now = 1000;
  let signalled = false;
  const video = { currentSrc: 'normal-source', muted: false };
  const attributes = new Set();
  const fakePlayer = {
    matches: () => false,
    querySelector: () => signalled ? {} : null,
    querySelectorAll: () => [video],
    toggleAttribute: (name, enabled) => enabled ? attributes.add(name) : attributes.delete(name),
    getBoundingClientRect: () => ({width: 100, height: 100})
  };
  const c = vm.createContext({fakePlayer, video, Date: {now: () => now}});
  vm.runInContext(body + '\nplayer = fakePlayer; primary = video;', c);
  assert.equal(c.state().ad, false);
  assert.equal(video.muted, false);
  signalled = true;
  assert.equal(c.state().ad, true);
  video.currentSrc = 'ad-source';
  assert.equal(c.state().ad, true);
  assert.equal(video.muted, true);
  assert.equal(attributes.has('data-movie-masked'), true);
  signalled = false;
  now += 600;
  assert.equal(c.state().ad, true);
  video.currentSrc = 'normal-source';
  assert.equal(c.state().ad, false);
  assert.equal(video.muted, false);
  assert.equal(attributes.has('data-movie-masked'), false);
  video.muted = true;
  vm.runInContext('manualUntil = Date.now() + 20000;', c);
  assert.equal(c.state().manual, true);
  now += 20001;
  assert.equal(c.state().manual, false);
  assert.equal(video.muted, true);
});

test('search returns unique titles and only same-origin playback paths', () => {
  const contents = fs.readFileSync(path.join(assets, 'search.js'), 'utf8');
  const location = {origin: 'https://example.test'};
  const makeLink = (path, textContent) => ({href: new URL(path, location.origin).href, textContent});
  const titleLinks = [
    makeLink('/play/alpha', ' 测试甲 '),
    makeLink('/play/alpha', ' 测试甲 '),
    makeLink('/play/beta', '测试乙'),
    makeLink('/news', '页面链接'),
    makeLink('https://other.test/play/example', '外部链接'),
  ];
  const root = {querySelectorAll: () => titleLinks, querySelector: () => null};
  const context = {URL, location, window: {}, document: {querySelector: () => root}};
  const result = vm.runInNewContext(contents, context);
  assert.deepEqual(JSON.parse(JSON.stringify(result.items)), [
    {url: 'https://example.test/play/alpha', name: '测试甲'},
    {url: 'https://example.test/play/beta', name: '测试乙'},
  ]);
});
