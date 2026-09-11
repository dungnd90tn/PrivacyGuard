# PrivacyGuard MVP

A responsive, local-first web app for exploring privacy rules and cleaning tracking parameters from links. Built with browser JavaScript, CSS, and a small Node.js server. No dependencies, accounts, API keys, or build step are required.

## Run

Requires Node.js 20 or newer.

```sh
npm start
```

Open **http://localhost:3000**. To choose another port, use `PORT=3001 npm start`. The server binds to localhost by default. Set `HOST=0.0.0.0` explicitly if you need to expose it on your network. Clipboard and UUID browser APIs require localhost or HTTPS.

```sh
npm test
```

## Working features

- **Overview:** responsive dashboard, request totals, activity chart, and per-app counts derived from simulated events. Select the last 24 hours or 7 days.
- **App rules:** independent Ads, Analytics, and Essential policies for Facebook, Zalo, Chrome, and Instagram; changes persist locally.
- **Domain exceptions:** exact and wildcard allow/block rules scoped to one app or all apps, with deterministic precedence and a domain decision tester.
- **Traffic simulation:** generate sample requests against current rules or pause evaluation to allow all sample requests. Existing decisions remain historical when rules change.
- **Link cleaner:** strip `utm_*`, `fbclid`, `gclid`, and custom names/prefixes; preserve unrelated query encoding and fragments; copy the result. Links are processed in memory and never persisted.
- **Activity log:** search apps, domains, and categories; filter decisions; load more results; export matching events to JSON.
- **Settings:** export the workspace, clear activity, restore sample events, and toggle simulation.

First launch creates 864 clearly labeled sample requests using reserved `example.com` domains. App names are illustrative; the app does not discover installed apps or inspect their traffic. Preferences and sample events live in this browser's `localStorage`, capped at 2,000 events and 7 days. Storage failures fall back to session-only operation with a visible notice.

## Scope

**This MVP does not block real network traffic.** It has no TUN interface, IP/UDP/TCP/DNS packet parser, VPN forwarding, OS application attribution, or production tracker feed. The Private Browser screen describes planned native functionality; it does not provide isolated cookies, third-party blocking, or delete-on-close browsing.

Device-wide protection and an isolated browser require a native implementation. See [ARCHITECTURE.md](ARCHITECTURE.md) for the proposed next stages.

## Rule semantics

1. Per-app domain exception.
2. Global domain exception (allow or block).
3. Per-app category preference.
4. Global category default: block Ads and Analytics, allow Essential.
5. Unknown domains are allowed.

Within one scope, exact matches win over wildcards; otherwise the longest suffix wins. `*.example.com` matches `a.example.com` and `a.b.example.com`, but excludes `example.com` and `notexample.com`. Saving the same domain/scope updates the existing rule.

The sample classification list in `lib.js` is deliberately small. It demonstrates rule behavior and is not a production tracker list.

## Files

- `app.js`: interface, interactions, persistence, and exports.
- `lib.js`: rule evaluation, domain normalization, link cleaning, sample events, and saved-state validation.
- `styles.css`: responsive layout and visual design.
- `server.mjs`: local HTTP server with an explicit public asset list and restrictive response headers.
- `test/lib.test.js`: tests for domain boundaries, policy precedence, app isolation, URL preservation, sample events, and storage recovery.
