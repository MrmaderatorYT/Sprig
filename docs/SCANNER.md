# Sprig Scanner

Scanner is an explicitly started, bounded audit workflow. Opening the screen or starting VPN capture does not start a scan. It does not provide the complete Burp Scanner rule set or claim automatic proof of every reported issue.

## Start and scope

Open **Tools → Scanner** for a URL-based scan, or send a complete captured request from HTTP history. History handoff passes only the URL and exchange ID in the Intent. Captured headers and bodies stay in process memory.

The seed defines one origin: scheme, normalized host and effective port. Crawl links, redirects and probes stay on that origin. The transport returns redirect responses without following them. The crawler resolves `<base href>`, ignores links embedded in comments/script/style text, deduplicates URLs and skips paths that resemble destructive or state-changing GET actions. These heuristics cannot guarantee that an incorrectly implemented server treats GET as safe.

Captured Cookie/Authorization headers are reused only when their original Host/absolute target agrees with the seed origin. Changing the origin rejects the scan before sending a request. The default URL-only workflow is anonymous.

## Categories

**Check categories** controls the registry below. The first five are enabled by default; extended categories are opt-in. Disabling **Active checks** suppresses all probes while leaving selected passive analysis and the requested crawl/baseline fetches enabled.

| Registry ID | Category | Behavior and evidence |
| --- | --- | --- |
| `headers` | Security headers/cookies | Passive hardening observations; CSP caveats, cookie attributes and prefix requirements. Missing headers do not prove an exploit. |
| `reflection` | Contextual reflection | Unique harmless marker/custom tag, with HTML text, attribute, event-handler or JavaScript-block context. No JavaScript is executed. |
| `sql-error` | SQL error differential | Apostrophe probe versus baseline and a benign control with the baseline status. New database-specific errors are tentative leads. |
| `redirect` | External redirect | Unique destination under reserved `example.invalid`; checks Location and never follows the external redirect. |
| `cors` | Credentialed CORS | Two distinct untrusted Origin values must each be reflected with credentials enabled. Actual sensitive-data access remains unproven. |
| `template` | Template arithmetic | Two unique multiplication expressions (`{{…}}` or `${…}`) and a literal control. No function calls, commands or arbitrary code execution. |
| `crlf` | Response-header injection | Encoded line-break marker must become an actual response header and be absent from baseline/control. Body reflection alone is insufficient. |
| `host-header` | Forwarded Host reflection | Distinct X-Forwarded-Host markers, unique cache-buster URLs and no-store/no-cache request headers. Actual Host, routing and TLS hostname remain unchanged. No shared-cache poisoning or cross-user retrieval is attempted. |
| `methods` | OPTIONS/TRACE | Reads advertised Allow methods and sends a credential-free TRACE marker. PUT/DELETE and other destructive methods are not invoked. TRACE omits captured headers and query parameters. |
| `sql-boolean` | Boolean differential | Numeric insertion values only. Repeated baseline/true/false responses must be stable, with matching statuses and a meaningful body-length difference. Still tentative: application query languages or validation can mimic SQL behavior. No delays, extraction or data modification. |
| `cache` | Cache policy observations | Reviews public/s-maxage policies on requests carrying cookies or Authorization. Cookie presence alone does not prove authentication; no-store/private restrictions are considered. No cache-poisoning test. |
| `disclosure` | Sensitive/debug signatures | Complete private-key block detection and stack/debug signatures. Private-key material is redacted, and debug signatures remain heuristic. Intentional fixtures/documentation need manual review. |
| `directory` | Directory listing | Recognizable index title plus parent-directory indicator. Listed files are not downloaded by this check. |
| `csrf` | Form audit | POST form without an obvious familiar CSRF field name. Forms are not submitted. SameSite, Origin/Referer and other defenses may apply. |
| `dom` | DOM source/sink observations | Browser-controlled source and dangerous HTML/code sink patterns in JavaScript source. No JavaScript execution, taint/dataflow proof or confirmed DOM XSS. |
| `oast` | User-provided callback | Requires the user's own callback URL/hostname. Submits a unique `/sprig-scan/<marker>` correlation path. A successful HTTP probe is not a callback; use your Collaborator events to corroborate it. No default public service is used. |

The `host-header` category tests **X-Forwarded-Host**, not raw Host authority or cache poisoning. Raw-Host Intruder behavior, if selected separately, is outside this scanner check.

