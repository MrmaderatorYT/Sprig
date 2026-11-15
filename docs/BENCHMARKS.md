# Reproducible benchmarks and local device fixture

The Java17 `benchmark` module exercises Sprig's real portable proxy, not an Android VPN substitute. It contacts only numeric loopback destinations. Reports explicitly identify `host-jvm-loopback` and leave unsupported metrics null. Do not treat these numbers as Android throughput, battery, UI-jank or VpnService evidence.

## Host benchmark

From the repository root, with JDK17:

```sh
./gradlew :benchmark:test
./gradlew :benchmark:run --args='--scenario http --warmup-seconds 3 --seconds 15 --concurrency 2 --rate-per-second 100 --body-bytes 4096 --output dist/benchmarks/host-http.json'
./gradlew :benchmark:run --args='--scenario mixed --warmup-seconds 3 --seconds 60 --concurrency 2 --rate-per-second 100 --body-bytes 4096 --output dist/benchmarks/host-mixed.json'
```

The runner starts an ephemeral 127.0.0.1 HTTP/WS echo fixture and a real Sprig HTTP proxy. Main workers alternate between actual HTTP capture through the proxy socket and direct Repeater requests. Every main response must be status200 with the exact binary body. Fixture response bodies are deterministic; the report includes a fixture revision and request-body SHA256. No external targets, DNS service, credentials or CA installation are needed. The temporary CA directory is removed after cleanup; this HTTP-only workload never generates a trusted MITM CA.

`mixed` adds one WebSocket binary-echo stream, a one-worker Intruder with lazy numeric payloads, and a one-page Scanner with headers/reflection/SQL-error categories. Auxiliary HTTP responses must be successful; WS echoes must match exact bytes. HTTP history, WebSocket buffers/history and Intruder share one32MiB reservation budget (the same core admission system used by the app). Reports include reserved peak, rejected reservations and final usage; nonzero reserved bytes after clear/forget/close fails the run. These reservations are conservative ownership estimates rather than a measurement of total JVM heap. The workload uses at most eight proxy admissions. It does not benchmark TLS, H2/H3, TUN, JavaScript execution or all Scanner categories.

One shared monotonic FIFO pacer caps all main HTTP, WS-data, Intruder and Scanner sends together; setup handshakes are excluded. Main latency starts after rate admission and excludes the pacing wait. Offered rate, admitted workload count and achieved **main** HTTP throughput are reported separately. This is a controlled offered-load experiment, not a maximum-throughput claim.

Warmup runs the selected scenario separately and clears HTTP history before measurement. Workloads stop at the configured deadline. Main operations finishing after it are counted separately and excluded from throughput and latency. Cleanup cancels request-local handles and tool runs, closes WS sessions and stops fixture workers. A content/transport/cleanup failure makes `passed=false` and exits the CLI unsuccessfully after writing its report.

### Options and bounds

| Option | Default | Bounds |
| --- | --- | --- |
| `--scenario` | `http` | `http` or `mixed` |
| `--warmup-seconds` | 3 | 0–60 |
| `--seconds` | 15 | 1–3600 |
| `--concurrency` | 2 | 1–4 main workers |
| `--rate-per-second` | 100 | 0–10000 total workload operations/s; 0 is explicit unpaced stress |
| `--body-bytes` | 4096 | 0–262144 |
| `--output` | `dist/benchmarks/host-latest.json` | Explicit local artifact path |

Only one Gradle invocation should run in a workspace at once. CPU-heavy builds, other processes, thermal state and OS scheduling influence host results. Keep identical JDK/heap/configuration/fixture versions, run several repetitions on an otherwise idle machine, and compare distributions. `:benchmark:run` defaults to a 256MiB maximum heap. Use the installed application distribution or an explicit Java launch to compare other heap limits; record them from the report.

### Report semantics

- Version, JDK/VM, OS/architecture, logical processors, maximum heap, UTC time and exact configuration.
- Successful main operations/second, late completions, bounded error samples, and auxiliary operation counts.
- p50/p95 in microseconds from a fixed4096-bin logarithmic histogram; values are upper bounds with approximately1.6% relative bin error, apart from nanosecond rounding. No per-request latency list is retained.
- Resource samples at start, roughly once per second and after workload cleanup: process CPU nanoseconds, heap used/committed, GC collections/time, thread count/peak, open FDs and RSS when available.
- CPU/GC deltas cover phase setup, measurement and cleanup, not just the steady-state operation interval. No CPU-utilization percentage is inferred from a different interval.
- RSS is obtained from Linux `/proc/self/status` only; macOS/Windows RSS is null. FDs use the supported host Unix management bean, otherwise null. Missing counters are never reported as zero.
- Resource samples are bounded by the configured one-hour maximum. There is no unbounded metrics queue or latency-sample accumulation.

