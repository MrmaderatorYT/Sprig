package com.ccs.sprig.core.proxy;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class HttpCodecTest {
    private static ByteArrayInputStream bytes(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.ISO_8859_1)); }

    @Test public void chunksAndTrailersDoNotConsumeNextRequest() throws Exception {
        ByteArrayInputStream input = bytes("POST /one HTTP/1.1\r\nHost: fixture.test\r\nTransfer-Encoding: chunked\r\n\r\n3;ext=yes\r\none\r\n0\r\nX-Trailer: discarded\r\n\r\nGET /two HTTP/1.1\r\nHost: fixture.test\r\n\r\n");
        HttpCodec.Message one = HttpCodec.readRequest(input, null);
        assertArrayEquals("one".getBytes(StandardCharsets.US_ASCII), one.body);
        assertEquals("3", one.header("Content-Length"));
        assertNull(one.header("Transfer-Encoding"));
        assertEquals("GET /two HTTP/1.1", HttpCodec.readRequest(input, null).startLine);
    }

    @Test public void rejectsAmbiguousTransferAndLength() {
        assertBadRequest("POST / HTTP/1.1\r\nHost: fixture.test\r\nTransfer-Encoding: chunked\r\nContent-Length: 0\r\n\r\n0\r\n\r\n");
        assertBadRequest("POST / HTTP/1.1\r\nHost: fixture.test\r\nContent-Length: 1\r\nContent-Length: 1\r\n\r\na");
        assertBadRequest("POST / HTTP/1.1\r\nHost: fixture.test\r\nContent-Length: -1\r\n\r\n");
        assertBadRequest("POST / HTTP/1.1\r\nHost: fixture.test\r\nTransfer-Encoding: chunked\r\ntransfer-encoding: identity\r\n\r\n0\r\n\r\n");
        assertBadRequest("GET / HTTP/1.1\r\nHost: one.test\r\nhost: two.test\r\n\r\n");
    }

    @Test public void boundsBodyBeforeAllocating() {
        assertBadRequest("POST / HTTP/1.1\r\nHost: fixture.test\r\nContent-Length: 999999999\r\n\r\n");
        assertBadRequest("POST / HTTP/1.1\r\nHost: fixture.test\r\nTransfer-Encoding: chunked\r\n\r\nFFFFFFFF\r\n");
    }

    @Test public void failsOnTruncatedPayloadAndBareLf() {
        assertBadRequest("POST / HTTP/1.1\r\nHost: fixture.test\r\nContent-Length: 4\r\n\r\na");
        assertBadRequest("GET / HTTP/1.1\nHost: fixture.test\n\n");
    }

    @Test public void editingRecalculatesContentLengthAndRetainsBinary() throws Exception {
        HttpCodec.Message edited = HttpCodec.parseEditedRequest("POST / HTTP/1.1\r\nHost: fixture.test\r\nContent-Length: 999\r\n\r\na\u0000b".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals("3", edited.header("Content-Length"));
        assertArrayEquals(new byte[]{97, 0, 98}, edited.body);
    }

    @Test public void handlesExpectContinueWithoutOriginDeadlock() throws Exception {
        ByteArrayOutputStream interim = new ByteArrayOutputStream();
        HttpCodec.Message result = HttpCodec.readRequest(bytes("POST / HTTP/1.1\r\nHost: fixture.test\r\nExpect: 100-continue\r\nContent-Length: 1\r\n\r\nx"), interim);
        assertEquals("HTTP/1.1 100 Continue\r\n\r\n", interim.toString("US-ASCII"));
        assertNull(result.header("Expect"));
        assertArrayEquals(new byte[]{'x'}, result.body);
    }

    @Test public void headLengthDoesNotConsumeFollowingResponse() throws Exception {
        ByteArrayInputStream input = bytes("HTTP/1.1 200 OK\r\nContent-Length: 8192\r\n\r\nHTTP/1.1 204 No Content\r\n\r\n");
        HttpCodec.Message result = HttpCodec.readResponse(input, "HEAD");
        assertEquals("8192", result.header("Content-Length"));
        assertEquals(0, result.body.length);
        assertEquals(204, HttpCodec.readResponse(input, "GET").status());
    }

    @Test public void closeDelimitedResponseGetsExplicitLength() throws Exception {
        HttpCodec.Message result = HttpCodec.readResponse(bytes("HTTP/1.0 200 OK\r\n\r\nhello"), "GET");
        assertEquals("5", result.header("Content-Length"));
        assertArrayEquals("hello".getBytes(StandardCharsets.US_ASCII), result.body);
    }

    @Test public void informationalResponseAndChunkedResponseAreNormalized() throws Exception {
        HttpCodec.Message result = HttpCodec.readResponse(bytes("HTTP/1.1 103 Early Hints\r\nLink: </style.css>\r\n\r\nHTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nok\r\n0\r\n\r\n"), "GET");
        assertEquals(200, result.status());
        assertEquals("2", result.header("Content-Length"));
        assertArrayEquals(new byte[]{'o', 'k'}, result.body);
    }

    @Test public void rejectsUnsupportedProtocolAndUpgrade() throws Exception {
        assertBadRequest("PRI * HTTP/2.0\r\n\r\n");
        assertBadRequest("GET /path\twith-tab HTTP/1.1\r\nHost: fixture.test\r\n\r\n");
        try { HttpCodec.readResponse(bytes("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\n"), "GET"); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("WebSocket")); }
    }

    private static void assertBadRequest(String request) {
        try { HttpCodec.readRequest(bytes(request), null); fail("Expected malformed/oversized request to fail"); }
        catch (IOException expected) { assertNotNull(expected.getMessage()); }
    }
}
