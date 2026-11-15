package com.ccs.sprig.benchmark;

import java.util.concurrent.atomic.AtomicLongArray;

/** Fixed 4096-bin logarithmic histogram, with percentile upper bounds within ~1.6%. */
final class LatencyHistogram {
    private final AtomicLongArray bins=new AtomicLongArray(4096);
    void record(long nanos) {
        long value=Math.max(1,nanos);int exponent=63-Long.numberOfLeadingZeros(value);long base=1L<<exponent;
        int fraction=(int)(((double)(value-base)/base)*64);bins.incrementAndGet(exponent*64+Math.min(63,fraction));
    }
    long count(){long total=0;for(int i=0;i<bins.length();i++)total+=bins.get(i);return total;}
    long percentileUpperNanos(double quantile) {
        if(!(quantile>0&&quantile<=1))throw new IllegalArgumentException("Quantile must be in (0,1]");
        long total=count();if(total==0)return 0;long needed=(long)Math.ceil(total*quantile),seen=0;
        for(int i=0;i<bins.length();i++){seen+=bins.get(i);if(seen>=needed){int exponent=i/64,fraction=i%64;double upper=Math.scalb(1.0,exponent)*(1.0+(fraction+1)/64.0);return upper>=Long.MAX_VALUE?Long.MAX_VALUE:Math.max(1,(long)Math.ceil(upper));}}
        return Long.MAX_VALUE;
    }
}
