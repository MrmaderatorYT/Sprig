package com.ccs.sprig.core.memory;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** Shared admission for explicitly owned byte buffers. Estimates are not a measurement of JVM heap. */
public final class MemoryBudget {
    public enum Category { HISTORY, WEBSOCKET_HISTORY, WEBSOCKET_QUEUE, WEBSOCKET_INTERCEPT, INTRUDER, HTTP_BODY, SCANNER }
    private final long limitBytes;
    private final long[] categories=new long[Category.values().length];
    private long usedBytes,peakBytes,rejectedReservations;
    public MemoryBudget(long limitBytes){if(limitBytes<1)throw new IllegalArgumentException("Positive memory limit required");this.limitBytes=limitBytes;}
    public synchronized Reservation tryReserve(Category category,long bytes){
        if(category==null)throw new NullPointerException("Category required");check(bytes);
        if(bytes>limitBytes-usedBytes){rejectedReservations++;return null;}
        add(category,bytes);return new Reservation(category,bytes);
    }
    public synchronized Snapshot snapshot(){
        EnumMap<Category,Long> values=new EnumMap<>(Category.class);for(Category category:Category.values())values.put(category,categories[category.ordinal()]);
        return new Snapshot(limitBytes,usedBytes,peakBytes,rejectedReservations,Collections.unmodifiableMap(values));
    }
    private void add(Category category,long delta){usedBytes+=delta;categories[category.ordinal()]+=delta;peakBytes=Math.max(peakBytes,usedBytes);}
    private static void check(long bytes){if(bytes<0)throw new IllegalArgumentException("Negative memory reservation");}
    /** An ownership lease; shrinking/releasing is synchronous, allocation admission is fail-fast. */
    public final class Reservation implements AutoCloseable {
        private final Category category;
        private long bytes;
        private boolean closed;
        private Reservation(Category category,long bytes){this.category=category;this.bytes=bytes;}
        public long bytes(){synchronized(MemoryBudget.this){return bytes;}}
        public boolean resize(long newBytes){
            check(newBytes);synchronized(MemoryBudget.this){
                if(closed)throw new IllegalStateException("Reservation is closed");long delta=newBytes-bytes;
                if(delta>limitBytes-usedBytes){rejectedReservations++;return false;}
                add(category,delta);bytes=newBytes;return true;
            }
        }
        @Override public void close(){synchronized(MemoryBudget.this){if(!closed){closed=true;add(category,-bytes);bytes=0;}}}
    }
    public static final class Snapshot {
        public final long limitBytes,usedBytes,peakBytes,rejectedReservations;
        public final Map<Category,Long> categories;
        private Snapshot(long limit,long used,long peak,long rejected,Map<Category,Long> categories){limitBytes=limit;usedBytes=used;peakBytes=peak;rejectedReservations=rejected;this.categories=categories;}
    }
}
