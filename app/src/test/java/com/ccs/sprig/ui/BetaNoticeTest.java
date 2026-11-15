package com.ccs.sprig.ui;

import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.ccs.sprig.R;
import com.ccs.sprig.SprigApp;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, qualifiers="en-w411dp-h891dp-xhdpi", application=SprigApp.class)
public class BetaNoticeTest {
    @Test public void noticeSurvivesRotationUntilExplicitAcknowledgement() {
        SprigApp.get().getPreferences().edit().remove(BetaNoticeDialog.ACKNOWLEDGED).commit();
        try (ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();
            BetaNoticeDialog first=notice(activity); assertNotNull(first);
            assertEquals("Open beta testing",activity.getString(R.string.beta_title));
            assertFalse(first.isCancelable());
            assertFalse(SprigApp.get().getPreferences().getBoolean(BetaNoticeDialog.ACKNOWLEDGED,false));
            controller.recreate(); activity=controller.get();
            BetaNoticeDialog restored=notice(activity); assertNotNull(restored);
            ((AlertDialog)restored.requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
            assertTrue(SprigApp.get().getPreferences().getBoolean(BetaNoticeDialog.ACKNOWLEDGED,false));
            controller.recreate();assertNull(notice(controller.get()));
        }
    }
    @Test public void feedbackOpensComposerWithoutAttachingDataOrAcknowledgingNotice() {
        SprigApp.get().getPreferences().edit().remove(BetaNoticeDialog.ACKNOWLEDGED).commit();
        try (ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();BetaNoticeDialog dialog=notice(activity);
            ((AlertDialog)dialog.requireDialog()).getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            Intent intent=Shadows.shadowOf(activity).getNextStartedActivity();assertNotNull(intent);
            assertEquals(Intent.ACTION_SENDTO,intent.getAction());
            assertEquals("mailto:cybercraftstudiopro@gmail.com",intent.getDataString());
            assertNull(intent.getClipData()); assertFalse(intent.hasExtra(Intent.EXTRA_STREAM));
            assertFalse(intent.hasExtra(Intent.EXTRA_TEXT));assertEquals(1,intent.getExtras().size());
            assertFalse(SprigApp.get().getPreferences().getBoolean(BetaNoticeDialog.ACKNOWLEDGED,false));
            assertTrue(dialog.requireDialog().isShowing());
        }
    }
    @Test public void acknowledgedEnglishSettingsShowInstalledIdentityAndSource() { settings("About Sprig","Package name: com.ccs.sprig"); }
    @Test @Config(qualifiers="uk-rUA-w411dp-h891dp-xhdpi")
    public void ukrainianSettingsAndNoticeUseUkrainianResources() {
        assertEquals("Відкрите бета-тестування",SprigApp.get().getString(R.string.beta_title));
        settings("Про Sprig","Назва пакета: com.ccs.sprig");
    }
    private void settings(String heading,String identity) {
        SprigApp.get().getPreferences().edit().putBoolean(BetaNoticeDialog.ACKNOWLEDGED,true).commit();
        try (ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get(); assertNull(notice(activity));
            View root=activity.getWindow().getDecorView();find(root,BottomNavigationView.class).setSelectedItemId(105);idle();
            assertTrue(contains(root,heading));assertTrue(contains(root,identity));
            android.content.pm.PackageInfo info=AppInformation.installed(activity);
            assertTrue(contains(root,info.versionName));
            assertTrue(contains(root,activity.getString(R.string.about_version_code,AppInformation.versionCode(info))));
            assertTrue(contains(root,"https://github.com/MrmaderatorYT/Sprig"));
            AppInformation.openSource(activity);Intent source=Shadows.shadowOf(activity).getNextStartedActivity();
            assertEquals(Intent.ACTION_VIEW,source.getAction());assertEquals(activity.getString(R.string.source_code_url),source.getDataString());
        }
    }
    private static BetaNoticeDialog notice(MainActivity activity) {
        activity.getSupportFragmentManager().executePendingTransactions();idle();
        return (BetaNoticeDialog)activity.getSupportFragmentManager().findFragmentByTag(BetaNoticeDialog.TAG);
    }
    private static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static boolean contains(View v,String text){if(v instanceof TextView&&((TextView)v).getText().toString().contains(text))return true;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)if(contains(g.getChildAt(i),text))return true;}return false;}
    private static <T>T find(View v,Class<T> type){if(type.isInstance(v))return type.cast(v);if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){T item=find(g.getChildAt(i),type);if(item!=null)return item;}}return null;}
}
