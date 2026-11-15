package com.ccs.sprig.core.http2;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class HpackTest {
    @Test public void decodesRfc7541HuffmanAndDynamicTableVectors() throws Exception {
        Hpack hpack=new Hpack();
        List<String[]> first=hpack.decode(1,hex("828684418cf1e3c2e5f23a6ba0ab90f4ff"));
        assertEquals("www.example.com",value(first,":authority"));assertEquals("GET",value(first,":method"));
        List<String[]> second=hpack.decode(3,hex("828684be5886a8eb10649cbf"));
        assertEquals("www.example.com",value(second,":authority"));assertEquals("no-cache",value(second,"cache-control"));
    }
    @Test public void rejectsInvalidIndexAndHeaderExpansion() throws Exception {
        try {new Hpack().decode(1,new byte[]{(byte)0x80});fail("Index zero is forbidden");}catch(IOException expected) { }
        char[] huge=new char[Hpack.MAX_HEADER_BYTES];Arrays.fill(huge,'x');
        try {new Hpack().encode(1,Arrays.asList(new String[]{":status","200"},new String[]{"x-large",new String(huge)}));fail("Header budget must be enforced");}
        catch(IOException expected) { }
    }
    @Test public void repeatedSensitiveHeadersAndDynamicFieldsRoundTrip() throws Exception {
        Hpack encoder=new Hpack(),decoder=new Hpack();
        List<String[]> headers=Arrays.asList(new String[]{":status","200"},new String[]{"set-cookie","a=secret"},new String[]{"set-cookie","b=other"},new String[]{"content-type","application/json"});
        for(int stream:new int[]{1,3,5}) {
            List<String[]> decoded=decoder.decode(stream,encoder.encode(stream,headers));
            assertEquals(4,decoded.size());assertEquals("200",value(decoded,":status"));
            assertEquals("a=secret",decoded.get(1)[1]);assertEquals("b=other",decoded.get(2)[1]);
        }
    }
    private static String value(List<String[]> list,String key){for(String[] e:list)if(e[0].equals(key))return e[1];return null;}
    private static byte[] hex(String s){byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;}
}
