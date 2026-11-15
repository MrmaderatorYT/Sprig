package com.ccs.sprig.core.intruder;

import com.ccs.sprig.core.model.Exchange;
import com.ccs.sprig.core.memory.MemoryBudget;
import com.ccs.sprig.core.net.CallCancellation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A resumable in-process run. Pause stops new launches; cancellation closes only its own network handles. */
public final class IntruderAttack {
    public enum State {RUNNING,PAUSED,CANCELLING,CANCELLED,COMPLETED,FAILED}
    public final long id;public final IntruderConfig config;public final IntruderPlan plan;
    private static final ScheduledThreadPoolExecutor DEADLINES=deadlines();
    private static ScheduledThreadPoolExecutor deadlines(){ScheduledThreadPoolExecutor out=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"sprig-intruder-deadline");t.setDaemon(true);return t;});out.setRemoveOnCancelPolicy(true);return out;}
    private final Object gate=new Object();
    private final IntruderEngine.Transport transport;
    private final MemoryBudget budget;private final MemoryBudget.Reservation configurationLease;
    private final LinkedHashMap<Long,MemoryBudget.Reservation> resultLeases=new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Runnable> listeners=new CopyOnWriteArrayList<>();
    private final LinkedHashMap<Long,IntruderResult> retained=new LinkedHashMap<>();
    private final Set<CallCancellation.Token> tokens=new HashSet<>();
    private final ThreadPoolExecutor workers;
    private final Thread coordinator;
    private volatile State state=State.RUNNING;private volatile boolean cancelled;
    private long dispatched,completed,errors,evicted,memory,nextLaunch,finishedMillis;private int active;
    private String message="";
    private final long startedMillis=System.currentTimeMillis();
    IntruderAttack(long id,IntruderConfig config,IntruderEngine.Transport transport,MemoryBudget budget) {
        this.id=id;this.config=config;this.transport=transport;this.budget=budget;
        long owned=4096+2L*config.markedTemplate.length+2L*config.url.length()+2L*config.protocol.length()+2L*config.grepExtract.length();
        for(String match:config.grepMatches)owned=checkedEstimate(owned,2L*match.length());
        for(PayloadSource source:config.payloadSets)owned=checkedEstimate(owned,source.estimatedBytes());
        for(PayloadProcessor processor:config.processors)owned=checkedEstimate(owned,processor.estimatedBytes());
        for(List<PayloadProcessor> pipeline:config.setProcessors.values())for(PayloadProcessor processor:pipeline)owned=checkedEstimate(owned,processor.estimatedBytes());
        configurationLease=budget.tryReserve(MemoryBudget.Category.INTRUDER,owned);
        if(configurationLease==null)throw new IllegalStateException("Shared memory budget is busy: Intruder configuration");
        try{plan=new IntruderPlan(config);
        workers=new ThreadPoolExecutor(config.concurrency,config.concurrency,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(config.concurrency),r->{Thread t=new Thread(r,"sprig-intruder-request");t.setDaemon(true);return t;});
        coordinator=new Thread(this::run,"sprig-intruder-run");coordinator.setDaemon(true);
        }catch(RuntimeException|Error failure){configurationLease.close();throw failure;}
    }
    void begin(){coordinator.start();}
    public State state(){return state;}
    public boolean isTerminal(){return state==State.CANCELLED||state==State.COMPLETED||state==State.FAILED;}
    public void pause(){synchronized(gate){if(state==State.RUNNING){state=State.PAUSED;gate.notifyAll();}}changed();}
    public void resume(){synchronized(gate){if(state==State.PAUSED){state=State.RUNNING;gate.notifyAll();}}changed();}
    public void cancel(){
        synchronized(gate){if(isTerminal()||cancelled)return;cancelled=true;state=State.CANCELLING;gate.notifyAll();}
        // Network close callbacks run on the coordinator/own request workers, never the caller's UI thread.
        coordinator.interrupt();workers.shutdownNow();changed();
    }
    public Stats stats(){synchronized(gate){return new Stats(plan.requestCount(),dispatched,completed,errors,active,startedMillis,finishedMillis,evicted,memory,message);}}
    public List<IntruderResult> results(){synchronized(gate){List<IntruderResult> result=new ArrayList<>(retained.values());Collections.reverse(result);return result;}}
    /** Regenerates the complete request for Send to Repeater without retaining every generated body. */
    public byte[] requestForResult(long number)throws IOException {return plan.caseAt(number-1).rawRequest;}
    public void addListener(Runnable value){listeners.addIfAbsent(value);}public void removeListener(Runnable value){listeners.remove(value);}
    private void run() {
        CompletionService<IntruderResult> completion=new ExecutorCompletionService<>(workers);long next=0;int inFlight=0;
        try {
            while(!cancelled) {
                synchronized(gate){if(inFlight==0&&(next>=plan.requestCount()||dispatched>=config.requestBudget))break;}
                synchronized(gate){while(state==State.PAUSED&&inFlight==0&&!cancelled)gate.wait();}
                while(state==State.RUNNING&&!cancelled&&inFlight<config.concurrency&&next<plan.requestCount()) {
                    synchronized(gate){if(dispatched>=config.requestBudget)break;}
                    long index=next++;completion.submit(()->execute(index));inFlight++;
                }
                if(inFlight==0){synchronized(gate){if(state==State.PAUSED&&!cancelled)continue;}break;}
                Future<IntruderResult> done=completion.poll(200,TimeUnit.MILLISECONDS);if(done==null)continue;inFlight--;
                IntruderResult result=done.get();if(result!=null)record(result);
                synchronized(gate){if(dispatched>=config.requestBudget&&next<plan.requestCount())message="Request budget reached (retries count toward the budget)";}
            }
        }catch(InterruptedException e){if(!cancelled){Thread.currentThread().interrupt();fail("Attack interrupted");}}
        catch(Exception e){if(!cancelled)fail(safe(e));}
        finally {
            List<CallCancellation.Token> stopping;synchronized(gate){stopping=new ArrayList<>(tokens);}for(CallCancellation.Token token:stopping)token.cancel();
            workers.shutdownNow();try{workers.awaitTermination(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            synchronized(gate){finishedMillis=System.currentTimeMillis();if(cancelled)state=State.CANCELLED;else if(state!=State.FAILED)state=State.COMPLETED;gate.notifyAll();}changed();
        }
    }
    private IntruderResult execute(long index) {
        IntruderPlan.Case item;
        try{item=plan.caseAt(index);}catch(Exception e){item=new IntruderPlan.Case(index,-1,new byte[0],Collections.emptyList());return new IntruderResult(config,item,null,safe(e),0,0);}
        String method=new String(item.rawRequest,0,Math.min(item.rawRequest.length,32),StandardCharsets.ISO_8859_1).split(" ",2)[0];
        boolean retrySafe=method.equals("GET")||method.equals("HEAD")||method.equals("OPTIONS")||method.equals("PUT")||method.equals("DELETE");
        Exchange exchange=null;String failure="";int attempts=0;long duration=0;
        for(int attempt=0;attempt<=config.retries&&!cancelled;attempt++) {
            CallCancellation.Token token=new CallCancellation.Token();AtomicBoolean expired=new AtomicBoolean(),finished=new AtomicBoolean();ScheduledFuture<?> timeout=null;
            try {
                if(!admit(token))return attempts==0?null:new IntruderResult(config,item,exchange,failure,attempts,duration);
                attempts++;long began=System.nanoTime();
                try(CallCancellation.Scope scope=CallCancellation.enter(token)) {
                    timeout=DEADLINES.schedule(()->{if(finished.compareAndSet(false,true)){expired.set(true);token.cancel();}},config.timeoutMillis,TimeUnit.MILLISECONDS);
                    CallCancellation.check();exchange=transport.send(config.url,item.rawRequest,config.protocol);
                    finished.compareAndSet(false,true);
                    if(expired.get())throw new IOException("Request timed out after "+config.timeoutMillis+" ms");
                    failure=exchange.error==null?"":exchange.error;
                }catch(IOException|RuntimeException e){failure=expired.get()?"Request timed out after "+config.timeoutMillis+" ms":cancelled?"Attack cancelled":safe(e);exchange=null;}
                finally{duration+=Math.max(0,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began));}
            }catch(InterruptedException e){Thread.currentThread().interrupt();return attempts==0?null:new IntruderResult(config,item,exchange,"Attack cancelled",attempts,duration);}
            finally {
                finished.set(true);if(timeout!=null)timeout.cancel(false);
                synchronized(gate){if(tokens.remove(token))active--;gate.notifyAll();}changed();
            }
            boolean retryable=exchange==null||exchange.status==502||exchange.status==503||exchange.status==504;
            if(!retrySafe||!retryable||attempt==config.retries)break;
        }
        return attempts==0?null:new IntruderResult(config,item,exchange,failure,attempts,duration);
    }
    private boolean admit(CallCancellation.Token token)throws InterruptedException {
        synchronized(gate) {
            for(;;) {
                if(cancelled||dispatched>=config.requestBudget)return false;
                if(state==State.PAUSED){gate.wait();continue;}
                long now=System.nanoTime(),wait=nextLaunch-now;
                if(wait>0){TimeUnit.NANOSECONDS.timedWait(gate,wait);continue;}
                nextLaunch=now+TimeUnit.MILLISECONDS.toNanos(config.intervalMillis);dispatched++;active++;tokens.add(token);return true;
            }
        }
    }
    private void record(IntruderResult result) {
        synchronized(gate) {
            completed++;if(!result.error.isEmpty()||result.status>=400)errors++;
            long size=result.memoryBytes();
            while(!retained.isEmpty()&&(retained.size()>=config.resultLimit||size>config.memoryBytes-memory))evictOldest();
            MemoryBudget.Reservation lease=size>config.memoryBytes?null:budget.tryReserve(MemoryBudget.Category.INTRUDER,size);
            while(lease==null&&!retained.isEmpty()){evictOldest();lease=size>config.memoryBytes?null:budget.tryReserve(MemoryBudget.Category.INTRUDER,size);}
            if(lease==null){evicted++;message="Result omitted: shared memory budget is busy";}
            else{retained.put(result.number,result);resultLeases.put(result.number,lease);memory+=size;}
        }changed();
    }
    private void evictOldest(){Iterator<IntruderResult> values=retained.values().iterator();IntruderResult oldest=values.next();values.remove();memory-=oldest.memoryBytes();resultLeases.remove(oldest.number).close();evicted++;}
    /** Registry eviction/Forget releases ownership; exported snapshots held by callers remain caller-owned. */
    void releaseRetainedMemory(){synchronized(gate){if(!isTerminal())throw new IllegalStateException("Attack is still active");for(MemoryBudget.Reservation lease:resultLeases.values())lease.close();resultLeases.clear();retained.clear();memory=0;configurationLease.close();}}
    private static long checkedEstimate(long total,long value){if(value<0||value>Long.MAX_VALUE-total)throw new IllegalArgumentException("Invalid retained payload/processor estimate");return total+value;}
    private void fail(String reason){synchronized(gate){state=State.FAILED;message=reason;}changed();}
    private void changed(){for(Runnable listener:listeners)try{listener.run();}catch(RuntimeException ignored){}}
    private static String safe(Throwable e){String s=e.getMessage();if(s==null||s.isEmpty())s=e.getClass().getSimpleName();s=s.replace('\r',' ').replace('\n',' ');return s.length()>512?s.substring(0,512):s;}
    public static final class Stats {
        public final long total,dispatched,completed,errors,startedMillis,finishedMillis,evicted,memoryBytes;public final int active;public final String message;
        Stats(long total,long dispatched,long completed,long errors,int active,long started,long ended,long evicted,long memory,String message){this.total=total;this.dispatched=dispatched;this.completed=completed;this.errors=errors;this.active=active;startedMillis=started;finishedMillis=ended;this.evicted=evicted;memoryBytes=memory;this.message=message;}
    }
}
