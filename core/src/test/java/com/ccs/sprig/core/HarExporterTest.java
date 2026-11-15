package com.ccs.sprig.core;

import com.ccs.sprig.core.model.Exchange;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class HarExporterTest {
    @Test public void exportsBinaryBodiesAndEscapesControlCharacters() throws Exception {
        Exchange e=new Exchange();e.timeMillis=0;e.method="POST";e.url="https://example.test/?q=a%20b";
        e.status=200;e.durationMillis=12;e.requestHeaders="POST / HTTP/1.1\r\nHost: example.test\r\n";
        e.responseHeaders="HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nX-Test: a\"b\r\n";
        e.responseBody=new byte[]{0,(byte)255,13,10};e.error="x\n\u0001";e.truncated=true;
        ByteArrayOutputStream out=new ByteArrayOutputStream();HarExporter.write(Collections.singletonList(e),out);
        String json=out.toString("UTF-8");
        assertTrue(json.contains("1970-01-01T00:00:00.000Z"));
        assertTrue(json.contains("AP8NCg=="));assertTrue(json.contains("a\\\"b"));
        assertTrue(json.contains("x\\n\\u0001"));assertTrue(json.contains("\"_truncated\":true"));
        assertTrue(json.contains("\"value\":\"a b\""));
    }
    @Test public void emptySessionIsValidHarEnvelope() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();HarExporter.write(Collections.emptyList(),out);
        assertTrue(out.toString(StandardCharsets.UTF_8.name()).endsWith("\"entries\":[]}}"));
    }
}
