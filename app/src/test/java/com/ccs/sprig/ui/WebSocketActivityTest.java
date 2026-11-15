package com.ccs.sprig.ui;

import android.os.Bundle;
import android.os.Parcel;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import com.ccs.sprig.SprigApp;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=SprigApp.class,qualifiers="w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WebSocketActivityTest {
    @Test public void workspaceInflatesAndLargeComposerStaysOutOfStateParcel()throws Exception {
        try(ActivityController<WebSocketActivity> controller=Robolectric.buildActivity(WebSocketActivity.class).setup()) {
            View root=controller.get().getWindow().getDecorView();assertTrue(contains(root,"WebSockets"));assertTrue(contains(root,"Hold data messages"));
            java.lang.reflect.Field hub=WebSocketActivity.class.getDeclaredField("hub");hub.setAccessible(true);long readyBy=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while(hub.get(controller.get())==null&&System.nanoTime()<readyBy){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(10);}
            assertNotNull("Workspace must finish preparing without connecting to a target",hub.get(controller.get()));org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            root.measure(View.MeasureSpec.makeMeasureSpec(1233,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2673,View.MeasureSpec.EXACTLY));root.layout(0,0,1233,2673);Bitmap image=Bitmap.createBitmap(1233,2673,Bitmap.Config.ARGB_8888);root.draw(new Canvas(image));java.io.File output=new java.io.File("build/test-previews/websocket.png");assertTrue(output.getParentFile().isDirectory()||output.getParentFile().mkdirs());try(java.io.FileOutputStream file=new java.io.FileOutputStream(output)){image.compress(Bitmap.CompressFormat.PNG,100,file);}image.recycle();
            java.lang.reflect.Field field=WebSocketActivity.class.getDeclaredField("composer");field.setAccessible(true);EditText composer=(EditText)field.get(controller.get());
            char[] large=new char[200000];java.util.Arrays.fill(large,'x');composer.setText(new String(large));
            Bundle state=new Bundle();controller.saveInstanceState(state);Parcel parcel=Parcel.obtain();try{state.writeToParcel(parcel,0);assertTrue(parcel.dataSize()<64*1024);}finally{parcel.recycle();}
        }
    }
    private static boolean contains(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().contains(text))return true;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(contains(((ViewGroup)view).getChildAt(i),text))return true;return false;}
}
