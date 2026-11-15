package com.ccs.sprig.ui;

import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import com.ccs.sprig.R;
import com.ccs.sprig.SprigApp;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Configuration tests exercise real view geometry; they do not replace device/TalkBack testing. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="w411dp-h891dp-xhdpi",application=SprigApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AdaptiveWorkspaceTest {
    @Test @Config(qualifiers="uk-rUA-w411dp-h891dp-xhdpi")
    public void ukrainianResourcesUsePluralRulesAndNavigationKeepsStableIds() throws Exception {
        android.content.res.Resources resources=RuntimeEnvironment.getApplication().getResources();
        assertEquals("1 категорія",resources.getQuantityString(R.plurals.scanner_categories,1,1));
        assertEquals("2 категорії",resources.getQuantityString(R.plurals.scanner_categories,2,2));
        assertEquals("5 категорій",resources.getQuantityString(R.plurals.scanner_categories,5,5));
        assertEquals("Підключення…",resources.getString(R.string.ws_connecting));
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            View root=controller.get().getWindow().getDecorView();BottomNavigationView nav=find(root,BottomNavigationView.class);assertNotNull(nav);
            assertEquals(5,nav.getMenu().size());assertEquals("Трафік",nav.getMenu().getItem(0).getTitle().toString());
            int tools=nav.getMenu().getItem(3).getItemId();nav.setSelectedItemId(tools);idle();assertEquals(tools,nav.getSelectedItemId());
            assertEquals("Інструменти",nav.getMenu().getItem(3).getTitle().toString());
        }
    }

    @Test @Config(qualifiers="uk-rUA-w411dp-h891dp-xhdpi")
    public void largeFontWebSocketControlsRemainReachableWithKeyboardInsets() throws Exception {
        RuntimeEnvironment.setFontScale(2f);
        try(ActivityController<WebSocketActivity> controller=Robolectric.buildActivity(WebSocketActivity.class).setup()){
            WebSocketActivity activity=controller.get();assertEquals(2f,activity.getResources().getConfiguration().fontScale,0.01f);awaitReady(activity);View decor=activity.getWindow().getDecorView();
            ScrollView workspace=find(decor,ScrollView.class);assertNotNull("The workspace must scroll when large captions exceed the viewport",workspace);
            View content=workspace.getChildAt(0);int keyboard=dp(decor,240);WindowInsetsCompat insets=new WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.ime(),Insets.of(0,0,0,keyboard)).setVisible(WindowInsetsCompat.Type.ime(),true).build();
            // API28 platform dispatch cannot round-trip synthetic IME type overrides; invoke the exact listener path.
            activity.applyWorkspaceInsets(content,insets);assertEquals(keyboard,content.getPaddingBottom());
            layout(decor,411,651);MaterialSwitch hold=find(decor,MaterialSwitch.class);assertNotNull(hold);assertFalse(hold.isChecked());
            assertEquals(activity.getString(R.string.ws_hold_description),hold.getContentDescription().toString());
            View send=findText(decor,activity.getString(R.string.ws_send));assertNotNull(send);reveal(send);assertVisible(send);
            View connect=findText(decor,activity.getString(R.string.ws_connect));assertNotNull(connect);reveal(connect);assertVisible(connect);
            assertTrue("Opening/configuring the screen must not connect to a target",activity.getString(R.string.ws_idle).equals(((TextView)get(activity,"status")).getText().toString()));
            preview(decor,"stage04-websocket-uk-large-font-keyboard");
        }
    }

    @Test @Config(qualifiers="w891dp-h411dp-land-xhdpi")
    public void scannerAndWebSocketActionsRemainReachableInLandscape() throws Exception {
        try(ActivityController<ScannerActivity> controller=Robolectric.buildActivity(ScannerActivity.class).setup()){
            View decor=controller.get().getWindow().getDecorView();layout(decor,891,411);View start=findText(decor,"Start scan");assertNotNull(start);
            reveal(start);assertVisible(start);ScannerActivity.Session session=new ViewModelProvider(controller.get()).get(ScannerActivity.Session.class);assertFalse(session.running);assertNull(session.engine);
            preview(decor,"stage04-scanner-landscape");
        }
        try(ActivityController<WebSocketActivity> controller=Robolectric.buildActivity(WebSocketActivity.class).setup()){
            awaitReady(controller.get());View decor=controller.get().getWindow().getDecorView();layout(decor,891,411);View send=findText(decor,controller.get().getString(R.string.ws_send));assertNotNull(send);
            reveal(send);assertVisible(send);preview(decor,"stage04-websocket-landscape");
        }
    }

    @Test @Config(qualifiers="sw600dp-w1280dp-h800dp-land-xhdpi")
    public void tabletNavigationAndIntruderTabsRecreateWithoutDispatch() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            View decor=controller.get().getWindow().getDecorView();BottomNavigationView nav=find(decor,BottomNavigationView.class);assertNotNull(nav);
            for(int i=0;i<nav.getMenu().size();i++){nav.setSelectedItemId(nav.getMenu().getItem(i).getItemId());idle();layout(decor,1280,800);assertTrue(nav.isShown());}
        }
        try(ActivityController<IntruderActivity> controller=Robolectric.buildActivity(IntruderActivity.class).setup()){
            IntruderActivity.Model model=new ViewModelProvider(controller.get()).get(IntruderActivity.Model.class);assertNull(model.attack);assertFalse(model.starting);
            layout(controller.get().getWindow().getDecorView(),1280,800);controller.recreate();assertSame(model,new ViewModelProvider(controller.get()).get(IntruderActivity.Model.class));assertNull(model.attack);
        }
    }
    private static void awaitReady(WebSocketActivity activity)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(get(activity,"hub")==null&&System.nanoTime()<deadline){idle();Thread.sleep(10);}idle();assertNotNull(get(activity,"hub"));}
    private static Object get(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static void idle(){Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();}
    private static void layout(View view,int widthDp,int heightDp){idle();int width=dp(view,widthDp),height=dp(view,heightDp);view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);idle();}
    private static void reveal(View view){view.requestRectangleOnScreen(new Rect(0,0,view.getWidth(),view.getHeight()),true);idle();}
    private static void assertVisible(View view){Rect rect=new Rect();assertTrue("Action must intersect the visible viewport",view.getGlobalVisibleRect(rect));assertTrue("Action must have a readable touch target",rect.height()>=dp(view,32));}
    private static int dp(View view,int value){return Math.round(value*view.getResources().getDisplayMetrics().density);}
    private static void preview(View view,String name)throws Exception{Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));File directory=new File("build/test-previews");assertTrue(directory.isDirectory()||directory.mkdirs());try(FileOutputStream output=new FileOutputStream(new File(directory,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}bitmap.recycle();}
    private static View findText(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().equals(text))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View result=findText(((ViewGroup)view).getChildAt(i),text);if(result!=null)return result;}return null;}
    private static <T>T find(View view,Class<T> type){if(type.isInstance(view))return type.cast(view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){T result=find(((ViewGroup)view).getChildAt(i),type);if(result!=null)return result;}return null;}
}
