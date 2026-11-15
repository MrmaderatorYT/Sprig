package com.ccs.sprig.core.net;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Serial lifecycle commands: one running command and at most one newest pending command. */
public final class LatestTaskExecutor implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private boolean closed;
    public LatestTaskExecutor(String name) {
        executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r->{Thread t=new Thread(r,name);t.setDaemon(true);return t;});
    }
    /** Returns false after shutdown. Replaced pending commands must own no resources yet. */
    public synchronized boolean executeLatest(Runnable command) {
        if(command==null)throw new NullPointerException("command");
        if(closed)return false;
        executor.getQueue().clear();
        executor.execute(command);return true;
    }
    /** The final cleanup cannot be replaced by another command or interrupted by shutdown. */
    public synchronized void closeAfter(Runnable cleanup) {
        if(closed)return;
        executeLatest(cleanup);closed=true;executor.shutdown();
    }
    public int pendingCount(){return executor.getQueue().size();}
    public boolean awaitTermination(long timeout,TimeUnit unit)throws InterruptedException{return executor.awaitTermination(timeout,unit);}
    @Override public void close(){closeAfter(()->{});}
}
