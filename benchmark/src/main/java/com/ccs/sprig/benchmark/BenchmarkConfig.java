package com.ccs.sprig.benchmark;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Explicit, bounded local workloads. No target URL or public-network option exists. */
public final class BenchmarkConfig {
    public final int seconds,warmupSeconds,concurrency,bodyBytes,ratePerSecond;
    public final boolean mixed;
    public final Path output;
    public BenchmarkConfig(int seconds,int warmupSeconds,int concurrency,int bodyBytes,boolean mixed,Path output) {
        this(seconds,warmupSeconds,concurrency,bodyBytes,mixed,100,output);
    }
    public BenchmarkConfig(int seconds,int warmupSeconds,int concurrency,int bodyBytes,boolean mixed,int ratePerSecond,Path output) {
        if(seconds<1||seconds>3600||warmupSeconds<0||warmupSeconds>60||concurrency<1||concurrency>4||bodyBytes<0||bodyBytes>256*1024||ratePerSecond<0||ratePerSecond>10000||output==null)
            throw new IllegalArgumentException("Use seconds 1–3600, warmup 0–60, concurrency 1–4, body bytes 0–262144 rate 0–10000 operations/s and an output path");
        this.ratePerSecond=ratePerSecond;this.seconds=seconds;this.warmupSeconds=warmupSeconds;this.concurrency=concurrency;this.bodyBytes=bodyBytes;this.mixed=mixed;this.output=output;
    }
    public static BenchmarkConfig parse(String[] arguments) {
        int seconds=15,warmup=3,concurrency=2,body=4096,rate=100;boolean mixed=false;Path output=Path.of("dist/benchmarks/host-latest.json");Set<String> seen=new HashSet<>();
        for(int i=0;i<arguments.length;i+=2) {
            if(i+1>=arguments.length||!seen.add(arguments[i]))throw new IllegalArgumentException("Every option needs one value and may occur once");
            String value=arguments[i+1];
            switch(arguments[i]) {
                case "--seconds":seconds=Integer.parseInt(value);break;
                case "--warmup-seconds":warmup=Integer.parseInt(value);break;
                case "--concurrency":concurrency=Integer.parseInt(value);break;
                case "--body-bytes":body=Integer.parseInt(value);break;
                case "--rate-per-second":rate=Integer.parseInt(value);break;
                case "--scenario":if(!value.equals("http")&&!value.equals("mixed"))throw new IllegalArgumentException("Scenario must be http or mixed");mixed=value.equals("mixed");break;
                case "--output":output=Path.of(value);break;
                default:throw new IllegalArgumentException("Unknown option: "+arguments[i]);
            }
        }
        return new BenchmarkConfig(seconds,warmup,concurrency,body,mixed,rate,output);
    }
}
