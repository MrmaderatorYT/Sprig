package com.ccs.sprig.core.http3;

import com.ccs.sprig.core.InterceptController;
import com.ccs.sprig.core.model.Exchange;
import com.ccs.sprig.core.model.SessionStore;
import com.ccs.sprig.core.proxy.CertificateAuthority;
import com.ccs.sprig.core.proxy.HttpCodec;
import com.ccs.sprig.core.proxy.ProxyServer;
import com.ccs.sprig.core.tunnel.IpPacket;
import java.io.Closeable;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** A real UDP boundary simulates TUN packet translation; both sides negotiate QUIC/TLS/H3. */
public class Http3GatewayTest {
    @ClassRule public static TemporaryFolder temporary=new TemporaryFolder();
    private static CertificateAuthority originCa;
    private static synchronized CertificateAuthority origin() throws Exception {if(originCa==null)originCa=new CertificateAuthority(temporary.newFolder());return originCa;}
    private static KeyStore trust(CertificateAuthority authority) throws Exception {
        KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null);
        trust.setCertificateEntry("root",java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(authority.certificateDer())));return trust;
    }
    private static HttpCodec.Message request() {HttpCodec.Message m=new HttpCodec.Message();m.startLine="POST /gateway HTTP/1.1";m.body=new byte[]{0,13,10,1,(byte)255};m.set("Content-Length","5");return m;}
    @Test(timeout=30000) public void originalDestinationAndH3ProtocolSurviveFullMitmPath() throws Exception {
        try(Fixture fixture=new Fixture(true)) {
            HttpCodec.Message response=fixture.execute(trust(fixture.proxy.getAuthority()),request());
            assertEquals(200,response.status());assertArrayEquals(request().body,response.body);fixture.awaitHistory();
            Exchange exchange=fixture.store.snapshot().get(0);assertEquals("HTTP/3",exchange.protocol);assertEquals("HTTP/3",exchange.upstreamProtocol);
            assertTrue(exchange.url.contains("localhost:"+fixture.port()+"/gateway"));assertNull(fixture.failure.get());
        }
    }
    @Test(timeout=30000) public void disabledMitmPreservesOriginCaEndToEnd() throws Exception {
        try(Fixture fixture=new Fixture(false)) {
            assertEquals(200,fixture.execute(trust(origin()),request()).status());
            assertEquals(0,fixture.store.size());assertNull(fixture.failure.get());
        }
    }
    @Test(timeout=30000) public void interceptedH3RequestCanBeEditedBeforeOriginDispatch() throws Exception {
        try(Fixture fixture=new Fixture(true)) {
            fixture.intercept.setEnabled(true);
            CompletableFuture<HttpCodec.Message> response=CompletableFuture.supplyAsync(()->{try{return fixture.execute(trust(fixture.proxy.getAuthority()),request());}catch(Exception e){throw new RuntimeException(e);}});
            long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(fixture.intercept.pending().isEmpty()&&System.nanoTime()<limit)Thread.sleep(10);
            assertFalse(fixture.intercept.pending().isEmpty());InterceptController.Pending pending=fixture.intercept.pending().get(0);
            String edited=new String(pending.rawRequest,StandardCharsets.ISO_8859_1).replace("/gateway","/edited");
            fixture.intercept.forward(pending.id,edited.getBytes(StandardCharsets.ISO_8859_1));
            assertEquals(200,response.get(10,TimeUnit.SECONDS).status());fixture.awaitHistory();assertTrue(fixture.store.snapshot().get(0).url.endsWith("/edited"));
        }
    }
    private static final class Fixture implements Closeable {
        final SessionStore store=new SessionStore();final InterceptController intercept=new InterceptController();
        final ProxyServer proxy;final Http3Server backend;final Http3Gateway gateway;final DatagramSocket boundary;
        final Thread pump;final AtomicReference<Throwable> failure=new AtomicReference<>();volatile boolean active=true;
        Fixture(boolean mitm) throws Exception {
            backend=new Http3Server(origin().serverKeyStore("localhost"),new DatagramSocket(new InetSocketAddress("127.0.0.1",0)),request->{
                if(!request.message.startLine.contains("/gateway")&&!request.message.startLine.contains("/edited"))throw new java.io.IOException("Wrong path");
                HttpCodec.Message out=new HttpCodec.Message();out.startLine="HTTP/1.1 200 OK";out.body=request.message.body;out.set("Content-Length",Integer.toString(out.body.length));return out;
            });
            proxy=new ProxyServer(store,intercept,temporary.newFolder(),socket->true);proxy.setMitmEnabled(mitm);proxy.setHttp3Client(new Http3Client(socket->true,trust(origin())));proxy.start(0);
            gateway=new Http3Gateway(proxy,socket->true,2);boundary=new DatagramSocket(new InetSocketAddress("127.0.0.1",0));
            pump=new Thread(()->{
                byte[] buffer=new byte[1500];
                while(active)try {
                    DatagramPacket datagram=new DatagramPacket(buffer,buffer.length);boundary.receive(datagram);
                    byte[] payload=Arrays.copyOf(datagram.getData(),datagram.getLength());InetSocketAddress client=(InetSocketAddress)datagram.getSocketAddress();
                    byte[] raw=IpPacket.udp(IpPacket.address("10.89.0.2"),IpPacket.address("127.0.0.1"),client.getPort(),backend.getPort(),payload);
                    assertTrue(gateway.handle(IpPacket.parse(raw,raw.length),reply->{
                        IpPacket packet=IpPacket.parse(reply,reply.length);if(packet==null)throw new java.io.IOException("Invalid reply IP packet");
                        boundary.send(new DatagramPacket(packet.payload,packet.payload.length,client));
                    }));
                }catch(Exception|AssertionError error){if(active)failure.set(error);}
            },"test-quic-boundary");pump.setDaemon(true);pump.start();
        }
        int port(){return backend.getPort();}
        HttpCodec.Message execute(KeyStore trust,HttpCodec.Message request) throws Exception {
            tech.kwik.core.QuicClientConnection connection=tech.kwik.core.QuicClientConnection.newBuilder().host("localhost").port(backend.getPort())
                    .applicationProtocol("h3").customTrustStore(trust).maxOpenPeerInitiatedUnidirectionalStreams(3)
                    .maxOpenPeerInitiatedBidirectionalStreams(0).connectTimeout(java.time.Duration.ofSeconds(5))
                    .socketFactory(destination->new RedirectSocket(boundary.getLocalPort(),backend.getPort())).build();
            try {
                Http3Session session=new Http3Session(connection,false);connection.setPeerInitiatedStreamCallback(session::acceptControl);
                connection.connect();session.start();tech.kwik.core.QuicStream stream=connection.createStream(true);
                Http3Codec.writeRequest(stream.getOutputStream(),request,"localhost:"+backend.getPort());
                return Http3Codec.readResponse(stream.getInputStream(),request.method());
            }finally{connection.close();}
        }
        void awaitHistory() throws Exception {long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(store.size()==0&&System.nanoTime()<limit)Thread.sleep(10);assertEquals(1,store.size());}
        @Override public void close(){active=false;boundary.close();intercept.cancelAll();gateway.close();proxy.stop();backend.close();try{pump.join(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    }
    /** Simulates the kernel's destination-preserving VPN packet route without configuring host interfaces. */
    private static final class RedirectSocket extends DatagramSocket {
        private final int boundaryPort,originPort;
        RedirectSocket(int boundaryPort,int originPort) throws java.net.SocketException {super(new InetSocketAddress("127.0.0.1",0));this.boundaryPort=boundaryPort;this.originPort=originPort;}
        @Override public void send(DatagramPacket packet) throws java.io.IOException {
            super.send(new DatagramPacket(packet.getData(),packet.getOffset(),packet.getLength(),new InetSocketAddress("127.0.0.1",boundaryPort)));
        }
        @Override public void receive(DatagramPacket packet) throws java.io.IOException {super.receive(packet);packet.setPort(originPort);}
    }
}
