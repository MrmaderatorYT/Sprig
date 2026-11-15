package com.ccs.sprig.validation;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import com.ccs.sprig.SprigApp;
import com.ccs.sprig.core.InterceptController;
import com.ccs.sprig.core.http2.Alpn;
import com.ccs.sprig.core.http2.Http2Connection;
import com.ccs.sprig.core.http3.Http3Client;
import com.ccs.sprig.core.http3.Http3Server;
import com.ccs.sprig.core.model.Exchange;
import com.ccs.sprig.core.model.SessionStore;
import com.ccs.sprig.core.proxy.CertificateAuthority;
import com.ccs.sprig.core.proxy.HttpCodec;
import com.ccs.sprig.core.proxy.ProxyServer;
import com.ccs.sprig.core.proxy.SocketProtector;
import com.ccs.sprig.core.websocket.WebSocketFrames;
import com.ccs.sprig.core.websocket.WebSocketHandshake;
import com.ccs.sprig.core.websocket.WebSocketHub;
import com.ccs.sprig.ui.MainActivity;
import com.ccs.sprig.ui.ScannerActivity;
import com.ccs.sprig.ui.CollaboratorActivity;
import com.ccs.sprig.ui.IntruderActivity;
import com.ccs.sprig.ui.WebSocketActivity;
import com.ccs.sprig.vpn.CaptureVpnService;
import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.json.JSONArray;
import org.json.JSONObject;

