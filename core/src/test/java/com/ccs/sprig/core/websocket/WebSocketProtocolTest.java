package com.ccs.sprig.core.websocket;

import com.ccs.sprig.core.proxy.HttpCodec;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebSocketProtocolTest {
    @Test public void rfcMaskVectorAndFreshlyMaskedBinaryRoundTrip()throws Exception {
        WebSocketFrames.Frame frame=WebSocketFrames.read(new ByteArrayInputStream(hex("818537fa213d7f9f4d5158")),true);
        assertEquals("Hello",new String(frame.payload,StandardCharsets.UTF_8));assertTrue(frame.fin);assertEquals(1,frame.opcode);
        byte[] body=new byte[70000];for(int i=0;i<body.length;i++)body[i]=(byte)i;ByteArrayOutputStream wire=new ByteArrayOutputStream();
        WebSocketFrames.write(wire,true,true,2,body,new SecureRandom());assertArrayEquals(body,WebSocketFrames.read(new ByteArrayInputStream(wire.toByteArray()),true).payload);
        assertFalse(Arrays.equals(body,wire.toByteArray()));
    }
    @Test public void rejectsMaskingRsvControlAndNonminimalLengthViolations()throws Exception {
        for(byte[] bytes:new byte[][]{{(byte)0x81,1,1},{(byte)0xc1,(byte)0x80,0,0,0,0},{9,(byte)0x80,0,0,0,0},{(byte)0x81,(byte)0xfe,0,1}}) {
            try{WebSocketFrames.read(new ByteArrayInputStream(bytes),true);fail("Malformed frame must be rejected");}catch(WebSocketFrames.ProtocolException expected){assertEquals(1002,expected.closeCode);}
        }
        try{WebSocketFrames.read(new ByteArrayInputStream(hex("817f0000000000100001")),false);fail("Huge frame must fail before allocation");}
        catch(WebSocketFrames.ProtocolException expected){assertEquals(1009,expected.closeCode);}
    }
    @Test public void validatesUtf8AndCloseCodesIncludingUnicodeTruncation()throws Exception {
        try{WebSocketFrames.validateText(new byte[]{(byte)0xc3,0x28});fail("Bad UTF8");}catch(WebSocketFrames.ProtocolException expected){assertEquals(1007,expected.closeCode);}
        for(int code:new int[]{1004,1005,1006,1015,2000,5000})try{WebSocketFrames.validateClose(new byte[]{(byte)(code>>>8),(byte)code},false);fail("Forbidden code");}catch(WebSocketFrames.ProtocolException expected){assertEquals(1002,expected.closeCode);}
        byte[] close=WebSocketFrames.closePayload(1000,"🙂".repeat(100));assertTrue(close.length<=125);WebSocketFrames.validateClose(close,true);
    }
    @Test public void validatesRfcAcceptAndRemovesCompressionOffers()throws Exception {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",WebSocketHandshake.accept("dGhlIHNhbXBsZSBub25jZQ=="));
        HttpCodec.Message request=WebSocketHandshake.clientRequest(URI.create("ws://fixture.test/chat"),"Sec-WebSocket-Protocol: chat\r\nOrigin: https://fixture.test",new SecureRandom());
        assertEquals(0,request.body.length);request.set("Sec-WebSocket-Extensions","permessage-deflate");WebSocketHandshake.prepareRequest(request);assertNull(request.header("Sec-WebSocket-Extensions"));
        HttpCodec.Message response=response(request);WebSocketHandshake.validateResponse(request,response);
        response.set("Sec-WebSocket-Extensions","permessage-deflate");try{WebSocketHandshake.validateResponse(request,response);fail("Unnegotiated extensions cannot be passed through");}catch(IOException expected){}
        response=response(request);response.set("Sec-WebSocket-Protocol","unoffered");try{WebSocketHandshake.validateResponse(request,response);fail("Unsolicited subprotocol");}catch(IOException expected){}
    }
    @Test public void invalidHandshakeKeyAndAcceptFailClosedAndOrdinary101StaysRejected()throws Exception {
        HttpCodec.Message request=WebSocketHandshake.clientRequest(URI.create("wss://fixture.test/"),"",new SecureRandom());
        request.set("Sec-WebSocket-Key","invalid");try{WebSocketHandshake.validateRequest(request);fail("Bad key");}catch(IOException expected){}
        request=WebSocketHandshake.clientRequest(URI.create("wss://fixture.test/"),"",new SecureRandom());HttpCodec.Message response=response(request);response.set("Sec-WebSocket-Accept","forged");
        try{WebSocketHandshake.validateResponse(request,response);fail("Bad accept");}catch(IOException expected){}
        try{HttpCodec.readResponse(new ByteArrayInputStream(response.headText().getBytes(StandardCharsets.US_ASCII)),"GET");fail("GenericHTTP must not accept101");}catch(IOException expected){}
    }
    @Test public void handshakeParserPreservesAlreadyBufferedFrameBytes()throws Exception {
        HttpCodec.Message request=WebSocketHandshake.clientRequest(URI.create("ws://fixture.test/"),"",new SecureRandom());ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        response(request).writeTo(bytes);WebSocketFrames.write(bytes,false,true,1,"immediate".getBytes(StandardCharsets.UTF_8),new SecureRandom());
        BufferedInputStream input=new BufferedInputStream(new ByteArrayInputStream(bytes.toByteArray()),16384);
        WebSocketHandshake.validateResponse(request,HttpCodec.readWebSocketResponse(input));assertEquals("immediate",new String(WebSocketFrames.read(input,false).payload,StandardCharsets.UTF_8));
    }
    @Test public void manualIpv6AuthorityGetsExactlyOnePairOfBrackets()throws Exception {
        HttpCodec.Message request=WebSocketHandshake.clientRequest(URI.create("ws://[::1]:8080/chat"),"",new SecureRandom());assertEquals("[::1]:8080",request.header("Host"));
    }
    private static HttpCodec.Message response(HttpCodec.Message request)throws Exception{HttpCodec.Message response=new HttpCodec.Message();response.startLine="HTTP/1.1 101 Switching Protocols";response.set("Upgrade","websocket");response.set("Connection","Upgrade");response.set("Sec-WebSocket-Accept",WebSocketHandshake.accept(request.header("Sec-WebSocket-Key")));return response;}
    private static byte[] hex(String value){byte[] bytes=new byte[value.length()/2];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return bytes;}
}
