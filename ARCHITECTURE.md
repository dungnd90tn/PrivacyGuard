# PrivacyGuard

PrivacyGuard is a proposed privacy app that combines network filtering, per-app rules, link cleaning, a private browser, and a dashboard.

Status: Android MVP implemented in `app/`: native UI, a split-route DNS VPN (UDP/TCP over IPv4/IPv6), local policies/counters, link cleaning, and a separate-process WebView session. Full network proxying and comprehensive tracker coverage remain outside this MVP. See [README.md](README.md) and [MVP scope and validation](docs/mvp.html) for its limitations and verification status.

## Modules

| Module | Responsibility |
| --- | --- |
| VPN Core | Manage the TUN interface, parse IPv4/IPv6 and UDP/TCP traffic, parse DNS, and connect packet handling to the filtering engine. |
| Tracker Engine | Match exact domains and wildcard rules, assign tracker categories, and apply allowlists. |
| App Rules | Store category decisions and domain exceptions for each application. |
| Link Cleaner | Remove `utm_*`, `fbclid`, `gclid`, and user-configured query parameters. |
| Private Browser | Isolate browsing state, apply third-party filtering, and delete session data on close. |
| Dashboard | Present observed block events grouped by app, domain, category, and time period. |

## Proposed decision flow

1. VPN Core validates the packet before parsing its transport payload.
2. The platform adapter supplies application identity when available. Unattributed traffic remains explicitly unknown.
3. DNS parsing supplies a domain when visible; filtering must represent traffic with no known domain explicitly.
4. Tracker Engine normalizes the domain and resolves a category from the configured rule source.
5. App Rules evaluates the domain and category against user preferences.
6. VPN Core enforces the decision through its forwarding implementation.
7. Dashboard storage records a minimal decision event only after the enforcement result is known.

TUN access and packet parsing alone do not provide a functioning VPN. The implementation must also forward allowed traffic, return responses, and handle connection state and lifecycle failures.

## Proposed rule precedence

Evaluate rules in this order, with the first matching decision winning:

1. Explicit per-app domain exception, either allow or block.
2. Global domain exception, either allow or block.
3. Per-app category preference.
4. Global category preference.
5. Default allow for unmatched traffic.

Within the same level, prefer exact domains over wildcard matches, then the longest matching domain suffix. Conflicting entries for the same normalized key should replace one another during configuration rather than depend on evaluation order.

Define `*.example.com` to match subdomains only. Add `example.com` separately when the apex should also match. Match at label boundaries so `notexample.com` cannot match `example.com`.

## App rule examples

| App | Category | Decision |
| --- | --- | --- |
| Facebook | Ads | Block |
| Facebook | Analytics | Block |
| Facebook | Essential | Allow |
| Zalo | To be classified | To be configured |

These are configuration examples, not validated classifications of vendor domains. If a domain serves both essential and advertising traffic, a domain-level rule cannot distinguish the requests. Shared-domain conflicts need to be visible to the user.

## Privacy and accuracy requirements

- Store aggregate counters locally by default; detailed event history should have a bounded retention policy.
- Do not store packet payloads or full browsing URLs in dashboard events.
- Show unknown app identity and unavailable domain information without inventing attribution.
- Count actual enforced block events; repeated requests count as repeated events, not distinct trackers.
- Keep demo statistics separate from observed counters. The outline's Facebook 312, Zalo 84, and Chrome 231 values are illustrative only.
- Describe network filtering coverage according to the implemented transport and domain-visibility support.
- Treat browser storage isolation and deletion as acceptance criteria that require platform-specific verification.

## Delivery sequence

1. Choose the target platform, build system, and initial deliverable.
2. Implement deterministic domain matching, rule precedence, and link cleaning as independently testable logic.
3. Build the rules editor and dashboard with clearly labeled sample data until real enforcement events exist.
4. Implement the platform network service, packet validation, forwarding, and DNS integration. Verify allowed traffic still works before presenting protection as active.
5. Add application attribution and validate category rules against representative app workflows.
6. Implement private browser session isolation, third-party filtering, and deletion behavior.
7. Validate IPv4/IPv6, UDP/TCP, malformed inputs, service lifecycle, and counter accuracy end to end.

## Initial acceptance cases

- Exact domain rules do not match unrelated domains.
- Wildcards follow the documented apex and label-boundary semantics.
- Per-app decisions do not affect another app's settings.
- Rule precedence resolves conflicting preferences predictably.
- Link cleaning preserves unrelated parameters, repeated parameters, and URL fragments.
- Malformed network input does not crash the service or produce a false success state.
- Allowed traffic reaches its destination and blocked traffic produces an enforcement event.
- Dashboard totals correspond to recorded enforcement events.
- Closing a private session removes the session data covered by the browser's stated deletion policy.
