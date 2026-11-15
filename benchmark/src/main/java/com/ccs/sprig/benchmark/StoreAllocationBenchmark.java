package com.ccs.sprig.benchmark;

import com.ccs.sprig.core.memory.MemoryBudget;
import com.ccs.sprig.core.model.Exchange;
import com.ccs.sprig.core.model.SessionStore;
import java.io.BufferedWriter;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/** Same-JVM copy/owned capture comparison. Reports thread allocation, never Android claims. */
public final class StoreAllocationBenchmark {
    private StoreAllocationBenchmark(){}
    public static void main(String[] args)throws Exception {
        int iterations=5000,warmup=1000,rounds=5;Path output=Path.of("dist/benchmarks/store-allocation.json");Set<String> seen=new HashSet<>();
        for(int i=0;i<args.length;i+=2){if(i+1>=args.length||!seen.add(args[i]))throw new IllegalArgumentException("Options need one value and may occur once");switch(args[i]){case"--iterations":iterations=Integer.parseInt(args[i+1]);break;case"--warmup-iterations":warmup=Integer.parseInt(args[i+1]);break;case"--rounds":rounds=Integer.parseInt(args[i+1]);break;case"--output":output=Path.of(args[i+1]);break;default:throw new IllegalArgumentException("Unknown option: "+args[i]);}}
        Map<String,Object> report=run(iterations,warmup,rounds);Files.createDirectories(output.toAbsolutePath().getParent());try(BufferedWriter writer=Files.newBufferedWriter(output)){Json.write(writer,report);writer.write('\n');}System.out.println("Host store allocation report: "+output.toAbsolutePath());
    }
    public static Map<String,Object> run(int iterations,int warmup,int rounds) {
        if(iterations<8||iterations>100000||warmup<0||warmup>100000||rounds<1||rounds>10)throw new IllegalArgumentException("Use iterations8–100000, warmup0–100000 and rounds1–10");
        Exchange source=new Exchange();source.method="POST";source.url="http://fixture.invalid/immutable";source.status=200;source.requestHeaders="POST /immutable HTTP/1.1\r\nHost: fixture.invalid\r\n\r\n";source.responseHeaders="HTTP/1.1 200 OK\r\nContent-Length: 32768\r\n\r\n";source.requestBody=new byte[32768];source.responseBody=new byte[32768];for(int i=0;i<32768;i++){source.requestBody[i]=(byte)i;source.responseBody[i]=(byte)(i*31);}
        com.sun.management.ThreadMXBean bean=allocationBean();List<Map<String,Object>> samples=new ArrayList<>();
        if(warmup>0){measure(source,false,warmup,bean,false);measure(source,true,warmup,bean,false);}
        for(int round=0;round<rounds;round++){boolean ownedFirst=(round&1)==1;samples.add(measure(source,ownedFirst,iterations,bean,true));samples.add(measure(source,!ownedFirst,iterations,bean,true));}
        long copiedNanos=0,ownedNanos=0,copiedBytes=0,ownedBytes=0;boolean allocationAvailable=bean!=null;
        for(Map<String,Object> row:samples){boolean owned=Boolean.TRUE.equals(row.get("owned"));long elapsed=((Number)row.get("elapsedNanos")).longValue();if(owned)ownedNanos+=elapsed;else copiedNanos+=elapsed;Object allocated=row.get("threadAllocatedBytes");if(!(allocated instanceof Number))allocationAvailable=false;else if(owned)ownedBytes+=((Number)allocated).longValue();else copiedBytes+=((Number)allocated).longValue();}
        long operations=(long)iterations*rounds;Map<String,Object> out=new LinkedHashMap<>();out.put("schemaVersion",1);out.put("environment","host-jvm-store-allocation");out.put("devicePerformanceMeasured",false);out.put("createdAtUtc",Instant.now().toString());out.put("buildVersion",System.getProperty("sprig.buildVersion","unspecified"));out.put("javaVersion",System.getProperty("java.version"));out.put("vm",System.getProperty("java.vm.name"));out.put("os",System.getProperty("os.name"));out.put("maxHeapBytes",Runtime.getRuntime().maxMemory());out.put("bodyBytesPerSide",32768);out.put("iterationsPerRound",iterations);out.put("warmupIterationsPerVariant",warmup);out.put("rounds",rounds);out.put("retainedEntries",8);out.put("counterScope","current-thread record calls only; prepared immutable exchange and validation/clear excluded");out.put("samples",samples);out.put("copyNanosPerOperation",copiedNanos/(double)operations);out.put("ownedNanosPerOperation",ownedNanos/(double)operations);out.put("ownedToCopyElapsedRatio",ownedNanos/(double)copiedNanos);out.put("copyAllocatedBytesPerOperation",allocationAvailable?copiedBytes/(double)operations:null);out.put("ownedAllocatedBytesPerOperation",allocationAvailable?ownedBytes/(double)operations:null);out.put("ownedToCopyAllocationRatio",allocationAvailable&&copiedBytes>0?ownedBytes/(double)copiedBytes:null);out.put("passed",true);return out;
    }
    private static Map<String,Object> measure(Exchange source,boolean owned,int iterations,com.sun.management.ThreadMXBean bean,boolean record) {
        MemoryBudget budget=new MemoryBudget(2L*1024*1024);SessionStore store=new SessionStore(8,1024*1024,budget);Consumer<Exchange> write=owned?store::recordOwned:store::record;
        Long allocatedBefore=allocated(bean);long began=System.nanoTime();for(int i=0;i<iterations;i++)write.accept(source);long elapsed=System.nanoTime()-began;Long allocatedAfter=allocated(bean);
        int retained=Math.min(8,iterations);if(store.size()!=retained||store.memoryBytes()!=source.capturedBytes()*retained||store.droppedRecords()!=0)throw new IllegalStateException("History count/bytes differ between benchmark variants");
        for(Exchange result:store.snapshot())if(!Arrays.equals(source.requestBody,result.requestBody)||!Arrays.equals(source.responseBody,result.responseBody))throw new IllegalStateException("Capture bytes changed");
        if(owned&&store.snapshot().get(0).requestBody!=source.requestBody||!owned&&store.snapshot().get(0).requestBody==source.requestBody)throw new IllegalStateException("Copy/ownership contract violated");
        long peak=budget.snapshot().peakBytes;store.clear();if(budget.snapshot().usedBytes!=0)throw new IllegalStateException("Memory reservation leaked after clear");
        Map<String,Object> out=new LinkedHashMap<>();if(record){out.put("owned",owned);out.put("operations",iterations);out.put("elapsedNanos",elapsed);out.put("threadAllocatedBytes",allocatedBefore!=null&&allocatedAfter!=null?allocatedAfter-allocatedBefore:null);out.put("peakReservedBytes",peak);out.put("usedAfterClear",budget.snapshot().usedBytes);}return out;
    }
    private static com.sun.management.ThreadMXBean allocationBean(){Object bean=ManagementFactory.getThreadMXBean();if(!(bean instanceof com.sun.management.ThreadMXBean))return null;com.sun.management.ThreadMXBean allocation=(com.sun.management.ThreadMXBean)bean;try{if(!allocation.isThreadAllocatedMemorySupported())return null;if(!allocation.isThreadAllocatedMemoryEnabled())allocation.setThreadAllocatedMemoryEnabled(true);return allocation;}catch(RuntimeException denied){return null;}}
    private static Long allocated(com.sun.management.ThreadMXBean bean){if(bean==null)return null;long bytes=bean.getThreadAllocatedBytes(Thread.currentThread().getId());return bytes<0?null:bytes;}
}