## Insertion points and POST controls

**Insertion points** selects the locations below. Discovery is bounded and excludes familiar secret/token parameter names.

- **Query**: URL query parameter values; the first occurrence of each name is selected.
- **Headers**: only explicitly named headers, up to eight. Authentication, cookie, framing and other sensitive header names are rejected. The default candidate is `X-Sprig-Input`, and headers are not probed unless the header location is enabled.
- **Form**: captured POST `application/x-www-form-urlencoded` field values. Unselected encoded fields are retained.
- **JSON**: captured POST `application/json` string leaves, with depth/node limits. Numbers/booleans are retained; selected strings are changed via a parsed JSON tree. Re-serialization changes whitespace and may invalidate application-specific signatures.

POST is disabled by default. A complete captured POST is required, followed by **Enable stateful testing of this captured POST**. The original captured request target is pinned. Replaying POST can change application state even when payloads are harmless. The crawler never automatically submits discovered forms, and PUT/DELETE are not accepted as captured scanner templates. Known destructive-looking seed paths are still rejected.

Requests must have complete framing and fit the 64 KiB captured-template cap. Mutated scanner requests are capped at 128 KiB. Incomplete/truncated request bodies cannot silently become anonymous or empty scans. A response being truncated does not by itself make an otherwise complete captured request unusable.

## Budgets, lifecycle and cancellation

Defaults: 20 pages, 150 total requests, two concurrent page workers, 250 ms global request spacing, crawl depth three, six insertion points per page and 24 probes per category across the scan.

Configurable bounds: 1–200 pages; 1–2000 total requests; 1–4 workers; 200–60000 ms spacing; depth 0–8; 1–24 points; 1–2000 probes per category. Baseline requests count toward the total. Probe counts are included by category in the report; reaching a category limit skips its further probes. Reaching the total budget ends the run with an explicit state.

Results are capped at 500 findings and 200 transport errors. Response text/decompression is capped at 512 KiB; HTML tags, forms, attributes and JSON discovery have separate limits. Findings deduplicate by check, URL and insertion-point label. Proxy-wide admission and body limits apply in addition to scanner limits.

The ViewModel retains settings, selected request and report across Activity recreation without putting large bodies or credentials into saved-state Bundles. Leaving Scanner cancels the run; process death discards this ephemeral state.

Cancel marks the scan cancelled, interrupts scanner workers and schedules scoped transport closure away from the UI thread. A shared cleanup helper has one worker and at most eight queued sweeps; repeated cancellation does not create a thread per click. If saturated, the scan's background owner performs final cleanup. TLS/provider close can still take time, but the Cancel button does not synchronously wait for it. Cancellation does not stop unrelated capture, Repeater or other tools. A cancelled engine instance is not restarted; the UI creates a new engine for another run.

## Evidence and export

Each finding includes check ID, severity, confidence, explanation, original target URL, insertion label, a bounded/redacted sample and **all baseline/probe/control exchange IDs**. Legacy `baselineExchangeId` and `probeExchangeId` fields remain available. Exchange IDs refer to the bounded in-memory HTTP history and may outlive an evicted history entry; the report retains its own short evidence sample.

Export supports:

- **JSON**: structured findings, evidence IDs, errors, visited URLs, enabled checks and per-check request counts.
- **HTML**: standalone text-only report; target and evidence text are HTML-escaped, with no executable report payloads or remote assets.
- **CSV**: quoted fields with confidence/explanation/evidence IDs; potentially spreadsheet-active leading characters are neutralized.

Report export is explicit. Target URLs can contain application secrets even when sample values/key blocks are redacted; inspect the artifact before sharing it.

## Practical limits

The crawler is static and does not execute JavaScript, render SPAs, solve authentication flows or maintain a browser cookie jar. JSON supports string-leaf insertion rather than arbitrary raw-body syntax mutations. XML, multipart/file upload, GraphQL semantics, WebSocket scanning, time-based blind injection, command/file-read payloads, full browser XSS confirmation and Burp's complete scanner coverage are not implemented here.

Controls reduce common false positives, but application-specific validation, caching, dynamic content and intentional debug fixtures still require manual assessment. A tentative lead or source-pattern observation is not a confirmed vulnerability. OAST submission is not a confirmed callback. Throughput and device battery/heap behavior still require real-device profiling.
