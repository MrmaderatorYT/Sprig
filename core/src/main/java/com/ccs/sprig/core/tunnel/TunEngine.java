package com.ccs.sprig.core.tunnel;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded IPv4 userspace bridge. A single selector owns all network channels; the TUN reader
 * only parses and enqueues packets. TCP acknowledges bytes after reserving bounded queue space,
 * honours advertised windows and retries unacknowledged output. It intentionally does not
 * implement IPv6, IP reassembly, or TCP urgent data. Optional QUIC interception uses DatagramHandler.
 */
public final class TunEngine implements Closeable {
    public interface SocketProtector {
        boolean protect(Socket socket);
        boolean protect(DatagramSocket socket);
    }
    public interface Listener { void onFatalError(Exception error); }
    public interface DatagramResponse {void send(byte[] packet) throws IOException;}
    public interface DatagramHandler {boolean handle(IpPacket packet,DatagramResponse response);}
    private static final int MAX_TCP=96, MAX_UDP=128, RECEIVE_WINDOW=32768, SEND_WINDOW=32768;
    private static final byte[] EMPTY=new byte[0];
    private final InputStream input;
    private final OutputStream output;
    private final SocketProtector protector;
    private final int proxyPort;
    private final boolean blockQuic;
    private final Listener listener;
    private final Selector selector;
    private final AtomicBoolean running=new AtomicBoolean();
    private final AtomicBoolean started=new AtomicBoolean();
    private final AtomicLong up=new AtomicLong(), down=new AtomicLong();
    private final ArrayBlockingQueue<IpPacket> packets=new ArrayBlockingQueue<>(512);
    private final Map<Key,TcpFlow> tcp=new HashMap<>();
    private final Map<Key,UdpFlow> udp=new HashMap<>();
    private final SecureRandom random=new SecureRandom();
    private volatile int tcpCount,udpCount;
    private Thread ioThread,readerThread;
    private DatagramHandler datagramHandler;

    public TunEngine(InputStream input,OutputStream output,SocketProtector protector,int proxyPort,
                     boolean blockQuic,Listener listener) throws IOException {
        this.input=input; this.output=output; this.protector=protector; this.proxyPort=proxyPort;
        this.blockQuic=blockQuic; this.listener=listener; selector=Selector.open();
    }
    public void start() {
        if(!started.compareAndSet(false,true)) throw new IllegalStateException("Already started");
        running.set(true);
        ioThread=new Thread(this::runLoop,"sprig-network");
        readerThread=new Thread(this::readTun,"sprig-tun");
        ioThread.start(); readerThread.start();
    }
    public void setDatagramHandler(DatagramHandler handler) {
        if(started.get())throw new IllegalStateException("Configure UDP interception before starting the tunnel");
        datagramHandler=handler;
    }
    public int activeTcpFlows() { return tcpCount; }
    public int activeUdpFlows() { return udpCount; }
    public long bytesUp() { return up.get(); }
    public long bytesDown() { return down.get(); }

