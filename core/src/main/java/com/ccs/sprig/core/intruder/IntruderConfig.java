package com.ccs.sprig.core.intruder;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import com.google.re2j.Pattern;

/** Immutable, explicit attack settings. Creating or validating a config sends nothing. */
public final class IntruderConfig {
    public final String url,protocol,grepExtract;
    public final byte[] markedTemplate;
    public final AttackType type;
    public final List<PayloadSource> payloadSets;
    public final List<PayloadProcessor> processors;
    public final Map<Integer,List<PayloadProcessor>> setProcessors;
    public final List<String> grepMatches;
    public final long requestBudget,intervalMillis,timeoutMillis,memoryBytes;
    public final int concurrency,retries,resultLimit,evidenceBytes;
    public final boolean preserveHost;
    final Pattern extractPattern;
    private IntruderConfig(Builder b) {
        url=b.url;markedTemplate=b.template.clone();type=b.type;protocol=b.protocol;
        payloadSets=Collections.unmodifiableList(new ArrayList<>(b.sets));processors=Collections.unmodifiableList(new ArrayList<>(b.processors));
        Map<Integer,List<PayloadProcessor>> perSet=new HashMap<>();for(Map.Entry<Integer,List<PayloadProcessor>> entry:b.perSet.entrySet())perSet.put(entry.getKey(),Collections.unmodifiableList(new ArrayList<>(entry.getValue())));setProcessors=Collections.unmodifiableMap(perSet);
        requestBudget=b.budget;concurrency=b.concurrency;intervalMillis=b.interval;timeoutMillis=b.timeout;retries=b.retries;
        resultLimit=b.resultLimit;memoryBytes=b.memory;evidenceBytes=b.evidence;grepExtract=b.extract==null?"":b.extract;
        preserveHost=b.preserveHost;
        grepMatches=Collections.unmodifiableList(new ArrayList<>(b.matches));
        URI target;
        try{target=URI.create(url);}catch(RuntimeException e){throw new IllegalArgumentException("Invalid target URL",e);}
        if(!(target.getScheme()!=null&&(target.getScheme().equals("http")||target.getScheme().equals("https")))||target.getHost()==null||target.getUserInfo()!=null||target.getPort()==0||target.getPort()>65535)throw new IllegalArgumentException("Use an HTTP(S) target without URL credentials");
        if(markedTemplate.length==0||markedTemplate.length>1024*1024)throw new IllegalArgumentException("Marked template must be 1 byte–1 MiB");
        if(protocol==null||!(protocol.equals("AUTO")||protocol.equals("HTTP/1.1")||protocol.equals("HTTP/2")||protocol.equals("HTTP/3")))throw new IllegalArgumentException("Unknown wire protocol");
        if(protocol.equals("HTTP/3")&&!target.getScheme().equals("https"))throw new IllegalArgumentException("HTTP/3 requires HTTPS");
        if(type==null||payloadSets.isEmpty()||payloadSets.size()>32||payloadSets.contains(null)||processors.contains(null)||processors.size()>16)throw new IllegalArgumentException("Invalid payload sets or processors");
        long payloadMemory=0;for(PayloadSource source:payloadSets){long estimate=source.estimatedBytes();if(estimate<0||estimate>PayloadSource.ListSource.MAX_MEMORY-payloadMemory)throw new IllegalArgumentException("Combined payload sets exceed 8 MiB including array overhead");payloadMemory+=estimate;}
        for(Map.Entry<Integer,List<PayloadProcessor>> entry:setProcessors.entrySet())if(entry.getKey()<0||entry.getKey()>=payloadSets.size()||entry.getValue().size()>16||entry.getValue().contains(null))throw new IllegalArgumentException("Invalid per-set processor pipeline");
        if(requestBudget<1||requestBudget>100000||concurrency<1||concurrency>4||intervalMillis<0||intervalMillis>60000||timeoutMillis<1000||timeoutMillis>120000||retries<0||retries>2)throw new IllegalArgumentException("Use budget 1–100000, workers 1–4, interval 0–60000 ms, timeout 1–120 s, retries 0–2");
        if(resultLimit<1||resultLimit>10000||memoryBytes<64*1024||memoryBytes>32L*1024*1024||evidenceBytes<0||evidenceBytes>32*1024)throw new IllegalArgumentException("Invalid result memory bounds");
        if(grepMatches.size()>16||grepMatches.stream().anyMatch(s->s==null||s.length()>128)||grepExtract.length()>512)throw new IllegalArgumentException("Use at most 16 grep literals (128 chars each) and a 512-character extract pattern");
        try{extractPattern=grepExtract.isEmpty()?null:Pattern.compile(grepExtract);}catch(RuntimeException e){throw new IllegalArgumentException("Invalid RE2 extract expression: "+e.getMessage(),e);}
        new IntruderPlan(this);
    }
    public static final class Builder {
        private final String url;private final byte[] template;
        private AttackType type=AttackType.SNIPER;private String protocol="AUTO",extract="";
        private List<PayloadSource> sets=new ArrayList<>();private List<PayloadProcessor> processors=new ArrayList<>();private List<String> matches=new ArrayList<>();
        private final Map<Integer,List<PayloadProcessor>> perSet=new HashMap<>();
        private long budget=1000,interval=200,timeout=30000,memory=16L*1024*1024;private int concurrency=2,retries,resultLimit=10000,evidence=4096;
        private boolean preserveHost;
        public Builder(String url,byte[] markedTemplate){if(url==null||markedTemplate==null)throw new IllegalArgumentException("Target and template are required");this.url=url;template=markedTemplate.clone();}
        public Builder type(AttackType value){type=value;return this;}public Builder protocol(String value){protocol=value;return this;}
        public Builder payloadSets(List<PayloadSource> value){sets=new ArrayList<>(value);return this;}public Builder processors(List<PayloadProcessor> value){processors=new ArrayList<>(value);return this;}
        public Builder processorsForSet(int index,List<PayloadProcessor> value){perSet.put(index,new ArrayList<>(value));return this;}
        public Builder requestBudget(long value){budget=value;return this;}public Builder concurrency(int value){concurrency=value;return this;}
        public Builder intervalMillis(long value){interval=value;return this;}public Builder timeoutMillis(long value){timeout=value;return this;}public Builder retries(int value){retries=value;return this;}
        public Builder grepMatches(List<String> value){matches=new ArrayList<>(value);return this;}public Builder grepExtract(String value){extract=value;return this;}
        public Builder resultLimit(int value){resultLimit=value;return this;}public Builder memoryBytes(long value){memory=value;return this;}public Builder evidenceBytes(int value){evidence=value;return this;}
        public Builder preserveHost(boolean value){preserveHost=value;return this;}
        public IntruderConfig build(){return new IntruderConfig(this);}
    }
}
