# Sprig WebSocket workspace

Sprig intercepts RFC 6455 WebSockets using an actual HTTP/1.1 upgrade. Both `ws://` and `wss://` work. Incoming VPN traffic uses the existing HTTP proxy ports (80, 443, 8080, 8443); the manual client can connect to another explicitly supplied TCP port.

## Working paths

- A browser or selected app makes a WebSocket request through the local VPN. For WSS, the client must trust the Sprig CA. Certificate pinning is not bypassed.
- Sprig validates version 13, the 16-byte client key, Upgrade/Connection tokens, the server's derived `Sec-WebSocket-Accept`, and any selected subprotocol.
- Origin TLS retains system certificate-chain and hostname verification. The protected socket, TLS SNI and verified hostname remain pinned to the original destination.
- The validated 101 response is captured in HTTP history. The same buffered input is transferred to the WebSocket frame reader, preserving frames that arrived in the same read as the 101 headers.
- The WebSockets screen lists sessions and text/binary/control messages. It can connect a manual client, send UTF-8 text or hexadecimal binary data, replay complete captured messages to a selected live server connection, and close one session without stopping the others.

## Interception and framing

Data-message interception can forward, edit or drop either direction independently. A hold expires after 60 seconds; disabling interception releases pending originals. Stopping capture cancels all held WebSocket messages and closes owned sessions.

Each session has separate frame readers and ordered data-delivery queues. A held data message does not block Ping, Pong or Close processing. Fragmented messages are assembled before editing; control frames can interleave with fragments. Text is validated as UTF-8 after assembly. Close codes/reasons, FIN, opcodes, reserved bits, canonical lengths and masking direction are checked. Client-to-server frames are re-masked with a fresh random mask; captures contain unmasked message bytes.

Peer close frames enter an ordered barrier behind already accepted data in that direction. Previously accepted, unheld messages finish before the close frame; a close cannot split an outgoing fragmented message. Data still held for a review decision is explicitly dropped when the peer starts closing. User-initiated close cancels queued/held data and completes or times out the active write before transport cleanup.

Compression is explicitly negotiated away: `Sec-WebSocket-Extensions` offers are removed before forwarding, and an origin that nevertheless returns an extension is rejected. RSV bits are therefore required to be zero. This avoids treating compressed bytes as editable uncompressed messages.

## Resource ownership and limits

- After the 101 handshake, generic proxy workers and the shared HTTP/Scanner/Intruder upstream admission permit are released. The WebSocket hub owns the two sockets and its bounded reader/delivery tasks.
- Active sessions are bounded to `min(4, configured proxy connection limit)`. A socket pool of long-lived WebSockets therefore does not consume every ordinary HTTP worker.
- At most 1 MiB per frame or assembled message; 2 MiB of queued data per session and eight messages per direction.
- Interception: at most 16 pending messages and 4 MiB of pending payloads; 60-second decision deadline.
- History: at most 512 messages and 8 MiB, 64 KiB captured per message; at most 32 session metadata records. Captures beyond 64 KiB are visibly marked truncated and cannot be replayed silently.
- The UI editor supports 256 KiB. Larger held messages offer Forward original and Drop, retaining all original bytes rather than truncating the forwarded message.
- After the first WebSocket frame byte, the rest of that frame has an absolute 45-second read deadline. Idle waiting between frames remains allowed. One cancellable scheduled task is used per in-progress frame, not per byte.
- Control writes have a five-second deadline; data writes have a 30-second deadline. A close handshake is given five seconds before transport cleanup.
- Stopping capture closes hub-owned sessions before generic socket cleanup, cancels pending holds and releases session slots. Closing one session does not close another session or an unrelated HTTP request.
- History and connection-header drafts are not automatically exported or persisted as session files. Captured traffic may contain credentials.

## Explicit limits

RFC 8441 extended CONNECT over HTTP/2 and RFC 9220 WebSockets over HTTP/3 are not implemented. Traditional HTTP/1 upgrades use ALPN `http/1.1` to the origin. There is no permessage-deflate support, unlimited message streaming, or cross-session replay of truncated captures. Client TLS certificates and pinning bypass remain outside the current transport support.

## Validation

JVM fixtures verify an independent JDK WebSocket client through WSS MITM, 101 headers coalesced with the first frame, 180 KiB binary traffic, fragmented UTF-8 with interleaved Ping/Pong, editing/drop/cancellation while control traffic stays live, invalid handshakes and frame encodings, forbidden close codes, WSS hostname rejection, targeted session close, and ordinary HTTP capture/replay while the maximum WebSocket session budget is occupied. Android lifecycle/rendering checks are separate from real-device VPN testing.

Protocol references: [RFC 6455](https://www.rfc-editor.org/rfc/rfc6455), [RFC 7692](https://www.rfc-editor.org/rfc/rfc7692).
