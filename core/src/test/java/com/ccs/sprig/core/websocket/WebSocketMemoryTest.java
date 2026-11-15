package com.ccs.sprig.core.websocket;

import com.ccs.sprig.core.memory.MemoryBudget;
import java.io.IOException;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebSocketMemoryTest {
    @Test public void failedSessionAdmissionDoesNotLeakSlotOrLease()throws Exception{
        MemoryBudget budget=new MemoryBudget(100000);try(WebSocketHub hub=new WebSocketHub(null,budget)){
            hub.setMaxSessions(1);MemoryBudget.Reservation other=budget.tryReserve(MemoryBudget.Category.HTTP_BODY,20000);
            try{hub.reserve();fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("memory"));}assertFalse(hub.hasActive());assertEquals(20000,budget.snapshot().usedBytes);
            other.close();WebSocketHub.Reservation slot=hub.reserve();assertTrue(hub.hasActive());assertTrue(budget.snapshot().usedBytes>80000);slot.close();slot.close();assertFalse(hub.hasActive());assertEquals(0,budget.snapshot().usedBytes);
        }
    }
    @Test(timeout=5000) public void editedHoldStaysChargedUntilDeliveryClosesDecision()throws Exception{
        MemoryBudget budget=new MemoryBudget(1000);WebSocketInterceptor interceptor=new WebSocketInterceptor(budget);interceptor.setEnabled(true);ExecutorService worker=Executors.newSingleThreadExecutor();
        try{
            Future<WebSocketInterceptor.Decision> result=worker.submit(()->interceptor.interceptLeased(1,WebSocketHub.CLIENT_TO_SERVER,2,new byte[100],()->true));awaitPending(interceptor);
            assertEquals(356,budget.snapshot().usedBytes);interceptor.forward(interceptor.pending().get(0).id,new byte[200]);
            WebSocketInterceptor.Decision decision=result.get(1,TimeUnit.SECONDS);assertTrue(interceptor.pending().isEmpty());assertEquals(620,budget.snapshot().usedBytes);assertEquals(200,decision.payload.length);
            decision.close();decision.close();assertEquals(0,budget.snapshot().usedBytes);
        }finally{interceptor.cancelAll();worker.shutdownNow();}
    }
    @Test(timeout=5000) public void failedEditRemainsPendingAndCancelReleasesItsLease()throws Exception{
        MemoryBudget budget=new MemoryBudget(450);WebSocketInterceptor interceptor=new WebSocketInterceptor(budget);interceptor.setEnabled(true);ExecutorService worker=Executors.newSingleThreadExecutor();
        try{
            Future<byte[]> result=worker.submit(()->interceptor.intercept(1,WebSocketHub.CLIENT_TO_SERVER,2,new byte[100]));awaitPending(interceptor);
            try{interceptor.forward(interceptor.pending().get(0).id,new byte[100]);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("memory"));}
            assertEquals(1,interceptor.pending().size());assertEquals(356,budget.snapshot().usedBytes);interceptor.cancelSession(1);assertNull(result.get(1,TimeUnit.SECONDS));assertEquals(0,budget.snapshot().usedBytes);
        }finally{interceptor.cancelAll();worker.shutdownNow();}
    }
    @Test(timeout=8000) public void queuedPayloadPressureAndCloseReleaseAllOwners()throws Exception{
        MemoryBudget budget=new MemoryBudget(200000);CountDownLatch closed=new CountDownLatch(1);
        java.io.InputStream idle=new java.io.InputStream(){public int read()throws IOException{try{closed.await();return -1;}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IOException(error);}}};
        java.net.Socket socket=new java.net.Socket(){@Override public void setSoTimeout(int timeout){}@Override public void close(){closed.countDown();}};
        WebSocketHub hub=new WebSocketHub((url,headers)->new WebSocketHub.ClientTransport(url,"GET / HTTP/1.1\r\n\r\n","HTTP/1.1 101 Switching Protocols\r\n\r\n",socket,idle,new java.io.ByteArrayOutputStream(),null),budget);
        try{
            hub.interceptor().setEnabled(true);long id=hub.connect("ws://localhost/","").id;hub.sendBinary(id,new byte[60000]);awaitPending(hub.interceptor());hub.sendBinary(id,new byte[60000]);
            try{hub.sendBinary(id,new byte[60000]);fail("Third payload must not allocate a queued clone");}catch(IOException expected){assertTrue(expected.getMessage().contains("memory"));}
            assertTrue(budget.snapshot().usedBytes<=200000);assertTrue(budget.snapshot().categories.get(MemoryBudget.Category.WEBSOCKET_QUEUE)>=120000);
        }finally{hub.close();closed.countDown();}
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(budget.snapshot().usedBytes!=0&&System.nanoTime()<deadline)Thread.sleep(2);
        assertEquals(0,budget.snapshot().usedBytes);
    }
    @Test(timeout=5000) public void closeDuringManualHandshakeCannotPublishUnchargedLateSession()throws Exception{
        MemoryBudget budget=new MemoryBudget(100000);CountDownLatch opening=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService worker=Executors.newSingleThreadExecutor();
        WebSocketHub hub=new WebSocketHub((url,headers)->{opening.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}java.net.Socket socket=new java.net.Socket(){@Override public void setSoTimeout(int timeout){}@Override public void close(){}};return new WebSocketHub.ClientTransport(url,"GET / HTTP/1.1","HTTP/1.1 101",socket,new java.io.ByteArrayInputStream(new byte[0]),new java.io.ByteArrayOutputStream(),null);},budget);
        try{Future<?> connect=worker.submit(()->{try{hub.connect("ws://localhost/","");fail("Closed hub must reject a late handshake");}catch(IOException expected){assertTrue(expected.getMessage().contains("closed"));}});assertTrue(opening.await(1,TimeUnit.SECONDS));hub.close();release.countDown();connect.get(1,TimeUnit.SECONDS);assertEquals(0,budget.snapshot().usedBytes);assertFalse(hub.hasActive());assertTrue(hub.sessions().isEmpty());}
        finally{release.countDown();hub.close();worker.shutdownNow();}
    }
    private static void awaitPending(WebSocketInterceptor interceptor)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(interceptor.pending().isEmpty()&&System.nanoTime()<end)Thread.sleep(2);assertFalse(interceptor.pending().isEmpty());}
}
