package com.ccs.sprig.core.net;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class LatestTaskExecutorTest {
    @Test public void commandFloodRetainsOnlyNewestPendingAndFinalCleanup()throws Exception {
        LatestTaskExecutor executor=new LatestTaskExecutor("fixture-lifecycle");
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger ran=new AtomicInteger(),cleanup=new AtomicInteger();
        executor.executeLatest(()->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new AssertionError(e);}});
        assertTrue(entered.await(2,TimeUnit.SECONDS));
        try{for(int i=0;i<10000;i++){int id=i;assertTrue(executor.executeLatest(()->ran.set(id+1)));assertEquals(1,executor.pendingCount());}
            executor.closeAfter(cleanup::incrementAndGet);assertEquals(1,executor.pendingCount());assertFalse(executor.executeLatest(()->fail("closed")));executor.closeAfter(()->fail("duplicate cleanup"));
        }finally{release.countDown();}
        assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS));assertEquals(0,ran.get());assertEquals(1,cleanup.get());
    }
    @Test public void replacedCommandsNeverRunAndLatestExecutesAfterActive()throws Exception {
        LatestTaskExecutor executor=new LatestTaskExecutor("fixture-lifecycle");CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(1);AtomicInteger value=new AtomicInteger();
        executor.executeLatest(()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(entered.await(2,TimeUnit.SECONDS));
        executor.executeLatest(()->value.set(-1));executor.executeLatest(()->{value.set(7);done.countDown();});release.countDown();assertTrue(done.await(2,TimeUnit.SECONDS));executor.close();assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS));assertEquals(7,value.get());
    }
}
