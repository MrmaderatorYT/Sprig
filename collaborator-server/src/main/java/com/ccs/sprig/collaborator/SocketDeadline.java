package com.ccs.sprig.collaborator;

import java.net.Socket;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Absolute lifetime bound; unlike SO_TIMEOUT this also stops continuously trickled input. */
final class SocketDeadline {
    private static final ScheduledThreadPoolExecutor TIMER=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"sprig-oast-deadline");t.setDaemon(true);return t;});
    static {TIMER.setRemoveOnCancelPolicy(true);}
    static ScheduledFuture<?> closeAfter(Socket socket,long millis){return TIMER.schedule(()->{try{socket.close();}catch(java.io.IOException ignored){}},millis,TimeUnit.MILLISECONDS);}
    private SocketDeadline(){}
}
