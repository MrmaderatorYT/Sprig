package com.ccs.sprig.core.intruder;

import com.ccs.sprig.core.memory.MemoryBudget;
import com.ccs.sprig.core.model.Exchange;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class IntruderMemoryTest {
    private static IntruderConfig.Builder config(){return new IntruderConfig.Builder("http://fixture.test/","GET /?q=§x§ HTTP/1.1\r\nHost: fixture.test\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)).payloadSets(Collections.singletonList(PayloadSource.numeric(0,49,1,0))).requestBudget(50).intervalMillis(0).concurrency(2).evidenceBytes(1000);}
    @Test(timeout=5000) public void retainedResultsContendWithConfigAndForgetReleasesAllMemory()throws Exception{
        MemoryBudget budget=new MemoryBudget(12000);IntruderEngine engine=new IntruderEngine((url,raw,protocol)->{
            Exchange response=new Exchange();response.status=200;response.requestHeaders="GET / HTTP/1.1\r\n\r\n";response.responseHeaders="HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n";response.responseBody=new byte[1000];return response;
        },budget);
        IntruderAttack attack=engine.start(config().build());try{
            await(attack);assertEquals(50,attack.stats().completed);assertTrue(attack.stats().evicted>0);assertFalse(attack.results().isEmpty());assertTrue(budget.snapshot().usedBytes<=12000);assertTrue(budget.snapshot().peakBytes<=12000);
            assertTrue("Terminal runs retain their explicitly budgeted export/repeater state",budget.snapshot().usedBytes>0);
        }finally{if(!attack.isTerminal()){attack.cancel();await(attack);}IntruderEngine.forget(attack.id);}
        assertEquals(0,budget.snapshot().usedBytes);
    }
    @Test public void rejectedConfigurationDoesNotStartTransportOrLeakLease(){
        MemoryBudget budget=new MemoryBudget(1000);AtomicInteger sent=new AtomicInteger();IntruderEngine engine=new IntruderEngine((url,raw,protocol)->{sent.incrementAndGet();return new Exchange();},budget);
        try{engine.start(config().build());fail();}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("memory"));}
        assertEquals(0,sent.get());assertEquals(0,budget.snapshot().usedBytes);
    }
    @Test public void processorCapturedBuffersAreIncludedAndBounded(){
        PayloadProcessor prefix=PayloadProcessors.prefix(new byte[64000]);assertTrue(prefix.estimatedBytes()>64000);
        PayloadProcessor replace=PayloadProcessors.replace(new byte[64000],new byte[64000]);assertTrue(replace.estimatedBytes()>128000);
        try{PayloadProcessors.replace(new byte[65537],new byte[1]);fail();}catch(IllegalArgumentException expected){}
        try{PayloadProcessors.replace(new byte[1],new byte[65537]);fail();}catch(IllegalArgumentException expected){}
        MemoryBudget budget=new MemoryBudget(32000);IntruderEngine engine=new IntruderEngine((url,raw,protocol)->new Exchange(),budget);
        try{engine.start(config().processors(Collections.singletonList(prefix)).build());fail();}catch(IllegalStateException expected){}
        assertEquals(0,budget.snapshot().usedBytes);
    }
    private static void await(IntruderAttack attack)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!attack.isTerminal()&&System.nanoTime()<end)Thread.sleep(2);assertTrue(attack.isTerminal());}
}
