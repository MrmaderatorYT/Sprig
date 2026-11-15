package com.ccs.sprig.core.intruder;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/** Explicit exports contain only the retained result snapshot, with eviction/truncation metadata. */
public final class IntruderExporter {
    private IntruderExporter(){}
    public static void writeJson(IntruderAttack attack,OutputStream output)throws IOException {
        Writer w=new OutputStreamWriter(output,StandardCharsets.UTF_8);IntruderAttack.Stats stats=attack.stats();List<IntruderResult> rows=attack.results();Collections.reverse(rows);
        w.write("{\"tool\":\"Sprig Intruder\",\"attackId\":"+attack.id+",\"target\":");string(w,attack.config.url);
        w.write(",\"type\":");string(w,attack.config.type.name());w.write(",\"protocol\":");string(w,attack.config.protocol);w.write(",\"state\":");string(w,attack.state().name());
        w.write(",\"combinations\":"+attack.plan.combinationCount()+",\"plannedCases\":"+stats.total+",\"requestsSent\":"+stats.dispatched+",\"completedCases\":"+stats.completed+",\"evictedResults\":"+stats.evicted+",\"results\":[");boolean first=true;
        for(IntruderResult row:rows) {
            if(!first)w.write(',');first=false;w.write("{\"number\":"+row.number+",\"status\":"+row.status+",\"durationMillis\":"+row.durationMillis+",\"responseLength\":"+row.responseLength+",\"attempts\":"+row.attempts+",\"matched\":"+row.matched+",\"payloads\":[");
            boolean payloadFirst=true;for(String payload:row.payloads){if(!payloadFirst)w.write(',');payloadFirst=false;string(w,payload);}w.write("],\"extract\":");string(w,row.extract);w.write(",\"error\":");string(w,row.error);
            w.write(",\"evidenceTruncated\":"+row.evidenceTruncated+",\"grepTruncated\":"+row.grepTruncated+",\"responseLengthExact\":"+row.responseLengthExact+",\"requestBase64\":");string(w,Base64.getEncoder().encodeToString(row.requestEvidence));
            w.write(",\"responseBase64\":");string(w,Base64.getEncoder().encodeToString(row.responseEvidence));w.write('}');
        }
        w.write("]}");w.flush();
    }
    public static void writeCsv(IntruderAttack attack,OutputStream output)throws IOException {
        Writer w=new OutputStreamWriter(output,StandardCharsets.UTF_8);w.write("number,status,duration_ms,response_length,attempts,matched,payloads,extract,error,evidence_truncated,grep_truncated\r\n");
        List<IntruderResult> rows=attack.results();Collections.reverse(rows);
        for(IntruderResult row:rows) {
            w.write(row.number+","+row.status+","+row.durationMillis+","+row.responseLength+","+row.attempts+","+row.matched+",");csv(w,String.join(" | ",row.payloads));w.write(',');csv(w,row.extract);w.write(',');csv(w,row.error);w.write(","+row.evidenceTruncated+","+row.grepTruncated+"\r\n");
        }w.flush();
    }
    private static void csv(Writer w,String value)throws IOException {w.write('"');int first=0;while(first<value.length()&&value.charAt(first)<=32)first++;if(first<value.length()&&"=+-@".indexOf(value.charAt(first))>=0)w.write('\'');for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c=='"')w.write('"');w.write(c);}w.write('"');}
    private static void string(Writer w,String value)throws IOException {
        w.write('"');for(int i=0;i<value.length();i++){char c=value.charAt(i);switch(c){case'"':w.write("\\\"");break;case'\\':w.write("\\\\");break;case'\n':w.write("\\n");break;case'\r':w.write("\\r");break;case'\t':w.write("\\t");break;default:if(c<32||Character.isSurrogate(c))w.write(String.format(java.util.Locale.ROOT,"\\u%04x",(int)c));else w.write(c);}}w.write('"');
    }
}
