package com.ccs.sprig.core.proxy;

import com.ccs.sprig.core.InterceptController;
import com.ccs.sprig.core.http2.Http2Connection;
import com.ccs.sprig.core.model.SessionStore;
import com.ccs.sprig.core.net.CallCancellation;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ProxyLifecycleTest {
    @Rule public TemporaryFolder directory=new TemporaryFolder();
    @Test(timeout=30000) public void repeatedStopRestartClosesPartialRequestsAndDoesNotLoseAdmission()throws Exception {
        ProxyServer proxy=new ProxyServer(new SessionStore(),new InterceptController(),directory.newFolder(),null);
        proxy.setConnectionLimit(1);
        try {
            for(int i=0;i<20;i++) {
                proxy.start(0);
                try(Socket idle=new Socket("127.0.0.1",proxy.getPort())) {
                    idle.setSoTimeout(2000);idle.getOutputStream().write('G');idle.getOutputStream().flush();
                    proxy.stop();assertEquals(-1,proxy.getPort());
                    try{while(idle.getInputStream().read()!=-1){} }catch(java.net.SocketException expected){}
                }
            }
            proxy.start(0);assertTrue(proxy.getPort()>0);proxy.setMitmEnabled(false);
        }finally{proxy.stop();}
    }
    @Test(timeout=20000) public void cancelledHttp2OriginDoesNotCancelAnotherReplayOrLeakPermit()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        CallCancellation.Token token=new CallCancellation.Token();
        ThreadPoolExecutor servers=pool("fixture-h2-connection"),handlers=pool("fixture-h2-handler"),clients=pool("fixture-replay");
        ProxyServer proxy=new ProxyServer(new SessionStore(),new InterceptController(),directory.newFolder(),null);proxy.setConnectionLimit(2);
        try(ServerSocket origin=new ServerSocket(0,2,InetAddress.getByName("127.0.0.1"))) {
            origin.setSoTimeout(5000);
            Future<?> accepting=servers.submit(()->{try{for(int i=0;i<2;i++){
                Socket socket=origin.accept();socket.setSoTimeout(5000);
                servers.execute(()->{try(socket){Http2Connection.serve(new BufferedInputStream(socket.getInputStream()),socket.getOutputStream(),socket,handlers,(request,authority,scheme)->{
                    if(request.startLine.contains("/blocked ")){entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}
                    HttpCodec.Message response=new HttpCodec.Message();response.startLine="HTTP/1.1 200 OK";response.body=new byte[]{42};response.set("Content-Length","1");return response;
                });}catch(IOException expected){}});
            }}catch(IOException e){throw new RuntimeException(e);}});
            String url="http://127.0.0.1:"+origin.getLocalPort()+"/";
            Future<Boolean> blocked=clients.submit(()->{try(CallCancellation.Scope ignored=CallCancellation.enter(token)){
                try{proxy.replay(url,raw("/blocked"),"HTTP/2");return false;}catch(IOException expected){return true;}
            }});
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            Future<Integer> healthy=clients.submit(()->proxy.replay(url,raw("/healthy"),"HTTP/2").status);
            token.cancel();assertTrue(blocked.get(3,TimeUnit.SECONDS));assertEquals(200,(int)healthy.get(3,TimeUnit.SECONDS));
            accepting.get(3,TimeUnit.SECONDS);proxy.setConnectionLimit(1);
        }finally{release.countDown();token.cancel();proxy.stop();servers.shutdownNow();handlers.shutdownNow();clients.shutdownNow();}
    }
    private static byte[] raw(String path){return("GET "+path+" HTTP/1.1\r\nHost: fixture.test\r\n\r\n").getBytes(StandardCharsets.US_ASCII);}
    private static ThreadPoolExecutor pool(String name){return new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(4),r->{Thread thread=new Thread(r,name);thread.setDaemon(true);return thread;});}
}
