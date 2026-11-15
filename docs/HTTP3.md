# Sprig HTTP/3 transport

The HTTP/3 path uses Java throughout: the local `quic` Android port of Kwik **0.12.2** for QUIC v1/v2 and TLS 1.3, Agent15 **4.0**, and QPACK **2.1**. No JNI, NDK, Cronet, or Kotlin code is required.

Kwik's upstream server constructor unconditionally instantiates a JMX heap-warning logger. Android does not provide `java.lang.management` or `javax.management`, so Sprig compiles the exact 0.12.2 sources in the `quic` module and replaces only `tech.kwik.core.util.MemoryWatcher` with a documented no-op compatibility hook. It preserves its public constructor/constants and creates no monitor threads. Sprig's own adaptive heap/flow limits and Kwik receive-buffer budgets remain active. JPMS `module-info.java` is omitted; upstream source headers and the version resource are preserved. See `quic/README.md` for reproducibility.

## Capture path

1. `TunEngine.DatagramHandler` receives a UDP packet before the optional UDP/443 blocking rule.
2. A bounded `Http3Gateway` flow collects QUIC Initial CRYPTO fragments. Kwik authenticates/decrypts the public Initial packets; Sprig reads SNI and ALPN from the reassembled ClientHello. Original datagrams are never modified by the sniffing parser.
3. For ALPN `h3`, enabled MITM, and a host in scope, the gateway creates a loopback IPv4 QUIC endpoint with a leaf certificate from the install-local CA. ECH-protected inner ClientHello names are not supported.
4. HTTP/3 requests map to the same interception, editing, capture and history path as HTTP/1 and HTTP/2. The original destination IP/UDP port stay pinned. An `:authority` cannot escape that destination.
5. The origin side uses a separate real HTTP/3 connection. Origin certificate chain and hostname verification remain enabled. An HTTP/3-only origin is supported; this path does not silently replace QUIC with TCP.
6. When MITM is disabled or the host is outside scope, the datagrams are forwarded directly and remain encrypted end to end.

The gateway also recognizes H3 on nonstandard UDP ports. QUIC versions other than v1/v2 and QUIC Initial messages that cannot be inspected are outside this path. When not handled, the existing configured UDP policy applies.

## Protocol coverage

- SETTINGS, control streams, HEADERS, DATA, informational responses, trailers, and multiple concurrent request streams.
- QPACK static table and Huffman decoding. Sprig advertises dynamic table capacity and blocked-stream count of zero; peers must encode without dynamic references. Its encoder currently uses non-Huffman literals.
- TLS 1.3, QUIC v1/v2, transport retransmission and flow control are supplied by Kwik.
- Request editing and Drop use the existing interception controller. Repeater can explicitly request HTTP/3.
- No server push, WebTransport, extended CONNECT, ECH, or application-level QUIC migration mapping.
- The origin client creates a fresh connection per request. Origin connection pooling, resumption and 0-RTT are not yet implemented.

This is an interoperable bounded inspection implementation, not a claim of complete Burp Professional parity or exhaustive RFC conformance across all clients.

## Resource and platform bounds

- At most **two gateway flows** for heaps ≤256 MiB and **four** above that.
- Gateway packet queues: 32 datagrams per flow; pending wire bytes: 64 KiB; Initial CRYPTO: 64 KiB.
- Endpoint: at most two connections, four concurrent bidirectional streams per connection, three critical unidirectional streams, 512 KiB QUIC receive budget per connection and 64 KiB per stream.
- Four HTTP request workers shared by all endpoints; queue eight. Critical stream readers and setup workers have separate bounded pools.
- HTTP headers: 64 KiB decoded/encoded; inspected bodies: 4 MiB. Existing history capture and byte budgets still apply.
- Connection setup: 12 seconds. Gateway idle expiry: 30 seconds. Upstream request deadline: 45 seconds.
- Unsupported JCA runtimes fail with an explicit HTTP/3 availability error. `Http3Client.availabilityError()` exposes the preflight result.
- Android selects Agent15's public Android RSA-PSS algorithm mapping. P-256 remains the portable key share. EC groups are checked through JCA, and X25519 is advertised only when the public XEC interface and an actual X25519 key generation test succeed; older Android runtimes use the supported EC groups. X448 is not advertised.
- Core library desugaring supplies newer Java collection APIs needed by the libraries on Android 8+.

## Verification

Eleven JVM tests cover QUIC v1/v2 Initial SNI extraction, fragmented/out-of-order CRYPTO, unchanged forwarded bytes, QPACK and binary body framing, negative header cases, true QUIC TLS 1.3 request/response, origin hostname and trust rejection, full simulated-TUN MITM with a separate actual QUIC origin, original destination routing, Forward editing, and MITM-disabled origin-CA passthrough.

An additional independent interoperability check used curl 8.23.0 with ngtcp2 1.25.0 / nghttp3 1.18.0 and `--http3-only` against the Java endpoint. A certificate-validated POST returned `HTTP/3 200` and the expected binary-safe payload; curl exited successfully. This also exercises a peer implementation of QUIC/QPACK independently of Kwik.

These tests do not replace real Android VPN testing, device-specific crypto checks, browser interoperability or performance/battery profiling.

## Upstream licenses

Kwik, Agent15 and QPACK are LGPLv3-or-later. The sole Kwik source modification is the Android `MemoryWatcher` compatibility hook described above; QPACK is built from the local `qpack` module with one equivalent bounded read-loop patch for Android 8 compatibility (`qpack/UPSTREAM.md`); Agent15 remains an unmodified dependency. The Gradle project and source archive permit replacing the libraries and rebuilding/re-signing the app. Include the upstream license texts and corresponding dependency sources when distributing the APK.

- [Kwik source](https://github.com/ptrd/kwik), [0.12.2 source JAR](https://repo.maven.apache.org/maven2/tech/kwik/kwik/0.12.2/kwik-0.12.2-sources.jar)
- [Agent15 source](https://github.com/ptrd/agent15), [4.0 source JAR](https://repo.maven.apache.org/maven2/tech/kwik/agent15/4.0/agent15-4.0-sources.jar)
- [QPACK source](https://github.com/ptrd/qpack), [2.1 source JAR](https://repo.maven.apache.org/maven2/tech/kwik/qpack/2.1/qpack-2.1-sources.jar)
