package com.ccs.sprig.core.intruder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class PayloadProcessorsTest {
    @Test public void binaryUrlBase64AndHexRoundTrip()throws Exception {
        byte[] original={0,(byte)255,' ',13,10,'+','A'};
        assertArrayEquals(original,PayloadProcessors.urlDecode().apply(PayloadProcessors.urlEncode().apply(original)));
        assertArrayEquals(original,PayloadProcessors.base64Decode().apply(PayloadProcessors.base64Encode().apply(original)));
        assertArrayEquals(original,PayloadProcessors.hexDecode().apply(PayloadProcessors.hexEncode().apply(original)));
        assertEquals("%00%FF%20%0D%0A%2BA",new String(PayloadProcessors.urlEncode().apply(original),StandardCharsets.US_ASCII));
    }
    @Test public void asciiCaseAndLiteralReplacementDoNotCorruptBinary()throws Exception {
        assertArrayEquals(new byte[]{(byte)255,'A',0},PayloadProcessors.upperCase().apply(new byte[]{(byte)255,'a',0}));
        assertEquals("xx-b-xx",new String(PayloadProcessors.replace(new byte[]{'a'},new byte[]{'x','x'}).apply("a-b-a".getBytes(StandardCharsets.US_ASCII)),StandardCharsets.US_ASCII));
        assertArrayEquals(new byte[]{0,'x',(byte)255},PayloadProcessors.suffix(new byte[]{(byte)255}).apply(PayloadProcessors.prefix(new byte[]{0}).apply(new byte[]{'x'})));
    }
    @Test public void malformedInputAndExpansionAreBounded()throws Exception {
        for(PayloadProcessor processor:Arrays.asList(PayloadProcessors.urlDecode(),PayloadProcessors.base64Decode(),PayloadProcessors.hexDecode()))try{processor.apply(new byte[]{'%'});fail();}catch(IOException expected){}
        byte[] huge=new byte[PayloadProcessors.MAX_BYTES];Arrays.fill(huge,(byte)'x');try{PayloadProcessors.replace(new byte[]{'x'},new byte[64]).apply(huge);fail();}catch(IOException expected){}
        try{PayloadProcessors.prefix(new byte[]{0}).apply(huge);fail();}catch(IOException expected){}
    }
}
