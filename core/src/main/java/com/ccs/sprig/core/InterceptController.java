package com.ccs.sprig.core;

import com.ccs.sprig.core.model.Exchange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Bounded interception queue: a worker waits, never the Android main thread. */
public final class InterceptController {
    public static final int MAX_PENDING = 16, MAX_EDIT_BYTES = 4 * 1024 * 1024 + 64 * 1024;
    public static final long TIMEOUT_SECONDS = 120;
    public static final class Pending {
        public final long id;
        public final Exchange exchange;
        public final byte[] rawRequest;
        private final CountDownLatch decision = new CountDownLatch(1);
        private volatile byte[] forwarded;
        private Pending(Exchange e, byte[] raw) { id = e.id; exchange = e.copy(); rawRequest = raw.clone(); }
    }
    private final LinkedHashMap<Long, Pending> queue = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean enabled;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) {
        synchronized (this) {
            enabled = value;
            if (!value) {
                for (Pending p : queue.values()) { p.forwarded = p.rawRequest; p.decision.countDown(); }
                queue.clear();
            }
        }
        changed();
    }
    public byte[] intercept(Exchange exchange, byte[] rawRequest) throws IOException {
        Pending pending;
        synchronized (this) {
            if (!enabled) return rawRequest;
            if (queue.size() >= MAX_PENDING) throw new IOException("Intercept queue full (16 requests)");
            pending = new Pending(exchange, rawRequest); queue.put(pending.id, pending);
        }
        changed();
        try {
            if (!pending.decision.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                throw new IOException("Intercept decision timed out after 120 seconds");
            return pending.forwarded;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interception cancelled", e); }
        finally { synchronized (this) { queue.remove(pending.id); } changed(); }
    }
    public synchronized List<Pending> pending() { return new ArrayList<>(queue.values()); }
    public void forward(long id, byte[] raw) {
        if (raw == null || raw.length > MAX_EDIT_BYTES) throw new IllegalArgumentException("Request too large");
        decide(id, raw.clone());
    }
    public void drop(long id) { decide(id, null); }
    public void cancelAll() {
        synchronized (this) { for (Pending p : queue.values()) p.decision.countDown(); queue.clear(); }
        changed();
    }
    private void decide(long id, byte[] raw) {
        synchronized (this) { Pending p = queue.remove(id); if (p != null) { p.forwarded = raw; p.decision.countDown(); } }
        changed();
    }
    public void addListener(Runnable listener) { listeners.addIfAbsent(listener); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }
    private void changed() { for (Runnable r : listeners) { try { r.run(); } catch (RuntimeException ignored) { } } }
}
