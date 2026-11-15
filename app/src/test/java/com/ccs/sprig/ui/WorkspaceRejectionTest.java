package com.ccs.sprig.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.lifecycle.ViewModelProvider;
import com.ccs.sprig.R;
import com.ccs.sprig.SprigApp;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=SprigApp.class)
public class WorkspaceRejectionTest {
    @Test public void repeaterRejectedBeforeDispatchRestoresSendButton()throws Exception{
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity activity=controller.get();View decor=activity.getWindow().getDecorView();BottomNavigationView navigation=find(decor,BottomNavigationView.class);assertNotNull(navigation);navigation.setSelectedItemId(navigation.getMenu().getItem(2).getItemId());
            ExecutorService work=(ExecutorService)get(activity,"work");CountDownLatch release=fill(work,4);
            try{View send=findText(decor,activity.getString(R.string.send_request));assertNotNull(send);send.performClick();assertTrue("Rejected request must be retryable",send.isEnabled());assertEquals(activity.getString(R.string.send_request),((TextView)send).getText().toString());assertFalse((boolean)get(activity,"replayBusy"));assertEquals(0,SprigApp.get().getStore().size());}finally{release.countDown();}
        }
    }
    @Test public void scannerRejectedBeforeDispatchClearsRunningState()throws Exception{
        try(ActivityController<ScannerActivity> controller=Robolectric.buildActivity(ScannerActivity.class).setup()){
            ScannerActivity activity=controller.get();ScannerActivity.Session session=new ViewModelProvider(activity).get(ScannerActivity.Session.class);CountDownLatch release=fill(session.io,2);
            try{session.start();assertFalse(session.running);assertNull(session.engine);assertEquals(activity.getString(R.string.workspace_busy),session.error);assertNull(session.report);assertEquals(0,SprigApp.get().getStore().size());}finally{release.countDown();}
        }
    }
    private static CountDownLatch fill(ExecutorService queue,int capacity)throws Exception{CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);queue.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException error){Thread.currentThread().interrupt();}});assertTrue(entered.await(2,TimeUnit.SECONDS));for(int i=0;i<capacity;i++)queue.execute(()->{});return release;}
    private static Object get(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static View findText(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().equals(text))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View result=findText(((ViewGroup)view).getChildAt(i),text);if(result!=null)return result;}return null;}
    private static <T>T find(View view,Class<T> type){if(type.isInstance(view))return type.cast(view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){T result=find(((ViewGroup)view).getChildAt(i),type);if(result!=null)return result;}return null;}
}
