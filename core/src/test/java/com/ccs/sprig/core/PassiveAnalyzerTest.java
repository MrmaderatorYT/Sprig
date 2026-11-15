package com.ccs.sprig.core;

import com.ccs.sprig.core.model.Exchange;
import org.junit.Test;
import static org.junit.Assert.*;

public class PassiveAnalyzerTest {
    @Test public void cookieValuesCannotMasqueradeAsAttributes() {
        Exchange e=new Exchange();e.status=200;e.url="https://example.test/";
        e.responseHeaders="HTTP/1.1 200 OK\r\nStrict-Transport-Security: max-age=300\r\nSet-Cookie: secure=httponly; SameSite=Lax\r\n";
        assertEquals(2,PassiveAnalyzer.analyze(e).size());
        assertTrue(PassiveAnalyzer.analyze(e).get(0).title.contains("without Secure"));
    }
    @Test public void secureCookieWithAttributesDoesNotTrigger() {
        Exchange e=new Exchange();e.status=200;e.url="https://example.test/";
        e.responseHeaders="HTTP/1.1 200 OK\r\nStrict-Transport-Security: max-age=300\r\nSet-Cookie: sid=secret; Secure; HttpOnly; SameSite=Lax\r\n";
        assertTrue(PassiveAnalyzer.analyze(e).isEmpty());
    }
}
