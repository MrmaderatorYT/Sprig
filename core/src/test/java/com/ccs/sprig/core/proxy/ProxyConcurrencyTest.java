package com.ccs.sprig.core.proxy;

import com.ccs.sprig.core.InterceptController;
import com.ccs.sprig.core.model.Exchange;
import com.ccs.sprig.core.model.SessionStore;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ProxyConcurrencyTest {
    @Rule public TemporaryFolder directory=new TemporaryFolder();
    @Test(timeout=20000) public void replaySharesTheSameFastBoundedOriginBudget() throws Exception {
        SessionStore history=new SessionStore();
        ProxyServer proxy=new ProxyServer(history,new InterceptController(),directory.newFolder("ca"),socket->socket.isBound());
        proxy.setConnectionLimit(1);
        CountDownLatch accepted=new CountDownLatch(1),finish=new CountDownLatch(1);
        AtomicInteger connections=new AtomicInteger();
        try(ServerSocket server=new ServerSocket(0,2,InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<Void> origin=CompletableFuture.runAsync(()->{
                try(Socket socket=server.accept()) {
                    connections.incrementAndGet();HttpCodec.readRequest(socket.getInputStream(),socket.getOutputStream());accepted.countDown();
                    if(!finish.await(5,TimeUnit.SECONDS))throw new IOException("Fixture response not released");
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".getBytes(StandardCharsets.US_ASCII));
                }catch(Exception failure){throw new RuntimeException(failure);}
            });
            byte[] raw="GET / HTTP/1.1\r\nHost: fixture.test\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            String url="http://127.0.0.1:"+server.getLocalPort()+"/";
            CompletableFuture<Exchange> first=CompletableFuture.supplyAsync(()->{try{return proxy.replay(url,raw);}catch(IOException failure){throw new RuntimeException(failure);}});
            assertTrue(accepted.await(5,TimeUnit.SECONDS));
            long start=System.nanoTime();
            try {proxy.replay(url,raw);fail("A second origin exchange must not bypass the common budget");}
            catch(IOException busy){assertTrue(busy.getMessage().contains("busy"));}
            assertTrue("Busy admission should not queue network workers",System.nanoTime()-start<TimeUnit.SECONDS.toNanos(1));
            assertEquals(1,connections.get());
            try{proxy.setConnectionLimit(2);fail("An active exchange must retain its budget");}catch(IllegalStateException expected){}
            finish.countDown();assertEquals(200,first.get(5,TimeUnit.SECONDS).status);origin.get(3,TimeUnit.SECONDS);
            assertEquals(2,history.size());
            // The first request's finally block must release its permit even after a concurrent rejection.
            proxy.setConnectionLimit(2);
        }finally{finish.countDown();proxy.stop();}
    }
}
