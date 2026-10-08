# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Rule #0 — NEVER commit (applies to every agent, every task)

**No agent — main thread or sub-agent — may commit, amend, push, tag, merge, revert, or stash anything.** When work is finished, leave the working tree exactly as it is so the change can be reviewed independently, diff-by-diff, before it enters git history.

Forbidden without an explicit, in-the-moment instruction from the stakeholder: `git commit`, `git commit --amend`, `git push`, `git tag`, `git merge`, `git rebase`, `git revert`, `git reset`, `git checkout -- <file>`, `git restore`, `git stash`, `git clean`, and any `gh pr create` / `gh pr merge`.

- Read-only git is always fine and encouraged: `git status`, `git diff`, `git log`, `git show`.
- Finish state = modified files on disk, unstaged, nothing hidden. Do not "tidy up" by stashing or reverting your own work, and never discard a change to escape a failing test or review (see the loop rule).
- Report what changed with file paths and a summary of the diff — that report **is** the deliverable handoff, not a commit.
- Only the human stakeholder decides when and how the change is committed. If a commit genuinely seems needed, ask; do not assume prior approval carries over to the next change.

## Rule #1 — Graphify-first discovery (all agents, before any work)

**Nothing starts — no requirement spec, no task breakdown, no line of code, no test — before the existing state of the system has been established from the knowledge graph.** The graph covers the whole product: backend services and shared libraries, the Blazor frontend, the EF Core data model, and every document under `docs/` (ADRs, requirements, design specs, runbooks, incident reports). Discovering by grep, by memory, or by asking the stakeholder what already exists is how this repo has re-specified features that were already built and "fixed" code no live path calls.

**How to run it** — the CLI is installed but is **not** on the agents' `PATH`; call it by absolute path:

```bash
~/.local/bin/graphify query "<question>"                                              # backend services + shared libs (~43.5k nodes)
~/.local/bin/graphify query "<q>" --graph graphify-out/shards/frontend/graph.json     # Blazor / Razor / UI / mobile
~/.local/bin/graphify query "<q>" --graph graphify-out/shards/data-model/graph.json   # EF Core DbContext / DbSet / entities / tables
~/.local/bin/graphify query "<q>" --graph docs/graphify-out/graph.json                # docs/: ADR, requirements, design, security, ops (~12.7k nodes)
~/.local/bin/graphify path "<A>" "<B>"                                                # relationship between two concepts
~/.local/bin/graphify explain "<concept>"                                             # plain-language explanation of one node
```

- **Check the graph is current before you trust a negative result: `python3 tools/graphify/build_coverage.py verify`** (~10 s). It prints `FRESH` / `STALE` per corpus — backend, frontend, documentation — by re-hashing every indexed source file against `graphify-out/source-index.json`, and exits non-zero naming the offending files and the rebuild command. A `STALE` corpus invalidates every "not found" it produced; rebuild first. (Between 2026-08-28 and 2026-09-10 the backend graph was never re-extracted by any command in this repo while `verify` still said OK, so two days of new services were invisible to `query` and would have been reported absent under E7.)
- Results are **truncated to a token budget**. `TRUNCATED: showing 13 of 461 nodes` does **not** mean "not found" — raise it (`--budget 2000`) or narrow the query before concluding anything is absent.
- The graph indexes **declarations** — files, namespaces, types, members, imports — not the text of string literals. A query for `"cluster-agent-v2"` returns nothing even though its file is fully indexed. Query the declaring type or method, and never log a literal miss as an E7 absence.
- Every node carries `src=` and `loc=` — jump straight to that `file:line`. `rg` / `find` are for *confirming* what the graph pointed at, constrained to those paths; they are never the first move, and a repo-wide scan before a graph query is a process FAIL.
- The **root graph intentionally excludes** tests, deployment, generated artifacts and third-party dependencies — absence there says nothing about `tests/`, `deployment/` or `tools/`. For those, inspect the known path directly and say that is what you did.
- **EF Core `**/Migrations/*.Designer.cs` snapshots are excluded from every graph** (`.graphifyignore` + `excluded_from_root_patterns` in `graphify-out/scope.json`). `dotnet ef` regenerates a full model copy per migration, which was 30% of the root graph. A graph miss on a `*.Designer.cs` symbol is therefore **expected and proves nothing** — it is not an **E7** negative result. What is still graphed, and is what you should query: the entity classes, the hand-written `Up()`/`Down()` migration files, and the live `<Context>ModelSnapshot.cs`. For the shape of the schema use the `data-model` shard; to read a designer file itself, open the path on disk and say that is what you did.
- `graphify-out/wiki/` does **not** exist in this repo — do not route through it. `graphify-out/GRAPH_REPORT.md` is for broad architecture review only, when query/path/explain do not surface enough.
- The `skill is from graphify 0.4.15, package is 0.9.51` warning on every run is expected, not an error.

**The discovery pass — Prior-Art Inventory (blocking).** Before a stakeholder request reaches `@project-manager` (step 0 of the execution flow), and before any agent's first substantive action on a task, query the graph for what already exists on the requested surface and write it down:

| What must be established | Query against |
|---|---|
| Is the feature / endpoint / service already implemented (fully or partially)? | root graph |
| Is the screen, component, or localization key already there? | `shards/frontend` |
| Is the entity, table, column, or migration already modelled? | `shards/data-model` |
| Is the requirement, ADR, design spec, runbook or incident report already written? | `docs/graphify-out` |

The inventory states, with `file:line` citations: **what exists, what partially exists, what genuinely does not exist, and which ADR / spec / requirement already governs this area.** Every downstream role builds on it — BA writes the delta against what exists, `@systems-architect` checks the governing ADR before proposing a new one, `@tech-lead` names the existing code each task must reuse, `@documentation-writer` updates the existing document instead of creating a rival one. "Nothing found" is only a valid answer when the query was not truncated **and** the relevant shard was queried — otherwise it is an unverified claim (rule **E7**).

Two more standing rules:

- Widen beyond the graph-guided paths only when graph-guided inspection was insufficient, the task is *about* missing or stale graph data, or the stakeholder asked for an exhaustive inventory — and say why you widened.
- **After modifying any code, rebuilding the graph before handoff is mandatory:** `python3 tools/graphify/build_coverage.py build`, then `python3 tools/graphify/build_coverage.py verify`. `build` re-extracts every backend project listed in `graphify-out/scope.json` and every frontend project, merges each corpus into its published graph, and rebuilds the documentation and data-model shards — measured **1 m 34 s** end to end on 2026-09-10 (backend extraction ~45 s of that; AST-only, no LLM calls, no API cost), faster on a warm `graphify-out/cache/`. `verify` then checks size (no `graph.json` may reach 80,000,000 bytes), presence, **and freshness**, and fails loudly on a stale corpus. `build --no-backend-refresh` / `--no-frontend-refresh` skip an extraction you know is unnecessary; they leave `source-index.json` untouched on purpose, so a skipped corpus still reports `STALE` in `verify` instead of being laundered green. `verify --skip-freshness` is size-only and must not be used before a handoff. The graph must use the canonical `tests/`, `tools/`, `deployment/` paths — the pre-2026-07-03 root paths (`*.Tests/`, `scripts/`, `kubernetes/`, `docker-compose*.yml`) are invalid.

Dirty `graphify-out/` files are expected and are never a reason to bypass graph-first discovery. The only exemptions are a task about stale/incorrect graph output, or an explicit stakeholder instruction not to use it.

## Project Overview

PrivacyGuard is an early-stage, privacy-focused Android app (network filtering, per-app rules, tracking-link cleaning, a private browser, and a dashboard) plus a standalone web prototype used to validate the UX and rule-evaluation logic before the native implementation exists.

- `app/` is the real product target: a native Android MVP in Kotlin, with a DNS-only split-route VPN, rules editor, local dashboard/history, link cleaner, and a separate-process WebView session. See `README.md` and `docs/mvp.html` for build instructions, scope, and verification limits.
- `prototype-web/` is a separate, fully working local web app (vanilla JS + a tiny Node static server, no dependencies) that mirrors the same rule semantics so the UX and rules can be explored without the native app. It explicitly does **not** block real network traffic — see its README's Scope section.

See [ARCHITECTURE.md](ARCHITECTURE.md) for the authoritative design: proposed modules, decision flow, rule precedence, privacy/accuracy requirements, and delivery sequence. Full detail in the Architecture section below.

## Documentation Reference

**Before starting any task, read:**

- **[ARCHITECTURE.md](ARCHITECTURE.md)** — proposed modules (VPN Core, Tracker Engine, App Rules, Link Cleaner, Private Browser, Dashboard), decision flow, rule precedence semantics, privacy/accuracy requirements, delivery sequence, and initial acceptance cases. This is the authoritative spec for `app/` — read the relevant section before touching packet/DNS parsing, rule evaluation, or link cleaning, in either `app/` or `prototype-web/`.
- **[prototype-web/README.md](prototype-web/README.md)** — what the web prototype does and its explicit non-scope (no TUN/VPN, no packet parser, no OS app attribution, no production tracker feed).

There is no `docs/` folder, no ADRs, and no additional services or ports beyond what's listed above — do not assume enterprise documentation exists in this repo.

## Documentation Output Format (MANDATORY)

**All new documentation deliverables MUST be authored as printable HTML, not Markdown.** This applies to every document Claude produces as a task output — reports, runbooks, ADRs, design specs, incident write-ups, status updates, audit reports, compliance attestations, release notes, and any other artifact intended for human consumption or archival.

