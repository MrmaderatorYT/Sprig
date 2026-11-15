package com.ccs.sprig.core.intruder;

import com.ccs.sprig.core.model.Exchange;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Immutable bounded evidence for one generated case, including its final retry outcome. */
public final class IntruderResult {
    public final long number,durationMillis,responseLength;
    public final int status,attempts;
    public final boolean matched,evidenceTruncated,requestTruncated,responseTruncated,grepTruncated,responseLengthExact;
    public final String error,extract,requestHeaders,responseHeaders;
    public final List<String> payloads;
    public final byte[] requestEvidence,responseEvidence;
    IntruderResult(IntruderConfig config,IntruderPlan.Case item,Exchange exchange,String failure,int attempts,long duration) {
        number=item.index+1;this.attempts=attempts;durationMillis=duration;status=exchange==null?0:exchange.status;
        error=clip(failure==null?"":failure,512);List<String> values=new ArrayList<>();int remaining=512;
        for(byte[] value:item.payloads){String text=new String(value,StandardCharsets.UTF_8);String clipped=clip(text,Math.min(128,remaining));values.add(clipped);remaining=Math.max(0,remaining-clipped.length());}payloads=Collections.unmodifiableList(values);
        requestHeaders=clip(exchange==null?"":exchange.requestHeaders,2048);responseHeaders=clip(exchange==null?"":exchange.responseHeaders,2048);
        byte[] reqBody=exchange==null?new byte[0]:nonNull(exchange.requestBody),resBody=exchange==null?new byte[0]:nonNull(exchange.responseBody);
        requestEvidence=exchange==null?Arrays.copyOf(item.rawRequest,Math.min(item.rawRequest.length,config.evidenceBytes)):evidence(exchange.requestHeaders,reqBody,config.evidenceBytes);
        responseEvidence=exchange==null?new byte[0]:evidence(exchange.responseHeaders,resBody,config.evidenceBytes);
        long capturedLength=resBody.length,declared=contentLength(exchange==null?"":exchange.responseHeaders);
        boolean noBody=item.rawRequest.length>=5&&new String(item.rawRequest,0,5,StandardCharsets.US_ASCII).equals("HEAD ")||status==204||status==304;
        responseLength=noBody?0:declared>=0?declared:capturedLength;responseLengthExact=noBody||declared>=0||exchange!=null&&!exchange.truncated;
        long reqLength=exchange==null?item.rawRequest.length:bytes(exchange.requestHeaders)+reqBody.length;
        long resLength=exchange==null?0:bytes(exchange.responseHeaders)+Math.max(capturedLength,responseLength);
        requestTruncated=reqLength>requestEvidence.length||exchange!=null&&contentLength(exchange.requestHeaders)>reqBody.length;
        responseTruncated=resLength>responseEvidence.length||exchange!=null&&exchange.truncated;
        evidenceTruncated=requestTruncated||responseTruncated;
        int max=64*1024;byte[] head=(exchange==null?"":safe(exchange.responseHeaders)).getBytes(StandardCharsets.ISO_8859_1);
        byte[] inspected=evidence(new String(head,StandardCharsets.ISO_8859_1),resBody,max);
        String response=new String(inspected,StandardCharsets.ISO_8859_1);grepTruncated=head.length+resBody.length>max||exchange!=null&&exchange.truncated;
        boolean any=false;for(String literal:config.grepMatches)if(response.contains(literal)){any=true;break;}matched=any;
        String extracted="";
        if(config.extractPattern!=null){com.google.re2j.Matcher matcher=config.extractPattern.matcher(response);if(matcher.find())extracted=matcher.groupCount()>0?matcher.group(1):matcher.group();}
        extract=clip(extracted,1024);
    }
    long memoryBytes(){long size=512+requestEvidence.length+responseEvidence.length+2L*(error.length()+extract.length()+requestHeaders.length()+responseHeaders.length());for(String payload:payloads)size+=2L*payload.length()+48;return size;}
    private static byte[] nonNull(byte[] value){return value==null?new byte[0]:value;}
    private static String safe(String value){return value==null?"":value;}
    private static int bytes(String value){return safe(value).getBytes(StandardCharsets.ISO_8859_1).length;}
    private static String clip(String value,int max){value=safe(value);return value.length()<=max?value:value.substring(0,Math.max(0,max));}
    static byte[] evidence(String headers,byte[] body,int limit){byte[] head=safe(headers).getBytes(StandardCharsets.ISO_8859_1);int length=Math.min(limit,head.length+body.length);byte[] result=new byte[length];int prefix=Math.min(length,head.length);System.arraycopy(head,0,result,0,prefix);if(prefix<length)System.arraycopy(body,0,result,prefix,length-prefix);return result;}
    private static long contentLength(String headers){for(String line:safe(headers).split("\r?\n")){int colon=line.indexOf(':');if(colon>0&&line.substring(0,colon).equalsIgnoreCase("Content-Length")){try{long n=Long.parseLong(line.substring(colon+1).trim());return n<0?-1:n;}catch(NumberFormatException ignored){return -1;}}}return -1;}
}
