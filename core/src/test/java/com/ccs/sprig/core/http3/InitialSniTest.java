package com.ccs.sprig.core.http3;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import tech.kwik.core.common.EncryptionLevel;
import tech.kwik.core.crypto.ConnectionSecrets;
import tech.kwik.core.frame.CryptoFrame;
import tech.kwik.core.impl.Role;
import tech.kwik.core.impl.Version;
import tech.kwik.core.impl.VersionHolder;
import tech.kwik.core.log.NullLogger;
import tech.kwik.core.packet.InitialPacket;
import org.junit.Test;
import static org.junit.Assert.*;

public class InitialSniTest {
    static byte[] hello() throws Exception {
        ByteArrayOutputStream extensions=new ByteArrayOutputStream();byte[] name="fixture.test".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer sni=ByteBuffer.allocate(5+name.length);sni.putShort((short)(3+name.length)).put((byte)0).putShort((short)name.length).put(name);
        extensions.write(new byte[]{0,0,0,(byte)sni.capacity()});extensions.write(sni.array());extensions.write(new byte[]{0,16,0,5,0,3,2,'h','3'});
        ByteBuffer body=ByteBuffer.allocate(34+1+2+2+1+1+2+extensions.size());body.putShort((short)0x0303).put(new byte[32]).put((byte)0).putShort((short)2).putShort((short)0x1301).put((byte)1).put((byte)0).putShort((short)extensions.size()).put(extensions.toByteArray());
        byte[] result=new byte[4+body.capacity()];result[0]=1;result[1]=(byte)(body.capacity()>>>16);result[2]=(byte)(body.capacity()>>>8);result[3]=(byte)body.capacity();System.arraycopy(body.array(),0,result,4,body.capacity());return result;
    }
    static byte[] packet(Version version,long number,long offset,byte[] fragment) throws Exception {
        byte[] dcid={1,2,3,4,5,6,7,8};ConnectionSecrets secrets=new ConnectionSecrets(VersionHolder.with(version),Role.Client,null,new NullLogger());secrets.computeInitialKeys(dcid);
        InitialPacket packet=new InitialPacket(version,new byte[]{8,7,6,5,4,3,2,1},dcid,null,new CryptoFrame(version,offset,fragment));packet.setPacketNumber(number);return packet.generatePacketBytes(secrets.getOwnAead(EncryptionLevel.Initial));
    }
    @Test public void decryptsPublicV1AndV2InitialWithoutChangingForwardedBytes() throws Exception {
        for(Version version:Arrays.asList(Version.QUIC_version_1,Version.QUIC_version_2)) {
            byte[] wire=packet(version,0,0,hello()),copy=wire.clone();InitialSni sni=new InitialSni();sni.feed(wire);
            assertTrue(sni.complete);assertTrue(sni.http3);assertEquals("fixture.test",sni.host);assertArrayEquals(copy,wire);
        }
    }
    @Test public void reassemblesOutOfOrderCryptoFragments() throws Exception {
        byte[] hello=hello();int split=hello.length/2;InitialSni sni=new InitialSni();
        sni.feed(packet(Version.QUIC_version_1,1,split,Arrays.copyOfRange(hello,split,hello.length)));assertFalse(sni.complete);
        sni.feed(packet(Version.QUIC_version_1,0,0,Arrays.copyOfRange(hello,0,split)));assertTrue(sni.complete);assertEquals("fixture.test",sni.host);
    }
}
