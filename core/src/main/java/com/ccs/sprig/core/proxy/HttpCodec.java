package com.ccs.sprig.core.proxy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Strict HTTP/1 framing. Bodies are bounded and dechunked without decoding content encodings. */
public final class HttpCodec {
    public static final int MAX_HEADERS = 64 * 1024;
    public static final int MAX_BODY = 4 * 1024 * 1024;

    private HttpCodec() { }

    public static final class Message {
        public String startLine;
        public final List<String[]> headers = new ArrayList<>();
        public byte[] body = new byte[0];

        public String header(String name) {
            for (String[] h : headers) if (h[0].equalsIgnoreCase(name)) return h[1];
            return null;
        }

        public void remove(String name) {
            headers.removeIf(h -> h[0].equalsIgnoreCase(name));
        }

        public void set(String name, String value) {
            remove(name);
            headers.add(new String[]{name, value});
        }

        public String headText() {
            StringBuilder result = new StringBuilder(startLine).append("\r\n");
            for (String[] h : headers) result.append(h[0]).append(": ").append(h[1]).append("\r\n");
            return result.append("\r\n").toString();
        }

        public byte[] raw() {
            byte[] head = headText().getBytes(StandardCharsets.ISO_8859_1);
            byte[] result = new byte[head.length + body.length];
            System.arraycopy(head, 0, result, 0, head.length);
            System.arraycopy(body, 0, result, head.length, body.length);
            return result;
        }

        public void writeTo(OutputStream output) throws IOException {
            output.write(headText().getBytes(StandardCharsets.ISO_8859_1));
            output.write(body);
            output.flush();
        }

        public String method() { return startLine.split(" ", 3)[0]; }
        public int status() throws IOException {
            try { return Integer.parseInt(startLine.split(" ", 3)[1]); }
            catch (RuntimeException e) { throw new IOException("Invalid HTTP response status", e); }
        }
    }

