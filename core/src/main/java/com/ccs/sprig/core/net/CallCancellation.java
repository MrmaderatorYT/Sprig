package com.ccs.sprig.core.net;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

/** A request-local cancellation boundary. Capture and unrelated calls never share this token. */
public final class CallCancellation {
    private static final ThreadLocal<Token> CURRENT = new ThreadLocal<>();
    private CallCancellation() { }

    public static final class Token {
        private final Map<Closeable,Boolean> handles = new IdentityHashMap<>();
        private volatile boolean cancelled;
        public boolean isCancelled() { return cancelled; }
        public void cancel() {
            ArrayList<Closeable> closing;
            synchronized(this) {
                if(cancelled) return;
                cancelled=true; closing=new ArrayList<>(handles.keySet()); handles.clear();
            }
            // Never hold the token monitor while a TLS/QUIC close invokes callbacks.
            for(Closeable handle:closing) quietlyClose(handle);
        }
        private void register(Closeable handle) {
            boolean close;
            synchronized(this) { close=cancelled;if(!close)handles.put(handle,Boolean.TRUE); }
            if(close)quietlyClose(handle);
        }
    }

    public static final class Scope implements AutoCloseable {
        private final Thread owner=Thread.currentThread();
        private final Token token,previous;
        private boolean closed;
        private Scope(Token token) {this.token=token;previous=CURRENT.get();CURRENT.set(token);}
        @Override public void close() {
            if(Thread.currentThread()!=owner)throw new IllegalStateException("Close the call scope on its creating thread");
            if(closed)return;closed=true;
            if(previous==null)CURRENT.remove();else CURRENT.set(previous);
            token.cancel();
        }
    }
    public static Scope enter(Token token) {
        if(token==null)throw new NullPointerException("Cancellation token required");
        return new Scope(token);
    }
    public static void check() throws InterruptedIOException {
        Token token=CURRENT.get();
        if(Thread.currentThread().isInterrupted()||(token!=null&&token.isCancelled()))throw new InterruptedIOException("Request cancelled");
    }
    public static void track(Closeable handle) throws IOException {
        Token token=CURRENT.get();
        if(token==null)return;
        token.register(handle);check();
    }
    private static void quietlyClose(Closeable handle) {try{handle.close();}catch(IOException|RuntimeException ignored){}}
}
