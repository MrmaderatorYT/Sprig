package com.ccs.sprig.core.memory;

import com.ccs.sprig.core.model.Exchange;
import com.ccs.sprig.core.model.SessionStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class MemoryBudgetTest {
    @Test public void leasesResizeAtomicallyAndCloseIdempotently(){
        MemoryBudget budget=new MemoryBudget(100);MemoryBudget.Reservation first=budget.tryReserve(MemoryBudget.Category.HTTP_BODY,60);
        assertNotNull(first);assertNull(budget.tryReserve(MemoryBudget.Category.HISTORY,41));assertFalse(first.resize(101));assertEquals(60,first.bytes());
        assertTrue(first.resize(20));MemoryBudget.Reservation second=budget.tryReserve(MemoryBudget.Category.HISTORY,80);assertNotNull(second);
        assertFalse(first.resize(21));assertEquals(100,budget.snapshot().usedBytes);first.close();first.close();assertEquals(80,budget.snapshot().usedBytes);
        assertEquals(Long.valueOf(0),budget.snapshot().categories.get(MemoryBudget.Category.HTTP_BODY));second.close();assertEquals(0,budget.snapshot().usedBytes);assertEquals(100,budget.snapshot().peakBytes);
        try{first.resize(0);fail();}catch(IllegalStateException expected){}
        try{budget.snapshot().categories.put(MemoryBudget.Category.HISTORY,0L);fail();}catch(UnsupportedOperationException expected){}
    }
    @Test(timeout=10000) public void contendingOwnersNeverExceedGlobalLimit()throws Exception{
        MemoryBudget budget=new MemoryBudget(4096);ExecutorService workers=Executors.newFixedThreadPool(8);CountDownLatch ready=new CountDownLatch(8),start=new CountDownLatch(1);List<Future<?>> jobs=new ArrayList<>();
        try{for(int thread=0;thread<8;thread++){final int index=thread;jobs.add(workers.submit(()->{ready.countDown();start.await();for(int i=0;i<3000;i++){MemoryBudget.Reservation lease=budget.tryReserve(MemoryBudget.Category.values()[index%MemoryBudget.Category.values().length],1024);if(lease!=null){try{assertTrue(budget.snapshot().usedBytes<=4096);lease.resize(512);}finally{lease.close();lease.close();}}}return null;}));}
            assertTrue(ready.await(2,TimeUnit.SECONDS));start.countDown();for(Future<?> job:jobs)job.get(8,TimeUnit.SECONDS);
            assertEquals(0,budget.snapshot().usedBytes);assertTrue(budget.snapshot().peakBytes<=4096);
        }finally{start.countDown();workers.shutdownNow();}
    }
    @Test public void historiesCompeteWithoutEvictingEachOthersOwnedData(){
        MemoryBudget budget=new MemoryBudget(600);SessionStore first=new SessionStore(10,1000,budget),second=new SessionStore(10,1000,budget);
        Exchange exchange=new Exchange();exchange.requestBody=new byte[100];first.record(exchange);assertEquals(420,budget.snapshot().usedBytes);
        second.record(exchange);assertEquals(0,second.size());assertEquals(1,second.droppedRecords());assertEquals(1,first.size());
        first.clear();assertEquals(0,budget.snapshot().usedBytes);second.record(exchange);assertEquals(1,second.size());second.clear();assertEquals(0,budget.snapshot().usedBytes);
    }
    @Test public void replacementEvictionAndOversizeRecordsReleaseOwnership(){
        MemoryBudget budget=new MemoryBudget(900);SessionStore store=new SessionStore(2,1000,budget);Exchange e=new Exchange();e.id=7;e.responseBody=new byte[300];store.record(e);assertEquals(620,budget.snapshot().usedBytes);
        e.responseBody=new byte[100];store.record(e);assertEquals(420,budget.snapshot().usedBytes);e.id=8;store.record(e);assertEquals(840,budget.snapshot().usedBytes);
        e.id=9;store.record(e);assertEquals(2,store.size());assertEquals(840,budget.snapshot().usedBytes);
        e.responseBody=new byte[2000];store.record(e);assertEquals(0,store.size());assertEquals(0,budget.snapshot().usedBytes);
    }
    @Test public void ownedHistoryAvoidsDuplicateBodyCopiesButKeepsMetadataIsolated(){
        MemoryBudget budget=new MemoryBudget(4096);SessionStore store=new SessionStore(10,2048,budget);Exchange e=new Exchange();e.id=1;e.requestBody=new byte[100];store.recordOwned(e);
        assertSame(e.requestBody,store.snapshot().get(0).requestBody);e.url="changed after publication";assertEquals("",store.snapshot().get(0).url);assertEquals(420,budget.snapshot().usedBytes);store.clear();assertEquals(0,budget.snapshot().usedBytes);
    }
    @Test public void hugeRequestsCannotOverflowOrCorruptCounters(){
        MemoryBudget budget=new MemoryBudget(Long.MAX_VALUE);MemoryBudget.Reservation lease=budget.tryReserve(MemoryBudget.Category.SCANNER,Long.MAX_VALUE);
        assertNotNull(lease);assertNull(budget.tryReserve(MemoryBudget.Category.HISTORY,1));assertTrue(lease.resize(0));assertEquals(0,budget.snapshot().usedBytes);
        try{budget.tryReserve(MemoryBudget.Category.HISTORY,-1);fail();}catch(IllegalArgumentException expected){}lease.close();
    }
}
