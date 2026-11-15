package com.ccs.sprig.core.net;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

public class CallCancellationTest {
    @Test public void closesOnlyScopedBlockedCallAndLeavesUnrelatedSocketAlive() throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0);Socket independent=new Socket("127.0.0.1",server.getLocalPort());Socket independentPeer=server.accept()) {
            CallCancellation.Token token=new CallCancellation.Token();CountDownLatch reading=new CountDownLatch(1);
            Future<Boolean> blocked=worker.submit(()->{
                try(CallCancellation.Scope scope=CallCancellation.enter(token);Socket socket=new Socket("127.0.0.1",server.getLocalPort())) {
                    CallCancellation.track(socket);reading.countDown();socket.getInputStream().read();return false;
                }catch(IOException expected){return true;}
            });
            try(Socket peer=server.accept()) {
                assertTrue(reading.await(2,TimeUnit.SECONDS));token.cancel();assertTrue(blocked.get(2,TimeUnit.SECONDS));
                independent.setSoTimeout(1000);independentPeer.getOutputStream().write(42);assertEquals(42,independent.getInputStream().read());
            }
        } finally {worker.shutdownNow();}
    }
    @Test public void cancellationBeforeRegistrationClosesImmediatelyAndScopeRestoresParent() throws Exception {
        CallCancellation.Token parent=new CallCancellation.Token(),child=new CallCancellation.Token();AtomicBoolean parentClosed=new AtomicBoolean(),childClosed=new AtomicBoolean();
        try(CallCancellation.Scope outer=CallCancellation.enter(parent)) {
            CallCancellation.track(()->parentClosed.set(true));child.cancel();
            try(CallCancellation.Scope inner=CallCancellation.enter(child)) {
                try{CallCancellation.track(()->childClosed.set(true));fail();}catch(IOException expected){}
            }
            assertTrue(childClosed.get());assertFalse(parentClosed.get());CallCancellation.check();
        }
        assertTrue(parentClosed.get());CallCancellation.check();
    }
    @Test public void completedScopeReleasesTrackedResourcesExactlyOnce() throws Exception {
        java.util.concurrent.atomic.AtomicInteger count=new java.util.concurrent.atomic.AtomicInteger();CallCancellation.Token token=new CallCancellation.Token();
        try(CallCancellation.Scope scope=CallCancellation.enter(token)){Closeable handle=count::incrementAndGet;CallCancellation.track(handle);CallCancellation.track(handle);}
        token.cancel();assertEquals(1,count.get());
    }
}
