package com.ccs.sprig.core.websocket;

import java.io.IOException;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebSocketInterceptorTest {
    @Test(timeout=3000) public void expiredHoldReleasesItsQueueAndWorker()throws Exception {
        WebSocketInterceptor interceptor=new WebSocketInterceptor(30);interceptor.setEnabled(true);
        try{interceptor.intercept(1,WebSocketHub.CLIENT_TO_SERVER,1,new byte[]{'x'});fail("Hold must expire");}
        catch(IOException timedOut){assertTrue(timedOut.getMessage().contains("timed out"));}
        assertTrue(interceptor.pending().isEmpty());
    }
    @Test(timeout=3000) public void disablingInterceptionForwardsOriginalAndSessionCancelDrops()throws Exception {
        WebSocketInterceptor interceptor=new WebSocketInterceptor();interceptor.setEnabled(true);ExecutorService worker=Executors.newSingleThreadExecutor();
        try{Future<byte[]> first=worker.submit(()->interceptor.intercept(1,WebSocketHub.CLIENT_TO_SERVER,1,new byte[]{'a'}));await(interceptor);interceptor.setEnabled(false);assertArrayEquals(new byte[]{'a'},first.get(1,TimeUnit.SECONDS));
            interceptor.setEnabled(true);Future<byte[]> second=worker.submit(()->interceptor.intercept(2,WebSocketHub.SERVER_TO_CLIENT,2,new byte[]{1}));await(interceptor);interceptor.cancelSession(2);assertNull(second.get(1,TimeUnit.SECONDS));assertTrue(interceptor.pending().isEmpty());
        }finally{worker.shutdownNow();}
    }
    @Test(timeout=3000) public void closeBeforePendingInsertionCannotCreateAnUncancelledHold()throws Exception {
        WebSocketInterceptor interceptor=new WebSocketInterceptor();interceptor.setEnabled(true);java.util.concurrent.atomic.AtomicBoolean open=new java.util.concurrent.atomic.AtomicBoolean(true);
        // Deterministically represents the delivery worker passing its initial OPEN check,
        // followed by the reader setting CLOSING and cancelling before insertion starts.
        assertTrue(open.get());open.set(false);interceptor.cancelSession(7);
        long start=System.nanoTime();assertNull(interceptor.intercept(7,WebSocketHub.CLIENT_TO_SERVER,1,new byte[]{'x'},open::get));
        assertTrue(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(1));assertTrue(interceptor.pending().isEmpty());
        interceptor.setEnabled(false);assertArrayEquals(new byte[]{'y'},interceptor.intercept(7,WebSocketHub.CLIENT_TO_SERVER,1,new byte[]{'y'},open::get));
    }
    private static void await(WebSocketInterceptor interceptor)throws InterruptedException{for(int i=0;i<100&&interceptor.pending().isEmpty();i++)Thread.sleep(5);assertFalse(interceptor.pending().isEmpty());}
}
