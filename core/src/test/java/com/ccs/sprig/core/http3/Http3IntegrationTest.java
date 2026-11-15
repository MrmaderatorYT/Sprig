package com.ccs.sprig.core.http3;

import com.ccs.sprig.core.proxy.CertificateAuthority;
import com.ccs.sprig.core.proxy.HttpCodec;
import java.io.IOException;
import java.security.KeyStore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class Http3IntegrationTest {
    @ClassRule public static TemporaryFolder temporary=new TemporaryFolder();
    private static CertificateAuthority authority;
    private static synchronized CertificateAuthority authority() throws Exception {
        if(authority==null)authority=new CertificateAuthority(temporary.newFolder());return authority;
    }
    private static KeyStore trust() throws Exception {
        KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null);
        java.security.cert.Certificate[] chain=authority().serverKeyStore("localhost").getCertificateChain("leaf");trust.setCertificateEntry("root",chain[chain.length-1]);return trust;
    }
    private static HttpCodec.Message request(){HttpCodec.Message m=new HttpCodec.Message();m.startLine="POST /real-h3 HTTP/1.1";m.body=new byte[]{0,1,13,10,(byte)255};m.set("Content-Length",Integer.toString(m.body.length));m.set("X-Sprig-Test","quic");return m;}
    @Test(timeout=30000) public void realQuicTls13QpackRequestAndResponse() throws Exception {
        AtomicInteger protectedSockets=new AtomicInteger();
        try(Http3Server server=new Http3Server(authority().serverKeyStore("localhost"),request->{
            assertEquals("POST /real-h3 HTTP/1.1",request.message.startLine);assertEquals("quic",request.message.header("x-sprig-test"));
            HttpCodec.Message response=new HttpCodec.Message();response.startLine="HTTP/1.1 200 OK";response.body=request.message.body;response.set("Content-Length",Integer.toString(response.body.length));return response;
        });Http3Client client=new Http3Client(socket->{assertTrue(socket.isBound());protectedSockets.incrementAndGet();return true;},trust())) {
            HttpCodec.Message result=client.execute("localhost","127.0.0.1",server.getPort(),request());
            assertEquals(200,result.status());assertTrue(result.startLine.startsWith("HTTP/3"));assertArrayEquals(request().body,result.body);assertEquals(1,protectedSockets.get());
        }
    }
    @Test(timeout=30000) public void wrongHostnameAndUntrustedOriginAreRejected() throws Exception {
        try(Http3Server server=new Http3Server(authority().serverKeyStore("localhost"),request->{throw new AssertionError("Untrusted connection must not reach HTTP handler");});
            Http3Client trusted=new Http3Client(socket->true,trust());Http3Client system=new Http3Client(socket->true)) {
            try{trusted.execute("wrong.fixture.test","127.0.0.1",server.getPort(),request());fail("Wrong hostname accepted");}catch(IOException expected){}
            try{system.execute("localhost","127.0.0.1",server.getPort(),request());fail("Untrusted CA accepted");}catch(IOException expected){}
        }
    }
    @Test(timeout=30000) public void authorityOverrideKeepsNetworkDestinationAndTlsIdentityPinned() throws Exception {
        AtomicReference<String> observedAuthority=new AtomicReference<>();AtomicInteger received=new AtomicInteger();
        try(Http3Server server=new Http3Server(authority().serverKeyStore("localhost"),incoming->{
            received.incrementAndGet();observedAuthority.set(incoming.authority);
            assertEquals(incoming.authority,incoming.message.header("host"));
            HttpCodec.Message response=new HttpCodec.Message();response.startLine="HTTP/1.1 204 No Content";return response;
        });Http3Client client=new Http3Client(socket->true,trust())) {
            // This authority is deliberately unrelated to the fixture's address and certificate.
            assertEquals(204,client.execute("localhost","127.0.0.1",server.getPort(),request(),"alternate.fixture.test:8443").status());
            assertEquals("alternate.fixture.test:8443",observedAuthority.get());assertEquals(1,received.get());
            assertEquals(204,client.execute("localhost","127.0.0.1",server.getPort(),request()).status());
            assertEquals("localhost:"+server.getPort(),observedAuthority.get());assertEquals(2,received.get());
            // Matching the HTTP authority to the certificate cannot bypass the URL's TLS name.
            try{client.execute("wrong.fixture.test","127.0.0.1",server.getPort(),request(),"localhost");fail("Authority override changed TLS identity");}catch(IOException expected){}
            assertEquals(2,received.get());
        }
    }
    @Test(timeout=30000) public void scopedCancelClosesOnlyBlockedQuicRequestAndClientRemainsReusable()throws Exception {
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        com.ccs.sprig.core.net.CallCancellation.Token token=new com.ccs.sprig.core.net.CallCancellation.Token();
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(Http3Server server=new Http3Server(authority().serverKeyStore("localhost"),incoming->{
            if(incoming.message.startLine.contains("/blocked ")){entered.countDown();try{release.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}
            HttpCodec.Message response=new HttpCodec.Message();response.startLine="HTTP/1.1 200 OK";response.body=new byte[]{1,2,3};response.set("Content-Length","3");return response;
        });Http3Client client=new Http3Client(socket->true,trust())) {
            java.util.concurrent.Future<Boolean> blocked=worker.submit(()->{try(com.ccs.sprig.core.net.CallCancellation.Scope ignored=com.ccs.sprig.core.net.CallCancellation.enter(token)){
                HttpCodec.Message request=request();request.startLine="POST /blocked HTTP/1.1";
                try{client.execute("localhost","127.0.0.1",server.getPort(),request);return false;}catch(IOException expected){return true;}
            }});
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));token.cancel();assertTrue(blocked.get(3,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(200,client.execute("localhost","127.0.0.1",server.getPort(),request()).status());
        }finally{release.countDown();token.cancel();worker.shutdownNow();}
    }

}
