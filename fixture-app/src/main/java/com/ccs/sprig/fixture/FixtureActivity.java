package com.ccs.sprig.fixture;

import android.app.Activity;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Debug;
import android.os.Process;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** Explicit local-IPv4-only traffic source. Never resumes or repeats after activity recreation. */
public final class FixtureActivity extends Activity {
    private ThreadPoolExecutor workers;
    private volatile boolean cancelled,reportWritten;
    private volatile String cancelReason="";
    private java.util.concurrent.ScheduledThreadPoolExecutor deadline;
    private final java.util.Set<HttpURLConnection> connections=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.ArrayDeque<JSONObject> lifecycle=new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<JSONObject> requestErrors=new java.util.ArrayDeque<>();
    private TextView status;
    @Override public void onCreate(Bundle state){super.onCreate(state);trace("onCreate",state!=null);status=new TextView(this);status.setTextSize(18);status.setPadding(24,24,24,24);setContentView(status);
        if(state!=null){status.setText("Fixture was recreated; traffic is not repeated. Launch explicitly for a new run.");return;}
        try{start(getIntent());}catch(Exception error){status.setText("Fixture rejected: "+error.getMessage());}
    }
    private void start(Intent intent)throws Exception{
        String address=intent.getStringExtra("url");if(address==null)throw new IllegalArgumentException("Pass an explicit local HTTP fixture URL");URI uri=URI.create(address);
        if(!"http".equals(uri.getScheme())||uri.getUserInfo()!=null||uri.getFragment()!=null||!privateIpv4(uri.getHost())||!"/sprig-fixture".equals(uri.getPath()))throw new IllegalArgumentException("Only /sprig-fixture on an explicit private IPv4 HTTP address is allowed");
        String token=intent.getStringExtra("token");if(token==null)token="sprig040fixture";if(!token.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid fixture token");
        int count=intent.getIntExtra("requests",20),parallel=intent.getIntExtra("workers",2);if(count<1||count>5000||parallel<1||parallel>4)throw new IllegalArgumentException("Use 1–5000 requests and 1–4 workers");
        String suppliedRun=intent.getStringExtra("run_id");if(suppliedRun!=null&&!suppliedRun.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid run_id");final String runId=suppliedRun;
        final String target=uri.getScheme()+"://"+uri.getRawAuthority()+uri.getRawPath()+"?token="+token,expected="sprig-fixture-v1:"+token;
        final long[] durations=new long[count];AtomicInteger next=new AtomicInteger(),completed=new AtomicInteger(),errors=new AtomicInteger(),remaining=new AtomicInteger(parallel);AtomicLong workerCpu=new AtomicLong();long started=System.nanoTime(),processCpu=Process.getElapsedCpuTime();
        JSONObject before=metrics();boolean vpn=hasVpn();status.setText("Running "+count+" local requests; VPN transport="+vpn);
        workers=new ThreadPoolExecutor(parallel,parallel,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(4));
        deadline=new java.util.concurrent.ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"sprig-fixture-deadline");t.setDaemon(true);return t;});deadline.setRemoveOnCancelPolicy(true);
        // Stop admissions after110s; active socket calls additionally have10s connect/read timeouts.
        deadline.schedule(()->cancel("110-second workload deadline"),110,TimeUnit.SECONDS);
        for(int worker=0;worker<parallel;worker++)workers.execute(()->{long cpu=Debug.threadCpuTimeNanos();try{int index;while(!cancelled&&(index=next.getAndIncrement())<count){long began=System.nanoTime();try{request(target,expected,index);}catch(Exception error){errors.incrementAndGet();recordError(index,error);}finally{durations[index]=Math.max(0,System.nanoTime()-began);completed.incrementAndGet();}}}finally{workerCpu.addAndGet(Debug.threadCpuTimeNanos()-cpu);if(remaining.decrementAndGet()==0){try{long elapsed=System.nanoTime()-started;long[] sorted=Arrays.copyOf(durations,Math.min(count,completed.get()));Arrays.sort(sorted);JSONObject result=new JSONObject();result.put("kind","Android local fixture through selected VPN");result.put("androidApi",android.os.Build.VERSION.SDK_INT);result.put("device",android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL);result.put("url",target);result.put("requested",count);result.put("workers",parallel);result.put("completed",completed.get());result.put("errors",errors.get());result.put("requestErrors",snapshot(requestErrors));result.put("workloadDeadlineSeconds",110);result.put("vpnTransportAtStart",vpn);result.put("durationMillis",elapsed/1_000_000);result.put("throughputRequestsPerSecond",completed.get()*1_000_000_000.0/Math.max(1,elapsed));result.put("p50Millis",percentile(sorted,.50)/1_000_000.0);result.put("p95Millis",percentile(sorted,.95)/1_000_000.0);result.put("processCpuMillis",Process.getElapsedCpuTime()-processCpu);result.put("workerCpuNanos",workerCpu.get());result.put("before",before);result.put("after",metrics());synchronized(lifecycle){result.put("cancelled",cancelled);result.put("cancelReason",cancelReason);result.put("lifecycle",snapshot(lifecycle));result.put("passed",!cancelled&&completed.get()==count&&errors.get()==0);reportWritten=true;}write(result);if(runId!=null)sendBroadcast(new Intent("com.ccs.sprig.fixture.DONE").setPackage("com.ccs.sprig").putExtra("run_id",runId).putExtra("result",result.toString()));runOnUiThread(()->status.setText("Finished "+completed.get()+" / "+count+"; errors="+errors.get()+". Report: files/fixture-result.json"));}catch(Exception error){runOnUiThread(()->status.setText("Report failed: "+error));}finally{workers.shutdown();if(deadline!=null)deadline.shutdownNow();}}}});
    }
    private void request(String url,String expected,int index)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new java.net.URL(url).openConnection();connections.add(connection);connection.setConnectTimeout(10000);connection.setReadTimeout(10000);connection.setInstanceFollowRedirects(false);connection.setRequestProperty("Cache-Control","no-store");connection.setRequestProperty("Connection","close");byte[] body=null;
        try{if((index&1)==1){body=new byte[16384];for(int i=0;i<body.length;i++)body[i]=(byte)(i*17);connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setFixedLengthStreamingMode(body.length);try(java.io.OutputStream out=connection.getOutputStream()){out.write(body);}}
            if(connection.getResponseCode()!=200)throw new java.io.IOException("Fixture did not return 200");byte[] response;try(InputStream input=connection.getInputStream()){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1){if(bytes.size()+n>32768)throw new java.io.IOException("Fixture body exceeds cap");bytes.write(buffer,0,n);}response=bytes.toByteArray();}
            if(body==null?!expected.equals(new String(response,StandardCharsets.UTF_8)):!Arrays.equals(body,response))throw new java.io.IOException("Fixture body mismatch");
        }finally{connections.remove(connection);connection.disconnect();}
    }
    private boolean hasVpn(){ConnectivityManager manager=getSystemService(ConnectivityManager.class);NetworkCapabilities capabilities=manager.getNetworkCapabilities(manager.getActiveNetwork());return capabilities!=null&&capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);}
    private static boolean privateIpv4(String host){if(host==null||!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))return false;String[] p=host.split("\\.");int[] v=new int[4];for(int i=0;i<4;i++){v[i]=Integer.parseInt(p[i]);if(v[i]>255||p[i].length()>1&&p[i].charAt(0)=='0')return false;}return v[0]==10||v[0]==127||v[0]==192&&v[1]==168||v[0]==172&&v[1]>=16&&v[1]<=31;}
    private static long percentile(long[] sorted,double quantile){return sorted.length==0?0:sorted[Math.max(0,(int)Math.ceil(sorted.length*quantile)-1)];}
    private JSONObject metrics()throws Exception{JSONObject out=new JSONObject();Runtime runtime=Runtime.getRuntime();out.put("heapUsedBytes",runtime.totalMemory()-runtime.freeMemory());Debug.MemoryInfo memory=new Debug.MemoryInfo();Debug.getMemoryInfo(memory);out.put("pssKiB",memory.getTotalPss());out.put("rssBytes",rssBytes());out.put("threads",count("/proc/self/task"));out.put("fds",count("/proc/self/fd"));out.put("gc",new JSONObject(Debug.getRuntimeStats()));return out;}
    private static int count(String path){String[] entries=new File(path).list();return entries==null?-1:entries.length;}
    private void write(JSONObject result)throws Exception{File temp=new File(getFilesDir(),"fixture-result.tmp"),target=new File(getFilesDir(),"fixture-result.json");try(FileOutputStream output=new FileOutputStream(temp)){output.write(result.toString(2).getBytes(StandardCharsets.UTF_8));output.getFD().sync();}if(!temp.renameTo(target))throw new java.io.IOException("Cannot commit fixture report");}
    private void trace(String event,boolean savedState){try{JSONObject item=new JSONObject();item.put("event",event);item.put("timeMillis",System.currentTimeMillis());item.put("activityId",System.identityHashCode(this));item.put("savedState",savedState);item.put("changingConfigurations",isChangingConfigurations());item.put("finishing",isFinishing());item.put("destroyed",isDestroyed());synchronized(lifecycle){if(lifecycle.size()==16)lifecycle.removeFirst();lifecycle.addLast(item);}android.util.Log.i("SprigFixtureLifecycle",item.toString());}catch(Exception ignored){}}
    private void recordError(int index,Exception error){try{JSONObject item=new JSONObject();item.put("index",index);item.put("type",error.getClass().getName());String message=error.getMessage();item.put("message",message==null?"":message.substring(0,Math.min(300,message.length())));synchronized(requestErrors){if(requestErrors.size()<8)requestErrors.addLast(item);}}catch(Exception ignored){}}
    private static org.json.JSONArray snapshot(java.util.ArrayDeque<JSONObject> values){synchronized(values){return new org.json.JSONArray(new java.util.ArrayList<>(values));}}
    private void cancel(String reason){synchronized(lifecycle){if(reportWritten)return;cancelReason=reason;cancelled=true;trace("cancel",false);}if(workers!=null)workers.shutdownNow();if(deadline!=null&&!deadline.isShutdown())try{deadline.execute(()->{for(HttpURLConnection connection:connections)connection.disconnect();});}catch(java.util.concurrent.RejectedExecutionException ignored){/* Own socket timeouts still bound active calls. */}}
    @Override protected void onStart(){super.onStart();trace("onStart",false);}
    @Override protected void onResume(){super.onResume();trace("onResume",false);}
    @Override protected void onPause(){trace("onPause",false);super.onPause();}
    @Override protected void onStop(){trace("onStop",false);super.onStop();}
    @Override public void onConfigurationChanged(android.content.res.Configuration config){super.onConfigurationChanged(config);trace("onConfigurationChanged",false);}
    @Override public void onDestroy(){trace("onDestroy",false);if(!reportWritten)cancel("Activity destroyed: changingConfigurations="+isChangingConfigurations()+", finishing="+isFinishing());super.onDestroy();}
    private static Object rssBytes(){
        try(java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.FileReader("/proc/self/status"))){
            for(int i=0;i<128;i++){String line=reader.readLine();if(line==null)break;if(line.startsWith("VmRSS:")){String[] fields=line.trim().split("\\s+");if(fields.length>=2)return Math.multiplyExact(Long.parseLong(fields[1]),1024L);}}
        }catch(java.io.IOException|RuntimeException unavailable){}return JSONObject.NULL;
    }

}
