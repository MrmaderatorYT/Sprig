package com.ccs.sprig.core.websocket;

import java.io.ByteArrayOutputStream;
import com.ccs.sprig.core.memory.MemoryBudget;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded WebSocket workspace. Frame readers keep control traffic live while data is held. */
public final class WebSocketHub implements Closeable {
    public static final String CLIENT_TO_SERVER="CLIENT_TO_SERVER",SERVER_TO_CLIENT="SERVER_TO_CLIENT";
    public static final int CAPTURE_BYTES=64*1024,MAX_HISTORY_BYTES=8*1024*1024,MAX_QUEUED_BYTES=2*1024*1024;
    public interface ClientOpener {ClientTransport open(String url,String extraHeaders)throws IOException;}
    public static final class ClientTransport {
        public final String url,requestHeaders,responseHeaders;
        public final Socket socket;
        public final InputStream input;
        public final OutputStream output;
        public final Runnable onClosed;
        public ClientTransport(String url,String requestHeaders,String responseHeaders,Socket socket,InputStream input,OutputStream output,Runnable onClosed){this.url=url;this.requestHeaders=requestHeaders;this.responseHeaders=responseHeaders;this.socket=socket;this.input=input;this.output=output;this.onClosed=onClosed;}
    }
    public static final class Session {
        public final long id,openedMillis;
        public final String url,requestHeaders,responseHeaders;
        public final boolean manual;
        public volatile String state="OPEN",error="";
        public volatile int closeCode;
        public volatile long closedMillis;
        private Session(long id,String url,boolean manual,String request,String response){this.id=id;this.url=bounded(url,8192);this.manual=manual;openedMillis=System.currentTimeMillis();requestHeaders=bounded(request,16384);responseHeaders=bounded(response,16384);}
        private Session(Session source){id=source.id;openedMillis=source.openedMillis;url=source.url;manual=source.manual;requestHeaders=source.requestHeaders;responseHeaders=source.responseHeaders;state=source.state;error=source.error;closeCode=source.closeCode;closedMillis=source.closedMillis;}
    }
    public static final class Message {
        public final long id,sessionId,timeMillis;
        public final String direction,disposition;
        public final int opcode,originalLength;
        public final byte[] payload;
        public final boolean truncated;
        Message(long id,long sessionId,long time,String direction,int opcode,byte[] data,String disposition){this.id=id;this.sessionId=sessionId;timeMillis=time;this.direction=direction;this.opcode=opcode;originalLength=data.length;payload=Arrays.copyOf(data,Math.min(data.length,CAPTURE_BYTES));truncated=data.length>payload.length;this.disposition=disposition;}
    }
    public final class Reservation implements AutoCloseable {
        private final Semaphore permits;private final MemoryBudget.Reservation metadata;private volatile boolean metadataTransferred;
        private final java.util.concurrent.atomic.AtomicInteger state=new java.util.concurrent.atomic.AtomicInteger();
        Reservation(Semaphore permits,MemoryBudget.Reservation metadata){this.permits=permits;this.metadata=metadata;}
        private void claim()throws IOException{if(!state.compareAndSet(0,1))throw new IOException("WebSocket slot already resolved");}
        private void release(){if(state.getAndSet(2)!=2){if(!metadataTransferred)metadata.close();permits.release();synchronized(WebSocketHub.this){activeSlots--;}}}
        @Override public void close(){if(state.compareAndSet(0,2)){metadata.close();permits.release();synchronized(WebSocketHub.this){activeSlots--;}}}
    }
    private final ClientOpener opener;
    private final SecureRandom random=new SecureRandom();
    private final AtomicLong ids=new AtomicLong(),messageIds=new AtomicLong();
    private final LinkedHashMap<Long,Session> sessions=new LinkedHashMap<>();
    private final LinkedHashMap<Long,LiveSession> live=new LinkedHashMap<>();
    private final LinkedHashMap<Long,Message> history=new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Runnable> listeners=new CopyOnWriteArrayList<>();
    private final WebSocketInterceptor interceptor;
    private final MemoryBudget budget;
    private final LinkedHashMap<Long,MemoryBudget.Reservation> historyLeases=new LinkedHashMap<>();
    private final LinkedHashMap<Long,MemoryBudget.Reservation> sessionLeases=new LinkedHashMap<>();
    private long droppedHistory;private volatile boolean disposed;
    private final ThreadPoolExecutor work=new ThreadPoolExecutor(0,24,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(r,"sprig-websocket");t.setDaemon(true);return t;});
    private final ScheduledThreadPoolExecutor deadlines=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"sprig-websocket-deadline");t.setDaemon(true);return t;});
    private Semaphore slots=new Semaphore(4);
    private int activeSlots,historyBytes;
    private final long frameReadMillis;
    public WebSocketHub(ClientOpener opener){this(opener,new MemoryBudget(32L*1024*1024),45000);}
    public WebSocketHub(ClientOpener opener,MemoryBudget budget){this(opener,budget,45000);}
    WebSocketHub(ClientOpener opener,long frameReadMillis){this(opener,new MemoryBudget(32L*1024*1024),frameReadMillis);}
    private WebSocketHub(ClientOpener opener,MemoryBudget budget,long frameReadMillis){if(budget==null)throw new NullPointerException("Memory budget required");this.budget=budget;interceptor=new WebSocketInterceptor(budget);if(frameReadMillis<1)throw new IllegalArgumentException("Positive frame deadline required");this.opener=opener;this.frameReadMillis=frameReadMillis;deadlines.setRemoveOnCancelPolicy(true);interceptor.addListener(this::changed);}
    public synchronized void setMaxSessions(int maximum){if(maximum<1||maximum>4)throw new IllegalArgumentException("Use 1 to 4 WebSocket sessions");if(activeSlots!=0)throw new IllegalStateException("Close WebSocket sessions before changing their budget");slots=new Semaphore(maximum);}
    public synchronized boolean hasActive(){return activeSlots!=0;}
    public synchronized Reservation reserve()throws IOException{
        if(disposed)throw new IOException("WebSocket hub is closed");
        if(!slots.tryAcquire())throw new IOException("WebSocket session budget is busy");
        MemoryBudget.Reservation lease=budget.tryReserve(MemoryBudget.Category.WEBSOCKET_HISTORY,2048+2L*(8193+16385+16385));
        if(lease==null){slots.release();throw new IOException("Shared memory budget is busy: WebSocket session");}
        activeSlots++;return new Reservation(slots,lease);
    }
    public WebSocketInterceptor interceptor(){return interceptor;}
    public synchronized List<Session> sessions(){List<Session> result=new ArrayList<>();for(Session session:sessions.values())result.add(new Session(session));Collections.reverse(result);return result;}
    public synchronized List<Message> messages(long sessionId){List<Message> result=new ArrayList<>();for(Message message:history.values())if(sessionId==0||message.sessionId==sessionId)result.add(message);Collections.reverse(result);return result;}
    public Session connect(String url,String extraHeaders)throws IOException {
        try(Reservation reservation=reserve()){
            ClientTransport connection=opener.open(url,extraHeaders);boolean adopted=false;
            try{Session session=adopt(reservation,connection.url,true,connection.requestHeaders,connection.responseHeaders,null,null,null,connection.input,connection.output,connection.socket,connection.onClosed);adopted=true;return session;}
            finally{if(!adopted){try{connection.socket.close();}catch(IOException ignored){}if(connection.onClosed!=null)connection.onClosed.run();}}
        }
    }
    /** Caller must validate and send the 101 response before starting this ownership handoff. */
    public Session adoptProxy(Reservation reservation,String url,String request,String response,InputStream clientInput,OutputStream clientOutput,Socket client,
                              InputStream originInput,OutputStream originOutput,Socket origin,Runnable onClosed)throws IOException {
        return adopt(reservation,url,false,request,response,clientInput,clientOutput,client,originInput,originOutput,origin,onClosed);
    }
    private Session adopt(Reservation reservation,String url,boolean manual,String request,String response,InputStream clientInput,OutputStream clientOutput,Socket client,
                          InputStream originInput,OutputStream originOutput,Socket origin,Runnable onClosed)throws IOException {
        reservation.claim();
        try {
            if(client!=null)client.setSoTimeout(0);origin.setSoTimeout(0);
            Session info=new Session(ids.incrementAndGet(),url,manual,request,response);reservation.metadata.resize(2048+2L*(info.url.length()+info.requestHeaders.length()+info.responseHeaders.length()));
            LiveSession session=new LiveSession(info,reservation,clientInput,clientOutput,client,originInput,originOutput,origin,onClosed);
            synchronized(this){if(disposed)throw new IOException("WebSocket hub closed during handshake");sessions.put(info.id,info);sessionLeases.put(info.id,reservation.metadata);reservation.metadataTransferred=true;live.put(info.id,session);while(sessions.size()>32){Long oldest=null;for(Session s:sessions.values())if(!live.containsKey(s.id)){oldest=s.id;break;}if(oldest==null)break;sessions.remove(oldest);sessionLeases.remove(oldest).close();}}
            changed();session.start();return new Session(info);
        }catch(IOException|RuntimeException error){reservation.release();if(client!=null)try{client.close();}catch(IOException ignored){}try{origin.close();}catch(IOException ignored){}throw error;}
    }
    public void sendText(long sessionId,String text)throws IOException{send(sessionId,1,text.getBytes(StandardCharsets.UTF_8));}
    public void sendBinary(long sessionId,byte[] payload)throws IOException{send(sessionId,2,payload);}
    private void send(long sessionId,int opcode,byte[] payload)throws IOException{if(payload.length>WebSocketFrames.MAX_MESSAGE_BYTES)throw new IOException("Message exceeds 1 MiB");LiveSession session; synchronized(this){session=live.get(sessionId);}if(session==null)throw new IOException("WebSocket session is closed");session.enqueue(CLIENT_TO_SERVER,opcode,payload,true);}
    public void replay(long targetSession,long messageId)throws IOException{Message message;synchronized(this){message=history.get(messageId);}if(message==null)throw new IOException("Message was evicted");if(message.truncated)throw new IOException("Captured message is truncated; replay is disabled");if(message.opcode!=1&&message.opcode!=2)throw new IOException("Replay supports text and binary messages");send(targetSession,message.opcode,message.payload);}
    public void closeSession(long id){LiveSession session;synchronized(this){session=live.get(id);}if(session!=null)session.beginClose(1000,"Closed by user");}
    public void closeAll(){List<LiveSession> snapshot;synchronized(this){snapshot=new ArrayList<>(live.values());}interceptor.cancelAll();for(LiveSession session:snapshot)session.terminate(1001,"");}
    public void clearHistory(){synchronized(this){for(MemoryBudget.Reservation lease:historyLeases.values())lease.close();historyLeases.clear();history.clear();historyBytes=0;java.util.Iterator<Long> it=sessions.keySet().iterator();while(it.hasNext()){long id=it.next();if(!live.containsKey(id)){it.remove();MemoryBudget.Reservation lease=sessionLeases.remove(id);if(lease!=null)lease.close();}}}changed();}
    public void addListener(Runnable listener){listeners.addIfAbsent(listener);}
    public void removeListener(Runnable listener){listeners.remove(listener);}
    private void changed(){for(Runnable listener:listeners)try{listener.run();}catch(RuntimeException ignored){}}
    public synchronized long droppedHistoryMessages(){return droppedHistory;}
    private void evictHistory(){long id=history.keySet().iterator().next();Message oldest=history.remove(id);historyBytes-=oldest.payload.length+256;historyLeases.remove(id).close();}
    private void record(long session,long time,String direction,int opcode,byte[] payload,String disposition){
        synchronized(this){
            if(disposed)return;long size=Math.min(payload.length,CAPTURE_BYTES)+256L;
            while(!history.isEmpty()&&(history.size()>=512||size>MAX_HISTORY_BYTES-historyBytes))evictHistory();
            MemoryBudget.Reservation lease=budget.tryReserve(MemoryBudget.Category.WEBSOCKET_HISTORY,size);
            while(lease==null&&!history.isEmpty()){evictHistory();lease=budget.tryReserve(MemoryBudget.Category.WEBSOCKET_HISTORY,size);}
            if(lease==null)droppedHistory++;
            else try{Message message=new Message(messageIds.incrementAndGet(),session,time,direction,opcode,payload,disposition);history.put(message.id,message);historyLeases.put(message.id,lease);historyBytes+=size;}
            catch(RuntimeException|Error failure){lease.close();throw failure;}
        }changed();
    }
    @Override public void close(){disposed=true;closeAll();clearHistory();work.shutdownNow();deadlines.shutdownNow();}

    private final class LiveSession {
        final Session info;
        final Reservation reservation;
        final Endpoint client,origin;
        final Runnable onClosed;
        final ArrayBlockingQueue<Data> toOrigin=new ArrayBlockingQueue<>(8),toClient=new ArrayBlockingQueue<>(8);
        final CopyOnWriteArrayList<FutureTask<Void>> tasks=new CopyOnWriteArrayList<>();
        final AtomicBoolean closed=new AtomicBoolean();
        volatile ScheduledFuture<?> closeDeadline;
        volatile boolean userClosing;
        int queuedBytes;
        LiveSession(Session info,Reservation reservation,InputStream clientInput,OutputStream clientOutput,Socket client,
                    InputStream originInput,OutputStream originOutput,Socket origin,Runnable onClosed){this.info=info;this.reservation=reservation;this.onClosed=onClosed;this.client=client==null?null:new Endpoint(clientInput,clientOutput,client,true,false);this.origin=new Endpoint(originInput,originOutput,origin,false,true);}
        void start(){try{submit(()->readLoop(origin,SERVER_TO_CLIENT,client));submit(()->deliver(toOrigin,origin,CLIENT_TO_SERVER));submit(()->deliver(toClient,client,SERVER_TO_CLIENT));if(client!=null)submit(()->readLoop(client,CLIENT_TO_SERVER,origin));}catch(RejectedExecutionException error){terminate(1013,"WebSocket worker budget is busy");}}
        void submit(Runnable task){FutureTask<Void> future=new FutureTask<>(()->{task.run();return null;});tasks.add(future);work.execute(future);}
        void enqueue(String direction,int opcode,byte[] payload,boolean injected)throws IOException {
            synchronized(this){if(closed.get()||userClosing||injected&&!info.state.equals("OPEN")){if(!injected){record(info.id,System.currentTimeMillis(),direction,opcode,payload,"Dropped during close");return;}throw new IOException("WebSocket is closing");}if(queuedBytes+payload.length>MAX_QUEUED_BYTES)throw new IOException("WebSocket message queue exceeds 2 MiB");MemoryBudget.Reservation lease=budget.tryReserve(MemoryBudget.Category.WEBSOCKET_QUEUE,payload.length+128L);if(lease==null)throw new IOException("Shared memory budget is busy: WebSocket delivery");Data data;try{data=new Data(opcode,injected?payload.clone():payload,injected,lease);}catch(RuntimeException|Error failure){lease.close();throw failure;}ArrayBlockingQueue<Data> queue=direction.equals(CLIENT_TO_SERVER)?toOrigin:toClient;if(!queue.offer(data)){lease.close();throw new IOException("WebSocket message queue is full");}queuedBytes+=payload.length;}
        }
        void readLoop(Endpoint source,String direction,Endpoint destination){int fragmented=-1;ByteArrayOutputStream fragments=null;
            try {
                while(!closed.get()){
                    WebSocketFrames.Frame frame=source.readFrame();
                    if(frame==null){if(!source.closeSeen)terminate(1006,"Peer disconnected without a WebSocket close frame");return;}
                    if(frame.opcode>=8){
                        if(frame.opcode==8){source.closeSeen=true;info.state="CLOSING";interceptor.cancelSession(info.id);
                            ArrayBlockingQueue<Data> queue=direction.equals(CLIENT_TO_SERVER)?toOrigin:toClient;
                            try{if(!queue.offer(new Data(8,frame.payload,false),5,TimeUnit.SECONDS))throw new IOException("WebSocket close barrier queue timed out");}
                            catch(InterruptedException stopped){Thread.currentThread().interrupt();return;}
                            changed();return;}
                        if(destination!=null)destination.control(frame.opcode,frame.payload);else if(frame.opcode==9)source.control(10,frame.payload);
                        record(info.id,System.currentTimeMillis(),direction,frame.opcode,frame.payload,frame.opcode==9?"Ping":"Pong");continue;
                    }
                    if(userClosing){record(info.id,System.currentTimeMillis(),direction,frame.opcode,frame.payload,"Dropped during close");continue;}
                    if(frame.opcode==0){if(fragmented<0)throw new WebSocketFrames.ProtocolException(1002,"Unexpected WebSocket continuation");}
                    else if(fragmented>=0)throw new WebSocketFrames.ProtocolException(1002,"New data opcode inside a fragmented message");
                    if(fragmented<0&&frame.fin){if(frame.opcode==1)WebSocketFrames.validateText(frame.payload);enqueue(direction,frame.opcode,frame.payload,false);}
                    else {
                        if(fragmented<0){fragmented=frame.opcode;fragments=new ByteArrayOutputStream();}
                        if(fragments.size()+frame.payload.length>WebSocketFrames.MAX_MESSAGE_BYTES)throw new WebSocketFrames.ProtocolException(1009,"Fragmented WebSocket message exceeds 1 MiB");
                        fragments.write(frame.payload);
                        if(frame.fin){byte[] message=fragments.toByteArray();if(fragmented==1)WebSocketFrames.validateText(message);enqueue(direction,fragmented,message,false);fragmented=-1;fragments=null;}
                    }
                }
            }catch(WebSocketFrames.ProtocolException violation){fail(violation.closeCode,violation.getMessage());}
            catch(IOException|RuntimeException error){if(!closed.get())fail(1008,message(error));}
        }
        void deliver(ArrayBlockingQueue<Data> queue,Endpoint destination,String direction){
            try{while(!closed.get()){Data data=queue.take();try{
                if(data.opcode==8){
                    Endpoint source=direction.equals(CLIENT_TO_SERVER)?client:origin;
                    if(destination!=null)destination.control(8,data.payload);else source.control(8,data.payload);
                    source.closeForwarded=true;record(info.id,data.time,direction,8,data.payload,"Close");
                    int code=data.payload.length>=2?((data.payload[0]&255)<<8)|(data.payload[1]&255):1000;
                    if(client==null||client.closeForwarded&&origin.closeForwarded)terminate(code,"");else closeLater(code,"");continue;
                }
                if(userClosing||!info.state.equals("OPEN")&&interceptor.isEnabled()){record(info.id,data.time,direction,data.opcode,data.payload,"Dropped during close");continue;}
                try(WebSocketInterceptor.Decision decision=interceptor.interceptLeased(info.id,direction,data.opcode,data.payload,()->info.state.equals("OPEN")&&!closed.get())){byte[] forwarded=decision.payload;
                if(forwarded==null||closed.get()||userClosing){record(info.id,data.time,direction,data.opcode,data.payload,"Dropped");continue;}
                if(data.opcode==1)WebSocketFrames.validateText(forwarded);
                if(destination!=null)try{destination.data(data.opcode,forwarded);}catch(IOException failure){if(destination.closeSent&&!info.state.equals("OPEN")){record(info.id,data.time,direction,data.opcode,data.payload,"Dropped during close");continue;}throw failure;}
                String state=Arrays.equals(data.payload,forwarded)?data.injected?"Injected":destination==null?"Received":"Forwarded":"Edited";
                record(info.id,data.time,direction,data.opcode,forwarded,state);}
            }finally{if(data.opcode!=8){data.close();synchronized(this){queuedBytes-=data.payload.length;}}}}}
            catch(InterruptedException stopped){Thread.currentThread().interrupt();}
            catch(IOException|RuntimeException error){if(!closed.get())fail(1008,message(error));}
        }
        void beginClose(int code,String reason){if(closed.get())return;userClosing=true;info.state="CLOSING";interceptor.cancelSession(info.id);closeLater(code,"");try{submit(()->{byte[] payload=WebSocketFrames.closePayload(code,reason);try{origin.control(8,payload);if(client!=null)client.control(8,payload);}catch(IOException error){terminate(code,message(error));}});}catch(RejectedExecutionException busy){terminate(code,reason);}changed();}
        void fail(int code,String reason){if(closed.get())return;info.state="CLOSING";info.error=bounded(reason,512);interceptor.cancelSession(info.id);try{byte[] payload=WebSocketFrames.closePayload(code==1006?1001:code,"Sprig protocol error");origin.control(8,payload);if(client!=null)client.control(8,payload);}catch(IOException ignored){}finally{terminate(code,reason);}}
        synchronized void closeLater(int code,String error){if(closeDeadline==null)closeDeadline=deadlines.schedule(()->terminate(code,error),5,TimeUnit.SECONDS);}
        void terminate(int code,String error){if(!closed.compareAndSet(false,true))return;info.state=error.isEmpty()?"CLOSED":"ERROR";info.closeCode=code;info.closedMillis=System.currentTimeMillis();if(!error.isEmpty())info.error=bounded(error,512);ScheduledFuture<?> deadline=closeDeadline;if(deadline!=null)deadline.cancel(false);interceptor.cancelSession(info.id);closeSocket(origin.socket);if(client!=null)closeSocket(client.socket);for(FutureTask<Void> task:tasks)task.cancel(true);synchronized(this){drain(toOrigin);drain(toClient);}synchronized(WebSocketHub.this){live.remove(info.id);}reservation.release();if(onClosed!=null)try{onClosed.run();}catch(RuntimeException ignored){}changed();}
        final class Endpoint {
            final InputStream input;final OutputStream output;final Socket socket;final boolean expectMask,maskOutput;
            final Object frames=new Object(),messages=new Object();volatile boolean closeSeen,closeSent,closeForwarded;
            Endpoint(InputStream input,OutputStream output,Socket socket,boolean expectMask,boolean maskOutput){this.input=input;this.output=output;this.socket=socket;this.expectMask=expectMask;this.maskOutput=maskOutput;}
            WebSocketFrames.Frame readFrame()throws IOException{int first=input.read();if(first<0)return null;ScheduledFuture<?> timeout=deadlines.schedule(()->terminate(1006,"WebSocket frame read timed out"),frameReadMillis,TimeUnit.MILLISECONDS);try{return WebSocketFrames.readFollowing(input,expectMask,first);}finally{timeout.cancel(false);}}
            void control(int opcode,byte[] payload)throws IOException{ScheduledFuture<?> timeout=deadlines.schedule(()->terminate(1006,"WebSocket control write timed out"),5,TimeUnit.SECONDS);try{if(opcode==8)synchronized(messages){controlFrame(opcode,payload);}else controlFrame(opcode,payload);}finally{timeout.cancel(false);}}
            void controlFrame(int opcode,byte[] payload)throws IOException{synchronized(frames){if(closed.get())throw new IOException("WebSocket session closed");if(opcode==8){if(closeSent)return;closeSent=true;}WebSocketFrames.write(output,maskOutput,true,opcode,payload,random);}}
            void data(int opcode,byte[] payload)throws IOException{ScheduledFuture<?> timeout=deadlines.schedule(()->terminate(1006,"WebSocket data write timed out"),30,TimeUnit.SECONDS);try{synchronized(messages){int offset=0;do{int count=Math.min(16384,payload.length-offset);synchronized(frames){if(closed.get()||closeSent)throw new IOException("WebSocket session closing");WebSocketFrames.write(output,maskOutput,offset+count==payload.length,offset==0?opcode:0,payload,offset,count,random);}offset+=count;}while(offset<payload.length);}}finally{timeout.cancel(false);}}
        }
    }
    private static void drain(ArrayBlockingQueue<Data> queue){Data item;while((item=queue.poll())!=null)item.close();}
    private static final class Data implements AutoCloseable {final int opcode;final byte[] payload;final boolean injected;final MemoryBudget.Reservation lease;final long time=System.currentTimeMillis();Data(int opcode,byte[] payload,boolean injected){this(opcode,payload,injected,null);}Data(int opcode,byte[] payload,boolean injected,MemoryBudget.Reservation lease){this.opcode=opcode;this.payload=payload;this.injected=injected;this.lease=lease;}public void close(){if(lease!=null)lease.close();}}
    private static String bounded(String value,int length){if(value==null)return "";return value.length()>length?value.substring(0,length)+"…":value;}
    private static String message(Throwable error){return error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();}
    private static void closeSocket(Socket socket){try{socket.close();}catch(IOException ignored){}}
}
