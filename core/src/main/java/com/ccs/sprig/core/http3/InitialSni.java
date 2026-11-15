package com.ccs.sprig.core.http3;

import com.ccs.sprig.core.proxy.CertificateAuthority;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.BitSet;
import tech.kwik.core.common.EncryptionLevel;
import tech.kwik.core.crypto.ConnectionSecrets;
import tech.kwik.core.frame.CryptoFrame;
import tech.kwik.core.frame.QuicFrame;
import tech.kwik.core.impl.Role;
import tech.kwik.core.impl.Version;
import tech.kwik.core.impl.VersionHolder;
import tech.kwik.core.log.NullLogger;
import tech.kwik.core.packet.InitialPacket;

/** Reads the public QUIC Initial ClientHello using Kwik's authenticated packet decoder, not custom cryptography. */
final class InitialSni {
    private final byte[] crypto=new byte[64*1024];
    private final BitSet present=new BitSet(crypto.length);
    private ConnectionSecrets secrets;
    private Version version;
    private long largest=-1;
    private int required=-1;
    boolean complete,http3;
    String host;
    static boolean isInitial(byte[] packet) {
        return packet.length>=7&&InitialPacket.isInitial(packet[0]&255,ByteBuffer.wrap(packet,1,4).getInt());
    }
    void feed(byte[] datagram) throws IOException {
        if(complete)return;
        if(!isInitial(datagram))return;
        try {
            int versionId=ByteBuffer.wrap(datagram,1,4).getInt();
            if(version==null) {
                version=Version.parse(versionId);int dcidLength=datagram[5]&255;
                if(dcidLength<1||dcidLength>20||6+dcidLength>=datagram.length)throw new IOException("Invalid QUIC destination connection ID");
                secrets=new ConnectionSecrets(VersionHolder.with(version),Role.Server,null,new NullLogger());
                secrets.computeInitialKeys(Arrays.copyOfRange(datagram,6,6+dcidLength));
            }
            if(version.getId()!=versionId)throw new IOException("QUIC version changed before ClientHello");
            // Header unprotection may modify this buffer; never modify bytes later forwarded to the endpoint.
            ByteBuffer wire=ByteBuffer.wrap(datagram.clone());
            while(wire.remaining()>=7&&InitialPacket.isInitial(wire.get(wire.position())&255,wire.getInt(wire.position()+1))) {
                InitialPacket packet=new InitialPacket(version);
                packet.parse(wire,secrets.getPeerAead(EncryptionLevel.Initial),largest,new NullLogger(),0);
                largest=Math.max(largest,packet.getPacketNumber());
                for(QuicFrame frame:packet.getFrames()) if(frame instanceof CryptoFrame) {
                    CryptoFrame fragment=(CryptoFrame)frame;
                    long end=fragment.getOffset()+fragment.getLength();
                    if(fragment.getOffset()<0||end>crypto.length)throw new IOException("QUIC ClientHello exceeds 64 KiB");
                    byte[] bytes=fragment.getStreamData();int offset=(int)fragment.getOffset();
                    for(int i=0;i<bytes.length;i++) {
                        if(present.get(offset+i)&&crypto[offset+i]!=bytes[i])throw new IOException("Conflicting QUIC CRYPTO retransmission");
                        crypto[offset+i]=bytes[i];present.set(offset+i);
                    }
                }
            }
            if(required<0&&present.nextClearBit(0)>=4) {
                if(crypto[0]!=1)throw new IOException("Expected TLS ClientHello");
                required=4+((crypto[1]&255)<<16)+((crypto[2]&255)<<8)+(crypto[3]&255);
                if(required>crypto.length)throw new IOException("QUIC ClientHello exceeds 64 KiB");
            }
            if(required>=0&&present.nextClearBit(0)>=required){parseHello(Arrays.copyOfRange(crypto,4,required));complete=true;}
        } catch(IOException error){throw error;}
        catch(Exception error){throw new IOException("Invalid QUIC Initial packet",error);}
    }
    private void parseHello(byte[] hello) throws IOException {
        try {
            ByteBuffer b=ByteBuffer.wrap(hello);b.position(34);skip(b,b.get()&255);skip(b,b.getShort()&65535);skip(b,b.get()&255);
            int end=b.position()+2+(b.getShort(b.position())&65535);b.getShort();
            if(end!=b.limit())throw new IOException("Invalid TLS ClientHello extensions");
            while(b.position()<end) {
                int type=b.getShort()&65535,length=b.getShort()&65535,next=b.position()+length;
                if(next>end)throw new IOException("Invalid TLS extension length");
                if(type==0) {
                    int namesEnd=b.position()+2+(b.getShort(b.position())&65535);b.getShort();
                    if(namesEnd!=next)throw new IOException("Invalid SNI list");
                    while(b.position()<namesEnd) {
                        int kind=b.get()&255,size=b.getShort()&65535;
                        if(size<1||b.position()+size>namesEnd)throw new IOException("Invalid SNI hostname");
                        byte[] name=new byte[size];b.get(name);
                        if(kind==0) {
                            if(host!=null)throw new IOException("Duplicate SNI hostname");
                            for(byte c:name)if(c<=0)throw new IOException("SNI must be ASCII");
                            host=CertificateAuthority.normalizeHost(new String(name,StandardCharsets.US_ASCII));
                        }
                    }
                } else if(type==16) {
                    int protocolsEnd=b.position()+2+(b.getShort(b.position())&65535);b.getShort();
                    if(protocolsEnd!=next)throw new IOException("Invalid ALPN list");
                    while(b.position()<protocolsEnd) {
                        int size=b.get()&255;if(size<1||b.position()+size>protocolsEnd)throw new IOException("Invalid ALPN protocol");
                        byte[] protocol=new byte[size];b.get(protocol);if(Arrays.equals(protocol,new byte[]{'h','3'}))http3=true;
                    }
                }
                b.position(next);
            }
        }catch(java.nio.BufferUnderflowException|IllegalArgumentException error){throw new IOException("Malformed TLS ClientHello",error);}
    }
    private static void skip(ByteBuffer b,int length) throws IOException {if(length>b.remaining())throw new IOException("Malformed TLS ClientHello");b.position(b.position()+length);}
}
