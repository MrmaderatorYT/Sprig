package com.ccs.sprig.core;

import java.io.IOException;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class PayloadRunnerTest {
    @Test public void rejectsExpansionBeforeAllocatingGiganticRequest() throws Exception {
        StringBuilder template=new StringBuilder("GET / HTTP/1.1\r\nHost: test\r\n\r\n");
        for(int i=0;i<1000;i++) template.append("{{payload}}");
        char[] payload=new char[16384];java.util.Arrays.fill(payload,'x');
        try {
            new PayloadRunner().run(null,"http://test/",template.toString(),Collections.singletonList(new String(payload)),100,(i,p,e,error)->fail("No replay should begin"));
            fail("Expansion must be bounded");
        } catch(IOException error) {assertTrue(error.getMessage().contains("Expanded request"));}
    }
    @Test public void cancelPreventsAnyNetworkRequest() throws Exception {
        PayloadRunner runner=new PayloadRunner();runner.cancel();
        runner.run(null,"http://test/","GET /{{payload}} HTTP/1.1\r\nHost: test\r\n\r\n",Collections.singletonList("ok"),100,(i,p,e,error)->fail("Cancelled work must not execute"));
        assertTrue(runner.isCancelled());
    }
}
