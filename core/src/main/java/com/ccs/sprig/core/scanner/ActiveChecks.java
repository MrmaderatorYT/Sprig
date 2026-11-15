package com.ccs.sprig.core.scanner;

import com.ccs.sprig.core.model.Exchange;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Low-impact explicit probes. No time-delay SQL, function calls, file reads or destructive methods. */
final class ActiveChecks {
    interface Sender {Exchange send(String check,ScanRequest request)throws IOException;boolean cancelled();}
    private static final Pattern SQL_ERROR=Pattern.compile("(?i)(you have an error in your sql syntax|org\\.postgresql\\.util\\.psqlexception|unterminated quoted string at or near|unclosed quotation mark after the character string|microsoft ole db provider for sql server|microsoft sql native client|sqlite(?:3)?(?:exception|error):|ora-0(?:1756|1704|0933)|sqlstate\\[[0-9a-z]{5}\\])");
    private static final Pattern REDIRECT=Pattern.compile("(?i)(?:url|uri|redirect(?:_uri|_url|to)?|return(?:url|_url|to)?|next|continue|dest(?:ination)?|target|callback)");
    private ActiveChecks(){ }
    static void run(ScanRequest request,Exchange baseline,String body,List<ScanRequest.Point> points,Set<String> enabled,String callback,Sender sender,List<Finding> out)throws IOException{
        if(enabled.contains("cors"))cors(request,baseline,sender,out);
        if(enabled.contains("host-header")&&request.method.equals("GET"))host(request,baseline,sender,out);
        if(enabled.contains("methods")&&request.method.equals("GET"))methods(request,baseline,sender,out);
        for(ScanRequest.Point point:points){
            if(sender.cancelled())break;
            if(enabled.contains("reflection")&&ScanSupport.html(baseline))reflection(point,baseline,sender,out);
            if(enabled.contains("redirect")&&REDIRECT.matcher(point.name).matches())redirect(point,baseline,sender,out);
            if(enabled.contains("sql-error"))sqlError(point,baseline,body,sender,out);
            if(enabled.contains("template"))template(point,baseline,sender,out);
            if(enabled.contains("crlf"))crlf(point,baseline,sender,out);
            if(enabled.contains("sql-boolean")&&point.value.matches("[0-9]{1,10}"))sqlBoolean(point,baseline,sender,out);
            if(enabled.contains("oast")&&!callback.isEmpty())callback(point,baseline,callback,sender,out);
        }
    }
    private static void reflection(ScanRequest.Point point,Exchange baseline,Sender sender,List<Finding> out)throws IOException{
        String token=ScanSupport.token(),marker="sprig_"+token,tag="<sprig-probe data-sprig=\""+token+"\">";
        Exchange probe=sender.send("reflection",point.mutate(marker+tag));if(!success(probe)||!ScanSupport.html(probe))return;
        String body=ScanSupport.text(probe);int position=body.indexOf(marker);if(position<0)return;
        boolean raw=body.contains(marker+tag);String context=new HtmlModel(body).context(position,marker);
        out.add(ScanSupport.finding(raw?"html-reflection":"input-reflection",raw?"Medium":"Info",raw?"Tentative":"Observation",
                raw?"Unencoded input reflected into HTML":"Input reflected in an HTML response",
                (raw?"A harmless marker/custom tag was returned without encoding. ":"A unique marker was reflected. ")+"Context: "+context+". Inspect encoding and parser context before classifying as injection or XSS; script execution was not attempted or demonstrated.",
                point.base,point.label(),"Context: "+context+"; marker context: "+ScanSupport.snippet(body,position),baseline,probe));
    }
    private static void redirect(ScanRequest.Point point,Exchange baseline,Sender sender,List<Finding> out)throws IOException{
        String host="sprig-"+ScanSupport.token()+".example.invalid";Exchange probe=sender.send("redirect",point.mutate("https://"+host+"/"));
        if(probe==null||probe.status<300||probe.status>=400)return;String location=ScanSupport.header(probe,"location");
        try{URI resolved=point.base.uri.resolve(location);if(host.equalsIgnoreCase(resolved.getHost())&&("http".equalsIgnoreCase(resolved.getScheme())||"https".equalsIgnoreCase(resolved.getScheme())))out.add(ScanSupport.finding("external-redirect","Medium","Certain","User-controlled external redirect","A unique external destination supplied in this insertion point appeared in Location. The external redirect was not followed. Review whether this behavior is intended.",point.base,point.label(),"Status: "+probe.status+"; Location: "+ScanSupport.trim(location,500),baseline,probe));}catch(IllegalArgumentException ignored){}
    }
    private static void sqlError(ScanRequest.Point point,Exchange baseline,String baselineBody,Sender sender,List<Finding> out)throws IOException{
        if(SQL_ERROR.matcher(baselineBody).find())return;Exchange probe=sender.send("sql-error",point.mutate(point.value+"'"));if(probe==null)return;
        String text=ScanSupport.text(probe);Matcher match=SQL_ERROR.matcher(text);if(!match.find())return;
        String evidence=ScanSupport.snippet(text,match.start());Exchange control=sender.send("sql-error",point.mutate(point.value+"sprig_"+ScanSupport.token()));if(control==null||control.status!=baseline.status||SQL_ERROR.matcher(ScanSupport.text(control)).find())return;
        out.add(ScanSupport.finding("sql-error-differential","Medium","Tentative","Database error after quote input","A database-specific error appeared after an apostrophe, but not in baseline or benign control. This is a SQL-injection lead, not proof of exploitability. No extraction, time delay or data-changing SQL was sent.",point.base,point.label(),"Database error context: "+evidence,baseline,probe,control));
    }
    private static void cors(ScanRequest request,Exchange baseline,Sender sender,List<Finding> out)throws IOException{
        String origin="https://sprig-"+ScanSupport.token()+".example.invalid";Exchange one=sender.send("cors",request.header("Origin",origin));if(!accepts(one,origin))return;
        String other="https://sprig-"+ScanSupport.token()+".example.invalid";Exchange two=sender.send("cors",request.header("Origin",other));if(!accepts(two,other))return;
        out.add(ScanSupport.finding("cors-reflected-credentials","Medium","Certain","Arbitrary CORS origin accepted with credentials","Two distinct untrusted origins were reflected with Access-Control-Allow-Credentials: true. Whether sensitive data is exposed depends on endpoint, cookie and browser rules.",request,"header:Origin","Origin: "+origin+"; reflected ACAO and ACAC:true on two probes",baseline,one,two));
    }
    private static boolean accepts(Exchange exchange,String origin){return exchange!=null&&exchange.status>=200&&exchange.status<400&&origin.equals(ScanSupport.header(exchange,"access-control-allow-origin"))&&"true".equalsIgnoreCase(ScanSupport.header(exchange,"access-control-allow-credentials"));}
    private static void template(ScanRequest.Point point,Exchange baseline,Sender sender,List<Finding> out)throws IOException{
        for(String[] syntax:new String[][]{{"{{","}}"},{"${","}"}}){
            String marker="sprig_"+ScanSupport.token()+"_";int a=ScanSupport.integer(17,70),b=ScanSupport.integer(13,70),c=a+3;
            String first=marker+syntax[0]+a+'*'+b+syntax[1]+marker;
            Exchange probe=sender.send("template",point.mutate(first));if(!success(probe))continue;
            String wanted=marker+(a*b)+marker,text=ScanSupport.text(probe);if(!text.contains(wanted)||text.contains(first))continue;
            Exchange control=sender.send("template",point.mutate(marker+syntax[0]+a+'x'+b+syntax[1]+marker));
            Exchange second=sender.send("template",point.mutate(marker+syntax[0]+c+'*'+b+syntax[1]+marker));
            if(control==null||!success(second)||ScanSupport.text(control).contains(wanted)||!ScanSupport.text(second).contains(marker+(c*b)+marker))continue;
            out.add(ScanSupport.finding("template-arithmetic-differential","Medium","Tentative","Template arithmetic evaluation observed","Two unique harmless multiplication expressions produced their expected results, while a literal control did not. Review whether a template engine evaluated user input; arbitrary code execution was not attempted or established.",point.base,point.label(),"Syntax "+syntax[0]+"…"+syntax[1]+"; results "+(a*b)+" and "+(c*b)+"; literal control negative. Marker "+marker,baseline,probe,control,second));return;
        }
    }
    private static void crlf(ScanRequest.Point point,Exchange baseline,Sender sender,List<Finding> out)throws IOException{
        if(!ScanSupport.header(baseline,"X-Sprig-Probe").isEmpty())return;String token=ScanSupport.token();
        String injection=point.type==ScanConfig.InsertionType.HEADER?point.value+"%0d%0aX-Sprig-Probe:%20"+token:point.value+"\r\nX-Sprig-Probe: "+token;
        Exchange probe=sender.send("crlf",point.mutate(injection));if(probe==null||!token.equals(ScanSupport.header(probe,"X-Sprig-Probe")))return;
        Exchange control=sender.send("crlf",point.mutate(point.value+"sprig_control_"+token));if(control==null||!ScanSupport.header(control,"X-Sprig-Probe").isEmpty())return;
        out.add(ScanSupport.finding("response-header-injection","Medium","Certain","User input created a response header","An encoded line-break probe introduced a unique response-header field, absent from baseline/control. No cookie overwrite, redirect poisoning or response splitting beyond the marker was attempted.",point.base,point.label(),"X-Sprig-Probe: "+token+"; baseline/control absent",baseline,probe,control));
    }
    private static void host(ScanRequest request,Exchange baseline,Sender sender,List<Finding> out)throws IOException{
        String token=ScanSupport.token(),oneHost="sprig-"+token+".example.invalid",twoHost="sprig-"+ScanSupport.token()+".example.invalid";
        ScanRequest unique=request.cacheBuster(token).header("Cache-Control","no-store").header("Pragma","no-cache");
        Exchange one=sender.send("host-header",unique.header("X-Forwarded-Host",oneHost));if(one==null)return;
        String location=ScanSupport.header(one,"location"),body=ScanSupport.text(one);if(!location.contains(oneHost)&&!body.contains(oneHost))return;
        Exchange control=sender.send("host-header",request.cacheBuster(ScanSupport.token()).header("Cache-Control","no-store").header("Pragma","no-cache").header("X-Forwarded-Host",twoHost));if(control==null)return;
        if(!ScanSupport.header(control,"location").contains(twoHost)&&!ScanSupport.text(control).contains(twoHost))return;
        boolean redirect=location.contains(oneHost);
        out.add(ScanSupport.finding("forwarded-host-reflection",redirect?"Medium":"Info",redirect?"Tentative":"Observation","Forwarded host influences response","Distinct X-Forwarded-Host markers were reflected. Actual Host and connection origin were unchanged. Unique cache-buster URLs and no-store requests were used; no shared-cache retrieval or poisoning was attempted. Reflection alone does not prove account compromise or cache poisoning.",request,"header:X-Forwarded-Host",redirect?"Location contains "+oneHost:"Response body contains a unique forwarded-host marker",baseline,one,control));
    }
    private static void methods(ScanRequest request,Exchange baseline,Sender sender,List<Finding> out)throws IOException{
        Exchange options=sender.send("methods",request.method("OPTIONS",false).header("Cache-Control","no-store"));
        if(options!=null){String allow=ScanSupport.header(options,"allow");if(!allow.isEmpty())out.add(ScanSupport.finding("advertised-http-methods","Info","Observation","HTTP methods advertised","Allow describes advertised behavior, not authorization. PUT, DELETE and other state-changing methods were not invoked.",request,"method:OPTIONS","Allow: "+ScanSupport.trim(allow,400),baseline,options));}
        String marker="sprig_"+ScanSupport.token();URI plain=ScanSupport.canonical(request.uri.getScheme()+"://"+request.uri.getRawAuthority()+request.uri.getRawPath());
        ScanRequest trace=new ScanRequest(plain,"TRACE",java.util.Collections.emptyList(),new byte[0]).header("X-Sprig-Trace",marker).header("Cache-Control","no-store");
        Exchange echo=sender.send("methods",trace);if(success(echo)&&ScanSupport.text(echo).contains("X-Sprig-Trace: "+marker))out.add(ScanSupport.finding("trace-marker-reflection","Low","Certain","TRACE echoed a harmless request marker","TRACE returned the unique marker. Captured authentication headers, cookies and query parameters were omitted. Browser restrictions and application context affect risk; credential extraction was not attempted.",request,"method:TRACE","X-Sprig-Trace marker echoed; credential-free probe",baseline,echo));
    }
    private static void sqlBoolean(ScanRequest.Point point,Exchange baseline,Sender sender,List<Finding> out)throws IOException{
        String original=ScanSupport.text(baseline);if(original.length()<50||original.length()>200000||SQL_ERROR.matcher(original).find())return;
        int n=ScanSupport.integer(17,200);String yes=point.value+" AND "+n+'='+n,no=point.value+" AND "+n+'='+(n+1);
        Exchange b2=sender.send("sql-boolean",point.base),t1=sender.send("sql-boolean",point.mutate(yes)),f1=sender.send("sql-boolean",point.mutate(no)),t2=sender.send("sql-boolean",point.mutate(yes)),f2=sender.send("sql-boolean",point.mutate(no));
        if(!success(b2)||!success(t1)||!success(t2)||!success(f1)||!success(f2)||b2.status!=baseline.status||t1.status!=baseline.status||t2.status!=baseline.status||f1.status!=baseline.status||f2.status!=baseline.status)return;
        String falseBody=ScanSupport.text(f1);
        if(!original.equals(ScanSupport.text(b2))||!original.equals(ScanSupport.text(t1))||!original.equals(ScanSupport.text(t2))||!falseBody.equals(ScanSupport.text(f2))||original.equals(falseBody)||Math.abs(original.length()-falseBody.length())<20)return;
        out.add(ScanSupport.finding("sql-boolean-differential","Medium","Tentative","Repeatable boolean response differential","Two true-condition responses exactly matched repeated baseline, while two false-condition responses exactly matched each other and differed in length. Application query languages or validation can mimic this; SQL injection is not confirmed. No time delay, extraction or data modification was attempted.",point.base,point.label(),"Baseline/true SHA-256 "+hash(original)+"; false SHA-256 "+hash(falseBody)+"; lengths "+original.length()+" / "+falseBody.length(),baseline,t1,b2,f1,t2,f2));
    }
    private static void callback(ScanRequest.Point point,Exchange baseline,String callback,Sender sender,List<Finding> out)throws IOException{
        String marker=ScanSupport.token();String value=callback+(callback.endsWith("/")?"":"/")+"sprig-scan/"+marker;
        Exchange probe=sender.send("oast",point.mutate(value));if(probe==null)return;
        out.add(ScanSupport.finding("oast-payload-submitted","Info","Observation","User-provided callback payload submitted","The configured callback address was submitted at this insertion point. This HTTP response is not evidence of an out-of-band interaction. Poll your own Collaborator server and correlate the marker before reporting SSRF or another blind issue. No default public callback service was contacted.",point.base,point.label(),"Correlation marker: "+marker+"; callback: "+callback+"; probe status "+probe.status,baseline,probe));
    }
    private static boolean success(Exchange exchange){return exchange!=null&&exchange.status>=200&&exchange.status<300;}
    private static String hash(String value){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder text=new StringBuilder();for(byte b:bytes)text.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return text.toString();}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
