package com.ccs.sprig.core.proxy;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLParameters;
import com.ccs.sprig.core.http2.Alpn;
import java.util.concurrent.TimeUnit;

/** Blocking TLS adapter. One reader and independently serialized writers support HTTP/2 streams. */
final class TlsStreams {
    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);
    private static final int MAX_BUFFER = 128 * 1024;
    private final SSLEngine engine;
    private final InputStream encryptedInput;
    private final OutputStream encryptedOutput;
    private ByteBuffer incoming;
    private ByteBuffer outgoing;
    private ByteBuffer plaintext;
    private volatile boolean eof;
    private final Object readLock = new Object(), writeLock = new Object(), taskLock = new Object(), progress = new Object();
    String applicationProtocol() { return Alpn.selected(engine); }
    final InputStream input = new InputStream() {
        @Override public int read() throws IOException {
            synchronized (readLock) {
                fillPlaintext();
                return plaintext.hasRemaining() ? plaintext.get() & 255 : -1;
            }
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
            if (length == 0) return 0;
            synchronized (readLock) {
                fillPlaintext();
                if (!plaintext.hasRemaining()) return -1;
                int count = Math.min(length, plaintext.remaining());
                plaintext.get(bytes, offset, count);
                return count;
            }
        }
    };

    private void fillPlaintext() throws IOException {
        while (!plaintext.hasRemaining() && !eof) {
            plaintext.clear();
            unwrap();
            plaintext.flip();
            handleHandshake();
        }
    }
    final OutputStream output = new OutputStream() {
        @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
            ByteBuffer source = ByteBuffer.wrap(bytes, offset, length);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            while (source.hasRemaining()) {
                runTasks();
                SSLEngineResult result = wrap(source);
                if (result.bytesConsumed() == 0 && result.bytesProduced() == 0) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw new IOException("TLS output handshake timed out");
                    synchronized (progress) {
                        try { TimeUnit.NANOSECONDS.timedWait(progress, Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100))); }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("TLS write interrupted", error); }
                    }
                }
            }
            runTasks();
            while (engine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_WRAP) wrap(EMPTY.duplicate());
        }
        @Override public void flush() throws IOException { synchronized (writeLock) { encryptedOutput.flush(); } }
    };

    TlsStreams(SSLContext context, InputStream input, OutputStream output) throws IOException {
        encryptedInput = input;
        encryptedOutput = output;
        engine = context.createSSLEngine();
        engine.setUseClientMode(false);
        List<String> protocols = new ArrayList<>();
        for (String p : engine.getSupportedProtocols()) if (p.equals("TLSv1.2") || p.equals("TLSv1.3")) protocols.add(p);
        engine.setEnabledProtocols(protocols.toArray(new String[0]));
        SSLParameters parameters = engine.getSSLParameters();
        Alpn.configure(parameters, "h2", "http/1.1");
        engine.setSSLParameters(parameters);
        int packet = engine.getSession().getPacketBufferSize();
        int application = engine.getSession().getApplicationBufferSize();
        incoming = ByteBuffer.allocate(Math.max(packet * 2, 32 * 1024));
        incoming.flip();
        outgoing = ByteBuffer.allocate(Math.max(packet * 2, 32 * 1024));
        plaintext = ByteBuffer.allocate(Math.max(application * 2, 32 * 1024));
        plaintext.flip();
        engine.beginHandshake();
        handleHandshake();
    }

    private void handleHandshake() throws IOException {
        int steps = 0;
        for (;;) {
            if (++steps > 512) throw new IOException("TLS handshake made no progress");
            switch (engine.getHandshakeStatus()) {
                case NEED_TASK:
                    runTasks();
                    break;
                case NEED_WRAP:
                    wrap(EMPTY.duplicate());
                    break;
                case NEED_UNWRAP:
                    plaintext.compact();
                    unwrap();
                    plaintext.flip();
                    if (eof) throw new EOFException("Peer closed during TLS handshake");
                    break;
                case FINISHED:
                case NOT_HANDSHAKING:
                    return;
                default:
                    // Handles NEED_UNWRAP_AGAIN on newer engines without linking to a newer enum API.
                    plaintext.compact();
                    unwrap();
                    plaintext.flip();
                    break;
            }
        }
    }

    private void unwrap() throws IOException {
        for (;;) {
            runTasks();
            SSLEngineResult result = engine.unwrap(incoming, plaintext);
            synchronized (progress) { progress.notifyAll(); }
            switch (result.getStatus()) {
                case OK:
                    if (result.bytesConsumed() == 0 && result.bytesProduced() == 0
                            && result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) readEncrypted();
                    else return;
                    break;
                case BUFFER_UNDERFLOW:
                    readEncrypted();
                    break;
                case BUFFER_OVERFLOW:
                    plaintext = growWrite(plaintext);
                    break;
                case CLOSED:
                    eof = true;
                    return;
            }
        }
    }

    private SSLEngineResult wrap(ByteBuffer source) throws IOException {
        synchronized (writeLock) { for (;;) {
            outgoing.clear();
            SSLEngineResult result = engine.wrap(source, outgoing);
            if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                outgoing = growWrite(outgoing);
                continue;
            }
            outgoing.flip();
            if (outgoing.hasRemaining()) {
                encryptedOutput.write(outgoing.array(), outgoing.position(), outgoing.remaining());
                encryptedOutput.flush();
            }
            if (result.getStatus() == SSLEngineResult.Status.CLOSED) {
                eof = true;
                if (source.hasRemaining()) throw new EOFException("TLS output is closed");
            }
            synchronized (progress) { progress.notifyAll(); }
            return result;
        } }
    }

    private void runTasks() {
        synchronized (taskLock) { Runnable task; while ((task = engine.getDelegatedTask()) != null) task.run(); }
    }

    private void readEncrypted() throws IOException {
        incoming.compact();
        if (!incoming.hasRemaining()) incoming = growWrite(incoming);
        int count = encryptedInput.read(incoming.array(), incoming.position(), incoming.remaining());
        if (count < 0) {
            eof = true;
            throw new EOFException("TLS peer closed without close_notify");
        }
        incoming.position(incoming.position() + count);
        incoming.flip();
    }

    private static ByteBuffer growWrite(ByteBuffer buffer) throws IOException {
        if (buffer.capacity() >= MAX_BUFFER) throw new IOException("TLS buffer exceeds bounded limit");
        ByteBuffer grown = ByteBuffer.allocate(Math.min(MAX_BUFFER, buffer.capacity() * 2));
        buffer.flip();
        grown.put(buffer);
        return grown;
    }
}