/** Framework-only, Java device validation. No cloud endpoints or consent bypass. */
public final class Stage04Instrumentation extends Instrumentation {
    private Bundle arguments;
    private JSONObject report;
    private JSONArray checks;
    private Context context;
    private int failures;
    private final java.util.concurrent.atomic.AtomicBoolean finished=new java.util.concurrent.atomic.AtomicBoolean();
    private java.util.concurrent.ScheduledExecutorService watchdog;
    @Override public void onCreate(Bundle args){super.onCreate(args);arguments=args==null?new Bundle():args;start();}
    @Override public void onStart(){
        context=getTargetContext();String requested=arguments.getString("mode","runtime");String mode=requested.matches("runtime|prepare-vpn|vpn|cleanup")?requested:"invalid";
        watchdog=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task->{Thread thread=new Thread(task,"sprig-device-watchdog");thread.setDaemon(true);return thread;});
        watchdog.schedule(()->timeout(mode),mode.equals("runtime")?120:360,TimeUnit.SECONDS);
        try{report=new JSONObject();checks=new JSONArray();report.put("mode",mode);report.put("androidApi",Build.VERSION.SDK_INT);report.put("device",Build.MANUFACTURER+" "+Build.MODEL);report.put("package",context.getPackageName());report.put("debuggable",(context.getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0);report.put("r8ReleaseRuntimeValidated",false);report.put("startedMillis",System.currentTimeMillis());report.put("before",metrics());
            if(mode.equals("runtime")){require(!CaptureVpnService.isRunning(),"runtime suite requires capture stopped");check("ui_launch_recreate",this::ui);check("native_http1_replay",this::http1);check("native_tls_http2_replay",this::http2);check("native_quic_http3_and_hostname",this::http3);check("native_websocket_echo",this::websocket);}
            else if(mode.equals("cleanup"))check("recover_fixture_settings",()->recoverSettings(true));
            else if(mode.equals("prepare-vpn")||mode.equals("vpn"))check("real_vpn_selected_fixture_capture_lifecycle",()->vpn(mode));
            else throw new IllegalArgumentException("Use mode runtime, prepare-vpn, vpn or cleanup");
            int idleSeconds=Integer.parseInt(arguments.getString("idleSeconds","0"));require(idleSeconds>=0&&idleSeconds<=45,"idleSeconds must be 0–45");JSONObject immediate=metrics();report.put("afterImmediate",immediate);if(idleSeconds>0){status("idle_cleanup","Waiting "+idleSeconds+" seconds without forced GC");SystemClock.sleep(idleSeconds*1000L);report.put("afterIdle",metrics());}report.put("idleSeconds",idleSeconds);report.put("after",metrics());report.put("finishedMillis",System.currentTimeMillis());report.put("checks",checks);report.put("failures",failures);report.put("passed",failures==0);complete(mode,failures==0?Activity.RESULT_OK:Activity.RESULT_CANCELED,report);
        }catch(Throwable error){JSONObject failure=new JSONObject();try{failure.put("fatal",error.toString());failure.put("passed",false);failure.put("checks",checks);}catch(Exception ignored){}complete(mode,Activity.RESULT_CANCELED,failure);}
    }
    private void complete(String mode,int code,JSONObject result){
        if(!finished.compareAndSet(false,true))return;Bundle output=new Bundle();
        try{write(mode,result);output.putString("report","files/stage04-"+mode+".json");output.putString("summary",result.toString());}catch(Exception error){code=Activity.RESULT_CANCELED;output.putString("failure","Cannot write report: "+error);}
        finally{if(watchdog!=null)watchdog.shutdownNow();super.finish(code,output);}
    }
    private void timeout(String mode){
        if(!finished.compareAndSet(false,true))return;JSONObject failure=new JSONObject();Bundle output=new Bundle();
        try{failure.put("mode",mode);failure.put("passed",false);failure.put("fatal","Absolute instrumentation watchdog expired");failure.put("checks",checks);try{recoverSettings(false);failure.put("fixtureCleanupCompleted",true);}catch(Exception cleanup){failure.put("cleanupError",cleanup.toString());}write(mode,failure);output.putString("report","files/stage04-"+mode+".json");}catch(Exception error){output.putString("failure",error.toString());}
        finally{if(watchdog!=null)watchdog.shutdownNow();super.finish(Activity.RESULT_CANCELED,output);}
    }
    /** Never block forever in startActivitySync when an existing task consumes the intent. */
    private Activity launch(Class<?> type)throws Exception{
        Application app=(Application)context.getApplicationContext();AtomicReference<Activity> resumed=new AtomicReference<>();Application.ActivityLifecycleCallbacks observer=new Application.ActivityLifecycleCallbacks(){public void onActivityResumed(Activity activity){if(activity.getClass()==type)resumed.set(activity);}public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}};
        runOnMainSync(()->app.registerActivityLifecycleCallbacks(observer));try{
            // An isolated validation task guarantees a resume callback even if the user's task is already resumed.
            context.startActivity(new Intent(context,type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
            long until=SystemClock.elapsedRealtime()+10000;while(resumed.get()==null&&SystemClock.elapsedRealtime()<until&&!finished.get())SystemClock.sleep(25);Activity result=resumed.get();require(result!=null,"Activity did not resume within 10s: "+type.getSimpleName());return result;
        }finally{runOnMainSync(()->app.unregisterActivityLifecycleCallbacks(observer));}
    }
    private void bringMainToFront(Activity known)throws Exception{
        Application app=(Application)context.getApplicationContext();AtomicReference<Activity> resumed=new AtomicReference<>();Application.ActivityLifecycleCallbacks observer=new Application.ActivityLifecycleCallbacks(){public void onActivityResumed(Activity activity){if(activity instanceof MainActivity)resumed.set(activity);}public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}};
        runOnMainSync(()->{app.registerActivityLifecycleCallbacks(observer);if(known!=null&&!known.isFinishing()&&known.getWindow().getDecorView().hasWindowFocus())resumed.set(known);});
        try{context.startActivity(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));long until=SystemClock.elapsedRealtime()+10000;while(resumed.get()==null&&SystemClock.elapsedRealtime()<until&&!finished.get())SystemClock.sleep(25);require(resumed.get()!=null,"MainActivity did not return to foreground within 10s");waitForIdleSync();}
        finally{runOnMainSync(()->app.unregisterActivityLifecycleCallbacks(observer));}
    }
    private File backupFile(){return new File(context.getFilesDir(),"stage04-vpn-settings-backup.json");}
    private void persistSettings()throws Exception{
        android.content.SharedPreferences preferences=SprigApp.get().getPreferences();JSONObject saved=new JSONObject();saved.put("hadApps",preferences.contains("selected_apps"));saved.put("hadScope",preferences.contains("scope"));saved.put("hadMitm",preferences.contains("mitm"));saved.put("apps",new JSONArray(preferences.getStringSet("selected_apps",Collections.emptySet())));saved.put("scope",preferences.getString("scope",""));saved.put("mitm",preferences.getBoolean("mitm",true));byte[] bytes=saved.toString().getBytes(StandardCharsets.UTF_8);require(bytes.length<=2*1024*1024,"Fixture settings snapshot exceeds cap");try(FileOutputStream out=new FileOutputStream(backupFile())){out.write(bytes);out.getFD().sync();}
    }
    /** Restores the durable snapshot; legacy cleanup touches only the exact old fixture selection/scope. */
    private void recoverSettings(boolean allowLegacy)throws Exception{
        SprigApp app=SprigApp.get();android.content.SharedPreferences preferences=app.getPreferences();boolean fixture=preferences.getStringSet("selected_apps",Collections.emptySet()).equals(Collections.singleton("com.ccs.sprig.fixture"))&&preferences.getString("scope","").equals("192.168.0.113");File backup=backupFile();
        if(!backup.exists()&&!(allowLegacy&&fixture))return;require(fixture,"Refusing recovery after non-fixture settings changed");stopCapture();awaitRunning(false,15000);android.content.SharedPreferences.Editor restore=preferences.edit();
        if(backup.exists()){
            require(backup.length()<=2*1024*1024,"Fixture backup exceeds cap");byte[] bytes=new byte[(int)backup.length()];try(java.io.DataInputStream input=new java.io.DataInputStream(new java.io.FileInputStream(backup))){input.readFully(bytes);}JSONObject saved=new JSONObject(new String(bytes,StandardCharsets.UTF_8));java.util.Set<String> apps=new HashSet<>();JSONArray values=saved.getJSONArray("apps");for(int i=0;i<values.length();i++)apps.add(values.getString(i));if(saved.getBoolean("hadApps"))restore.putStringSet("selected_apps",apps);else restore.remove("selected_apps");if(saved.getBoolean("hadScope"))restore.putString("scope",saved.getString("scope"));else restore.remove("scope");if(saved.getBoolean("hadMitm"))restore.putBoolean("mitm",saved.getBoolean("mitm"));else restore.remove("mitm");
        }else{restore.remove("selected_apps");restore.remove("scope");}
        require(restore.commit(),"Cannot restore fixture settings");app.applySettings();require(!backup.exists()||backup.delete(),"Cannot delete restored fixture backup");
    }
    private interface Check{void run()throws Exception;}
    private void check(String name,Check action)throws Exception{JSONObject result=new JSONObject();result.put("name",name);long started=SystemClock.elapsedRealtime();try{action.run();result.put("passed",true);}catch(Throwable error){failures++;result.put("passed",false);result.put("error",error.toString());StackTraceElement[] trace=error.getStackTrace();JSONArray frames=new JSONArray();for(int i=0;i<Math.min(8,trace.length);i++)frames.put(trace[i].toString());result.put("stack",frames);}result.put("durationMillis",SystemClock.elapsedRealtime()-started);checks.put(result);status(name,result.toString());}
    private void ui()throws Exception{
        Application app=(Application)context.getApplicationContext();AtomicReference<Activity> resumed=new AtomicReference<>();Application.ActivityLifecycleCallbacks observer=new Application.ActivityLifecycleCallbacks(){public void onActivityResumed(Activity activity){resumed.set(activity);}public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}};
        runOnMainSync(()->app.registerActivityLifecycleCallbacks(observer));JSONArray activities=new JSONArray();
        try{for(Class<?> type:new Class<?>[]{MainActivity.class,ScannerActivity.class,CollaboratorActivity.class,IntruderActivity.class,WebSocketActivity.class}){
            Activity activity=launch(type);waitForIdleSync();runOnMainSync(activity::recreate);long deadline=SystemClock.elapsedRealtime()+10000;Activity replacement;
            while(((replacement=resumed.get())==activity||replacement==null||replacement.getClass()!=type)&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(25);
            require(replacement!=null&&replacement!=activity&&replacement.getClass()==type,"Activity recreation did not resume: "+type.getSimpleName());Activity finish=replacement;runOnMainSync(finish::finish);waitForIdleSync();activities.put(type.getSimpleName());}
        }finally{runOnMainSync(()->app.unregisterActivityLifecycleCallbacks(observer));Activity last=resumed.get();if(last!=null&&!last.isFinishing())runOnMainSync(last::finish);}
        report.put("uiActivitiesRecreated",activities);
    }
    private void http1()throws Exception{
        try(LocalOrigin origin=new LocalOrigin(false,false,null)){SessionStore store=new SessionStore();ProxyServer proxy=proxy(store,null,"runtime-h1");try{Exchange response=proxy.replay("http://127.0.0.1:"+origin.port()+"/fixture",requestBytes(),"HTTP/1.1");require(response.status==200&&Arrays.equals(payload(),response.responseBody),"HTTP1 binary body/status mismatch: "+response.error);require(store.size()==1,"HTTP1 history missing");}finally{proxy.stop();store.clear();}}
    }
    private void http2()throws Exception{
        CertificateAuthority ca=new CertificateAuthority(new File(context.getNoBackupFilesDir(),"validation-h2-ca"));SSLContext trusted=trust(ca);
        try(LocalOrigin origin=new LocalOrigin(true,true,ca)){SessionStore store=new SessionStore();ProxyServer proxy=proxy(store,trusted,"runtime-h2");try{Exchange result=proxy.replay("https://localhost:"+origin.port()+"/fixture",requestBytes(),"HTTP/2");require(result.status==200&&"HTTP/2".equals(result.upstreamProtocol)&&Arrays.equals(payload(),result.responseBody),"Native TLS/H2 failed: "+result.error);report.put("http2Path","Android native SSLSocket ALPN + local custom-trust TLS origin; not VpnService");}finally{proxy.stop();store.clear();}}
    }
    private void http3()throws Exception{
        CertificateAuthority ca=new CertificateAuthority(new File(context.getNoBackupFilesDir(),"validation-h3-ca"));KeyStore trust=trustStore(ca);
        try(Http3Server server=new Http3Server(ca.serverKeyStore("localhost"),incoming->response(incoming.message.body));Http3Client client=new Http3Client(socket->true,trust)){
            HttpCodec.Message request=request();HttpCodec.Message result=client.execute("localhost","127.0.0.1",server.getPort(),request);require(result.status()==200&&Arrays.equals(payload(),result.body),"Native QUIC/H3 binary mismatch");boolean rejected=false;
            try{client.execute("wrong.fixture.test","127.0.0.1",server.getPort(),request);}catch(IOException expected){rejected=true;}require(rejected,"H3 accepted a wrong TLS hostname");report.put("http3Path","Real Android JCA/QUIC/QPACK loopback with fixture trust; not VpnService");
        }
    }
    private void websocket()throws Exception{
        SessionStore store=new SessionStore();ProxyServer proxy=proxy(store,null,"runtime-ws");try(LocalOrigin origin=new LocalOrigin(false,false,null,true)){
            WebSocketHub hub=proxy.getWebSockets();WebSocketHub.Session session=hub.connect("ws://127.0.0.1:"+origin.port()+"/fixture","");byte[] data=payload();hub.sendBinary(session.id,data);long deadline=SystemClock.elapsedRealtime()+10000;boolean echoed=false;
            while(!echoed&&SystemClock.elapsedRealtime()<deadline){for(WebSocketHub.Message message:hub.messages(session.id))if(message.direction.equals(WebSocketHub.SERVER_TO_CLIENT)&&message.opcode==2&&Arrays.equals(data,message.payload))echoed=true;if(!echoed)SystemClock.sleep(20);}require(echoed,"Manual WS binary echo did not arrive");hub.closeSession(session.id);
        }finally{proxy.stop();store.clear();}
    }
    private JSONObject autoFixture(int requests)throws Exception{
        String runId=java.util.UUID.randomUUID().toString().replace("-","");AtomicReference<String> completed=new AtomicReference<>();android.content.BroadcastReceiver receiver=new android.content.BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){if(runId.equals(intent.getStringExtra("run_id"))){String result=intent.getStringExtra("result");if(result!=null&&result.length()<=65536)completed.compareAndSet(null,result);}}};
        android.content.IntentFilter filter=new android.content.IntentFilter("com.ccs.sprig.fixture.DONE");androidx.core.content.ContextCompat.registerReceiver(context,receiver,filter,androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
        try{context.startActivity(new Intent().setClassName("com.ccs.sprig.fixture","com.ccs.sprig.fixture.FixtureActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK).putExtra("url","http://192.168.0.113:8080/sprig-fixture").putExtra("token","sprig040fixture").putExtra("requests",requests).putExtra("workers",2).putExtra("run_id",runId));long until=SystemClock.elapsedRealtime()+120000;
            while(completed.get()==null&&SystemClock.elapsedRealtime()<until&&!finished.get())SystemClock.sleep(25);require(completed.get()!=null,"Fixture app did not signal completion within 120s");JSONObject result=new JSONObject(completed.get());require(result.getBoolean("passed")&&result.getInt("completed")==requests&&result.getInt("errors")==0&&result.getBoolean("vpnTransportAtStart"),"Fixture client reported failure: "+result);return result;
        }finally{context.unregisterReceiver(receiver);}
    }
    private int awaitFixtureHistory(long since,int expected,long timeout)throws Exception{
        long until=SystemClock.elapsedRealtime()+timeout;int matching=0,posts=0;
        do{matching=0;posts=0;for(Exchange exchange:SprigApp.get().getStore().snapshot())if(exchange.timeMillis>=since&&exchange.url.startsWith("http://192.168.0.113:8080/sprig-fixture")&&exchange.url.contains("token=sprig040fixture")){
            require(exchange.status==200,"Captured fixture status was "+exchange.status+": "+exchange.error);
            if(exchange.method.equals("POST")){require(Arrays.equals(payload(),exchange.requestBody)&&Arrays.equals(payload(),exchange.responseBody),"Captured POST binary bytes differ from fixture");posts++;}
            else{require(exchange.method.equals("GET")&&"sprig-fixture-v1:sprig040fixture".equals(new String(exchange.responseBody,StandardCharsets.UTF_8)),"Captured GET body differs from fixture");}matching++;
        }if(matching>=expected)break;SystemClock.sleep(50);}while(SystemClock.elapsedRealtime()<until&&!finished.get());
        require(matching>=expected,"Expected "+expected+" validated captured fixture responses; got "+matching);require(expected<2||posts>0,"No captured POST binary response");return matching;
    }
    private void vpn(String mode)throws Exception{
        SprigApp app=SprigApp.get();require(!CaptureVpnService.isRunning(),"Stop any current capture before the isolated fixture run");require(!app.getInterceptor().isEnabled(),"Disable interception before the fixture run");
        context.getPackageManager().getApplicationInfo("com.ccs.sprig.fixture",0);
        android.content.SharedPreferences preferences=app.getPreferences();boolean hadApps=preferences.contains("selected_apps"),hadScope=preferences.contains("scope"),hadMitm=preferences.contains("mitm");java.util.Set<String> oldApps=new HashSet<>(preferences.getStringSet("selected_apps",Collections.emptySet()));String oldScope=preferences.getString("scope","");boolean oldMitm=preferences.getBoolean("mitm",true);Activity[] shown=new Activity[1];long runSince=System.currentTimeMillis();require(!backupFile().exists(),"An earlier fixture settings backup exists; run mode cleanup first");persistSettings();
        try{
            require(preferences.edit().putStringSet("selected_apps",new HashSet<>(Collections.singletonList("com.ccs.sprig.fixture"))).putString("scope","192.168.0.113").putBoolean("mitm",true).commit(),"Cannot save isolated fixture settings");
            shown[0]=launch(MainActivity.class);waitForIdleSync();Intent consent=VpnService.prepare(context);
            if(consent!=null){require(mode.equals("prepare-vpn"),"VPN consent is required: run prepare-vpn and accept the system dialog");status("consent_pending","Accept the standard Android VPN consent for Sprig. Only the fixture app is selected.");runOnMainSync(()->shown[0].startActivityForResult(consent,701));}
            else startCapture();
            awaitRunning(true,120000);boolean automatic=Boolean.parseBoolean(arguments.getString("autoFixture","false"));status("capture_running",automatic?"Starting isolated fixture app automatically":"Launch com.ccs.sprig.fixture now against http://192.168.0.113:8080/sprig-fixture with token sprig040fixture");int expected=Integer.parseInt(arguments.getString("expectedRequests","20"));require(expected>=1&&expected<=500,"expectedRequests outside 1–500 (bounded retained-history validation)");report.put("autoFixture",automatic);
            if(automatic)report.put("firstFixtureClient",autoFixture(expected));int matching=awaitFixtureHistory(runSince,expected,automatic?15000:180000);
            require(CaptureVpnService.bytesUp()>0&&CaptureVpnService.bytesDown()>0,"VPN byte counters did not advance");report.put("capturedFixtureResponses",matching);report.put("vpnBytesUp",CaptureVpnService.bytesUp());report.put("vpnBytesDown",CaptureVpnService.bytesDown());report.put("vpnScope","Only com.ccs.sprig.fixture; LAN HTTP/IPv4 fixture; full GET/body and POST/binary validation; no HTTPS/H2/H3 VPN claim");
            stopCapture();awaitRunning(false,15000);bringMainToFront(shown[0]);startCapture();awaitRunning(true,15000);
            if(automatic){long restartedSince=System.currentTimeMillis();report.put("restartFixtureClient",autoFixture(4));report.put("restartCapturedResponses",awaitFixtureHistory(restartedSince,4,15000));}
            stopCapture();awaitRunning(false,15000);report.put("stopRestartStopCompleted",true);
        }finally{
            try{stopCapture();awaitRunning(false,15000);}finally{android.content.SharedPreferences.Editor restore=preferences.edit();if(hadApps)restore.putStringSet("selected_apps",oldApps);else restore.remove("selected_apps");if(hadScope)restore.putString("scope",oldScope);else restore.remove("scope");if(hadMitm)restore.putBoolean("mitm",oldMitm);else restore.remove("mitm");require(restore.commit(),"Cannot restore original capture settings");app.applySettings();require(!backupFile().exists()||backupFile().delete(),"Cannot remove restored fixture settings backup");if(shown[0]!=null)runOnMainSync(()->{if(!shown[0].isFinishing())shown[0].finish();});}
        }
    }
    private void startCapture(){Intent service=new Intent(context,CaptureVpnService.class).setAction(CaptureVpnService.ACTION_START);if(Build.VERSION.SDK_INT>=26)context.startForegroundService(service);else context.startService(service);}
    private void stopCapture(){context.startService(new Intent(context,CaptureVpnService.class).setAction(CaptureVpnService.ACTION_STOP));}
    private void awaitRunning(boolean expected,long timeout)throws Exception{long end=SystemClock.elapsedRealtime()+timeout;while((CaptureVpnService.isRunning()!=expected||!expected&&SprigApp.get().getProxy().getPort()!=-1)&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(50);require(CaptureVpnService.isRunning()==expected&&(expected||SprigApp.get().getProxy().getPort()==-1),"VPN state did not become "+expected+": "+CaptureVpnService.lastError());}
    private ProxyServer proxy(SessionStore store,SSLContext trust,String name)throws Exception{SocketProtector protector=socket->true;File directory=new File(context.getNoBackupFilesDir(),name);if(trust==null)return new ProxyServer(store,new InterceptController(),directory,protector);java.lang.reflect.Constructor<ProxyServer> constructor=ProxyServer.class.getDeclaredConstructor(SessionStore.class,InterceptController.class,File.class,SocketProtector.class,javax.net.ssl.SSLSocketFactory.class);constructor.setAccessible(true);return constructor.newInstance(store,new InterceptController(),directory,protector,trust.getSocketFactory());}
    private static byte[] payload(){byte[] bytes=new byte[16384];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i*17);return bytes;}
    private static HttpCodec.Message request(){HttpCodec.Message request=new HttpCodec.Message();request.startLine="POST /fixture HTTP/1.1";request.body=payload();request.set("Host","localhost");request.set("Content-Length",Integer.toString(request.body.length));return request;}
    private static byte[] requestBytes(){return request().raw();}
    private static HttpCodec.Message response(byte[] body){HttpCodec.Message response=new HttpCodec.Message();response.startLine="HTTP/1.1 200 OK";response.body=body;response.set("Content-Length",Integer.toString(body.length));return response;}
    private static KeyStore trustStore(CertificateAuthority ca)throws Exception{KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null);store.setCertificateEntry("fixture",CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(ca.certificateDer())));return store;}
    private static SSLContext trust(CertificateAuthority ca)throws Exception{TrustManagerFactory managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());managers.init(trustStore(ca));SSLContext context=SSLContext.getInstance("TLS");context.init(null,managers.getTrustManagers(),null);return context;}
    private static void require(boolean condition,String message)throws IOException{if(!condition)throw new IOException(message);}
    private void status(String stage,String detail){Bundle data=new Bundle();data.putString("stage",stage);data.putString("detail",detail);sendStatus(1,data);}
    private JSONObject metrics()throws Exception{JSONObject result=new JSONObject();Runtime runtime=Runtime.getRuntime();result.put("heapUsedBytes",runtime.totalMemory()-runtime.freeMemory());result.put("heapMaxBytes",runtime.maxMemory());result.put("processCpuMillis",Process.getElapsedCpuTime());Debug.MemoryInfo memory=new Debug.MemoryInfo();Debug.getMemoryInfo(memory);result.put("pssKiB",memory.getTotalPss());result.put("rssBytes",rssBytes());result.put("threads",count("/proc/self/task"));result.put("fds",count("/proc/self/fd"));result.put("gc",new JSONObject(Debug.getRuntimeStats()));return result;}
    private static int count(String path){String[] entries=new File(path).list();return entries==null?-1:entries.length;}
    private void write(String mode,JSONObject result)throws Exception{File temporary=new File(context.getFilesDir(),"stage04-"+mode+".tmp"),target=new File(context.getFilesDir(),"stage04-"+mode+".json");try(FileOutputStream out=new FileOutputStream(temporary)){out.write(result.toString(2).getBytes(StandardCharsets.UTF_8));out.getFD().sync();}if(!temporary.renameTo(target))throw new IOException("Cannot commit instrumentation report");}

    private static final class LocalOrigin implements Closeable{
        final ServerSocket server;final Thread acceptor;final ThreadPoolExecutor work=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(4));final boolean h2,ws;
        volatile Socket active;
        LocalOrigin(boolean tls,boolean h2,CertificateAuthority ca)throws Exception{this(tls,h2,ca,false);}
        LocalOrigin(boolean tls,boolean h2,CertificateAuthority ca,boolean ws)throws Exception{this.h2=h2;this.ws=ws;server=tls?ca.serverContext("localhost").getServerSocketFactory().createServerSocket(0,8,InetAddress.getByName("127.0.0.1")):new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));server.setSoTimeout(20000);acceptor=new Thread(()->{while(!server.isClosed())try{Socket socket=server.accept();active=socket;socket.setSoTimeout(10000);work.execute(()->serve(socket));}catch(Exception done){break;}},"sprig-device-origin");acceptor.start();}
        int port(){return server.getLocalPort();}
        void serve(Socket socket){try(Socket closing=socket){if(socket instanceof SSLSocket){SSLSocket tls=(SSLSocket)socket;SSLParameters parameters=tls.getSSLParameters();Alpn.configure(parameters,h2?"h2":"http/1.1");tls.setSSLParameters(parameters);tls.startHandshake();}BufferedInputStream input=new BufferedInputStream(socket.getInputStream());if(h2)Http2Connection.serve(input,socket.getOutputStream(),socket,work,(message,authority,scheme)->response(message.body));else{HttpCodec.Message request=HttpCodec.readRequest(input,null);if(request==null)return;if(ws){WebSocketHandshake.validateRequest(request);HttpCodec.Message upgrade=new HttpCodec.Message();upgrade.startLine="HTTP/1.1 101 Switching Protocols";upgrade.set("Upgrade","websocket");upgrade.set("Connection","Upgrade");upgrade.set("Sec-WebSocket-Accept",WebSocketHandshake.accept(request.header("Sec-WebSocket-Key")));upgrade.writeTo(socket.getOutputStream());WebSocketFrames.Frame frame=WebSocketFrames.read(input,true);if(frame!=null){WebSocketFrames.write(socket.getOutputStream(),false,true,frame.opcode,frame.payload,new SecureRandom());WebSocketFrames.Frame close=WebSocketFrames.read(input,true);if(close!=null&&close.opcode==8)WebSocketFrames.write(socket.getOutputStream(),false,true,8,close.payload,new SecureRandom());}}else response(request.body).writeTo(socket.getOutputStream());}}catch(Exception ignored){}}
        public void close()throws IOException{server.close();Socket socket=active;if(socket!=null)socket.close();work.shutdownNow();try{acceptor.join(2000);work.awaitTermination(2,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}}
    }
    private static Object rssBytes(){
        try(java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.FileReader("/proc/self/status"))){
            for(int i=0;i<128;i++){String line=reader.readLine();if(line==null)break;if(line.startsWith("VmRSS:")){String[] fields=line.trim().split("\\s+");if(fields.length>=2)return Math.multiplyExact(Long.parseLong(fields[1]),1024L);}}
        }catch(java.io.IOException|RuntimeException unavailable){}return JSONObject.NULL;
    }

}
