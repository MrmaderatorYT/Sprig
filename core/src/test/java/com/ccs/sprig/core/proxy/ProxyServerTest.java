package com.ccs.sprig.core.proxy;

import com.ccs.sprig.core.InterceptController;
import com.ccs.sprig.core.model.Exchange;
import com.ccs.sprig.core.model.SessionStore;
import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ProxyServerTest {
    @ClassRule public static TemporaryFolder shared = new TemporaryFolder();
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private SessionStore store;
    private InterceptController intercept;
    private ProxyServer proxy;
    private Fixture fixture;
    private final AtomicInteger protectedSockets = new AtomicInteger();

    @Before public void prepare() { store = new SessionStore(); intercept = new InterceptController(); }
    @After public void close() throws Exception {
        intercept.cancelAll();
        if (proxy != null) proxy.stop();
        if (fixture != null) fixture.close();
    }

    private void startProxy(SSLContext upstreamTrust) throws Exception {
        File ca = new File(shared.getRoot(), "proxy-ca");
        SocketProtector protector = socket -> {
            assertTrue("VPN protect requires a materialized descriptor before connect", socket.isBound());
            assertFalse("Protect must happen before connect", socket.isConnected());
            protectedSockets.incrementAndGet(); return true;
        };
        proxy = upstreamTrust == null ? new ProxyServer(store, intercept, ca, protector)
                : new ProxyServer(store, intercept, ca, protector, upstreamTrust.getSocketFactory());
        proxy.start(0);
    }

    @Test(timeout=20000) public void forwardsAndRecordsTwoRequestsOverOneClientConnection() throws Exception {
        fixture = new Fixture(null);
        startProxy(null);
        try (Socket client = client()) {
            send(client, "GET http://127.0.0.1:" + fixture.port() + "/one?q=1 HTTP/1.1\r\nHost: ignored\r\n\r\n");
            HttpCodec.Message first = HttpCodec.readResponse(client.getInputStream(), "GET");
            assertEquals(200, first.status());
            assertEquals("GET /one?q=1 HTTP/1.1", new String(first.body, StandardCharsets.US_ASCII));
            send(client, "GET http://127.0.0.1:" + fixture.port() + "/two HTTP/1.1\r\nHost: ignored\r\nConnection: close\r\n\r\n");
            assertEquals(200, HttpCodec.readResponse(client.getInputStream(), "GET").status());
        }
        awaitHistory(2);
        assertEquals(2, store.size());
        assertTrue(store.snapshot().get(0).url.endsWith("/two"));
        assertTrue(protectedSockets.get() >= 2);
    }

    @Test(timeout=20000) public void transparentConnectDetectsPlainHttp() throws Exception {
        fixture = new Fixture(null);
        startProxy(null);
        try (Socket client = client()) {
            connect(client, "127.0.0.1:" + fixture.port());
            send(client, "GET /plain HTTP/1.1\r\nHost: fixture.test\r\nConnection: close\r\n\r\n");
            HttpCodec.Message result = HttpCodec.readResponse(client.getInputStream(), "GET");
            assertEquals(200, result.status());
            assertEquals("GET /plain HTTP/1.1", new String(result.body, StandardCharsets.US_ASCII));
            assertEquals("fixture.test", fixture.lastHost);
        }
        awaitHistory(1);
        assertFalse(store.snapshot().get(0).tls);
    }

    @Test(timeout=20000) public void idleKeepAliveClosesWithout502OrErrorHistory() throws Exception {
        fixture = new Fixture(null);
        startProxy(null);
        try (Socket client = client()) {
            send(client, "GET http://127.0.0.1:" + fixture.port() + "/idle HTTP/1.1\r\nHost: ignored\r\n\r\n");
            assertEquals(200, HttpCodec.readResponse(client.getInputStream(), "GET").status());
            assertEquals("Idle connection should close quietly", -1, client.getInputStream().read());
        }
        assertEquals(1, store.size());
        assertEquals("", store.snapshot().get(0).error);
        assertEquals("GET", store.snapshot().get(0).method);
    }

    @Test(timeout=20000) public void editedInterceptRecalculatesLengthBeforeForwarding() throws Exception {
        fixture = new Fixture(null);
        startProxy(null);
        intercept.setEnabled(true);
        CompletableFuture<HttpCodec.Message> response = CompletableFuture.supplyAsync(() -> {
            try (Socket client = client()) {
                send(client, "POST http://127.0.0.1:" + fixture.port() + "/old HTTP/1.1\r\nHost: ignored\r\nContent-Length: 1\r\nConnection: close\r\n\r\na");
                return HttpCodec.readResponse(client.getInputStream(), "POST");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        InterceptController.Pending pending = awaitPending();
        intercept.forward(pending.id, ("POST http://127.0.0.1:" + fixture.port() + "/changed HTTP/1.1\r\nHost: ignored\r\nContent-Length: 1\r\n\r\nlonger").getBytes(StandardCharsets.ISO_8859_1));
        assertEquals("POST /changed HTTP/1.1", new String(response.get(10, TimeUnit.SECONDS).body, StandardCharsets.US_ASCII));
        awaitHistory(1);
        Exchange recorded = store.snapshot().get(0);
        assertEquals("longer", new String(recorded.requestBody, StandardCharsets.US_ASCII));
        assertTrue(recorded.requestHeaders.contains("Content-Length: 6"));
        assertTrue(recorded.url.endsWith("/changed"));
    }

    @Test(timeout=30000) public void tlsMitmUsesSniAndValidatesTrustedUpstream() throws Exception {
        CertificateAuthority upstream = new CertificateAuthority(new File(shared.getRoot(), "origin-ca"));
        fixture = new Fixture(upstream.serverContext("fixture.test"));
        startProxy(trusting(upstream.certificateDer()));
        try (Socket client = client()) {
            connect(client, "127.0.0.1:" + fixture.port());
            SSLContext clientContext = trusting(proxy.getAuthority().certificateDer());
            try (SSLSocket tls = (SSLSocket) clientContext.getSocketFactory().createSocket(client, "fixture.test", fixture.port(), true)) {
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                tls.setSSLParameters(parameters);
                tls.setSoTimeout(10000);
                tls.startHandshake();
                send(tls, "GET /secure HTTP/1.1\r\nHost: fixture.test\r\nConnection: close\r\n\r\n");
                HttpCodec.Message response = HttpCodec.readResponse(tls.getInputStream(), "GET");
                assertEquals(200, response.status());
                assertEquals("GET /secure HTTP/1.1", new String(response.body, StandardCharsets.US_ASCII));
            }
        }
        awaitHistory(1);
        assertTrue(store.snapshot().get(0).tls);
        assertTrue(store.snapshot().get(0).url.startsWith("https://fixture.test:"));
        assertEquals("", store.snapshot().get(0).error);
    }

    @Test(timeout=30000) public void trustedCertificateWithWrongHostnameIsRejected() throws Exception {
        CertificateAuthority upstream = new CertificateAuthority(new File(shared.getRoot(), "origin-ca"));
        fixture = new Fixture(upstream.serverContext("wrong-host.test"));
        startProxy(trusting(upstream.certificateDer()));
        try (Socket client = client()) {
            connect(client, "127.0.0.1:" + fixture.port());
            try (SSLSocket tls = (SSLSocket) trusting(proxy.getAuthority().certificateDer()).getSocketFactory()
                    .createSocket(client, "fixture.test", fixture.port(), true)) {
                tls.setSoTimeout(10000);
                tls.startHandshake();
                send(tls, "GET /must-fail HTTP/1.1\r\nHost: fixture.test\r\nConnection: close\r\n\r\n");
                assertEquals(502, HttpCodec.readResponse(tls.getInputStream(), "GET").status());
            }
        }
        awaitHistory(1);
        assertFalse(store.snapshot().get(0).error.isEmpty());
        assertEquals(0, fixture.requests.get());
    }

    @Test(timeout=30000) public void disabledMitmRelaysOriginalTlsCertificate() throws Exception {
        CertificateAuthority upstream = new CertificateAuthority(new File(shared.getRoot(), "origin-ca"));
        fixture = new Fixture(upstream.serverContext("fixture.test"));
        startProxy(null);
        proxy.setMitmEnabled(false);
        try (Socket client = client()) {
            connect(client, "127.0.0.1:" + fixture.port());
            // Trust only the origin CA. An accidental MITM certificate therefore fails the handshake.
            try (SSLSocket tls = (SSLSocket) trusting(upstream.certificateDer()).getSocketFactory()
                    .createSocket(client, "fixture.test", fixture.port(), true)) {
                tls.setSoTimeout(10000);
                tls.startHandshake();
                send(tls, "GET /bypassed HTTP/1.1\r\nHost: fixture.test\r\nConnection: close\r\n\r\n");
                assertEquals(200, HttpCodec.readResponse(tls.getInputStream(), "GET").status());
            }
        }
        awaitHistory(1);
        assertEquals("TUNNEL", store.snapshot().get(0).method);
    }

    @Test(timeout=20000) public void droppingInterceptNeverContactsOrigin() throws Exception {
        fixture = new Fixture(null);
        startProxy(null);
        intercept.setEnabled(true);
        CompletableFuture<HttpCodec.Message> response = CompletableFuture.supplyAsync(() -> {
            try (Socket client = client()) {
                send(client, "GET http://127.0.0.1:" + fixture.port() + "/drop HTTP/1.1\r\nHost: ignored\r\nConnection: close\r\n\r\n");
                return HttpCodec.readResponse(client.getInputStream(), "GET");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        intercept.drop(awaitPending().id);
        assertEquals(403, response.get(10, TimeUnit.SECONDS).status());
        awaitHistory(1);
        assertEquals("Dropped by user", store.snapshot().get(0).error);
        assertEquals(0, fixture.requests.get());
        assertEquals(0, protectedSockets.get());
    }

    @Test(timeout=20000) public void replayDoesNotEnterInterceptQueue() throws Exception {
        fixture = new Fixture(null);
        startProxy(null);
        intercept.setEnabled(true);
        Exchange result = proxy.replay("http://127.0.0.1:" + fixture.port() + "/url-path-ignored",
                "GET /replayed HTTP/1.1\r\nHost: ignored\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        assertEquals(200, result.status);
        assertTrue(intercept.pending().isEmpty());
        assertEquals("GET /replayed HTTP/1.1", new String(result.responseBody, StandardCharsets.US_ASCII));
    }
    @Test(timeout=20000) public void explicitRawHostOptionKeepsUrlRoutingAndDefaultRewriteBehavior() throws Exception {
        fixture=new Fixture(null);startProxy(null);
        String url="http://127.0.0.1:"+fixture.port()+"/";byte[] request="GET /raw-host HTTP/1.1\r\nHost: virtual.unresolvable.test:8081\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        Exchange preserved=proxy.replay(url,request,"HTTP/1.1",true);assertEquals(200,preserved.status);assertEquals("virtual.unresolvable.test:8081",fixture.lastHost);assertTrue(preserved.url.startsWith(url));
        assertEquals(200,proxy.replay(url,request,"HTTP/1.1").status);assertEquals("127.0.0.1:"+fixture.port(),fixture.lastHost);
    }

    @Test(timeout=20000) public void rawRelayAllowsUploadAfterOriginHalfClose() throws Exception {
        startProxy(null);
        try (ServerSocket origin = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<String> receivedAfterFin = CompletableFuture.supplyAsync(() -> {
                try (Socket accepted = origin.accept()) {
                    accepted.setSoTimeout(10000);
                    byte[] first = new byte[6];
                    HttpCodec.readExact(accepted.getInputStream(), first, 0, first.length);
                    accepted.getOutputStream().write("reply".getBytes(StandardCharsets.US_ASCII));
                    accepted.getOutputStream().flush();
                    accepted.shutdownOutput();
                    byte[] ack = new byte[3];
                    HttpCodec.readExact(accepted.getInputStream(), ack, 0, ack.length);
                    return new String(ack, StandardCharsets.US_ASCII);
                } catch (IOException e) { throw new RuntimeException(e); }
            });
            try (Socket client = client()) {
                connect(client, "127.0.0.1:" + origin.getLocalPort());
                send(client, "!hello");
                byte[] reply = new byte[5];
                HttpCodec.readExact(client.getInputStream(), reply, 0, reply.length);
                assertEquals("reply", new String(reply, StandardCharsets.US_ASCII));
                assertEquals(-1, client.getInputStream().read());
                send(client, "ack");
                client.shutdownOutput();
                assertEquals("ack", receivedAfterFin.get(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test(timeout=10000) public void caIdentityPersistsAndFingerprintMatches() throws Exception {
        File directory = temporary.newFolder("authority");
        CertificateAuthority first = new CertificateAuthority(directory);
        CertificateAuthority second = new CertificateAuthority(directory);
        assertArrayEquals(first.certificateDer(), second.certificateDer());
        assertEquals(first.fingerprint(), second.fingerprint());
        java.security.cert.X509Certificate root = (java.security.cert.X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(first.certificateDer()));
        assertTrue(root.getBasicConstraints() >= 0);
        assertTrue(root.getKeyUsage()[5]);
        root.verify(root.getPublicKey());
    }

    private Socket client() throws IOException {
        Socket client = new Socket("127.0.0.1", proxy.getPort());
        client.setSoTimeout(10000);
        return client;
    }
    private static void connect(Socket socket, String authority) throws Exception {
        send(socket, "CONNECT " + authority + " HTTP/1.1\r\nHost: " + authority + "\r\nX-Sprig-Transparent: 1\r\n\r\n");
        assertEquals(200, HttpCodec.readHead(socket.getInputStream()).status());
    }
    private static void send(Socket socket, String request) throws IOException {
        socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
        socket.getOutputStream().flush();
    }
    private static SSLContext trusting(byte[] certificate) throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null);
        store.setCertificateEntry("fixture", CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certificate)));
        TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, managers.getTrustManagers(), null);
        return context;
    }
    private InterceptController.Pending awaitPending() throws Exception {
        for (int i = 0; i < 200; i++) {
            if (!intercept.pending().isEmpty()) return intercept.pending().get(0);
            Thread.sleep(10);
        }
        throw new AssertionError("Request did not enter interception queue");
    }
    private void awaitHistory(int count) throws Exception {
        for (int i = 0; i < 200 && store.size() < count; i++) Thread.sleep(10);
        assertTrue("Expected completed history", store.size() >= count);
    }

    private static final class Fixture implements AutoCloseable {
        final ServerSocket server;
        final Thread thread;
        final AtomicInteger requests = new AtomicInteger();
        volatile String lastHost;
        Fixture(SSLContext context) throws IOException {
            server = context == null ? new ServerSocket() : context.getServerSocketFactory().createServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(10000);
                        HttpCodec.Message request = HttpCodec.readRequest(socket.getInputStream(), null);
                        if (request == null) continue;
                        requests.incrementAndGet();
                        lastHost = request.header("Host");
                        byte[] body = request.startLine.getBytes(StandardCharsets.US_ASCII);
                        OutputStream output = socket.getOutputStream();
                        // A malicious hop header must not remove the normalized framing sent to a keep-alive client.
                        output.write(("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: Content-Length\r\nContent-Type: text/plain\r\n\r\n"
                                + Integer.toHexString(body.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                        output.write(body);
                        output.write("\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        output.flush();
                    } catch (IOException ignored) { }
                }
            }, "sprig-fixture");
            thread.setDaemon(true); thread.start();
        }
        int port() { return server.getLocalPort(); }
        @Override public void close() throws Exception { server.close(); thread.join(1000); }
    }
}