- **File extension:** `.html` (never `.md` for new deliverables).
- **Print-ready required:** every HTML document must include a print stylesheet so that File → Print / Save as PDF produces a clean, paginated document without browser chrome, navigation, or interactive controls. Use `@media print` rules to:
  - set `@page { size: A4; margin: 18mm 16mm; }` (or Letter where a US audience is specified)
  - force `body { -webkit-print-color-adjust: exact; print-color-adjust: exact; }` so brand colors render
  - hide non-print elements (`.no-print { display: none !important; }` for nav, ToC toggles, copy buttons)
  - control pagination: `h1, h2 { page-break-after: avoid; }`, `table, pre, figure { page-break-inside: avoid; }`, `thead { display: table-header-group; }`
  - render link URLs after anchor text in print: `a[href]::after { content: " (" attr(href) ")"; }` for external links only
- **Self-contained:** documents must render correctly when opened as a single file on disk. Inline CSS in a `<style>` block (or one sibling `.css` file in the same folder). Do NOT depend on CDN assets, external fonts that fail offline, or framework runtimes. Embed images as relative paths or base64 when small.
- **Structure:** semantic HTML5 (`<header>`, `<main>`, `<section>`, `<article>`, `<table>`, `<figure>`, `<figcaption>`). Include `<title>`, `<meta charset="utf-8">`, `<meta name="viewport">`, and a printable header/footer block at the top of `<body>` (document title, version, date, classification, author).
- **Brand alignment:** this repo has no shared CSS design-token file (no Blazor frontend, no `--dp-*` tokens). Use a small, consistent inline palette in the report's own `<style>` block and verify WCAG AA contrast survives both screen and print.
- **Verify before delivery:** open the file in a browser and run "Print preview" — confirm pagination, color fidelity, no overflowing tables, and no clipped content. State explicitly in your end-of-turn summary that print preview was checked.
- **Existing Markdown documents:** do NOT mass-convert existing `.md` files. Convert to HTML only when (a) the document is being substantively rewritten in this task, or (b) the user explicitly asks for conversion. New companion documents to an existing `.md` should still be HTML.
- **Exceptions (Markdown still allowed):** `CLAUDE.md`, `README.md`, `.github/copilot-instructions.md`, `.github/instructions/*.md`, agent/skill prompt files, in-repo memory files, and chat-only responses. ADRs and other `docs/` artifacts going forward must be HTML.

## Build & Test Commands

### Android app (`app/`)

The Gradle Wrapper is present and pinned to Gradle 8.11.1 with its distribution SHA-256. Use JDK 17 and Android SDK 35 (`ANDROID_HOME` or `local.properties`) before running these commands:

```bash
# Build the debug APK
./gradlew :app:assembleDebug

# Run Android Lint — already configured to fail on any finding (lint { abortOnError = true } in app/build.gradle.kts)
./gradlew :app:lint

# Run JVM unit tests (rules, packets, DNS/TCP, and JSON policy recovery)
./gradlew :app:testDebugUnitTest

# Build the dependency-free Android instrumentation test APK; see README before running on an emulator
./gradlew :app:assembleDebugAndroidTest
```

Key facts from the build files: Android Gradle Plugin 8.9.2, Kotlin 2.1.20, `compileSdk`/`targetSdk` 35, `minSdk` 29, Java 17 toolchain (`app/build.gradle.kts`). `gradle.properties` sets `android.useAndroidX=true` — AndroidX is present only through Robolectric test dependencies; there are no AndroidX runtime libraries, so check the scope before using an AndroidX API.

### Web prototype (`prototype-web/`)

A separate, unrelated Node.js project (no dependencies, no build step) — see [prototype-web/README.md](prototype-web/README.md).

```bash
cd prototype-web
npm start          # serves the SPA at http://localhost:3000 (binds 127.0.0.1 by default; PORT/HOST env vars override)
npm test           # runs prototype-web/test/lib.test.js via Node's built-in node:test runner (Node >= 20 required)
```

## Architecture

**Two independent codebases in this repo, not yet integrated:**

| Path | What it is | Status |
|---|---|---|
| `app/` | The real product: a native Android app (Kotlin) | MVP implemented; device verification remains required (see `docs/mvp.html`) |
| `prototype-web/` | A local-first web app (vanilla JS + a tiny Node static server) | Fully working UI/UX + rule-logic demo; explicitly not the shipped product |

They implement the **same rule semantics independently** — Kotlin in `app/src/main/java/com/privacyguard/android/core/Rules.kt` vs. JavaScript in `prototype-web/lib.js`. There is no shared source between them: a change to rule precedence, domain matching, or link-cleaning behavior in one does not update the other. `ARCHITECTURE.md` is the authoritative semantics both must follow — check it, and keep both in sync by hand.

### `app/` — Android module (`com.privacyguard.android`)

`AndroidManifest.xml` (`app/src/main/AndroidManifest.xml`) declares three implemented components:
- `.MainActivity` — launcher activity; also handles `ACTION_SEND` for `text/plain` (share-to-clean-link entry point)
- `.PrivateBrowserActivity` — runs in its own process (`:private_browser`), excluded from recents, not exported
- `.DnsVpnService` — a `VpnService` (`BIND_VPN_SERVICE`, foreground service type `specialUse`, `SUPPORTS_ALWAYS_ON=false`)

