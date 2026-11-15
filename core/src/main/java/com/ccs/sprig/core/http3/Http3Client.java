package com.ccs.sprig.core.http3;

import com.ccs.sprig.core.proxy.HttpCodec;
import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import tech.kwik.core.QuicClientConnection;
import tech.kwik.core.QuicStream;
import tech.kwik.core.log.NullLogger;

/** Strict HTTP/3 origin client: QUIC TLS1.3 chain and hostname verification remain enabled. */
public final class Http3Client implements Closeable {
    public interface DatagramProtector {boolean protect(DatagramSocket socket);}
    private static final ScheduledExecutorService DEADLINES=deadlineExecutor();
    private static ScheduledExecutorService deadlineExecutor(){
        java.util.concurrent.ScheduledThreadPoolExecutor executor=new java.util.concurrent.ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"sprig-h3-deadlines");t.setDaemon(true);return t;});
        // Fast batches must not retain every cancelled task/QUIC connection until its original deadline.
        executor.setRemoveOnCancelPolicy(true);return executor;
    }
    private final DatagramProtector protector;
    private final KeyStore fixtureTrust;
    private final Semaphore concurrent=new Semaphore(4);
    private final Set<QuicClientConnection> connections=ConcurrentHashMap.newKeySet();
    public Http3Client(DatagramProtector protector){this(protector,null);}
    /** Optional explicit trust store still performs certificate chain and hostname validation. */
    public Http3Client(DatagramProtector protector,KeyStore fixtureTrust){Http3Crypto.initialize();this.protector=protector;this.fixtureTrust=fixtureTrust;}
    /** Null means the essential public JCA algorithms are available on this runtime. */
    public static String availabilityError(){return Http3Crypto.availabilityError();}
    public HttpCodec.Message execute(String host,String connectIp,int port,HttpCodec.Message request) throws IOException {
        return execute(host,connectIp,port,request,null);
    }
    /** Optional HTTP authority does not change the TLS identity or network destination. */
    public HttpCodec.Message execute(String host,String connectIp,int port,HttpCodec.Message request,String requestAuthority) throws IOException {
        com.ccs.sprig.core.net.CallCancellation.check();
        if(requestAuthority!=null) {
            try {
                java.net.URI parsed=java.net.URI.create("https://"+requestAuthority);
                if(parsed.getHost()==null||parsed.getUserInfo()!=null||parsed.getRawQuery()!=null||parsed.getRawFragment()!=null
                        ||!parsed.getRawPath().isEmpty()||parsed.getPort()==0||parsed.getPort()>65535)
                    throw new IllegalArgumentException();
            }catch(IllegalArgumentException error){throw new IOException("Invalid HTTP/3 request authority",error);}
        }
        Http3Crypto.requireAvailable();
        if(!concurrent.tryAcquire())throw new IOException("HTTP/3 concurrent request limit reached");
        QuicClientConnection connection=null;ScheduledFuture<?> deadline=null;
        try {
            QuicClientConnection.Builder builder=QuicClientConnection.newBuilder().host(host).port(port).applicationProtocol("h3")
                    .connectTimeout(Duration.ofSeconds(12)).maxIdleTimeout(Duration.ofSeconds(30))
                    .preferredGroups(java.util.Collections.singletonList(tech.kwik.agent15.TlsConstants.NamedGroup.secp256r1))
                    .supportedGroups(Http3Crypto.groups())
                    .defaultStreamReceiveBufferSize(64L*1024).maxOpenPeerInitiatedUnidirectionalStreams(3)
                    .maxOpenPeerInitiatedBidirectionalStreams(0).preferIPv4().logger(new NullLogger()).socketFactory(destination->{
                        DatagramSocket socket=new DatagramSocket(null);socket.bind(new InetSocketAddress(0));
                        if(protector!=null&&!protector.protect(socket)){socket.close();throw new SocketException("Cannot protect HTTP/3 upstream socket");}
                        return socket;
                    });
            String destination=connectIp==null||connectIp.isEmpty()?host:connectIp;
            java.net.InetAddress resolved=com.ccs.sprig.core.net.HostResolver.resolve(destination,true);
            // Resolve before Kwik constructs any connection threads; host still pins TLS/SNI.
            builder.proxy(resolved.getHostAddress());
            if(fixtureTrust!=null)builder.customTrustStore(fixtureTrust);
            com.ccs.sprig.core.net.CallCancellation.check();
            connection=builder.build();connections.add(connection);
            QuicClientConnection closing=connection;
            com.ccs.sprig.core.net.CallCancellation.track(()->{closing.close();});
            deadline=DEADLINES.schedule(()->{closing.close();},45,TimeUnit.SECONDS);
            Http3Session session=new Http3Session(connection,false);
            connection.setPeerInitiatedStreamCallback(stream->{if(stream.isUnidirectional()) session.acceptControl(stream);else stream.abortReading(Http3Codec.H3_STREAM_CREATION_ERROR);});
            connection.connect();
            session.start();
            QuicStream stream=connection.createStream(true);
            String authority=requestAuthority==null?host+(port==443?"":":"+port):requestAuthority;
            Http3Codec.writeRequest(stream.getOutputStream(),request,authority);
            return Http3Codec.readResponse(stream.getInputStream(),request.method());
        } catch(RuntimeException error){throw new IOException("HTTP/3 connection failed",error);}
        finally {
            if(deadline!=null)deadline.cancel(false);
            if(connection!=null){connections.remove(connection);connection.close();}
            concurrent.release();
        }
    }
    @Override public void close(){for(QuicClientConnection connection:connections)connection.close();connections.clear();}
}
