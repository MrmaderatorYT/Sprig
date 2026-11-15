package com.ccs.sprig.core.websocket;

import java.io.IOException;
import com.ccs.sprig.core.memory.MemoryBudget;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Data-message holds run on delivery workers, never on frame/control readers. */
public final class WebSocketInterceptor {
    public static final long HOLD_SECONDS=60;
    public static final class Pending {
        public final long id,sessionId,createdMillis=System.currentTimeMillis();
        public final String direction;
        public final int opcode;
        public final byte[] payload;
        private final CountDownLatch latch=new CountDownLatch(1);
        private volatile byte[] forwarded;
        private final MemoryBudget.Reservation original;private MemoryBudget.Reservation edited;
        Pending(long id,long sessionId,String direction,int opcode,byte[] payload,MemoryBudget.Reservation original){this.original=original;this.id=id;this.sessionId=sessionId;this.direction=direction;this.opcode=opcode;this.payload=payload.clone();}
    }
    private final LinkedHashMap<Long,Pending> pending=new LinkedHashMap<>();
    private final AtomicLong ids=new AtomicLong();
    private final CopyOnWriteArrayList<Runnable> listeners=new CopyOnWriteArrayList<>();
    private volatile boolean enabled;
    private int pendingBytes;
    private final long holdMillis;private final MemoryBudget budget;
    public WebSocketInterceptor(){this(new MemoryBudget(9L*1024*1024),TimeUnit.SECONDS.toMillis(HOLD_SECONDS));}
    public WebSocketInterceptor(MemoryBudget budget){this(budget,TimeUnit.SECONDS.toMillis(HOLD_SECONDS));}
    WebSocketInterceptor(long holdMillis){this(new MemoryBudget(9L*1024*1024),holdMillis);}
    private WebSocketInterceptor(MemoryBudget budget,long holdMillis){if(budget==null)throw new NullPointerException("Memory budget required");this.budget=budget;if(holdMillis<1)throw new IllegalArgumentException("Positive hold deadline required");this.holdMillis=holdMillis;}
    public boolean isEnabled(){return enabled;}
    public void setEnabled(boolean value){synchronized(this){enabled=value;if(!value){for(Pending p:pending.values()){p.forwarded=p.payload;p.latch.countDown();}pending.clear();pendingBytes=0;}}changed();}
    public synchronized List<Pending> pending(){return new ArrayList<>(pending.values());}
    byte[] intercept(long sessionId,String direction,int opcode,byte[] payload)throws IOException {
        return intercept(sessionId,direction,opcode,payload,()->true);
    }
    byte[] intercept(long sessionId,String direction,int opcode,byte[] payload,BooleanSupplier sessionOpen)throws IOException {
        try(Decision decision=interceptLeased(sessionId,direction,opcode,payload,sessionOpen)){return decision.payload;}
    }
    /** Keeps held/edited buffers charged until the delivery worker finishes using them. */
    Decision interceptLeased(long sessionId,String direction,int opcode,byte[] payload,BooleanSupplier sessionOpen)throws IOException {
        Pending p;
        synchronized(this){
            if(!enabled)return new Decision(payload,null);if(!sessionOpen.getAsBoolean())return new Decision(null,null);
            if(pending.size()>=16||pendingBytes+payload.length>4*1024*1024)throw new IOException("WebSocket interception queue is full");
            MemoryBudget.Reservation lease=budget.tryReserve(MemoryBudget.Category.WEBSOCKET_INTERCEPT,payload.length+256L);
            if(lease==null)throw new IOException("Shared memory budget is busy: WebSocket hold");
            try{p=new Pending(ids.incrementAndGet(),sessionId,direction,opcode,payload,lease);}catch(RuntimeException|Error failure){lease.close();throw failure;}
            pending.put(p.id,p);pendingBytes+=payload.length;
        }
        changed();boolean transferred=false;
        try{
            if(!p.latch.await(holdMillis,TimeUnit.MILLISECONDS))throw new IOException("WebSocket interception decision timed out");
            Decision result=new Decision(p.forwarded,p);transferred=true;return result;
        }catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new IOException("WebSocket interception cancelled",cancelled);}
        finally{synchronized(this){remove(p.id);}if(!transferred)release(p);changed();}
    }
    static final class Decision implements AutoCloseable {
        final byte[] payload;private final Pending pending;
        Decision(byte[] payload,Pending pending){this.payload=payload;this.pending=pending;}
        @Override public void close(){if(pending!=null)release(pending);}
    }
    private static void release(Pending p){p.original.close();if(p.edited!=null)p.edited.close();}
    public void forward(long id,byte[] payload)throws IOException {
        if(payload==null||payload.length>WebSocketFrames.MAX_MESSAGE_BYTES)throw new IOException("Edited message exceeds 1 MiB");
        synchronized(this){Pending p=pending.get(id);if(p!=null){if(p.opcode==1)WebSocketFrames.validateText(payload);MemoryBudget.Reservation lease=budget.tryReserve(MemoryBudget.Category.WEBSOCKET_INTERCEPT,payload.length+64L);if(lease==null)throw new IOException("Shared memory budget is busy: edited WebSocket message");try{p.forwarded=payload.clone();p.edited=lease;}catch(RuntimeException|Error failure){lease.close();throw failure;}remove(id);p.latch.countDown();}}changed();
    }
    public void drop(long id){synchronized(this){Pending p=remove(id);if(p!=null)p.latch.countDown();}changed();}
    public void cancelSession(long session){synchronized(this){for(Pending p:new ArrayList<>(pending.values()))if(p.sessionId==session){remove(p.id);p.latch.countDown();}}changed();}
    public void cancelAll(){synchronized(this){for(Pending p:pending.values())p.latch.countDown();pending.clear();pendingBytes=0;}changed();}
    private Pending remove(long id){Pending p=pending.remove(id);if(p!=null)pendingBytes-=p.payload.length;return p;}
    public void addListener(Runnable listener){listeners.addIfAbsent(listener);}
    public void removeListener(Runnable listener){listeners.remove(listener);}
    private void changed(){for(Runnable listener:listeners)try{listener.run();}catch(RuntimeException ignored){}}
}
