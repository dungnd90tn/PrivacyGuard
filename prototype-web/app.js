import { APPS, CATEGORIES, TRACKERS, freshState, loadState, makeEvents, evaluate, cleanLink, normalizeDomain } from './lib.js';

const iconPaths = {
  shield: '<path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6z"/><path d="m8 12 3 3 5-6"/>',
  grid: '<rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><rect x="14" y="14" width="7" height="7" rx="2"/>',
  apps: '<rect x="5" y="2" width="14" height="20" rx="3"/><path d="M10 18h4M9 5h6"/>',
  link: '<path d="m10 13 4-4m-6 6-1 1a4 4 0 0 1-6-6l4-4a4 4 0 0 1 6 0m2 3 1-1a4 4 0 0 1 6 6l-4 4a4 4 0 0 1-6 0" transform="translate(1 1)"/>',
  globe: '<circle cx="12" cy="12" r="9"/><ellipse cx="12" cy="12" rx="4" ry="9"/><path d="M3 12h18"/>',
  activity: '<path d="M2 12h5l3-8 4 16 3-8h5"/>',
  settings: '<path d="M4 7h16M4 17h16"/><circle cx="9" cy="7" r="3"/><circle cx="15" cy="17" r="3"/>',
  arrow: '<path d="M5 12h14m-5-5 5 5-5 5"/>',
  down: '<path d="m8 10 4 4 4-4"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  lock: '<rect x="5" y="10" width="14" height="11" rx="3"/><path d="M8 10V7a4 4 0 0 1 8 0v3m-4 5v2"/>',
  search: '<circle cx="10" cy="10" r="6"/><path d="m15 15 5 5"/>',
  download: '<path d="M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5"/>',
  play: '<path d="m8 5 11 7-11 7z"/>',
  close: '<path d="m6 6 12 12M6 18 18 6"/>',
  copy: '<rect x="8" y="8" width="12" height="13" rx="2"/><path d="M15 8V3H3v12h5"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6m0-10v1"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  leaf: '<path d="M19 3c-9 0-15 2-15 9a7 7 0 0 0 7 7c7 0 8-7 8-16ZM4 21 15 10"/>',
};
const icon = (name, cls = '') => `<svg class="icon ${cls}" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${iconPaths[name] || iconPaths.shield}</svg>`;
const esc = value => String(value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const appIcon = app => `<span class="app-icon ${app.color}" aria-hidden="true">${app.letter}</span>`;
const storageKey = 'privacyguard.v1';
let state, persistenceAvailable = true;
try {
  const raw = localStorage.getItem(storageKey);
  state = loadState(raw);
  if (!raw) state.events = makeEvents(state, 864, true);
} catch { state = freshState(); persistenceAvailable = false; }
let page = location.hash.slice(1) || 'overview';
let period = 24, activityFilter = 'all', activitySearch = '', activityLimit = 30;
let toastTimer, cleanResult = null, cleanerDraft = '';
const pages = {
  overview: ['Overview', 'A little less tracking. A lot more peace of mind.'],
  rules: ['App rules', 'Decide what gets through, one app at a time.'],
  cleaner: ['Link cleaner', 'Share the destination. Leave the tracking behind.'],
  browser: ['Private browser', 'A separate space for your browsing.'],
  activity: ['Activity log', 'A transparent look at every simulated decision.'],
  settings: ['Settings', 'Make this privacy workspace your own.'],
};
function save() {
  state.events = state.events.filter(e => e.time >= Date.now() - 7 * 86400000).slice(0, 2000);
  try { localStorage.setItem(storageKey, JSON.stringify(state)); persistenceAvailable = true; }
  catch { persistenceAvailable = false; toast('Browser storage is unavailable. Changes last for this session only.'); }
}
function toast(message) {
  const el = document.querySelector('#toast');
  el.textContent = message;
  el.classList.add('visible');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('visible'), 3600);
}
function eventsInPeriod() { return state.events.filter(e => e.time >= Date.now() - period * 3600000); }
function button(label, action, type = 'secondary', symbol = '') {
  return `<button class="btn ${type}" data-action="${action}">${symbol ? icon(symbol) : ''}${label}</button>`;
}
function shell() {
  if (!pages[page]) page = 'overview';
  document.title = `${pages[page][0]} · PrivacyGuard`;
  document.querySelector('#app').innerHTML = `
    <aside class="sidebar">
      <a class="brand" href="#overview"><span class="brand-mark">${icon('shield')}</span>PrivacyGuard<span class="brand-dot">.</span></a>
      <div class="workspace"><span class="workspace-icon">P</span><span>Personal workspace<small>Local · Free plan</small></span><span class="workspace-lock">${icon('lock')}</span></div>
      <div class="nav-label">WORKSPACE</div>
      <nav aria-label="Main navigation">${[['overview', 'grid', 'Overview'], ['rules', 'apps', 'App rules'], ['cleaner', 'link', 'Link cleaner'], ['browser', 'globe', 'Private browser'], ['activity', 'activity', 'Activity log']].map(([id, symbol, label]) => `<a href="#${id}" class="nav-item ${page === id ? 'active' : ''}" ${page === id ? 'aria-current="page"' : ''}>${icon(symbol)}<span>${label}</span>${id === 'browser' ? '<span class="soon">SOON</span>' : ''}</a>`).join('')}</nav>
      <div class="sidebar-bottom"><div class="local-card">${icon('leaf')}<strong>Privacy starts with you.</strong><p>Your rules and demo activity stay in this browser.</p><span>${icon('lock')} No account needed</span></div><a href="#settings" class="nav-item ${page === 'settings' ? 'active' : ''}" ${page === 'settings' ? 'aria-current="page"' : ''}>${icon('settings')}<span>Settings</span></a><div class="profile"><span class="avatar">Y</span><span>Your workspace<small>On this device</small></span><span class="version">v0.1</span></div></div>
    </aside>
    <div class="main-shell"><header class="topbar"><div class="breadcrumb">Workspace <span>/</span> <strong>${pages[page][0]}</strong></div><div class="topbar-right"><span class="demo-chip"><i></i> Demo workspace</span><span class="topbar-divider"></span><a href="#settings" class="avatar small" aria-label="Workspace settings" title="Workspace settings">Y</a></div></header>
    <main id="main" tabindex="-1"><div class="page-heading"><div><div class="eyebrow">YOUR PRIVACY, YOUR RULES</div><h1>${pages[page][0]}</h1><p>${pages[page][1]}</p></div><div class="heading-actions">${page === 'overview' || page === 'activity' ? `<label class="period-select">${icon('clock')}<select id="period" aria-label="Activity time period"><option value="24" ${period === 24 ? 'selected' : ''}>Last 24 hours</option><option value="168" ${period === 168 ? 'selected' : ''}>Last 7 days</option></select></label>${button('Run simulation', 'simulate', 'dark', 'play')}` : ''}</div></div>
    ${!persistenceAvailable ? '<div class="notice">Browser storage is unavailable. Changes last for this session only.</div>' : ''}
    ${{ overview, rules: rulesPage, cleaner: cleanerPage, activity: activityPage, browser: browserPage, settings: settingsPage }[page]()}
    <footer><span>${icon('lock')} Designed for a little more privacy.</span><span>Local-first · No account · No telemetry</span></footer></main></div>`;
  if (page === 'cleaner' && cleanResult) showCleanResult();
}
function overview() {
  const events = eventsInPeriod(), blocked = events.filter(e => e.action === 'block');
  const rate = events.length ? Math.round(blocked.length / events.length * 100) : 0;
  const activeApps = APPS.filter(a => Object.values(state.rules[a.id]).includes('block')).length;
  return `<section class="protection-banner"><div class="banner-shield">${icon('shield')}</div><div class="banner-copy"><div class="banner-title">Your privacy. In your hands.<span class="status-pill">${state.enabled ? 'Simulator active' : 'Simulator paused'}</span></div><p>Explore your rules in action. Device-wide VPN protection is not connected.</p></div><button class="toggle ${state.enabled ? 'on' : ''}" role="switch" aria-checked="${state.enabled}" aria-label="Enable rule simulation" data-action="toggle-simulation"><span></span></button></section>
    <div class="section-kicker"><span>AT A GLANCE</span><span class="muted">Sample traffic · ${period === 24 ? 'last 24 hours' : 'last 7 days'}</span></div>
    <section class="stats-grid" aria-label="Simulation statistics">
      ${statCard('Requests blocked', blocked.length.toLocaleString(), 'shield', 'green', 'Across your sample apps', `${rate}% of requests`)}
      ${statCard('Requests analyzed', events.length.toLocaleString(), 'activity', 'purple', 'Generated locally', 'Simulated traffic')}
      ${statCard('Apps with block rules', `${activeApps}<span class="stat-denom"> / ${APPS.length}</span>`, 'apps', 'blue', 'Personalized category controls', 'You’re in control')}
      ${statCard('Domain exceptions', state.exceptions.length, 'globe', 'orange', 'Your custom allow & block rules', 'Fine-tuned by you')}
    </section>
    <div class="overview-grid"><section class="card activity-card"><div class="card-heading"><div><h2>Tracking activity</h2><p>A quieter digital footprint, one request at a time.</p></div><span class="tiny-tag">SIMULATION</span></div><div class="chart-summary"><strong>${blocked.length.toLocaleString()}</strong><span>requests blocked</span><div class="legend"><span><i class="legend-blocked"></i>Blocked</span><span><i class="legend-allowed"></i>Allowed</span></div></div>${chart(events)}<div class="chart-foot">${icon('info')} Sample traffic illustrates how your current rules behave.</div></section>
    <section class="card app-summary"><div class="card-heading"><div><h2>Your apps</h2><p>A closer look at the activity.</p></div><a class="icon-button" href="#rules" aria-label="Manage app rules">${icon('arrow')}</a></div><div class="app-column-label"><span>APPLICATION</span><span>BLOCKED</span></div>${APPS.map(app => { const count = blocked.filter(e => e.app === app.id).length; return `<button class="app-summary-row" data-action="edit-app" data-app="${app.id}">${appIcon(app)}<span class="app-summary-name"><strong>${app.name}</strong><small>${Object.values(state.rules[app.id]).filter(a => a === 'block').length} categories blocked</small></span><strong class="app-count">${count}</strong>${icon('down')}</button>`; }).join('')}<a href="#rules" class="card-bottom-link">Manage app rules ${icon('arrow')}</a></section></div>
    <div class="bottom-grid"><section class="card recent-card"><div class="card-heading"><div><h2>Recent activity</h2><p>Your latest simulated connections.</p></div><a class="text-link" href="#activity">View all ${icon('arrow')}</a></div>${eventTable(events.slice(0, 4), true)}</section><section class="cleaner-promo"><span class="promo-icon">${icon('link')}</span><span class="tiny-tag">A LITTLE PRIVACY WIN</span><h2>A cleaner link.<br>A smaller footprint.</h2><p>Remove tracking parameters before your next share.</p><a class="btn dark" href="#cleaner">Clean a link ${icon('arrow')}</a><div class="promo-decoration" aria-hidden="true">↗</div></section></div>`;
}
function statCard(label, value, symbol, color, note, badge) {
  return `<article class="stat-card"><div class="stat-label">${label}<span class="stat-icon ${color}">${icon(symbol)}</span></div><div class="stat-value">${value}</div><div class="stat-note">${note}</div><div class="stat-badge ${color}">${color === 'green' ? icon('shield') : icon('check')}${badge}</div></article>`;
}
function chart(events) {
  const bins = Array.from({ length: 24 }, () => ({ block: 0, allow: 0 }));
  const now = Date.now(), duration = period * 3600000;
  events.forEach(e => { const i = Math.min(23, Math.max(0, Math.floor((e.time - (now - duration)) / duration * 24))); bins[i][e.action]++; });
  const max = Math.max(4, ...bins.map(b => b.block + b.allow));
  const top = Math.ceil(max / 4) * 4;
  const bars = bins.map((b, i) => {
    const x = 44 + i * 24.4, bh = b.block / top * 136, ah = b.allow / top * 136;
    return `<g><title>Period ${i + 1}: ${b.block} blocked, ${b.allow} allowed</title><rect x="${x}" y="${158 - bh}" width="13" height="${bh}" rx="3" fill="#79ac88"/><rect x="${x}" y="${158 - bh - ah}" width="13" height="${ah}" rx="3" fill="#dfeadf"/></g>`;
  }).join('');
  return `<div class="chart"><svg viewBox="0 0 650 195" role="img" aria-label="Simulated requests over the selected time period, grouped into 24 intervals">${[0, 1, 2, 3, 4].map(i => `<text x="0" y="${162 - i * 34}" fill="#8a928b" font-size="10">${top * i / 4}</text><line x1="32" y1="${158 - i * 34}" x2="638" y2="${158 - i * 34}" stroke="#edf0eb" stroke-dasharray="3 4"/>`).join('')}${bars}<text x="35" y="187" fill="#8a928b" font-size="10">${period === 24 ? '24 hours ago' : '7 days ago'}</text><text x="320" y="187" text-anchor="middle" fill="#8a928b" font-size="10">${period === 24 ? '12 hours ago' : '3.5 days ago'}</text><text x="638" y="187" text-anchor="end" fill="#8a928b" font-size="10">Now</text></svg></div>`;
}
function eventTable(events, compact = false) {
  if (!events.length) return `<div class="empty-state">${icon('activity')}<h3>No activity to show</h3><p>Run a simulation to see how your rules handle sample traffic.</p></div>`;
  return `<div class="table-scroll"><table><thead><tr><th>App / domain</th>${!compact ? '<th>Category</th><th>Reason</th>' : ''}<th>Decision</th><th class="align-right">Time</th></tr></thead><tbody>${events.map(e => { const app = APPS.find(a => a.id === e.app); return `<tr><td><div class="table-app">${appIcon(app)}<span><strong>${app.name}</strong><small>${esc(e.domain)}</small></span></div></td>${!compact ? `<td>${esc(e.category)}</td><td class="muted">${esc(e.reason)}</td>` : ''}<td><span class="decision ${e.action}">${icon(e.action === 'block' ? 'shield' : 'check')}${e.action === 'block' ? 'Blocked' : 'Allowed'}</span></td><td class="align-right time-cell"><time datetime="${new Date(e.time).toISOString()}" title="${esc(new Date(e.time).toLocaleString())}">${new Date(e.time).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</time></td></tr>`; }).join('')}</tbody></table></div>`;
}
function rulesPage() {
  return `<div class="notice">${icon('info')} These rules control the local simulator. Sample tracker domains use example.com.</div><section class="rules-grid">${APPS.map(app => `<article class="card rule-card"><div class="rule-app-heading">${appIcon(app)}<div><h2>${app.name}</h2><p>${app.description}</p></div>${button('Edit', `edit-${app.id}`, 'small-button')}</div>${CATEGORIES.map(cat => `<div class="category-row"><span>${icon(cat === 'Essential' ? 'globe' : cat === 'Ads' ? 'shield' : 'activity')}${cat}</span><button class="category-button ${state.rules[app.id][cat]}" data-action="category" data-app="${app.id}" data-category="${cat}" aria-label="${app.name} ${cat}: ${state.rules[app.id][cat]}. Click to change.">${state.rules[app.id][cat] === 'block' ? 'Block' : 'Allow'}${icon('down')}</button></div>`).join('')}</article>`).join('')}</section>
    <section class="card spaced"><div class="card-heading"><div><h2>Domain exceptions</h2><p>Give a domain a specific allow or block decision.</p></div><span class="count-badge">${state.exceptions.length} rules</span></div><form id="exception-form" class="exception-form"><label>Domain<input name="domain" placeholder="*.example.com" required maxlength="253" autocomplete="off"></label><label>Applies to<select name="app"><option value="all">All apps</option>${APPS.map(a => `<option value="${a.id}">${a.name}</option>`).join('')}</select></label><label>Decision<select name="action"><option value="allow">Allow</option><option value="block">Block</option></select></label><button class="btn dark" type="submit">${icon('plus')}Add rule</button><p id="exception-error" class="form-error" role="alert"></p></form><p class="form-hint">App exceptions take priority over global exceptions, then category rules. Wildcards match subdomains only.</p><div class="exception-list">${state.exceptions.length ? state.exceptions.map(rule => `<div class="exception-row"><span class="domain-text">${icon('globe')}${esc(rule.domain)}</span><span class="muted">${rule.app === 'all' ? 'All apps' : APPS.find(a => a.id === rule.app).name}</span><span class="decision ${rule.action}">${rule.action === 'allow' ? 'Allow' : 'Block'}</span><button class="icon-button" data-action="delete-rule" data-id="${esc(rule.id)}" aria-label="Delete rule for ${esc(rule.domain)}">${icon('close')}</button></div>`).join('') : '<div class="inline-empty">No exceptions yet. Your category rules make the decisions.</div>'}</div></section>
    <section class="card spaced"><div class="card-heading"><div><h2>Try a rule</h2><p>Check a domain without making a network request.</p></div>${icon('shield')}</div><form id="test-form" class="test-form"><label class="grow">Domain<input name="domain" value="ads.example.com" required></label><label>Application<select name="app">${APPS.map(a => `<option value="${a.id}">${a.name}</option>`).join('')}</select></label><button type="submit" class="btn secondary">Test decision ${icon('arrow')}</button></form><div id="test-result" class="test-result" role="status"></div></section>`;
}
function cleanerPage() {
  return `<div class="cleaner-layout"><section class="card cleaner-tool"><div class="tool-heading"><span class="tool-icon">${icon('link')}</span><h2>Good links travel light.</h2><p>Paste a URL and we’ll remove the tracking parameters.<br>Your links are processed here, in your browser.</p></div><form id="cleaner-form"><label for="dirty-link">Your original link</label><textarea id="dirty-link" name="url" rows="4" placeholder="https://example.com/article?utm_source=newsletter&fbclid=123" required>${esc(cleanerDraft)}</textarea><div class="form-toolbar"><button class="text-link" type="button" data-action="example-link">Try an example</button><button class="btn dark" type="submit">${icon('link')}Clean link</button></div><p id="cleaner-error" class="form-error" role="alert"></p></form><div id="clean-result" aria-live="polite"></div><div class="private-note">${icon('lock')} No links are sent to a server or saved in your history.</div></section><aside><section class="card cleaner-side"><h2>Leave these behind</h2><p>Common tracking parameters removed automatically.</p><div class="parameter-row"><code>utm_*</code><span>Campaign tracking</span>${icon('check')}</div><div class="parameter-row"><code>fbclid</code><span>Facebook click ID</span>${icon('check')}</div><div class="parameter-row"><code>gclid</code><span>Google click ID</span>${icon('check')}</div><div class="custom-params"><label for="custom-params">Your custom parameters</label><p>Separate names with commas. Use a trailing * to match a prefix.</p><form id="custom-form"><input id="custom-params" name="params" value="${esc(state.customParams.join(', '))}" placeholder="ref, campaign_*" maxlength="1000"><button class="btn secondary full-width" type="submit">Save parameters</button></form></div></section><div class="tip">${icon('info')}<p>Some parameters are needed for a link to work. Check the cleaned link before sharing it.</p></div></aside></div>`;
}
function showCleanResult() {
  document.querySelector('#clean-result').innerHTML = `<div class="result-card"><div class="result-title">${icon('check')} ${cleanResult.removed.length ? `${cleanResult.removed.length} tracking parameter${cleanResult.removed.length === 1 ? '' : 's'} removed` : 'This link is already clean'}</div><label for="clean-url">Your clean link</label><textarea id="clean-url" rows="3" readonly>${esc(cleanResult.url)}</textarea>${cleanResult.removed.length ? `<div class="removed-tags">${cleanResult.removed.map(p => `<span>${esc(p)}</span>`).join('')}</div>` : ''}<button class="btn dark" data-action="copy-link">${icon('copy')}Copy clean link</button></div>`;
}
function filteredActivity() {
  return eventsInPeriod().filter(e => (activityFilter === 'all' || e.action === activityFilter) && `${e.domain} ${e.category} ${APPS.find(a => a.id === e.app).name}`.toLowerCase().includes(activitySearch.toLowerCase()));
}
function activityPage() {
  return `<section class="card"><div class="card-heading"><div><h2>Connection history</h2><p>Simulated traffic only. Up to 2,000 events, retained for 7 days.</p></div>${button('Export JSON', 'export-events', 'secondary', 'download')}</div><div class="activity-toolbar"><div class="segmented" aria-label="Filter decisions">${[['all', 'All requests'], ['block', 'Blocked'], ['allow', 'Allowed']].map(([id, title]) => `<button class="${activityFilter === id ? 'selected' : ''}" data-action="filter" data-filter="${id}" aria-pressed="${activityFilter === id}">${title}</button>`).join('')}</div><label class="search-input">${icon('search')}<input id="activity-search" placeholder="Search app or domain…" aria-label="Search activity" value="${esc(activitySearch)}"></label></div><div id="activity-results">${activityResults()}</div></section>`;
}
function activityResults() {
  const events = filteredActivity();
  return `${eventTable(events.slice(0, activityLimit))}<div class="list-footer"><span>${Math.min(activityLimit, events.length)} of ${events.length} matching requests</span>${events.length > activityLimit ? button('Load more', 'load-more') : ''}</div>`;
}
function browserPage() {
  return `<section class="card browser-placeholder"><div class="browser-illustration">${icon('globe')}<span>${icon('lock')}</span></div><span class="tiny-tag">PLANNED FOR THE NATIVE APP</span><h2>A fresh start. Every session.</h2><p>Private Browser will provide isolated cookies, third-party filtering, and session deletion when you close it.</p><div class="browser-features"><span>${icon('lock')}Isolated storage</span><span>${icon('shield')}Third-party controls</span><span>${icon('check')}Delete on close</span></div><div class="notice">This web MVP does not provide an isolated browser or control your browser’s cookies. No private browsing session is active.</div><a href="#cleaner" class="btn dark">Try the link cleaner ${icon('arrow')}</a></section>`;
}
function settingsPage() {
  return `<section class="card settings-card"><div class="card-heading"><div><h2>Your local workspace</h2><p>Simple controls. Transparent defaults.</p></div>${icon('settings')}</div><div class="setting-row"><div><h3>Rule simulation</h3><p>Apply your rules to generated requests. Pausing allows all sample requests.</p></div><button class="toggle ${state.enabled ? 'on' : ''}" role="switch" aria-checked="${state.enabled}" aria-label="Enable rule simulation" data-action="toggle-simulation"><span></span></button></div><div class="setting-row"><div><h3>Local storage</h3><p>Preferences and sample events are stored in this browser. Links are never stored.</p></div><span class="decision ${persistenceAvailable ? 'allow' : 'block'}">${persistenceAvailable ? 'Available' : 'Unavailable'}</span></div><div class="setting-row"><div><h3>Activity retention</h3><p>At most 2,000 sample events from the last 7 days. No background traffic collection.</p></div><span class="count-badge">7 days</span></div><div class="setting-row"><div><h3>Export workspace</h3><p>Download your app rules, domain exceptions, and simulated events as JSON.</p></div>${button('Export', 'export-workspace', 'secondary', 'download')}</div><div class="setting-row"><div><h3>Clear activity</h3><p>Remove all simulated events. Your rules and custom parameters stay saved.</p></div>${button('Clear activity', 'clear-events', 'danger')}</div><div class="setting-row"><div><h3>Restore demo data</h3><p>Replace the event history with a new sample using your current rules.</p></div>${button('Restore demo', 'restore-demo', 'secondary')}</div></section><div class="notice spaced">${icon('info')} PrivacyGuard MVP v0.1 · VPN forwarding, device app discovery, real tracker lists, and private browsing are not implemented in this web version.</div>`;
}
function editApp(appId) {
  const app = APPS.find(a => a.id === appId);
  const dialog = document.querySelector('#rule-dialog');
  dialog.innerHTML = `<form id="app-form" data-app="${appId}"><div class="dialog-heading">${appIcon(app)}<h2 id="dialog-title">${app.name} rules</h2><button type="button" class="icon-button" data-action="close-dialog" aria-label="Close dialog">${icon('close')}</button></div><p class="muted">Choose what this app can do in the simulator.</p>${CATEGORIES.map(cat => `<label class="dialog-category">${cat}<select name="${cat}"><option value="block" ${state.rules[appId][cat] === 'block' ? 'selected' : ''}>Block</option><option value="allow" ${state.rules[appId][cat] === 'allow' ? 'selected' : ''}>Allow</option></select></label>`).join('')}<div class="dialog-note">Blocking Essential traffic may prevent an app from working when native protection becomes available.</div><div class="dialog-actions"><button class="btn secondary" type="button" data-action="close-dialog">Cancel</button><button class="btn dark" type="submit">Save rules</button></div></form>`;
  dialog.showModal();
}
function download(value, name) {
  const url = URL.createObjectURL(new Blob([JSON.stringify(value, null, 2)], { type: 'application/json' }));
  const a = document.createElement('a'); a.href = url; a.download = name; a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  toast('Export downloaded.');
}
document.addEventListener('click', async event => {
  const el = event.target.closest('[data-action]'); if (!el) return;
  const action = el.dataset.action;
  if (action === 'edit-app' || action.startsWith('edit-')) { editApp(el.dataset.app || action.slice(5)); return; }
  if (action === 'close-dialog') { document.querySelector('#rule-dialog').close(); return; }
  if (action === 'simulate') {
    const entries = makeEvents(state); state.events = [...entries, ...state.events].sort((a, b) => b.time - a.time); save(); shell();
    toast(`12 sample requests analyzed · ${entries.filter(e => e.action === 'block').length} blocked${state.enabled ? '' : ' (simulator paused)'}.`);
  } else if (action === 'toggle-simulation') { state.enabled = !state.enabled; save(); shell(); toast(state.enabled ? 'Rule simulation enabled.' : 'Simulation paused. Sample requests will be allowed.'); }
  else if (action === 'category') {
    const { app, category } = el.dataset;
    state.rules[app][category] = state.rules[app][category] === 'block' ? 'allow' : 'block';
    save(); shell(); toast(`${APPS.find(a => a.id === app).name}: ${category} set to ${state.rules[app][category]}.`);
  } else if (action === 'delete-rule') { state.exceptions = state.exceptions.filter(r => r.id !== el.dataset.id); save(); shell(); toast('Domain exception removed.'); }
  else if (action === 'example-link') { cleanerDraft = 'https://example.com/article?utm_source=newsletter&utm_medium=email&fbclid=abc123&topic=privacy#read'; document.querySelector('#dirty-link').value = cleanerDraft; cleanResult = null; document.querySelector('#clean-result').innerHTML = ''; document.querySelector('#cleaner-error').textContent = ''; }
  else if (action === 'copy-link') {
    try { await navigator.clipboard.writeText(cleanResult.url); toast('Clean link copied.'); }
    catch { document.querySelector('#clean-url').select(); toast('Select and copy the clean link with your browser.'); }
  } else if (action === 'filter') { activityFilter = el.dataset.filter; activityLimit = 30; shell(); }
  else if (action === 'load-more') { activityLimit += 30; document.querySelector('#activity-results').innerHTML = activityResults(); }
  else if (action === 'export-events') download({ exportedAt: new Date().toISOString(), simulated: true, events: filteredActivity() }, 'privacyguard-activity.json');
  else if (action === 'export-workspace') download(state, 'privacyguard-workspace.json');
  else if (action === 'clear-events') { state.events = []; save(); shell(); toast('All sample activity cleared.'); }
  else if (action === 'restore-demo') { state.events = makeEvents(state, 864, true); save(); shell(); toast('Demo activity restored using your current rules.'); }
});
document.addEventListener('change', event => {
  if (event.target.id === 'period') { period = Number(event.target.value); activityLimit = 30; shell(); }
});
document.addEventListener('input', event => {
  if (event.target.id === 'activity-search') { activitySearch = event.target.value; activityLimit = 30; document.querySelector('#activity-results').innerHTML = activityResults(); }
  if (event.target.id === 'dirty-link') { cleanerDraft = event.target.value; cleanResult = null; document.querySelector('#clean-result').innerHTML = ''; document.querySelector('#cleaner-error').textContent = ''; }
});
document.addEventListener('submit', event => {
  event.preventDefault();
  const form = event.target, data = new FormData(form);
  if (form.id === 'cleaner-form') {
    document.querySelector('#cleaner-error').textContent = '';
    try { cleanResult = cleanLink(data.get('url'), state.customParams); showCleanResult(); }
    catch (error) { cleanResult = null; document.querySelector('#clean-result').innerHTML = ''; document.querySelector('#cleaner-error').textContent = error.message; }
  } else if (form.id === 'custom-form') {
    state.customParams = data.get('params').split(',').map(p => p.trim()).filter(Boolean).slice(0, 100);
    cleanResult = null; save(); shell(); toast('Custom tracking parameters saved.');
  } else if (form.id === 'exception-form') {
    try {
      const domain = normalizeDomain(data.get('domain'), true), app = data.get('app'), action = data.get('action');
      const existing = state.exceptions.find(r => r.domain === domain && r.app === app);
      if (!existing && state.exceptions.length >= 500) throw new Error('You have reached 500 exceptions. Remove a rule before adding another.');
      if (existing) existing.action = action;
      else state.exceptions.push({ id: crypto.randomUUID(), domain, app, action });
      save(); shell(); toast(existing ? 'Domain exception updated.' : 'Domain exception added.');
    } catch (error) { document.querySelector('#exception-error').textContent = error.message; }
  } else if (form.id === 'test-form') {
    const resultEl = document.querySelector('#test-result');
    try { const result = evaluate(data.get('domain'), data.get('app'), state); resultEl.innerHTML = `<span class="decision ${result.action}">${result.action === 'block' ? 'Blocked' : 'Allowed'}</span><span>${esc(result.domain)} · ${result.category} · ${result.reason}</span>`; }
    catch (error) { resultEl.textContent = error.message; }
  } else if (form.id === 'app-form') {
    for (const category of CATEGORIES) state.rules[form.dataset.app][category] = data.get(category);
    save(); document.querySelector('#rule-dialog').close(); shell(); toast('App rules saved. Run a simulation to try them.');
  }
});
window.addEventListener('hashchange', () => { page = location.hash.slice(1) || 'overview'; shell(); document.querySelector('#main').focus(); window.scrollTo(0, 0); });
save(); shell();
