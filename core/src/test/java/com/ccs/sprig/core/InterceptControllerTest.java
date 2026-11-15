package com.ccs.sprig.core;

import com.ccs.sprig.core.model.Exchange;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class InterceptControllerTest {
    @Test public void forwardEditedDropAndDisableResolveWorkers() throws Exception {
        InterceptController controller=new InterceptController();controller.setEnabled(true);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Exchange e=new Exchange();e.id=1;
            CountDownLatch pending=new CountDownLatch(1);controller.addListener(pending::countDown);
            Future<byte[]> result=worker.submit(()->controller.intercept(e,new byte[]{1}));
            assertTrue(pending.await(2,TimeUnit.SECONDS));controller.forward(1,new byte[]{2});
            assertArrayEquals(new byte[]{2},result.get(2,TimeUnit.SECONDS));
            CountDownLatch second=new CountDownLatch(1);controller.addListener(second::countDown);e.id=2;
            Future<byte[]> dropped=worker.submit(()->controller.intercept(e,new byte[]{3}));
            assertTrue(second.await(2,TimeUnit.SECONDS));controller.drop(2);assertNull(dropped.get(2,TimeUnit.SECONDS));
            controller.setEnabled(false);assertArrayEquals(new byte[]{4},controller.intercept(e,new byte[]{4}));
        } finally {worker.shutdownNow();}
    }
}
