package com.ccs.sprig.core.model;

import java.nio.charset.StandardCharsets;

/** One completed HTTP exchange. Body arrays must be treated as immutable after publication. */
public final class Exchange {
    public long id, timeMillis, durationMillis;
    public String method = "", url = "", protocol = "HTTP/1.1";
    public String upstreamProtocol = "";
    public int status;
    public String requestHeaders = "", responseHeaders = "", error = "";
    public byte[] requestBody = new byte[0], responseBody = new byte[0];
    public boolean tls, truncated;

    /** Cheap metadata copy; bodies are immutable shared captures. */
    public Exchange copy() {
        Exchange e = new Exchange();
        e.id = id; e.timeMillis = timeMillis; e.durationMillis = durationMillis;
        e.method = method; e.url = url; e.protocol = protocol; e.status = status;
        e.upstreamProtocol = upstreamProtocol;
        e.requestHeaders = requestHeaders; e.responseHeaders = responseHeaders;
        e.requestBody = requestBody; e.responseBody = responseBody;
        e.error = error; e.tls = tls; e.truncated = truncated;
        return e;
    }

    public byte[] rawRequest() {
        String headers = requestHeaders == null ? "" : requestHeaders;
        headers = headers.replaceAll("[\\r\\n]+$", "") + "\r\n\r\n";
        byte[] prefix = headers.getBytes(StandardCharsets.ISO_8859_1);
        byte[] body = requestBody == null ? new byte[0] : requestBody;
        byte[] raw = java.util.Arrays.copyOf(prefix, prefix.length + body.length);
        System.arraycopy(body, 0, raw, prefix.length, body.length);
        return raw;
    }

    public long capturedBytes() {
        return (requestBody == null ? 0 : requestBody.length) + (responseBody == null ? 0 : responseBody.length)
                + 2L * (length(requestHeaders) + length(responseHeaders) + length(url) + length(error));
    }
    private static int length(String s) { return s == null ? 0 : s.length(); }
}
