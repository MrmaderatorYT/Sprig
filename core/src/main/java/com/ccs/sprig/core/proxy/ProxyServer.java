package com.ccs.sprig.core.proxy;

import com.ccs.sprig.core.InterceptController;
import com.ccs.sprig.core.model.Exchange;
import com.ccs.sprig.core.model.SessionStore;
import com.ccs.sprig.core.http2.Alpn;
import com.ccs.sprig.core.http2.Http2Connection;
import com.ccs.sprig.core.http3.Http3Client;
import com.ccs.sprig.core.net.CallCancellation;
import com.ccs.sprig.core.websocket.WebSocketHandshake;
import com.ccs.sprig.core.websocket.WebSocketHub;
import java.io.ByteArrayInputStream;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Semaphore;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Loopback HTTP/1 and HTTP/2 MITM proxy. Upstream chain and hostname validation stay enabled. */
public final class ProxyServer {
    private static final int CONNECT_TIMEOUT = 12_000;
    private static final int READ_TIMEOUT = 45_000;
    private static final int KEEP_ALIVE_IDLE_TIMEOUT = 5_000;
    private static final int CAPTURE_LIMIT = 512 * 1024;
    private int connectionLimit = 8;
    private Semaphore requestBudget = new Semaphore(8);
    private int inFlightRequests;
    private final SessionStore store;
    private final InterceptController intercept;
    private final CertificateAuthority authority;
    private final SocketProtector protector;
    private final SSLSocketFactory upstreamTls;
    private final Set<Socket> sockets = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private volatile boolean mitmEnabled = true;
    private volatile Set<String> scope = Collections.emptySet();
    private volatile ServerSocket listener;
    private ExecutorService workers;
    private ExecutorService pumps;
    private ExecutorService streamWorkers;
    private volatile Http3Client http3Client;
    private final WebSocketHub webSockets;

    public ProxyServer(SessionStore store, InterceptController intercept, File caDirectory, SocketProtector protector) throws IOException {
        this(store, intercept, caDirectory, protector, (SSLSocketFactory) SSLSocketFactory.getDefault());
    }

    /** Package-private trust injection for local TLS fixtures; the app always uses the system trust manager. */
    ProxyServer(SessionStore store, InterceptController intercept, File caDirectory, SocketProtector protector,
                SSLSocketFactory upstreamTls) throws IOException {
        this.store = store;
        this.intercept = intercept;
        this.protector = protector;
        this.upstreamTls = upstreamTls;
        authority = new CertificateAuthority(caDirectory);
        webSockets = new WebSocketHub(this::openManualWebSocket,store.memoryBudget());
    }

    public com.ccs.sprig.core.memory.MemoryBudget getMemoryBudget() { return store.memoryBudget(); }
    public CertificateAuthority getAuthority() { return authority; }
    public WebSocketHub getWebSockets() { return webSockets; }
    public boolean shouldInspectHost(String host) { return mitmEnabled && inScope(host); }
    public void setHttp3Client(Http3Client client) { http3Client = client; }
    public void setMitmEnabled(boolean enabled) { mitmEnabled = enabled; }
    /** Configure while stopped; limits apply to both connection workers and raw tunnel pumps. */
    public synchronized void setConnectionLimit(int limit) {
        if (limit < 1 || limit > 8) throw new IllegalArgumentException("Connection limit must be between 1 and 8");
        if (listener != null && !listener.isClosed()) throw new IllegalStateException("Stop the proxy before changing its connection limit");
        if (inFlightRequests != 0) throw new IllegalStateException("Wait for active requests before changing the connection limit");
        if (webSockets.hasActive()) throw new IllegalStateException("Close WebSockets before changing the connection limit");
        connectionLimit = limit;
        requestBudget = new Semaphore(limit);
        webSockets.setMaxSessions(Math.min(4, limit));
    }
    public void setScope(Set<String> hosts) {
        Set<String> normalized = new HashSet<>();
        if (hosts != null) for (String host : hosts) {
            if (host == null || host.trim().isEmpty()) continue;
            host = host.trim().toLowerCase(Locale.ROOT);
            if (host.startsWith("*.")) host = host.substring(2);
            try { normalized.add(CertificateAuthority.normalizeHost(host)); }
            catch (IOException e) { throw new IllegalArgumentException("Invalid scope host: " + host, e); }
        }
        scope = Collections.unmodifiableSet(normalized);
    }

