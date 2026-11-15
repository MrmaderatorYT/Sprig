package com.ccs.sprig.core.http2;

import com.ccs.sprig.core.proxy.HttpCodec;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class Http2ConnectionTest {
    @Test(timeout=15000) public void requestAndResponseLargerThanWindowRemainByteExact() throws Exception {
        byte[] payload=new byte[240000];for(int i=0;i<payload.length;i++)payload[i]=(byte)(i*31);
        try(Fixture fixture=new Fixture((request,authority,scheme)->{
            assertEquals("fixture.test",authority);assertArrayEquals(payload,request.body);
            return response(payload);
        });Socket socket=fixture.connect()) {
            HttpCodec.Message request=new HttpCodec.Message();request.startLine="POST /bytes HTTP/1.1";request.body=payload;request.set("content-length",Integer.toString(payload.length));
            HttpCodec.Message response=Http2Connection.exchange(new BufferedInputStream(socket.getInputStream()),socket.getOutputStream(),socket,request,"fixture.test","http");
            assertEquals(200,response.status());assertArrayEquals(payload,response.body);
        }
    }
    @Test(timeout=15000) public void independentStreamsFinishWhileEarlierStreamIsBlocked() throws Exception {
        CountDownLatch releaseSlow=new CountDownLatch(1);
        try(Fixture fixture=new Fixture((request,authority,scheme)->{
            if(request.startLine.contains("/slow "))try{if(!releaseSlow.await(5,TimeUnit.SECONDS))throw new IOException("Test stream was serialized");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
            return response(request.startLine.getBytes(StandardCharsets.US_ASCII));
        });Socket socket=fixture.connect()) {
            OutputStream out=socket.getOutputStream();out.write(Http2Connection.PREFACE);write(out,4,0,0,new byte[0]);Hpack hpack=new Hpack();
            write(out,1,5,1,hpack.encode(1,requestHeaders("/slow")));write(out,1,5,3,hpack.encode(3,requestHeaders("/fast")));
            InputStream in=socket.getInputStream();int complete=0,firstHeaders=0;
            while(complete<2) {
                Frame frame=read(in);
                if(frame.type==1) {if(firstHeaders==0){firstHeaders=frame.stream;assertEquals(3,firstHeaders);releaseSlow.countDown();}hpack.decode(frame.stream,frame.data);}
                if((frame.type==0||frame.type==1)&&(frame.flags&1)!=0)complete++;
            }
            assertEquals(3,firstHeaders);
        } finally {releaseSlow.countDown();}
    }
    @Test(timeout=15000) public void resetCancelsOneStreamWithoutBreakingTheNext() throws Exception {
        CountDownLatch entered=new CountDownLatch(1);
        try(Fixture fixture=new Fixture((request,authority,scheme)->{
            if(request.startLine.contains("/wait ")) {entered.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Cancelled",e);}}
            return response("ok".getBytes(StandardCharsets.US_ASCII));
        });Socket socket=fixture.connect()) {
            OutputStream out=socket.getOutputStream();out.write(Http2Connection.PREFACE);write(out,4,0,0,new byte[0]);Hpack hpack=new Hpack();
            write(out,1,5,1,hpack.encode(1,requestHeaders("/wait")));assertTrue(entered.await(2,TimeUnit.SECONDS));
            write(out,3,0,1,new byte[]{0,0,0,8});write(out,1,5,3,hpack.encode(3,requestHeaders("/ok")));
            boolean ended=false;for(int i=0;i<20&&!ended;i++){Frame f=read(socket.getInputStream());if(f.stream==3&&(f.flags&1)!=0)ended=true;}
            assertTrue(ended);
        }
    }
    @Test(timeout=10000) public void malformedSettingsAndInterleavedContinuationEmitGoAway() throws Exception {
        try(Fixture fixture=new Fixture((request,a,s)->response(new byte[0]));Socket socket=fixture.connect()) {
            socket.getOutputStream().write(Http2Connection.PREFACE);
            write(socket.getOutputStream(),4,0,0,new byte[]{0,5,0,0,0,1});
            assertEquals(1,awaitGoAway(socket.getInputStream()));
        }
        try(Fixture fixture=new Fixture((request,a,s)->response(new byte[0]));Socket socket=fixture.connect()) {
            OutputStream out=socket.getOutputStream();out.write(Http2Connection.PREFACE);write(out,4,0,0,new byte[0]);
            write(out,1,1,1,new byte[]{(byte)0x82});write(out,6,0,0,new byte[8]);
            assertEquals(1,awaitGoAway(socket.getInputStream()));
        }
    }
    @Test(timeout=10000) public void contentLengthMismatchNeverReachesApplication() throws Exception {
        AtomicInteger dispatches=new AtomicInteger();
        try(Fixture fixture=new Fixture((request,a,s)->{dispatches.incrementAndGet();return response(new byte[0]);});Socket socket=fixture.connect()) {
            OutputStream out=socket.getOutputStream();out.write(Http2Connection.PREFACE);write(out,4,0,0,new byte[0]);
            List<String[]> headers=new java.util.ArrayList<>(requestHeaders("/bad"));headers.add(new String[]{"content-length","1"});
            write(out,1,4,1,new Hpack().encode(1,headers));write(out,0,1,1,new byte[]{1,2});
            Frame f;do{f=read(socket.getInputStream());}while(f.type!=3);
            assertEquals(1,f.stream);assertEquals(1,int32(f.data,0));assertEquals(0,dispatches.get());
        }
    }
    @Test(timeout=10000) public void rejectedStreamStillUpdatesConnectionHpackState() throws Exception {
        AtomicInteger dispatches=new AtomicInteger();
        try(Fixture fixture=new Fixture((request,a,s)->{dispatches.incrementAndGet();return response(new byte[0]);});Socket socket=fixture.connect()) {
            OutputStream out=socket.getOutputStream();out.write(Http2Connection.PREFACE);write(out,4,0,0,new byte[0]);
            Hpack hpack=new Hpack();List<String[]> headers=new java.util.ArrayList<>(requestHeaders("/context"));headers.add(new String[]{"x-test","dynamic-context"});
            byte[] first=hpack.encode(1,headers),priority=new byte[first.length+5];priority[3]=1;System.arraycopy(first,0,priority,5,first.length);
            write(out,1,37,1,priority);write(out,1,5,3,hpack.encode(3,headers));
            boolean reset=false,completed=false;
            for(int i=0;i<20&&!completed;i++){Frame frame=read(socket.getInputStream());if(frame.stream==1&&frame.type==3)reset=true;if(frame.stream==3&&frame.type==1&&(frame.flags&1)!=0)completed=true;assertNotEquals(7,frame.type);}
            assertTrue(reset);assertTrue(completed);assertEquals(1,dispatches.get());
        }
    }
    private static List<String[]> requestHeaders(String path){return Arrays.asList(new String[]{":method","GET"},new String[]{":scheme","http"},new String[]{":authority","fixture.test"},new String[]{":path",path});}
    private static HttpCodec.Message response(byte[] body){HttpCodec.Message m=new HttpCodec.Message();m.startLine="HTTP/1.1 200 OK";m.body=body;m.set("content-length",Integer.toString(body.length));return m;}
    private static int awaitGoAway(InputStream in)throws IOException{for(int i=0;i<10;i++){Frame f=read(in);if(f.type==7)return int32(f.data,4);}throw new IOException("No GOAWAY");}
    private static void write(OutputStream out,int type,int flags,int stream,byte[] bytes)throws IOException {
        byte[] head=new byte[9];head[0]=(byte)(bytes.length>>>16);head[1]=(byte)(bytes.length>>>8);head[2]=(byte)bytes.length;head[3]=(byte)type;head[4]=(byte)flags;
        head[5]=(byte)(stream>>>24);head[6]=(byte)(stream>>>16);head[7]=(byte)(stream>>>8);head[8]=(byte)stream;out.write(head);out.write(bytes);out.flush();
    }
    private static Frame read(InputStream in)throws IOException{byte[] h=bytes(in,9);int length=((h[0]&255)<<16)|((h[1]&255)<<8)|(h[2]&255);return new Frame(h[3]&255,h[4]&255,int32(h,5)&0x7fffffff,bytes(in,length));}
    private static byte[] bytes(InputStream in,int count)throws IOException{byte[] b=new byte[count];int p=0;while(p<count){int n=in.read(b,p,count-p);if(n<0)throw new java.io.EOFException();p+=n;}return b;}
    private static int int32(byte[] b,int p){return ((b[p]&255)<<24)|((b[p+1]&255)<<16)|((b[p+2]&255)<<8)|(b[p+3]&255);}
    private static final class Frame{final int type,flags,stream;final byte[] data;Frame(int type,int flags,int stream,byte[] data){this.type=type;this.flags=flags;this.stream=stream;this.data=data;}}
    private static final class Fixture implements AutoCloseable {
        final ServerSocket server;final ExecutorService handlers=Executors.newFixedThreadPool(2);final CompletableFuture<Void> finished;
        volatile Socket accepted;
        Fixture(Http2Connection.Handler handler)throws IOException{
            server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));
            finished=CompletableFuture.runAsync(()->{try{accepted=server.accept();accepted.setSoTimeout(5000);Http2Connection.serve(new BufferedInputStream(accepted.getInputStream()),accepted.getOutputStream(),accepted,handlers,handler);}
            catch(IOException ignored){}});
        }
        Socket connect()throws IOException{Socket socket=new Socket("127.0.0.1",server.getLocalPort());socket.setSoTimeout(5000);return socket;}
        public void close()throws Exception{server.close();if(accepted!=null)accepted.close();handlers.shutdownNow();finished.get(3,TimeUnit.SECONDS);}
    }
}
