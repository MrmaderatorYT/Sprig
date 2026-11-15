package com.ccs.sprig.core.intruder;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class PayloadSourceBoundsTest {
    private static void rejected(Runnable operation){try{operation.run();fail("Oversized input accepted");}catch(IllegalArgumentException expected){}}
    @Test public void lineParsingPreservesCrLfEmptyUnicodeAndBinarySemantics() {
        PayloadSource text=PayloadSource.ofLines("Привіт\r\n\nemoji\ud83c\udf3f\r\nlone\rend\n");
        assertEquals(5,text.size());assertArrayEquals("Привіт".getBytes(StandardCharsets.UTF_8),text.valueAt(0));
        assertArrayEquals(new byte[0],text.valueAt(1));assertArrayEquals("emoji\ud83c\udf3f".getBytes(StandardCharsets.UTF_8),text.valueAt(2));
        assertArrayEquals("lone\rend".getBytes(StandardCharsets.UTF_8),text.valueAt(3));assertArrayEquals(new byte[0],text.valueAt(4));
        assertEquals(1,PayloadSource.ofLines("").size());
        PayloadSource hex=PayloadSource.ofHexLines("00 ff\r\n\n41\t42\n");assertEquals(4,hex.size());
        assertArrayEquals(new byte[]{0,(byte)255},hex.valueAt(0));assertArrayEquals(new byte[0],hex.valueAt(1));
        assertArrayEquals(new byte[]{'A','B'},hex.valueAt(2));assertArrayEquals(new byte[0],hex.valueAt(3));
    }
    @Test(timeout=3000) public void newlineFloodRejectsBeforePayloadArrayAllocation() {
        String flood="\n".repeat(1024*1024);
        rejected(()->PayloadSource.ofLines(flood));rejected(()->PayloadSource.ofHexLines(flood));
        PayloadSource maximum=PayloadSource.ofLines("\n".repeat(99999));assertEquals(100000,maximum.size());
        assertEquals(256L+64L*100000,maximum.estimatedBytes());
    }
    @Test public void utf8AndHexByteBoundsAreCheckedBeforeEncoding() {
        assertEquals(65536,PayloadSource.ofLines("\ud83c\udf3f".repeat(16384)).valueAt(0).length);
        rejected(()->PayloadSource.ofLines("\ud83c\udf3f".repeat(16385)));
        assertEquals(65536,PayloadSource.ofHexLines("ff".repeat(65536)).valueAt(0).length);
        rejected(()->PayloadSource.ofHexLines("ff".repeat(65537)));
        rejected(()->PayloadSource.ofHexLines("00\n0"));
        assertArrayEquals(new byte[]{'?'},PayloadSource.ofLines("\ud800").valueAt(0));
    }
    @Test public void aggregateBudgetIncludesEmptyArrayOverheadAndGuardsOverflow() {
        PayloadSource emptyList=PayloadSource.ofLines("\n".repeat(99999));
        byte[] marked="GET /§a§/§b§ HTTP/1.1\r\nHost: fixture.test\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        rejected(()->new IntruderConfig.Builder("http://fixture.test/",marked).type(AttackType.PITCHFORK).payloadSets(Arrays.asList(emptyList,emptyList)).build());
        PayloadSource impossible=new PayloadSource(){public long size(){return 1;}public byte[] valueAt(long index){return new byte[0];}public long estimatedBytes(){return Long.MAX_VALUE;}};
        rejected(()->new IntruderConfig.Builder("http://fixture.test/",marked).payloadSets(Collections.singletonList(impossible)).build());
        byte[] full=new byte[65536];
        rejected(()->PayloadSource.ofBytes(Collections.nCopies(128,full)));
        rejected(()->PayloadSource.ofLines(("x".repeat(65536)+"\n").repeat(128)));
    }
}
