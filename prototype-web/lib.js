export const APPS = [
  { id: 'facebook', name: 'Facebook', letter: 'f', color: 'blue', description: 'Social networking' },
  { id: 'zalo', name: 'Zalo', letter: 'Z', color: 'cyan', description: 'Messaging & calls' },
  { id: 'chrome', name: 'Chrome', letter: '◉', color: 'yellow', description: 'Web browser' },
  { id: 'instagram', name: 'Instagram', letter: '◎', color: 'pink', description: 'Photos & videos' },
];
export const CATEGORIES = ['Ads', 'Analytics', 'Essential'];
// Reserved example domains: these fixtures make no claims about real vendor traffic.
export const TRACKERS = [
  { domain: 'ads.example.com', category: 'Ads' },
  { domain: '*.ads.example.com', category: 'Ads' },
  { domain: 'metrics.example.com', category: 'Analytics' },
  { domain: '*.metrics.example.com', category: 'Analytics' },
  { domain: 'api.example.com', category: 'Essential' },
  { domain: 'cdn.example.com', category: 'Essential' },
];
export function normalizeDomain(value, wildcard = false) {
  let raw = String(value).trim().toLowerCase().replace(/\.$/, '');
  const prefix = wildcard && raw.startsWith('*.') ? '*.' : '';
  if (prefix) raw = raw.slice(2);
  if (!raw || /[\s/:?#@*\\]/.test(raw)) throw new Error('Enter a domain such as example.com or *.example.com.');
  let domain;
  try { domain = new URL(`https://${raw}`).hostname; } catch { throw new Error('Enter a valid domain.'); }
  if (domain.length > 253 || !domain.includes('.') || domain.split('.').some(label => !/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(label))) throw new Error('Enter a valid domain with a suffix, such as example.com.');
  return prefix + domain;
}
export function matchesDomain(domain, pattern) {
  return pattern.startsWith('*.') ? domain.endsWith(pattern.slice(1)) && domain !== pattern.slice(2) : domain === pattern;
}
function bestMatch(domain, rules) {
  return rules.filter(rule => matchesDomain(domain, rule.domain)).sort((a, b) => Number(a.domain.startsWith('*.')) - Number(b.domain.startsWith('*.')) || b.domain.length - a.domain.length)[0];
}
export function evaluate(domain, appId, state) {
  domain = normalizeDomain(domain);
  const category = bestMatch(domain, TRACKERS)?.category || 'Unknown';
  if (!state.enabled) return { domain, category, action: 'allow', reason: 'Simulation paused' };
  const appRule = bestMatch(domain, state.exceptions.filter(rule => rule.app === appId));
  const globalRule = bestMatch(domain, state.exceptions.filter(rule => rule.app === 'all'));
  const rule = appRule || globalRule;
  if (rule) return { domain, category, action: rule.action, reason: appRule ? 'App domain exception' : 'Global domain exception' };
  const action = state.rules[appId]?.[category] || state.globalRules[category] || 'allow';
  return { domain, category, action, reason: category === 'Unknown' ? 'No matching rule' : `${category} category rule` };
}
export function cleanLink(input, custom = []) {
  let url;
  try { url = new URL(input.trim()); } catch { throw new Error('Enter a complete URL starting with https:// or http://.'); }
  if (!['https:', 'http:'].includes(url.protocol)) throw new Error('Only HTTP and HTTPS links are supported.');
  if (url.username || url.password) throw new Error('Remove the username or password from this URL first.');
  const patterns = ['utm_*', 'fbclid', 'gclid', ...custom].map(p => p.trim().toLowerCase()).filter(Boolean);
  const removed = [];
  // Preserve the original encoding and order of unrelated query parameters.
  const query = url.search.slice(1);
  const retained = query ? query.split('&').filter(part => {
    const name = new URLSearchParams(part).keys().next().value || '';
    const lower = name.toLowerCase();
    const match = patterns.some(p => p.endsWith('*') ? lower.startsWith(p.slice(0, -1)) : lower === p);
    if (match) removed.push(name);
    return !match;
  }) : [];
  url.search = retained.length ? `?${retained.join('&')}` : '';
  return { url: url.href, removed };
}
export function freshState() {
  return {
    version: 1, enabled: true,
    globalRules: { Ads: 'block', Analytics: 'block', Essential: 'allow' },
    rules: Object.fromEntries(APPS.map(app => [app.id, { Ads: 'block', Analytics: 'block', Essential: 'allow' }])),
    exceptions: [], customParams: [], events: [],
  };
}
export function makeEvents(state, count = 12, historical = false) {
  const domains = ['ads.example.com', 'metrics.example.com', 'api.example.com', 'video.ads.example.com', 'cdn.example.com', 'events.metrics.example.com', 'ads.example.com', 'metrics.example.com'];
  const now = Date.now();
  return Array.from({ length: count }, (_, i) => {
    const app = APPS[i % APPS.length].id;
    const domain = domains[(i + Math.floor(i / APPS.length)) % domains.length];
    const position = i / count;
    const age = (position + 0.035 * Math.sin(position * Math.PI * 6)) * 23.8 * 3600000;
    return { id: crypto.randomUUID(), app, ...evaluate(domain, app, state), time: now - (historical ? Math.floor(age) : 0), simulated: true };
  });
}
export function loadState(raw) {
  const fallback = freshState();
  if (!raw) return fallback;
  try {
    const value = JSON.parse(raw);
    if (value.version !== 1) return fallback;
    if (typeof value.enabled === 'boolean') fallback.enabled = value.enabled;
    for (const app of APPS) for (const category of CATEGORIES) {
      const action = value.rules?.[app.id]?.[category];
      if (['block', 'allow'].includes(action)) fallback.rules[app.id][category] = action;
    }
    fallback.exceptions = (Array.isArray(value.exceptions) ? value.exceptions : []).slice(0, 500).flatMap(rule => {
      try {
        if (!['all', ...APPS.map(a => a.id)].includes(rule.app) || !['allow', 'block'].includes(rule.action)) return [];
        return [{ id: String(rule.id), app: rule.app, action: rule.action, domain: normalizeDomain(rule.domain, true) }];
      } catch { return []; }
    });
    fallback.customParams = (Array.isArray(value.customParams) ? value.customParams : []).filter(p => typeof p === 'string' && p.length <= 100).slice(0, 100);
    fallback.events = (Array.isArray(value.events) ? value.events : []).filter(e => e && e.simulated === true && typeof e.id === 'string' && typeof e.domain === 'string' && typeof e.reason === 'string' && Number.isFinite(e.time) && e.time <= Date.now() && e.time >= Date.now() - 7 * 86400000 && APPS.some(a => a.id === e.app) && ['allow', 'block'].includes(e.action) && [...CATEGORIES, 'Unknown'].includes(e.category)).slice(0, 2000);
    return fallback;
  } catch { return fallback; }
}
