package com.ccs.sprig.core.proxy;

import com.ccs.sprig.core.InterceptController;
import com.ccs.sprig.core.model.SessionStore;
import com.ccs.sprig.core.websocket.WebSocketFrames;
import com.ccs.sprig.core.websocket.WebSocketHandshake;
import com.ccs.sprig.core.websocket.WebSocketHub;
import com.ccs.sprig.core.websocket.WebSocketInterceptor;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class WebSocketProxyTest {
    @ClassRule public static TemporaryFolder directory=new TemporaryFolder();
    private static ProxyServer proxy(String name,SSLContext trust)throws Exception{return trust==null?new ProxyServer(new SessionStore(),new InterceptController(),new java.io.File(directory.getRoot(),name),socket->socket.isBound()):new ProxyServer(new SessionStore(),new InterceptController(),new java.io.File(directory.getRoot(),name),socket->socket.isBound(),trust.getSocketFactory());}
    @Test(timeout=30000) public void independentJdkWssClientUsesMitmAndReceivesCoalesced101ThenFragments()throws Exception {
        CertificateAuthority ca=new CertificateAuthority(new java.io.File(directory.getRoot(),"ws-origin-ca"));ProxyServer proxy=proxy("ws-proxy-ca",trusting(ca.certificateDer()));
        try(Fixture origin=new Fixture(ca.serverContext("127.0.0.1"),false)){
            proxy.start(0);Listener listener=new Listener();HttpClient client=HttpClient.newBuilder().sslContext(trusting(proxy.getAuthority().certificateDer())).proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1",proxy.getPort()))).connectTimeout(Duration.ofSeconds(5)).build();
            WebSocket ws=client.newWebSocketBuilder().buildAsync(URI.create("wss://127.0.0.1:"+origin.port()+"/chat"),listener).get(10,TimeUnit.SECONDS);
            assertEquals("welcome",listener.text.poll(5,TimeUnit.SECONDS));ws.sendText("hello",true).get(3,TimeUnit.SECONDS);assertEquals("hello",listener.text.poll(5,TimeUnit.SECONDS));
            byte[] payload=new byte[180000];for(int i=0;i<payload.length;i++)payload[i]=(byte)i;ws.sendBinary(ByteBuffer.wrap(payload),true).get(5,TimeUnit.SECONDS);assertArrayEquals(payload,listener.binary.poll(5,TimeUnit.SECONDS));
            awaitMessages(proxy.getWebSockets(),5);List<WebSocketHub.Message> messages=proxy.getWebSockets().messages(0);assertTrue(messages.stream().anyMatch(m->m.opcode==2&&m.truncated&&m.originalLength==payload.length));assertNull(origin.extensions);
            ws.sendClose(1000,"bye").get(5,TimeUnit.SECONDS);assertTrue(listener.closed.await(5,TimeUnit.SECONDS));
        }finally{proxy.stop();}
    }
    @Test(timeout=25000) public void heldDataDoesNotBlockPingAndCanBeEditedDroppedOrCancelled()throws Exception {
        ProxyServer proxy=proxy("ws-manual-ca",null);WebSocketHub hub=proxy.getWebSockets();
        try(Fixture origin=new Fixture(null,false)){
            WebSocketHub.Session session=hub.connect("ws://127.0.0.1:"+origin.port()+"/chat","");awaitMessages(hub,1);
            hub.interceptor().setEnabled(true);hub.sendText(session.id,"original");WebSocketInterceptor.Pending pending=awaitPending(hub,WebSocketHub.CLIENT_TO_SERVER);
            origin.ping();assertTrue("Ping/pong must remain live while data is held",origin.pong.await(3,TimeUnit.SECONDS));
            hub.interceptor().forward(pending.id,"edited".getBytes(StandardCharsets.UTF_8));WebSocketInterceptor.Pending response=awaitPending(hub,WebSocketHub.SERVER_TO_CLIENT);assertEquals("edited",new String(response.payload,StandardCharsets.UTF_8));hub.interceptor().forward(response.id,response.payload);
            assertEquals("edited",origin.received.poll(3,TimeUnit.SECONDS));hub.sendText(session.id,"drop");hub.interceptor().drop(awaitPending(hub,WebSocketHub.CLIENT_TO_SERVER).id);assertNull(origin.received.poll(200,TimeUnit.MILLISECONDS));
            hub.sendText(session.id,"cancel");awaitPending(hub,WebSocketHub.CLIENT_TO_SERVER);proxy.stop();assertTrue(hub.interceptor().pending().isEmpty());assertFalse(hub.hasActive());
        }finally{proxy.stop();}
    }
    @Test(timeout=25000) public void maximumLiveWsDoesNotRetainHttpWorkerOrOriginAdmission()throws Exception {
        ProxyServer proxy=proxy("ws-budget-ca",null);proxy.setConnectionLimit(1);
        try(Fixture origin=new Fixture(null,false)){
            proxy.start(0);Listener listener=new Listener();HttpClient client=HttpClient.newBuilder().proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1",proxy.getPort()))).build();
            WebSocket ws=client.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:"+origin.port()+"/chat"),listener).get(10,TimeUnit.SECONDS);assertEquals("welcome",listener.text.poll(3,TimeUnit.SECONDS));
            assertEquals(200,proxy.replay("http://127.0.0.1:"+origin.port()+"/ordinary","GET /ordinary HTTP/1.1\r\nHost: ignored\r\n\r\n".getBytes(StandardCharsets.US_ASCII)).status);
            int status=0;for(int attempt=0;attempt<20&&status!=200;attempt++){try(Socket socket=new Socket("127.0.0.1",proxy.getPort())){socket.setSoTimeout(3000);socket.getOutputStream().write(("GET http://127.0.0.1:"+origin.port()+"/captured HTTP/1.1\r\nHost: ignored\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));status=HttpCodec.readResponse(socket.getInputStream(),"GET").status();}if(status!=200)Thread.sleep(20);}assertEquals(200,status);
            ws.sendText("still alive",true).get(3,TimeUnit.SECONDS);assertEquals("still alive",listener.text.poll(3,TimeUnit.SECONDS));ws.sendClose(1000,"").get(3,TimeUnit.SECONDS);
        }finally{proxy.stop();}
    }
    @Test(timeout=20000) public void forged101IsNeverForwardedOrAdopted()throws Exception {
        ProxyServer proxy=proxy("ws-forged-ca",null);
        try(Fixture origin=new Fixture(null,true);Socket client=new Socket()){
            proxy.start(0);client.connect(new InetSocketAddress("127.0.0.1",proxy.getPort()));client.setSoTimeout(5000);
            HttpCodec.Message request=WebSocketHandshake.clientRequest(URI.create("ws://127.0.0.1:"+origin.port()+"/chat"),"",new SecureRandom());request.startLine="GET http://127.0.0.1:"+origin.port()+"/chat HTTP/1.1";request.writeTo(client.getOutputStream());
            assertEquals(502,HttpCodec.readResponse(client.getInputStream(),"GET").status());assertTrue(proxy.getWebSockets().sessions().isEmpty());
        }finally{proxy.stop();}
    }
    @Test(timeout=20000) public void closingOneManualSessionDoesNotCloseAnother()throws Exception {
        ProxyServer proxy=proxy("ws-select-ca",null);WebSocketHub hub=proxy.getWebSockets();
        try(Fixture origin=new Fixture(null,false)){
            WebSocketHub.Session first=hub.connect("ws://127.0.0.1:"+origin.port()+"/one","");WebSocketHub.Session second=hub.connect("ws://127.0.0.1:"+origin.port()+"/two","");awaitMessages(hub,2);
            hub.closeSession(first.id);for(int i=0;i<100&&hub.sessions().stream().anyMatch(s->s.id==first.id&&s.state.equals("OPEN"));i++)Thread.sleep(10);
            hub.sendText(second.id,"second");assertEquals("second",origin.received.poll(3,TimeUnit.SECONDS));awaitMessages(hub,4);
            assertTrue(hub.sessions().stream().anyMatch(s->s.id==second.id&&s.state.equals("OPEN")));
        }finally{proxy.stop();}
    }
    @Test(timeout=20000) public void fragmentedUtf8WithInterleavedPingIsAssembledThenInvalidContinuationCloses()throws Exception {
        ProxyServer proxy=proxy("ws-fragment-ca",null);WebSocketHub hub=proxy.getWebSockets();
        try(Fixture origin=new Fixture(null,false)){
            WebSocketHub.Session session=hub.connect("ws://127.0.0.1:"+origin.port()+"/fragments","");awaitMessages(hub,1);Fixture.Peer peer=origin.peers.get(0);byte[] text="A🙂B".getBytes(StandardCharsets.UTF_8);
            synchronized(peer){WebSocketFrames.write(peer.socket.getOutputStream(),false,false,1,Arrays.copyOfRange(text,0,3),new SecureRandom());peer.send(9,"in-fragment".getBytes(StandardCharsets.US_ASCII));WebSocketFrames.write(peer.socket.getOutputStream(),false,true,0,Arrays.copyOfRange(text,3,text.length),new SecureRandom());}
            awaitMessages(hub,3);assertTrue(hub.messages(session.id).stream().anyMatch(m->m.opcode==1&&new String(m.payload,StandardCharsets.UTF_8).equals("A🙂B")));assertTrue(origin.pong.await(2,TimeUnit.SECONDS));
            peer.send(0,new byte[]{'x'});for(int i=0;i<100&&hub.hasActive();i++)Thread.sleep(10);assertFalse(hub.hasActive());assertEquals(1002,hub.sessions().get(0).closeCode);
        }finally{proxy.stop();}
    }
    @Test(timeout=20000) public void wssOriginHostnameValidationIsNotBypassedByUpgrade()throws Exception {
        CertificateAuthority ca=new CertificateAuthority(new java.io.File(directory.getRoot(),"ws-wrong-origin"));ProxyServer proxy=proxy("ws-wrong-proxy",trusting(ca.certificateDer()));
        try(Fixture origin=new Fixture(ca.serverContext("wrong.test"),false)){
            try{proxy.getWebSockets().connect("wss://127.0.0.1:"+origin.port()+"/","");fail("A trusted CA must not bypass hostname verification");}catch(IOException expected){}
            assertTrue(proxy.getWebSockets().sessions().isEmpty());assertFalse(proxy.getWebSockets().hasActive());
        }finally{proxy.stop();}
    }
    @Test(timeout=25000) public void textAndCloseInOneWritePreserveCompleteTextBeforeClientClose()throws Exception {
        ProxyServer proxy=proxy("ws-close-order-ca",null);
        try(Fixture origin=new Fixture(null,false)){
            proxy.start(0);Listener listener=new Listener();HttpClient client=HttpClient.newBuilder().proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1",proxy.getPort()))).build();
            client.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:"+origin.port()+"/final"),listener).get(8,TimeUnit.SECONDS);assertEquals("welcome",listener.text.poll(3,TimeUnit.SECONDS));
            byte[] last=new byte[220000];Arrays.fill(last,(byte)'x');ByteArrayOutputStream wire=new ByteArrayOutputStream();WebSocketFrames.write(wire,false,true,1,last,new SecureRandom());WebSocketFrames.write(wire,false,true,8,WebSocketFrames.closePayload(1000,"done"),new SecureRandom());
            Fixture.Peer peer=origin.peers.get(0);synchronized(peer){peer.socket.getOutputStream().write(wire.toByteArray());peer.socket.getOutputStream().flush();}
            assertEquals(new String(last,StandardCharsets.UTF_8),listener.text.poll(5,TimeUnit.SECONDS));assertTrue(listener.closed.await(5,TimeUnit.SECONDS));
        }finally{proxy.stop();}
    }
    private static void awaitMessages(WebSocketHub hub,int count)throws Exception{for(int i=0;i<300&&hub.messages(0).size()<count;i++)Thread.sleep(10);assertTrue("Missing WebSocket history",hub.messages(0).size()>=count);}
    private static WebSocketInterceptor.Pending awaitPending(WebSocketHub hub,String direction)throws Exception{for(int i=0;i<300;i++){for(WebSocketInterceptor.Pending pending:hub.interceptor().pending())if(pending.direction.equals(direction))return pending;Thread.sleep(10);}throw new AssertionError("No pending "+direction);}
    private static SSLContext trusting(byte[] certificate)throws Exception{KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null);store.setCertificateEntry("test",CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certificate)));TrustManagerFactory managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());managers.init(store);SSLContext context=SSLContext.getInstance("TLS");context.init(null,managers.getTrustManagers(),null);return context;}
    private static final class Listener implements WebSocket.Listener {final BlockingQueue<String> text=new LinkedBlockingQueue<>();final BlockingQueue<byte[]> binary=new LinkedBlockingQueue<>();final CountDownLatch closed=new CountDownLatch(1);StringBuilder partialText=new StringBuilder();ByteArrayOutputStream partialBinary=new ByteArrayOutputStream();public void onOpen(WebSocket ws){ws.request(1);}public CompletionStage<?> onText(WebSocket ws,CharSequence value,boolean last){partialText.append(value);if(last){text.add(partialText.toString());partialText.setLength(0);}ws.request(1);return CompletableFuture.completedFuture(null);}public CompletionStage<?> onBinary(WebSocket ws,ByteBuffer data,boolean last){byte[] bytes=new byte[data.remaining()];data.get(bytes);partialBinary.write(bytes,0,bytes.length);if(last){binary.add(partialBinary.toByteArray());partialBinary.reset();}ws.request(1);return CompletableFuture.completedFuture(null);}public CompletionStage<?> onClose(WebSocket ws,int code,String reason){closed.countDown();return CompletableFuture.completedFuture(null);}}
    private static final class Fixture implements AutoCloseable {
        final ServerSocket server;final boolean forged;final Thread accept;final ExecutorService workers=Executors.newFixedThreadPool(6);final Set<Socket> sockets=ConcurrentHashMap.newKeySet();final CopyOnWriteArrayList<Peer> peers=new CopyOnWriteArrayList<>();final BlockingQueue<String> received=new LinkedBlockingQueue<>();final CountDownLatch pong=new CountDownLatch(1);volatile String extensions;
        Fixture(SSLContext context,boolean forged)throws IOException{this.forged=forged;server=context==null?new ServerSocket(0,8,InetAddress.getByName("127.0.0.1")):context.getServerSocketFactory().createServerSocket(0,8,InetAddress.getByName("127.0.0.1"));accept=new Thread(()->{while(!server.isClosed())try{Socket socket=server.accept();sockets.add(socket);workers.execute(()->serve(socket));}catch(IOException ignored){}});accept.setDaemon(true);accept.start();}
        int port(){return server.getLocalPort();}
        void ping()throws IOException{for(Peer peer:peers)peer.send(9,"probe".getBytes(StandardCharsets.US_ASCII));}
        void serve(Socket socket){try{socket.setSoTimeout(10000);BufferedInputStream input=new BufferedInputStream(socket.getInputStream(),16384);HttpCodec.Message request=HttpCodec.readRequest(input,socket.getOutputStream());if(!WebSocketHandshake.isWebSocket(request)){socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".getBytes(StandardCharsets.US_ASCII));return;}WebSocketHandshake.validateRequest(request);extensions=request.header("Sec-WebSocket-Extensions");Peer peer=new Peer(socket);peers.add(peer);ByteArrayOutputStream initial=new ByteArrayOutputStream();String head="HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+(forged?"forged":WebSocketHandshake.accept(request.header("Sec-WebSocket-Key")))+"\r\n\r\n";initial.write(head.getBytes(StandardCharsets.US_ASCII));WebSocketFrames.write(initial,false,true,1,"welcome".getBytes(StandardCharsets.UTF_8),new SecureRandom());socket.getOutputStream().write(initial.toByteArray());socket.getOutputStream().flush();int fragmented=-1;ByteArrayOutputStream body=null;
            for(WebSocketFrames.Frame frame;(frame=WebSocketFrames.read(input,true))!=null;){if(frame.opcode==8){peer.send(8,frame.payload);return;}if(frame.opcode==9){peer.send(10,frame.payload);continue;}if(frame.opcode==10){pong.countDown();continue;}if(frame.opcode!=0){fragmented=frame.opcode;body=new ByteArrayOutputStream();}body.write(frame.payload);if(frame.fin){byte[] payload=body.toByteArray();if(fragmented==1)received.add(new String(payload,StandardCharsets.UTF_8));peer.send(fragmented,payload);fragmented=-1;body=null;}}
        }catch(IOException ignored){}finally{sockets.remove(socket);peers.removeIf(p->p.socket==socket);try{socket.close();}catch(IOException ignored){}}}
        public void close()throws Exception{server.close();for(Socket socket:sockets)socket.close();workers.shutdownNow();accept.join(1000);}
        static final class Peer{final Socket socket;Peer(Socket socket){this.socket=socket;}synchronized void send(int opcode,byte[] bytes)throws IOException{WebSocketFrames.write(socket.getOutputStream(),false,true,opcode,bytes,new SecureRandom());}}
    }
}
