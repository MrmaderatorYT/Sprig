package com.ccs.sprig.core;

import com.ccs.sprig.core.model.Exchange;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** HAR 1.2 export. Binary bodies use Base64; captures may be explicitly truncated. */
public final class HarExporter {
    private HarExporter() { }
    public static void write(List<Exchange> exchanges, OutputStream output) throws IOException {
        Writer w = new OutputStreamWriter(output, StandardCharsets.UTF_8);
        w.write("{\"log\":{\"version\":\"1.2\",\"creator\":{\"name\":\"Sprig\",\"version\":\"0.1 Preview\"},\"entries\":[");
        boolean first = true;
        for (int i = exchanges.size() - 1; i >= 0; i--) {
            if (!first) w.write(','); first = false;
            entry(w, exchanges.get(i));
        }
        w.write("]}}"); w.flush();
    }
    private static void entry(Writer w, Exchange e) throws IOException {
        byte[] req = e.requestBody == null ? new byte[0] : e.requestBody;
        byte[] res = e.responseBody == null ? new byte[0] : e.responseBody;
        SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        date.setTimeZone(TimeZone.getTimeZone("UTC"));
        w.write("{\"startedDateTime\":"); string(w, date.format(new Date(e.timeMillis)));
        w.write(",\"time\":" + Math.max(0, e.durationMillis) + ",\"request\":{\"method\":"); string(w, e.method);
        w.write(",\"url\":"); string(w, e.url); w.write(",\"httpVersion\":"); string(w, e.protocol);
        w.write(",\"cookies\":[],\"headers\":"); headers(w, e.requestHeaders);
        w.write(",\"queryString\":"); query(w, e.url);
        w.write(",\"headersSize\":-1,\"bodySize\":" + req.length);
        if (req.length > 0) {
            w.write(",\"postData\":{\"mimeType\":"); string(w, header(e.requestHeaders, "content-type", "application/octet-stream"));
            w.write(",\"text\":"); string(w, Base64.getEncoder().encodeToString(req));
            w.write(",\"_encoding\":\"base64\"}");
        }
        w.write("},\"response\":{\"status\":" + e.status + ",\"statusText\":"); string(w, statusText(e.responseHeaders));
        w.write(",\"httpVersion\":"); string(w, responseVersion(e));
        w.write(",\"cookies\":[],\"headers\":"); headers(w, e.responseHeaders);
        w.write(",\"content\":{\"size\":" + res.length + ",\"mimeType\":");
        string(w, header(e.responseHeaders, "content-type", "application/octet-stream"));
        w.write(",\"text\":"); string(w, Base64.getEncoder().encodeToString(res));
        w.write(",\"encoding\":\"base64\"},\"redirectURL\":"); string(w, header(e.responseHeaders, "location", ""));
        w.write(",\"headersSize\":-1,\"bodySize\":" + res.length + "},\"cache\":{},\"timings\":{\"send\":0,\"wait\":"
                + Math.max(0, e.durationMillis) + ",\"receive\":0},\"_truncated\":" + e.truncated + ",\"_error\":");
        string(w, e.error); w.write('}');
    }
    private static void headers(Writer w, String raw) throws IOException {
        w.write('['); boolean first = true;
        if (raw != null) for (String line : raw.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0 || line.substring(0, colon).contains(" ")) continue;
            if (!first) w.write(','); first = false;
            w.write("{\"name\":"); string(w, line.substring(0, colon).trim());
            w.write(",\"value\":"); string(w, line.substring(colon + 1).trim()); w.write('}');
        }
        w.write(']');
    }
    private static void query(Writer w, String url) throws IOException {
        w.write('['); boolean first = true;
        try {
            String query = URI.create(url).getRawQuery();
            if (query != null) for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                if (!first) w.write(','); first = false;
                w.write("{\"name\":"); string(w, decode(kv[0]));
                w.write(",\"value\":"); string(w, kv.length > 1 ? decode(kv[1]) : ""); w.write('}');
            }
        } catch (IllegalArgumentException ignored) { }
        w.write(']');
    }
    private static String decode(String raw) {
        try { return URLDecoder.decode(raw, "UTF-8"); }
        catch (IllegalArgumentException | java.io.UnsupportedEncodingException ignored) { return raw; }
    }
    private static String header(String raw, String name, String fallback) {
        if (raw != null) for (String line : raw.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) return line.substring(colon + 1).trim();
        }
        return fallback;
    }
    private static String responseVersion(Exchange e) {
        if(e.upstreamProtocol != null && !e.upstreamProtocol.isEmpty()) return e.upstreamProtocol;
        return e.responseHeaders != null && e.responseHeaders.startsWith("HTTP/") ? e.responseHeaders.split(" ", 2)[0] : e.protocol;
    }
    private static String statusText(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String[] parts = raw.split("\r?\n", 2)[0].split(" ", 3); return parts.length > 2 ? parts[2] : "";
    }
    private static void string(Writer w, String s) throws IOException {
        w.write('"');
        if (s != null) for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': w.write("\\\""); break;
                case '\\': w.write("\\\\"); break;
                case '\n': w.write("\\n"); break;
                case '\r': w.write("\\r"); break;
                case '\t': w.write("\\t"); break;
                default: if (c < 32 || Character.isSurrogate(c)) w.write(String.format(Locale.ROOT,"\\u%04x", (int)c)); else w.write(c);
            }
        }
        w.write('"');
    }
}
