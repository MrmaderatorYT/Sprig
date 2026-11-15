package com.ccs.sprig.core.tunnel;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/** Small IPv4 codec. Fragmented and malformed packets are rejected before flow allocation. */
public final class IpPacket {
    public static final int TCP = 6, UDP = 17;
    public static final int FIN = 1, SYN = 2, RST = 4, PSH = 8, ACK = 16;
    public final int source, destination, protocol, sourcePort, destinationPort, flags, window, mss;
    public final long sequence, acknowledgement;
    public final byte[] payload;
    final byte[] original;

    private IpPacket(byte[] bytes, int source, int destination, int protocol, int sourcePort,
                     int destinationPort, int flags, int window, int mss, long sequence,
                     long acknowledgement, byte[] payload) {
        this.original = bytes;
        this.source = source; this.destination = destination; this.protocol = protocol;
        this.sourcePort = sourcePort; this.destinationPort = destinationPort;
        this.flags = flags; this.window = window; this.mss = mss;
        this.sequence = sequence; this.acknowledgement = acknowledgement; this.payload = payload;
    }

    public static IpPacket parse(byte[] data, int length) {
        if (length < 20 || length > data.length || (data[0] >>> 4 & 15) != 4) return null;
        int ihl = (data[0] & 15) * 4, total = u16(data, 2);
        if (ihl < 20 || ihl > length || total < ihl || total > length) return null;
        if ((u16(data, 6) & 0x3fff) != 0 || checksum(data, 0, ihl, 0) != 0) return null;
        int protocol = data[9] & 255, transport = total - ihl;
        if (protocol == TCP) {
            if (transport < 20) return null;
            if (checksum(data,ihl,transport,pseudo(i32(data,12),i32(data,16),TCP,transport)) != 0) return null;
            int header = ((data[ihl + 12] & 255) >>> 4) * 4;
            if (header < 20 || header > transport) return null;
            int mss = 1200;
            for (int i = ihl + 20; i < ihl + header;) {
                int type = data[i] & 255;
                if (type == 0) break;
                if (type == 1) { i++; continue; }
                if (i + 1 >= ihl + header) return null;
                int optionLength = data[i + 1] & 255;
                if (optionLength < 2 || i + optionLength > ihl + header) return null;
                if (type == 2 && optionLength == 4) mss = Math.max(256, Math.min(1460, u16(data, i + 2)));
                i += optionLength;
            }
            return new IpPacket(Arrays.copyOf(data, ihl+8), i32(data,12), i32(data,16), TCP,
                    u16(data,ihl), u16(data,ihl+2), data[ihl+13]&255, u16(data,ihl+14), mss,
                    u32(data,ihl+4), u32(data,ihl+8), Arrays.copyOfRange(data,ihl+header,total));
        }
        if (protocol == UDP) {
            if (transport < 8 || u16(data,ihl+4) < 8 || u16(data,ihl+4) > transport) return null;
            int udpLength=u16(data,ihl+4);
            if (u16(data,ihl+6)!=0 && checksum(data,ihl,udpLength,pseudo(i32(data,12),i32(data,16),UDP,udpLength)) != 0) return null;
            return new IpPacket(Arrays.copyOf(data,ihl+8),i32(data,12),i32(data,16),UDP,
                    u16(data,ihl),u16(data,ihl+2),0,0,0,0,0,
                    Arrays.copyOfRange(data,ihl+8,ihl+u16(data,ihl+4)));
        }
        return null;
    }

    public static byte[] tcp(int source, int destination, int sourcePort, int destinationPort,
                             long sequence, long acknowledgement, int flags, int window, byte[] data) {
        boolean syn = (flags & SYN) != 0;
        int header = syn ? 24 : 20;
        byte[] packet = ipv4(source,destination,TCP,header+data.length);
        put16(packet,20,sourcePort); put16(packet,22,destinationPort);
        put32(packet,24,sequence); put32(packet,28,acknowledgement);
        packet[32]=(byte)((header/4)<<4); packet[33]=(byte)flags;
        put16(packet,34,Math.max(0,Math.min(65535,window)));
        if (syn) { packet[40]=2; packet[41]=4; put16(packet,42,1200); }
        System.arraycopy(data,0,packet,20+header,data.length);
        put16(packet,36,checksum(packet,20,header+data.length,pseudo(source,destination,TCP,header+data.length)));
        return packet;
    }

    public static byte[] udp(int source,int destination,int sourcePort,int destinationPort,byte[] data) {
        byte[] packet = ipv4(source,destination,UDP,8+data.length);
        put16(packet,20,sourcePort); put16(packet,22,destinationPort); put16(packet,24,8+data.length);
        System.arraycopy(data,0,packet,28,data.length);
        int sum=checksum(packet,20,8+data.length,pseudo(source,destination,UDP,8+data.length));
        put16(packet,26,sum==0 ? 65535 : sum);
        return packet;
    }

    public byte[] unreachable() {
        int quote=Math.min(original.length,(original[0]&15)*4+8);
        byte[] packet=ipv4(destination,source,1,8+quote);
        packet[20]=3; packet[21]=3;
        System.arraycopy(original,0,packet,28,quote);
        put16(packet,22,checksum(packet,20,8+quote,0));
        return packet;
    }

    private static byte[] ipv4(int source,int destination,int protocol,int size) {
        byte[] packet=new byte[20+size];
        packet[0]=0x45; put16(packet,2,packet.length); packet[6]=0x40; packet[8]=64; packet[9]=(byte)protocol;
        put32(packet,12,source&0xffffffffL); put32(packet,16,destination&0xffffffffL);
        put16(packet,10,checksum(packet,0,20,0));
        return packet;
    }

    public static String address(int address) {
        return ((address>>>24)&255)+"."+((address>>>16)&255)+"."+((address>>>8)&255)+"."+(address&255);
    }
    public static int address(String address) throws UnknownHostException {
        byte[] bytes=InetAddress.getByName(address).getAddress();
        if(bytes.length!=4) throw new UnknownHostException("IPv4 required");
        return i32(bytes,0);
    }
    static int pseudo(int source,int destination,int protocol,int length) {
        return (source>>>16)+(source&65535)+(destination>>>16)+(destination&65535)+protocol+length;
    }
    public static int checksum(byte[] data,int offset,int length,int initial) {
        long sum=initial&0xffffffffL;
        int end=offset+length;
        for(int i=offset;i<end;i+=2) sum+=((data[i]&255)<<8)+(i+1<end ? data[i+1]&255:0);
        while((sum>>>16)!=0) sum=(sum&65535)+(sum>>>16);
        return (int)(~sum)&65535;
    }
    static int u16(byte[] b,int o) { return ((b[o]&255)<<8)|(b[o+1]&255); }
    static int i32(byte[] b,int o) { return (b[o]<<24)|((b[o+1]&255)<<16)|((b[o+2]&255)<<8)|(b[o+3]&255); }
    static long u32(byte[] b,int o) { return i32(b,o)&0xffffffffL; }
    static void put16(byte[] b,int o,int v) { b[o]=(byte)(v>>>8); b[o+1]=(byte)v; }
    static void put32(byte[] b,int o,long v) { b[o]=(byte)(v>>>24); b[o+1]=(byte)(v>>>16); b[o+2]=(byte)(v>>>8); b[o+3]=(byte)v; }
}