What exists today, under `app/src/main/java/com/privacyguard/android/core/`:
- **`Packets.kt`** — dependency-free IPv4/IPv6 header validation, UDP parsing + checksum (RFC 1071), DNS question parsing, DNS error/reply construction, and response validation (ID + echoed-question matching). This is the packet layer `DnsVpnService` will eventually call into; no Android dependencies, so it's unit-testable in isolation.
- **`Rules.kt`** — the rule-evaluation core: `Category`/`Action`/`DomainRule`/`Decision`/`Policy` data types, `Rules.decide()` (tracker classification → per-app/global domain exception → per-app/global category policy → default allow, per `ARCHITECTURE.md`'s rule precedence), domain normalization (`IDN` + label/length validation, exact-vs-wildcard + longest-suffix precedence), and a separate `LinkCleaner` object stripping `utm_*`/`fbclid`/`gclid`/custom query keys while preserving unrelated query parts and the fragment.

`app/src/main/res/` has a launcher icon, base theme, strings, and backup exclusions. Native layouts are constructed programmatically with `Ui.kt`; no AndroidX runtime is used. Version 0.3 adds persisted DNS profiles (`DnsSettingsCodec.kt`, `core/DnsServers.kt`) and protected UDP/TCP forwarding (`core/DnsForwarder.kt`), with plain-language request details (`RequestText.kt`). See `docs/dns-requests.html`. Version 0.2 uses blue light/dark palettes and persistent bottom tabs; `Traffic.kt` filters the complete retained history and separates All/Unknown/package scopes. See `docs/mobile-redesign.html` for native render evidence and tests. Policies/counters are stored by `GuardStore.kt`, and platform-independent DNS/TCP processing lives in `core/DnsFilter.kt` and `core/TcpDns.kt`.

Native version 0.4 adds optional authenticated DNS-over-TLS on upstream port 853, with VPN DNS input still on UDP/TCP 53. `core/UrlAnalysis.kt` makes display-only URL/query inferences; `RequestSession` is an opt-in, bounded process-memory journal for the private browser. Never persist URL/path/query values to DNS events, preferences or exports; never use query inferences as blocking policy. See `docs/query-dns-tls.html` for evidence and limits. Test TLS certificates live in test resources only; production uses platform trust roots.

### `prototype-web/` — web prototype (Node ≥ 20, zero dependencies)

- `server.mjs` — static file server with an explicit allowlisted asset map (no directory traversal), restrictive security headers (CSP, `X-Content-Type-Options`, `Referrer-Policy`), binds to `127.0.0.1` unless `HOST` is set.
- `lib.js` — the JS mirror of the rule engine: sample app/category/tracker data, `evaluate()`, `cleanLink()`, `normalizeDomain()`, sample-event generation, and saved-state validation/recovery.
- `app.js` — the SPA: hash-based routing across six pages (overview, rules, cleaner, browser, activity, settings), `localStorage`-backed persistence (capped at 2,000 events / 7 days, with a session-only fallback on storage failure).
- `styles.css` — responsive layout.
- `test/lib.test.js` — `node:test` coverage for domain-matching boundaries, policy precedence, per-app isolation, URL/query preservation, sample-event generation, and storage recovery.

**Explicit non-scope** (from `prototype-web/README.md`): the web prototype does not block real network traffic, has no VPN/TUN interface, no packet parser, no OS app attribution, and no production tracker feed — that is exactly what `app/` is for.

`ARCHITECTURE.md` also defines the full proposed module list beyond what's built (Tracker Engine, App Rules, Private Browser, Dashboard), the packet-to-decision flow, and the delivery sequence — consult it before adding a new module or changing rule semantics.

## Code Conventions

- **Kotlin style:** `kotlin.code.style=official` (`gradle.properties`); Kotlin 2.1.20, Java 17 toolchain.
- **Stateless logic as `object` singletons:** `Packets`, `Rules`, `LinkCleaner` are Kotlin `object`s of pure functions — no injected state, no Android framework dependency in `core/`. Keep that package platform-agnostic so it stays unit-testable without an emulator.
- **Value types as `data class`:** `UdpPacket`, `DnsQuestion`, `DomainRule`, `Decision`, `Policy`, etc. — prefer this over a class with mutable fields.
- **Validation via `require()`:** invalid input throws `IllegalArgumentException` with a user-facing message (`Rules.normalize`, `LinkCleaner.clean`) — except the packet-parsing path (`Packets.parseUdp`, `Packets.question`), which returns `null` on malformed input instead of throwing, since malformed network data is expected, not exceptional.
- **User-facing strings are Vietnamese, written directly in code** (`Category.label`, `require()` messages in `Rules.kt`) — there is no localization/resource layer yet. Identifiers and comments stay in English.
- **Android Lint gates the build:** `lint { abortOnError = true }` in `app/build.gradle.kts` — a new lint finding fails `:app:lint` (and anything depending on it); it is not an optional warning.
- **`android.useAndroidX=true`** — required by Robolectric 4.17 test-only AndroidX dependencies. There are still no AndroidX runtime dependencies; do not assume AndroidX UI APIs are available.
- **`prototype-web/` JS:** plain ES modules, no build step, no external dependencies — keep it that way per its README ("No dependencies, accounts, API keys, or build step are required").

## Testing Stack

- **Android (`app/`):** `app/src/test/` contains JUnit tests for rules, packets, DNS/TCP and policy JSON recovery (`org.json` is a test-only dependency). `app/src/androidTest/` contains `MvpInstrumentation`, using the platform Instrumentation API to verify storage, UI and real DNS through TUN on a test emulator. See `README.md` for setup and `docs/mvp.html` for execution status.
- **Web prototype (`prototype-web/`):** Node's built-in `node:test` runner (`npm test`, requires Node ≥ 20), no external test framework. `test/lib.test.js` covers domain-matching boundaries, policy precedence, per-app rule isolation, URL/query preservation, sample-event generation, and `localStorage` failure recovery — run it whenever `lib.js` changes, and mirror any rule-semantics change into `app/.../core/Rules.kt` (see Architecture) since the two are not shared code.

## Key Infrastructure Files

- `settings.gradle.kts` — root Gradle project name (`PrivacyGuard`) and the single included module (`:app`)
- `build.gradle.kts` (root) — plugin versions: Android Gradle Plugin 8.9.2, Kotlin 2.1.20 (`apply false`, applied per-module)
- `app/build.gradle.kts` — the Android module: namespace/applicationId `com.privacyguard.android`, SDK versions, Java 17 toolchain, lint config
- `gradle.properties` — JVM args, `kotlin.code.style=official`, `android.useAndroidX=true`
- `ARCHITECTURE.md` — the design source of truth: proposed modules, decision flow, rule precedence, privacy/accuracy requirements, delivery sequence, acceptance cases
- `prototype-web/README.md` — what the web prototype does and its explicit non-scope
- `.gitignore` — excludes `.gradle/`, `.kotlin/`, `local.properties`, `**/build/`, `*.iml`, `.idea/`, keystores

There is no `.github/` directory and no per-task instruction files in this repo — do not reference paths like `.github/instructions/*.md` unless they are added.

## Agent Workflow (MANDATORY — every agent reads this before starting work)

This repo uses an agent-based development workflow. This section (plus the per-role detail in each `.claude/agents/<role>.md` / `.github/agents/<role>.md`) is the source of truth — every specialized agent must read it at the start of its conversation, in addition to its own agent file. The rules below are not optional guidance.

**Pipeline:** `@project-manager (sơ bộ) → @business-analyst (requirement + performance gate + security requirements) → @systems-architect + @database-expert (performance & security design — DB expert on every query/index) → @tech-lead (technical breakdown + assignment) → @security-engineer (pre-implementation task validation) → @ui-ux-designer → @database-expert → @backend-developer → @frontend-developer → @tech-lead (code review) → @qa-engineer (incl. performance verification) → @ui-ux-designer (post-QA review) → @security-engineer (post-implementation assessment) → @tech-lead (consolidated report) → @project-manager (gate) → @documentation-writer`

### Mandatory execution flow (every stakeholder request)

The main thread does **not** start implementing a stakeholder request directly. It runs the discovery gate below, then this 7-step loop, dispatching each role with the `Agent` tool:

0. **Discovery → graphify (blocking, main thread, before PM).** Establish what already exists before routing the request anywhere: run the Prior-Art Inventory of Rule #1 across the root graph plus every relevant shard (frontend / data-model / docs). Hand the inventory to `@project-manager` together with the stakeholder's words — it is an input to every later step, and every role re-queries the graph for its own surface rather than trusting a summary. A request that entered the pipeline without it is routed back: the cost of skipping this step is a spec written against a system that already behaves differently.
1. **Intake → PM (preliminary only).** Dispatch `@project-manager` first for a *sơ bộ* pass: what area of the product this touches, which roles will be involved, obvious risks/dependencies, priority, and whether the request is clear enough to proceed. PM does **not** write the detailed requirement, the case list, or the technical task breakdown. If the request is ambiguous or contradictory, PM raises it with the stakeholder now, before anything goes downstream.
2. **Requirement analysis → BA.** Dispatch `@business-analyst` with PM's preliminary read. BA produces the authoritative requirement specification: user stories, functional + non-functional requirements, business rules, and the **complete case set** (happy / edge / negative — see below). BA must also state the **performance gate** (mandatory budgets below, per screen/endpoint the change touches) and the **security requirements** attached to each requirement — both are part of the spec, not an afterthought. BA is the requirement owner; nobody implements against PM's sơ bộ notes.
3. **Technical breakdown → Tech Lead, with SA (+ `@database-expert`) on performance design.** BA's spec goes to `@tech-lead`. `@systems-architect` is consulted **whenever the change needs an architecture decision or an ADR, and always when the performance gate is in play** (new/changed page load, new or modified API, new query/join, new cache surface, cross-service call added, payload or list size grown) — SA designs how the budget will be met (caching layer + keys + TTL + invalidation, payload shape, batching/parallelism, cold-cache path) before any code is written. **Whenever the change touches the database — any new/modified query, join, filter, sort, pagination, aggregate, migration or growing table — `@database-expert` is consulted in the same step** and owns the query/index side of that design (query plan, index strategy, N+1 removal, bounded result set, `EXPLAIN ANALYZE` evidence). SA and `@database-expert` deliver one combined performance design; neither part is optional when both apply. Tech Lead translates the requirement into implementation tasks, decides the technical approach, and assigns each task to the owning DEV agent (`@database-expert` / `@backend-developer` / `@frontend-developer`) using the task assignment contract below — every task carries its performance budget and its security requirements. PM does not do this breakdown — PM owns priority, sequencing and delivery risk, Tech Lead owns *how* it gets built and *who* builds it.
4. **Pre-implementation security validation → Security.** Before any DEV agent starts, `@tech-lead` sends the task pack to `@security-engineer`, who validates each task *as designed*: are the security requirements from BA present and correct, is tenant isolation specified on every data path, authz (RBAC/ABAC + permission code) named, input validation/output encoding defined, secrets/PII handling and encryption stated, audit events identified, rate limiting/CORS considered, and does the technical approach introduce a design-level vulnerability (IDOR, injection, SSRF, mass assignment, insecure direct object exposure, missing authorization on a new endpoint). Verdict per task: **PASS** / **PASS with required changes** / **FAIL**. A task with a FAIL or unaddressed required change does **not** go to a DEV agent — it returns to `@tech-lead` (and to `@business-analyst` if the gap is in the requirement itself) and is re-validated.
5. **DEV implements → Tech Lead validates.** DEV agents build under the minimal implementation rule and report back to `@tech-lead`, not to PM. Tech Lead reviews the actual diff (minimality, maintainability, correctness, conventions, and that the security requirements + performance design from steps 3–4 are actually implemented) and loops FAILs back to the owning DEV agent until PASS. Nothing reaches QA on a Tech Lead FAIL.
6. **Verification.** `@qa-engineer` tests against BA's case set **in a real browser driven by Playwright** (incl. UI/mockup/i18n — script/unit checks only for cases with no browser path, with the reason stated) **and measures the performance gate** (cold-cache and warm, page-level P95 + LCP/INP), `@ui-ux-designer` does the post-QA review, `@security-engineer` runs the post-implementation OWASP/NIST/ISO pass (which also re-checks that its step-4 required changes were built). Findings loop back to the owning DEV agent through `@tech-lead`, who re-reviews the fix.
7. **Tech Lead reports → PM gate.** `@tech-lead` consolidates the technical outcome (what shipped, review verdict, test/security status, anything deliberately not built) and hands it to `@project-manager`. PM validates delivery against BA's spec and each task's Requirement/Goal, re-consulting `@business-analyst` on requirement disputes, then either certifies DONE to the stakeholder or routes it back per the loop rule.

**Division of judgement — PM owns delivery, `@tech-lead` owns the build.** PM: is this what was asked for, is every case covered, is the scope right, is it on track, is the stakeholder-facing claim honest. Tech Lead: how it is built, who builds it, and whether the code is minimal, maintainable and inheritable — judged by reading the diff, not the self-report. PM does not overrule a technical verdict and does not review code; Tech Lead does not renegotiate the requirement (that goes to `@business-analyst`) or unilaterally change scope/priority (that goes to PM). Two verdicts sit outside both lanes and cannot be overridden by PM or Tech Lead: `@security-engineer`'s pre-implementation task validation (step 4) blocks assignment, and BA's performance gate can only be relaxed by the stakeholder via PM with the reason written into the spec.

**Task assignment contract** — a breakdown table is not enough on its own; every task `@tech-lead` hands to a DEV agent must include:

```md
### Task <n>: <title>
- Owner: @agent
- Requirement: what must be true in the product (functional + non-functional), citing BA's spec section / requirements doc / ADR
- Goal / Definition of Done: observable, checkable criteria Tech Lead will review against
- Acceptance criteria: the happy + edge + negative cases from BA that this task must satisfy
- Performance budget: the applicable gate(s) from BA's spec (page end-to-end / internal API /
  public API, cold-cache and warm) **+ the percentile this surface is judged at — page = P95 of the
  whole page (LCP/INP too); a request inside a page's N-way parallel fan-out = P99 (N ≥ 5) or
  P99.9 (N ≥ 50)** + SA's fan-out design (N, what reduced it, cache keys/TTL/invalidation, payload
  shape, batching) + @database-expert's query/index plan when the task touches the DB (query plan,
  index used/added, N+1 removal, bounded result set, expected DB-hop share of the budget) —
  "n/a" only with a stated reason
- Security requirements: authz (role/permission code), tenant isolation on every data path, input
  validation + output encoding, PII/secret handling + encryption, audit events, rate limiting/CORS —
  taken from BA's spec and SA's design; each one is a checkable statement, not "follow best practice"
- Security validation: @security-engineer verdict on this task (PASS / PASS with required changes /
  FAIL) + the required changes — the task is not assignable to a DEV agent without a PASS
- Technical approach: the intended solution shape + existing code to reuse (keeps the diff minimal)
- Out of scope for this task: what NOT to build (explicit anti-"bánh vẽ" boundary)
- Depends on: <task ids or "none"> | Priority: P0/P1/P2 (from PM)
```

**BA case set (owned by `@business-analyst`, mandatory and complete):** before any implementation task is assigned, BA enumerates:
- **Happy path(s)** — the intended flow(s) end to end.
- **Edge cases** — empty/null/boundary inputs, max lengths, concurrent/duplicate submissions, expired or revoked tokens/licenses, cross-tenant access attempts, permission-denied paths, network/service failures, partial failures and rollback, i18n content missing for a locale, timezone/date boundaries.
- **Negative cases** — invalid input, unauthorized role, tampered payload.

That case list is the contract `@tech-lead` breaks down against, `@qa-engineer` tests against, and `@project-manager` validates against. A case set that only covers the happy path is an incomplete BA deliverable — send it back to `@business-analyst`. When implementation reveals a case BA missed, it goes back to BA to be added to the spec, not patched silently in code or tests.

### Performance gate (stated by `@business-analyst`, designed by `@systems-architect` + `@database-expert`, measured by `@qa-engineer`)

Performance is a requirement, not a tuning phase after release. `@business-analyst` states the applicable budget in the requirement spec for **every screen and every endpoint the change touches** — a spec without a performance gate is incomplete and goes back to BA, exactly like a missing negative case.

**Mandatory platform budgets (unless the stakeholder approves a documented exception):**

| Surface | Budget | Percentile it is judged at | Measured how |
|---------|--------|----------------------------|--------------|
| **Page — end-to-end load, cold cache** (first hit, empty L1/L2, no warm connection pool) | **< 500 ms** | **P95 of the whole page**, i.e. of the *last* request that gates the render — never the mean of per-request P95s | Navigation start → last render-blocking response (Blazor render + all parallel API/asset calls the screen needs), cache flushed before the run |
| **Page — user-centric metrics** (same run) | **LCP ≤ 500 ms cold** / **INP < 200 ms** | P95 across runs | Browser-measured (`PerformanceObserver` via Playwright), not server-side timers |
| Page load — warm cache | ≤ the cold-cache budget (never regresses) | P95 of the whole page | Same screen, second run |
| Component request inside a page's parallel fan-out (API, asset, script) | its own budget below | **P99 (≥ 5 parallel) / P99.9 (≥ 50 parallel)** — see the amplification table | Per-request timing at the gateway + browser waterfall |
| Internal API (service-to-service, behind Kong internal routes, S2S calls) | **< 50 ms** | P95 standalone; **P99+** when it is one of a page's parallel calls | Endpoint response time excluding client network, cold + warm |
| Public API (client/SDK/browser-facing, through Kong edge) | **< 200 ms** | P95 standalone; **P99+** when it is one of a page's parallel calls | Endpoint response time at the gateway, cold + warm |

#### Tail latency amplification — why per-request P95 is not the page's P95

A page is finished only when its **slowest** request finishes. If a screen needs N parallel requests and each independently meets its budget 95% of the time, the probability that *all* N are fast is `0.95^N` — with N = 10 that is ≈ 0.598, so **≈ 40% of page loads are slow even though every single endpoint "passes P95"**. Per-request P95 measures the endpoint; it does not measure what the user waits for. Three consequences, all binding:

1. **The page budget is judged at the page level.** BA states, and QA measures, the P95 of the *end-to-end* page time (navigation start → last render-blocking response) plus LCP/INP. Summing, averaging or "all endpoints passed P95" is **not** evidence the page passed — that claim is rejected at the gate.
2. **Component requests in a parallel fan-out are held to a higher percentile.** To hold a page-level 95% target with N parallel requests, each must succeed at `0.95^(1/N)`:

| Parallel requests N on the screen | Required per-request pass rate `0.95^(1/N)` | Percentile to measure at (rounded up) |
|---|---|---|
| 2 | 0.9747 | P97.5 |
| 3 | 0.9830 | P98.5 |
| 5 | 0.9898 | **P99** |
| 10 | 0.9949 | **P99.5** |
| 20 | 0.9974 | P99.75 |
| 50 | 0.9990 | **P99.9** |

(For an N not in the table, compute `0.95^(1/N)` and round the percentile up. The same arithmetic applies to a stricter page target: a 99% page target with N = 10 needs `0.99^(1/10)` ≈ 0.999 per request.)

`@systems-architect` states **N per surface** in the performance design (count the requests the screen must complete before it is usable — API calls, scripts, fonts, images above the fold), derives the per-request percentile from this table, and writes it into each task's Performance budget. `@qa-engineer` measures at that percentile — a component request reported only at P95 when N ≥ 5 is an unmeasured surface, routed back like a FAIL.

3. **Reducing N is the first lever, not the last.** Before tightening a percentile, SA must show what was done to shrink the fan-out: batch/aggregate endpoints (one composite call instead of six), server-render data the page already has, defer non-blocking calls out of the critical path (lazy-load below the fold), bundle assets, cache at the edge. A design that meets the budget by demanding P99.9 from 30 parallel calls is a worse design than one that needs 4 calls at P99 — Tech Lead sends the former back.

Rules that go with the numbers:

- **Cold cache is the number that counts.** A budget met only with a warm L1/L2 cache is not met. BA states the cold-cache expectation explicitly; QA flushes cache (and reports how) before measuring.
- **SA must participate in the design.** Whenever the gate is in play, `@systems-architect` designs the path to it *before* implementation — cache layer + key shape + TTL + invalidation trigger, **the fan-out count N for the screen and the per-request percentile it forces (amplification table above), plus what was done to reduce N**, payload size and shape, batching/parallel fan-out, and what happens on cache miss and on dependency slowness. That design is attached to the task's **Performance budget** field. Tech Lead does not invent the performance approach alone, and a DEV agent does not improvise it.
- **`@database-expert` must participate whenever the change touches the database.** Any new or modified query, join, filter, sort, pagination, aggregate, migration, or query against a table expected to grow goes through `@database-expert` *before* implementation. DB expert owns and states: the query plan (`EXPLAIN ANALYZE` on representative data volume), the index strategy (existing index reused / new index + which columns + why, incl. the `tenant_id` leading column for tenant-scoped queries), N+1 removal, bounded result set (pagination/limit, no `SELECT *` on wide tables), and the expected DB-hop share of the surface's budget **at the percentile that surface is judged at — a query feeding one of N parallel page calls is planned against its P99/P99.9 time (worst-case plan, cold buffer cache, lock/contention), not its average**. That statement is attached to the same task's **Performance budget** field alongside SA's design. A DB-touching task assigned without DB expert's query/index plan is a process FAIL — `@backend-developer` does not invent the index strategy alone.
- **Budget per task, not per release.** Each task carries the budget for the surface it touches, so the owner is unambiguous.
- **QA measures the page, not just the endpoints, and it is a gate.** `@qa-engineer` verifies on the real running app (cold + warm) and reports: **page-level P95 end-to-end** (navigation start → last render-blocking response), **LCP and INP at P95** from the browser, the **fan-out N actually observed** in the network waterfall, and each parallel component request **at the percentile the table demands** (P99/P99.9 — which needs enough runs to be meaningful: ≥ 100 samples for P99, ≥ 1000 for P99.9, via NBomber or repeated Playwright runs; state the sample count). Per-request P95 alone, or "every endpoint passed", is not evidence the page passed. Every reported number carries its measurement window, instrument and sample count (rule **E1**), and a percentile QA could not sample enough runs for is reported as **not measured** (rule **E4**), never rounded up from P95. A miss is a FAIL routed back through `@tech-lead` to the owning DEV agent — not a "known slowness" note.
- **Exceptions are stakeholder decisions.** A surface that genuinely cannot meet the budget (heavy export, bulk import, third-party dependency latency) needs an explicit budget stated by BA with the reason, approved by the stakeholder via `@project-manager`, and recorded in the spec. Silent acceptance of a miss is a process FAIL.

### Security-in-task rule (`@security-engineer` validates before implementation)

Security requirements are written into the tasks and validated **before** code is written, not discovered in the post-implementation review.

- **BA writes the security requirements** into the spec per requirement: who may do this (role + permission code), tenant isolation on every data path, what input is validated and how, what is encoded on output, which fields are PII/secret and how they are protected, what must be audited, retention and compliance obligations (GDPR/CCPA/PDPL), and the negative/abuse cases (cross-tenant access, unauthorized role, tampered payload) that already belong to the case set.
- **SA carries them into the design**; `@tech-lead` copies them into each task's **Security requirements** field — concrete and checkable, never "follow security best practice".
- **`@security-engineer` validates each task before assignment** (step 4): PASS / PASS with required changes / FAIL. FAIL or unaddressed required changes block assignment to the DEV agent. The verdict is recorded in the task's **Security validation** field with a date/ref, so `@tech-lead` and `@project-manager` can see it.
- **The pre-implementation validation does not replace the post-implementation assessment.** Step 6's OWASP 10/10 pass still runs on the actual diff, and it explicitly re-checks that every step-4 required change was implemented.
- **New requirement or changed approach → re-validate.** Any change to the technical approach, data model, endpoint surface or permission model after a PASS sends the task back through `@security-engineer` before implementation continues.

Parallelizable once the relevant contract is fixed: DB schema + Backend logic; Backend + Frontend (once API contract is set); QA + post-implementation Security review (findings still must be fixed pre-release); post-QA UI/UX review + Docs update. **Not parallelizable:** the step-4 pre-implementation security validation — it blocks task assignment.

No role may be silently skipped. If a task genuinely only touches one role's surface, say so explicitly rather than skipping without comment.

**Rule #0 applies to every step of this pipeline:** no agent commits, pushes, stashes or reverts. The pipeline ends with an uncommitted working tree plus PM's validation report — the human reviews and decides what gets committed.

### Minimal implementation rule (every DEV agent)

Build **exactly** what the requirement asks — nothing more. The requirement is the ceiling, not a starting point.

- **No speculative work ("bánh vẽ").** No feature flags, extension points, config knobs, abstract base classes, interfaces with one implementation, generic "framework" layers, event hooks, or "we'll need this later" parameters that no case in BA's set requires. YAGNI applies even when the extra code is cheap to write.
- **No over-engineering.** Prefer the straightforward implementation: fewest moving parts, fewest new files, fewest new abstractions. Reuse what the codebase already has (existing service, helper, cache pattern, base controller, `--dp-*` token, localization resource) instead of introducing a parallel mechanism. A new pattern must be justified against the requirement, not against taste.
- **Scope-locked diff.** Do not refactor, rename, reformat, or "clean up" code unrelated to the task, and do not silently expand scope into adjacent features. If you find an unrelated defect, report it to `@tech-lead` (who routes it to PM's backlog) — don't fix it inside this diff.
- **Simple ≠ sloppy.** Minimal means no surplus code, not skipped tenant filtering, missing validation, absent error handling, absent tests, or copy-pasted duplication. Non-functional requirements (security, tenant isolation, audit, **performance budget**, i18n, a11y) are part of the requirement and are never trimmed as "extra" — the task's Security requirements and Performance budget fields are mandatory work, and a DEV agent that cannot meet the budget with the assigned design escalates to `@tech-lead` (who re-consults `@systems-architect`) instead of shipping a miss.
- **Ask, don't assume.** If you're weighing "minimal now vs. flexible later" or any technical judgement call, ask `@tech-lead` — that is who assigned you the task. If the *requirement* itself is ambiguous or a case in BA's set is unclear, Tech Lead routes it to `@business-analyst`. Guessing and building the bigger version is the failure mode this rule exists to prevent.
- **Report what you did NOT build.** Every DEV implementation summary (delivered to `@tech-lead`) lists the abstractions/extras deliberately skipped and any question raised, so the minimality is reviewable rather than assumed.

`@tech-lead` enforces this rule by reading the diff — checklists in `.claude/agents/tech-lead.md`.

### Loop rule — iterate until clean, not until it passes once

- Work started without the step-0 Prior-Art Inventory, or against an inventory whose "does not exist" was never queried → back to the owning agent to run the graph queries first; anything already built on that assumption is re-checked against what the graph actually returns.
- `@tech-lead` review FAIL → back to the owning DEV agent → re-review by `@tech-lead` → only then `@qa-engineer`.
- QA FAIL → back to `@backend-developer`/`@frontend-developer` → re-test by `@qa-engineer`.
- Case reported PASS on script/unit evidence when it could have been driven in the browser, or without the browser-path-impossible reason stated → treated as untested → back to `@qa-engineer` to run it with Playwright.
- Post-QA UI/UX review FAIL → back to `@frontend-developer` → re-test by `@qa-engineer` → re-review by `@ui-ux-designer`.
- `@security-engineer` pre-implementation validation FAIL (step 4) → back to `@tech-lead` to fix the task design (and to `@business-analyst` if the gap is in the requirement) → re-validate by `@security-engineer` → only then assign to the DEV agent.
- Security finds a vulnerability → back to the owning DEV agent → re-review by `@security-engineer`.
- Performance gate missed (QA-measured P95 over budget, cold or warm) → back to `@tech-lead` → `@systems-architect` revises the performance design (**and `@database-expert` revises the query/index plan when the slow hop is the database — slow query, missing/unused index, N+1, unbounded result set**) → owning DEV agent implements → `@qa-engineer` re-measures. A miss is never closed by relaxing the budget without stakeholder approval via `@project-manager`.
- DB schema issue → fix migration → `@backend-developer` updates dependent code → `@qa-engineer` retests.
- Repeat until PASS with zero open security issues — one green run does not close the loop if a later step reopens a question.
- `git stash`/discard-and-redo to dodge a failing test or review is forbidden; find the root cause and fix it.
- `@tech-lead` finds over-engineering / unrequested code / maintainability problems → back to the owning DEV agent to strip it down → `@tech-lead` re-review → `@qa-engineer` re-runs the suite after the removal.
- Requirement gap or newly discovered case → back to `@business-analyst` to update the spec → `@tech-lead` re-assigns the implementation task → `@qa-engineer` covers the new case.
- **Business logic outranks test setup**: if a test's assumptions conflict with the actual business requirement, fix the test (or escalate the ambiguity to the user) — never weaken or delete a test to make incorrect logic pass. `@business-analyst` (requirement owner) and `@qa-engineer` (test design) are jointly responsible for enforcing this, with `@project-manager` arbitrating; a green suite that checks the wrong behavior is a FAIL.

### Evidence rules — how a claim earns the right to drive work

Every rule below exists because it was violated on this codebase and cost a review cycle or shipped a silent defect. They bind every agent, main thread included.

**E1 — A number that drives a task assignment must carry its provenance, and be reproduced by someone other than its author.**
State the measurement window, the instrument, and what was excluded. A count taken over a whole page-load window is not a per-component count; an aggregate p50 across a service is not the latency of one endpoint; a figure that already includes a fan-out must not be multiplied by that fan-out again. Before a number becomes a task's justification, one other agent reproduces it independently — different window, different instrument if possible. *(Cost when skipped: three task assignments issued against a call count that was double-multiplied, then attributed to components that a live capture showed make zero network calls on that route.)*

**E2 — A criterion that references a field must cite the line that ASSIGNS the value, not the line that reads it.**
"Cache only when `Source != "System"`" was written against a real field, on a real path, and would have cached exactly zero verdicts — because nothing on that path ever assigns `Source`, and the sibling path only worked because it stamped the value itself. Build and suite stayed green. Whenever an acceptance criterion depends on a field's *value*, trace it to its producer and cite that line; if the producer is in another service, say so explicitly and see E5.

**E3 — A regression test must be shown to fail against the pre-fix code.**
Run it against the unfixed implementation, record the failure output, then restore and verify the file is byte-identical (checksum, and grep for probe residue). A test that passes both before and after is not a guard, and "it exercises the code" is not evidence. When a discriminating test genuinely cannot be written cheaply — a race needing a controlled scheduler, for example — **report no coverage rather than shipping a green test that does not test the thing**, and say what the compensating control is.

**E4 — Report what you could not verify. Never substitute a proxy check and present it as verification.**
"The CSS print rules are all present" is not "the document renders correctly" — the rules were present and the tables still shattered one character per line. "The code returns the right value" is not "zero HTTP calls were issued". If a check needs a tool you do not have — a browser, a running stack, cluster access, a credential — say so plainly, name the check, and hand it to whoever can run it. An honest gap is routed in one turn; a proxy check is discovered three rounds later.

**E5 — A contract between two parallel agents must specify both ends of every lifecycle, and every cross-service invariant it depends on.**
A frozen contract that defined the signal for the *start* of a navigation and never defined one for its *end* left the consumer waiting forever, with both halves' tests green. Likewise, when one service's code depends on a field another service owns, that invariant needs a test **where the value is produced** — otherwise a future change there degrades this side silently, with green suites on both sides and no error anywhere.

**E6 — Correct the record when a measurement turns out to be wrong.**
Withdraw the number, say which claim it invalidated, and re-measure before drawing anything new from it. Overstated confidence in a diagnostic is worse than no diagnostic: the whole point of an instrument is that someone will act on what it says.

**E7 — "It does not exist yet" is a claim, and the graph is how it is evidenced.**
Never build, spec or document something on the assumption that nothing like it is there. Before an agent proposes a new endpoint, screen, table, ADR or document, it queries the graph (root + the relevant shard, `docs/graphify-out` for anything document-shaped) and cites what it found — including the near-misses it decided not to reuse and why. A truncated result is not a negative result. A "new" artifact delivered without that citation is treated as unverified and routed back, exactly like a missing measurement — duplicated features and rival documents are more expensive to unpick later than the query is to run now.

### PM gate — the only valid path to "done"

`@project-manager` is the only role allowed to report a task or feature complete to the stakeholder, and only when:
1. Every agent in the pipeline has reported back PASS — `@tech-lead` code review PASS (minimality + maintainability), 100% test pass rate from `@qa-engineer` (including UI, mockup-conformance and i18n checks), **every UI-reachable case executed in a browser with Playwright — script-only cases each carry a stated reason**, **QA-measured performance gate met on every touched surface — page-level P95 end-to-end + LCP/INP, and every parallel component at its required P99/P99.9 (cold cache + warm)**, **`@security-engineer` pre-implementation validation PASS recorded for every task**, zero open findings from `@security-engineer`'s post-implementation assessment, post-QA UI/UX PASS for any UI-touching change.
2. PM has personally reviewed the delivered outcome against BA's spec (not just trusted the self-reported status): every requirement and every case in BA's set is either covered by a test or carries an explicit documented decision, each task's **Requirement** and **Goal** is demonstrably met, and no business-logic or security question is left open. PM reads deliverables and reports at requirement level — code craft is `@tech-lead`'s verdict (item 3).
3. **Minimality and maintainability carry a `@tech-lead` PASS.** PM does not judge code craft — Tech Lead reads the diff and rules on speculative abstraction, unrequested code, duplication, convention drift and inheritability (checklists in `.claude/agents/tech-lead.md`). PM records that verdict, and a Tech Lead FAIL blocks the gate exactly like a failing test. What PM *does* check at requirement level: that the delivered scope matches what was assigned — no feature nobody asked for, no task quietly dropped. Suspected over-engineering that Tech Lead did not flag goes back to `@tech-lead` as a question, not decided by PM.
4. PM explicitly certifies three things before reporting up: **no known vulnerability** (pre-implementation validation PASS on every task + post-implementation security report clean), **no known logic defect** (every acceptance criterion demonstrably met), and **no unmet performance budget** (QA's measured *page-level* numbers are inside BA's gate — not a set of per-request P95s — or the miss carries a written stakeholder-approved exception). If PM cannot verify a claim, it is not verified — route it back.
5. Any FAIL or partial result re-enters the loop above — PM routes it back to the right agent instead of reporting a "mostly done" result upward.
6. **The evidence rules (E1–E7) hold for every claim in the pack.** PM checks the shape, not the craft: does each number carry its measurement window and an independent reproduction (E1); was every regression test shown to fail against the pre-fix code (E3); is every "verified" actually verified rather than proxied, with the gaps named (E4). A claim that cannot show its evidence is treated as unverified and routed back, exactly like a FAIL.
7. **The Prior-Art Inventory (step 0) is in the pack and was actually used.** PM checks that what shipped is a delta against what already existed — not a second implementation of a feature the graph already listed, not a rival document beside an existing one, not an ADR that contradicts the one already governing the area. A delivery whose inventory is missing, or whose "this did not exist" was never queried, is routed back before it reaches the stakeholder.

Reporting "done" before this gate is satisfied is a process failure on its own, independent of whether the code happens to be fine — the gate exists to catch problems before the stakeholder sees them.

### Roles and standards baseline

| Role | Agent | Model | Primary function |
|------|-------|-------|-------------------|
| PM | `@project-manager` | sonnet | Preliminary intake, scope, priority, timeline, backlog, final validation gate to the stakeholder |
| BA | `@business-analyst` | sonnet | Requirement specification, user stories, business rules, complete happy/edge/negative case set, acceptance criteria, **performance gate + security requirements per requirement** |
| SA | `@systems-architect` | opus | Architecture design, ADR, caching strategy, **performance design to meet BA's gate (cache/index/payload/cold-cache path)** |
| Tech Lead | `@tech-lead` | opus | Technical breakdown, task assignment to DEV, code review (minimality/maintainability), technical arbitration, consolidated report to PM |
| UI/UX | `@ui-ux-designer` | sonnet | UX flow, component spec, post-QA review |
| DEV (Backend) | `@backend-developer` | sonnet | .NET 9 API, business logic, Redis, RabbitMQ |
| DEV (Frontend) | `@frontend-developer` | sonnet | Blazor Server, Razor components, localization |
| DEV (Database) | `@database-expert` | sonnet | PostgreSQL schema, EF Core migrations, indexing, **query/index plan for BA's performance gate on every DB-touching change (with SA)** |
| QA | `@qa-engineer` | sonnet | **Browser-first verification of BA's case set with Playwright** (`webapp-testing` skill / Playwright MCP), Playwright E2E, xUnit tests, NBomber perf, **performance-gate measurement (page-level P95 end-to-end + LCP/INP, fan-out components at P99/P99.9, cold + warm)** |
| Security | `@security-engineer` | sonnet | **Pre-implementation task validation**, OWASP, ISO 27001, GDPR/CCPA/PDPL compliance |
| DevOps (CI/CD) | `@cicd-engineer` | sonnet | GitHub Actions, pipeline, security scanning |
| DevOps (Docker) | `@docker-expert` | sonnet | Docker Compose, Kubernetes, container ops |
| Docs | `@documentation-writer` | haiku | User guides, API docs, operational docs |

**Three owners, three lanes — do not blur them.** `@project-manager` does the *sơ bộ* pass (routing, scope, priority, sequencing, delivery risk) and owns the final stakeholder gate. `@business-analyst` owns the requirement: what the system must do, the business rules, the acceptance criteria, the full case set. `@tech-lead` owns the build: technical approach, task breakdown and assignment to DEV agents, code review, and the consolidated technical report to PM. When PM and BA disagree on what a requirement means, BA's spec wins or it goes back to the stakeholder — PM does not overwrite a requirement to keep a schedule, and does not overrule Tech Lead on code.

Standards every role designs/reviews against: ISO 27001 (security controls), NIST CSF, OWASP Top 10 (2021), ISO/IEC/IEEE 29148 (requirements quality), IEEE 1016 (design description), WCAG 2.2 + ISO 9241 (usability/accessibility).

### Documentation each role must read before starting

| Agent | Required docs |
|-------|---------------|
| `@business-analyst` | `docs/requirements/requirements.html`, `docs/compliance/compliance.html` (PDPL), GDPR/CCPA obligations in the security docs, relevant ADRs, existing user guides, Caching Architecture + the Performance gate section above (to state realistic budgets), Auth doc (to state the security requirements per requirement) |
| `@tech-lead` | BA's requirement spec + case set, CLAUDE.md (Code Conventions, minimal implementation rule, CSS tokens), relevant ADRs, Solution Architecture, API Docs, the surrounding source of the change |
| `@systems-architect` | Solution Architecture, Caching Architecture, Database Design, API Docs, Auth |
| `@database-expert` | Database Design, Solution Architecture, Caching Architecture, migration-guideline.md, BA's performance gate + SA's performance design for the change |
| `@backend-developer` | API Docs, Database Design, Caching Architecture, Auth, dotnetcore-microservices-instructions.md |
| `@frontend-developer` | API Docs, Auth, blazor-frontend-instructions.md, localization files |
| `@ui-ux-designer` | API Docs, Auth, User Guides, `docs/requirements/requirements.html`, `variables.css`, `docs/design/design.html#design-designsystem` |
| `@qa-engineer` | API Docs, Auth, `docs/requirements/requirements.html` (acceptance criteria), `@business-analyst`'s requirement spec + case set, `@ui-ux-designer`'s mockup/component spec, localization resource files (vi/en/id), the `webapp-testing` skill (Playwright browser driving — the default instrument) |
| `@security-engineer` | Auth, Solution Architecture, Database Design, API Docs, security-compliance-instructions.md, permission-system-instructions.md, `@business-analyst`'s spec + `@tech-lead`'s task pack (for the step-4 pre-implementation validation) |
| `@cicd-engineer` | cicd-instructions.md, Docker Compose files, Solution Architecture |
| `@docker-expert` | Docker Compose files, Solution Architecture, Docker Cleanup Guide, K8s manifests |
| `@documentation-writer` | User Guides, API Docs, Solution Architecture |

**Every role's first read is the graph, not the file tree** (Rule #1): query `docs/graphify-out/graph.json` to find which of these documents actually covers the change — the doc index above names the areas, the graph names the `file:line`. Role → shard it must query before starting: BA / PM / SA / Docs → `docs/graphify-out` (+ root); Tech Lead / Backend / Security / QA → root (+ `docs/graphify-out` for the governing ADR); Frontend / UI-UX → `shards/frontend`; `@database-expert` → `shards/data-model`. A role that opens files cold has skipped its own discovery pass.

### Handoff contract

Every handoff between agents must state: scope being handled, files/modules touched, assumptions locked in, open blockers/risks, what was delivered, and the next owner + next action. Minimal reusable template:

```md
## Handoff
- From: @source-agent
- To: @target-agent
- Scope: [feature or change set]
- Relevant files: [paths]
- Prior art (graphify): queries run + what already exists / partially exists, with file:line — and what was confirmed absent
- Inputs confirmed: [requirements, contracts, constraints]
- Outputs delivered: [summary]
- Risks / blockers: [if any]
- Next action: [what target agent must do next]
```

Cross-reference map for who hands off to whom:

```
stakeholder request→ graphify discovery→ @project-manager (BLOCKING — no intake without the Prior-Art Inventory)
@project-manager   → sơ bộ intake     → @business-analyst
@business-analyst  → requirement spec → @tech-lead (+ @systems-architect when an ADR is needed
                     (incl. perf gate     or the performance gate is in play)
                      + security reqs)
@business-analyst  → case set         → @qa-engineer
@business-analyst  → perf gate        → @qa-engineer (measures it) + @systems-architect (designs for it)
                                        + @database-expert (query/index plan on DB-touching change)
@systems-architect → architecture/ADR → @tech-lead
@systems-architect → perf design      → @tech-lead (attached to each task's Performance budget)
@database-expert   → query/index plan → @tech-lead + @systems-architect (same Performance budget field)
@tech-lead         → task pack        → @security-engineer (pre-implementation validation, BLOCKING)
@security-engineer → task PASS        → @tech-lead (then assignment may proceed)
@security-engineer → task FAIL        → @tech-lead (→ @business-analyst if the gap is in the spec)
@tech-lead         → task assignment  → @database-expert / @backend-developer / @frontend-developer
@ui-ux-designer    → component spec   → @frontend-developer
@database-expert   → migration done   → @backend-developer
@backend-developer → API available    → @frontend-developer
@backend-developer → impl complete    → @tech-lead (code review)
@frontend-developer→ impl complete    → @tech-lead (code review)
@tech-lead         → review FAIL      → owning DEV agent (re-review after fix)
@tech-lead         → review PASS      → @qa-engineer
@qa-engineer       → bug found        → @tech-lead → owning DEV agent
@qa-engineer       → perf gate miss   → @tech-lead → @systems-architect (+ @database-expert if the
                                        slow hop is the DB) → owning DEV agent
@security-engineer → vuln found       → @tech-lead → owning DEV agent
@qa-engineer       → PASS             → @ui-ux-designer (post-QA review)
@ui-ux-designer    → review PASS      → @tech-lead (consolidated report)
@tech-lead         → technical report → @project-manager (gate)
@project-manager   → gate PASS        → @documentation-writer
@cicd-engineer     → build failure    → @tech-lead → owning DEV agent
@docker-expert     → container issue  → @tech-lead / @cicd-engineer
```

### Per-role output formats (use these shapes when reporting back)

**PM (preliminary intake — step 1, sơ bộ only):**
```
## Preliminary Assessment
- Prior-Art Inventory (step 0, graphify): what already exists / partially exists / is absent, with file:line + the ADR or spec already governing this area
- Request restated / Product area & services touched / Roles needed / Known risks & dependencies
- Priority + delivery constraints (what PM owns)
- Clarity: clear | needs stakeholder clarification (list the questions)
- Next: hand to @business-analyst for the requirement spec
```

**BA (requirement spec — step 2):**
```
## Requirement Specification
- Context & business objective / Stakeholders / Requirements ref (docs/requirements/…, ADR)
- User stories: As a <role>, I want <capability>, so that <value>
- Functional requirements (numbered, testable) / Non-functional (perf, security, compliance, i18n, a11y)
- Business rules & constraints (incl. GDPR/CCPA/PDPL obligations)

## Performance Gate (mandatory — SA designs for it, QA measures it)
| Surface (screen / endpoint) | Type (page / internal API / public API / component of a fan-out) |
| Budget | Percentile judged at (page = P95 end-to-end; component of N parallel = P99/P99.9) |
| Cold-cache budget | User-centric metric where it applies (LCP / INP) | Notes / exception + who approved |
- Default budgets: page end-to-end cold-cache < 500 ms (P95 of the whole page, LCP ≤ 500 ms,
  INP < 200 ms) | internal API < 50 ms | public API < 200 ms
- A page budget is stated at page level, never as "each request < X at P95" — tail latency
  amplification (see the gate section) makes per-request P95 unrepresentative of the user
- Any deviation needs a stated reason + stakeholder approval via @project-manager

## Security Requirements (per requirement — @security-engineer validates the tasks against this)
| Req # | Authz (role + permission code) | Tenant isolation on data path | Input validation / output encoding | PII & secrets (encryption, masking, logging) | Audit event | Compliance ref (GDPR/CCPA/PDPL) |

## Case Set (complete — QA tests against this)
| # | Type (happy/edge/negative) | Precondition | Action | Expected result |

## Acceptance Criteria
- Given/When/Then per requirement
## Out of scope
- What this change explicitly does not cover
```

**Tech Lead (technical breakdown / assignment — step 3):**
```
## Technical Breakdown
- BA spec ref / Architecture decision or ADR applied / SA performance design ref / Existing code to reuse

| # | Task | Agent | Perf budget | Security validation | Depends on | Priority |
+ one Task assignment block per row (Requirement + Goal + acceptance criteria from BA's
  case set + performance budget & SA design + security requirements + @security-engineer
  verdict + technical approach + explicit out-of-scope boundary)
- Not assignable to a DEV agent until @security-engineer returns PASS on the task (step 4)
```

**Security (pre-implementation task validation — step 4, BEFORE any DEV agent starts):**
```
## Pre-Implementation Security Validation — <feature>
| Task | Authz specified | Tenant isolation on every data path | Input validation / output encoding |
| PII & secrets handling | Audit events | Rate limit / CORS | Design-level vuln risk (IDOR/injection/
  SSRF/mass assignment/missing authz) | Verdict (PASS / PASS with required changes / FAIL) |
## Required changes per task — what must be added to the task before assignment (concrete, checkable)
## Requirement gaps → @business-analyst — security requirements missing from BA's spec
## Verdict: tasks cleared for assignment: <list> | blocked: <list + reason>
```

**Tech Lead (code review — step 5, and after every fix):**
```
# Code Review — <task / feature>
- Diff reviewed (files, +/-) | Build PASS/FAIL | Touched tests PASS/FAIL
## Verdict — Minimality: PASS/FAIL | Maintainability & inheritability: PASS/FAIL |
   Security requirements implemented: PASS/FAIL | Performance design implemented: PASS/FAIL | Overall
## Findings — | # | Severity (BLOCKER/MAJOR/MINOR) | file:line | Finding | Required change |
## Requirement traceability — hunks with no requirement / requirements with no code
```

**Tech Lead (consolidated report — step 7, to PM):**
```
## Technical Delivery Report
- Tasks delivered (per DEV agent) / Files touched / Review verdict per task
- QA: <n>/<n> PASS | Performance gate: measured vs budget per surface — page-level P95 end-to-end
  + LCP/INP (cold + warm), and each fan-out component at its required P99/P99.9 — PASS/FAIL
- Security: pre-implementation validation PASS per task (+ required changes built)
  | post-implementation findings <counts by severity, 0 open Critical/High>
- Deliberately NOT built (anti-bánh-vẽ log) + questions raised and how they were resolved
- Known technical debt / follow-ups for PM's backlog
- Verdict: ready for PM gate | blocked by <what>
```

**PM (validation / gate — step 7):**
```
## Validation Report
| Task | Agent | Requirement met? | Goal met? | Perf budget met? | Evidence reviewed |
- Cases covered: <n>/<n> from BA's case set (list gaps)
- Minimality & maintainability: PASS/FAIL (unrequested code, speculative abstraction,
  duplication, scope creep, convention drift — cite file:line for each)
- Performance gate: page-level P95 end-to-end + LCP/INP vs budget (cold + warm), fan-out components
  at their required percentile — met / missed / not measured (+ approved exception ref).
  "Every endpoint passed P95" is not a page PASS — route it back.
- Security: pre-implementation validation PASS on every task + 0 open findings
  (Critical/High/Medium/Low counts)
- Logic defects: none known / list
- Verdict: DONE | BACK TO @agent for <reason>
```

**SA:**
```
## Architecture Decision
- Context / Options (2-3 with trade-offs) / Decision + rationale / Consequences

## Technical Design
- Architecture diagram (C4/Mermaid), DB schema spec → @database-expert,
  API contract → @backend-developer, cache strategy, inter-service comms pattern

## Performance Design (mandatory when BA's performance gate is in play)
| Surface | Budget + percentile (from BA) | Cold-cache path | Cache layer/key/TTL/invalidation trigger |
| Payload shape & size | Batching / parallel fan-out |
| Expected latency by hop (DB / cache / service call / render) at the judged percentile |
| Risk if a dependency is slow |

## Fan-out & tail latency (mandatory for every page surface)
| Screen | N = requests that must complete before the page is usable (list them) |
| Per-request percentile forced by N (amplification table) | What was done to reduce N
  (composite endpoint, server-side render, deferred/lazy calls, bundled assets, edge cache) |
| N before → N after | Slowest expected request at that percentile vs the page budget |
- A design that meets the budget only by demanding P99.9 from a large fan-out is sent back:
  cut N first, tighten percentiles second
- DB hop → co-designed with @database-expert (its query/index plan is attached, not restated here)
- Verdict: budget achievable as designed | achievable only with <trade-off> | not achievable →
  back to @business-analyst + @project-manager for an explicit exception
```

**UI/UX (design delivery — `@frontend-developer` may refuse handoff if incomplete):**
```
1. Component spec (states: loading/empty/error/success/disabled, interactions)
2. CSS token mapping table (element/property/light token/dark token) + any new tokens proposed
3. UX flow: happy path + edge cases
4. Responsive behavior at ≥1280px / 768-1279px / 320-767px
5. Localization keys table (en/vi/id)
6. Accessibility: contrast ratios, keyboard nav order, ARIA labels, focus states
```
Post-QA review output: PASS/FAIL + issue list, CSS-token compliance, dark-mode rendering check.

**Backend:** `## Implementation Summary` — service, endpoints touched, tenant_id filtering confirmed, cache keys + invalidation strategy, test count/coverage, **Security requirements from the task implemented (one line per item, incl. the step-4 required changes)**, **Performance: budget per endpoint + what was done to meet it (following SA's design and `@database-expert`'s query/index plan — deviations flagged to `@tech-lead`, not decided alone) + own measured/expected P95 cold vs warm**, **Minimality: what was deliberately NOT built + existing code reused + questions raised with PM**.

**Frontend:** `## Implementation Summary` — pages/components touched, localization keys added per language, states handled, breakpoints tested, WCAG checks passed, **Security requirements from the task implemented (authz on the page/component, no PII leaked to the client, output encoding)**, **Performance: page-load budget + the fan-out N this screen actually issues (list the blocking requests) + what was done to keep N low and meet the budget (composite call, server-side render, deferred/lazy below-the-fold calls, bundled assets, cache) + own measured cold end-to-end timing and LCP**, **Minimality: what was deliberately NOT built + existing components/tokens reused + questions raised with PM**.

**Database (query/index plan — step 3, delivered with SA's performance design, BEFORE implementation):**
```
## Query & Index Plan — <feature>
| # | Query / operation | Surface it serves + budget P95 (from BA) | Tables & expected row volume |
| Index used (existing) or added (columns + order, tenant_id leading for tenant-scoped) |
| EXPLAIN ANALYZE result on representative volume | N+1 removed (how) | Result set bounded (how) |
| Expected DB-hop share of the budget |
- Risks: table growth, hot partition, lock/contention, migration cost on a large table
- Verdict: DB hop fits the budget as planned | fits only with <trade-off> | does not fit →
  back to @tech-lead + @systems-architect (and @business-analyst for an exception)
```

**Database:** `## Migration Summary` — target database, migration name, schema changes, tenant_id confirmed on all tables, **indexes added/changed + the query each one serves**, **measured query time vs the DB-hop budget (EXPLAIN ANALYZE before/after)**, deployment risk (none/low/medium/high + why), rollback (`Down()`) verified, cache invalidation impact.

**QA:** `## Test Report` — test project, tests added, coverage of **every case in `@business-analyst`'s case set — happy, edge and negative** (list any case not covered and why), **how each case was executed: browser via Playwright (default) / Playwright E2E test / script-or-unit fallback + the reason no browser path existed** (see the browser-first rule below), line/branch coverage, tenant-isolation tested (yes/no), all tests PASS (yes/no), **UI verified (yes + evidence / n-a với lý do)**, **mockup conformance (yes/no/n-a)**, **i18n verified per locale (vi/en/id)**, **performance gate measured (table below)**, issues found with severity, escalate to `@project-manager` on blockers.

**QA — performance verification is part of "verify" (MANDATORY when BA's spec carries a performance gate):** measure on the real running app, not from a unit test. Report:

```
## Performance Report
### Page level (the number that decides PASS/FAIL for a screen)
| Screen | Budget | Measured P95 end-to-end cold (nav start → last render-blocking response) |
| Measured P95 warm | LCP P95 | INP P95 | Runs | PASS/FAIL |
### Component requests in the page's parallel fan-out
| Screen | N observed in the waterfall | Request | Budget | Required percentile (P99 / P99.9 per the
  amplification table) | Measured at that percentile | Samples | PASS/FAIL |
### Standalone endpoints (not part of a page fan-out)
| Endpoint | Type | Budget P95 | Measured P95 cold | Measured P95 warm | Runs | PASS/FAIL |
- How cache was flushed before the cold run (Redis L2 + L1 restart / documented method)
- Tooling: browser timing (PerformanceObserver for LCP/INP) / Playwright / NBomber (load-sensitive
  endpoints) — state which, the load profile, and the sample count per percentile
  (≥ 100 samples to claim P99, ≥ 1000 for P99.9; fewer samples = report it as not measured)
- "All endpoints passed P95" is NOT a page PASS — report the page-level number or report the
  surface as unmeasured (E4).
- Any FAIL is routed to @tech-lead → @systems-architect (+ @database-expert if the slow hop is the
  DB) → owning DEV agent; never reported as "acceptable slowness" without a stakeholder-approved
  exception.
```

**QA — browser-first rule: Playwright is the default instrument (MANDATORY):** every case in `@business-analyst`'s case set that a user can reach through the UI — happy, edge **and** negative — is executed by `@qa-engineer` **in a real browser driven by Playwright**, not inferred from a green test suite. Use the `webapp-testing` skill / Playwright MCP browser tools to drive the app live, and the E2E project (`tests/e2e/PrivacyPlatform.E2ETests/`) to lock a case in as a regression test.

- **Order of preference:** (1) drive the case live in the browser with Playwright and capture evidence; (2) add/extend a Playwright E2E test when the case must stay guarded after this change; (3) script / unit / direct-API check — **last resort only**.
- **Script-only is a fallback that must be justified.** A case may be verified without a browser **only when it genuinely cannot be driven through one** — no UI entry point (internal S2S endpoint, RabbitMQ consumer, background job, migration/data-integrity check), load generation needed for P99/P99.9 sample counts (NBomber), or the surface is unreachable from the browser in this environment. QA names the case, why the browser path was impossible, and what the substitute check actually proves (rule **E4** — a proxy check presented as verification is a process FAIL).
- **A case marked PASS on script evidence when a browser path existed is treated as untested** and routed back to `@qa-engineer`, exactly like a missing case.
- **Evidence per browser-run case:** route/URL, steps driven, assertion checked, screenshot, console errors and failed network requests. "Tested manually" without evidence does not count.
- Blocked from running the app at all (stack won't start, no credentials, missing service)? Report it as **not verified** with the blocker named and route it through `@tech-lead` — never substitute unit tests and call the case covered.

**QA — UI verification is part of "verify" (MANDATORY):** any change touching UI surface (Razor page/component, layout, CSS/tokens, theme, localization affecting render) is NOT verified by green unit tests alone. QA must run the real app, open the affected screens in a browser (Playwright MCP / `webapp-testing` skill / E2E project), check light + dark mode, the 3 breakpoints, interaction states, console errors, and capture screenshots as evidence. Skipping the UI pass on a UI-touching change is a process FAIL. Full checklist lives in `.claude/agents/qa-engineer.md` (§ UI Verification). On top of that checklist:

- **Mockup conformance:** when `@ui-ux-designer` delivered a mockup or component spec, QA compares the rendered screen against it — layout, spacing, states (loading/empty/error/success/disabled), token usage — and reports each deviation as a finding routed to `@frontend-developer`. If no mockup exists, say so explicitly rather than silently skipping the check.
- **i18n (all three locales):** QA switches the UI to **vi, en and id** and verifies every added/changed key renders translated text (no raw resource keys, no English fallback in vi/id), no truncation or overflow from longer strings, and that dates/numbers/currency follow the active locale. Missing keys in any locale are a FAIL routed back to `@frontend-developer`.

**Security** (`# Security Assessment Report` — the post-implementation pass at step 6; the step-4 pre-implementation validation uses the shorter format above and must also be re-checked here):
```
## Executive Summary — overall posture PASS/FAIL/PARTIAL, counts by severity
## Vulnerability Findings — Critical / High / Medium / Low, each with
   Type (OWASP # / CWE-XXX), Location (File:Line), Risk, Remediation,
   Severity, Assign to (@backend-developer / @frontend-developer)
## Security Controls Checklist
   - Auth & Authz (ISO 27001 A.9): RBAC, ABAC, JWT expiry, MFA, S2S, session mgmt, password policy
   - Multi-Tenant Isolation: tenant_id on every query, EF global filters, no cross-tenant leak,
     tenant-prefixed cache keys, no IDOR
   - API Security (OWASP #1/#3/#6): input validation, output encoding, rate limiting, CORS, no
     exposed secrets, auth headers checked
   - Data Protection (ISO 27001 A.10 + GDPR/CCPA): PII encrypted at rest, per-tenant keys, TLS in
     transit, no secrets in code/logs, retention policy, audit logging (SHA-256 hash chain)
   - Injection Prevention (OWASP #1): parameterized queries only, no raw SQL concat, XSS/command
     injection prevention
   - Compliance: GDPR (consent/DSR/PbD/DPA/DPIA), CCPA (consumer rights/opt-out/disclosure),
     Vietnam PDPL 2025 (data protection/cross-border/consent)
## Remediation Priority — MUST FIX (blocks release) / SHOULD FIX (before release) /
   NICE TO FIX (backlog via @project-manager)
```
Inline code-review variant: `## Security Issue: [File:Line]` with Issue, OWASP #, CWE, Severity, Assign to, Recommendation (fix snippet).

**Security — two mandatory passes:** (1) **pre-implementation task validation** at step 4 — every task is validated *as designed* and blocks assignment until PASS; (2) **post-implementation assessment** at step 6 on the actual diff, which also verifies that each step-4 required change was built. Skipping the pre-implementation pass is a process FAIL even if the post-implementation pass comes back clean.

**Security — zero-vulnerability gate (MANDATORY):** `@security-engineer` must walk the change through **all ten OWASP Top 10 (2021) categories** (not only the ones that look relevant), map every finding to a **CWE** and the applicable **NIST CSF** function (Identify/Protect/Detect/Respond/Recover) and **ISO 27001 Annex A** control, and state the verdict per category — PASS, FAIL, or N/A with a reason. A category marked N/A without justification is treated as unreviewed. Available tooling to back the review up: the `sec-owasp-top10`, `sec-sast-trivy`, `sec-dependency-scan` and `sec-mitre-exploit` skills, and the `vuln-scanner-*` / `vuln-scan-orchestrator` agents. The pipeline does not reach PM's gate while any Critical or High finding is open; Medium/Low must be either fixed or explicitly accepted in writing by the stakeholder via `@project-manager`.

## Branching

- `main` is currently the only branch in this repo, and there is no CI/CD workflow configured yet.
- **Agents never commit or push to any branch.** See Rule #0: finished work stays uncommitted in the working tree for independent human review.

## graphify — routed knowledge graphs (single source: Rule #1)

`graphify-out/scope.json` declares the routed graphs this repo maintains: the root backend graph (`graphify-out/graph.json`), the `frontend` and `data-model` shards, and the documentation shard at `docs/graphify-out/graph.json`. **The operating rules — how to invoke the CLI, the mandatory Prior-Art Inventory before any stakeholder request, when text search is allowed, and the mandatory rebuild after code changes — are Rule #1 at the top of this file.** `AGENTS.md` carries the same rules for non-Claude agents; keep the two in sync when either changes.
