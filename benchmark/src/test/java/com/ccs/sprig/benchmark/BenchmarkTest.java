package com.ccs.sprig.benchmark;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.ccs.sprig.core.proxy.HttpCodec;
import org.junit.Test;
import static org.junit.Assert.*;

public class BenchmarkTest {
    @Test public void invalidAndDuplicateConfigurationDoesNotStartWork(){
        for(String[] args:new String[][]{{"--seconds","0"},{"--seconds","3601"},{"--concurrency","5"},{"--body-bytes","262145"},{"--rate-per-second","-1"},{"--rate-per-second","10001"},{"--scenario","public"},{"--unknown","1"},{"--seconds","1","--seconds","2"},{"--seconds"}}){try{BenchmarkConfig.parse(args);fail(java.util.Arrays.toString(args));}catch(IllegalArgumentException expected){}}
        BenchmarkConfig valid=BenchmarkConfig.parse(new String[]{"--seconds","1","--warmup-seconds","0","--concurrency","4","--body-bytes","262144","--scenario","mixed"});assertTrue(valid.mixed);assertEquals(4,valid.concurrency);assertEquals(100,valid.ratePerSecond);assertEquals(0,BenchmarkConfig.parse(new String[]{"--rate-per-second","0"}).ratePerSecond);
    }
    @Test public void globalPacerBoundsMultipleCallersWithinIntervalBudget()throws Exception{
        WorkloadPacer pacer=new WorkloadPacer(20);java.util.concurrent.atomic.AtomicInteger admitted=new java.util.concurrent.atomic.AtomicInteger();ExecutorService threads=Executors.newFixedThreadPool(4);long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(260);try{for(int i=0;i<4;i++)threads.submit(()->{try{while(pacer.await(()->true,until))admitted.incrementAndGet();}catch(InterruptedException e){Thread.currentThread().interrupt();}});threads.shutdown();assertTrue(threads.awaitTermination(2,TimeUnit.SECONDS));assertTrue(admitted.get()>0);assertTrue("Global rate cannot multiply by worker count",admitted.get()<=6);}finally{threads.shutdownNow();}
    }
    @Test public void rateWaitHonorsStopInterruptAndUnpacedOption()throws Exception{
        WorkloadPacer pacer=new WorkloadPacer(1);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);assertTrue(pacer.await(()->true,deadline));java.util.concurrent.atomic.AtomicBoolean running=new java.util.concurrent.atomic.AtomicBoolean(true);ExecutorService thread=Executors.newSingleThreadExecutor();try{java.util.concurrent.Future<Boolean> waiting=thread.submit(()->pacer.await(running::get,deadline));Thread.sleep(20);long stopped=System.nanoTime();running.set(false);assertFalse(waiting.get(300,TimeUnit.MILLISECONDS));assertTrue(System.nanoTime()-stopped<TimeUnit.MILLISECONDS.toNanos(250));}finally{thread.shutdownNow();}
        Thread.currentThread().interrupt();try{pacer.await(()->true,deadline);fail();}catch(InterruptedException expected){}finally{Thread.interrupted();}WorkloadPacer unpaced=new WorkloadPacer(0);for(int i=0;i<100;i++)assertTrue(unpaced.await(()->true,System.nanoTime()+TimeUnit.SECONDS.toNanos(1)));assertFalse(unpaced.await(()->false,deadline));
    }
    @Test public void boundedHistogramReportsPercentileUpperBounds(){
        LatencyHistogram histogram=new LatencyHistogram();assertEquals(0,histogram.percentileUpperNanos(.95));
        for(int i=1;i<=100;i++)histogram.record(i*1000000L);assertEquals(100,histogram.count());assertTrue(histogram.percentileUpperNanos(.50)>=50000000L);assertTrue(histogram.percentileUpperNanos(.50)<=51000000L);assertTrue(histogram.percentileUpperNanos(.95)>=95000000L);assertTrue(histogram.percentileUpperNanos(.95)<=97000000L);
        histogram.record(Long.MAX_VALUE);assertEquals(Long.MAX_VALUE,histogram.percentileUpperNanos(1));
    }
    @Test public void histogramCountsConcurrentWritersWithoutRetainingSamples()throws Exception{
        LatencyHistogram histogram=new LatencyHistogram();ExecutorService threads=Executors.newFixedThreadPool(4);try{for(int i=0;i<4;i++)threads.submit(()->{for(int n=0;n<10000;n++)histogram.record(1000000);});threads.shutdown();assertTrue(threads.awaitTermination(3,TimeUnit.SECONDS));assertEquals(40000,histogram.count());assertTrue(histogram.percentileUpperNanos(.95)>=1000000);assertTrue(histogram.percentileUpperNanos(.95)<=1020000);}finally{threads.shutdownNow();}
    }
    @Test public void loopbackMixedRunValidatesAllToolsAndTerminates()throws Exception{
        Map<String,Object> report=BenchmarkMain.run(new BenchmarkConfig(1,0,2,1024,true,Path.of("unused.json")));assertEquals("host-jvm-loopback",report.get("environment"));assertEquals(false,report.get("androidVpnTested"));assertEquals(true,report.get("fixtureTerminated"));assertEquals(true,report.get("passed"));
        List<?> phases=(List<?>)report.get("phases");Map<?,?> phase=(Map<?,?>)phases.get(0);assertTrue(((Number)phase.get("successes")).longValue()>0);assertEquals(0,((Number)phase.get("errors")).longValue());Map<?,?> auxiliary=(Map<?,?>)phase.get("auxiliaryOperations");for(String key:List.of("webSocketEchoes","intruderRequests","scannerRequests"))assertTrue(key,((Number)auxiliary.get(key)).longValue()>0);
        StringWriter json=new StringWriter();Json.write(json,report);assertTrue(json.toString().contains("\"devicePerformanceMeasured\":false"));
    }
    @Test public void deviceFixtureEchoesBinaryAndRequiresItsToken()throws Exception{
        InetAddress local=InetAddress.getByAddress(new byte[]{127,0,0,1});LoopbackFixture fixture=new LoopbackFixture(local,0,"testfixturetoken");
        try{byte[] body={0,(byte)255,13,10,0};try(Socket socket=new Socket(local,fixture.port())){socket.setSoTimeout(2000);String head="POST /sprig-fixture?token=testfixturetoken HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\nConnection: close\r\n\r\n";socket.getOutputStream().write(head.getBytes(StandardCharsets.ISO_8859_1));socket.getOutputStream().write(body);socket.getOutputStream().flush();HttpCodec.Message response=HttpCodec.readResponse(new BufferedInputStream(socket.getInputStream()),"POST");assertEquals(200,response.status());assertArrayEquals(body,response.body);}
            try(Socket socket=new Socket(local,fixture.port())){socket.setSoTimeout(2000);socket.getOutputStream().write("GET /sprig-fixture?token=wrong HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));socket.getOutputStream().flush();assertEquals(-1,socket.getInputStream().read());}
        }finally{fixture.close();assertTrue(fixture.awaitClosed());}
    }
    @Test public void allocationComparisonPreservesContentAndReleasesAllReservations(){Map<String,Object> report=StoreAllocationBenchmark.run(16,8,2);assertEquals(true,report.get("passed"));assertEquals(4,((List<?>)report.get("samples")).size());for(Object value:(List<?>)report.get("samples")){Map<?,?> sample=(Map<?,?>)value;assertEquals(0,((Number)sample.get("usedAfterClear")).longValue());}Object copy=report.get("copyAllocatedBytesPerOperation"),owned=report.get("ownedAllocatedBytesPerOperation");if(copy instanceof Number&&owned instanceof Number)assertTrue(((Number)owned).doubleValue()<((Number)copy).doubleValue());}
    @Test public void jsonEscapesUntrustedStringsAndRejectsNonFiniteNumbers()throws Exception{
        StringWriter writer=new StringWriter();Json.write(writer,Map.of("value","\"\n\t\\\u0000\ud800"));assertTrue(writer.toString().contains("\\\"\\n\\t\\\\\\u0000\\ud800"));
        for(Number number:List.of(Double.NaN,Double.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY))try{Json.write(new StringWriter(),number);fail();}catch(IOException expected){}
    }
}
