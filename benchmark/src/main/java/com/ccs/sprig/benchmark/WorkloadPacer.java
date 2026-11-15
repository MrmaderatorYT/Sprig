package com.ccs.sprig.benchmark;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** One monotonic rate gate with ordered slot reservations shared by main HTTP, WS, Intruder and Scanner sends. */
final class WorkloadPacer {
    private final long intervalNanos;
    private long next;
    WorkloadPacer(int operationsPerSecond) {
        if(operationsPerSecond<0||operationsPerSecond>10000)throw new IllegalArgumentException("Rate must be 0–10000");
        intervalNanos=operationsPerSecond==0?0:(1_000_000_000L+operationsPerSecond-1)/operationsPerSecond;
    }
    boolean await(BooleanSupplier continuing,long deadline)throws InterruptedException {
        if(Thread.interrupted())throw new InterruptedException("Pacing interrupted");
        if(!continuing.getAsBoolean()||deadline-System.nanoTime()<=0)return false;
        if(intervalNanos==0)return true;
        long slot;
        synchronized(this){long now=System.nanoTime();slot=next==0||next-now<0?now:next;next=slot+intervalNanos;}
        for(;;){if(!continuing.getAsBoolean())return false;long now=System.nanoTime(),remaining=deadline-now;if(remaining<=0)return false;long wait=slot-now;if(wait<=0)return true;TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(25),Math.min(wait,remaining)));}
    }
}
