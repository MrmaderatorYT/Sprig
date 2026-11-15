package com.ccs.sprig.core.model;

import com.ccs.sprig.core.memory.MemoryBudget;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** Ephemeral bounded history. No request secrets are persisted to disk. */
public final class SessionStore {
    private final LinkedHashMap<Long,Stored> entries=new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Runnable> listeners=new CopyOnWriteArrayList<>();
    private final AtomicLong ids=new AtomicLong();
    private final int maxEntries;
    private final long maxBytes;
    private final MemoryBudget budget;
    private long bytes,dropped;
    public SessionStore(){this(1000,24L*1024*1024);}
    public SessionStore(int maxEntries,long maxBytes){this(maxEntries,maxBytes,new MemoryBudget(defaultLimit(maxEntries,maxBytes)));}
    public SessionStore(int maxEntries,long maxBytes,MemoryBudget budget){
        if(maxEntries<1||maxBytes<1||budget==null)throw new IllegalArgumentException("Positive bounds and memory budget required");
        this.maxEntries=maxEntries;this.maxBytes=maxBytes;this.budget=budget;
    }
    private static long defaultLimit(int count,long bytes){long overhead=320L*Math.max(1,count);return bytes>Long.MAX_VALUE-overhead?Long.MAX_VALUE:Math.max(1,bytes+overhead);}
    public MemoryBudget memoryBudget(){return budget;}
    public long nextId(){return ids.incrementAndGet();}
    public void record(Exchange source){record(source,false);}
    /** Transfers read-only body ownership. Caller must never mutate these captures after publication. */
    public void recordOwned(Exchange source){record(source,true);}
    private void record(Exchange source,boolean owned){
        Exchange e=source.copy();long size=e.capturedBytes();
        synchronized(this){
            if(e.id==0)e.id=nextId();Stored previous=entries.remove(e.id);if(previous!=null)release(previous);
            if(size>maxBytes||size>Long.MAX_VALUE-320){while(!entries.isEmpty())evictOldest();dropped++;}
            else{
                while(!entries.isEmpty()&&(entries.size()>=maxEntries||size>maxBytes-bytes))evictOldest();
                MemoryBudget.Reservation reservation=budget.tryReserve(MemoryBudget.Category.HISTORY,size+320);
                while(reservation==null&&!entries.isEmpty()){evictOldest();reservation=budget.tryReserve(MemoryBudget.Category.HISTORY,size+320);}
                if(reservation==null)dropped++;
                else{
                    try{
                        // Reserve before defensive copies; transferred captures reuse their read-only arrays.
                        e.requestBody=e.requestBody==null?new byte[0]:owned?e.requestBody:e.requestBody.clone();
                        e.responseBody=e.responseBody==null?new byte[0]:owned?e.responseBody:e.responseBody.clone();
                        entries.put(e.id,new Stored(e,size,reservation));bytes+=size;
                    }catch(RuntimeException|Error error){reservation.close();throw error;}
                }
            }
        }changed();
    }
    private void evictOldest(){Iterator<Stored> iterator=entries.values().iterator();Stored oldest=iterator.next();iterator.remove();release(oldest);}
    private void release(Stored value){bytes-=value.size;value.reservation.close();}
    /** Newest first; metadata snapshots with read-only body arrays. External retained snapshots are not budget-owned. */
    public synchronized List<Exchange> snapshot(){List<Exchange> result=new ArrayList<>(entries.size());for(Stored stored:entries.values())result.add(stored.exchange.copy());Collections.reverse(result);return result;}
    public synchronized long memoryBytes(){return bytes;}
    public synchronized long droppedRecords(){return dropped;}
    public synchronized int size(){return entries.size();}
    public void clear(){synchronized(this){for(Stored value:entries.values())value.reservation.close();entries.clear();bytes=0;}changed();}
    public void addListener(Runnable listener){listeners.addIfAbsent(listener);}
    public void removeListener(Runnable listener){listeners.remove(listener);}
    private void changed(){for(Runnable listener:listeners)try{listener.run();}catch(RuntimeException ignored){}}
    private static final class Stored {
        final Exchange exchange;final long size;final MemoryBudget.Reservation reservation;
        Stored(Exchange exchange,long size,MemoryBudget.Reservation reservation){this.exchange=exchange;this.size=size;this.reservation=reservation;}
    }
}
