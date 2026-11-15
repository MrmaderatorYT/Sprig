package com.ccs.sprig.core.proxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Reads only the initial TLS records; all consumed bytes are replayed to TLS or to the origin. */
final class ClientHello {
    final byte[] consumed;
    final boolean tls;
    final String serverName;

    private ClientHello(byte[] consumed, boolean tls, String serverName) {
        this.consumed = consumed;
        this.tls = tls;
        this.serverName = serverName;
    }

    static ClientHello read(InputStream input) throws IOException {
        int first = input.read();
        if (first == -1) return new ClientHello(new byte[0], false, null);
        if (first != 22) return new ClientHello(new byte[]{(byte) first}, false, null);
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        int recordType = first;
        int required = -1;
        while (wire.size() < 64 * 1024) {
            byte[] header = new byte[5];
            header[0] = (byte) recordType;
            HttpCodec.readExact(input, header, 1, 4);
            int length = u16(header, 3);
            if (recordType != 22 || header[1] != 3 || length <= 0 || length > 18 * 1024)
                throw new IOException("Invalid initial TLS record");
            if (wire.size() + 5 + length > 64 * 1024) throw new IOException("TLS ClientHello exceeds 64 KiB");
            byte[] body = new byte[length];
            HttpCodec.readExact(input, body, 0, body.length);
            wire.write(header);
            wire.write(body);
            handshake.write(body);
            byte[] bytes = handshake.toByteArray();
            if (required < 0 && bytes.length >= 4) {
                if (bytes[0] != 1) throw new IOException("Expected TLS ClientHello");
                required = 4 + ((bytes[1] & 255) << 16) + ((bytes[2] & 255) << 8) + (bytes[3] & 255);
                if (required > 64 * 1024) throw new IOException("TLS ClientHello exceeds 64 KiB");
            }
            if (required >= 0 && bytes.length >= required)
                return new ClientHello(wire.toByteArray(), true, parseServerName(Arrays.copyOfRange(bytes, 4, required)));
            recordType = input.read();
            if (recordType < 0) throw new IOException("Incomplete TLS ClientHello");
        }
        throw new IOException("TLS ClientHello exceeds 64 KiB");
    }

    static String parseServerName(byte[] hello) throws IOException {
        try {
            int p = 34;
            p += 1 + (hello[p] & 255);
            p += 2 + u16(hello, p);
            p += 1 + (hello[p] & 255);
            if (p == hello.length) return null;
            int end = p + 2 + u16(hello, p);
            p += 2;
            if (end > hello.length) throw new IOException("Invalid TLS extensions");
            while (p < end) {
                int type = u16(hello, p);
                int length = u16(hello, p + 2);
                p += 4;
                if (p + length > end) throw new IOException("Invalid TLS extension length");
                if (type == 0) {
                    int listEnd = p + 2 + u16(hello, p);
                    int q = p + 2;
                    if (listEnd != p + length) throw new IOException("Invalid SNI list");
                    while (q < listEnd) {
                        int kind = hello[q++] & 255;
                        int size = u16(hello, q);
                        q += 2;
                        if (size == 0 || q + size > listEnd) throw new IOException("Invalid SNI hostname");
                        if (kind == 0) {
                            for (int i = q; i < q + size; i++) if ((hello[i] & 255) > 127 || hello[i] == 0)
                                throw new IOException("SNI hostname must be ASCII");
                            return CertificateAuthority.normalizeHost(new String(hello, q, size, StandardCharsets.US_ASCII));
                        }
                        q += size;
                    }
                }
                p += length;
            }
            return null;
        } catch (IndexOutOfBoundsException e) { throw new IOException("Malformed TLS ClientHello", e); }
    }

    private static int u16(byte[] value, int offset) { return ((value[offset] & 255) << 8) | (value[offset + 1] & 255); }
}
