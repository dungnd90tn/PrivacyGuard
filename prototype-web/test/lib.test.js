import test from 'node:test';
import assert from 'node:assert/strict';
import { normalizeDomain, matchesDomain, evaluate, cleanLink, freshState, loadState, makeEvents } from '../lib.js';

test('normalizes case, trailing dots, and internationalized domains', () => {
  assert.equal(normalizeDomain('  ADS.Example.COM.  '), 'ads.example.com');
  assert.equal(normalizeDomain('bücher.example'), 'xn--bcher-kva.example');
  assert.equal(normalizeDomain('*.Example.com', true), '*.example.com');
});
test('rejects URLs, embedded credentials, invalid labels, and malformed wildcard domains', () => {
  for (const value of ['https://example.com', 'me@example.com', 'example.com/path', '-a.example', 'a..example', 'a b.example', '*.example.com', '*example.com', 'example.com:443', 'example.com?x=1']) assert.throws(() => normalizeDomain(value));
  assert.throws(() => normalizeDomain('*.*.example.com', true));
});
test('domain matching respects label boundaries and wildcard apex exclusion', () => {
  assert.equal(matchesDomain('ads.example.com', 'example.com'), false);
  assert.equal(matchesDomain('example.com', '*.example.com'), false);
  assert.equal(matchesDomain('a.b.example.com', '*.example.com'), true);
  assert.equal(matchesDomain('notexample.com', '*.example.com'), false);
});
test('default policy blocks Ads and Analytics, allows Essential and Unknown', () => {
  const state = freshState();
  assert.equal(evaluate('ads.example.com', 'facebook', state).action, 'block');
  assert.equal(evaluate('metrics.example.com', 'facebook', state).action, 'block');
  assert.equal(evaluate('api.example.com', 'facebook', state).action, 'allow');
  assert.equal(evaluate('unknown.example.com', 'facebook', state).action, 'allow');
  assert.equal(evaluate('a.ads.example.com', 'facebook', state).category, 'Ads');
});
test('changing an app category is isolated to that app', () => {
  const state = freshState(); state.rules.facebook.Ads = 'allow';
  assert.equal(evaluate('ads.example.com', 'facebook', state).action, 'allow');
  assert.equal(evaluate('ads.example.com', 'zalo', state).action, 'block');
});
test('app exception wins over global allowlist and category policy', () => {
  const state = freshState();
  state.exceptions = [{ app: 'all', domain: 'ads.example.com', action: 'allow' }, { app: 'facebook', domain: '*.example.com', action: 'block' }];
  assert.equal(evaluate('ads.example.com', 'facebook', state).action, 'block');
  assert.equal(evaluate('ads.example.com', 'zalo', state).action, 'allow');
});
test('exact matches win over wildcards; longest wildcard wins among wildcards', () => {
  const state = freshState();
  state.exceptions = [{ app: 'all', domain: '*.example.com', action: 'block' }, { app: 'all', domain: '*.ads.example.com', action: 'allow' }, { app: 'all', domain: 'special.ads.example.com', action: 'block' }];
  assert.equal(evaluate('other.ads.example.com', 'facebook', state).action, 'allow');
  assert.equal(evaluate('special.ads.example.com', 'facebook', state).action, 'block');
  assert.equal(evaluate('cdn.example.com', 'facebook', state).action, 'block');
});
test('paused simulator allows requests even when explicit block exceptions exist', () => {
  const state = freshState(); state.enabled = false;
  state.exceptions = [{ app: 'all', domain: 'ads.example.com', action: 'block' }];
  assert.equal(evaluate('ads.example.com', 'facebook', state).action, 'allow');
  assert.equal(evaluate('ads.example.com', 'facebook', state).reason, 'Simulation paused');
});
test('cleans common, encoded, repeated, and case-insensitive tracking keys', () => {
  const result = cleanLink('https://example.com/read?%75tm_source=email&FBCLID=1&gclid=2&gclid=3&keep=yes#section');
  assert.equal(result.url, 'https://example.com/read?keep=yes#section');
  assert.deepEqual(result.removed, ['utm_source', 'FBCLID', 'gclid', 'gclid']);
});
test('preserves unrelated query bytes, repeated values, encoded URLs, and fragments', () => {
  const result = cleanLink('https://example.com/?q=a%20b&q=a+b&empty=&flag&next=https%3A%2F%2Fexample.org%2F%3Fa%3Db&utm_medium=x#utm_keep');
  assert.equal(result.url, 'https://example.com/?q=a%20b&q=a+b&empty=&flag&next=https%3A%2F%2Fexample.org%2F%3Fa%3Db#utm_keep');
});
test('custom patterns support exact names and trailing wildcard prefixes', () => {
  assert.equal(cleanLink('https://example.com/?ref=1&referral=2&campaign_a=3&keep=4', ['ref', 'campaign_*']).url, 'https://example.com/?referral=2&keep=4');
});
test('removes empty query after cleaning and preserves clean URLs', () => {
  assert.equal(cleanLink('https://example.com/?fbclid=1#hello').url, 'https://example.com/#hello');
  assert.deepEqual(cleanLink('https://example.com/read#hello'), { url: 'https://example.com/read#hello', removed: [] });
});
test('rejects unsafe protocols and URLs with credentials', () => {
  for (const value of ['javascript:alert(1)', 'file:///etc/passwd', 'data:text/plain,hi', 'not a url', 'https://name:password@example.com']) assert.throws(() => cleanLink(value));
});
test('sample events reflect the configured rules and remain explicitly simulated', () => {
  const state = freshState(); state.enabled = false;
  const events = makeEvents(state, 100, true);
  assert.equal(events.length, 100);
  assert.ok(events.every(e => e.action === 'allow' && e.simulated && e.time <= Date.now()));
  assert.equal(new Set(events.map(e => e.id)).size, 100);
  assert.ok(events.every((e, i) => !i || events[i - 1].time >= e.time));
});
test('round-trips preferences and events', () => {
  const state = freshState(); state.rules.facebook.Ads = 'allow'; state.customParams = ['ref'];
  state.exceptions = [{ id: '1', app: 'all', domain: 'example.com', action: 'allow' }];
  state.events = makeEvents(state, 12);
  assert.deepEqual(loadState(JSON.stringify(state)), state);
});
test('recovers from corrupted storage and validates saved values', () => {
  assert.deepEqual(loadState('{broken'), freshState());
  assert.deepEqual(loadState('null'), freshState());
  const restored = loadState(JSON.stringify({ version: 1, rules: { facebook: { Ads: 'invalid' } }, exceptions: [null, { app: 'facebook', action: 'allow', domain: '<script>' }], customParams: [1, null, 'ref'], events: [null, {}] }));
  assert.equal(restored.rules.facebook.Ads, 'block');
  assert.deepEqual(restored.exceptions, []);
  assert.deepEqual(restored.customParams, ['ref']);
  assert.deepEqual(restored.events, []);
});
test('retention drops expired events and never accepts non-simulated events', () => {
  const state = freshState(); const [event] = makeEvents(state, 1);
  state.events = [event, { ...event, time: Date.now() - 8 * 86400000 }, { ...event, simulated: false }];
  assert.deepEqual(loadState(JSON.stringify(state)).events, [event]);
});
