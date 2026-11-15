package com.ccs.sprig.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.ccs.sprig.SprigApp;
import com.ccs.sprig.core.model.Exchange;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.Shadows;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="w411dp-h891dp-xxhdpi",application=SprigApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MainActivityTest {
    @Test public void allNavigationScreensInflateAndRender() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();
            View root=activity.getWindow().getDecorView();
            BottomNavigationView nav=find(root,BottomNavigationView.class);
            assertNotNull("Primary navigation must be present",nav);
            render(root,"traffic-empty");
            for(int i=0;i<nav.getMenu().size();i++) {
                nav.setSelectedItemId(nav.getMenu().getItem(i).getItemId());
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                assertTrue(root.isShown());
                render(root,"screen-"+i);
            }
        }
    }
    @Test public void liveHistoryRendersCapturedExchange() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            Exchange exchange=new Exchange();exchange.id=1;exchange.timeMillis=System.currentTimeMillis();
            exchange.method="GET";exchange.url="https://fixture.test/api/status";exchange.status=200;exchange.durationMillis=24;
            exchange.responseHeaders="HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n";
            exchange.responseBody="{\"ok\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            SprigApp.get().getStore().record(exchange);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));
            View root=controller.get().getWindow().getDecorView();render(root,"traffic-fixture");
            assertTrue("Captured target should appear",containsText(root,"fixture.test"));
        }
    }
    private static void render(View view,String name) throws Exception {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200));
        int width=1233,height=2673;
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
        view.layout(0,0,width,height);
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        File directory=new File("build/test-previews");assertTrue(directory.isDirectory()||directory.mkdirs());
        try(FileOutputStream output=new FileOutputStream(new File(directory,name+".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));
        }
        bitmap.recycle();
    }
    private static boolean containsText(View view,String needle) {
        if(view instanceof TextView && ((TextView)view).getText().toString().contains(needle)) return true;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) if(containsText(((ViewGroup)view).getChildAt(i),needle)) return true;
        return false;
    }
    private static <T> T find(View view,Class<T> type) {
        if(type.isInstance(view)) return type.cast(view);
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) {T result=find(((ViewGroup)view).getChildAt(i),type);if(result!=null)return result;}
        return null;
    }
}
