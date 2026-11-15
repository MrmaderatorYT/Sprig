package com.ccs.sprig.core.intruder;

import com.ccs.sprig.core.InterceptController;
import com.ccs.sprig.core.model.SessionStore;
import com.ccs.sprig.core.proxy.HttpCodec;
import com.ccs.sprig.core.proxy.ProxyServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class IntruderProxyIntegrationTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    @Test(timeout=20000) public void realPostRetainsAuthenticationAndRecalculatesBinaryLength()throws Exception {
        AtomicReference<Throwable> failure=new AtomicReference<>();List<byte[]> bodies=Collections.synchronizedList(new ArrayList<>());
        try(ServerSocket origin=new ServerSocket(0,8,InetAddress.getLoopbackAddress())) {
            Thread server=new Thread(()->{try{for(int i=0;i<2;i++)try(Socket peer=origin.accept()){
                HttpCodec.Message request=HttpCodec.readRequest(peer.getInputStream(),peer.getOutputStream());
                assertEquals("sid=fixture-secret",request.header("Cookie"));assertEquals("Bearer fixture-token",request.header("Authorization"));assertEquals("4",request.header("Content-Length"));bodies.add(request.body);
                peer.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".getBytes(StandardCharsets.US_ASCII));
            }}catch(Throwable error){failure.set(error);}});server.setDaemon(true);server.start();
            ProxyServer proxy=new ProxyServer(new SessionStore(),new InterceptController(),temporary.newFolder(),socket->true);
            try {
                String template="POST / HTTP/1.1\r\nHost: ignored\r\nCookie: sid=fixture-secret\r\nAuthorization: Bearer fixture-token\r\nContent-Length: 999\r\n\r\n\u0001§x§\u0002";
                IntruderConfig config=new IntruderConfig.Builder("http://127.0.0.1:"+origin.getLocalPort()+"/",IntruderPlanTest.raw(template)).payloadSets(Collections.singletonList(PayloadSource.ofHexLines("00FF\n0D0A"))).concurrency(1).intervalMillis(0).requestBudget(2).build();
                IntruderAttack attack=IntruderEngine.start(proxy,config);IntruderAttackTest.await(attack);server.join(2000);assertNull(failure.get());assertEquals(2,bodies.size());assertArrayEquals(new byte[]{1,0,(byte)255,2},bodies.get(0));assertArrayEquals(new byte[]{1,13,10,2},bodies.get(1));assertEquals(2,attack.stats().completed);assertEquals(0,attack.stats().errors);
            }finally{proxy.stop();}
        }
    }
    @Test(timeout=15000) public void cancelImmediatelyClosesOnlyIntruderOriginSocket()throws Exception {
        CountDownLatch received=new CountDownLatch(1),closed=new CountDownLatch(1);
        try(ServerSocket origin=new ServerSocket(0,8,InetAddress.getLoopbackAddress())) {
            Thread server=new Thread(()->{try(Socket peer=origin.accept()){
                HttpCodec.readRequest(peer.getInputStream(),peer.getOutputStream());received.countDown();if(peer.getInputStream().read()<0)closed.countDown();
            }catch(IOException ignored){closed.countDown();}});server.setDaemon(true);server.start();
            ProxyServer proxy=new ProxyServer(new SessionStore(),new InterceptController(),temporary.newFolder(),socket->true);
            try {
                IntruderConfig config=new IntruderConfig.Builder("http://127.0.0.1:"+origin.getLocalPort()+"/",IntruderPlanTest.raw("GET /?q=§x§ HTTP/1.1\r\nHost: ignored\r\n\r\n")).payloadSets(Collections.singletonList(PayloadSource.ofLines("1\n2"))).concurrency(1).intervalMillis(0).build();
                IntruderAttack attack=IntruderEngine.start(proxy,config);assertTrue(received.await(3,TimeUnit.SECONDS));attack.cancel();assertTrue("Cancel must close the actual network socket",closed.await(1,TimeUnit.SECONDS));IntruderAttackTest.await(attack);assertEquals(1,attack.stats().dispatched);
            }finally{proxy.stop();}
        }
    }
}
