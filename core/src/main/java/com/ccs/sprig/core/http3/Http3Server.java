package com.ccs.sprig.core.http3;

import com.ccs.sprig.core.proxy.HttpCodec;
import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import tech.kwik.core.QuicConnection;
import tech.kwik.core.QuicStream;
import tech.kwik.core.log.NullLogger;
import tech.kwik.core.server.ApplicationProtocolConnection;
import tech.kwik.core.server.ApplicationProtocolConnectionFactory;
import tech.kwik.core.server.ServerConnectionConfig;
import tech.kwik.core.server.ServerConnector;

/** Real loopback QUIC v1/v2 HTTP/3 endpoint; TLS credentials come from the local inspection CA. */
public final class Http3Server implements Closeable {
    public interface Handler {HttpCodec.Message handle(Http3Codec.Request request) throws IOException;}
    private final ServerConnector connector;
    private final DatagramSocket socket;
    private static final ExecutorService REQUESTS=requestExecutor();
    private static ExecutorService requestExecutor(){ThreadPoolExecutor executor=new ThreadPoolExecutor(4,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"sprig-h3-request");t.setDaemon(true);return t;});executor.allowCoreThreadTimeOut(true);return executor;}
    private final Set<QuicConnection> connections=ConcurrentHashMap.newKeySet();
    public Http3Server(KeyStore credentials,Handler handler) throws IOException {
        this(credentials,new DatagramSocket(new InetSocketAddress("127.0.0.1",0)),handler);
    }
    public Http3Server(KeyStore credentials,DatagramSocket socket,Handler handler) throws IOException {
        this.socket=socket;
        Http3Crypto.initialize();
        try {
            Http3Crypto.requireAvailable();
            ServerConnectionConfig config=ServerConnectionConfig.builder().maxIdleTimeoutInSeconds(30)
                    .maxConnectionBufferSize(512*1024).maxBidirectionalStreamBufferSize(64*1024)
                    .maxUnidirectionalStreamBufferSize(64*1024).maxOpenPeerInitiatedBidirectionalStreams(4)
                    .maxOpenPeerInitiatedUnidirectionalStreams(3).maxTotalPeerInitiatedBidirectionalStreams(1000)
                    .maxTotalPeerInitiatedUnidirectionalStreams(3).retryRequired(false).build();
            connector=ServerConnector.builder().withSocket(socket).withKeyStore(credentials,"leaf",new char[0])
                    .withSupportedVersions(Arrays.asList(QuicConnection.QuicVersion.V1,QuicConnection.QuicVersion.V2))
                    .withSupportedGroups(Http3Crypto.groups())
                    .withConfiguration(config).withLogger(new NullLogger()).build();
            connector.registerApplicationProtocol("h3",new ApplicationProtocolConnectionFactory() {
                @Override public int maxConcurrentPeerInitiatedUnidirectionalStreams(){return 3;}
                @Override public int maxConcurrentPeerInitiatedBidirectionalStreams(){return 4;}
                @Override public long maxTotalPeerInitiatedUnidirectionalStreams(){return 3;}
                @Override public long maxUnidirectionalStreamReceiverBufferSize(){return 64*1024;}
                @Override public long maxBidirectionalStreamReceiverBufferSize(){return 64*1024;}
                @Override public ApplicationProtocolConnection createConnection(String protocol,QuicConnection connection) {
                    if(connections.size()>=2){connection.close(Http3Codec.H3_EXCESSIVE_LOAD,"Endpoint connection limit");return new ApplicationProtocolConnection(){};}
                    connections.add(connection);connection.setConnectionListener(event->connections.remove(connection));
                    try {
                        Http3Session session=new Http3Session(connection);
                        return new ApplicationProtocolConnection(){@Override public void acceptPeerInitiatedStream(QuicStream stream){
                            if(stream.isUnidirectional()) session.acceptControl(stream);
                            else if(!stream.isClientInitiatedBidirectional())stream.abortReading(Http3Codec.H3_STREAM_CREATION_ERROR);
                            else try{REQUESTS.execute(()->serve(stream,handler));}
                            catch(RejectedExecutionException error){stream.abortReading(Http3Codec.H3_REQUEST_REJECTED);stream.resetStream(Http3Codec.H3_REQUEST_REJECTED);}
                        }};
                    } catch(IOException error){connection.close(Http3Codec.H3_INTERNAL_ERROR,"Cannot create HTTP/3 control stream");return new ApplicationProtocolConnection(){};}
                }
            });
            connector.start();
        } catch(Exception error){socket.close();throw new IOException("Cannot start HTTP/3 endpoint",error);}
    }
    private static void serve(QuicStream stream,Handler handler) {
        try {
            Http3Codec.Request request=Http3Codec.readRequest(stream.getInputStream());
            HttpCodec.Message response=handler.handle(request);
            Http3Codec.writeResponse(stream.getOutputStream(),response,"HEAD".equalsIgnoreCase(request.message.method()));
        } catch(IOException|RuntimeException error){stream.abortReading(Http3Codec.H3_MESSAGE_ERROR);stream.resetStream(Http3Codec.H3_MESSAGE_ERROR);}
    }
    public int getPort(){return socket.getLocalPort();}
    @Override public void close(){for(QuicConnection connection:connections)connection.close();connector.close();socket.close();connections.clear();}
}
