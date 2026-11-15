package com.ccs.sprig.core.intruder;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class IntruderPlanTest {
    static byte[] raw(String text){return text.getBytes(StandardCharsets.ISO_8859_1);}
    static IntruderConfig.Builder builder(AttackType type,PayloadSource... sets){return new IntruderConfig.Builder("http://fixture.test/",raw("GET /?a=§A§&b=§B§ HTTP/1.1\r\nHost: fixture.test\r\nCookie: sid=secret\r\n\r\n")).type(type).payloadSets(Arrays.asList(sets)).requestBudget(100000).intervalMillis(0);}
    static String line(IntruderPlan plan,int index)throws Exception{return new String(plan.caseAt(index).rawRequest,StandardCharsets.ISO_8859_1).split("\r\n",2)[0];}
    @Test public void sniperAndBatteringRamHaveExactPositionsAndCounts()throws Exception {
        IntruderPlan sniper=new IntruderPlan(builder(AttackType.SNIPER,PayloadSource.ofLines("x\ny")).build());
        assertEquals(2,sniper.positions());assertEquals(4,sniper.combinationCount());
        assertEquals("GET /?a=x&b=B HTTP/1.1",line(sniper,0));assertEquals("GET /?a=y&b=B HTTP/1.1",line(sniper,1));assertEquals("GET /?a=A&b=x HTTP/1.1",line(sniper,2));
        IntruderPlan ram=new IntruderPlan(builder(AttackType.BATTERING_RAM,PayloadSource.ofLines("x\ny")).build());assertEquals(2,ram.requestCount());assertEquals("GET /?a=y&b=y HTTP/1.1",line(ram,1));
    }
    @Test public void pitchforkStopsAtShortestAndClusterBombIsLazyMixedRadix()throws Exception {
        PayloadSource first=PayloadSource.ofLines("x\ny"),second=PayloadSource.ofLines("1\n2\n3");
        IntruderPlan pitchfork=new IntruderPlan(builder(AttackType.PITCHFORK,first,second).build());assertEquals(2,pitchfork.combinationCount());assertEquals("GET /?a=y&b=2 HTTP/1.1",line(pitchfork,1));
        IntruderPlan cluster=new IntruderPlan(builder(AttackType.CLUSTER_BOMB,first,second).build());assertEquals(6,cluster.combinationCount());assertEquals("GET /?a=x&b=3 HTTP/1.1",line(cluster,2));assertEquals("GET /?a=y&b=1 HTTP/1.1",line(cluster,3));
        IntruderPlan trillion=new IntruderPlan(builder(AttackType.CLUSTER_BOMB,PayloadSource.numeric(0,999999,1,0),PayloadSource.numeric(0,999999,1,0)).build());assertEquals(1_000_000_000_000L,trillion.combinationCount());assertEquals(100000,trillion.requestCount());assertTrue(line(trillion,99999).contains("a=0&b=99999"));
    }
    @Test public void independentProcessorsRunBeforeGlobalAndPreserveHeaders()throws Exception {
        IntruderConfig config=builder(AttackType.PITCHFORK,PayloadSource.ofLines("a b"),PayloadSource.ofLines("z")).processorsForSet(0,Collections.singletonList(PayloadProcessors.urlEncode())).processorsForSet(1,Collections.singletonList(PayloadProcessors.upperCase())).processors(Collections.singletonList(PayloadProcessors.prefix(raw("p-")))).build();
        IntruderPlan plan=new IntruderPlan(config);String raw=new String(plan.caseAt(0).rawRequest,StandardCharsets.ISO_8859_1);assertTrue(raw.startsWith("GET /?a=p-a%20b&b=p-Z "));assertTrue(raw.contains("Cookie: sid=secret\r\n"));
    }
    @Test public void numericBoundariesAndBinaryPayloadsAreExact()throws Exception {
        PayloadSource numbers=PayloadSource.numeric(Long.MIN_VALUE,Long.MAX_VALUE,Long.MAX_VALUE,0);assertEquals(3,numbers.size());assertEquals("-1",new String(numbers.valueAt(1),StandardCharsets.US_ASCII));
        assertEquals("-003",new String(PayloadSource.numeric(-3,-1,1,3).valueAt(0),StandardCharsets.US_ASCII));
        byte[] bytes={0,(byte)255,13,10};IntruderConfig config=new IntruderConfig.Builder("http://fixture.test/",raw("POST / HTTP/1.1\r\nHost: fixture.test\r\nAuthorization: Bearer secret\r\n\r\n§x§")).payloadSets(Collections.singletonList(PayloadSource.ofBytes(Collections.singletonList(bytes)))).build();
        byte[] generated=new IntruderPlan(config).caseAt(0).rawRequest;assertArrayEquals(bytes,Arrays.copyOfRange(generated,generated.length-4,generated.length));assertTrue(new String(generated,StandardCharsets.ISO_8859_1).contains("Authorization: Bearer secret"));
        assertArrayEquals("Привіт".getBytes(StandardCharsets.UTF_8),PayloadSource.ofLines("Привіт").valueAt(0));
    }
    @Test public void overflowMalformedMarkersAndWrongSetCountFailBeforeRunning() {
        try{PayloadSource.numeric(Long.MIN_VALUE,Long.MAX_VALUE,1,0);fail();}catch(IllegalArgumentException expected){}
        try{builder(AttackType.CLUSTER_BOMB,PayloadSource.numeric(0,Long.MAX_VALUE-1,1,0),PayloadSource.ofLines("a\nb")).build();fail();}catch(IllegalArgumentException expected){}
        try{builder(AttackType.PITCHFORK,PayloadSource.ofLines("x")).build();fail();}catch(IllegalArgumentException expected){}
        try{new IntruderConfig.Builder("http://fixture.test/",raw("GET /§x HTTP/1.1\r\n\r\n")).payloadSets(Collections.singletonList(PayloadSource.ofLines("x"))).build();fail();}catch(IllegalArgumentException expected){}
    }
}
