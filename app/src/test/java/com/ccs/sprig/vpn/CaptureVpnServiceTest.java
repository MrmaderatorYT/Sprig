package com.ccs.sprig.vpn;

import android.content.Intent;
import com.ccs.sprig.SprigApp;
import com.ccs.sprig.core.net.LatestTaskExecutor;
import com.ccs.sprig.core.tunnel.TunEngine;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowService;
import static org.junit.Assert.*;

/** Service scheduling/ownership tests use fake engines; they do not claim real VpnService validation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=SprigApp.class)
public class CaptureVpnServiceTest {
    private ServiceController<CaptureVpnService> controller;
    private CaptureVpnService service;
    private final List<FakeCapture> created=new CopyOnWriteArrayList<>();
    @Before public void setup(){controller=Robolectric.buildService(CaptureVpnService.class).create();service=controller.get();service.setCaptureFactory((command,listener)->capture(listener));}
    @After public void teardown()throws Exception{controller.destroy();assertTrue(executor().awaitTermination(5,TimeUnit.SECONDS));assertFalse(CaptureVpnService.isRunning());}
    private FakeCapture capture(TunEngine.Listener listener){FakeCapture value=new FakeCapture(listener);created.add(value);return value;}
    private void command(String action,int id){service.onStartCommand(new Intent(service,CaptureVpnService.class).setAction(action),0,id);}
    private ShadowService shadow(){return Shadows.shadowOf(service);}
    private LatestTaskExecutor executor()throws Exception{Field field=CaptureVpnService.class.getDeclaredField("lifecycle");field.setAccessible(true);return(LatestTaskExecutor)field.get(service);}
    private long handled(){try{Field field=CaptureVpnService.class.getDeclaredField("handledGeneration");field.setAccessible(true);return field.getLong(service);}catch(Exception e){throw new AssertionError(e);}}

    @Test public void duplicateStartKeepsLiveEngineFailureHandling()throws Exception{
        command(CaptureVpnService.ACTION_START,1);await(CaptureVpnService::isRunning);FakeCapture original=created.get(0);
        command(CaptureVpnService.ACTION_START,2);await(()->handled()==2);assertEquals(1,created.size());original.fail("live engine failed");
        await(()->shadow().isStoppedBySelf());assertFalse(CaptureVpnService.isRunning());assertEquals("live engine failed",CaptureVpnService.lastError());assertEquals(2,shadow().getStopSelfId());assertEquals(1,original.closes.get());
    }
    @Test public void staleStartupFailureCannotStopNewerStartOrPublishItsError()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger attempts=new AtomicInteger();
        service.setCaptureFactory((command,listener)->{if(attempts.incrementAndGet()==1){entered.countDown();release.await();throw new IOException("stale startup failure");}return capture(listener);});
        try{command(CaptureVpnService.ACTION_START,1);assertTrue(entered.await(3,TimeUnit.SECONDS));command(CaptureVpnService.ACTION_START,2);release.countDown();await(CaptureVpnService::isRunning);
            assertEquals(2,attempts.get());assertEquals("",CaptureVpnService.lastError());assertFalse(shadow().isStoppedBySelf());assertFalse(shadow().isForegroundStopped());
        }finally{release.countDown();}
    }
    @Test public void immediateFailurePreservesCauseAndClosesStartupEngine()throws Exception{
        service.setCaptureFactory((command,listener)->{FakeCapture value=capture(listener);value.startFailure="immediate tunnel failure";return value;});
        command(CaptureVpnService.ACTION_START,1);await(()->shadow().isStoppedBySelf());assertFalse(CaptureVpnService.isRunning());assertEquals("immediate tunnel failure",CaptureVpnService.lastError());assertEquals(1,created.size());assertEquals(1,created.get(0).closes.get());assertEquals(1,shadow().getStopSelfId());
    }
    @Test public void newerStartDuringSlowStopKeepsForegroundAndRestarts()throws Exception{
        command(CaptureVpnService.ACTION_START,1);await(CaptureVpnService::isRunning);FakeCapture original=created.get(0);original.closeEntered=new CountDownLatch(1);original.closeRelease=new CountDownLatch(1);
        try{command(CaptureVpnService.ACTION_STOP,2);assertTrue(original.closeEntered.await(3,TimeUnit.SECONDS));command(CaptureVpnService.ACTION_START,3);original.closeRelease.countDown();await(()->CaptureVpnService.isRunning()&&created.size()==2);
            assertFalse(shadow().isStoppedBySelf());assertFalse(shadow().isForegroundStopped());assertEquals("",CaptureVpnService.lastError());
        }finally{original.closeRelease.countDown();}
    }
    @Test public void staleEngineFailureCannotStopReplacement()throws Exception{
        command(CaptureVpnService.ACTION_START,1);await(CaptureVpnService::isRunning);FakeCapture original=created.get(0);command(CaptureVpnService.ACTION_STOP,2);await(()->shadow().getStopSelfId()==2);
        command(CaptureVpnService.ACTION_START,3);await(()->CaptureVpnService.isRunning()&&created.size()==2);original.fail("old callback");
        CountDownLatch drained=new CountDownLatch(1);assertTrue(executor().executeLatest(drained::countDown));assertTrue(drained.await(3,TimeUnit.SECONDS));assertTrue(CaptureVpnService.isRunning());assertEquals("",CaptureVpnService.lastError());assertEquals(2,shadow().getStopSelfId());
    }
    @Test public void failureCallbackCannotDiscardPendingExplicitRestart()throws Exception{
        command(CaptureVpnService.ACTION_START,1);await(CaptureVpnService::isRunning);FakeCapture original=created.get(0);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        assertTrue(executor().executeLatest(()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}));
        try{assertTrue(entered.await(3,TimeUnit.SECONDS));command(CaptureVpnService.ACTION_START,2);original.fail("failed with pending start");release.countDown();await(()->CaptureVpnService.isRunning()&&created.size()==2);
            assertEquals(1,original.closes.get());assertEquals("",CaptureVpnService.lastError());assertFalse(shadow().isStoppedBySelf());
        }finally{release.countDown();}
    }
    private static void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);assertTrue("Lifecycle condition must complete",condition.getAsBoolean());}
    private static final class FakeCapture implements CaptureVpnService.CaptureHandle {
        final TunEngine.Listener listener;final AtomicInteger closes=new AtomicInteger();volatile String startFailure;volatile CountDownLatch closeEntered,closeRelease;
        FakeCapture(TunEngine.Listener listener){this.listener=listener;}
        public void start(){if(startFailure!=null)fail(startFailure);}
        void fail(String reason){listener.onFatalError(new IOException(reason));}
        public void close(){closes.incrementAndGet();if(closeEntered!=null)closeEntered.countDown();if(closeRelease!=null)try{closeRelease.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}
        public long bytesUp(){return 7;}public long bytesDown(){return 11;}
    }
}
