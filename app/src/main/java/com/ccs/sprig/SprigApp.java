package com.ccs.sprig;

import android.app.Application;
import android.content.SharedPreferences;
import com.ccs.sprig.core.HarExporter;
import com.ccs.sprig.core.InterceptController;
import com.ccs.sprig.core.model.SessionStore;
import com.ccs.sprig.core.proxy.ProxyServer;
import com.ccs.sprig.core.http3.Http3Client;
import com.ccs.sprig.vpn.CaptureVpnService;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class SprigApp extends Application {
    private static final long MIB = 1024L * 1024L;
    private static final long MAX_HEAP = Runtime.getRuntime().maxMemory();
    private static SprigApp instance;
    // Conservative retained-storage cap; device-derived tuning remains pending benchmarks.
    private final com.ccs.sprig.core.memory.MemoryBudget memoryBudget=new com.ccs.sprig.core.memory.MemoryBudget(Math.max(8L*MIB,Math.min(48L*MIB,MAX_HEAP/5)));
    private final SessionStore store = new SessionStore(1000, Math.max(4L * MIB, Math.min(24L * MIB, MAX_HEAP / 10L)),memoryBudget);
    private final InterceptController interceptor = new InterceptController();
    private volatile ProxyServer proxy;
    private SharedPreferences preferences;
    public static SprigApp get() { return instance; }
    @Override public void onCreate() {
        super.onCreate(); instance = this;
        preferences = getSharedPreferences("sprig", MODE_PRIVATE);
    }
    public com.ccs.sprig.core.memory.MemoryBudget getMemoryBudget() { return memoryBudget; }
    public SessionStore getStore() { return store; }
    public InterceptController getInterceptor() { return interceptor; }
    public SharedPreferences getPreferences() { return preferences; }
    /** Lazy construction; CA key generation occurs in proxy worker start, never Application startup. */
    public synchronized ProxyServer getProxy() {
        if (proxy == null) {
            try {
                ProxyServer configured = new ProxyServer(store, interceptor, new File(getNoBackupFilesDir(), "authority"), CaptureVpnService::protectSocket);
                configured.setConnectionLimit(MAX_HEAP <= 128L * MIB ? 2 : MAX_HEAP <= 256L * MIB ? 4 : 8);
                configured.setHttp3Client(new Http3Client(CaptureVpnService::protectSocket));
                proxy = configured;
            } catch (IOException error) {
                throw new IllegalStateException("Cannot open the local certificate authority", error);
            }
            applySettings();
        }
        return proxy;
    }
    public void applySettings() {
        ProxyServer current = proxy;
        if (current == null) return;
        current.setMitmEnabled(preferences.getBoolean("mitm", true));
        Set<String> scope = new HashSet<>();
        for (String host : preferences.getString("scope", "").split("[\\s,]+")) {
            host = host.trim().toLowerCase(Locale.ROOT);
            if (!host.isEmpty()) scope.add(host);
        }
        current.setScope(scope);
    }
    public void exportHar(OutputStream output) throws IOException { HarExporter.write(store.snapshot(), output); }
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Captured secrets live only in the bounded session; retain user work while capture is active.
        if (level >= TRIM_MEMORY_COMPLETE && !CaptureVpnService.isRunning()) store.clear();
    }
}