    public synchronized void start(int port) throws IOException {
        if (listener != null && !listener.isClosed()) return;
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        try { server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 32); }
        catch (IOException e) { close(server); throw e; }
        workers = executor("sprig-proxy", connectionLimit);
        pumps = executor("sprig-relay", connectionLimit);
        streamWorkers = executor("sprig-http-stream", connectionLimit);
        listener = server;
        ExecutorService acceptingWorkers=workers;
        Thread accept = new Thread(() -> acceptLoop(server,acceptingWorkers), "sprig-proxy-accept");
        accept.setDaemon(true);
        accept.start();
    }

    public synchronized int getPort() { return listener == null ? -1 : listener.getLocalPort(); }

    public synchronized void stop() {
        ServerSocket current = listener;
        listener = null;
        close(current);
        webSockets.closeAll();
        for (Socket socket : sockets) close(socket);
        sockets.clear();
        if (workers != null) workers.shutdownNow();
        if (pumps != null) pumps.shutdownNow();
        if (streamWorkers != null) streamWorkers.shutdownNow();
        Http3Client quic = http3Client;
        if (quic != null) quic.close();
    }

    /** Sends one user-authored request directly, with the same framing and certificate checks as capture. */
    public Exchange replay(String url, byte[] rawRequest) throws IOException {
        return replay(url, rawRequest, "AUTO");
    }

    /** Explicit protocol selection never silently falls back to a different requested version. */
    public Exchange replay(String url, byte[] rawRequest, String protocol) throws IOException {
        return replay(url,rawRequest,protocol,false);
    }

    /** Explicit Host testing keeps the socket route, TLS SNI and certificate verification pinned to URL. */
    public Exchange replay(String url, byte[] rawRequest, String protocol, boolean preserveHost) throws IOException {
        CallCancellation.check();
        if (!("AUTO".equals(protocol) || "HTTP/1.1".equals(protocol) || "HTTP/2".equals(protocol) || "HTTP/3".equals(protocol)))
            throw new IOException("Unknown HTTP protocol preference");
        Target target = Target.url(url);
        HttpCodec.Message request = HttpCodec.parseEditedRequest(rawRequest);
        target = target.withPath(request.startLine.split(" ", 3)[1]);
        Exchange exchange = begin(request, target);
        if (!"AUTO".equals(protocol)) exchange.protocol = protocol;
        long started = System.nanoTime();
        try {
            request.startLine = request.method() + " " + target.path + " HTTP/1.1";
            HttpCodec.Message response = perform(request, target, exchange, protocol, preserveHost);
            captureResponse(exchange, response);
            if (!exchange.upstreamProtocol.isEmpty()) exchange.protocol = exchange.upstreamProtocol;
            return exchange;
        } catch (IOException e) {
            exchange.error = safeMessage(e);
            exchange.truncated |= exchange.error.contains("limit") || exchange.error.contains("exceeds");
            throw e;
        } finally {
            exchange.durationMillis = elapsed(started);
            store.recordOwned(exchange);
        }
    }

    private void acceptLoop(ServerSocket server,ExecutorService acceptingWorkers) {
        while (!server.isClosed()) {
            Socket client = null;
            try {
                client = server.accept();
                client.setTcpNoDelay(true);
                client.setSoTimeout(READ_TIMEOUT);
                Socket accepted = client;
                synchronized(this) {
                    // An old accept loop must never submit work into a restarted generation.
                    if(listener!=server){close(client);return;}
                    sockets.add(client);
                    acceptingWorkers.execute(() -> handle(accepted));
                }
            } catch (RejectedExecutionException e) {
                if (client != null) {
                    try { sendError(client.getOutputStream(), 503, "Concurrent connection limit reached"); }
                    catch (IOException ignored) { }
                    sockets.remove(client);
                    close(client);
                }
            } catch (IOException e) {
                if (client != null) { sockets.remove(client); close(client); }
                if (server.isClosed()) return;
            }
        }
    }

    private void handle(Socket client) {
        Target fixed = null;
        boolean transparentPlain = false;
        OutputStream output = null;
        boolean ownershipTransferred = false;
        try {
            InputStream input = new BufferedInputStream(client.getInputStream(), 16 * 1024);
            output = client.getOutputStream();
            if (Http2Connection.hasPreface(input)) { serveHttp2(input, output, client, null, false); return; }
            HttpCodec.Message first = HttpCodec.readRequest(input, output);
            if (first == null) return;
            if (first.method().equals("CONNECT")) {
                boolean transparent = "1".equals(first.header("X-Sprig-Transparent"));
                fixed = Target.authority(first.startLine.split(" ", 3)[1], 443, true);
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                ClientHello hello = ClientHello.read(input);
                if (hello.consumed.length == 0) return;
                String host = hello.serverName == null ? fixed.host : hello.serverName;
                fixed = fixed.withHost(host, hello.tls);
                if (!hello.tls && !looksHttp(hello.consumed[0])) {
                    relay(client, input, output, fixed, hello.consumed);
                    return;
                }
                transparentPlain = transparent && !hello.tls;
                if (hello.tls && (!inScope(host) || !mitmEnabled)) {
                    relay(client, input, output, fixed, hello.consumed);
                    return;
                }
                InputStream replayed = new SequenceInputStream(new ByteArrayInputStream(hello.consumed), input);
                if (hello.tls) {
                    TlsStreams tls = new TlsStreams(authority.serverContext(host), replayed, output);
                    input = new BufferedInputStream(tls.input, 16 * 1024);
                    output = tls.output;
                    if ("h2".equals(tls.applicationProtocol())) { serveHttp2(input, output, client, fixed, false); return; }
                } else input = new BufferedInputStream(replayed, 16 * 1024);
                if (Http2Connection.hasPreface(input)) { serveHttp2(input, output, client, fixed, transparentPlain); return; }
                first = null;
            }
            boolean completedTransaction = false;
            for (int transaction = 0; transaction < 1000 && !client.isClosed(); transaction++) {
                if (first == null && completedTransaction && !awaitNextRequest(client, input)) return;
                HttpCodec.Message request = first == null ? HttpCodec.readRequest(input, output) : first;
                first = null;
                if (request == null) return;
                Target target = fixed == null ? targetFor(request) : fixed.withPath(request.startLine.split(" ", 3)[1]);
                if (transparentPlain && request.header("Host") != null) {
                    String logicalHost = Target.authority(request.header("Host"), 80, false).host;
                    target = target.withHost(logicalHost, false);
                }
                if (transparentPlain && !inScope(target.host)) {
                    relay(client, input, output, target, request.raw());
                    return;
                }
                if (WebSocketHandshake.isWebSocket(request)) {
                    ownershipTransferred = upgradeWebSocket(request, input, output, client, target);
                    return;
                }
                boolean shouldClose = "close".equalsIgnoreCase(request.header("Connection")) || request.startLine.endsWith("HTTP/1.0");
                if (!transact(request, output, target, shouldClose)) return;
                if (shouldClose) return;
                completedTransaction = true;
            }
        } catch (Exception e) {
            recordFailure(fixed, e);
            if (output != null) try { sendError(output, 502, safeMessage(e)); } catch (IOException ignored) { }
        } finally {
            if (!ownershipTransferred) { sockets.remove(client); close(client); }
        }
    }

    private boolean upgradeWebSocket(HttpCodec.Message request, InputStream clientInput, OutputStream clientOutput, Socket client, Target target) throws IOException {
        Exchange exchange = begin(request, target); exchange.protocol = "HTTP/1.1"; exchange.url = socketUrl(target);
        long started = System.nanoTime(); Socket origin = null; boolean transferred = false, sent101 = false;
        try (WebSocketHub.Reservation reservation = webSockets.reserve()) {
            if (inScope(target.host) && intercept.isEnabled()) {
                byte[] forwarded = intercept.intercept(exchange, request.raw());
                if (forwarded == null) { exchange.error = "Dropped by user"; sendError(clientOutput,403,exchange.error); return false; }
                request = HttpCodec.parseEditedRequest(forwarded); target = target.withPath(request.startLine.split(" ",3)[1]);
                exchange.url = socketUrl(target);
            }
            WebSocketHandshake.prepareRequest(request);
            request.startLine = "GET " + target.path + " HTTP/1.1";
            captureRequest(exchange,request);
            Semaphore budget = acquireRequestBudget();
            try {
                origin = connect(target,"HTTP/1.1");
                request.writeTo(origin.getOutputStream());
                InputStream originInput = new BufferedInputStream(origin.getInputStream(),16384);
                HttpCodec.Message response = HttpCodec.readWebSocketResponse(originInput);
                exchange.upstreamProtocol = "HTTP/1.1";
                if (response.status() != 101) {
                    HttpCodec.stripHopHeaders(response);HttpCodec.normalizeLength(response,false);response.set("Connection","close");
                    captureResponse(exchange,response);response.writeTo(clientOutput);return false;
                }
                WebSocketHandshake.validateResponse(request,response);
                captureResponse(exchange,response);
                response.writeTo(clientOutput);sent101 = true;
                Socket ownedOrigin = origin;
                webSockets.adoptProxy(reservation,socketUrl(target),request.headText(),response.headText(),clientInput,clientOutput,client,
                        originInput,origin.getOutputStream(),origin,()->{sockets.remove(client);sockets.remove(ownedOrigin);});
                transferred = true; return true;
            } finally { releaseRequestBudget(budget); }
        } catch (IOException failure) {
            exchange.error = safeMessage(failure);
            if (!sent101) sendError(clientOutput,502,exchange.error);
            return false;
        } finally {
            if (!transferred && origin != null) { sockets.remove(origin); close(origin); }
            exchange.durationMillis = elapsed(started); store.recordOwned(exchange);
        }
    }

    private WebSocketHub.ClientTransport openManualWebSocket(String value,String extraHeaders) throws IOException {
        CallCancellation.check();
        URI uri;
        try { uri = new URI(value); } catch (URISyntaxException error) { throw new IOException("Invalid WebSocket URL",error); }
        HttpCodec.Message request = WebSocketHandshake.clientRequest(uri,extraHeaders,new java.security.SecureRandom());
        boolean tls = "wss".equalsIgnoreCase(uri.getScheme());
        Target target = Target.url((tls?"https":"http")+value.substring(value.indexOf(':'))).withPath(request.startLine.split(" ",3)[1]);
        Exchange exchange = begin(request,target);exchange.protocol="HTTP/1.1";exchange.url=socketUrl(target);
        long started=System.nanoTime();Socket origin=null;boolean transferred=false;
        Semaphore budget=acquireRequestBudget();
        try {
            origin=connect(target,"HTTP/1.1");request.writeTo(origin.getOutputStream());
            InputStream input=new BufferedInputStream(origin.getInputStream(),16384);
            HttpCodec.Message response=HttpCodec.readWebSocketResponse(input);captureResponse(exchange,response);
            WebSocketHandshake.validateResponse(request,response);exchange.upstreamProtocol="HTTP/1.1";
            Socket owned=origin;transferred=true;
            return new WebSocketHub.ClientTransport(socketUrl(target),request.headText(),response.headText(),origin,input,origin.getOutputStream(),()->sockets.remove(owned));
        } catch (IOException failure) {exchange.error=safeMessage(failure);throw failure;}
        finally {releaseRequestBudget(budget);if(!transferred&&origin!=null){sockets.remove(origin);close(origin);}exchange.durationMillis=elapsed(started);store.recordOwned(exchange);}
    }

    private static String socketUrl(Target target) {return (target.tls?"wss://":"ws://")+target.authority()+target.path;}

    private void serveHttp2(InputStream input, OutputStream output, Socket client, Target fixed, boolean transparentPlain) throws IOException {
        Http2Connection.serve(input, output, client, streamWorkers, (request, host, scheme) -> {
            Target target;
            if (fixed == null) target = Target.authority(host, "https".equals(scheme) ? 443 : 80, "https".equals(scheme))
                    .withPath(request.startLine.split(" ", 3)[1]);
            else {
                if (!scheme.equals(fixed.tls ? "https" : "http")) throw new IOException("HTTP/2 :scheme differs from its TLS origin");
                target = fixed.withPath(request.startLine.split(" ", 3)[1]);
                if (transparentPlain) target = target.withHost(Target.authority(host, 80, false).host, false);
            }
            return dispatch(request, target, "HTTP/2");
        });
    }

    /** Common decoded-message path used by HTTP/2 and the Java QUIC HTTP/3 endpoint. */
    public HttpCodec.Message dispatchHttpMessage(HttpCodec.Message request, String logicalHost, String connectIp,
                                                int port, boolean tls, String inboundProtocol) throws IOException {
        String host = CertificateAuthority.normalizeHost(logicalHost);
        if (port < 1 || port > 65535) throw new IOException("Invalid origin port");
        Target target = new Target(host, connectIp == null || connectIp.isEmpty() ? host : connectIp,
                port, tls, "/", true).withPath(request.startLine.split(" ", 3)[1]);
        return dispatch(request, target, inboundProtocol);
    }

    private HttpCodec.Message dispatch(HttpCodec.Message request, Target target, String protocol) throws IOException {
        Exchange exchange = begin(request, target);
        exchange.protocol = protocol;
        long started = System.nanoTime();
        try {
            if (request.header("Upgrade") != null) throw new IOException("Extended CONNECT and WebSocket editing are not supported");
            if (inScope(target.host) && intercept.isEnabled()) {
                byte[] edited = intercept.intercept(exchange, request.raw());
                if (edited == null) {
                    exchange.error = "Dropped by user";
                    HttpCodec.Message dropped = errorMessage(403, exchange.error);
                    captureResponse(exchange, dropped);
                    return dropped;
                }
                request = HttpCodec.parseEditedRequest(edited);
                target = target.withPath(request.startLine.split(" ", 3)[1]);
                exchange.url = target.url(); exchange.method = request.method();
            }
            HttpCodec.Message response = perform(request, target, exchange, "HTTP/3".equals(protocol) ? "HTTP/3" : "AUTO");
            HttpCodec.stripHopHeaders(response);
            int status = response.status();
            HttpCodec.normalizeLength(response, request.method().equalsIgnoreCase("HEAD") || status == 204 || status == 304);
            captureResponse(exchange, response);
            return response;
        } catch (IOException failure) {
            exchange.error = safeMessage(failure);
            exchange.truncated |= exchange.error.contains("limit") || exchange.error.contains("exceeds");
            throw failure;
        } finally { exchange.durationMillis = elapsed(started); store.recordOwned(exchange); }
    }

    /** Only an idle gap before the first HTTP byte is an ordinary timeout; parsing keeps the full read timeout. */
    private static boolean awaitNextRequest(Socket client, InputStream input) throws IOException {
        client.setSoTimeout(KEEP_ALIVE_IDLE_TIMEOUT);
        try {
            input.mark(1);
            int first = input.read();
            if (first < 0) return false;
            input.reset();
            return true;
        } catch (SocketTimeoutException idle) {
            return false;
        } finally {
            client.setSoTimeout(READ_TIMEOUT);
        }
    }

    private boolean transact(HttpCodec.Message request, OutputStream output, Target target, boolean shouldClose) throws IOException {
        Exchange exchange = begin(request, target);
        long started = System.nanoTime();
        try {
            if (request.header("Upgrade") != null) throw new IOException("Protocol upgrades and WebSockets are not inspected in this version");
            if (inScope(target.host) && intercept.isEnabled()) {
                byte[] forwarded = intercept.intercept(exchange, request.raw());
                if (forwarded == null) {
                    exchange.error = "Dropped by user";
                    sendError(output, 403, exchange.error);
                    return false;
                }
                request = HttpCodec.parseEditedRequest(forwarded);
                if (target.fixed) {
                    target = target.withPath(request.startLine.split(" ", 3)[1]);
                } else target = targetFor(request);
                exchange.url = target.url();
                exchange.method = request.method();
            }
            HttpCodec.Message response = perform(request, target, exchange);
            HttpCodec.stripHopHeaders(response);
            int status = response.status();
            HttpCodec.normalizeLength(response, request.method().equalsIgnoreCase("HEAD") || status == 204 || status == 304);
            response.set("Connection", shouldClose ? "close" : "keep-alive");
            captureResponse(exchange, response);
            if (response.startLine.startsWith("HTTP/2")) response.startLine = "HTTP/1.1 " + status + " ";
            response.writeTo(output);
            return true;
        } catch (IOException e) {
            exchange.error = safeMessage(e);
            exchange.truncated |= exchange.error.contains("limit") || exchange.error.contains("exceeds");
            sendError(output, 502, exchange.error);
            return false;
        } finally {
            exchange.durationMillis = elapsed(started);
            store.recordOwned(exchange);
        }
    }

    private HttpCodec.Message perform(HttpCodec.Message request, Target target, Exchange exchange) throws IOException {
        return perform(request, target, exchange, "AUTO");
    }

    private HttpCodec.Message perform(HttpCodec.Message request, Target target, Exchange exchange, String protocol) throws IOException {
        return perform(request,target,exchange,protocol,false);
    }

    private HttpCodec.Message perform(HttpCodec.Message request, Target target, Exchange exchange, String protocol, boolean preserveHost) throws IOException {
        Semaphore budget = acquireRequestBudget();
        try { return performGranted(request, target, exchange, protocol, preserveHost); }
        finally { releaseRequestBudget(budget); }
    }

    private synchronized Semaphore acquireRequestBudget() throws IOException {
        CallCancellation.check();
        Semaphore budget=requestBudget;
        if(!budget.tryAcquire())throw new IOException("Sprig is busy: maximum "+connectionLimit+" concurrent upstream exchanges");
        inFlightRequests++;return budget;
    }
    private synchronized void releaseRequestBudget(Semaphore budget) {inFlightRequests--;budget.release();}

    private HttpCodec.Message performGranted(HttpCodec.Message request, Target target, Exchange exchange, String protocol, boolean preserveHost) throws IOException {
        HttpCodec.stripHopHeaders(request);
        request.startLine = request.method() + " " + target.path + " HTTP/1.1";
        if ((!target.fixed && !preserveHost) || request.header("Host") == null) request.set("Host", target.authority());
        String requestAuthority=preserveHost && request.header("Host")!=null ? request.header("Host") : target.authority();
        request.set("Connection", "close");
        HttpCodec.normalizeLength(request, false);
        captureRequest(exchange, request);
        if ("HTTP/3".equals(protocol)) {
            Http3Client client = http3Client;
            if (!target.tls) throw new IOException("HTTP/3 requires an https:// target URL");
            if (client == null) throw new IOException("HTTP/3 client is not configured");
            exchange.upstreamProtocol = "HTTP/3";
            return preserveHost ? client.execute(target.host,target.connectHost,target.port,request,requestAuthority)
                    : client.execute(target.host, target.connectHost, target.port, request);
        }
        Socket origin = connect(target, protocol);
        try {
            boolean h2 = origin instanceof SSLSocket ? "h2".equals(Alpn.selected((SSLSocket) origin)) : "HTTP/2".equals(protocol);
            if ("HTTP/2".equals(protocol) && !h2) throw new IOException("Origin did not negotiate requested HTTP/2");
            if (h2) {
                exchange.upstreamProtocol = "HTTP/2";
                return Http2Connection.exchange(new BufferedInputStream(origin.getInputStream(), 16 * 1024),
                        origin.getOutputStream(), origin, request, requestAuthority, target.tls ? "https" : "http");
            }
            request.writeTo(origin.getOutputStream());
            HttpCodec.Message response = HttpCodec.readResponse(new BufferedInputStream(origin.getInputStream(), 16 * 1024), request.method());
            exchange.upstreamProtocol = response.startLine.split(" ", 2)[0];
            return response;
        } finally { sockets.remove(origin); close(origin); }
    }

    private Socket connect(Target target) throws IOException {
        return connect(target, "AUTO");
    }

    private Socket connect(Target target, String protocol) throws IOException {
        Socket origin = new Socket();
        CallCancellation.track(origin);
        sockets.add(origin);
        try {
            // Android's Socket creates its descriptor lazily; protect() needs a real unconnected FD.
            origin.bind(null);
            if (protector != null && !protector.protect(origin)) throw new IOException("Cannot exclude upstream socket from VPN");
            origin.setTcpNoDelay(true);
            origin.setSoTimeout(READ_TIMEOUT);
            java.net.InetAddress resolved=com.ccs.sprig.core.net.HostResolver.resolve(target.connectHost,false);
            CallCancellation.check();
            origin.connect(new InetSocketAddress(resolved, target.port), CONNECT_TIMEOUT);
            if (target.tls) {
                SSLSocket tls = (SSLSocket) upstreamTls.createSocket(origin, target.host, target.port, true);
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                if ("HTTP/1.1".equals(protocol)) Alpn.configure(parameters, "http/1.1");
                else if ("HTTP/2".equals(protocol)) Alpn.configure(parameters, "h2");
                else Alpn.configure(parameters, "h2", "http/1.1");
                tls.setSSLParameters(parameters);
                tls.startHandshake();
                sockets.remove(origin);
                sockets.add(tls);
                origin = tls;
            }
            return origin;
        } catch (IOException | RuntimeException e) {
            sockets.remove(origin);
            close(origin);
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Cannot connect upstream", e);
        }
    }

    private void relay(Socket client, InputStream input, OutputStream output, Target target, byte[] prefix) throws IOException {
        // Raw forwarding must not create a second TLS layer around the client's TLS stream.
        Target raw = new Target(target.host, target.connectHost, target.port, false, "/", true);
        Socket origin = connect(raw);
        Exchange exchange = new Exchange();
        exchange.id = store.nextId(); exchange.timeMillis = System.currentTimeMillis();
        exchange.method = "TUNNEL"; exchange.protocol = "TCP"; exchange.tls = target.tls;
        exchange.url = (target.tls ? "tls://" : "tcp://") + target.authority();
        long started = System.nanoTime();
        try {
            origin.getOutputStream().write(prefix);
            origin.getOutputStream().flush();
            Future<?> upload = pumps.submit(() -> {
                try { copy(input, origin.getOutputStream()); origin.shutdownOutput(); }
                catch (IOException e) { close(origin); }
            });
            copy(origin.getInputStream(), output);
            // A peer's FIN closes one direction only; uploads may still finish after a response FIN.
            client.shutdownOutput();
            try { upload.get(READ_TIMEOUT, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Raw relay interrupted", e); }
            catch (ExecutionException e) { throw new IOException("Raw relay upload failed", e.getCause()); }
            catch (TimeoutException e) { upload.cancel(true); throw new IOException("Raw relay half-close timed out", e); }
        } catch (RejectedExecutionException e) { throw new IOException("Raw relay concurrency limit reached", e); }
        finally {
            exchange.durationMillis = elapsed(started);
            store.recordOwned(exchange);
            sockets.remove(origin);
            close(origin);
        }
    }

    private Exchange begin(HttpCodec.Message request, Target target) {
        Exchange exchange = new Exchange();
        exchange.id = store.nextId(); exchange.timeMillis = System.currentTimeMillis();
        exchange.method = request.method(); exchange.url = target.url(); exchange.tls = target.tls;
        captureRequest(exchange, request);
        return exchange;
    }

    private static void captureRequest(Exchange exchange, HttpCodec.Message request) {
        exchange.requestHeaders = request.headText();
        exchange.requestBody = capture(request.body);
        exchange.truncated |= request.body.length > CAPTURE_LIMIT;
    }

    private static void captureResponse(Exchange exchange, HttpCodec.Message response) throws IOException {
        exchange.status = response.status();
        exchange.responseHeaders = response.headText();
        exchange.responseBody = capture(response.body);
        exchange.truncated |= response.body.length > CAPTURE_LIMIT;
    }

    private void recordFailure(Target target, Exception failure) {
        Exchange exchange = new Exchange();
        exchange.id = store.nextId(); exchange.timeMillis = System.currentTimeMillis();
        exchange.method = "ERROR"; exchange.error = safeMessage(failure);
        exchange.url = target == null ? "proxy://local" : target.url();
        exchange.tls = target != null && target.tls;
        exchange.truncated = exchange.error.contains("limit") || exchange.error.contains("exceeds");
        store.recordOwned(exchange);
    }

    private boolean inScope(String host) {
        Set<String> allowed = scope;
        if (allowed.isEmpty()) return true;
        host = host.toLowerCase(Locale.ROOT);
        for (String suffix : allowed) if (host.equals(suffix) || host.endsWith("." + suffix)) return true;
        return false;
    }

    private static Target targetFor(HttpCodec.Message request) throws IOException {
        String path = request.startLine.split(" ", 3)[1];
        if (WebSocketHandshake.isWebSocket(request) && (path.startsWith("ws://") || path.startsWith("wss://")))
            return Target.url((path.startsWith("wss://") ? "https" : "http") + path.substring(path.indexOf(':')));
        if (path.startsWith("http://") || path.startsWith("https://")) return Target.url(path);
        if (!path.startsWith("/") && !path.equals("*")) throw new IOException("Unsupported request target");
        String host = request.header("Host");
        if (host == null) throw new IOException("HTTP request requires Host");
        return Target.authority(host, 80, false).withPath(path);
    }

    private static byte[] capture(byte[] bytes) { return Arrays.copyOf(bytes, Math.min(bytes.length, CAPTURE_LIMIT)); }
    private static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    private static boolean looksHttp(byte first) { return first >= 'A' && first <= 'Z'; }
    private static String safeMessage(Throwable throwable) {
        String text = throwable.getMessage();
        if (text == null || text.isEmpty()) text = throwable.getClass().getSimpleName();
        text = text.replace('\r', ' ').replace('\n', ' ');
        return text.length() > 512 ? text.substring(0, 512) : text;
    }

    private static void sendError(OutputStream output, int status, String message) throws IOException {
        byte[] body = ("Sprig: " + message + "\n").getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + (status == 403 ? " Forbidden" : status == 503 ? " Service Unavailable" : " Bad Gateway")
                + "\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        output.write(head.getBytes(StandardCharsets.US_ASCII));
        output.write(body); output.flush();
    }

    private static HttpCodec.Message errorMessage(int status, String error) {
        HttpCodec.Message message = new HttpCodec.Message(); message.startLine = "HTTP/1.1 " + status;
        message.body = ("Sprig: " + error + "\n").getBytes(StandardCharsets.UTF_8);
        message.set("Content-Type", "text/plain; charset=utf-8"); message.set("Content-Length", Integer.toString(message.body.length));
        return message;
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) { output.write(buffer, 0, count); output.flush(); }
    }

    private static ExecutorService executor(String name, int maximum) {
        ThreadFactory factory = runnable -> { Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread; };
        return new ThreadPoolExecutor(0, maximum, 45, TimeUnit.SECONDS, new SynchronousQueue<>(), factory);
    }

    private static void close(java.io.Closeable closeable) {
        if (closeable != null) try { closeable.close(); } catch (IOException ignored) { }
    }

    private static final class Target {
        final String host, connectHost, path;
        final int port;
        final boolean tls, fixed;
        Target(String host, String connectHost, int port, boolean tls, String path, boolean fixed) {
            this.host = host; this.connectHost = connectHost; this.port = port; this.tls = tls; this.path = path; this.fixed = fixed;
        }
        static Target url(String value) throws IOException {
            try {
                URI uri = new URI(value);
                boolean tls = "https".equalsIgnoreCase(uri.getScheme());
                if (!tls && !"http".equalsIgnoreCase(uri.getScheme())) throw new IOException("URL must use HTTP or HTTPS");
                if (uri.getHost() == null || uri.getUserInfo() != null) throw new IOException("URL requires a host without credentials");
                String host = CertificateAuthority.normalizeHost(uri.getHost());
                int port = uri.getPort() < 0 ? (tls ? 443 : 80) : uri.getPort();
                if (port < 1 || port > 65535) throw new IOException("Invalid URL port");
                String path = uri.getRawPath();
                if (path == null || path.isEmpty()) path = "/";
                if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
                return new Target(host, host, port, tls, path, false);
            } catch (URISyntaxException e) { throw new IOException("Invalid URL", e); }
        }
        static Target authority(String value, int defaultPort, boolean tls) throws IOException {
            Target parsed = url((tls ? "https://" : "http://") + value);
            if (!parsed.path.equals("/")) throw new IOException("Invalid proxy authority");
            return new Target(parsed.host, parsed.host, parsed.port, tls, "/", true);
        }
        Target withHost(String value, boolean useTls) { return new Target(value, connectHost, port, useTls, path, true); }
        Target withPath(String value) throws IOException {
            String path = value;
            if (value.startsWith("http://") || value.startsWith("https://")) {
                Target absolute = url(value);
                if (!absolute.host.equalsIgnoreCase(host) || absolute.port != port || absolute.tls != tls)
                    throw new IOException("Request target cannot escape its CONNECT origin; use Repeater for a new target");
                path = absolute.path;
            }
            if (!path.startsWith("/") && !path.equals("*")) throw new IOException("Unsupported request target");
            return new Target(host, connectHost, port, tls, path, fixed);
        }
        String authority() {
            String formatted = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
            return formatted + (port == (tls ? 443 : 80) ? "" : ":" + port);
        }
        String url() { return (tls ? "https://" : "http://") + authority() + path; }
    }
}
