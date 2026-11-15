package com.ccs.sprig.core.http3;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import tech.kwik.core.QuicConnection;
import tech.kwik.core.QuicStream;

/** Bounded handlers for the three long-lived peer control/QPACK streams. */
final class Http3Session {
    private static final ExecutorService CONTROL=new ThreadPoolExecutor(0,32,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(r,"sprig-h3-control");t.setDaemon(true);return t;});
    private final QuicConnection connection;
    private final Set<Long> types=ConcurrentHashMap.newKeySet();
    Http3Session(QuicConnection connection) throws IOException {
        this(connection,true);
    }
    Http3Session(QuicConnection connection,boolean start) throws IOException {
        this.connection=connection;
        if(start)start();
    }
    void start() throws IOException {Http3Codec.writeSettings(connection.createStream(false).getOutputStream());}
    void acceptControl(QuicStream stream) {
        try {CONTROL.execute(()->readControl(stream));}
        catch(RejectedExecutionException error){stream.abortReading(Http3Codec.H3_EXCESSIVE_LOAD);connection.close(Http3Codec.H3_EXCESSIVE_LOAD,"HTTP/3 control stream limit");}
    }
    private void readControl(QuicStream stream) {
        try {
            InputStream input=stream.getInputStream();long type=Http3Codec.readVarInt(input,false);
            if(type<=3&&!types.add(type)) throw new IOException("Duplicate HTTP/3 critical stream");
            if(type==0) Http3Codec.readControl(input);
            else if(type==1) throw new IOException("HTTP/3 push is disabled");
            else if(type==2) {
                // Capacity zero permits a capacity-zero instruction, but no insertions.
                int value;while((value=input.read())>=0)if(value!=0x20)throw new IOException("QPACK insertion with zero table capacity");
                throw new IOException("QPACK encoder stream closed");
            } else if(type==3) {
                if(input.read()>=0) throw new IOException("Unexpected QPACK acknowledgement for static-only encoding");
                throw new IOException("QPACK decoder stream closed");
            } else {
                // Extension streams are not inspected and cannot occupy unbounded receiver buffers.
                stream.abortReading(Http3Codec.H3_STREAM_CREATION_ERROR);
            }
        } catch(IOException|RuntimeException error) {connection.close(Http3Codec.H3_GENERAL_PROTOCOL_ERROR,"Invalid HTTP/3 control stream");}
    }
}
