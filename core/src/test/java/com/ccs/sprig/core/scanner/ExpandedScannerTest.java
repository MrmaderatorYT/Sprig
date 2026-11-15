package com.ccs.sprig.core.scanner;

import com.ccs.sprig.core.model.Exchange;
import com.ccs.sprig.core.net.CallCancellation;
import com.ccs.sprig.core.proxy.HttpCodec;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;
import static org.junit.Assert.*;

public class ExpandedScannerTest {
    @Test public void templateEvaluationRequiresTwoComputedResultsAndControl()throws Exception{
        AtomicInteger ids=new AtomicInteger();ScanEngine engine=new ScanEngine((url,raw)->{
            String q=value(url,"q");Matcher matcher=Pattern.compile("\\{\\{([0-9]+)\\*([0-9]+)}}").matcher(q);StringBuffer body=new StringBuffer();
            while(matcher.find())matcher.appendReplacement(body,Integer.toString(Integer.parseInt(matcher.group(1))*Integer.parseInt(matcher.group(2))));matcher.appendTail(body);
            return response(ids.incrementAndGet(),url,"Content-Type: text/html\r\n",body.toString());
        });
        ScanReport report=engine.run(config("https://fixture.test/?q=base","template"),null);
        Finding f=find(report,"template-arithmetic-differential");assertNotNull(f);assertEquals(4,f.evidenceExchangeIds.size());assertEquals("Tentative",f.confidence);assertTrue(f.detail.contains("not attempted"));
        ScanEngine echo=new ScanEngine((url,raw)->response(1,url,"Content-Type: text/html\r\n",value(url,"q")));
        assertNull(find(echo.run(config("https://fixture.test/?q=base","template"),null),"template-arithmetic-differential"));
    }
    @Test public void crlfNeedsAnActualNewHeaderNotBodyReflection()throws Exception{
        AtomicInteger ids=new AtomicInteger();ScanEngine engine=new ScanEngine((url,raw)->{
            String q=value(url,"q"),headers="Content-Type: text/plain\r\n";int start=q.indexOf("\r\nX-Sprig-Probe: ");if(start>=0)headers+="X-Sprig-Probe: "+q.substring(start+17)+"\r\n";
            return response(ids.incrementAndGet(),url,headers,q);
        });
        ScanReport report=engine.run(config("https://fixture.test/?q=base","crlf"),null);Finding f=find(report,"response-header-injection");assertNotNull(f);assertEquals(3,f.evidenceExchangeIds.size());
        ScanEngine reflectedBody=new ScanEngine((url,raw)->response(1,url,"Content-Type: text/plain\r\n",value(url,"q")));
        assertNull(find(reflectedBody.run(config("https://fixture.test/?q=base","crlf"),null),"response-header-injection"));
    }
    @Test public void postJsonInsertionPreservesOtherTypesTargetCredentialsAndLength()throws Exception{
        byte[] body="{\"query\":\"base\",\"password\":\"private-value\",\"count\":7,\"enabled\":true}".getBytes(StandardCharsets.UTF_8);
        ScanConfig config=new ScanConfig("https://fixture.test/api",post("/api","application/json",body));configure(config,"reflection");config.allowStatefulTesting=true;config.insertionPoints.clear();config.insertionPoints.add(ScanConfig.InsertionType.JSON);
        AtomicInteger calls=new AtomicInteger();ScanEngine engine=new ScanEngine((url,raw)->{
            calls.incrementAndGet();HttpCodec.Message request=HttpCodec.parseEditedRequest(raw);assertEquals("POST",request.method());assertEquals("https://fixture.test/api",url);assertEquals("sid=private",request.header("Cookie"));assertEquals(Integer.toString(request.body.length),request.header("Content-Length"));
            JsonObject json=JsonParser.parseString(new String(request.body,StandardCharsets.UTF_8)).getAsJsonObject();assertEquals(7,json.get("count").getAsInt());assertTrue(json.get("enabled").getAsBoolean());assertEquals("private-value",json.get("password").getAsString());
            return response(calls.get(),url,"Content-Type: text/html\r\n",json.get("query").getAsString());
        });
        ScanReport report=engine.run(config,null);assertEquals(2,calls.get());assertNotNull(find(report,"html-reflection"));assertEquals("json:$.query",report.findings.get(0).parameter);
        ScanConfig wrongTarget=new ScanConfig("https://fixture.test/other",post("/api","application/json",body));wrongTarget.allowStatefulTesting=true;
        try{new ScanEngine((url,raw)->{fail("Must not send to a new POST target");return null;}).run(wrongTarget,null);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("original captured POST target"));}
    }
    @Test public void formAndHeaderInsertionAreExplicitAndSkipSensitiveNames()throws Exception{
        ScanConfig form=new ScanConfig("https://fixture.test/api",post("/api","application/x-www-form-urlencoded","q=base&csrf_token=keep".getBytes(StandardCharsets.US_ASCII)));configure(form,"reflection");form.allowStatefulTesting=true;form.insertionPoints.clear();form.insertionPoints.add(ScanConfig.InsertionType.FORM);
        AtomicInteger ids=new AtomicInteger();ScanReport report=new ScanEngine((url,raw)->{
            HttpCodec.Message message=HttpCodec.parseEditedRequest(raw);String content=new String(message.body,StandardCharsets.UTF_8);assertTrue(content.contains("csrf_token=keep"));return response(ids.incrementAndGet(),url,"Content-Type: text/html\r\n",pair(content,"q"));
        }).run(form,null);assertNotNull(find(report,"html-reflection"));assertEquals("form:q",report.findings.get(0).parameter);
        ScanConfig header=config("https://fixture.test/","reflection");header.insertionPoints.clear();header.insertionPoints.add(ScanConfig.InsertionType.HEADER);header.headerNames.clear();header.headerNames.add("X-Input");
        ScanReport headers=new ScanEngine((url,raw)->response(ids.incrementAndGet(),url,"Content-Type: text/html\r\n",HttpCodec.parseEditedRequest(raw).header("X-Input"))).run(header,null);assertNotNull(find(headers,"html-reflection"));
        header.headerNames.add("Authorization");try{new ScanEngine((url,raw)->{fail();return null;}).run(header,null);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("sensitive header"));}
    }
    @Test public void methodsUseOnlyOptionsAndCredentialFreeTrace()throws Exception{
        ScanConfig config=new ScanConfig("https://fixture.test/path?token=private","GET /path?token=private HTTP/1.1\r\nHost: fixture.test\r\nCookie: sid=secret\r\nAuthorization: Bearer private\r\nX-Api-Key: private\r\n\r\n".getBytes(StandardCharsets.US_ASCII));configure(config,"methods");
        AtomicInteger ids=new AtomicInteger();List<String> methods=new ArrayList<>();ScanReport report=new ScanEngine((url,raw)->{
            HttpCodec.Message request=HttpCodec.parseEditedRequest(raw);methods.add(request.method());String headers="Content-Type: text/plain\r\n",body="ok";
            if(request.method().equals("OPTIONS"))headers+="Allow: GET, POST, TRACE, DELETE\r\n";
            if(request.method().equals("TRACE")){assertNull(request.header("Cookie"));assertNull(request.header("Authorization"));assertNull(request.header("X-Api-Key"));assertNull(URI.create(url).getRawQuery());body="X-Sprig-Trace: "+request.header("X-Sprig-Trace");}
            return response(ids.incrementAndGet(),url,headers,body);
        }).run(config,null);assertEquals(Arrays.asList("GET","OPTIONS","TRACE"),methods);assertNotNull(find(report,"trace-marker-reflection"));assertNotNull(find(report,"advertised-http-methods"));
    }
    @Test public void forwardedHostProbeUsesUniqueUrlsWithoutActualHostOverride()throws Exception{
        AtomicInteger ids=new AtomicInteger();ScanReport report=new ScanEngine((url,raw)->{
            HttpCodec.Message request=HttpCodec.parseEditedRequest(raw);assertEquals("fixture.test",request.header("Host"));String forwarded=request.header("X-Forwarded-Host");
            if(forwarded!=null){assertTrue(url.contains("__sprig_probe="));assertEquals("no-store",request.header("Cache-Control"));}
            return response(ids.incrementAndGet(),url,"Content-Type: text/html\r\n",forwarded==null?"normal":forwarded);
        }).run(config("https://fixture.test/","host-header"),null);Finding f=find(report,"forwarded-host-reflection");assertNotNull(f);assertEquals("Observation",f.confidence);assertTrue(f.detail.contains("no shared-cache retrieval or poisoning"));
    }
    @Test public void passiveCategoriesRedactSecretsAndDoNotSubmitFormsOrFakeScriptLinks()throws Exception{
        AtomicInteger calls=new AtomicInteger();String key="-----BEGIN PRIVATE KEY-----\nVERY_PRIVATE_KEY_MATERIAL_123456789\n-----END PRIVATE KEY-----";
        String html="<title>Index of /</title><a href='../'>Parent Directory</a>"+key+"<form method='post' action='/change'><input name='email'></form>"+
                "<script>var fake=\"<a href='/fake'>\"; var x=location.hash; target.innerHTML=x;</script><!-- <a href='/comment'> -->";
        ScanConfig config=config("https://fixture.test/","disclosure","csrf","dom","directory","cache");config.activeChecks=false;
        ScanReport report=new ScanEngine((url,raw)->{calls.incrementAndGet();return response(calls.get(),url,"Content-Type: text/html\r\n",html);}).run(config,null);
        assertEquals(1,calls.get());assertNotNull(find(report,"private-key-disclosure"));assertNotNull(find(report,"csrf-form-observation"));assertNotNull(find(report,"dom-source-sink-observation"));assertNotNull(find(report,"directory-listing"));
        ByteArrayOutputStream json=new ByteArrayOutputStream();report.writeJson(json);assertFalse(json.toString("UTF-8").contains("VERY_PRIVATE_KEY_MATERIAL"));
        HtmlModel parsed=new HtmlModel(html);assertFalse(parsed.links.contains("/fake"));assertFalse(parsed.links.contains("/comment"));
        String protectedForm="<form method=post><input type=hidden name=csrf value=private></form>";ScanReport safe=new ScanEngine((url,raw)->response(1,url,"Content-Type: text/html\r\n",protectedForm)).run(config("https://fixture.test/","csrf"),null);assertNull(find(safe,"csrf-form-observation"));
    }
    @Test public void booleanDifferentialRequiresRepeatableBaselineAndTrueFalsePairs()throws Exception{
        AtomicInteger ids=new AtomicInteger();String normal="RESULTS "+String.join("",Collections.nCopies(100,"a"));String different="EMPTY";
        ScanReport report=new ScanEngine((url,raw)->{
            String q=value(url,"id");Matcher m=Pattern.compile(".* AND ([0-9]+)=([0-9]+)").matcher(q);String body=m.matches()&&!m.group(1).equals(m.group(2))?different:normal;return response(ids.incrementAndGet(),url,"Content-Type: text/plain\r\n",body);
        }).run(config("https://fixture.test/?id=1","sql-boolean"),null);Finding f=find(report,"sql-boolean-differential");assertNotNull(f);assertEquals(6,f.evidenceExchangeIds.size());assertEquals("Tentative",f.confidence);
        AtomicInteger dynamic=new AtomicInteger();ScanReport noisy=new ScanEngine((url,raw)->response(dynamic.incrementAndGet(),url,"Content-Type: text/plain\r\n",normal+dynamic.get())).run(config("https://fixture.test/?id=1","sql-boolean"),null);assertNull(find(noisy,"sql-boolean-differential"));
    }
    @Test public void callbackRequiresUserAddressAndOnlySubmitsAnObservation()throws Exception{
        ScanConfig missing=config("https://fixture.test/?url=base","oast");try{new ScanEngine((url,raw)->{fail();return null;}).run(missing,null);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("your own"));}
        missing.collaboratorPayload="unique.private-oast.test";AtomicInteger calls=new AtomicInteger();ScanReport report=new ScanEngine((url,raw)->{
            int call=calls.incrementAndGet();assertEquals("fixture.test",URI.create(url).getHost());if(call>1)assertTrue(value(url,"url").startsWith("https://unique.private-oast.test/sprig-scan/"));return response(call,url,"Content-Type: text/plain\r\n","ok");
        }).run(missing,null);Finding f=find(report,"oast-payload-submitted");assertNotNull(f);assertEquals("Observation",f.confidence);assertTrue(f.detail.contains("not evidence"));
    }
    @Test public void perCheckBudgetAndEscapedExportsAreEnforced()throws Exception{
        ScanConfig config=config("https://fixture.test/?a=1&b=2&c=3","reflection");config.maxProbesPerCheck=1;AtomicInteger calls=new AtomicInteger();
        ScanReport report=new ScanEngine((url,raw)->response(calls.incrementAndGet(),url,"Content-Type: text/html\r\n",URLDecoder.decode(URI.create(url).getRawQuery(),"UTF-8"))).run(config,null);
        assertEquals(2,calls.get());assertEquals(Integer.valueOf(1),report.checkRequests.get("reflection"));
        ByteArrayOutputStream json=new ByteArrayOutputStream();report.writeJson(json);assertTrue(JsonParser.parseString(json.toString("UTF-8")).getAsJsonObject().has("checkRequests"));
        Finding malicious=new Finding(1,"test","Info","Observation","=SUM(1)","<script>evil()</script>","https://fixture.test/","query:q","<img src=x onerror=evil()>",1,2);
        ScanReport test=new ScanReport("https://fixture.test/","Completed",0,1,2,1,Collections.singletonList(malicious),Collections.emptyList(),Collections.emptyList());
        ByteArrayOutputStream html=new ByteArrayOutputStream();test.writeHtml(html);assertFalse(html.toString("UTF-8").contains("<script>evil"));assertTrue(html.toString("UTF-8").contains("&lt;script&gt;"));
        ByteArrayOutputStream csv=new ByteArrayOutputStream();test.writeCsv(csv);assertTrue(csv.toString("UTF-8").contains("'=SUM(1)"));
    }
    @Test public void cancelClosesOnlyTheActiveScannerTransport()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),closed=new CountDownLatch(1);ScanEngine engine=new ScanEngine((url,raw)->{
            CallCancellation.track(()->closed.countDown());entered.countDown();try{if(!closed.await(3,TimeUnit.SECONDS))throw new IOException("No cancellation");}catch(InterruptedException ignored){Thread.currentThread().interrupt();}CallCancellation.check();return response(1,url,"","ok");
        });
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();try{
            java.util.concurrent.Future<ScanReport> result=worker.submit(()->engine.run(config("https://fixture.test/"),null));assertTrue(entered.await(2,TimeUnit.SECONDS));engine.cancel();assertTrue(closed.await(500,TimeUnit.MILLISECONDS));assertEquals("Cancelled",result.get(2,TimeUnit.SECONDS).state);
        }finally{engine.cancel();worker.shutdownNow();}
    }
    @Test public void cookieValuesAndNoStoreDoNotCreateFalsePolicyFindings()throws Exception{
        ScanConfig config=new ScanConfig("https://fixture.test/","GET / HTTP/1.1\r\nHost: fixture.test\r\nCookie: sid=private\r\n\r\n".getBytes(StandardCharsets.US_ASCII));configure(config,"headers","cache");config.activeChecks=false;
        ScanReport report=new ScanEngine((url,raw)->response(1,url,"Content-Type: text/html\r\nCache-Control: public, no-store\r\nSet-Cookie: s=samesite=none; Secure; HttpOnly; SameSite=Lax\r\nSet-Cookie: __Host-sid=value; Path=/; Secure; HttpOnly; SameSite=Lax\r\n","ok")).run(config,null);
        assertNull(find(report,"cookie-samesite-none"));assertNull(find(report,"cookie-prefix-policy"));assertNull(find(report,"authenticated-shared-cache"));
    }
    @Test public void reflectionReportsScriptContextWithoutExecutionAndPrivateKeySnippetsAreRedacted()throws Exception{
        AtomicInteger ids=new AtomicInteger();ScanReport report=new ScanEngine((url,raw)->response(ids.incrementAndGet(),url,"Content-Type: text/html\r\n","<script>var reflected='"+value(url,"q")+"';</script>")).run(config("https://fixture.test/?q=base","reflection"),null);
        Finding finding=find(report,"html-reflection");assertNotNull(finding);assertTrue(finding.detail.contains("JavaScript block"));assertTrue(finding.detail.contains("not attempted or demonstrated"));
        String body="-----BEGIN PRIVATE KEY-----\nSECRET_MATERIAL_123456789_sprig_marker_123456789\n-----END PRIVATE KEY-----";
        assertEquals("[REDACTED PRIVATE KEY CONTEXT]",ScanSupport.snippet(body,body.indexOf("sprig_marker")));
        assertFalse(ScanSupport.redact("Authorization: Bearer private.jwt.value").contains("private.jwt.value"));
    }
    @Test public void crawlerHonorsBaseHrefWithoutCrossingOriginOrFollowingScriptStrings()throws Exception{
        List<String> urls=Collections.synchronizedList(new ArrayList<>());ScanConfig config=config("https://fixture.test/page");config.crawl=true;config.activeChecks=false;
        ScanReport report=new ScanEngine((url,raw)->{urls.add(url);return response(urls.size(),url,"Content-Type: text/html\r\n",url.endsWith("/page")?"<base href='https://outside.invalid/'><a href='relative'>external relative</a><a href='https://fixture.test/actual'>actual</a><script>var fake=\"<a href='/fake'>\";</script>":"done");}).run(config,null);
        assertEquals(2,report.requests);assertEquals(Arrays.asList("https://fixture.test/page","https://fixture.test/actual"),urls);
    }
    @Test public void cancelDoesNotRunSlowTransportCloseOnTheCallingThread()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),closeEntered=new CountDownLatch(1),releaseClose=new CountDownLatch(1);java.util.concurrent.atomic.AtomicReference<Thread> closingThread=new java.util.concurrent.atomic.AtomicReference<>();
        ScanEngine engine=new ScanEngine((url,raw)->{
            CallCancellation.track(()->{closingThread.set(Thread.currentThread());closeEntered.countDown();try{releaseClose.await(3,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}});entered.countDown();
            while(closeEntered.getCount()!=0)try{closeEntered.await(100,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){}
            CallCancellation.check();return response(1,url,"","done");
        });
        java.util.concurrent.ExecutorService owner=java.util.concurrent.Executors.newSingleThreadExecutor();try{
            java.util.concurrent.Future<ScanReport> run=owner.submit(()->engine.run(config("https://fixture.test/"),null));assertTrue(entered.await(2,TimeUnit.SECONDS));
            Thread caller=Thread.currentThread();long start=System.nanoTime();engine.cancel();long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
            assertTrue("Cancel must return without waiting for TLS/socket close",elapsed<250);assertTrue(closeEntered.await(2,TimeUnit.SECONDS));assertNotSame(caller,closingThread.get());releaseClose.countDown();assertEquals("Cancelled",run.get(2,TimeUnit.SECONDS).state);
        }finally{releaseClose.countDown();engine.cancel();owner.shutdownNow();}
    }
    private static ScanConfig config(String url,String...checks){ScanConfig config=new ScanConfig(url);configure(config,checks);return config;}
    private static void configure(ScanConfig config,String...checks){config.enabledChecks.clear();config.enabledChecks.addAll(Arrays.asList(checks));config.crawl=false;config.delayMillis=200;}
    private static Finding find(ScanReport report,String id){for(Finding f:report.findings)if(f.checkId.equals(id))return f;return null;}
    private static Exchange response(long id,String url,String headers,String body){Exchange e=new Exchange();e.id=id;e.url=url;e.status=200;e.method="GET";e.responseHeaders="HTTP/1.1 200 OK\r\n"+headers+"\r\n";e.responseBody=body==null?new byte[0]:body.getBytes(StandardCharsets.UTF_8);return e;}
    private static String value(String url,String name)throws IOException{return pair(URI.create(url).getRawQuery(),name);}
    private static String pair(String raw,String name)throws IOException{if(raw!=null)for(String pair:raw.split("&")){String[] kv=pair.split("=",2);if(URLDecoder.decode(kv[0],"UTF-8").equals(name))return kv.length>1?URLDecoder.decode(kv[1],"UTF-8"):"";}return "";}
    private static byte[] post(String path,String type,byte[] body){byte[] head=("POST "+path+" HTTP/1.1\r\nHost: fixture.test\r\nCookie: sid=private\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII);byte[] raw=Arrays.copyOf(head,head.length+body.length);System.arraycopy(body,0,raw,head.length,body.length);return raw;}
}
