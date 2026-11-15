package com.ccs.sprig.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.lifecycle.ViewModelProvider;
import com.ccs.sprig.SprigApp;
import com.ccs.sprig.core.model.Exchange;
import com.ccs.sprig.core.scanner.ScanConfig;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.io.File;
import java.io.FileOutputStream;
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
public class ScannerActivityTest {
    @Test public void scannerOpensAndRendersWithoutStartingNetwork()throws Exception {
        Intent intent=new Intent(RuntimeEnvironment.getApplication(),ScannerActivity.class).putExtra(ScannerActivity.EXTRA_SEED_URL,"https://fixture.test/read-only");
        try(ActivityController<ScannerActivity> controller=Robolectric.buildActivity(ScannerActivity.class,intent).setup()) {
            ScannerActivity activity=controller.get();ScannerActivity.Session session=new ViewModelProvider(activity).get(ScannerActivity.Session.class);
            assertFalse(session.running);assertNull(session.engine);assertNull(session.report);
            View root=activity.getWindow().getDecorView();
            assertTrue(contains(root,"Start scan"));assertTrue(contains(root,"fixture.test/read-only"));
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            root.measure(View.MeasureSpec.makeMeasureSpec(1233,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2673,View.MeasureSpec.EXACTLY));root.layout(0,0,1233,2673);
            Bitmap bitmap=Bitmap.createBitmap(1233,2673,Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));
            File directory=new File("build/test-previews");assertTrue(directory.isDirectory()||directory.mkdirs());
            try(FileOutputStream output=new FileOutputStream(new File(directory,"scanner-ready.png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}bitmap.recycle();
            controller.recreate();assertSame(session,new ViewModelProvider(controller.get()).get(ScannerActivity.Session.class));assertFalse(session.running);
        }
    }
    @Test public void capturedGetIsLookedUpOnceAndRetainedWithoutCredentialsInIntent()throws Exception {
        Exchange exchange=new Exchange();exchange.id=713;exchange.method="GET";exchange.url="https://fixture.test/private";
        exchange.requestHeaders="GET /private HTTP/1.1\r\nHost: fixture.test\r\nCookie: sid=private-value\r\nAuthorization: Bearer private-token\r\n\r\n";
        SprigApp app=(SprigApp)RuntimeEnvironment.getApplication();app.getStore().record(exchange);
        Intent intent=new Intent(app,ScannerActivity.class).putExtra(ScannerActivity.EXTRA_SEED_URL,exchange.url).putExtra(ScannerActivity.EXTRA_EXCHANGE_ID,exchange.id);
        assertEquals(2,intent.getExtras().size());
        try(ActivityController<ScannerActivity> controller=Robolectric.buildActivity(ScannerActivity.class,intent).setup()) {
            ScannerActivity.Session session=new ViewModelProvider(controller.get()).get(ScannerActivity.Session.class);
            assertNull(session.sourceError);assertFalse(session.running);assertNull(session.engine);
            assertTrue(new String(session.capturedGetRequest,java.nio.charset.StandardCharsets.ISO_8859_1).contains("Cookie: sid=private-value"));
            byte[] retained=session.capturedGetRequest;app.getStore().clear();controller.recreate();
            assertSame(session,new ViewModelProvider(controller.get()).get(ScannerActivity.Session.class));assertSame(retained,session.capturedGetRequest);assertNull(session.sourceError);
        }
    }
    @Test public void incompleteCapturedGetCannotStartSilentlyAsAnonymous()throws Exception {
        Exchange exchange=new Exchange();exchange.id=714;exchange.method="GET";exchange.url="https://fixture.test/private";
        exchange.requestHeaders="GET /private HTTP/1.1\r\nHost: fixture.test\r\nContent-Length: 12\r\n\r\n";
        SprigApp app=(SprigApp)RuntimeEnvironment.getApplication();app.getStore().record(exchange);
        Intent intent=new Intent(app,ScannerActivity.class).putExtra(ScannerActivity.EXTRA_EXCHANGE_ID,exchange.id);
        try(ActivityController<ScannerActivity> controller=Robolectric.buildActivity(ScannerActivity.class,intent).setup()) {
            ScannerActivity.Session session=new ViewModelProvider(controller.get()).get(ScannerActivity.Session.class);
            assertNotNull(session.sourceError);assertNull(session.capturedGetRequest);session.start();assertFalse(session.running);assertNull(session.engine);
        }
    }
    @Test public void capturedPostRequiresStatefulOptInAndRetainsCheckChoicesAcrossRotation()throws Exception {
        Exchange exchange=new Exchange();exchange.id=715;exchange.method="POST";exchange.url="https://fixture.test/api";exchange.requestBody="{\"q\":\"base\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.requestHeaders="POST /api HTTP/1.1\r\nHost: fixture.test\r\nCookie: sid=private\r\nContent-Type: application/json\r\nContent-Length: "+exchange.requestBody.length+"\r\n\r\n";
        SprigApp app=(SprigApp)RuntimeEnvironment.getApplication();app.getStore().record(exchange);Intent intent=new Intent(app,ScannerActivity.class).putExtra(ScannerActivity.EXTRA_SEED_URL,exchange.url).putExtra(ScannerActivity.EXTRA_EXCHANGE_ID,exchange.id);
        try(ActivityController<ScannerActivity> controller=Robolectric.buildActivity(ScannerActivity.class,intent).setup()){
            ScannerActivity.Session session=new ViewModelProvider(controller.get()).get(ScannerActivity.Session.class);View root=controller.get().getWindow().getDecorView();
            assertNull(session.sourceError);assertTrue(session.isPost());assertFalse(findText(root,"Start scan").isEnabled());session.start();assertNull(session.engine);assertFalse(session.running);
            View toggle=findText(root,"Enable stateful testing of this captured POST");assertTrue(toggle instanceof MaterialSwitch);toggle.performClick();assertTrue(session.allowStateful);assertTrue(findText(root,"Start scan").isEnabled());
            session.enabled.add("template");session.points.clear();session.points.add(ScanConfig.InsertionType.JSON);session.maxPerCheck=12;session.callback="private.oast.test";
            controller.recreate();assertSame(session,new ViewModelProvider(controller.get()).get(ScannerActivity.Session.class));assertTrue(session.allowStateful);assertTrue(session.enabled.contains("template"));assertTrue(session.points.contains(ScanConfig.InsertionType.JSON));assertEquals(12,session.maxPerCheck);assertEquals("private.oast.test",session.callback);assertNull(session.engine);
        }
    }
    private static View findText(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().equals(text))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=findText(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}return null;}
    private static boolean contains(View view,String needle){if(view instanceof TextView&&((TextView)view).getText().toString().contains(needle))return true;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(contains(((ViewGroup)view).getChildAt(i),needle))return true;return false;}
}
