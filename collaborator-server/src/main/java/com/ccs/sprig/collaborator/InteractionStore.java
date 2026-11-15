package com.ccs.sprig.collaborator;

import com.ccs.sprig.oast.Interaction;
import com.ccs.sprig.oast.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, expiring, process-local interaction sessions. Secrets never appear in callback hosts. */
public final class InteractionStore {
    private static final long TTL=24L*60*60*1000;
    private static final int MAX_SESSIONS=128,MAX_EVENTS=256,MAX_DETAILS=8192;
    private static final long MAX_EVENT_BYTES=2L*1024*1024;
    private static final long MAX_TOTAL_BYTES=16L*1024*1024;
    private final SecureRandom random=new SecureRandom();
    private final Map<String,Session> sessions=new LinkedHashMap<>();
    private final String domain;
    private long totalBytes;
    public static final class Session {
        public final String id,secret,payloadHost;
        public final long expiresAt;
        final ArrayDeque<Interaction> events=new ArrayDeque<>();
        long sequence,bytes;
        Session(String id,String secret,String domain,long expiry) {this.id=id;this.secret=secret;payloadHost=id+"."+domain;expiresAt=expiry;}
        Map<String,Object> json(List<String> protocols) {return Json.map("id",id,"secret",secret,"payloadHost",payloadHost,"expiresAt",expiresAt,"protocols",protocols);}
    }
    public InteractionStore(String domain) {
        if(domain==null||!domain.matches("(?i)[a-z0-9](?:[a-z0-9.-]{0,200}[a-z0-9])?")||domain.contains("..")) throw new IllegalArgumentException("Configure a valid ASCII DNS domain");
        this.domain=domain.toLowerCase(java.util.Locale.ROOT);
    }
    public String domain() {return domain;}
    public synchronized Session create() {
        expire();if(sessions.size()>=MAX_SESSIONS) throw new IllegalStateException("Session limit reached; revoke an unused session");
        byte[] token=new byte[20];random.nextBytes(token);StringBuilder id=new StringBuilder();
        // Base32 without padding, 160 bits of unguessable correlation data.
        int acc=0,bits=0;String alphabet="abcdefghijklmnopqrstuvwxyz234567";
        for(byte b:token) {acc=(acc<<8)|(b&255);bits+=8;while(bits>=5) {bits-=5;id.append(alphabet.charAt((acc>>>bits)&31));}}
        byte[] secret=new byte[32];random.nextBytes(secret);
        Session session=new Session(id.toString(),Base64.getUrlEncoder().withoutPadding().encodeToString(secret),domain,System.currentTimeMillis()+TTL);
        sessions.put(session.id,session);return session;
    }
    public synchronized Session authenticate(String id,String secret) {expire();Session s=sessions.get(id);return s!=null&&constantTime(s.secret,secret)?s:null;}
    public synchronized boolean revoke(String id,String secret) {Session s=authenticate(id,secret);if(s==null)return false;sessions.remove(id);totalBytes-=s.bytes;return true;}
    public synchronized Session forHost(String host) {
        expire();host=host.toLowerCase(java.util.Locale.ROOT);if(host.endsWith("."))host=host.substring(0,host.length()-1);
        if(!host.endsWith("."+domain))return null;
        String prefix=host.substring(0,host.length()-domain.length()-1);String[] labels=prefix.split("\\.");
        return sessions.get(labels[labels.length-1]);
    }
    public synchronized void record(Session s,String protocol,String remote,String summary,String details) {
        if(sessions.get(s.id)!=s||s.expiresAt<System.currentTimeMillis())return;
        summary=limit(summary,1024);details=limit(details,MAX_DETAILS);
        Interaction e=new Interaction(++s.sequence,System.currentTimeMillis(),protocol,remote,summary,details);
        s.events.addLast(e);s.bytes+=cost(e);totalBytes+=cost(e);
        while(s.events.size()>MAX_EVENTS||s.bytes>MAX_EVENT_BYTES) removeFirst(s);
        while(totalBytes>MAX_TOTAL_BYTES){Session oldest=null;for(Session candidate:sessions.values())if(!candidate.events.isEmpty()&&(oldest==null||candidate.events.getFirst().timeMillis<oldest.events.getFirst().timeMillis))oldest=candidate;if(oldest==null)break;removeFirst(oldest);}
    }
    public synchronized Map<String,Object> poll(Session s,long after) {
        List<Object> events=new ArrayList<>();long cursor=after;int bytes=0;
        boolean lost=!s.events.isEmpty()&&after<s.events.getFirst().sequence-1;
        for(Interaction e:s.events) if(e.sequence>after) {
            int cost=Json.encode(e.json()).getBytes(StandardCharsets.UTF_8).length;if(!events.isEmpty()&&bytes+cost>256*1024)break;
            events.add(e.json());bytes+=cost;cursor=e.sequence;
        }
        return Json.map("events",events,"cursor",cursor,"truncated",lost,"expiresAt",s.expiresAt);
    }
    private void removeFirst(Session s){long bytes=cost(s.events.removeFirst());s.bytes-=bytes;totalBytes-=bytes;}
    private void expire() {long now=System.currentTimeMillis();Iterator<Session> it=sessions.values().iterator();while(it.hasNext()){Session s=it.next();if(s.expiresAt<now){totalBytes-=s.bytes;it.remove();}}}
    private static long cost(Interaction e) {return 2L*(e.details.length()+e.summary.length()+e.remoteAddress.length()+e.protocol.length())+128;}
    private static String limit(String s,int n) {if(s==null)return "";return s.length()>n?s.substring(0,n)+"\n[truncated]":s;}
    static boolean constantTime(String expected,String actual) {return actual!=null&&MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),actual.getBytes(StandardCharsets.UTF_8));}
}
