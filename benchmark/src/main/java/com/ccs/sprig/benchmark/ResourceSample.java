package com.ccs.sprig.benchmark;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.GarbageCollectorMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Host JVM measurements. Unsupported RSS/FD/process CPU counters remain null rather than zero. */
final class ResourceSample {
    final Map<String,Object> values=new LinkedHashMap<>();
    private ResourceSample(){}
    static ResourceSample capture(long elapsedMillis,String phase) {
        ResourceSample sample=new ResourceSample();Map<String,Object> out=sample.values;
        out.put("elapsedMillis",elapsedMillis);out.put("phase",phase);out.put("heapUsedBytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());out.put("heapCommittedBytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getCommitted());
        out.put("threads",ManagementFactory.getThreadMXBean().getThreadCount());out.put("peakThreads",ManagementFactory.getThreadMXBean().getPeakThreadCount());
        long count=0,time=0;boolean countAvailable=true,timeAvailable=true;
        for(GarbageCollectorMXBean collector:ManagementFactory.getGarbageCollectorMXBeans()){long n=collector.getCollectionCount(),t=collector.getCollectionTime();if(n<0)countAvailable=false;else count+=n;if(t<0)timeAvailable=false;else time+=t;}
        out.put("gcCollections",countAvailable?count:null);out.put("gcTimeMillis",timeAvailable?time:null);
        Object bean=ManagementFactory.getOperatingSystemMXBean();
        out.put("processCpuNanos",bean instanceof com.sun.management.OperatingSystemMXBean?nonnegative(((com.sun.management.OperatingSystemMXBean)bean).getProcessCpuTime()):null);
        out.put("openFileDescriptors",bean instanceof com.sun.management.UnixOperatingSystemMXBean?nonnegative(((com.sun.management.UnixOperatingSystemMXBean)bean).getOpenFileDescriptorCount()):null);
        out.put("rssBytes",linuxRss());return sample;
    }
    private static Long nonnegative(long value){return value<0?null:value;}
    private static Long linuxRss(){Path file=Path.of("/proc/self/status");if(!Files.isRegularFile(file))return null;try{for(String line:Files.readAllLines(file))if(line.startsWith("VmRSS:")){String[] parts=line.trim().split("\\s+");return Math.multiplyExact(Long.parseLong(parts[1]),1024L);}}catch(IOException|RuntimeException ignored){}return null;}
}
