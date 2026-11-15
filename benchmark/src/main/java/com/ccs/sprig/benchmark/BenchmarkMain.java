package com.ccs.sprig.benchmark;

import com.ccs.sprig.core.InterceptController;
import com.ccs.sprig.core.intruder.*;
import com.ccs.sprig.core.memory.MemoryBudget;
import com.ccs.sprig.core.model.Exchange;
import com.ccs.sprig.core.model.SessionStore;
import com.ccs.sprig.core.net.CallCancellation;
import com.ccs.sprig.core.proxy.HttpCodec;
import com.ccs.sprig.core.proxy.ProxyServer;
import com.ccs.sprig.core.scanner.*;
import com.ccs.sprig.core.websocket.WebSocketHub;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Reproducible host benchmark. No Android/VPN, UI-jank, radio or battery claims are made. */
public final class BenchmarkMain {
    private BenchmarkMain(){}
    public static void main(String[] arguments)throws Exception {
        BenchmarkConfig config=BenchmarkConfig.parse(arguments);Map<String,Object> report=run(config);Path parent=config.output.toAbsolutePath().getParent();Files.createDirectories(parent);
        try(BufferedWriter writer=Files.newBufferedWriter(config.output,StandardCharsets.UTF_8)){Json.write(writer,report);writer.write('\n');}
        System.out.println("Host benchmark report: "+config.output.toAbsolutePath());
        if(!Boolean.TRUE.equals(report.get("passed")))throw new IllegalStateException("Benchmark content, transport or cleanup checks failed; inspect report");
    }
    public static Map<String,Object> run(BenchmarkConfig config)throws Exception {
        byte[] payload=new byte[config.bodyBytes];for(int i=0;i<payload.length;i++)payload[i]=(byte)(i*31+17);
        Map<String,Object> report=new LinkedHashMap<>();report.put("schemaVersion",1);report.put("environment","host-jvm-loopback");report.put("androidVpnTested",false);report.put("devicePerformanceMeasured",false);report.put("createdAtUtc",Instant.now().toString());
        report.put("build",Map.of("version",System.getProperty("sprig.buildVersion","unspecified"),"javaVersion",System.getProperty("java.version"),"vm",System.getProperty("java.vm.name"),"os",System.getProperty("os.name"),"osVersion",System.getProperty("os.version"),"architecture",System.getProperty("os.arch"),"processors",Runtime.getRuntime().availableProcessors(),"maxHeapBytes",Runtime.getRuntime().maxMemory()));
        report.put("configuration",Map.of("scenario",config.mixed?"mixed":"http","seconds",config.seconds,"warmupSeconds",config.warmupSeconds,"concurrency",config.concurrency,"bodyBytes",config.bodyBytes,"proxyAdmission",8,"sampleIntervalMillis",1000,"offeredRatePerSecond",config.ratePerSecond));
        report.put("fixture",Map.of("revision",LoopbackFixture.REVISION,"bind","127.0.0.1 ephemeral ports only","requestBodySha256",hex(MessageDigest.getInstance("SHA-256").digest(payload)),"mainOperation","alternating HTTP/1.1 POST proxy-capture and direct Repeater echo","mixedOperations","one WS echo stream plus one Intruder worker and one Scanner page worker","originConnectionReuse",false));
        report.put("unavailableMetrics",List.of("Android UI jank","battery/radio energy","Android RSS/heap/GC","real VpnService/TUN throughput"));
        List<Map<String,Object>> phases=new ArrayList<>();Path ca=Files.createTempDirectory("sprig-benchmark-ca-");MemoryBudget memory=new MemoryBudget(32L*1024*1024);SessionStore store=new SessionStore(256,8L*1024*1024,memory);ProxyServer proxy=null;LoopbackFixture fixture=null;boolean cleanup=true;
        ResourceSample.capture(0,"initialize-counters");
        try {
            fixture=new LoopbackFixture();proxy=new ProxyServer(store,new InterceptController(),ca.toFile(),null);proxy.setConnectionLimit(8);proxy.start(0);
            if(config.warmupSeconds>0){phases.add(phase("warmup",config.warmupSeconds,config,proxy,fixture,payload,store));store.clear();}
            phases.add(phase("measurement",config.seconds,config,proxy,fixture,payload,store));
        }finally{
            if(proxy!=null){proxy.stop();proxy.getWebSockets().close();}store.clear();
            if(fixture!=null){fixture.close();cleanup=fixture.awaitClosed();}
            try(java.util.stream.Stream<Path> paths=Files.walk(ca)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
        MemoryBudget.Snapshot accounting=memory.snapshot();report.put("memoryBudget",Map.of("limitBytes",accounting.limitBytes,"peakReservedBytes",accounting.peakBytes,"rejectedReservations",accounting.rejectedReservations,"usedAfterCleanup",accounting.usedBytes));cleanup&=accounting.usedBytes==0;report.put("phases",phases);report.put("postCleanup",ResourceSample.capture(0,"post-cleanup").values);report.put("fixtureTerminated",cleanup);
        report.put("passed",cleanup&&phases.stream().allMatch(p->Boolean.TRUE.equals(p.get("passed"))));return report;
    }
    private static Map<String,Object> phase(String name,int seconds,BenchmarkConfig config,ProxyServer proxy,LoopbackFixture fixture,byte[] payload,SessionStore store)throws Exception {
        Phase state=new Phase(config.ratePerSecond);AtomicReference<IntruderAttack> attack=new AtomicReference<>();AtomicReference<ScanEngine> scan=new AtomicReference<>();Set<CallCancellation.Token> tokens=ConcurrentHashMap.newKeySet();
        int count=config.concurrency+(config.mixed?3:0);ThreadPoolExecutor workers=new ThreadPoolExecutor(count,count,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(count),r->{Thread thread=new Thread(r,"benchmark-workload");thread.setDaemon(true);return thread;});
        List<Future<?>> jobs=new ArrayList<>();List<Map<String,Object>> samples=new ArrayList<>();CountDownLatch start=new CountDownLatch(1);long session=0;WebSocketHub hub=proxy.getWebSockets();AtomicReference<Expectation> expected=new AtomicReference<>();
        Runnable listener=()->{Expectation waiting=expected.get();if(waiting!=null)for(WebSocketHub.Message message:hub.messages(waiting.session))if(message.direction.equals(WebSocketHub.SERVER_TO_CLIENT)&&message.opcode==2&&!message.truncated&&Arrays.equals(message.payload,waiting.bytes)){waiting.received.countDown();break;}};
        long begin=System.nanoTime();long end=begin+TimeUnit.SECONDS.toNanos(seconds);ResourceSample before=ResourceSample.capture(0,name);samples.add(before.values);
        try {
            byte[] raw=request("POST",fixture.url()+"/echo",fixture.port(),payload);
            for(int i=0;i<config.concurrency;i++){final int id=i;jobs.add(workers.submit(()->guard(state,()->{start.await();int n=id;while(!state.stop.get()&&System.nanoTime()<state.deadline){if(!pace(state))break;long began=System.nanoTime();CallCancellation.Token token=new CallCancellation.Token();tokens.add(token);if(state.stop.get())token.cancel();try(CallCancellation.Scope ignored=CallCancellation.enter(token)){if((n++&1)==0){Exchange result=proxy.replay(fixture.url()+"/echo",raw,"HTTP/1.1");validate(result,payload);}else capture(proxy,raw,payload);}finally{tokens.remove(token);}state.record(System.nanoTime()-began);}})));}
            if(config.mixed){
                session=hub.connect("ws://127.0.0.1:"+fixture.port()+"/ws","").id;final long live=session;hub.addListener(listener);
                jobs.add(workers.submit(()->guard(state,()->{start.await();long sequence=0;while(!state.stop.get()&&System.nanoTime()<state.deadline){if(!pace(state))break;byte[] message=new byte[256];Arrays.fill(message,(byte)0x5a);java.nio.ByteBuffer.wrap(message).putLong(++sequence);Expectation waiting=new Expectation(live,message);expected.set(waiting);hub.sendBinary(live,message);if(!waiting.received.await(2,TimeUnit.SECONDS))throw new IOException("WebSocket echo timeout or payload mismatch");state.ws.incrementAndGet();Thread.sleep(20);}})));
                jobs.add(workers.submit(()->guard(state,()->{start.await();while(!state.stop.get()&&System.nanoTime()<state.deadline){IntruderConfig settings=new IntruderConfig.Builder(fixture.url()+"/scan",("GET /scan?q=§0§ HTTP/1.1\r\nHost: 127.0.0.1:"+fixture.port()+"\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1)).payloadSets(List.of(PayloadSource.numeric(0,15,1,0))).requestBudget(16).concurrency(1).intervalMillis(100).timeoutMillis(2000).evidenceBytes(256).memoryBytes(128*1024).resultLimit(16).build();IntruderAttack run=new IntruderEngine((url,request,protocol)->{if(!pace(state))throw new java.io.InterruptedIOException("Measurement interval ended");try{Exchange result=proxy.replay(url,request,protocol);validate(result,null);state.intruder.incrementAndGet();return result;}catch(IOException error){if(state.accepting())state.fail("Intruder transport: "+error.getMessage());throw error;}},store.memoryBudget()).start(settings);attack.set(run);while(!run.isTerminal()&&!state.stop.get())Thread.sleep(10);if(!run.isTerminal())run.cancel();waitTerminal(run);IntruderEngine.forget(run.id);attack.compareAndSet(run,null);}})));
                jobs.add(workers.submit(()->guard(state,()->{start.await();while(!state.stop.get()&&System.nanoTime()<state.deadline){ScanEngine engine=new ScanEngine((url,request)->{if(!pace(state))throw new java.io.InterruptedIOException("Measurement interval ended");Exchange result=proxy.replay(url,request,"HTTP/1.1");if(state.accepting())validate(result,null);state.scanner.incrementAndGet();return result;});scan.set(engine);ScanConfig settings=new ScanConfig(fixture.url()+"/scan?q=1");settings.crawl=false;settings.maxPages=1;settings.maxRequests=6;settings.concurrency=1;settings.delayMillis=200;settings.enabledChecks.clear();settings.enabledChecks.addAll(List.of("headers","reflection","sql-error"));ScanReport result=engine.run(settings,null);if(state.accepting()&&!result.errors.isEmpty())throw new IOException("Scanner transport errors: "+result.errors.get(0));scan.compareAndSet(engine,null);}})));
            }
            // Start after setup; connect/initialization is reported separately from steady-state operation latency.
            begin=System.nanoTime();end=begin+TimeUnit.SECONDS.toNanos(seconds);state.deadline=end;start.countDown();
            while(System.nanoTime()<end){long remaining=end-System.nanoTime();TimeUnit.NANOSECONDS.sleep(Math.min(remaining,TimeUnit.SECONDS.toNanos(1)));samples.add(ResourceSample.capture(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin),name).values);}
        }finally {
            state.stop.set(true);start.countDown();IntruderAttack current=attack.get();if(current!=null)current.cancel();ScanEngine currentScan=scan.get();if(currentScan!=null)currentScan.cancel();
            for(CallCancellation.Token token:tokens)token.cancel();workers.shutdownNow();if(!workers.awaitTermination(8,TimeUnit.SECONDS))state.fail("Workload workers did not terminate within 8 seconds");
            if(current!=null){waitTerminal(current);IntruderEngine.forget(current.id);}
            for(Future<?> job:jobs)try{job.get(1,TimeUnit.SECONDS);}catch(CancellationException|InterruptedException ignored){}catch(ExecutionException|TimeoutException error){state.fail("Worker failure: "+error);}
            hub.removeListener(listener);if(session!=0){hub.closeSession(session);long closeBy=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(hub.hasActive()&&System.nanoTime()<closeBy)Thread.sleep(10);if(hub.hasActive())state.fail("WebSocket did not close within 6 seconds");}hub.clearHistory();
        }
        ResourceSample after=ResourceSample.capture(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin),name+"-cleanup");samples.add(after.values);
        Map<String,Object> out=new LinkedHashMap<>();out.put("name",name);out.put("measurementNanos",TimeUnit.SECONDS.toNanos(seconds));out.put("successes",state.success.get());out.put("lateCompletions",state.late.get());out.put("errors",state.errors.get());out.put("errorSamples",new ArrayList<>(state.details));out.put("offeredRatePerSecond",config.ratePerSecond);out.put("rateScope","all main HTTP, WebSocket data, Intruder and Scanner sends; setup handshakes excluded; zero means unpaced stress");out.put("admittedWorkloadOperations",state.admitted.get());out.put("latencyScope","main HTTP service after global pacing admission; pacing wait excluded");out.put("operationsPerSecond",state.success.get()/(double)seconds);out.put("latencyP50MicrosUpper",state.latency.percentileUpperNanos(.50)/1000.0);out.put("latencyP95MicrosUpper",state.latency.percentileUpperNanos(.95)/1000.0);out.put("latencyHistogram","4096 fixed log2 bins, percentile upper-bound error <=1.6% (except nanosecond rounding)");out.put("auxiliaryOperations",Map.of("webSocketEchoes",state.ws.get(),"intruderRequests",state.intruder.get(),"scannerRequests",state.scanner.get()));MemoryBudget.Snapshot memory=store.memoryBudget().snapshot();out.put("memoryBudget",Map.of("limitBytes",memory.limitBytes,"peakReservedBytes",memory.peakBytes,"rejectedReservations",memory.rejectedReservations,"usedAfterToolCleanup",memory.usedBytes));out.put("historyEntriesAtEnd",store.size());out.put("historyBytesAtEnd",store.memoryBytes());out.put("resourceCounterScope","phase setup, measurement and cleanup; not device counters");out.put("resources",samples);out.put("processCpuNanosDelta",difference(after.values,before.values,"processCpuNanos"));out.put("gcCollectionsDelta",difference(after.values,before.values,"gcCollections"));out.put("gcTimeMillisDelta",difference(after.values,before.values,"gcTimeMillis"));out.put("passed",state.errors.get()==0&&state.success.get()>0&&(!config.mixed||state.ws.get()>0&&state.intruder.get()>0&&state.scanner.get()>0));return out;
    }
    private static Object difference(Map<String,Object> after,Map<String,Object> before,String key){Object a=after.get(key),b=before.get(key);return a instanceof Number&&b instanceof Number?Math.max(0,((Number)a).longValue()-((Number)b).longValue()):null;}
    private interface Work {void run()throws Exception;}
    private static void guard(Phase phase,Work work){try{work.run();}catch(Exception error){if(phase.accepting())phase.fail(error.getClass().getSimpleName()+": "+error.getMessage());}}
    private static boolean pace(Phase phase)throws IOException{try{boolean allowed=phase.pacer.await(()->!phase.stop.get(),phase.deadline);if(allowed)phase.admitted.incrementAndGet();return allowed;}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException("Workload pacing interrupted");}}
    private static void validate(Exchange exchange,byte[] expected)throws IOException {if(exchange.status!=200||exchange.error!=null&&!exchange.error.isEmpty()||expected!=null&&!Arrays.equals(expected,exchange.responseBody))throw new IOException("Unexpected status/error/echo payload: "+exchange.status+" "+exchange.error);}
    private static void capture(ProxyServer proxy,byte[] raw,byte[] expected)throws IOException {try(Socket socket=new Socket()){CallCancellation.track(socket);socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),proxy.getPort()),2000);socket.setSoTimeout(2000);socket.getOutputStream().write(raw);socket.getOutputStream().flush();HttpCodec.Message result=HttpCodec.readResponse(new BufferedInputStream(socket.getInputStream(),16384),"POST");if(result.status()!=200||!Arrays.equals(expected,result.body))throw new IOException("Capture echo mismatch");}}
    private static byte[] request(String method,String url,int port,byte[] body)throws IOException{HttpCodec.Message request=new HttpCodec.Message();request.startLine=method+" "+url+" HTTP/1.1";request.headers.add(new String[]{"Host","127.0.0.1:"+port});request.headers.add(new String[]{"Connection","close"});request.headers.add(new String[]{"Content-Type","application/octet-stream"});request.body=body;HttpCodec.normalizeLength(request,false);return request.raw();}
    private static void waitTerminal(IntruderAttack attack)throws InterruptedException{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!attack.isTerminal()&&System.nanoTime()<until)Thread.sleep(10);if(!attack.isTerminal())throw new IllegalStateException("Intruder cancellation deadline exceeded");}
    private static String hex(byte[] bytes){StringBuilder value=new StringBuilder(bytes.length*2);for(byte b:bytes)value.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return value.toString();}
    private static final class Expectation {final long session;final byte[] bytes;final CountDownLatch received=new CountDownLatch(1);Expectation(long session,byte[] bytes){this.session=session;this.bytes=bytes;}}
    private static final class Phase {final WorkloadPacer pacer;Phase(int rate){pacer=new WorkloadPacer(rate);}boolean accepting(){return !stop.get()&&System.nanoTime()<deadline;}final AtomicLong admitted=new AtomicLong();final AtomicBoolean stop=new AtomicBoolean();final AtomicLong success=new AtomicLong(),late=new AtomicLong(),errors=new AtomicLong(),ws=new AtomicLong(),intruder=new AtomicLong(),scanner=new AtomicLong();final List<String> details=Collections.synchronizedList(new ArrayList<>());final LatencyHistogram latency=new LatencyHistogram();volatile long deadline;void record(long nanos){if(System.nanoTime()<=deadline){latency.record(nanos);success.incrementAndGet();}else late.incrementAndGet();}void fail(String error){errors.incrementAndGet();synchronized(details){if(details.size()<16)details.add(error.substring(0,Math.min(300,error.length())));}}}
}
