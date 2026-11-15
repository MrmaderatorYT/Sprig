package com.ccs.sprig.core;

import com.ccs.sprig.core.model.Exchange;
import com.ccs.sprig.core.proxy.ProxyServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Sequential, cancellable payload replay with one explicit {{payload}} replacement marker. */
public final class PayloadRunner {
    public interface Listener { void result(int index, String payload, Exchange exchange, String error); }
    private final AtomicBoolean cancelled = new AtomicBoolean();
    public void cancel() { cancelled.set(true); }
    public boolean isCancelled() { return cancelled.get(); }
    public void run(ProxyServer proxy, String url, String template, List<String> payloads, long delayMillis, Listener listener) throws IOException {
        if (!template.contains("{{payload}}")) throw new IOException("Add {{payload}} to the raw request template");
        if (payloads.isEmpty() || payloads.size()>500) throw new IOException("Use between 1 and 500 payloads");
        if (delayMillis<100 || delayMillis>60000) throw new IOException("Delay must be between 100 and 60000 ms");
        if(template.length()>1024*1024) throw new IOException("Template exceeds 1 MiB");
        int markers=0;
        for(int p=0;(p=template.indexOf("{{payload}}",p))>=0;p+=11) markers++;
        for(String payload:payloads) {
            if(payload.length()>16384) throw new IOException("Payload exceeds 16 KiB");
            long expanded=template.length()+(long)markers*(payload.length()-11);
            if(expanded>1024*1024) throw new IOException("Expanded request exceeds 1 MiB");
        }
        for(int i=0;i<payloads.size()&&!cancelled.get();i++) {
            String payload=payloads.get(i);
            try {
                Exchange e=proxy.replay(url,template.replace("{{payload}}",payload).getBytes(StandardCharsets.ISO_8859_1));
                listener.result(i,payload,e,"");
            } catch(IOException error) {listener.result(i,payload,null,error.getMessage());}
            if(i<payloads.size()-1&&!cancelled.get()) try { Thread.sleep(delayMillis); }
            catch(InterruptedException error) {Thread.currentThread().interrupt();cancel();throw new IOException("Payload replay interrupted",error);}
        }
    }
}
