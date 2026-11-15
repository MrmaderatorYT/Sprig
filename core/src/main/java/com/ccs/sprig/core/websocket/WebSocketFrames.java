package com.ccs.sprig.core.websocket;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/** Strict RFC 6455 framing. No compression extension is negotiated. */
public final class WebSocketFrames {
    public static final int MAX_MESSAGE_BYTES=1024*1024;
    public static final class ProtocolException extends IOException {
        public final int closeCode;
        public ProtocolException(int code,String message){super(message);closeCode=code;}
    }
    public static final class Frame {
        public final boolean fin;
        public final int opcode;
        public final byte[] payload;
        public Frame(boolean fin,int opcode,byte[] payload){this.fin=fin;this.opcode=opcode;this.payload=payload;}
    }
    private WebSocketFrames(){}
    public static Frame read(InputStream input,boolean expectMasked)throws IOException {
        int a=input.read();if(a<0)return null;return readFollowing(input,expectMasked,a);
    }
    static Frame readFollowing(InputStream input,boolean expectMasked,int a)throws IOException {
        int b=one(input);
        boolean fin=(a&128)!=0,masked=(b&128)!=0;int opcode=a&15;
        if((a&112)!=0)throw protocol(1002,"Unexpected WebSocket RSV bits; extensions are disabled");
        if(opcode!=0&&opcode!=1&&opcode!=2&&opcode!=8&&opcode!=9&&opcode!=10)throw protocol(1002,"Unknown WebSocket opcode");
        if(masked!=expectMasked)throw protocol(1002,expectMasked?"Client frame must be masked":"Server frame must not be masked");
        long size=b&127;
        if(size==126){size=((long)one(input)<<8)|one(input);if(size<126)throw protocol(1002,"Non-minimal WebSocket length");}
        else if(size==127){size=one(input);if((size&128)!=0)throw protocol(1002,"Negative WebSocket frame length");for(int i=0;i<7;i++)size=(size<<8)|one(input);if(size<65536)throw protocol(1002,"Non-minimal WebSocket length");}
        if(opcode>=8&&(!fin||size>125))throw protocol(1002,"Invalid fragmented or oversized control frame");
        if(size>MAX_MESSAGE_BYTES)throw protocol(1009,"WebSocket frame exceeds 1 MiB");
        byte[] mask=masked?bytes(input,4):null,payload=bytes(input,(int)size);
        if(masked)for(int i=0;i<payload.length;i++)payload[i]^=mask[i&3];
        if(opcode==8)validateClose(payload,!expectMasked);
        return new Frame(fin,opcode,payload);
    }
    public static void write(OutputStream output,boolean mask,boolean fin,int opcode,byte[] payload,SecureRandom random)throws IOException {
        write(output,mask,fin,opcode,payload,0,payload.length,random);
    }
    public static void write(OutputStream output,boolean mask,boolean fin,int opcode,byte[] payload,int offset,int length,SecureRandom random)throws IOException {
        if(length>MAX_MESSAGE_BYTES||length<0||offset<0||offset>payload.length-length)throw protocol(1009,"Invalid WebSocket payload length");
        if(opcode>=8&&(!fin||length>125))throw protocol(1002,"Invalid WebSocket control frame");
        output.write((fin?128:0)|opcode);
        int bit=mask?128:0;
        if(length<126)output.write(bit|length);
        else if(length<=65535){output.write(bit|126);output.write(length>>>8);output.write(length);}
        else{output.write(bit|127);for(int shift=56;shift>=0;shift-=8)output.write((int)(((long)length)>>>shift));}
        if(mask){byte[] key=new byte[4];random.nextBytes(key);output.write(key);byte[] scratch=new byte[Math.min(16384,Math.max(1,length))];
            for(int p=0;p<length;){int count=Math.min(scratch.length,length-p);for(int i=0;i<count;i++)scratch[i]=(byte)(payload[offset+p+i]^key[(p+i)&3]);output.write(scratch,0,count);p+=count;}}
        else output.write(payload,offset,length);
        output.flush();
    }
    public static void validateText(byte[] payload)throws ProtocolException {
        try{StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload));}
        catch(CharacterCodingException invalid){throw protocol(1007,"Invalid UTF-8 WebSocket message");}
    }
    public static void validateClose(byte[] payload,boolean fromServer)throws ProtocolException {
        if(payload.length==1)throw protocol(1002,"Truncated WebSocket close code");
        if(payload.length<2)return;
        int code=((payload[0]&255)<<8)|(payload[1]&255);
        boolean standard=code==1000||code==1001||code==1002||code==1003||code==1007||code==1008||code==1009||code==1010||code==1011||code==1012||code==1013||code==1014;
        if((!standard&&(code<3000||code>4999))||(fromServer&&code==1010))throw protocol(1002,"Forbidden WebSocket close code");
        byte[] reason=new byte[payload.length-2];System.arraycopy(payload,2,reason,0,reason.length);validateText(reason);
    }
    public static byte[] closePayload(int code,String reason){byte[] text=reason.getBytes(StandardCharsets.UTF_8);int length=Math.min(120,text.length);for(;;){try{validateText(java.util.Arrays.copyOf(text,length));break;}catch(ProtocolException invalid){length--;}}byte[] result=new byte[2+length];result[0]=(byte)(code>>>8);result[1]=(byte)code;System.arraycopy(text,0,result,2,length);return result;}
    private static ProtocolException protocol(int code,String reason){return new ProtocolException(code,reason);}
    private static int one(InputStream input)throws IOException{int n=input.read();if(n<0)throw new EOFException("Truncated WebSocket frame");return n;}
    private static byte[] bytes(InputStream input,int length)throws IOException{byte[] bytes=new byte[length];int p=0;while(p<length){int count=input.read(bytes,p,length-p);if(count<0)throw new EOFException("Truncated WebSocket frame");p+=count;}return bytes;}
}
