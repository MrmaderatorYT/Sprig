package com.ccs.sprig.core.intruder;

import com.ccs.sprig.core.model.Exchange;
import com.ccs.sprig.core.net.CallCancellation;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class IntruderAttackTest {
    @After public void cleanup()throws Exception {IntruderAttack latest=IntruderEngine.latest();if(latest!=null&&!latest.isTerminal()){latest.cancel();await(latest);} }
    static IntruderConfig.Builder config(int cases){return new IntruderConfig.Builder("http://fixture.test/",IntruderPlanTest.raw("GET /?value=§x§ HTTP/1.1\r\nHost: fixture.test\r\nCookie: sid=secret\r\n\r\n")).payloadSets(Collections.singletonList(PayloadSource.numeric(0,cases-1,1,0))).requestBudget(cases).concurrency(1).intervalMillis(0);}
    static Exchange response(byte[] raw){Exchange out=new Exchange();out.status=200;out.requestHeaders=new String(raw,StandardCharsets.ISO_8859_1);out.responseHeaders="HTTP/1.1 200 OK\r\nContent-Length: 9\r\n\r\n";out.responseBody="token=abc!".getBytes(StandardCharsets.ISO_8859_1);return out;}
    static void await(IntruderAttack attack)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!attack.isTerminal()&&System.nanoTime()<end)Thread.sleep(5);assertTrue("Attack did not finish: "+attack.state(),attack.isTerminal());}
    static void completed(IntruderAttack attack,long count)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(attack.stats().completed<count&&System.nanoTime()<end)Thread.sleep(5);assertEquals(count,attack.stats().completed);}
    @Test public void pauseStopsNewLaunchesThenResumeCompletes()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        IntruderAttack attack=new IntruderEngine((url,raw,protocol)->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new IOException(e);}return response(raw);}).start(config(3).build());
        assertTrue(entered.await(2,TimeUnit.SECONDS));attack.pause();release.countDown();completed(attack,1);Thread.sleep(80);assertEquals(1,attack.stats().dispatched);assertEquals(IntruderAttack.State.PAUSED,attack.state());
        attack.resume();await(attack);assertEquals(3,attack.stats().completed);assertEquals(IntruderAttack.State.COMPLETED,attack.state());
    }
    @Test public void cancelClosesOwnRequestWithoutCancellingAnotherToken()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),closed=new CountDownLatch(1),unrelated=new CountDownLatch(1);
        // A separate active scope is kept on its own thread; it must not be closed by attack cancellation.
        CountDownLatch otherReady=new CountDownLatch(1),otherRelease=new CountDownLatch(1);CallCancellation.Token activeOther=new CallCancellation.Token();
        Thread otherThread=new Thread(()->{try(CallCancellation.Scope scope=CallCancellation.enter(activeOther)){CallCancellation.track(unrelated::countDown);otherReady.countDown();otherRelease.await();}catch(Exception ignored){}});otherThread.setDaemon(true);otherThread.start();assertTrue(otherReady.await(1,TimeUnit.SECONDS));
        IntruderAttack attack=new IntruderEngine((url,raw,protocol)->{CallCancellation.track(closed::countDown);entered.countDown();try{closed.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}throw new IOException("closed");}).start(config(100).build());
        assertTrue(entered.await(2,TimeUnit.SECONDS));attack.cancel();assertTrue(closed.await(1,TimeUnit.SECONDS));assertFalse(activeOther.isCancelled());await(attack);assertEquals(1,attack.stats().dispatched);assertEquals(IntruderAttack.State.CANCELLED,attack.state());otherRelease.countDown();otherThread.join(1000);
    }
    @Test public void rateLimitIsGlobalAcrossWorkersAndConcurrencyIsBounded()throws Exception {
        List<Long> starts=Collections.synchronizedList(new ArrayList<>());AtomicInteger active=new AtomicInteger(),max=new AtomicInteger();
        IntruderAttack attack=new IntruderEngine((url,raw,protocol)->{starts.add(System.nanoTime());int count=active.incrementAndGet();max.accumulateAndGet(count,Math::max);try{Thread.sleep(100);}catch(InterruptedException e){throw new IOException(e);}finally{active.decrementAndGet();}return response(raw);}).start(config(6).concurrency(4).intervalMillis(50).build());await(attack);
        assertEquals(6,starts.size());for(int i=1;i<starts.size();i++)assertTrue("Pacing must be global",TimeUnit.NANOSECONDS.toMillis(starts.get(i)-starts.get(i-1))>=30);assertTrue(max.get()>1);assertTrue(max.get()<=4);
    }
    @Test public void deadlineClosesRequestAndRecordsTimeout()throws Exception {
        CountDownLatch closed=new CountDownLatch(1);
        IntruderAttack attack=new IntruderEngine((url,raw,protocol)->{CallCancellation.track(closed::countDown);try{closed.await();}catch(InterruptedException e){throw new IOException(e);}throw new IOException("closed");}).start(config(1).timeoutMillis(1000).build());await(attack);assertTrue(attack.results().get(0).error.contains("timed out"));assertEquals(0,attack.stats().active);
    }
    @Test public void retriesConsumeBudgetAndPostIsNeverAutomaticallyRetried()throws Exception {
        AtomicInteger sent=new AtomicInteger();IntruderEngine engine=new IntruderEngine((url,raw,protocol)->{sent.incrementAndGet();throw new IOException("temporary");});
        IntruderAttack get=engine.start(config(10).requestBudget(3).retries(2).build());await(get);assertEquals(3,sent.get());assertEquals(1,get.stats().completed);assertEquals(3,get.results().get(0).attempts);
        byte[] post=IntruderPlanTest.raw("POST / HTTP/1.1\r\nHost: fixture.test\r\n\r\n§x§");IntruderAttack run=engine.start(new IntruderConfig.Builder("http://fixture.test/",post).payloadSets(Collections.singletonList(PayloadSource.ofLines("a\nb\nc"))).requestBudget(3).retries(2).intervalMillis(0).concurrency(1).build());await(run);assertEquals(6,sent.get());assertEquals(3,run.stats().completed);for(IntruderResult result:run.results())assertEquals(1,result.attempts);
    }
    @Test public void evidenceGrepRetentionAndExportsAreExplicitlyBounded()throws Exception {
        IntruderAttack attack=new IntruderEngine((url,raw,protocol)->response(raw)).start(config(5).resultLimit(2).evidenceBytes(32).grepMatches(Collections.singletonList("token=")).grepExtract("token=([a-z]+)").build());await(attack);
        assertEquals(2,attack.results().size());assertEquals(3,attack.stats().evicted);IntruderResult result=attack.results().get(0);assertTrue(result.matched);assertEquals("abc",result.extract);assertTrue(result.evidenceTruncated);assertTrue(result.requestEvidence.length<=32);assertTrue(result.responseEvidence.length<=32);assertEquals(9,result.responseLength);
        assertTrue(new String(attack.requestForResult(result.number),StandardCharsets.ISO_8859_1).contains("Cookie: sid=secret"));
        ByteArrayOutputStream json=new ByteArrayOutputStream();IntruderExporter.writeJson(attack,json);String text=json.toString("UTF-8");assertTrue(text.contains("\"evictedResults\":3"));assertTrue(text.contains("\"requestBase64\""));ByteArrayOutputStream csv=new ByteArrayOutputStream();IntruderExporter.writeCsv(attack,csv);assertTrue(csv.toString("UTF-8").contains("number,status,duration_ms"));
    }
    @Test(timeout=3000) public void extractUsesLinearTimeRe2AgainstAdversarialBody()throws Exception {
        IntruderAttack attack=new IntruderEngine((url,raw,protocol)->{Exchange out=response(raw);out.responseBody=new byte[256*1024];Arrays.fill(out.responseBody,(byte)'a');out.responseHeaders="HTTP/1.1 200 OK\r\nContent-Length: 262144\r\n\r\n";return out;}).start(config(1).grepExtract("(a+)+b").build());await(attack);assertEquals("",attack.results().get(0).extract);assertTrue(attack.results().get(0).grepTruncated);
    }
}
