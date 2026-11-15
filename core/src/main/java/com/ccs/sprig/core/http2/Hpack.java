package com.ccs.sprig.core.http2;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersDecoder;
import io.netty.handler.codec.http2.DefaultHttp2HeadersEncoder;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Bounded RFC 7541 HPACK using Netty's maintained Java implementation, including Huffman. */
public final class Hpack {
    public static final int MAX_HEADER_BYTES = 64 * 1024, MAX_TABLE_BYTES = 4096;
    static {
        // Avoid probing optional desktop logging integrations on Android.
        io.netty.util.internal.logging.InternalLoggerFactory.setDefaultFactory(
                io.netty.util.internal.logging.JdkLoggerFactory.INSTANCE);
    }
    private final DefaultHttp2HeadersDecoder decoder = new DefaultHttp2HeadersDecoder(true, MAX_HEADER_BYTES);
    private final DefaultHttp2HeadersEncoder encoder = new DefaultHttp2HeadersEncoder((name, value) ->
            name.toString().equals("authorization") || name.toString().equals("proxy-authorization")
                    || name.toString().equals("cookie") || name.toString().equals("set-cookie"));
    public synchronized List<String[]> decode(int stream, byte[] bytes) throws IOException {
        if (bytes.length > MAX_HEADER_BYTES) throw new IOException("Compressed HTTP/2 headers exceed 64 KiB");
        ByteBuf input = Unpooled.wrappedBuffer(bytes);
        try {
            Http2Headers headers = decoder.decodeHeaders(stream, input);
            List<String[]> result = new ArrayList<>(headers.size());
            for (java.util.Map.Entry<CharSequence,CharSequence> entry : headers)
                result.add(new String[]{entry.getKey().toString(), entry.getValue().toString()});
            return result;
        } catch (Http2Exception | IllegalArgumentException error) { throw new IOException("Invalid HPACK header block", error); }
        finally { input.release(); }
    }
    public synchronized byte[] encode(int stream, List<String[]> headers) throws IOException {
        DefaultHttp2Headers values = new DefaultHttp2Headers(true);
        long size = 0;
        try {
            for (String[] header : headers) {
                size += 32L + header[0].length() + header[1].length();
                if (size > MAX_HEADER_BYTES) throw new IOException("HTTP/2 headers exceed 64 KiB");
                values.add(header[0], header[1]);
            }
            ByteBuf output = Unpooled.buffer(512, MAX_HEADER_BYTES);
            try {
                encoder.encodeHeaders(stream, values, output);
                byte[] bytes = new byte[output.readableBytes()]; output.readBytes(bytes); return bytes;
            } finally { output.release(); }
        } catch (Http2Exception | IllegalArgumentException | IndexOutOfBoundsException error) {
            throw new IOException("Cannot encode HTTP/2 headers", error);
        }
    }
    public synchronized void peerTableLimit(long bytes) throws IOException {
        try { encoder.configuration().maxHeaderTableSize(Math.min(bytes, MAX_TABLE_BYTES)); }
        catch (Http2Exception error) { throw new IOException("Invalid HPACK table setting", error); }
    }
}
