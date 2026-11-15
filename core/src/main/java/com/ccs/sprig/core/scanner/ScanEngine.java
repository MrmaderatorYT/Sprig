package com.ccs.sprig.core.scanner;

import com.ccs.sprig.core.model.Exchange;
import com.ccs.sprig.core.net.CallCancellation;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Explicit-scope audit engine. Extended checks, insertion locations and POST replay require opt-in. */
public final class ScanEngine {
    /** Transport must return 3xx responses unchanged, without automatically following redirects. */
    public interface Transport {Exchange send(String url,byte[] rawRequest)throws IOException;}
    public interface Listener {
        default void onProgress(Progress progress){ }
        default void onFinding(Finding finding){ }
    }
    private static final int MAX_FINDINGS=500,MAX_ERRORS=200;
    private static final ThreadPoolExecutor CANCELLATIONS=new ThreadPoolExecutor(0,1,10,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),task->{Thread thread=new Thread(task,"sprig-scan-cancel");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    private final Transport transport;
    private final AtomicBoolean running=new AtomicBoolean(),cancelled=new AtomicBoolean();
    private final AtomicBoolean cancellationQueued=new AtomicBoolean();
    private final AtomicInteger requests=new AtomicInteger(),reservedRequests=new AtomicInteger();
    private final Object lifecycleLock=new Object(),rateLock=new Object(),reportLock=new Object();
    private final List<Finding> findings=new ArrayList<>();private final List<String> errors=new ArrayList<>(),visited=new ArrayList<>();
    private final Set<String> findingKeys=new HashSet<>();
    private final Map<String,AtomicInteger> reservedChecks=new ConcurrentHashMap<>(),actualChecks=new ConcurrentHashMap<>();
    private final Set<CallCancellation.Token> activeTokens=Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile ScanReport snapshot;private volatile ExecutorService workers;private volatile boolean budgetReached;private long nextRequestNanos;
    public ScanEngine(Transport transport){if(transport==null)throw new IllegalArgumentException("Transport required");this.transport=transport;}
    public boolean isRunning(){return running.get();}
    public ScanReport snapshot(){return snapshot;}
    /** Nonblocking for UI callers; closes only this scan's transports on a bounded cleanup worker. */
    public void cancel(){
        ExecutorService executor;synchronized(lifecycleLock){cancelled.set(true);executor=workers;}
        if(executor!=null)executor.shutdownNow();
        if(!activeTokens.isEmpty()&&cancellationQueued.compareAndSet(false,true))try{
            CANCELLATIONS.execute(()->{try{closeActiveTokens();}finally{cancellationQueued.set(false);}});
        }catch(RejectedExecutionException saturated){
            // run() always performs a final close on its background owner if the helper is busy.
            cancellationQueued.set(false);
        }
    }
    private void closeActiveTokens(){for(CallCancellation.Token token:activeTokens)token.cancel();}
    public ScanReport run(ScanConfig source,Listener listener)throws IOException{
        synchronized(lifecycleLock){if(!running.compareAndSet(false,true))throw new IOException("A scan is already running");}
        long started=System.currentTimeMillis();Config config=null;int completed=0;
        try{
            final Config settings=new Config(source);config=settings;requests.set(0);reservedRequests.set(0);reservedChecks.clear();actualChecks.clear();budgetReached=false;nextRequestNanos=0;
            synchronized(reportLock){findings.clear();errors.clear();visited.clear();findingKeys.clear();}
            Listener callbacks=listener==null?new Listener(){}:listener;
            ExecutorService executor=Executors.newFixedThreadPool(settings.concurrency,r->{Thread thread=new Thread(r,"sprig-scanner");thread.setDaemon(true);return thread;});workers=executor;
            CompletionService<PageResult> completion=new ExecutorCompletionService<>(executor);ArrayDeque<Page> queue=new ArrayDeque<>();queue.add(new Page(settings.seed,0));
            Set<String> scheduled=new LinkedHashSet<>();scheduled.add(settings.seed.toString());int active=0;publish(settings,started,0,1,"Running",callbacks);
            while((!queue.isEmpty()||active>0)&&!cancelled.get()){
                while(active<settings.concurrency&&!queue.isEmpty()&&!budgetReached){Page page=queue.remove();completion.submit(()->inspect(page,settings));active++;}
                if(active==0)break;Future<PageResult> done;
                try{done=completion.poll(250,TimeUnit.MILLISECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();cancel();break;}
                if(done==null)continue;active--;completed++;
                try{
                    PageResult result=done.get();synchronized(reportLock){visited.add(result.page.uri.toString());for(String error:result.errors)if(errors.size()<MAX_ERRORS)errors.add(error);}
                    for(Finding finding:result.findings)add(finding,callbacks);
                    if(settings.crawl&&!budgetReached&&result.page.depth<settings.maxDepth)for(URI link:result.links){if(scheduled.size()>=settings.maxPages)break;if(scheduled.add(link.toString()))queue.add(new Page(link,result.page.depth+1));}
                }catch(Exception error){if(!cancelled.get())synchronized(reportLock){if(errors.size()<MAX_ERRORS)errors.add("Worker failed: "+message(error.getCause()==null?error:error.getCause()));}}
                publish(settings,started,completed,queue.size()+active,"Running",callbacks);
            }
            publish(settings,started,completed,0,cancelled.get()?"Cancelled":budgetReached?"Request budget reached":"Completed",callbacks);return snapshot;
        }finally{ExecutorService executor=workers;workers=null;if(executor!=null)executor.shutdownNow();closeActiveTokens();running.set(false);if(config==null)snapshot=null;}
    }
    private PageResult inspect(Page page,Config config){
        PageResult result=new PageResult(page);if(cancelled.get())return result;
        try{
            ScanRequest request=page.uri.equals(config.seed)?config.template:config.template.page(page.uri);
            Exchange baseline=send("",request,config);if(baseline==null)return result;String body=ScanSupport.text(baseline);HtmlModel html=ScanSupport.html(baseline)?new HtmlModel(body):null;
            PassiveChecks.analyze(request,baseline,body,html,config.checks,result.findings);
            if(config.crawl){
                String location=ScanSupport.header(baseline,"location");if(baseline.status>=300&&baseline.status<400&&!location.isEmpty())addLink(result,config,page.uri,location);
                if(html!=null){URI linkBase=page.uri;try{if(!html.baseHref.isEmpty())linkBase=ScanSupport.canonical(page.uri.resolve(html.baseHref).toString());}catch(Exception ignored){}
                    for(String link:html.links)addLink(result,config,linkBase,link);}
            }
            if(config.activeChecks&&baseline.status>=200&&baseline.status<400&&!cancelled.get()){
                List<ScanRequest.Point> points=request.points(config.insertions,config.headerNames,config.maxInsertionPoints);
                ActiveChecks.run(request,baseline,body,points,config.checks,config.callback,new ActiveChecks.Sender(){
                    @Override public Exchange send(String check,ScanRequest probe)throws IOException{return ScanEngine.this.send(check,probe,config);}
                    @Override public boolean cancelled(){return cancelled.get()||budgetReached;}
                },result.findings);
            }
        }catch(BudgetException ignored){budgetReached=true;}catch(Exception error){if(!cancelled.get())result.errors.add(page.uri+": "+message(error));}
        return result;
    }
    private Exchange send(String check,ScanRequest request,Config config)throws IOException{
        if(cancelled.get())return null;if(!ScanSupport.sameOrigin(config.seed,request.uri)||ScanSupport.unsafe(request.uri))throw new IOException("Request escaped the configured scan scope or resembles a state-changing action");
        if(!check.isEmpty()){
            AtomicInteger count=reservedChecks.computeIfAbsent(check,key->new AtomicInteger());int previous;
            do{previous=count.get();if(previous>=config.maxProbesPerCheck)return null;}while(!count.compareAndSet(previous,previous+1));
        }
        long wait;
        synchronized(rateLock){if(reservedRequests.get()>=config.maxRequests)throw new BudgetException();reservedRequests.incrementAndGet();long now=System.nanoTime(),start=Math.max(now,nextRequestNanos);wait=start-now;nextRequestNanos=start+TimeUnit.MILLISECONDS.toNanos(config.delayMillis);}
        if(wait>0)try{TimeUnit.NANOSECONDS.sleep(wait);}catch(InterruptedException error){Thread.currentThread().interrupt();cancel();return null;}
        if(cancelled.get())return null;
        CallCancellation.Token token=new CallCancellation.Token();activeTokens.add(token);if(cancelled.get())token.cancel();
        try(CallCancellation.Scope ignored=CallCancellation.enter(token)){
            CallCancellation.check();requests.incrementAndGet();if(!check.isEmpty())actualChecks.computeIfAbsent(check,key->new AtomicInteger()).incrementAndGet();
            Exchange response=transport.send(request.uri.toString(),request.raw());
            if(response==null)throw new IOException("Transport returned no exchange");if(response.error!=null&&!response.error.isEmpty())throw new IOException(response.error);return response;
        }finally{activeTokens.remove(token);}
    }
    private void add(Finding finding,Listener listener){Finding published;synchronized(reportLock){String key=finding.checkId+'\n'+finding.url+'\n'+finding.parameter;if(findings.size()>=MAX_FINDINGS||!findingKeys.add(key))return;published=finding.withId(findings.size()+1L);findings.add(published);}try{listener.onFinding(published);}catch(RuntimeException ignored){}}
    private void publish(Config config,long started,int pages,int queued,String state,Listener listener){
        Map<String,Integer> counts=new HashMap<>();for(Map.Entry<String,AtomicInteger> entry:actualChecks.entrySet())counts.put(entry.getKey(),entry.getValue().get());
        synchronized(reportLock){snapshot=new ScanReport(config.seed.toString(),state,started,"Running".equals(state)?0:System.currentTimeMillis(),requests.get(),pages,findings,errors,visited,counts,new ArrayList<>(config.checks));}
        try{listener.onProgress(new Progress(pages,requests.get(),queued,snapshot.findings.size(),state));}catch(RuntimeException ignored){}
    }
    private static void addLink(PageResult result,Config config,URI base,String value){if(value==null||value.length()>4096||result.links.size()>=200)return;try{URI link=ScanSupport.canonical(base.resolve(value.trim()).toString());if(ScanSupport.sameOrigin(config.seed,link)&&!ScanSupport.unsafe(link))result.links.add(link);}catch(Exception ignored){}}
    static URI canonical(String value)throws IOException{return ScanSupport.canonical(value);}
    private static String message(Throwable error){String text=error.getMessage();return ScanSupport.trim(text==null?error.getClass().getSimpleName():text.replace('\r',' ').replace('\n',' '),300);}
    private static final class Config{
        final URI seed;final ScanRequest template;final int maxPages,maxRequests,concurrency,maxDepth,maxInsertionPoints,maxProbesPerCheck;final long delayMillis;final boolean crawl,activeChecks;
        final Set<String> checks,headerNames;final Set<ScanConfig.InsertionType> insertions;final String callback;
        Config(ScanConfig source)throws IOException{
            if(source==null)throw new IOException("Scan configuration required");seed=canonical(source.seedUrl);maxPages=source.maxPages;maxRequests=source.maxRequests;concurrency=source.concurrency;maxDepth=source.maxDepth;delayMillis=source.delayMillis;crawl=source.crawl;activeChecks=source.activeChecks;maxInsertionPoints=source.maxInsertionPoints;maxProbesPerCheck=source.maxProbesPerCheck;
            if(maxPages<1||maxPages>200||maxRequests<1||maxRequests>2000||concurrency<1||concurrency>4||maxDepth<0||maxDepth>8||delayMillis<200||delayMillis>60000||maxInsertionPoints<1||maxInsertionPoints>24||maxProbesPerCheck<1||maxProbesPerCheck>2000)throw new IOException("Use 1–200 pages, 1–2000 requests/check probes, 1–4 workers, depth 0–8, 1–24 insertion points and delay 200–60000 ms");
            if(ScanSupport.unsafe(seed))throw new IOException("The selected URL resembles a state-changing action; choose a read-only test endpoint");
            checks=Collections.unmodifiableSet(new LinkedHashSet<>(source.enabledChecks));for(String id:checks)if(!CheckRegistry.known(id))throw new IOException("Unknown scanner check: "+id);
            insertions=source.insertionPoints.isEmpty()?Collections.emptySet():Collections.unmodifiableSet(EnumSet.copyOf(source.insertionPoints));
            headerNames=Collections.unmodifiableSet(new LinkedHashSet<>(source.headerNames));if(headerNames.size()>8)throw new IOException("Use at most eight explicit header insertion names");for(String name:headerNames)if(ScanRequest.forbiddenHeader(name))throw new IOException("Unsupported or sensitive header insertion: "+name);
            template=ScanRequest.captured(seed,source.capturedRequest,source.allowStatefulTesting);
            template.points(insertions,headerNames,maxInsertionPoints);
            String supplied=source.collaboratorPayload==null?"":source.collaboratorPayload.trim();
            if(checks.contains("oast")){
                if(supplied.isEmpty())throw new IOException("OAST check requires your own Collaborator callback URL or hostname");if(supplied.length()>2048)throw new IOException("Callback address exceeds 2048 characters");
                URI address=canonical(supplied.contains("://")?supplied:"https://"+supplied);if(address.getRawQuery()!=null)throw new IOException("Callback address must not contain a query string");callback=address.toString();
            }else callback="";
        }
    }
    private static final class Page{final URI uri;final int depth;Page(URI uri,int depth){this.uri=uri;this.depth=depth;}}
    private static final class PageResult{final Page page;final List<Finding> findings=new ArrayList<>();final List<String> errors=new ArrayList<>();final Set<URI> links=new LinkedHashSet<>();PageResult(Page page){this.page=page;}}
    private static final class BudgetException extends IOException{private static final long serialVersionUID=1L;}
}
