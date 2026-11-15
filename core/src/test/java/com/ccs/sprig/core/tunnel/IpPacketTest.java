package com.ccs.sprig.core.tunnel;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class IpPacketTest {
    @Test public void tcpRoundTripIncludesValidPseudoHeaderChecksum() throws Exception {
        int source=IpPacket.address("10.89.0.2"),destination=IpPacket.address("192.0.2.40");
        byte[] packet=IpPacket.tcp(source,destination,43210,443,0xfffffffeL,123,IpPacket.ACK|IpPacket.PSH,32768,new byte[]{0,1,2,3,4});
        IpPacket parsed=IpPacket.parse(packet,packet.length);
        assertNotNull(parsed); assertEquals(0xfffffffeL,parsed.sequence); assertEquals(123,parsed.acknowledgement);
        assertEquals("192.0.2.40",IpPacket.address(parsed.destination));
        assertArrayEquals(new byte[]{0,1,2,3,4},parsed.payload);
        assertEquals(0,IpPacket.checksum(packet,0,20,0));
        assertEquals(0,IpPacket.checksum(packet,20,packet.length-20,IpPacket.pseudo(source,destination,6,packet.length-20)));
    }
    @Test public void synAdvertisesSmallMssWithoutUnsupportedWindowScaling() {
        byte[] packet=IpPacket.tcp(1,2,3,4,5,6,IpPacket.SYN|IpPacket.ACK,32768,new byte[0]);
        IpPacket parsed=IpPacket.parse(packet,packet.length);
        assertNotNull(parsed); assertEquals(1200,parsed.mss); assertEquals(44,packet.length);
        assertEquals(0,IpPacket.checksum(packet,20,24,IpPacket.pseudo(1,2,6,24)));
    }
    @Test public void udpChecksumHandlesOddLength() {
        byte[] packet=IpPacket.udp(0x0a000002,0x08080808,30303,53,new byte[]{11,22,33});
        IpPacket parsed=IpPacket.parse(packet,packet.length);
        assertNotNull(parsed); assertEquals(53,parsed.destinationPort); assertEquals(3,parsed.payload.length);
        assertEquals(0,IpPacket.checksum(packet,20,packet.length-20,IpPacket.pseudo(parsed.source,parsed.destination,17,packet.length-20)));
    }
    @Test public void malformedAndFragmentedPacketsCannotAllocateFlows() {
        byte[] valid=IpPacket.tcp(1,2,3,4,5,6,IpPacket.ACK,32768,new byte[0]);
        for(int i=0;i<valid.length;i++) assertNull(IpPacket.parse(valid,i));
        byte[] corrupt=valid.clone(); corrupt[12]^=1; assertNull(IpPacket.parse(corrupt,corrupt.length));
        byte[] fragment=valid.clone(); fragment[6]=0x20; fragment[10]=0; fragment[11]=0;
        IpPacket.put16(fragment,10,IpPacket.checksum(fragment,0,20,0));
        assertNull(IpPacket.parse(fragment,fragment.length));
        byte[] tcpHeader=valid.clone(); tcpHeader[32]=(byte)0xf0;
        assertNull(IpPacket.parse(tcpHeader,tcpHeader.length));
    }
    @Test public void quicRejectionContainsValidIcmpQuote() {
        byte[] request=IpPacket.udp(1,2,12345,443,new byte[]{1,2,3});
        byte[] rejection=IpPacket.parse(request,request.length).unreachable();
        assertEquals(3,rejection[20]); assertEquals(3,rejection[21]);
        assertEquals(0,IpPacket.checksum(rejection,20,rejection.length-20,0));
        assertArrayEquals(Arrays.copyOf(request,28),Arrays.copyOfRange(rejection,28,rejection.length));
    }
}
