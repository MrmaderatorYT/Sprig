package com.ccs.sprig.core.net;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class HostResolverTest {
    @Test public void numericLiteralsNeverEnterTheLookupPool()throws Exception{
        AtomicInteger calls=new AtomicInteger();try(HostResolver resolver=new HostResolver(host->{calls.incrementAndGet();throw new IOException("DNS must not run");},1000)){
            assertArrayEquals(new byte[]{(byte)192,0,2,1},resolver.resolveHost("192.0.2.1",false).getAddress());
            assertTrue(resolver.resolveHost("::1",true) instanceof Inet6Address);
            assertArrayEquals(new byte[]{0x20,1,0x0d,(byte)0xb8,0,0,0,0,0,0,0,0,0,0,0,1},resolver.resolveHost("[2001:db8::1]",true).getAddress());
            assertTrue(resolver.resolveHost("::ffff:192.0.2.1",false) instanceof Inet6Address);
            assertEquals("127.0.0.1",HostResolver.resolve("127.0.0.1",true).getHostAddress());
            assertEquals(0,calls.get());assertEquals(0,resolver.activeLookups());
        }
    }
    @Test public void malformedNumericAddressesFailWithoutDnsFallback()throws Exception{
        AtomicInteger calls=new AtomicInteger();try(HostResolver resolver=new HostResolver(host->{calls.incrementAndGet();return new InetAddress[0];},1000)){
            for(String host:new String[]{"127.1","2130706433","127.00.0.1","256.1.2.3","0x7f000001","0x7f.0.0.1","1.2.3.4.5","1.2.3.","127.0.0.1.","0x7f000001.","1::2::3",":::1","1:2:3:4:5:6:7:8:9","1:2:3:4:5:6:7","[::1","[example.test]","::ffff:192.0.2.999","::ffff:127.01.0.1","fe80::1%3","::not-an-ip"}){
                try{resolver.resolveHost(host,true);fail("Reject "+host);}catch(UnknownHostException expected){}
            }
            assertEquals(0,calls.get());
        }
    }
    @Test public void ipv4PreferencePreservesTheOriginalResolverOrderOtherwise()throws Exception{
        InetAddress v4=InetAddress.getByAddress(new byte[]{(byte)192,0,2,10}),v6=Inet6Address.getByAddress(null,new byte[]{0x20,1,0x0d,(byte)0xb8,0,0,0,0,0,0,0,0,0,0,0,1},-1);
        try(HostResolver resolver=new HostResolver(host->{assertEquals("fixture.test",host);return new InetAddress[]{v6,v4};},1000)){
            assertSame(v4,resolver.resolveHost("FIXTURE.TEST",true));assertSame(v6,resolver.resolveHost("fixture.test",false));
        }
    }
    @Test public void cancellationWakesCallerEvenWhenLookupIgnoresInterrupts()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);CallCancellation.Token token=new CallCancellation.Token();ExecutorService caller=Executors.newSingleThreadExecutor();
        try(HostResolver resolver=new HostResolver(host->{entered.countDown();uninterruptible(release);return loopback();},5000)){
            Future<IOException> result=caller.submit(()->{try(CallCancellation.Scope ignored=CallCancellation.enter(token)){try{resolver.resolveHost("blocked.test",true);return null;}catch(IOException error){return error;}}});
            assertTrue(entered.await(2,TimeUnit.SECONDS));long start=System.nanoTime();token.cancel();IOException failure=result.get(1,TimeUnit.SECONDS);
            assertTrue(failure instanceof InterruptedIOException);assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<500);assertEquals(1,resolver.activeLookups());
        }finally{release.countDown();token.cancel();caller.shutdownNow();}
    }
    @Test public void callerDeadlineIncludesBlockedLookupAndDoesNotSpawnMoreWorkers()throws Exception{
        CountDownLatch release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();try(HostResolver resolver=new HostResolver(host->{calls.incrementAndGet();uninterruptible(release);return loopback();},60)){
            long start=System.nanoTime();try{resolver.resolveHost("timeout.test",true);fail();}catch(SocketTimeoutException expected){assertTrue(expected.getMessage().contains("60 ms"));}
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1000);assertEquals(1,calls.get());assertEquals(0,resolver.queuedRequests());
        }finally{release.countDown();}
    }
    @Test public void queueIsBoundedAndCancelledQueuedCallFreesItsSlot()throws Exception{
        CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);AtomicInteger active=new AtomicInteger(),maximum=new AtomicInteger();List<String> resolved=Collections.synchronizedList(new ArrayList<>());
        ExecutorService callers=Executors.newFixedThreadPool(12);CallCancellation.Token queuedToken=new CallCancellation.Token();
        try(HostResolver resolver=new HostResolver(host->{resolved.add(host);int count=active.incrementAndGet();maximum.accumulateAndGet(count,Math::max);entered.countDown();uninterruptible(release);active.decrementAndGet();return loopback();},5000)){
            List<Future<?>> futures=new ArrayList<>();futures.add(callers.submit(()->call(resolver,"blocker-a.test")));futures.add(callers.submit(()->call(resolver,"blocker-b.test")));assertTrue(entered.await(2,TimeUnit.SECONDS));
            Future<IOException> cancelled=callers.submit(()->{try(CallCancellation.Scope ignored=CallCancellation.enter(queuedToken)){try{resolver.resolveHost("cancelled-queue.test",true);return null;}catch(IOException error){return error;}}});
            awaitQueue(resolver,1);
            for(int i=0;i<7;i++){String name="queued-"+i+".test";futures.add(callers.submit(()->call(resolver,name)));}awaitQueue(resolver,8);
            long start=System.nanoTime();try{resolver.resolveHost("overloaded.test",true);fail("Eleventh request must be rejected");}catch(IOException expected){assertTrue(expected.getMessage().contains("busy"));}
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<250);assertEquals(2,maximum.get());queuedToken.cancel();assertTrue(cancelled.get(1,TimeUnit.SECONDS) instanceof InterruptedIOException);awaitQueue(resolver,7);
            Future<?> replacement=callers.submit(()->call(resolver,"replacement.test"));awaitQueue(resolver,8);release.countDown();for(Future<?> future:futures)future.get(2,TimeUnit.SECONDS);replacement.get(2,TimeUnit.SECONDS);
            assertFalse(resolved.contains("cancelled-queue.test"));assertFalse(resolved.contains("overloaded.test"));assertTrue(resolved.contains("replacement.test"));assertEquals(2,maximum.get());
        }finally{release.countDown();queuedToken.cancel();callers.shutdownNow();}
    }
    @Test public void interruptedCallerAndLookupFailureRemainIoFailures()throws Exception{
        AtomicInteger calls=new AtomicInteger();try(HostResolver resolver=new HostResolver(host->{calls.incrementAndGet();throw new UnknownHostException("fixture lookup failure");},1000)){
            Thread.currentThread().interrupt();try{resolver.resolveHost("unused.test",true);fail();}catch(InterruptedIOException expected){}finally{Thread.interrupted();}
            assertEquals(0,calls.get());try{resolver.resolveHost("unknown.test",true);fail();}catch(UnknownHostException expected){assertEquals("fixture lookup failure",expected.getMessage());}
        }
    }
    private static void awaitQueue(HostResolver resolver,int expected)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(resolver.queuedRequests()!=expected&&System.nanoTime()<end)Thread.sleep(2);assertEquals(expected,resolver.queuedRequests());}
    private static InetAddress[] loopback()throws UnknownHostException{return new InetAddress[]{InetAddress.getByAddress(new byte[]{127,0,0,1})};}
    private static InetAddress call(HostResolver resolver,String host){try{return resolver.resolveHost(host,true);}catch(IOException error){throw new AssertionError(error);}}
    private static void uninterruptible(CountDownLatch latch){boolean interrupted=false;while(latch.getCount()!=0)try{latch.await();}catch(InterruptedException ignored){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();}
}
