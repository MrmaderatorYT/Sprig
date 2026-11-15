package com.ccs.sprig.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import com.ccs.sprig.core.net.LatestTaskExecutor;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.ccs.sprig.SprigApp;
import com.ccs.sprig.R;
import com.ccs.sprig.core.proxy.ProxyServer;
import com.ccs.sprig.core.tunnel.TunEngine;
import com.ccs.sprig.core.http3.Http3Gateway;
import com.ccs.sprig.ui.MainActivity;

/** User-started local VPN. There is no remote VPN endpoint and no traffic upload. */
public final class CaptureVpnService extends VpnService {
    public static final String ACTION_START="com.ccs.sprig.vpn.START";
    public static final String ACTION_STOP="com.ccs.sprig.vpn.STOP";
    public static final String ACTION_STATE="com.ccs.sprig.vpn.STATE";
    private static final String CHANNEL="capture";
    private static final int NOTIFICATION_ID=11;
    private static final Object CAPTURE_LOCK=new Object();
    private static volatile CaptureVpnService current;
    private static volatile boolean active;
    private static volatile String error="";
    private static volatile long previousUp,previousDown;
    private final LatestTaskExecutor lifecycle=new LatestTaskExecutor("sprig-vpn-lifecycle");
    private final AtomicLong generation=new AtomicLong();
    // Command generations change on user requests; engine epochs change only when ownership changes.
    private final Object commandLock=new Object();
    private final AtomicLong engineEpoch=new AtomicLong();
    private final AtomicReference<EngineFailure> fatalFailure=new AtomicReference<>();
    private volatile boolean desiredStart;
    private volatile int latestStartId;
    private volatile long handledGeneration;
    private volatile CaptureHandle engine;
    interface CaptureHandle {void start();void close();long bytesUp();long bytesDown();}
    interface CaptureFactory {CaptureHandle create(long command,TunEngine.Listener listener)throws Exception;}
    private CaptureFactory captureFactory=this::createCapture;
    void setCaptureFactory(CaptureFactory factory){if(factory==null)throw new NullPointerException("factory");captureFactory=factory;}
    private static final class EngineFailure {final long epoch;final Exception error;EngineFailure(long epoch,Exception error){this.epoch=epoch;this.error=error;}}
    private ParcelFileDescriptor descriptor;

