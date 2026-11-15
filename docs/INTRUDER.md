# Sprig Intruder

Intruder runs only after an explicit **Start** action. It reuses Sprig's HTTP/1.1, HTTP/2 and HTTP/3 replay path and shared network admission; existing cookies and authorization headers are retained from the selected request. The target URL pins the network destination and TLS identity. An optional **Preserve raw Host** setting changes only the HTTP Host/authority, not the connection destination or certificate validation.

## Positions and modes

The raw request editor uses ISO-8859-1, so each `§` delimiter is byte `0xA7`. Add balanced `§original value§` pairs; there must be 1–32 positions. Only delimiters are removed. All other request bytes, including binary bodies and session headers, remain unchanged. The replay transport recalculates Content-Length.

- **Sniper:** one payload set; iterate each position in order, leaving other original values unchanged. Count = positions × payloads.
- **Battering Ram:** one payload set; replace every position with the same payload. Count = payloads.
- **Pitchfork:** an independent set per position; take corresponding indices together. Count = the shortest set.
- **Cluster Bomb:** an independent set per position; lazily enumerate the Cartesian product, with the last position changing fastest. Count = the product of set sizes.

Counts use checked 64-bit arithmetic. Overflow is rejected during configuration. Large products are indexed lazily and are never materialized; a phone run executes at most 100,000 request attempts. Retries consume this same budget, so they may reduce the number of completed logical cases below the displayed maximum.

## Payloads and processing

Lists support UTF-8 text and explicit hexadecimal byte lines. Empty lines are empty payloads. Numeric sources generate inclusive signed 64-bit integer ranges lazily, with a nonzero ascending/descending step and optional zero padding. Binary payload arrays are also available through the core API.

Each set can have its own ordered processor pipeline, followed by the global pipeline. Available operations are percent URL encode/decode, Base64 encode/decode, hexadecimal encode/decode, ASCII case conversion, byte prefixes/suffixes and literal byte replacement. URL encoding uses `%20` for spaces and preserves literal `+`; it is not form-style `+` encoding. Processed payloads are capped at 64 KiB before allocation growth. List imports preflight line count and encoded byte lengths before allocating payload arrays, preserving CRLF and trailing empty lines. The aggregate list memory estimate is capped at 8 MiB across the configuration: encoded bytes plus a conservative 64 bytes per payload and 256 bytes per source. Empty payloads therefore consume budget as well. This bound concerns retained payload sources; the editor's original text and temporary encoding storage are separate.

## Runtime control

- 1–4 workers, bounded in-flight work and a single monotonic global request pacer (0–60,000 ms between admissions).
- Pause stops new dispatches and allows already active requests to finish. Resume continues the same generated sequence.
- Cancel closes only the attack's request-local sockets/QUIC handles through `CallCancellation`; unrelated capture and requests stay active.
- Each attempt has a 1–120 second deadline. Canceled deadline tasks are removed from the scheduler queue.
- Retries default to zero. Explicit retries are capped at two, apply only to GET/HEAD/OPTIONS/PUT/DELETE and transient transport errors or 502/503/504, and count against the global attempt budget. POST/PATCH are never automatically retried.
- One attack may run or pause at a time. Two recent runs remain in process memory; process death loses drafts/results. No captured secrets are automatically written to disk.

## Results, evidence and export

Results retain status, attempt count, total attempt duration, response length, payload display, error, literal grep match, RE2 extraction and bounded raw request/response evidence. RE2 avoids catastrophic backtracking. Grep inspects at most the first 64 KiB of captured response headers/entity bytes; `grepTruncated` explicitly marks incomplete inspection. Compressed entity bytes are not automatically decompressed for grep.

History is bounded by both result count (at most 10,000) and byte budget (default 16 MiB). Old results are evicted and the eviction count remains visible. Evidence is independently capped (default 4 KiB for each raw message); preview truncation and uncertain response lengths are recorded. Full requests for **Send to Repeater** are regenerated deterministically from the plan, rather than retaining every generated body.

CSV and JSON export are explicit and contain the retained snapshot, not evicted results. JSON includes Base64 evidence, case/attempt counts, eviction and truncation flags. CSV protects formula-like text fields when opened in spreadsheets. Exported evidence may contain authentication material from the user's selected request.

The core provides concrete Intruder modes and runtime controls; this does not claim every Burp Professional feature (such as arbitrary extension-defined generators, recursive grep-driven payloads or unrestricted malformed wire-message attacks).

## Validation

JVM tests cover all four mode sequences/counts, large lazy products and overflow, independent/global processors, Unicode/binary preservation, numeric boundaries, pause/resume, global pacing/concurrency, cancellation isolation, real origin socket cancellation, deadlines, retry/attempt budgets, result eviction, evidence/grep bounds and exports. A real local proxy fixture verifies binary POST bodies, recalculated length and retained Cookie/Authorization headers. No test contacts public targets.
