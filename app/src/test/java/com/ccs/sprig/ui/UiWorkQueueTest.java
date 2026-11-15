package com.ccs.sprig.ui;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class UiWorkQueueTest {
    @Test public void overloadAndShutdownRejectWithoutRunningWorkOnCaller()throws Exception{
        ThreadPoolExecutor queue=UiWorkQueue.create("fixture-ui-queue",2);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger executed=new AtomicInteger(),rejected=new AtomicInteger();
        try{assertTrue(UiWorkQueue.submit(queue,()->{entered.countDown();await(release);},rejected::incrementAndGet));assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertTrue(UiWorkQueue.submit(queue,executed::incrementAndGet,rejected::incrementAndGet));assertTrue(UiWorkQueue.submit(queue,executed::incrementAndGet,rejected::incrementAndGet));
            for(int i=0;i<1000;i++)assertFalse(UiWorkQueue.submit(queue,()->fail("Rejected action must never run on caller"),rejected::incrementAndGet));
            assertEquals(1,queue.getPoolSize());assertEquals(2,queue.getQueue().size());assertEquals(1000,rejected.get());assertEquals(0,executed.get());
            release.countDown();queue.shutdown();assertTrue(queue.awaitTermination(2,TimeUnit.SECONDS));assertEquals(2,executed.get());assertFalse(UiWorkQueue.submit(queue,()->fail("Shutdown action must not run"),rejected::incrementAndGet));assertEquals(1001,rejected.get());
        }finally{release.countDown();queue.shutdownNow();}
    }
    private static void await(CountDownLatch latch){try{latch.await();}catch(InterruptedException error){Thread.currentThread().interrupt();}}
}
