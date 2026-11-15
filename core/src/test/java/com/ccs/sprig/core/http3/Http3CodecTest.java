package com.ccs.sprig.core.http3;

import com.ccs.sprig.core.proxy.HttpCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class Http3CodecTest {
    @Test public void variableIntegersCoverAllEncodingLengthsAndTruncation() throws Exception {
        long[] values={0,63,64,16383,16384,(1L<<30)-1,1L<<30,0x3fffffffffffffffL};
        for(long value:values){ByteArrayOutputStream wire=new ByteArrayOutputStream();Http3Codec.writeVarInt(wire,value);assertEquals(value,Http3Codec.readVarInt(new ByteArrayInputStream(wire.toByteArray()),false));}
        try{Http3Codec.readVarInt(new ByteArrayInputStream(new byte[]{(byte)0xc0,1}),false);fail();}catch(IOException expected){}
    }
    @Test public void realQpackRoundTripsRequestHeadersAndBinaryBody() throws Exception {
        HttpCodec.Message request=new HttpCodec.Message();request.startLine="POST /api?q=one HTTP/1.1";
        request.headers.add(new String[]{"X-Sprig-Check","token-value"});request.body=new byte[]{0,13,10,(byte)255};request.set("Content-Length","4");
        ByteArrayOutputStream wire=new ByteArrayOutputStream();Http3Codec.writeRequest(wire,request,"fixture.test");
        Http3Codec.Request decoded=Http3Codec.readRequest(new ByteArrayInputStream(wire.toByteArray()));
        assertEquals("fixture.test",decoded.authority);assertEquals(request.startLine,decoded.message.startLine);
        assertEquals("token-value",decoded.message.header("x-sprig-check"));assertArrayEquals(request.body,decoded.message.body);
    }
    @Test public void headAnd304ResponseLengthsAreMetadata() throws Exception {
        HttpCodec.Message response=new HttpCodec.Message();response.startLine="HTTP/1.1 304 Not Modified";response.set("Content-Length","8192");
        ByteArrayOutputStream wire=new ByteArrayOutputStream();Http3Codec.writeResponse(wire,response,false);
        HttpCodec.Message decoded=Http3Codec.readResponse(new ByteArrayInputStream(wire.toByteArray()),"GET");
        assertEquals(304,decoded.status());assertEquals("8192",decoded.header("content-length"));assertEquals(0,decoded.body.length);
    }
    @Test public void rejectsDynamicQpackAndAmbiguousPseudoHeaders() throws Exception {
        try{Http3Codec.decodeHeaders(new byte[]{1,0,0});fail();}catch(IOException expected){}
        byte[] headers=Http3Codec.encodeHeaders(Arrays.asList(new AbstractMap.SimpleImmutableEntry<>(":method","GET"),new AbstractMap.SimpleImmutableEntry<>(":method","POST"),new AbstractMap.SimpleImmutableEntry<>(":scheme","https"),new AbstractMap.SimpleImmutableEntry<>(":path","/"),new AbstractMap.SimpleImmutableEntry<>(":authority","fixture.test")));
        ByteArrayOutputStream wire=new ByteArrayOutputStream();Http3Codec.writeFrame(wire,1,headers);
        try{Http3Codec.readRequest(new ByteArrayInputStream(wire.toByteArray()));fail();}catch(IOException expected){}
    }
}