    private void readTun() {
        byte[] buffer=new byte[65535];
        try {
            while(running.get()) {
                int count=input.read(buffer);
                if(count<0) { if(running.get()) fail(new IOException("VPN interface closed")); break; }
                if(count==0) continue;
                up.addAndGet(count);
                IpPacket packet=IpPacket.parse(buffer,count);
                if(packet!=null && packets.offer(packet,100,TimeUnit.MILLISECONDS)) selector.wakeup();
                // Queue saturation drops packets; TCP retransmission supplies backpressure.
            }
        } catch(Exception error) { if(running.get()) fail(error); }
    }
    private void runLoop() {
        try {
            long lastSweep=0;
            while(running.get()) {
                selector.select(100);
                IpPacket packet;
                int processed=0;
                while(processed++<512 && (packet=packets.poll())!=null) handle(packet);
                Iterator<SelectionKey> iterator=selector.selectedKeys().iterator();
                while(iterator.hasNext()) {
                    SelectionKey key=iterator.next(); iterator.remove();
                    if(!key.isValid()) continue;
                    Object flow=key.attachment();
                    try {
                        if(flow instanceof TcpFlow) ((TcpFlow)flow).selected(key);
                        else ((UdpFlow)flow).selected(key);
                    } catch(IOException|RuntimeException failure) {
                        if(flow instanceof TcpFlow) ((TcpFlow)flow).abort(true);
                        else ((UdpFlow)flow).close();
                    }
                }
                long now=now();
                if(now-lastSweep>=200) {
                    lastSweep=now;
                    for(TcpFlow flow:new ArrayList<>(tcp.values())) flow.tick(now);
                    for(UdpFlow flow:new ArrayList<>(udp.values())) if(now-flow.touched>30000) flow.close();
                }
            }
        } catch(Exception error) { if(running.get()) fail(error); }
        finally {
            running.set(false);
            for(TcpFlow flow:new ArrayList<>(tcp.values())) flow.abort(false);
            for(UdpFlow flow:new ArrayList<>(udp.values())) flow.close();
            try { selector.close(); } catch(IOException ignored) { }
            packets.clear();
        }
    }
    private void handle(IpPacket packet) throws IOException {
        Key key=new Key(packet);
        if(packet.protocol==IpPacket.TCP) {
            TcpFlow flow=tcp.get(key);
            if(flow==null) {
                if((packet.flags&IpPacket.RST)!=0) return;
                if((packet.flags&IpPacket.SYN)==0 || (packet.flags&IpPacket.ACK)!=0 || tcp.size()>=MAX_TCP) {
                    reset(packet); return;
                }
                try { flow=new TcpFlow(key,packet); tcp.put(key,flow); tcpCount=tcp.size(); }
                catch(IOException error) { reset(packet); }
            } else flow.receive(packet);
        } else {
            if(datagramHandler!=null&&datagramHandler.handle(packet,this::emit))return;
            if((blockQuic && packet.destinationPort==443) || packet.payload.length>1472) {
                emit(packet.unreachable()); return;
            }
            UdpFlow flow=udp.get(key);
            if(flow==null) {
                if(udp.size()>=MAX_UDP) { emit(packet.unreachable()); return; }
                try { flow=new UdpFlow(key); udp.put(key,flow); udpCount=udp.size(); }
                catch(IOException error) { emit(packet.unreachable()); return; }
            }
            try { flow.send(packet.payload); } catch(IOException error) { flow.close(); emit(packet.unreachable()); }
        }
    }
    private synchronized void emit(byte[] packet) throws IOException {if(!running.get())return;output.write(packet);down.addAndGet(packet.length);}
    private void reset(IpPacket packet) throws IOException {
        boolean ack=(packet.flags&IpPacket.ACK)!=0;
        long ackNumber=plus(packet.sequence,packet.payload.length+((packet.flags&IpPacket.SYN)!=0 ? 1:0)+((packet.flags&IpPacket.FIN)!=0 ? 1:0));
        emit(IpPacket.tcp(packet.destination,packet.source,packet.destinationPort,packet.sourcePort,
                ack ? packet.acknowledgement:0,ack ? 0:ackNumber,IpPacket.RST|(ack ? 0:IpPacket.ACK),0,EMPTY));
    }
    private static long plus(long n,long amount) { return (n+amount)&0xffffffffL; }
    private static boolean before(long a,long b) { return (int)(a-b)<0; }
    private static long now() { return System.nanoTime()/1000000L; }
    private void fail(Exception error) {
        if(running.compareAndSet(true,false)) {
            selector.wakeup();
            if(listener!=null) listener.onFatalError(error);
        }
    }
    @Override public void close() {
        running.set(false); selector.wakeup();
        if(datagramHandler instanceof Closeable)try{((Closeable)datagramHandler).close();}catch(IOException ignored){}
        try { input.close(); } catch(IOException ignored) { }
        if(readerThread!=null) readerThread.interrupt();
        if(ioThread!=null && ioThread!=Thread.currentThread()) {
            try { ioThread.join(1500); } catch(InterruptedException error) { Thread.currentThread().interrupt(); }
        }
        if(ioThread==null) try { selector.close(); } catch(IOException ignored) { }
        try { output.close(); } catch(IOException ignored) { }
    }