    public static Message readHead(InputStream input) throws IOException {
        String first = readLine(input, MAX_HEADERS);
        if (first == null) return null;
        if (first.isEmpty()) throw new IOException("Empty HTTP start line");
        Message message = new Message();
        message.startLine = first;
        int remaining = MAX_HEADERS - first.length() - 2;
        for (;;) {
            String line = readLine(input, remaining);
            if (line == null) throw new EOFException("Incomplete HTTP headers");
            remaining -= line.length() + 2;
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon <= 0 || !isToken(line.substring(0, colon))) throw new IOException("Invalid HTTP header");
            String value = line.substring(colon + 1).trim();
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if ((c < 32 && c != '\t') || c == 127) throw new IOException("Invalid header character");
            }
            message.headers.add(new String[]{line.substring(0, colon), value});
        }
        return message;
    }

    public static Message readRequest(InputStream input, OutputStream interim) throws IOException {
        Message result = readHead(input);
        if (result == null) return null;
        validateRequestLine(result.startLine);
        validateHost(result);
        String expect = result.header("Expect");
        if (expect != null) {
            if (!expect.equalsIgnoreCase("100-continue")) throw new IOException("Unsupported Expect header");
            if (interim != null) {
                interim.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                interim.flush();
            }
        }
        readBody(result, input, true, false);
        result.remove("Expect");
        return result;
    }

    public static Message readResponse(InputStream input, String method) throws IOException {
        return readResponse(input, method, false);
    }

    /** Only the WebSocket upgrade path may retain a 101 head and hand the same input to its frame reader. */
    public static Message readWebSocketResponse(InputStream input) throws IOException {
        return readResponse(input, "GET", true);
    }

    private static Message readResponse(InputStream input, String method, boolean allowWebSocketUpgrade) throws IOException {
        Message result;
        int interimCount = 0;
        do {
            result = readHead(input);
            if (result == null) throw new EOFException("Origin closed without a response");
            if (!result.startLine.matches("HTTP/1\\.[01] [0-9]{3}( .*)?")) throw new IOException("Unsupported response protocol");
            if (++interimCount > 16) throw new IOException("Too many informational responses");
        } while (result.status() >= 100 && result.status() < 200 && result.status() != 101);
        int status = result.status();
        if (status == 101) {
            if (allowWebSocketUpgrade) return result;
            throw new IOException("Unexpected HTTP 101 outside a validated WebSocket upgrade");
        }
        boolean noBody = method.equalsIgnoreCase("HEAD") || status == 204 || status == 304;
        readBody(result, input, false, noBody);
        return result;
    }

    public static Message parseEditedRequest(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_BODY + MAX_HEADERS) throw new IOException("Edited request is too large");
        ByteArrayInputStream input = new ByteArrayInputStream(bytes);
        Message result = readHead(input);
        if (result == null) throw new IOException("Empty request");
        validateRequestLine(result.startLine);
        validateHost(result);
        if (result.header("Transfer-Encoding") != null) {
            readBody(result, input, true, false);
            if (input.available() > 0) throw new IOException("Multiple requests are not allowed in an editor");
        } else {
            if (input.available() > MAX_BODY) throw new IOException("Request body exceeds 4 MiB");
            result.body = new byte[input.available()];
            readExact(input, result.body, 0, result.body.length);
            normalizeLength(result, false);
        }
        result.remove("Expect");
        return result;
    }

    public static void stripHopHeaders(Message message) {
        String connection = message.header("Connection");
        if (connection != null) for (String token : connection.split(",")) message.remove(token.trim());
        message.remove("Connection");
        message.remove("Proxy-Connection");
        message.remove("Keep-Alive");
        message.remove("Proxy-Authorization");
        message.remove("Proxy-Authenticate");
        message.remove("TE");
        message.remove("Trailer");
        message.remove("Transfer-Encoding");
        message.remove("Upgrade");
    }

    private static void readBody(Message message, InputStream input, boolean request, boolean noBody) throws IOException {
        String transfer = message.header("Transfer-Encoding");
        String length = null;
        int count = 0, transfers = 0;
        for (String[] h : message.headers) {
            if (h[0].equalsIgnoreCase("Content-Length")) { length = h[1]; count++; }
            if (h[0].equalsIgnoreCase("Transfer-Encoding")) transfers++;
        }
        if (count > 1 || transfers > 1 || (transfer != null && length != null)) throw new IOException("Ambiguous HTTP message framing");
        if (transfer != null && !transfer.equalsIgnoreCase("chunked")) throw new IOException("Unsupported Transfer-Encoding");
        if (noBody) return;
        if (transfer != null) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            for (;;) {
                String line = readLine(input, 1024);
                if (line == null) throw new EOFException("Incomplete chunk size");
                int semicolon = line.indexOf(';');
                String hex = semicolon < 0 ? line : line.substring(0, semicolon);
                if (!hex.matches("[0-9a-fA-F]{1,8}")) throw new IOException("Invalid HTTP chunk size");
                long size = Long.parseLong(hex, 16);
                if (size > MAX_BODY - body.size()) throw new IOException("Body exceeds 4 MiB inspection limit");
                if (size == 0) {
                    int trailerSize = 0;
                    for (;;) {
                        String trailer = readLine(input, MAX_HEADERS - trailerSize);
                        if (trailer == null) throw new EOFException("Incomplete HTTP trailer");
                        trailerSize += trailer.length() + 2;
                        if (trailer.isEmpty()) break;
                    }
                    break;
                }
                copyExactly(input, body, (int) size);
                if (input.read() != '\r' || input.read() != '\n') throw new IOException("Invalid chunk ending");
            }
            message.body = body.toByteArray();
        } else if (length != null) {
            if (!length.matches("[0-9]{1,12}")) throw new IOException("Invalid Content-Length");
            long size = Long.parseLong(length);
            if (size > MAX_BODY) throw new IOException("Body exceeds 4 MiB inspection limit");
            message.body = new byte[(int) size];
            readExact(input, message.body, 0, message.body.length);
        } else if (!request) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int countRead;
            while ((countRead = input.read(buffer)) != -1) {
                if (countRead > MAX_BODY - body.size()) throw new IOException("Body exceeds 4 MiB inspection limit");
                body.write(buffer, 0, countRead);
            }
            message.body = body.toByteArray();
        }
        normalizeLength(message, false);
    }

    public static void normalizeLength(Message message, boolean noBody) {
        if (noBody) return;
        boolean hadLength = message.header("Content-Length") != null || message.header("Transfer-Encoding") != null;
        message.remove("Transfer-Encoding");
        message.remove("Trailer");
        if (message.body.length > 0 || hadLength || message.startLine.startsWith("HTTP/"))
            message.set("Content-Length", Integer.toString(message.body.length));
    }

    private static void validateRequestLine(String line) throws IOException {
        String[] parts = line.split(" ", -1);
        if (parts.length != 3 || !isToken(parts[0]) || parts[1].isEmpty() ||
                !(parts[2].equals("HTTP/1.0") || parts[2].equals("HTTP/1.1"))) throw new IOException("Unsupported HTTP request line");
        for (int i = 0; i < parts[1].length(); i++) if (parts[1].charAt(i) <= 32 || parts[1].charAt(i) == 127)
            throw new IOException("Invalid request target character");
    }

    private static void validateHost(Message message) throws IOException {
        int hosts = 0;
        for (String[] header : message.headers) if (header[0].equalsIgnoreCase("Host")) hosts++;
        if (hosts > 1) throw new IOException("Ambiguous Host headers");
    }

    private static boolean isToken(String text) {
        return !text.isEmpty() && text.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    }

    static String readLine(InputStream input, int limit) throws IOException {
        if (limit <= 0) throw new IOException("HTTP headers exceed 64 KiB");
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (;;) {
            int b = input.read();
            if (b == -1) {
                if (line.size() == 0) return null;
                throw new EOFException("Incomplete HTTP line");
            }
            if (b == '\r') {
                if (input.read() != '\n') throw new IOException("HTTP requires CRLF line endings");
                return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
            }
            if (b == '\n' || b == 0) throw new IOException("Invalid HTTP line ending");
            if (line.size() >= limit) throw new IOException("HTTP line exceeds limit");
            line.write(b);
        }
    }

    static void readExact(InputStream input, byte[] bytes, int offset, int length) throws IOException {
        while (length > 0) {
            int count = input.read(bytes, offset, length);
            if (count < 0) throw new EOFException("Incomplete HTTP body");
            offset += count;
            length -= count;
        }
    }

    private static void copyExactly(InputStream input, OutputStream output, int length) throws IOException {
        byte[] buffer = new byte[Math.min(16 * 1024, length)];
        while (length > 0) {
            int count = input.read(buffer, 0, Math.min(buffer.length, length));
            if (count < 0) throw new EOFException("Incomplete HTTP chunk");
            output.write(buffer, 0, count);
            length -= count;
        }
    }
}
