package com.ccs.sprig.core.scanner;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable process-local report. Export is explicit and may contain sensitive target URLs. */
public final class ScanReport {
    public final String seedUrl, state;
    public final long startedMillis, finishedMillis;
    public final int requests, pages;
    public final List<Finding> findings;
    public final List<String> errors, visitedUrls;
    public final Map<String,Integer> checkRequests;
    public final List<String> enabledChecks;
    ScanReport(String seedUrl,String state,long started,long finished,int requests,int pages,
            List<Finding> findings,List<String> errors,List<String> visited) {
        this(seedUrl,state,started,finished,requests,pages,findings,errors,visited,Collections.emptyMap(),Collections.emptyList());
    }
    ScanReport(String seedUrl,String state,long started,long finished,int requests,int pages,List<Finding> findings,List<String> errors,List<String> visited,Map<String,Integer> checkRequests,List<String> checks){
        this.seedUrl=seedUrl;this.state=state;this.startedMillis=started;this.finishedMillis=finished;
        this.requests=requests;this.pages=pages;
        this.findings=Collections.unmodifiableList(new ArrayList<>(findings));
        this.errors=Collections.unmodifiableList(new ArrayList<>(errors));
        this.visitedUrls=Collections.unmodifiableList(new ArrayList<>(visited));
        this.checkRequests=Collections.unmodifiableMap(new LinkedHashMap<>(checkRequests));this.enabledChecks=Collections.unmodifiableList(new ArrayList<>(checks));
    }
    public void writeJson(OutputStream output) throws IOException {
        Writer w=new OutputStreamWriter(output,StandardCharsets.UTF_8);
        w.write("{\"format\":\"sprig-scan-1\",\"seedUrl\":");quote(w,seedUrl);
        w.write(",\"state\":");quote(w,state);w.write(",\"startedMillis\":"+startedMillis+",\"finishedMillis\":"+finishedMillis+
                ",\"requests\":"+requests+",\"pages\":"+pages+",\"findings\":[");
        boolean first=true;
        for(Finding f:findings) {
            if(!first)w.write(',');first=false;
            w.write("{\"id\":"+f.id+",\"checkId\":");quote(w,f.checkId);
            field(w,"severity",f.severity);field(w,"confidence",f.confidence);field(w,"title",f.title);
            field(w,"detail",f.detail);field(w,"url",f.url);field(w,"parameter",f.parameter);field(w,"evidence",f.evidence);
            w.write(",\"baselineExchangeId\":"+f.baselineExchangeId+",\"probeExchangeId\":"+f.probeExchangeId+",\"evidenceExchangeIds\":[");boolean idFirst=true;for(long id:f.evidenceExchangeIds){if(!idFirst)w.write(',');idFirst=false;w.write(Long.toString(id));}w.write("]}");
        }
        w.write("],\"errors\":");strings(w,errors);w.write(",\"visitedUrls\":");strings(w,visitedUrls);w.write(",\"enabledChecks\":");strings(w,enabledChecks);w.write(",\"checkRequests\":{");boolean firstCheck=true;for(Map.Entry<String,Integer> entry:checkRequests.entrySet()){if(!firstCheck)w.write(',');firstCheck=false;quote(w,entry.getKey());w.write(':'+entry.getValue().toString());}w.write("}}");w.flush();
    }
    public void writeCsv(OutputStream output)throws IOException{
        Writer w=new OutputStreamWriter(output,StandardCharsets.UTF_8);w.write("id,check,severity,confidence,title,detail,url,parameter,evidence,exchange_ids\r\n");
        for(Finding f:findings){String[] fields={Long.toString(f.id),f.checkId,f.severity,f.confidence,f.title,f.detail,f.url,f.parameter,f.evidence,f.evidenceExchangeIds.toString()};for(int i=0;i<fields.length;i++){if(i>0)w.write(',');csv(w,fields[i]);}w.write("\r\n");}w.flush();
    }
    public void writeHtml(OutputStream output)throws IOException{
        Writer w=new OutputStreamWriter(output,StandardCharsets.UTF_8);w.write("<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>Sprig scan report</title><style>body{font:16px system-ui;background:#101411;color:#e1e8df;max-width:1000px;margin:40px auto;padding:0 24px}article{background:#1b211d;padding:20px;border-radius:18px;margin:16px 0}h1,h2{color:#b6f3c6}pre{white-space:pre-wrap;overflow-wrap:anywhere;color:#b5ccb8}small{color:#a9b7ad}</style><h1>Sprig scan report</h1><p>");html(w,seedUrl);w.write("</p><small>");html(w,state+" · "+pages+" pages · "+requests+" requests");w.write("</small>");
        for(Finding f:findings){w.write("<article><small>");html(w,f.severity+" · "+f.confidence+" · "+f.checkId);w.write("</small><h2>");html(w,f.title);w.write("</h2><p>");html(w,f.detail);w.write("</p><pre>");html(w,f.url+"\n"+f.parameter+"\n"+f.evidence+"\nExchange IDs: "+f.evidenceExchangeIds);w.write("</pre></article>");}w.write("</html>");w.flush();
    }
    private static void csv(Writer w,String value)throws IOException{if(!value.isEmpty()&&"=+-@\t\r".indexOf(value.charAt(0))>=0)value="'"+value;w.write('"');w.write(value.replace("\"","\"\""));w.write('"');}
    private static void html(Writer w,String value)throws IOException{w.write(value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"));}
    private static void field(Writer w,String name,String value)throws IOException {w.write(",\"");w.write(name);w.write("\":");quote(w,value);}
    private static void strings(Writer w,List<String> values)throws IOException {
        w.write('[');boolean first=true;for(String s:values){if(!first)w.write(',');first=false;quote(w,s);}w.write(']');
    }
    private static void quote(Writer w,String text)throws IOException {
        w.write('"');
        if(text!=null)for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            switch(c) {
                case '"':w.write("\\\"");break;case '\\':w.write("\\\\");break;
                case '\n':w.write("\\n");break;case '\r':w.write("\\r");break;case '\t':w.write("\\t");break;
                default:if(c<32||Character.isSurrogate(c))w.write(String.format(Locale.ROOT,"\\u%04x",(int)c));else w.write(c);
            }
        }
        w.write('"');
    }
}
