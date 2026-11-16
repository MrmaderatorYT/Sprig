package com.ccs.sprig.ui;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.ccs.sprig.R;
import com.ccs.sprig.SprigApp;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import java.net.InetAddress;
import java.security.Permission;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowVpnService;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, qualifiers="en-rGB-w412dp-h915dp-xhdpi", application=SprigApp.class)
public class PrivacyBoundaryTest {
    @Before public void acknowledgedBeta(){SprigApp.get().getPreferences().edit().putBoolean(BetaNoticeDialog.ACKNOWLEDGED,true).commit();}
    @Test public void catalogUsesOnlyLaunchableAppsAndDeduplicatesPackages() {
        PackageManager pm=SprigApp.get().getPackageManager();
        Intent launcher=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Shadows.shadowOf(pm).addResolveInfoForIntent(launcher,Arrays.asList(
                app("fixture.z","Zeta","One"),app("fixture.a","Alpha","One"),
                app("fixture.a","Alpha","Two"),app("com.ccs.sprig","Sprig","Main")));
        List<CaptureAppCatalog.Entry> choices=CaptureAppCatalog.load(pm,"com.ccs.sprig");
        assertEquals(2,choices.size());assertEquals("fixture.a",choices.get(0).packageName);
        assertEquals("Alpha",choices.get(0).name);assertEquals("fixture.z",choices.get(1).packageName);
        assertFalse(choices.stream().anyMatch(e->e.packageName.equals("com.ccs.sprig")));
    }
    @Test public void catalogIgnoresIncompleteResolverEntries() {
        PackageManager pm=SprigApp.get().getPackageManager();
        ResolveInfo broken=new ResolveInfo();
        Shadows.shadowOf(pm).addResolveInfoForIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                Arrays.asList(broken,app("fixture.valid","Valid","Main")));
        assertEquals(1,CaptureAppCatalog.load(pm,"com.ccs.sprig").size());
    }
    @Test public void decliningDisclosureAfterRotationDoesNotRequestVpnOrStartCapture() {
        ShadowVpnService.setPrepareResult(new Intent("fixture.VPN_CONSENT"));
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();findText(activity.getWindow().getDecorView(),activity.getString(R.string.capture_start)).performClick();
            assertNotNull(disclosure(activity));assertNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
            controller.recreate();activity=controller.get();CaptureDisclosureDialog restored=disclosure(activity);assertNotNull(restored);
            ((AlertDialog)restored.requireDialog()).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();idle();
            assertNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
            assertFalse(com.ccs.sprig.vpn.CaptureVpnService.isRunning());
        }
    }
    @Test public void agreeingRequestsStandardVpnPermissionRatherThanGrantingIt() {
        ShadowVpnService.setPrepareResult(new Intent("fixture.VPN_CONSENT"));
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();findText(activity.getWindow().getDecorView(),activity.getString(R.string.capture_start)).performClick();
            ((AlertDialog)disclosure(activity).requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();
            assertEquals("fixture.VPN_CONSENT",Shadows.shadowOf(activity).getNextStartedActivityForResult().intent.getAction());
            assertFalse(com.ccs.sprig.vpn.CaptureVpnService.isRunning());
        }
    }
    @Test @SuppressWarnings("removal")
    public void openingWorkspaceDoesNotInitializeDownloadableEmojiOrConnectSockets() throws Exception {
        android.content.pm.ProviderInfo startup=SprigApp.get().getPackageManager().getProviderInfo(new android.content.ComponentName(SprigApp.get(),"androidx.startup.InitializationProvider"),PackageManager.GET_META_DATA);
        assertNotNull(startup.metaData); assertFalse(startup.metaData.containsKey("androidx.emoji2.text.EmojiCompatInitializer"));
        assertFalse(emojiConfigured());
        AtomicInteger attempts=new AtomicInteger();SecurityManager previous=System.getSecurityManager();
        SecurityManager guard=new SecurityManager(){
            @Override public void checkPermission(Permission permission){}
            @Override public void checkConnect(String host,int port){attempts.incrementAndGet();throw new AssertionError("Unexpected network connect during navigation");}
            @Override public void checkConnect(String host,int port,Object context){checkConnect(host,port);}
            @Override public void checkMulticast(InetAddress address){checkConnect("multicast",0);}
        };
        System.setSecurityManager(guard);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();BottomNavigationView nav=find(activity.getWindow().getDecorView(),BottomNavigationView.class);
            for(int i=0;i<nav.getMenu().size();i++){nav.setSelectedItemId(nav.getMenu().getItem(i).getItemId());idle();}
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            assertEquals(0,attempts.get());assertFalse(emojiConfigured());
        } finally {System.setSecurityManager(previous);}
    }
    private static boolean emojiConfigured() throws Exception {return (Boolean)Class.forName("androidx.emoji2.text.EmojiCompat").getMethod("isConfigured").invoke(null);}
    private static ResolveInfo app(String pkg,String label,String activity){ResolveInfo r=new ResolveInfo();r.activityInfo=new ActivityInfo();r.activityInfo.packageName=pkg;r.activityInfo.name=activity;r.activityInfo.applicationInfo=new ApplicationInfo();r.activityInfo.applicationInfo.packageName=pkg;r.activityInfo.applicationInfo.nonLocalizedLabel=label;return r;}
    private static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static CaptureDisclosureDialog disclosure(MainActivity a){a.getSupportFragmentManager().executePendingTransactions();idle();return(CaptureDisclosureDialog)a.getSupportFragmentManager().findFragmentByTag(CaptureDisclosureDialog.TAG);}
    private static View findText(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().equals(text))return view;if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){View found=findText(g.getChildAt(i),text);if(found!=null)return found;}}return null;}
    private static <T>T find(View v,Class<T> type){if(type.isInstance(v))return type.cast(v);if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){T found=find(g.getChildAt(i),type);if(found!=null)return found;}}return null;}
}
