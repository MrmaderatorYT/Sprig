# Sprig architecture

All repository code is Java; Android uses native Material 3 views. Gradle is Groovy. There is no Kotlin compiler plugin, JNI/NDK transport, remote capture gateway, analytics or default cloud connection.

## Packet and request paths

```text
Selected apps → Android VpnService TUN (IPv4)
  TCP 80/443/8080/8443 → local proxy → optional TLS MITM / ALPN
      HTTP/1 codec or HTTP/2 framing + Netty HPACK
      → shared Intercept → bounded upstream admission → origin HTTP/1 or HTTP/2
      HTTP/1 Upgrade → independent WebSocket readers / ordered message delivery
  QUIC UDP → Initial SNI/ALPN inspection → optional local QUIC MITM
      HTTP/3 + QPACK → shared Intercept → admission → actual HTTP/3 origin
  Out-of-scope TLS/QUIC and other supported TCP/UDP → protected direct sockets
```

All origin TLS paths verify certificate trust and hostname. The original IP/port stays pinned for transparent capture. Explicit Repeater selects its origin from the URL and request path from the editor. Redirects are returned, never automatically followed by the transport.

IPv6 is routed into the interface and dropped to avoid silent bypass. Fragmented IPv4, general ICMP, IPv6-only DNS/NAT64 and ECH need further support. Underlying IPv4 DNS resolvers are reused; public resolvers are fallback only.

## Modules

- `app`: VPN lifecycle/consent, adaptive budgets, Material 3 workspaces, explicit Storage Access Framework exports.
- `core`: packet bridge, proxy, HTTP/1/2/3 adapters, CA, history, interception, WebSocket, Intruder, Scanner, HAR, payload replay.
- `quic`: exact Kwik 0.12.2 source port, with its optional JMX heap logger replaced by a no-op hook for Android.
- `qpack`: exact QPACK 2.1 source port, replacing one newer InputStream method with an equivalent bounded loop.
- `oast`: Android-compatible authenticated Collaborator client and data models.
- `collaborator-server`: separate JDK 17 application serving OAST callbacks and an isolated authenticated API.

Original LGPL source JARs, complete modified source modules, patch descriptions and license texts are included. No QUIC/TLS cryptographic algorithms are rewritten by Sprig.

## Limits and privacy

- History: 1,000 entries, 10% of process heap bounded to 4–24 MiB. Body capture512 KiB per side; inspected message4 MiB; headers64 KiB; editor1 MiB.
- Capture workers, relay pumps and shared upstream admission: 2 at heap≤128 MiB,4 at≤256 MiB,8 above. Admission covers Scanner, Intruder, Repeater, payload replay, HTTP/1/2/3 and rejects overload explicitly. A validated WebSocket upgrade releases its HTTP permit and transfers sockets to a separately bounded hub.
- TUN: one NIO selector, up to96 TCP and128 UDP flows,32 KiB windows, packet queue512, idle/connect/retransmission limits.
- HTTP/2:8 active streams/connection,4 MiB aggregate pending request bodies,64 KiB header lists,4 KiB HPACK table,16 KiB inbound frames. Server push/extended CONNECT disabled.
- HTTP/3:2 or4 gateway flows,32 queued datagrams and64 KiB pending wire bytes per flow,512 KiB QUIC connection receive budget,64 KiB stream budget, bounded shared workers. QPACK dynamic capacity0.
- Intercept:16 waiting requests,120-second decision timeout. Stop cancels waits. Turning interception off forwards the originals.
- WebSocket: at most 4 active sessions (fewer on small heaps), separate readers and ordered delivery, 1 MiB messages, 2 MiB queued/session, 8 MiB captured history. Independent interception keeps Ping/Pong responsive. Ordered Close preserves accepted preceding data. Compression and H2/H3 extended CONNECT are disabled.
- Intruder: four lazy position modes; one active run, 1–4 workers, 100,000-attempt budget, global pacing, per-attempt cancellation/deadline, bounded payload/result memory, RE2 grep and explicit export. Recent two runs are retained in process memory.
- Scanner: one origin, GET-only by default; captured POST requires stateful opt-in. Sixteen selectable categories, bounded insertion discovery, per-check/global budgets and pacing. Credential templates are origin-validated. Reports include probe/control references and confidence; static/browser heuristics remain observations.
- DNS: two expiring resolver workers, queue eight, 12-second caller deadline. Numeric IPs are parsed without DNS; scoped cancellation wakes the waiting request even if the OS lookup ignores interruption. TLS/SNI still uses the original target name. Interface-scoped IPv6 literals are rejected.
- Scanner/Intruder cancellation owns only scoped sockets/QUIC handles. Closing is done away from the UI thread. UI work queues are bounded and reject excess actions explicitly.
- OAST server:128 sessions,24-hour TTL,256 events/2 MiB each,16 MiB aggregate,256 KiB JSON pages. Separate public-callback and management worker pools; SMTP/TCP DNS have absolute connection deadlines.
- CA keys stay in app-private no-backup storage, public certificate export excludes the key. Keys are not hardware-backed in this version.
- Traffic, reports and OAST sessions are process-local and ephemeral. HAR/JSON export is explicit and may contain sensitive target data. Collaborator API credentials never appear in callback domains or polling URLs.

## Performance and verification

Buffered protocol parsing, bounded queues/bodies, cached leaf credentials, batched UI refresh, independent HTTP/2 stream dispatch and R8/resource shrinking are implemented. Large drafts live in process memory rather than Android saved-state parcels. The QUIC source port creates no JMX monitor thread.

Fresh origin connections are used per exchange. Pooling, resumption and streaming bodies remain pending. Stage0.4 adds one shared retained-owner byte budget for history, WebSocket and Intruder; transient protocol/native/UI/snapshot memory is not universally reserved. See MEMORY_BUDGET.md. Host unpaced testing also exposes ephemeral-port/TIME_WAIT exhaustion, so the reproducible load harness offers explicit pacing. Current controls do not prove maximum throughput or battery efficiency. Initial Pixel7a/API37 debug runtime and HTTP/IPv4 VpnService evidence are recorded in STAGE_0_4.md. Other API/OEM/browser protocols, sustained profiling and radio/battery validation remain pending.

See `VALIDATION.md`, `DEVICE_TESTING.md`, `WEBSOCKET.md`, `INTRUDER.md`, `SCANNER.md`, `HTTP3.md` and `COLLABORATOR.md` for concrete checks and deployment requirements.