    private final class TcpFlow {
        final Key key;
        final SocketChannel channel;
        final SelectionKey selection;
        final ArrayDeque<ByteBuffer> toNetwork=new ArrayDeque<>();
        final ArrayDeque<Segment> unacknowledged=new ArrayDeque<>();
        final ByteBuffer networkRead=ByteBuffer.allocate(1200);
        final int mss;
        long clientNext,serverNext,serverAck,touched=now(),created=touched;
        long closedAt,probeAt;
        int queuedBytes,peerWindow,duplicateAcks;
        boolean connected,ready,handshakeSent,clientFin,remoteFin,outputShutdown,disposed;
        final boolean proxied;
        ByteBuffer proxyRequest;
        ByteArrayOutputStream proxyResponse;

        TcpFlow(Key key,IpPacket syn) throws IOException {
            this.key=key; mss=Math.min(syn.mss,1200); clientNext=plus(syn.sequence,1);
            serverNext=random.nextInt()&0xffffffffL; serverAck=serverNext; peerWindow=syn.window;
            proxied=proxyPort>0 && (key.destinationPort==80 || key.destinationPort==443 || key.destinationPort==8080 || key.destinationPort==8443);
            channel=SocketChannel.open();
            SelectionKey registered=null;
            try {
                channel.configureBlocking(false);
                channel.socket().setTcpNoDelay(true); channel.socket().setSendBufferSize(32768); channel.socket().setReceiveBufferSize(32768);
                if(!protector.protect(channel.socket())) throw new IOException("Could not protect TCP socket");
                connected=channel.connect(new InetSocketAddress(proxied ? "127.0.0.1":IpPacket.address(key.destination),proxied ? proxyPort:key.destinationPort));
                registered=channel.register(selector,connected ? SelectionKey.OP_WRITE:SelectionKey.OP_CONNECT,this);
            } catch(IOException|RuntimeException error) { channel.close(); throw error; }
            selection=registered;
            if(connected) onConnected();
        }
        void onConnected() throws IOException {
            connected=true;
            if(proxied) {
                String authority=IpPacket.address(key.destination)+":"+key.destinationPort;
                proxyRequest=ByteBuffer.wrap(("CONNECT "+authority+" HTTP/1.1\r\nHost: "+authority+"\r\nX-Sprig-Transparent: 1\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                proxyResponse=new ByteArrayOutputStream();
            } else establish();
            updateOps();
        }
        void establish() throws IOException {
            ready=true;
            sendTracked(IpPacket.SYN|IpPacket.ACK,EMPTY);
        }
        void selected(SelectionKey selected) throws IOException {
            if(selected.isConnectable()) { if(channel.finishConnect()) onConnected(); }
            if(!selected.isValid()) return;
            if(selected.isWritable()) writeNetwork();
            if(selected.isValid() && selected.isReadable()) readNetwork();
        }
        void writeNetwork() throws IOException {
            if(!ready) {
                if(proxyRequest!=null && proxyRequest.hasRemaining()) channel.write(proxyRequest);
                updateOps(); return;
            }
            int budget=65536;
            while(!toNetwork.isEmpty() && budget>0) {
                ByteBuffer data=toNetwork.peek(); int wrote=channel.write(data);
                queuedBytes-=wrote; budget-=wrote;
                if(!data.hasRemaining()) toNetwork.remove();
                if(wrote==0) break;
            }
            if(clientFin && toNetwork.isEmpty() && !outputShutdown) { channel.socket().shutdownOutput(); outputShutdown=true; }
            if(queuedBytes<RECEIVE_WINDOW/2) sendAck();
            updateOps();
        }
        void readNetwork() throws IOException {
            if(!ready) {
                ByteBuffer data=networkRead; data.clear();
                int count=channel.read(data);
                if(count<0) throw new IOException("Proxy closed CONNECT");
                if(count==0) return;
                if(proxyResponse.size()+count>8192) throw new IOException("Oversized CONNECT response");
                proxyResponse.write(data.array(),0,count);
                String response=new String(proxyResponse.toByteArray(),StandardCharsets.ISO_8859_1);
                int end=response.indexOf("\r\n\r\n");
                if(end>=0) {
                    String first=response.substring(0,response.indexOf("\r\n"));
                    if(!first.matches("HTTP/1\\.[01] 200(?: .*)?")) throw new IOException("Proxy rejected CONNECT");
                    if(end+4!=response.length()) throw new IOException("Unexpected CONNECT response body");
                    proxyRequest=null; proxyResponse=null; establish(); updateOps();
                }
                return;
            }
            int allowance=sendAllowance();
            if(allowance<=0 || !handshakeSent) { updateOps(); return; }
            ByteBuffer data=networkRead; data.clear(); data.limit(Math.min(mss,allowance));
            int count=channel.read(data);
            if(count<0) {
                remoteFin=true; sendTracked(IpPacket.FIN|IpPacket.ACK,EMPTY); updateOps(); return;
            }
            if(count>0) {
                byte[] payload=new byte[count]; System.arraycopy(data.array(),0,payload,0,count);
                touched=now(); sendTracked(IpPacket.PSH|IpPacket.ACK,payload);
            }
            updateOps();
        }
        void receive(IpPacket packet) throws IOException {
            touched=now();
            if((packet.flags&IpPacket.RST)!=0) { abort(false); return; }
            if((packet.flags&IpPacket.SYN)!=0) {
                if(ready && !unacknowledged.isEmpty()) unacknowledged.peek().transmit();
                return;
            }
            if(!ready) return;
            if((packet.flags&IpPacket.ACK)==0) return;
            long ack=packet.acknowledgement;
            if(before(serverNext,ack)) { sendAck(); return; }
            peerWindow=packet.window;
            if(before(serverAck,ack)) {
                serverAck=ack; duplicateAcks=0;
                while(!unacknowledged.isEmpty() && !before(ack,unacknowledged.peek().end())) unacknowledged.remove();
                handshakeSent=true;
            } else if(ack==serverAck && packet.payload.length==0 && ++duplicateAcks==3 && !unacknowledged.isEmpty()) {
                unacknowledged.peek().transmit();
            }
            if(packet.payload.length>0 || (packet.flags&IpPacket.FIN)!=0) {
                if(packet.sequence!=clientNext || clientFin) { sendAck(); updateOps(); return; }
                if(packet.payload.length>RECEIVE_WINDOW-queuedBytes) { sendAck(); updateOps(); return; }
                if(packet.payload.length>0) {
                    toNetwork.add(ByteBuffer.wrap(packet.payload)); queuedBytes+=packet.payload.length;
                    clientNext=plus(clientNext,packet.payload.length);
                }
                if((packet.flags&IpPacket.FIN)!=0) { clientFin=true; clientNext=plus(clientNext,1); }
                sendAck();
                if(clientFin && toNetwork.isEmpty() && !outputShutdown) { channel.socket().shutdownOutput(); outputShutdown=true; }
            }
            if(clientFin && remoteFin && unacknowledged.isEmpty() && closedAt==0) {
                closedAt=now(); channel.close(); selection.cancel();
            } else updateOps();
        }
        int sendAllowance() {
            int flight=(int)((serverNext-serverAck)&0xffffffffL);
            return Math.max(0,Math.min(SEND_WINDOW,peerWindow)-flight);
        }
        void updateOps() {
            if(disposed || !selection.isValid()) return;
            int ops=0;
            if(!connected) ops=SelectionKey.OP_CONNECT;
            else if(!ready) {
                ops=SelectionKey.OP_READ;
                if(proxyRequest!=null && proxyRequest.hasRemaining()) ops|=SelectionKey.OP_WRITE;
            } else {
                if(!remoteFin && handshakeSent && sendAllowance()>0) ops|=SelectionKey.OP_READ;
                if(!toNetwork.isEmpty()) ops|=SelectionKey.OP_WRITE;
            }
            selection.interestOps(ops);
        }
        void sendAck() throws IOException { sendPacket(serverNext,IpPacket.ACK,EMPTY); }
        void sendPacket(long sequence,int flags,byte[] data) throws IOException {
            emit(IpPacket.tcp(key.destination,key.source,key.destinationPort,key.sourcePort,sequence,clientNext,
                    flags,RECEIVE_WINDOW-queuedBytes,data));
        }
        void sendTracked(int flags,byte[] data) throws IOException {
            Segment segment=new Segment(serverNext,flags,data);
            unacknowledged.add(segment); serverNext=segment.end(); segment.transmit();
        }
        void tick(long now) throws IOException {
            if(disposed) return;
            if(closedAt!=0) { if(now-closedAt>10000) abort(false); return; }
            if((!ready && now-created>15000) || now-touched>300000) { abort(true); return; }
            Segment first=unacknowledged.peek();
            if(first!=null && now-first.sentAt>first.timeout) {
                if(first.retries++>=7) { abort(true); return; }
                first.timeout=Math.min(8000,first.timeout*2); first.transmit();
            }
            if(ready && peerWindow==0 && now-probeAt>2000) {
                probeAt=now;
                // An old-sequence ACK elicits a fresh advertised window without consuming data.
                sendPacket(plus(serverNext,-1),IpPacket.ACK,EMPTY);
            }
        }
        void abort(boolean reset) {
            if(disposed) return;
            disposed=true;
            if(reset) try { sendPacket(serverNext,IpPacket.RST|IpPacket.ACK,EMPTY); } catch(IOException ignored) { }
            selection.cancel(); try { channel.close(); } catch(IOException ignored) { }
            tcp.remove(key); tcpCount=tcp.size(); toNetwork.clear(); unacknowledged.clear();
        }
        final class Segment {
            final long sequence;
            final int flags;
            final byte[] data;
            long sentAt,timeout=600;
            int retries;
            Segment(long sequence,int flags,byte[] data) { this.sequence=sequence; this.flags=flags; this.data=data; }
            long end() { return plus(sequence,data.length+((flags&IpPacket.SYN)!=0 ? 1:0)+((flags&IpPacket.FIN)!=0 ? 1:0)); }
            void transmit() throws IOException { sendPacket(sequence,flags,data); sentAt=now(); }
        }
    }

    private final class UdpFlow {
        final Key key;
        final DatagramChannel channel;
        final SelectionKey selection;
        final ArrayDeque<ByteBuffer> pending=new ArrayDeque<>();
        final ByteBuffer receive=ByteBuffer.allocate(65507);
        long touched=now();
        UdpFlow(Key key) throws IOException {
            this.key=key; channel=DatagramChannel.open();
            try {
                channel.configureBlocking(false); channel.socket().bind(null);
                if(!protector.protect(channel.socket())) throw new IOException("Could not protect UDP socket");
                channel.connect(new InetSocketAddress(IpPacket.address(key.destination),key.destinationPort));
                selection=channel.register(selector,SelectionKey.OP_READ,this);
            } catch(IOException|RuntimeException error) { channel.close(); throw error; }
        }
        void send(byte[] data) throws IOException {
            touched=now();
            ByteBuffer packet=ByteBuffer.wrap(data);
            if(pending.isEmpty() && channel.write(packet)>0) return;
            if(pending.size()>=8) return;
            pending.add(packet); selection.interestOps(SelectionKey.OP_READ|SelectionKey.OP_WRITE);
        }
        void selected(SelectionKey selected) throws IOException {
            if(selected.isWritable()) {
                while(!pending.isEmpty()) { if(channel.write(pending.peek())==0) break; pending.remove(); }
                if(pending.isEmpty()) selected.interestOps(SelectionKey.OP_READ);
            }
            if(selected.isReadable()) {
                receive.clear(); int count=channel.read(receive);
                if(count<=0) return;
                touched=now();
                if(count>1472) return; // IPv4 fragmentation is intentionally unsupported.
                byte[] data=new byte[count]; System.arraycopy(receive.array(),0,data,0,count);
                emit(IpPacket.udp(key.destination,key.source,key.destinationPort,key.sourcePort,data));
            }
        }
        void close() {
            selection.cancel(); try { channel.close(); } catch(IOException ignored) { }
            udp.remove(key); udpCount=udp.size(); pending.clear();
        }
    }
    private static final class Key {
        final int source,destination,sourcePort,destinationPort;
        Key(IpPacket packet) { source=packet.source; destination=packet.destination; sourcePort=packet.sourcePort; destinationPort=packet.destinationPort; }
        @Override public int hashCode() { int result=source*31+destination; return (result*31+sourcePort)*31+destinationPort; }
        @Override public boolean equals(Object object) {
            if(!(object instanceof Key)) return false;
            Key other=(Key)object;
            return source==other.source && destination==other.destination && sourcePort==other.sourcePort && destinationPort==other.destinationPort;
        }
    }
}