    public static boolean isRunning() { return active; }
    public static String lastError() { return error; }
    public static long bytesUp() { CaptureVpnService service=current; CaptureHandle e=service==null ? null:service.engine; return e==null ? previousUp:e.bytesUp(); }
    public static long bytesDown() { CaptureVpnService service=current; CaptureHandle e=service==null ? null:service.engine; return e==null ? previousDown:e.bytesDown(); }
    public static boolean protectSocket(Socket socket) {
        CaptureVpnService service=current;
        return service==null || service.protect(socket);
    }
    public static boolean protectSocket(DatagramSocket socket) {
        CaptureVpnService service=current;
        return service==null || service.protect(socket);
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel=new NotificationChannel(CHANNEL,"Traffic capture",NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Local VPN capture status and stop control");
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        synchronized(commandLock){
            latestStartId=startId;
            desiredStart=intent!=null&&ACTION_START.equals(intent.getAction());
            if(desiredStart){
                Notification notice=notification("Starting local capture…");
                if(Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION_ID,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                else startForeground(NOTIFICATION_ID,notice);
            }
            long token=generation.incrementAndGet();
            lifecycle.executeLatest(()->reconcile(token,startId));
        }
        return START_NOT_STICKY;
    }
    private void reconcile(long token,int startId){
        synchronized(CAPTURE_LOCK){
            if(token!=generation.get())return;
            handledGeneration=token;
            EngineFailure pending=fatalFailure.get();
            if(!desiredStart){
                if(pending!=null&&pending.epoch==engineEpoch.get()&&current==this)error=message(pending.error);
                shutdownCaptureLocked(true,token);stopLatest(token,startId);return;
            }
            if(active&&current==this&&pending==null){updateNotification("Capturing IPv4 traffic locally · IPv6 blocked");return;}
            try{
                shutdownCaptureLocked(false,token);
                if(token!=generation.get()||!desiredStart)return;
                if(current!=null&&current!=this){CaptureVpnService old=current;old.generation.incrementAndGet();old.shutdownCapture(false);}
                current=this;error="";previousUp=0;previousDown=0;
                long epoch=engineEpoch.incrementAndGet();
                CaptureHandle created=captureFactory.create(token,failure->onEngineFailure(epoch,failure));engine=created;
                if(token!=generation.get()||!desiredStart){finishSupersededStartup(token,startId,epoch);return;}
                created.start();
                if(token!=generation.get()||!desiredStart){finishSupersededStartup(token,startId,epoch);return;}
                EngineFailure immediate=fatalFailure.get();
                if(immediate!=null&&immediate.epoch==epoch){error=message(immediate.error);shutdownCaptureLocked(true,token);stopLatest(token,startId);return;}
                active=true;updateNotification("Capturing IPv4 traffic locally · IPv6 blocked");notifyState();
            }catch(Exception failure){
                if(token!=generation.get()){shutdownCaptureLocked(false,token);return;}
                error=message(failure);shutdownCaptureLocked(true,token);stopLatest(token,startId);
            }
        }
    }
    private void finishSupersededStartup(long token,int startId,long epoch){
        EngineFailure pending=fatalFailure.get();
        boolean failed=token==generation.get()&&pending!=null&&pending.epoch==epoch;
        if(failed)error=message(pending.error);
        shutdownCaptureLocked(failed,token);if(failed)stopLatest(token,startId);
    }
    private void onEngineFailure(long epoch,Exception failure){
        synchronized(commandLock){
            if(current!=this||engineEpoch.get()!=epoch||!desiredStart)return;
            if(!fatalFailure.compareAndSet(null,new EngineFailure(epoch,failure)))return;
            long token=generation.get();
            // An already handled START is running this failed engine. A newer pending START may restart it.
            if(handledGeneration==token)desiredStart=false;
            int startId=latestStartId;
            lifecycle.executeLatest(()->reconcile(token,startId));
        }
    }
    private void stopLatest(long token,int startId){synchronized(commandLock){if(token==generation.get()&&startId==latestStartId)stopSelf(startId);}}
    private static String message(Exception failure){return failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();}
    private void checkStarting(long command)throws java.io.InterruptedIOException{if(command!=generation.get()||!desiredStart)throw new java.io.InterruptedIOException("Capture startup superseded");}
    private CaptureHandle createCapture(long command,TunEngine.Listener fatalListener)throws Exception{
            SprigApp app=SprigApp.get();SharedPreferences preferences=app.getPreferences();ProxyServer proxy=app.getProxy();proxy.start(0);checkStarting(command);
            Intent configure=new Intent(this,MainActivity.class);
            PendingIntent configureIntent=PendingIntent.getActivity(this,0,configure,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            Builder builder=new Builder().setSession("Sprig local inspection")
                    .setMtu(1500).setBlocking(true)
                    .addAddress("10.89.0.2",32).addRoute("0.0.0.0",0)
                    .addAddress("fd89:ca:70::2",128).addRoute("::",0)
                    .setConfigureIntent(configureIntent);
            addUnderlyingDnsServers(builder);
            if(Build.VERSION.SDK_INT>=29) builder.setMetered(false);
            Set<String> selected=new HashSet<>(preferences.getStringSet("selected_apps",Collections.emptySet()));
            selected.remove(getPackageName());
            if(selected.isEmpty()) builder.addDisallowedApplication(getPackageName());
            else {
                int allowed=0;
                for(String packageName:selected) {
                    try { builder.addAllowedApplication(packageName); allowed++; }
                    catch(PackageManager.NameNotFoundException ignored) { }
                }
                if(allowed==0) throw new IllegalStateException("The selected apps are no longer installed. Update the app scope.");
            }
            descriptor=builder.establish();
            if(descriptor==null) throw new IllegalStateException("VPN permission was not granted. Start capture again and accept Android’s VPN request.");
            checkStarting(command);
            FileInputStream input=new FileInputStream(descriptor.getFileDescriptor());
            FileOutputStream output=new FileOutputStream(descriptor.getFileDescriptor());
            TunEngine created=new TunEngine(input,output,new TunEngine.SocketProtector(){
                @Override public boolean protect(Socket socket){return CaptureVpnService.this.protect(socket);}
                @Override public boolean protect(DatagramSocket socket){return CaptureVpnService.this.protect(socket);}
            },proxy.getPort(),preferences.getBoolean("blocked_quic",true),fatalListener);
            CaptureHandle handle=new CaptureHandle(){public void start(){created.start();}public void close(){created.close();}public long bytesUp(){return created.bytesUp();}public long bytesDown(){return created.bytesDown();}};
            // Publish ownership before later configuration can fail, so startup cleanup closes the selector too.
            engine=handle;
            if(preferences.getBoolean("http3",true)) {
                int flowLimit=Runtime.getRuntime().maxMemory()<=256L*1024*1024?2:4;
                created.setDatagramHandler(new Http3Gateway(proxy,CaptureVpnService::protectSocket,flowLimit));
            }
            return handle;
    }
    private void shutdownCapture(){shutdownCapture(true);}
    private void shutdownCapture(boolean removeNotification){synchronized(CAPTURE_LOCK){shutdownCaptureLocked(removeNotification,generation.get());}}
    private void shutdownCaptureLocked(boolean removeNotification,long command){
        boolean owner=current==this;
        engineEpoch.incrementAndGet();fatalFailure.set(null);
        if(owner)active=false;
        CaptureHandle stopping=engine;engine=null;
        if(stopping!=null){
            if(owner){previousUp=stopping.bytesUp();previousDown=stopping.bytesDown();}
            if(descriptor!=null)try{descriptor.close();}catch(Exception ignored){}
            stopping.close();
        }
        if(descriptor!=null){try{descriptor.close();}catch(Exception ignored){}descriptor=null;}
        SprigApp app=SprigApp.get();if(owner&&app!=null){app.getInterceptor().cancelAll();app.getProxy().stop();}
        if(owner)current=null;
        if(removeNotification)synchronized(commandLock){if(command==generation.get()&&(owner||current==null))stopForeground(STOP_FOREGROUND_REMOVE);}
        notifyState();
    }
    private Notification notification(String text) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,CaptureVpnService.class).setAction(ACTION_STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("Sprig capture is active").setContentText(text)
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(R.drawable.ic_shield,"Stop",stop).build())
                .setCategory(Notification.CATEGORY_SERVICE).build();
    }
    private void updateNotification(String text) { getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,notification(text)); }
    private void addUnderlyingDnsServers(Builder builder) {
        ConnectivityManager connectivity=getSystemService(ConnectivityManager.class);
        Set<InetAddress> servers=new LinkedHashSet<>();
        if(connectivity!=null) {
            Network activeNetwork=connectivity.getActiveNetwork();
            NetworkCapabilities activeCapabilities=activeNetwork==null ? null:connectivity.getNetworkCapabilities(activeNetwork);
            if(isUnderlyingInternet(activeCapabilities)) {
                collectIpv4Dns(connectivity,activeNetwork,servers);
            } else {
                // The current default can still be a VPN being replaced; never reuse its resolver.
                // Prefer a validated physical network before trying an unvalidated Internet network.
                Network[] networks=connectivity.getAllNetworks();
                for(int pass=0;pass<2 && servers.isEmpty();pass++) {
                    for(Network network:networks) {
                        NetworkCapabilities capabilities=connectivity.getNetworkCapabilities(network);
                        if(!isUnderlyingInternet(capabilities)) continue;
                        if(pass==0 && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) continue;
                        collectIpv4Dns(connectivity,network,servers);
                        if(!servers.isEmpty()) break;
                    }
                }
            }
        }
        if(servers.isEmpty()) builder.addDnsServer("1.1.1.1").addDnsServer("9.9.9.9");
        else for(InetAddress server:servers) builder.addDnsServer(server);
    }
    private static boolean isUnderlyingInternet(NetworkCapabilities capabilities) {
        return capabilities!=null && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
    private static void collectIpv4Dns(ConnectivityManager connectivity,Network network,Set<InetAddress> servers) {
        LinkProperties properties=connectivity.getLinkProperties(network);
        if(properties!=null) for(InetAddress server:properties.getDnsServers())
            if(server instanceof Inet4Address) servers.add(server);
    }
    private void notifyState() { sendBroadcast(new Intent(ACTION_STATE).setPackage(getPackageName())); }
    @Override public void onRevoke(){
        synchronized(commandLock){desiredStart=false;long token=generation.incrementAndGet();int id=latestStartId;lifecycle.executeLatest(()->reconcile(token,id));}
        // Cleanup/stopSelf is serialized above; VpnService's default synchronous stop must not race a newer START.
    }
    @Override public void onDestroy(){
        synchronized(commandLock){desiredStart=false;generation.incrementAndGet();lifecycle.closeAfter(this::shutdownCapture);}
        super.onDestroy();
    }
}