### Fresh TCP connections and the unpaced stress failure

Each HTTP exchange currently opens fresh origin connections; capture also opens its client-to-proxy socket. An unpaced60-second attempt exhausted host ephemeral ports and reported BindException / Address already in use rather than completing valid load. Its failed JSON is preserved as `dist/stage04/host-mixed-60s-unpaced-failed.json`. The run is not a passing baseline. High-rate repeated runs can exhaust TCP/TIME_WAIT resources before testing useful app capacity. Allow the host TCP state to drain, select an explicit offered rate, and preserve failures. Pooling belongs to roadmap0.5; the harness does not silently introduce it or alter kernel networking settings. A rate cap reduces demand but cannot guarantee availability under unrelated host traffic. `--rate-per-second 0` remains an intentional unpaced stress option whose failure must remain visible.

A failed workload worker stops its own operation loop; remaining workers may keep running until the configured deadline. Error samples and `passed=false` must therefore be read before interpreting throughput or resource samples.

The loopback fixture shares the measured JVM, so CPU, heap, GC, threads and FDs include fixture and harness overhead. Sampled heap is not allocation-per-operation or a reliable retained-size measurement; allocation optimization needs a profiler/JFR comparison. No automatic performance pass/fail thresholds are invented before baseline/device measurements.

## HTTP/WS fixture for a real Android test device

A separate Java main can serve an explicit private-LAN interface. It never changes Wi-Fi/firewall settings, installs APKs or grants VPN consent. Choose an assigned private IPv4 address and a fixture port intercepted by the current VPN (for example8080).

```sh
./gradlew :benchmark:serveFixture -PfixtureArgs='--bind 192.168.1.20 --port 8080 --seconds 600 --token sprig040fixture --ready-file /private/tmp/sprig-fixture-ready.json'
```

Omit `--token` to generate a fresh128-bit token. Allowed tokens contain12–64 ASCII URL-safe characters. The server prints/writes ready JSON with its actual address/port, URLs, expected GET body and expiration duration. It accepts only assigned loopback/private-LAN numericIPv4 binds; wildcard/public binds are rejected. Default bind is127.0.0.1, port0, lifetime600seconds (maximum3600).

- GET `/sprig-fixture?token=<token>` returns exactly `sprig-fixture-v1:<token>` as UTF-8.
- POST to the same path echoes exact body bytes, allowing binary capture validation.
- A WebSocket upgrade on the same URL/port echoes text/binary/fragments and responds to Ping/Close.
- Incorrect token/path suffix closes the connection. A token is temporary fixture isolation, not production authentication.
- One bounded12-worker fixture pool, connection deadlines and the core codec's4MiB body limit protect the host from unbounded workers/bodies. The test fixture is not a production HTTP server.

Run the separately authorized Android fixture client against the printed LAN URL while Sprig VPN capture is active. Sprig excludes itself from VPN, so requests made by Sprig alone cannot prove another app's TUN capture. Record device/API/OEM, APK hash, VPN state and independently verified captured request/response. Root `docs/DEVICE_TESTING.md` covers the broader acceptance matrix.

Device CPU/RSS/heap/GC, FDs, UI frame timing and battery require separate Android instrumentation/Perfetto/profiler artifacts. A successful host benchmark or LAN fixture launch must not check off those device measurements.

## History copy/ownership allocation comparison

```sh
./gradlew :benchmark:storeAllocation -PallocationArgs='--iterations 5000 --warmup-iterations 1000 --rounds 5 --output dist/benchmarks/store-allocation.json'
```

This independent same-JVM experiment compares `SessionStore.record` (defensive copies) with `recordOwned` (already immutable capture buffers). Both consume the same prepared exchange with32KiB request and32KiB response bodies and retain at most eight entries under a2MiB shared budget. Source-body creation, snapshot validation and clear are outside the timed/allocation interval. Warmup and alternating variant order reduce simple order/JIT effects; individual rounds remain in the JSON.

The host ThreadMXBean current-thread allocation counter is optional and explicitly null if unavailable. It measures allocations during record calls, not RSS or peak heap, and includes bookkeeping allocations. Outputs include elapsed/allocated bytes per operation and owned/copy ratios. Assertions check equal content/count/byte accounting, ownership semantics and zero reserved bytes after clear. No Android allocation or speedup claim follows from this host microbenchmark. The owned path is valid only for captures whose callers never mutate body arrays after publication; it must not replace defensive copying at untrusted/mutable API boundaries.
