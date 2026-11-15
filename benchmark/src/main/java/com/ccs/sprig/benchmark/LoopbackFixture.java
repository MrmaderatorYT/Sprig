package com.ccs.sprig.benchmark;

import com.ccs.sprig.core.proxy.HttpCodec;
import com.ccs.sprig.core.websocket.WebSocketFrames;
import com.ccs.sprig.core.websocket.WebSocketHandshake;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Local-only deterministic HTTP echo + RFC6455 echo. Never resolves or contacts another host. */
final class LoopbackFixture implements AutoCloseable {
    static final String REVISION="sprig-loopback-v1";
    private final ServerSocket server=new ServerSocket();
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(0,12,10,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(r,"benchmark-fixture");t.setDaemon(true);return t;});
    private final Thread accept;
    private final String token;
    LoopbackFixture()throws IOException {this(InetAddress.getByAddress(new byte[]{127,0,0,1}),0,"");}
    LoopbackFixture(InetAddress bind,int port,String token)throws IOException {
        this.token=token;server.setReuseAddress(true);server.bind(new InetSocketAddress(bind,port),32);
        accept=new Thread(()->{while(!server.isClosed())try{Socket socket=server.accept();socket.setSoTimeout(3000);socket.setTcpNoDelay(true);sockets.add(socket);try{workers.execute(()->serve(socket));}catch(RuntimeException busy){sockets.remove(socket);socket.close();}}catch(IOException error){if(!server.isClosed())close();}},"benchmark-fixture-accept");accept.setDaemon(true);accept.start();
    }
    int port(){return server.getLocalPort();}
    String url(){return "http://127.0.0.1:"+port();}
    private void serve(Socket socket) {
        try(Socket owned=socket){BufferedInputStream input=new BufferedInputStream(owned.getInputStream(),16384);HttpCodec.Message request=HttpCodec.readRequest(input,null);if(request==null)return;
            if(!token.isEmpty()&&!request.startLine.split(" ",3)[1].equals("/sprig-fixture?token="+token))throw new IOException("Fixture token mismatch");
            if(WebSocketHandshake.isWebSocket(request)){
                WebSocketHandshake.validateRequest(request);String head="HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+WebSocketHandshake.accept(request.header("Sec-WebSocket-Key"))+"\r\n\r\n";owned.getOutputStream().write(head.getBytes(StandardCharsets.ISO_8859_1));owned.getOutputStream().flush();
                for(;;){WebSocketFrames.Frame frame=WebSocketFrames.read(input,true);if(frame==null)return;WebSocketFrames.write(owned.getOutputStream(),false,frame.fin,frame.opcode==9?10:frame.opcode,frame.payload,null);if(frame.opcode==8)return;}
            }
            byte[] body=request.method().equals("POST")?request.body:!token.isEmpty()?("sprig-fixture-v1:"+token).getBytes(StandardCharsets.UTF_8):"<!doctype html><html><head><title>Sprig local fixture</title></head><body>Deterministic local scanner baseline. No external links, redirects or credentials.</body></html>".getBytes(StandardCharsets.UTF_8);
            HttpCodec.Message response=new HttpCodec.Message();response.startLine="HTTP/1.1 200 OK";response.headers.add(new String[]{"Content-Type",request.method().equals("POST")?"application/octet-stream":"text/html; charset=utf-8"});response.headers.add(new String[]{"Cache-Control","no-store"});response.headers.add(new String[]{"Connection","close"});response.body=body;HttpCodec.normalizeLength(response,false);response.writeTo(owned.getOutputStream());
        }catch(IOException ignored){/* Client cancellation/closed sockets are expected; clients validate outcomes. */}finally{sockets.remove(socket);}
    }
    @Override public void close(){try{server.close();}catch(IOException ignored){}for(Socket socket:sockets)try{socket.close();}catch(IOException ignored){}sockets.clear();workers.shutdownNow();}
    boolean awaitClosed()throws InterruptedException{accept.join(3000);return !accept.isAlive()&&workers.awaitTermination(3,TimeUnit.SECONDS);}
}
