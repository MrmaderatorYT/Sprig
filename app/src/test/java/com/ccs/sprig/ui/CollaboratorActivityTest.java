package com.ccs.sprig.ui;

import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.lifecycle.ViewModelProvider;
import com.ccs.sprig.SprigApp;
import com.ccs.sprig.oast.CollaboratorClient;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=SprigApp.class)
public class CollaboratorActivityTest {
    @Test public void createAndRevokeCompleteAcrossRotationWithoutLosingSecrets()throws Exception {
        CountDownLatch createEntered=new CountDownLatch(1),releaseCreate=new CountDownLatch(1),revokeEntered=new CountDownLatch(1),releaseRevoke=new CountDownLatch(1);
        AtomicInteger creates=new AtomicInteger(),revokes=new AtomicInteger();
        CollaboratorClient.Session expected=new CollaboratorClient.Session("abcdefghijklmnopqrstuvwx234567ab","secret-abcdefghijklmnopqrstuvwxyz0123456789","abcdefghijklmnopqrstuvwx234567ab.oast.test",System.currentTimeMillis()+60000,Collections.singletonList("HTTP"));
        try(ActivityController<CollaboratorActivity> controller=Robolectric.buildActivity(CollaboratorActivity.class).setup()) {
            CollaboratorActivity activity=controller.get();CollaboratorActivity.Model model=new ViewModelProvider(activity).get(CollaboratorActivity.Model.class);
            model.clients=base->new CollaboratorActivity.Api(){
                @Override public CollaboratorClient.Session create(String key)throws IOException {creates.incrementAndGet();createEntered.countDown();await(releaseCreate);return expected;}
                @Override public CollaboratorClient.Poll poll(CollaboratorClient.Session session,long cursor)throws IOException {throw new IOException("No polling expected");}
                @Override public void revoke(CollaboratorClient.Session session)throws IOException {assertSame(expected,session);revokes.incrementAndGet();revokeEntered.countDown();await(releaseRevoke);}
            };
            EditText api=findEdit(activity.getWindow().getDecorView());assertNotNull(api);api.setText("https://private-api.test/api/v1");
            model.generate("https://private-api.test/api/v1","test-admin-key-abcdefghijklmnopqrstuvwxyz");
            assertTrue(createEntered.await(2,TimeUnit.SECONDS));controller.recreate();
            assertSame(model,new ViewModelProvider(controller.get()).get(CollaboratorActivity.Model.class));
            assertEquals("https://private-api.test/api/v1",findEdit(controller.get().getWindow().getDecorView()).getText().toString());
            assertTrue(model.snapshot().busy);releaseCreate.countDown();waitIdle(model);
            assertSame(expected,model.snapshot().session);assertEquals(1,creates.get());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20));
            assertTrue(contains(controller.get().getWindow().getDecorView(),expected.payloadHost));
            model.revoke();assertTrue(revokeEntered.await(2,TimeUnit.SECONDS));controller.recreate();releaseRevoke.countDown();waitIdle(model);
            assertNull(model.snapshot().session);assertEquals(1,revokes.get());
        }finally{releaseCreate.countDown();releaseRevoke.countDown();}
    }
    private static void await(CountDownLatch latch)throws IOException {try{if(!latch.await(5,TimeUnit.SECONDS))throw new IOException("Fixture timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}
    private static void waitIdle(CollaboratorActivity.Model model)throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(model.snapshot().busy&&System.nanoTime()<deadline)Thread.sleep(5);assertFalse("Operation should complete independently of Activity",model.snapshot().busy);}
    private static EditText findEdit(View view){if(view instanceof EditText)return(EditText)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){EditText found=findEdit(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}return null;}
    private static boolean contains(View view,String needle){if(view instanceof TextView&&((TextView)view).getText().toString().contains(needle))return true;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(contains(((ViewGroup)view).getChildAt(i),needle))return true;return false;}
}
