package com.ccs.sprig.core.tunnel;

import org.junit.Test;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class TunEngineTest {
    private static final TunEngine.SocketProtector PROTECTOR=new TunEngine.SocketProtector() {
        public boolean protect(Socket socket) { return true; }
        public boolean protect(DatagramSocket socket) { return true; }
    };
    @Test public void bridgesTcpAndRecoversOutOfOrderAndLostSynAck() throws Exception {
        PacketInput input=new PacketInput(); PacketOutput output=new PacketOutput();
        AtomicReference<Exception> failure=new AtomicReference<>();
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));
            TunEngine engine=new TunEngine(input,output,PROTECTOR,0,true,failure::set)) {
            server.setSoTimeout(3000);
            Thread fixture=new Thread(() -> {
                try(Socket socket=server.accept()) {
                    byte[] request=new byte[4]; int offset=0;
                    while(offset<4) { int count=socket.getInputStream().read(request,offset,4-offset); if(count<0) break; offset+=count; }
                    if(Arrays.equals(request,new byte[]{10,20,30,40})) { socket.getOutputStream().write(new byte[]{50,60,70}); socket.getOutputStream().flush(); }
                } catch(IOException ignored) { }
            }); fixture.start(); engine.start();
            int source=IpPacket.address("10.89.0.2"),destination=IpPacket.address("127.0.0.1"),port=server.getLocalPort();
            input.add(IpPacket.tcp(source,destination,42424,port,0xfffffffeL,0,IpPacket.SYN,32768,new byte[0]));
            IpPacket synAck=output.tcp(); assertEquals(IpPacket.SYN|IpPacket.ACK,synAck.flags); assertEquals(0xffffffffL,synAck.acknowledgement);
            IpPacket retry=output.tcp(); assertEquals(synAck.sequence,retry.sequence); assertEquals(synAck.flags,retry.flags);
            long remote=(synAck.sequence+1)&0xffffffffL;
            input.add(IpPacket.tcp(source,destination,42424,port,0xffffffffL,remote,IpPacket.ACK,32768,new byte[0]));
            input.add(IpPacket.tcp(source,destination,42424,port,1,remote,IpPacket.ACK,32768,new byte[]{30,40}));
            assertEquals(0xffffffffL,output.tcp().acknowledgement);
            input.add(IpPacket.tcp(source,destination,42424,port,0xffffffffL,remote,IpPacket.ACK|IpPacket.PSH,32768,new byte[]{10,20,30,40}));
            IpPacket response=null;
            for(int i=0;i<6;i++) { IpPacket next=output.tcp(); if(next.payload.length>0) { response=next; break; } }
            assertNotNull(response); assertEquals(3,response.acknowledgement); assertArrayEquals(new byte[]{50,60,70},response.payload);
            input.add(IpPacket.tcp(source,destination,42424,port,3,(response.sequence+3)&0xffffffffL,IpPacket.ACK,32768,new byte[0]));
            fixture.join(1000); assertNull(failure.get());
        }
    }
    @Test public void relaysUdpAndRejectsQuicWithIcmp() throws Exception {
        PacketInput input=new PacketInput(); PacketOutput output=new PacketOutput();
        try(DatagramSocket server=new DatagramSocket(0,InetAddress.getByName("127.0.0.1"));
            TunEngine engine=new TunEngine(input,output,PROTECTOR,0,true,error -> fail(error.toString()))) {
            server.setSoTimeout(3000);
            Thread fixture=new Thread(() -> {
                try {
                    DatagramPacket request=new DatagramPacket(new byte[512],512); server.receive(request);
                    server.send(new DatagramPacket(request.getData(),request.getLength(),request.getAddress(),request.getPort()));
                } catch(IOException ignored) { }
            }); fixture.start(); engine.start();
            int source=IpPacket.address("10.89.0.2"),destination=IpPacket.address("127.0.0.1");
            input.add(IpPacket.udp(source,destination,42424,server.getLocalPort(),new byte[]{1,2,3,4}));
            byte[] response=output.next(); IpPacket parsed=IpPacket.parse(response,response.length);
            assertNotNull(parsed); assertEquals(42424,parsed.destinationPort); assertArrayEquals(new byte[]{1,2,3,4},parsed.payload);
            input.add(IpPacket.udp(source,destination,42424,443,new byte[]{99}));
            byte[] icmp=output.next(); assertEquals(1,icmp[9]); assertEquals(3,icmp[20]);
            fixture.join(1000); assertTrue(engine.bytesUp()>0); assertTrue(engine.bytesDown()>0);
        }
    }
    @Test public void respectsZeroPeerWindowBeforeReadingUpstream() throws Exception {
        PacketInput input=new PacketInput(); PacketOutput output=new PacketOutput();
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));
            TunEngine engine=new TunEngine(input,output,PROTECTOR,0,true,error -> fail(error.toString()))) {
            server.setSoTimeout(3000);
            Thread fixture=new Thread(() -> { try(Socket socket=server.accept()) { socket.getOutputStream().write(42); Thread.sleep(500); } catch(Exception ignored) { } });
            fixture.start(); engine.start();
            int source=IpPacket.address("10.89.0.2"),destination=IpPacket.address("127.0.0.1");
            input.add(IpPacket.tcp(source,destination,42424,server.getLocalPort(),100,0,IpPacket.SYN,0,new byte[0]));
            IpPacket syn=output.tcp();
            input.add(IpPacket.tcp(source,destination,42424,server.getLocalPort(),101,(syn.sequence+1)&0xffffffffL,IpPacket.ACK,0,new byte[0]));
            assertNull(output.packets.poll(150,TimeUnit.MILLISECONDS));
            input.add(IpPacket.tcp(source,destination,42424,server.getLocalPort(),101,(syn.sequence+1)&0xffffffffL,IpPacket.ACK,4096,new byte[0]));
            IpPacket response=output.tcp(); assertArrayEquals(new byte[]{42},response.payload);
            fixture.join(1000);
        }
    }
    @Test public void retransmitsUnacknowledgedDataAndPreservesClientHalfClose() throws Exception {
        PacketInput input=new PacketInput(); PacketOutput output=new PacketOutput();
        AtomicReference<byte[]> request=new AtomicReference<>();
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));
            TunEngine engine=new TunEngine(input,output,PROTECTOR,0,true,error -> fail(error.toString()))) {
            server.setSoTimeout(3000);
            Thread fixture=new Thread(() -> {
                try(Socket socket=server.accept()) {
                    java.io.ByteArrayOutputStream body=new java.io.ByteArrayOutputStream(); int b;
                    while((b=socket.getInputStream().read())>=0) body.write(b);
                    request.set(body.toByteArray()); socket.getOutputStream().write(new byte[]{50,60,70});
                    socket.shutdownOutput();
                } catch(IOException ignored) { }
            }); fixture.start(); engine.start();
            int source=IpPacket.address("10.89.0.2"),destination=IpPacket.address("127.0.0.1"),port=server.getLocalPort();
            input.add(IpPacket.tcp(source,destination,42424,port,100,0,IpPacket.SYN,32768,new byte[0]));
            IpPacket syn=output.tcp(); long remote=(syn.sequence+1)&0xffffffffL;
            input.add(IpPacket.tcp(source,destination,42424,port,101,remote,IpPacket.ACK|IpPacket.FIN,32768,new byte[]{10,20,30,40}));
            IpPacket first=null,retry=null,fin=null;
            for(int i=0;i<8;i++) {
                IpPacket packet=output.tcp();
                if((packet.flags&IpPacket.FIN)!=0) fin=packet;
                if(packet.payload.length>0) { if(first==null) first=packet; else { retry=packet; break; } }
            }
            assertNotNull(first); assertNotNull(retry); assertNotNull(fin);
            assertArrayEquals(new byte[]{50,60,70},retry.payload); assertEquals(first.sequence,retry.sequence);
            assertEquals(106,retry.acknowledgement);
            input.add(IpPacket.tcp(source,destination,42424,port,106,(fin.sequence+1)&0xffffffffL,IpPacket.ACK,32768,new byte[0]));
            fixture.join(1000); assertArrayEquals(new byte[]{10,20,30,40},request.get());
        }
    }
    @Test public void completesLoopbackProxyConnectBeforeAcknowledgingSyn() throws Exception {
        PacketInput input=new PacketInput(); PacketOutput output=new PacketOutput();
        AtomicReference<String> preface=new AtomicReference<>();
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));
            TunEngine engine=new TunEngine(input,output,PROTECTOR,server.getLocalPort(),true,error -> fail(error.toString()))) {
            server.setSoTimeout(3000);
            Thread fixture=new Thread(() -> {
                try(Socket socket=server.accept()) {
                    java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream(); int b;
                    while((b=socket.getInputStream().read())>=0) {
                        bytes.write(b);
                        if(new String(bytes.toByteArray(),StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) break;
                    }
                    preface.set(new String(bytes.toByteArray(),StandardCharsets.US_ASCII));
                    // Exercise partial CONNECT response framing.
                    socket.getOutputStream().write("HTTP/1.1 200 Connection".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    Thread.sleep(30);
                    socket.getOutputStream().write(" established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    assertEquals(42,socket.getInputStream().read());
                } catch(Exception ignored) { }
            }); fixture.start(); engine.start();
            int source=IpPacket.address("10.89.0.2"),destination=IpPacket.address("192.0.2.15");
            input.add(IpPacket.tcp(source,destination,42424,443,100,0,IpPacket.SYN,32768,new byte[0]));
            IpPacket syn=output.tcp(); assertEquals(IpPacket.SYN|IpPacket.ACK,syn.flags);
            assertEquals("CONNECT 192.0.2.15:443 HTTP/1.1\r\nHost: 192.0.2.15:443\r\nX-Sprig-Transparent: 1\r\n\r\n",preface.get());
            input.add(IpPacket.tcp(source,destination,42424,443,101,(syn.sequence+1)&0xffffffffL,IpPacket.ACK,32768,new byte[]{42}));
            fixture.join(1000); assertFalse(fixture.isAlive());
        }
    }
    private static class PacketInput extends InputStream {
        final BlockingQueue<byte[]> packets=new LinkedBlockingQueue<>();
        volatile boolean closed;
        void add(byte[] data) { packets.add(data); }
        @Override public int read() { throw new UnsupportedOperationException(); }
        @Override public int read(byte[] bytes) throws IOException {
            try {
                byte[] packet=packets.take(); if(closed) return -1;
                System.arraycopy(packet,0,bytes,0,packet.length); return packet.length;
            } catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
        }
        @Override public void close() { closed=true; packets.add(new byte[0]); }
    }
    private static class PacketOutput extends OutputStream {
        final BlockingQueue<byte[]> packets=new LinkedBlockingQueue<>();
        @Override public void write(int data) { throw new UnsupportedOperationException(); }
        @Override public void write(byte[] data) { packets.add(data.clone()); }
        byte[] next() throws InterruptedException { byte[] data=packets.poll(3,TimeUnit.SECONDS); assertNotNull("Expected output packet",data); return data; }
        IpPacket tcp() throws InterruptedException { byte[] data=next(); IpPacket parsed=IpPacket.parse(data,data.length); assertNotNull(parsed); return parsed; }
    }
}
