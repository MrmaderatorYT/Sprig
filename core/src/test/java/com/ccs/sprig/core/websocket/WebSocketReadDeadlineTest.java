package com.ccs.sprig.core.websocket;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebSocketReadDeadlineTest {
    @Test(timeout=5000) public void idleWaitIsAllowedButPartialFrameGetsAnAbsoluteDeadline()throws Exception {
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){CountDownLatch send=new CountDownLatch(1);
            CompletableFuture<Void> peer=CompletableFuture.runAsync(()->{try(Socket socket=server.accept()){socket.setSoTimeout(3000);send.await();socket.getOutputStream().write(0x81);socket.getOutputStream().flush();assertEquals(-1,socket.getInputStream().read());}catch(Exception error){throw new RuntimeException(error);}});
            try(WebSocketHub hub=new WebSocketHub((url,headers)->{Socket socket=new Socket("127.0.0.1",server.getLocalPort());return new WebSocketHub.ClientTransport(url,"","",socket,socket.getInputStream(),socket.getOutputStream(),null);},60)){
                hub.connect("ws://fixture/","");Thread.sleep(180);assertTrue("Idle between frames must not expire",hub.hasActive());send.countDown();
                for(int i=0;i<200&&hub.hasActive();i++)Thread.sleep(5);assertFalse(hub.hasActive());assertTrue(hub.sessions().get(0).error.contains("frame read timed out"));peer.get(2,TimeUnit.SECONDS);
            }finally{send.countDown();}
        }
    }
    @Test(timeout=5000) public void completedFrameCancelsItsDeadlineBeforeTheNextIdleWait()throws Exception {
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
            CompletableFuture<Void> peer=CompletableFuture.runAsync(()->{try(Socket socket=server.accept()){socket.setSoTimeout(3000);WebSocketFrames.write(socket.getOutputStream(),false,true,1,"complete".getBytes(StandardCharsets.UTF_8),new SecureRandom());socket.getInputStream().read();}catch(Exception error){throw new RuntimeException(error);}});
            try(WebSocketHub hub=new WebSocketHub((url,headers)->{Socket socket=new Socket("127.0.0.1",server.getLocalPort());return new WebSocketHub.ClientTransport(url,"","",socket,socket.getInputStream(),socket.getOutputStream(),null);},60)){
                hub.connect("ws://fixture/","");for(int i=0;i<200&&hub.messages(0).isEmpty();i++)Thread.sleep(5);assertFalse(hub.messages(0).isEmpty());Thread.sleep(180);assertTrue("A completed frame's timer must be removed",hub.hasActive());hub.closeAll();peer.get(2,TimeUnit.SECONDS);
            }
        }
    }
}
