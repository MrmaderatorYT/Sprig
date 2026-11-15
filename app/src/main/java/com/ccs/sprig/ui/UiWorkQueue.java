package com.ccs.sprig.ui;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Small UI-owned I/O queues never execute rejected work on the submitting/main thread. */
final class UiWorkQueue {
    private UiWorkQueue() { }
    static ThreadPoolExecutor create(String name,int capacity){
        ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,10,TimeUnit.SECONDS,new ArrayBlockingQueue<>(capacity),task->{Thread thread=new Thread(task,name);thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);return executor;
    }
    static boolean submit(Executor executor,Runnable task,Runnable rejected){
        try{executor.execute(task);return true;}catch(RejectedExecutionException busy){rejected.run();return false;}
    }
}
