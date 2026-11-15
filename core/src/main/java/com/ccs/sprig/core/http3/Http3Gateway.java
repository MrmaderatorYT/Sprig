package com.ccs.sprig.core.http3;

import com.ccs.sprig.core.proxy.ProxyServer;
import com.ccs.sprig.core.tunnel.IpPacket;
import com.ccs.sprig.core.tunnel.TunEngine;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded TUN UDP gateway: inspect H3 with a local QUIC endpoint; out-of-scope QUIC stays encrypted end to end. */
public final class Http3Gateway implements TunEngine.DatagramHandler,Closeable {
    private final ProxyServer proxy;
    private final Http3Client.DatagramProtector protector;
    private final int maxFlows;
    private final Selector selector;
    private final ExecutorService setup=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"sprig-h3-setup");t.setDaemon(true);return t;});
    private final ConcurrentLinkedQueue<Runnable> changes=new ConcurrentLinkedQueue<>();
    private final Map<String,Flow> flows=new HashMap<>();
    private final AtomicBoolean running=new AtomicBoolean(true);
    private final Thread worker;
    public Http3Gateway(ProxyServer proxy,Http3Client.DatagramProtector protector,int maxFlows) throws IOException {
        Http3Crypto.requireAvailable();
        this.proxy=proxy;this.protector=protector;this.maxFlows=Math.max(1,Math.min(4,maxFlows));selector=Selector.open();
        worker=new Thread(this::loop,"sprig-h3-udp");worker.setDaemon(true);worker.start();
    }
    @Override public boolean handle(IpPacket packet,TunEngine.DatagramResponse response) {
        if(!running.get()||packet.protocol!=IpPacket.UDP)return false;
        String key=packet.source+":"+packet.sourcePort+":"+packet.destination+":"+packet.destinationPort;
        synchronized(flows) {
            Flow flow=flows.get(key);
            if(flow==null) {
                if(!InitialSni.isInitial(packet.payload)||flows.size()>=maxFlows)return false;
                flow=new Flow(key,packet,response);flows.put(key,flow);
            }
            if(!flow.queue.offer(packet.payload))return true;
            if(flow.processScheduled.compareAndSet(false,true))enqueue(flow::process);
            return true;
        }
    }
    private void enqueue(Runnable change) {
        if(!running.get()){change.run();return;}
        changes.add(change);
        if(!running.get()&&changes.remove(change))change.run();
        else selector.wakeup();
    }
    public int activeFlows(){synchronized(flows){return flows.size();}}
    private void loop() {
        ByteBuffer receive=ByteBuffer.allocate(1500);
        try {
            while(running.get()) {
                selector.select(1000);Runnable change;while((change=changes.poll())!=null)change.run();
                Iterator<SelectionKey> iterator=selector.selectedKeys().iterator();
                while(iterator.hasNext()) {
                    SelectionKey key=iterator.next();iterator.remove();if(!key.isValid())continue;
                    Flow flow=(Flow)key.attachment();
                    try {
                        if(key.isWritable())flow.flush();
                        if(key.isReadable()) {
                            receive.clear();int size=flow.channel.read(receive);
                            if(size>0&&size<=1472) {
                                byte[] payload=new byte[size];System.arraycopy(receive.array(),0,payload,0,size);
                                flow.touched=System.nanoTime();flow.response.send(IpPacket.udp(flow.packet.destination,flow.packet.source,flow.packet.destinationPort,flow.packet.sourcePort,payload));
                            }
                        }
                    }catch(IOException|RuntimeException error){flow.close();}
                }
                long now=System.nanoTime();ArrayList<Flow> snapshot;
                synchronized(flows){snapshot=new ArrayList<>(flows.values());}
                for(Flow flow:snapshot)if(now-flow.touched>Time.SECONDS_30||flow.channel==null&&now-flow.created>Time.SECONDS_12)flow.close();
            }
        }catch(IOException ignored) { }
        finally {running.set(false);ArrayList<Flow> snapshot;synchronized(flows){snapshot=new ArrayList<>(flows.values());}for(Flow flow:snapshot)flow.close();Runnable change;while((change=changes.poll())!=null)change.run();try{selector.close();}catch(IOException ignored){}}
    }
    @Override public void close(){if(!running.getAndSet(false))return;selector.wakeup();setup.shutdownNow();if(Thread.currentThread()!=worker)try{worker.join(1500);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    private final class Flow {
        final String key;final IpPacket packet;final TunEngine.DatagramResponse response;
        final InitialSni hello=new InitialSni();final ArrayBlockingQueue<byte[]> queue=new ArrayBlockingQueue<>(32);
        final AtomicBoolean processScheduled=new AtomicBoolean();
        final ArrayDeque<byte[]> pending=new ArrayDeque<>();final long created=System.nanoTime();
        volatile long touched=created;volatile DatagramChannel channel;volatile Http3Server endpoint;volatile boolean closed,initializing;
        int pendingBytes;SelectionKey selection;
        Flow(String key,IpPacket packet,TunEngine.DatagramResponse response){this.key=key;this.packet=packet;this.response=response;}
        void process() {
            if(closed||!running.get()){processScheduled.set(false);return;}
            try {
                byte[] bytes;while((bytes=queue.poll())!=null) {
                    touched=System.nanoTime();
                    if(pendingBytes+bytes.length>64*1024)throw new IOException("HTTP/3 UDP queue limit");
                    pending.add(bytes);pendingBytes+=bytes.length;
                    if(!hello.complete)hello.feed(bytes);
                }
                if(channel!=null)flush();
                else if(hello.complete&&!initializing){initializing=true;setup.execute(this::initialize);}
            }catch(IOException|RuntimeException error){close();}
            finally {processScheduled.set(false);if(!closed&&!queue.isEmpty()&&processScheduled.compareAndSet(false,true))enqueue(this::process);}
        }
        void initialize() {
            DatagramChannel opened=null;Http3Server server=null;
            try {
                String host=hello.host==null?IpPacket.address(packet.destination):hello.host;
                InetSocketAddress destination;
                if(hello.http3&&proxy.shouldInspectHost(host)) {
                    server=new Http3Server(proxy.getAuthority().serverKeyStore(host),request->{
                        java.net.URI authority=java.net.URI.create("https://"+request.authority);
                        if(authority.getHost()==null||!authority.getHost().equalsIgnoreCase(host)||authority.getPort()>0&&authority.getPort()!=packet.destinationPort)
                            throw new IOException("HTTP/3 authority cannot escape its QUIC destination");
                        try{return proxy.dispatchHttpMessage(request.message,host,IpPacket.address(packet.destination),packet.destinationPort,true,"HTTP/3");}
                        catch(IOException error){
                            com.ccs.sprig.core.proxy.HttpCodec.Message failed=new com.ccs.sprig.core.proxy.HttpCodec.Message();
                            failed.startLine="HTTP/1.1 502 Bad Gateway";failed.body="Sprig: upstream request failed\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            failed.set("Content-Type","text/plain; charset=utf-8");failed.set("Content-Length",Integer.toString(failed.body.length));return failed;
                        }
                    });
                    destination=new InetSocketAddress("127.0.0.1",server.getPort());
                } else destination=new InetSocketAddress(IpPacket.address(packet.destination),packet.destinationPort);
                opened=DatagramChannel.open();opened.configureBlocking(false);opened.socket().bind(null);
                if(protector!=null&&!protector.protect(opened.socket()))throw new IOException("Cannot protect QUIC gateway socket");
                opened.connect(destination);
                DatagramChannel ready=opened;Http3Server readyServer=server;
                enqueue(()->{
                    if(closed||!running.get()){try{ready.close();}catch(IOException ignored){}if(readyServer!=null)readyServer.close();return;}
                    channel=ready;endpoint=readyServer;
                    try{selection=ready.register(selector,SelectionKey.OP_READ,this);flush();}
                    catch(IOException|RuntimeException error){close();}
                });
            }catch(Exception error){if(opened!=null)try{opened.close();}catch(IOException ignored){}if(server!=null)server.close();enqueue(this::close);}
        }
        void flush() throws IOException {
            if(channel==null||closed)return;
            while(!pending.isEmpty()) {
                byte[] bytes=pending.peek();if(channel.write(ByteBuffer.wrap(bytes))==0){selection.interestOps(SelectionKey.OP_READ|SelectionKey.OP_WRITE);return;}
                pending.remove();pendingBytes-=bytes.length;
            }
            selection.interestOps(SelectionKey.OP_READ);
        }
        synchronized void close(){if(closed)return;closed=true;synchronized(flows){flows.remove(key,this);}if(selection!=null)selection.cancel();if(channel!=null)try{channel.close();}catch(IOException ignored){}if(endpoint!=null)endpoint.close();queue.clear();pending.clear();}
    }
    private static final class Time{static final long SECONDS_30=30_000_000_000L,SECONDS_12=12_000_000_000L;}
}
