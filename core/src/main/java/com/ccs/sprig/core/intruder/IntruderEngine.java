package com.ccs.sprig.core.intruder;

import com.ccs.sprig.core.model.Exchange;
import com.ccs.sprig.core.memory.MemoryBudget;
import com.ccs.sprig.core.proxy.ProxyServer;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** One active phone attack at a time, with two recent runs retained only in process memory. */
public final class IntruderEngine {
    @FunctionalInterface public interface Transport {Exchange send(String url,byte[] rawRequest,String protocol)throws IOException;}
    private static final AtomicLong IDS=new AtomicLong();
    private static final LinkedHashMap<Long,IntruderAttack> RUNS=new LinkedHashMap<>();
    private final Transport transport;
    private final MemoryBudget budget;
    public IntruderEngine(Transport transport){this(transport,new MemoryBudget(64L*1024*1024));}
    public IntruderEngine(Transport transport,MemoryBudget budget){
        if(transport==null||budget==null)throw new NullPointerException("Transport and memory budget required");this.transport=transport;this.budget=budget;
    }
    public static IntruderAttack start(ProxyServer proxy,IntruderConfig config){
        return new IntruderEngine((url,raw,protocol)->proxy.replay(url,raw,protocol,config.preserveHost),proxy.getMemoryBudget()).start(config);
    }
    public IntruderAttack start(IntruderConfig config){
        synchronized(RUNS){
            for(IntruderAttack existing:RUNS.values())if(!existing.isTerminal())throw new IllegalStateException("Pause/resume or cancel the existing Intruder attack before starting another");
            while(RUNS.size()>=2){Iterator<IntruderAttack> oldest=RUNS.values().iterator();IntruderAttack discarded=oldest.next();oldest.remove();discarded.releaseRetainedMemory();}
            IntruderAttack attack=new IntruderAttack(IDS.incrementAndGet(),config,transport,budget);RUNS.put(attack.id,attack);
            attack.begin();return attack;
        }
    }
    public static IntruderAttack latest(){synchronized(RUNS){IntruderAttack latest=null;for(IntruderAttack value:RUNS.values())latest=value;return latest;}}
    public static IntruderAttack find(long id){synchronized(RUNS){return RUNS.get(id);}}
    public static void forget(long id){
        synchronized(RUNS){IntruderAttack attack=RUNS.get(id);if(attack!=null&&!attack.isTerminal())throw new IllegalStateException("Cancel the attack before clearing its results");RUNS.remove(id);if(attack!=null)attack.releaseRetainedMemory();}
    }
}
