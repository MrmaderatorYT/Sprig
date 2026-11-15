package com.ccs.sprig.core.scanner;

import com.ccs.sprig.core.model.Exchange;
import com.ccs.sprig.core.proxy.HttpCodec;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class ScanEngineTest {
    @Test public void crawlerKeepsOriginCredentialsAndSkipsStateChangingLinks()throws Exception {
        List<String> targets=Collections.synchronizedList(new ArrayList<>());
        AtomicInteger ids=new AtomicInteger();
        ScanEngine engine=new ScanEngine((url,raw)->{
            targets.add(url);assertTrue(url.startsWith("https://fixture.test/"));
            assertTrue(new String(raw,StandardCharsets.ISO_8859_1).contains("Cookie: session=private"));
            String path=URI.create(url).getPath();
            if(path.equals("/redirect"))return response(ids.incrementAndGet(),url,302,"Location: https://outside.invalid/\r\n","");
            return response(ids.incrementAndGet(),url,200,"Content-Type: text/html\r\n",path.equals("/")?
                    "<a href='/safe?a=1&amp;b=2'>safe</a><a href='/safe?a=1&b=2'>duplicate</a>"+
                    "<a href='https://outside.invalid/'>external</a><a href='//outside.invalid/'>relative external</a>"+
                    "<a href='/logout'>logout</a><a href='/%64elete'>delete</a><a href='/x?action=delete'>action</a>"+
                    "<a href='#fragment'>same</a><a href='/redirect'>redirect</a>":"ok");
        });
        ScanConfig config=new ScanConfig("https://fixture.test/","GET / HTTP/1.1\r\nHost: fixture.test\r\nCookie: session=private\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        config.activeChecks=false;config.delayMillis=200;
        ScanReport report=engine.run(config,null);
        assertEquals("Completed",report.state);assertEquals(3,targets.size());
        assertTrue(targets.contains("https://fixture.test/safe?a=1&b=2"));
        assertFalse(targets.stream().anyMatch(u->u.contains("outside.invalid")||u.contains("logout")||u.contains("delete")));
    }

    @Test public void activeChecksKeepEvidenceAndDoNotClaimScriptExecution()throws Exception {
        AtomicInteger ids=new AtomicInteger();List<String> visited=new ArrayList<>();
        ScanEngine engine=new ScanEngine((url,raw)->{
            visited.add(url);Map<String,String> query=query(URI.create(url));String q=query.get("q"),next=query.get("next");
            HttpCodec.Message request=HttpCodec.parseEditedRequest(raw);String origin=request.header("Origin");
            String headers="Content-Type: text/html\r\n";
            if(origin!=null)headers+="Access-Control-Allow-Origin: "+origin+"\r\nAccess-Control-Allow-Credentials: true\r\n";
            if(next.startsWith("https://sprig-"))return response(ids.incrementAndGet(),url,302,"Location: "+next+"\r\n","");
            String body=q.endsWith("'")?"You have an error in your SQL syntax":q.contains("sprig_")?"<p>"+q+"</p>":"<p>normal</p>";
            return response(ids.incrementAndGet(),url,200,headers,body);
        });
        ScanConfig config=new ScanConfig("https://fixture.test/search?q=value&next=home");config.crawl=false;config.delayMillis=200;
        ScanReport report=engine.run(config,null);
        assertTrue(has(report,"html-reflection"));assertTrue(has(report,"sql-error-differential"));
        assertTrue(has(report,"external-redirect"));assertTrue(has(report,"cors-reflected-credentials"));
        for(Finding f:report.findings)if(!f.checkId.startsWith("passive:")) {
            assertTrue(f.baselineExchangeId>0);assertTrue(f.probeExchangeId>0);assertFalse(f.evidence.isEmpty());
            if(f.checkId.equals("html-reflection")){assertEquals("Tentative",f.confidence);assertTrue(f.detail.contains("not attempted or demonstrated"));}
        }
        assertFalse(visited.stream().anyMatch(u->URI.create(u).getHost().endsWith("example.invalid")));
        ByteArrayOutputStream json=new ByteArrayOutputStream();report.writeJson(json);
        String export=json.toString("UTF-8");assertTrue(export.contains("sprig-scan-1"));assertTrue(export.contains("\\\""));
    }

    @Test public void escapedReflectionAndExistingDatabaseErrorAreNotConfirmedIssues()throws Exception {
        AtomicInteger ids=new AtomicInteger();
        ScanEngine engine=new ScanEngine((url,raw)->{
            String q=query(URI.create(url)).get("q");String escaped=q.replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
            return response(ids.incrementAndGet(),url,200,"Content-Type: text/html\r\n","You have an error in your SQL syntax "+escaped);
        });
        ScanConfig config=new ScanConfig("https://fixture.test/?q=a");config.crawl=false;config.delayMillis=200;
        ScanReport report=engine.run(config,null);
        assertTrue(has(report,"input-reflection"));assertFalse(has(report,"html-reflection"));assertFalse(has(report,"sql-error-differential"));
    }

    @Test public void globalRequestBudgetAndRateHoldAcrossWorkers()throws Exception {
        List<Long> times=Collections.synchronizedList(new ArrayList<>());
        ScanEngine engine=new ScanEngine((url,raw)->{
            times.add(System.nanoTime());StringBuilder links=new StringBuilder();for(int i=0;i<10;i++)links.append("<a href='/p").append(i).append("'>p</a>");
            return response(times.size(),url,200,"Content-Type: text/html\r\n",links.toString());
        });
        ScanConfig config=new ScanConfig("https://fixture.test/");config.activeChecks=false;config.maxRequests=3;config.concurrency=4;config.delayMillis=200;
        ScanReport report=engine.run(config,null);
        assertEquals("Request budget reached",report.state);assertEquals(3,times.size());assertEquals(3,report.requests);
        Collections.sort(times);for(int i=1;i<times.size();i++)assertTrue("Requests must be globally paced",TimeUnit.NANOSECONDS.toMillis(times.get(i)-times.get(i-1))>=175);
    }

    @Test public void rejectsNonGetSeedAndInvalidLimitsWithoutTransport()throws Exception {
        ScanEngine engine=new ScanEngine((url,raw)->{fail("No network call expected");return null;});
        ScanConfig post=new ScanConfig("https://fixture.test/","POST / HTTP/1.1\r\nHost: fixture.test\r\nContent-Length: 1\r\n\r\nx".getBytes(StandardCharsets.US_ASCII));
        try{engine.run(post,null);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("GET"));}
        ScanConfig tooFast=new ScanConfig("https://fixture.test/");tooFast.delayMillis=1;
        try{engine.run(tooFast,null);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("delay"));}
        try{engine.run(new ScanConfig("https://fixture.test/logout"),null);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("state-changing"));}
        assertFalse(engine.isRunning());
    }

    @Test public void cancellationStopsQueuedRequests()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger sent=new AtomicInteger();
        ScanEngine engine=new ScanEngine((url,raw)->{sent.incrementAndGet();entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return response(1,url,200,"Content-Type: text/html\r\n","<a href='/next'>next</a>");});
        ScanConfig config=new ScanConfig("https://fixture.test/");config.activeChecks=false;
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{
            java.util.concurrent.Future<ScanReport> result=worker.submit(()->engine.run(config,null));
            assertTrue(entered.await(2,TimeUnit.SECONDS));engine.cancel();release.countDown();
            assertEquals("Cancelled",result.get(3,TimeUnit.SECONDS).state);assertEquals(1,sent.get());
        }finally{release.countDown();worker.shutdownNow();}
    }

    @Test public void cancellationBeforeWorkerStartsDoesNotSendAnything()throws Exception {
        ScanEngine engine=new ScanEngine((url,raw)->{fail("Cancelled startup must not send a request");return null;});engine.cancel();
        ScanReport report=engine.run(new ScanConfig("https://fixture.test/"),null);
        assertEquals("Cancelled",report.state);assertEquals(0,report.requests);
    }

    @Test public void capturedCredentialsCannotCrossTheSeedOrigin()throws Exception {
        ScanEngine engine=new ScanEngine((url,raw)->{fail("Captured credentials must not cross origins");return null;});
        ScanConfig config=new ScanConfig("https://another.test/","GET / HTTP/1.1\r\nHost: original.test\r\nAuthorization: Bearer private\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        try{engine.run(config,null);fail("Mismatched origin must fail validation");}catch(IOException expected){assertTrue(expected.getMessage().contains("different origin"));}
    }

    @Test public void localHttpFixtureIsActuallyCrawledWithoutFollowingExternalRedirect()throws Exception {
        AtomicInteger received=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            received.incrementAndGet();String body=exchange.getRequestURI().getPath().equals("/")?"<a href='/second'>next</a>":"second";
            exchange.getResponseHeaders().set("Content-Type","text/html");byte[] data=body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);exchange.close();
        });server.start();
        AtomicInteger ids=new AtomicInteger();
        try{
            ScanEngine engine=new ScanEngine((url,raw)->{
                HttpURLConnection connection=(HttpURLConnection)URI.create(url).toURL().openConnection();connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(2000);connection.setReadTimeout(2000);
                HttpCodec.Message request=HttpCodec.parseEditedRequest(raw);for(String[] h:request.headers)if(!h[0].equalsIgnoreCase("Host")&&!h[0].equalsIgnoreCase("Connection"))connection.setRequestProperty(h[0],h[1]);
                int status=connection.getResponseCode();ByteArrayOutputStream body=new ByteArrayOutputStream();byte[] buffer=new byte[1024];int n;
                try(java.io.InputStream input=connection.getInputStream()){while((n=input.read(buffer))!=-1)body.write(buffer,0,n);}
                Exchange response=response(ids.incrementAndGet(),url,status,"Content-Type: "+connection.getContentType()+"\r\n",body.toString("UTF-8"));connection.disconnect();return response;
            });
            ScanConfig config=new ScanConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/");config.activeChecks=false;config.delayMillis=200;
            ScanReport report=engine.run(config,null);assertEquals(2,report.pages);assertEquals(2,received.get());assertTrue(report.errors.isEmpty());
        }finally{server.stop(0);}
    }

    @Test public void canonicalizationNormalizesDefaultPortsFragmentsAndDotSegments()throws Exception {
        assertEquals("https://example.test/b?q=x",ScanEngine.canonical("HTTPS://EXAMPLE.TEST:443/a/../b?q=x#fragment").toString());
        for(String bad:new String[]{"file:///etc/passwd","https://user:pass@example.test/","https://example.test:99999/","https:///missing"})
            try{ScanEngine.canonical(bad);fail("Rejected URL expected: "+bad);}catch(IOException expected){}
    }
    private static Exchange response(long id,String url,int status,String headers,String body){Exchange e=new Exchange();e.id=id;e.url=url;e.method="GET";e.status=status;e.responseHeaders="HTTP/1.1 "+status+" Test\r\n"+headers+"\r\n";e.responseBody=body.getBytes(StandardCharsets.UTF_8);return e;}
    private static boolean has(ScanReport report,String check){return report.findings.stream().anyMatch(f->f.checkId.equals(check));}
    private static Map<String,String> query(URI uri)throws IOException {Map<String,String> map=new HashMap<>();if(uri.getRawQuery()!=null)for(String pair:uri.getRawQuery().split("&")){String[] kv=pair.split("=",2);map.put(URLDecoder.decode(kv[0],"UTF-8"),kv.length==2?URLDecoder.decode(kv[1],"UTF-8"):"");}return map;}
}
