package com.ccs.sprig.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Parcel;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.lifecycle.ViewModelProvider;
import com.ccs.sprig.SprigApp;
import com.ccs.sprig.core.model.Exchange;
import com.google.android.material.tabs.TabLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
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

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="w411dp-h891dp-xxhdpi",application=SprigApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class IntruderActivityTest {
    @Test public void allTabsOpenWithoutDispatchAndPositionsRender() throws Exception {
        try(ActivityController<IntruderActivity> controller=Robolectric.buildActivity(IntruderActivity.class).setup()) {
            IntruderActivity activity=controller.get();IntruderActivity.Model model=new ViewModelProvider(activity).get(IntruderActivity.Model.class);
            assertFalse(model.starting);assertNull(model.attack);
            View root=activity.getWindow().getDecorView();assertNotNull(findText(root,"Start attack"));
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            root.measure(View.MeasureSpec.makeMeasureSpec(1233,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2673,View.MeasureSpec.EXACTLY));root.layout(0,0,1233,2673);
            Bitmap bitmap=Bitmap.createBitmap(1233,2673,Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));
            File directory=new File("build/test-previews");assertTrue(directory.isDirectory()||directory.mkdirs());
            try(FileOutputStream output=new FileOutputStream(new File(directory,"intruder-ready.png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}bitmap.recycle();
            TabLayout tabs=find(root,TabLayout.class);assertNotNull(tabs);
            tabs.getTabAt(1).select();assertNotNull(findText(root,"Add set"));assertNotNull(findText(root,"Add processing rule"));
            tabs.getTabAt(2).select();assertFalse(model.starting);assertNull(model.attack);assertNotNull(findText(root,"Start attack"));
            controller.recreate();assertSame(model,new ViewModelProvider(controller.get()).get(IntruderActivity.Model.class));assertEquals(2,model.page);
        }
    }
    @Test public void largeBinaryDraftSurvivesRotationOutsideSavedState() {
        byte[] prefix="POST /?q=§x§ HTTP/1.1\r\nHost: fixture.test\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] raw=Arrays.copyOf(prefix,prefix.length+900*1024);Arrays.fill(raw,prefix.length,raw.length,(byte)0xff);
        IntruderActivity.prepareDraft("https://fixture.test/",raw,"HTTP/2");
        try(ActivityController<IntruderActivity> controller=Robolectric.buildActivity(IntruderActivity.class).setup()) {
            EditText editor=findRequest(controller.get().getWindow().getDecorView());assertNotNull(editor);assertEquals(raw.length,editor.length());
            Bundle state=new Bundle();controller.saveInstanceState(state);Parcel parcel=Parcel.obtain();
            try{state.writeToParcel(parcel,0);assertTrue("Large editors must stay outside Android transactions",parcel.dataSize()<64*1024);}finally{parcel.recycle();}
            controller.recreate();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            EditText restored=findRequest(controller.get().getWindow().getDecorView());assertNotNull(restored);assertArrayEquals(raw,restored.getText().toString().getBytes(StandardCharsets.ISO_8859_1));
            assertNull(new ViewModelProvider(controller.get()).get(IntruderActivity.Model.class).attack);
        }
    }
    @Test public void incompleteCapturedPostDoesNotBecomeAnonymousTemplate() {
        Exchange exchange=new Exchange();exchange.id=718;exchange.method="POST";exchange.url="https://fixture.test/private";exchange.requestBody=new byte[]{1,2};
        exchange.requestHeaders="POST /private HTTP/1.1\r\nHost: fixture.test\r\nCookie: secret\r\nContent-Length: 20\r\n\r\n";
        SprigApp app=(SprigApp)RuntimeEnvironment.getApplication();app.getStore().record(exchange);
        Intent intent=new Intent(app,IntruderActivity.class).putExtra(IntruderActivity.EXTRA_SEED_URL,exchange.url).putExtra(IntruderActivity.EXTRA_EXCHANGE_ID,exchange.id);assertEquals(2,intent.getExtras().size());
        try(ActivityController<IntruderActivity> controller=Robolectric.buildActivity(IntruderActivity.class,intent).setup()) {
            IntruderActivity.Model model=new ViewModelProvider(controller.get()).get(IntruderActivity.Model.class);assertFalse(model.sourceError.isEmpty());
            findText(controller.get().getWindow().getDecorView(),"Start attack").performClick();assertFalse(model.starting);assertNull(model.attack);
            controller.recreate();assertSame(model,new ViewModelProvider(controller.get()).get(IntruderActivity.Model.class));assertFalse(model.sourceError.isEmpty());
        }
    }
    @Test public void fullCapturedUploadRemainsEditableWhenOnlyResponseWasTruncated() {
        Exchange exchange=new Exchange();exchange.id=719;exchange.method="POST";exchange.url="https://fixture.test/upload";exchange.requestBody=new byte[512*1024];exchange.truncated=true;
        exchange.requestHeaders="POST /upload HTTP/1.1\r\nHost: fixture.test\r\nContent-Length: "+exchange.requestBody.length+"\r\n\r\n";
        SprigApp app=(SprigApp)RuntimeEnvironment.getApplication();app.getStore().record(exchange);
        Intent intent=new Intent(app,IntruderActivity.class).putExtra(IntruderActivity.EXTRA_EXCHANGE_ID,exchange.id);
        try(ActivityController<IntruderActivity> controller=Robolectric.buildActivity(IntruderActivity.class,intent).setup()) {
            IntruderActivity.Model model=new ViewModelProvider(controller.get()).get(IntruderActivity.Model.class);assertEquals("",model.sourceError);assertNull(model.attack);
        }
    }
    @Test public void queuedPreparationCanBeCancelledAndWorkspaceQueueIsBounded() throws Exception {
        try(ActivityController<IntruderActivity> controller=Robolectric.buildActivity(IntruderActivity.class).setup()) {
            IntruderActivity.Model model=new ViewModelProvider(controller.get()).get(IntruderActivity.Model.class);
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            assertTrue(model.submitIo(()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}));assertTrue(entered.await(2,TimeUnit.SECONDS));
            try{model.start();assertTrue(model.starting);model.cancel();assertTrue(model.submitIo(()->{}));assertFalse(model.submitIo(()->fail("Rejected action must never run")));}finally{release.countDown();}
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(model.starting&&System.nanoTime()<deadline)Thread.sleep(5);
            assertFalse(model.starting);assertNull("Cancel before dispatch must not start any attack",model.attack);
        }
    }
    private static EditText findRequest(View view){if(view instanceof EditText&&((EditText)view).getText().toString().startsWith("POST /?q="))return(EditText)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){EditText found=findRequest(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}return null;}
    private static View findText(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().equals(text))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=findText(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}return null;}
    private static <T>T find(View view,Class<T> type){if(type.isInstance(view))return type.cast(view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){T found=find(((ViewGroup)view).getChildAt(i),type);if(found!=null)return found;}return null;}
}
