package com.ccs.sprig.core.proxy;

import com.ccs.sprig.core.InterceptController;
import com.ccs.sprig.core.http2.Alpn;
import com.ccs.sprig.core.http2.Http2Connection;
import com.ccs.sprig.core.model.Exchange;
import com.ccs.sprig.core.model.SessionStore;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Independent JDK HTTP/2 client exercises the actual CONNECT -> TLS ALPN -> multiplexed MITM path. */
public class Http2ProxyTest {
    @ClassRule public static TemporaryFolder directory=new TemporaryFolder();
    @Test(timeout=30000) public void independentJdkClientNegotiatesHttp2ThroughMitmAndToOrigin() throws Exception {
        CertificateAuthority originCa=new CertificateAuthority(new java.io.File(directory.getRoot(),"h2-origin"));
        SessionStore history=new SessionStore();InterceptController intercept=new InterceptController();
        ProxyServer proxy=new ProxyServer(history,intercept,new java.io.File(directory.getRoot(),"h2-proxy"),socket->socket.isBound(),trusting(originCa.certificateDer()).getSocketFactory());
        ExecutorService clientWork=Executors.newFixedThreadPool(2);
        try(Origin origin=new Origin(originCa)) {
            proxy.start(0);
            HttpClient client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_2)
                    .connectTimeout(Duration.ofSeconds(5)).executor(clientWork)
                    .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1",proxy.getPort())))
                    .sslContext(trusting(proxy.getAuthority().certificateDer())).build();
            URI uri=URI.create("https://127.0.0.1:"+origin.port()+"/multiplex");
            byte[] bytes=new byte[180000];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i*17);
            HttpResponse<byte[]> response=client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(HttpClient.Version.HTTP_2,response.version());assertEquals(200,response.statusCode());assertArrayEquals(bytes,response.body());
            CompletableFuture<HttpResponse<String>> one=client.sendAsync(HttpRequest.newBuilder(uri.resolve("/one")).GET().build(),HttpResponse.BodyHandlers.ofString());
            CompletableFuture<HttpResponse<String>> two=client.sendAsync(HttpRequest.newBuilder(uri.resolve("/two")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals("GET /one HTTP/1.1",one.get(10,TimeUnit.SECONDS).body());assertEquals("GET /two HTTP/1.1",two.get(10,TimeUnit.SECONDS).body());
            for(int i=0;i<100&&history.size()<3;i++)Thread.sleep(10);
            assertEquals(3,history.size());
            for(Exchange exchange:history.snapshot()) {
                assertEquals("HTTP/2",exchange.protocol);assertEquals("HTTP/2",exchange.upstreamProtocol);assertEquals("",exchange.error);
            }
        } finally {proxy.stop();clientWork.shutdownNow();}
    }
    @Test(timeout=20000) public void forcedHttp2RepeaterUsesPriorKnowledgeForCleartextAndPreservesRedirect() throws Exception {
        SessionStore history=new SessionStore();ProxyServer proxy=new ProxyServer(history,new InterceptController(),new java.io.File(directory.getRoot(),"h2-replay"),socket->socket.isBound());
        java.net.ServerSocket server=new java.net.ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));ExecutorService handlers=Executors.newFixedThreadPool(1);
        CompletableFuture<Void> serving=CompletableFuture.runAsync(()->{
            try(Socket socket=server.accept()) {
                socket.setSoTimeout(5000);Http2Connection.serve(new BufferedInputStream(socket.getInputStream()),socket.getOutputStream(),socket,handlers,(request,authority,scheme)->{
                    HttpCodec.Message message=new HttpCodec.Message();message.startLine="HTTP/2 302";message.set("location","/do-not-follow");message.set("content-length","0");return message;
                });
            }catch(IOException ignored){}
        });
        try {
            Exchange exchange=proxy.replay("http://127.0.0.1:"+server.getLocalPort()+"/ignored","GET /actual HTTP/1.1\r\nHost: ignored\r\n\r\n".getBytes(StandardCharsets.US_ASCII),"HTTP/2");
            assertEquals(302,exchange.status);assertEquals("HTTP/2",exchange.upstreamProtocol);assertEquals("HTTP/2",exchange.protocol);
            assertTrue(exchange.url.endsWith("/actual"));assertTrue(exchange.responseHeaders.contains("location: /do-not-follow"));
        }finally{proxy.stop();server.close();handlers.shutdownNow();serving.get(3,TimeUnit.SECONDS);}
    }
    @Test(timeout=20000) public void explicitHostChangesH2AuthorityWithoutChangingTlsIdentityOrDestination() throws Exception {
        CertificateAuthority ca=new CertificateAuthority(new java.io.File(directory.getRoot(),"h2-host-origin"));SessionStore history=new SessionStore();ProxyServer proxy=new ProxyServer(history,new InterceptController(),new java.io.File(directory.getRoot(),"h2-host-proxy"),socket->socket.isBound(),trusting(ca.certificateDer()).getSocketFactory());
        try(Origin origin=new Origin(ca)) {
            Exchange exchange=proxy.replay("https://127.0.0.1:"+origin.port()+"/","GET /authority HTTP/1.1\r\nHost: alternate.unresolvable.test:8081\r\n\r\n".getBytes(StandardCharsets.US_ASCII),"HTTP/2",true);
            assertEquals(200,exchange.status);assertEquals("alternate.unresolvable.test:8081",origin.lastAuthority);assertTrue(exchange.url.startsWith("https://127.0.0.1:"));assertEquals("HTTP/2",exchange.upstreamProtocol);
        }finally{proxy.stop();}
    }
    private static SSLContext trusting(byte[] certificate)throws Exception {
        KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null);
        store.setCertificateEntry("test",CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certificate)));
        TrustManagerFactory managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());managers.init(store);
        SSLContext context=SSLContext.getInstance("TLS");context.init(null,managers.getTrustManagers(),null);return context;
    }
    private static final class Origin implements AutoCloseable {
        final SSLServerSocket server;final ExecutorService work=Executors.newFixedThreadPool(8),streams=Executors.newFixedThreadPool(4);
        final Set<Socket> sockets=ConcurrentHashMap.newKeySet();final Thread accepting;
        volatile String lastAuthority;
        Origin(CertificateAuthority authority)throws Exception {
            server=(SSLServerSocket)authority.serverContext("127.0.0.1").getServerSocketFactory().createServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
            SSLParameters parameters=server.getSSLParameters();parameters.setApplicationProtocols(new String[]{"h2"});server.setSSLParameters(parameters);
            accepting=new Thread(()->{while(!server.isClosed())try{SSLSocket socket=(SSLSocket)server.accept();sockets.add(socket);work.execute(()->serve(socket));}catch(IOException ignored){}});
            accepting.setDaemon(true);accepting.start();
        }
        int port(){return server.getLocalPort();}
        void serve(SSLSocket socket) {
            try {
                socket.setSoTimeout(10000);socket.startHandshake();assertEquals("h2",Alpn.selected(socket));
                Http2Connection.serve(new BufferedInputStream(socket.getInputStream()),socket.getOutputStream(),socket,streams,(request,host,scheme)->{
                    lastAuthority=host;
                    HttpCodec.Message message=new HttpCodec.Message();message.startLine="HTTP/2 200";
                    message.body=request.body.length==0?request.startLine.getBytes(StandardCharsets.US_ASCII):request.body;
                    message.set("content-length",Integer.toString(message.body.length));return message;
                });
            }catch(IOException ignored){}finally{sockets.remove(socket);try{socket.close();}catch(IOException ignored){}}
        }
        public void close()throws Exception{server.close();for(Socket socket:sockets)socket.close();work.shutdownNow();streams.shutdownNow();accepting.join(1000);}
    }
}
