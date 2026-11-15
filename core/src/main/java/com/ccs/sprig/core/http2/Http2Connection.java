package com.ccs.sprig.core.http2;

import com.ccs.sprig.core.proxy.HttpCodec;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * RFC 9113 framing around bounded HTTP messages. One reader owns inbound frames; a bounded
 * executor dispatches independent server streams. Writers share HPACK ordering, not a network
 * worker or a flow-control wait. Server push and extended CONNECT are deliberately disabled.
 */
public final class Http2Connection implements Closeable {
    public interface Handler {
        HttpCodec.Message handle(HttpCodec.Message request, String authority, String scheme) throws IOException;
    }
    public static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final int DATA=0, HEADERS=1, PRIORITY=2, RST=3, SETTINGS=4, PUSH=5, PING=6, GOAWAY=7, WINDOW=8, CONTINUATION=9;
    private static final int END_STREAM=1, ACK=1, END_HEADERS=4, PADDED=8, PRIORITIZED=32;
    private static final int PROTOCOL_ERROR=1, INTERNAL_ERROR=2, FLOW_CONTROL_ERROR=3, STREAM_CLOSED=5,
            FRAME_SIZE_ERROR=6, REFUSED_STREAM=7, CANCEL=8, COMPRESSION_ERROR=9, ENHANCE_YOUR_CALM=11;
    private static final int FRAME_LIMIT=16384, MAX_STREAMS=8, MAX_BUFFERED=4*1024*1024;
    private static final byte[] EMPTY=new byte[0];
    private final InputStream input;
    private final OutputStream output;
    private final Closeable transport;
    private final boolean server;
    private final Handler handler;
    private final Executor executor;
    private final Hpack hpack=new Hpack();
    private final Object writeLock=new Object(), state=new Object();
    private final Map<Integer,Stream> streams=new HashMap<>();
    private volatile boolean closed,draining;
    private int lastStream,peerFrameLimit=FRAME_LIMIT,peerInitialWindow=65535,buffered;
    private long sendWindow=65535,receiveWindow=65535;
    private int continuationStream;
    private boolean continuationEndStream;
    private int continuationError;
    private ByteArrayOutputStream continuation;
    private int controlFrames;
    private boolean receivedSettings;

    private Http2Connection(InputStream input,OutputStream output,Closeable transport,boolean server,Executor executor,Handler handler) {
        this.input=input;this.output=output;this.transport=transport;this.server=server;this.executor=executor;this.handler=handler;
    }

    public static boolean hasPreface(InputStream input) throws IOException {
        if(!input.markSupported()) throw new IOException("HTTP/2 detection needs a buffered stream");
        input.mark(PREFACE.length);
        try {
            for(byte expected:PREFACE) { int b=input.read();if(b<0 || b!=(expected&255))return false; }
            return true;
        } finally {input.reset();}
    }

    public static void serve(InputStream input,OutputStream output,Closeable transport,Executor executor,Handler handler) throws IOException {
        Http2Connection connection=new Http2Connection(input,output,transport,true,executor,handler);
        try {
            byte[] preface=new byte[PREFACE.length];readFully(input,preface,0,preface.length);
            if(!Arrays.equals(preface,PREFACE)) throw connection.error(PROTOCOL_ERROR,"Invalid HTTP/2 connection preface");
            connection.settings();
            for(Frame frame;(frame=connection.readFrame())!=null;) {
                try {connection.receive(frame);}
                catch(StreamError error) {connection.reset(error.stream,error.code);}
                synchronized(connection.state) {if(connection.draining&&connection.streams.isEmpty())break;}
            }
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
            synchronized(connection.state) {
                while(!connection.streams.isEmpty()) {
                    long remaining=deadline-System.nanoTime();if(remaining<=0)break;
                    try {TimeUnit.NANOSECONDS.timedWait(connection.state,remaining);}
                    catch(InterruptedException error){Thread.currentThread().interrupt();break;}
                }
            }
        } catch(ProtocolError error) {
            connection.goAway(error.code,error.getMessage());throw error;
        } finally {connection.close();}
    }

    /** One origin request on an ALPN-selected h2 socket, with genuine request/response flow control. */
    public static HttpCodec.Message exchange(InputStream input,OutputStream output,Closeable transport,
                                               HttpCodec.Message request,String authority,String scheme) throws IOException {
        Http2Connection c=new Http2Connection(input,output,transport,false,null,null);
        Stream stream=new Stream(1,65535);stream.method=request.method();
        synchronized(c.state) {c.streams.put(1,stream);c.lastStream=1;}
        try {
            synchronized(c.writeLock) {output.write(PREFACE);output.flush();}
            c.settings();
            List<String[]> headers=requestHeaders(request,authority,scheme);
            c.writeHeaders(1,headers,request.body.length==0);
            int offset=0;
            while(!stream.ended) {
                while(offset<request.body.length) {
                    int count=c.reserve(stream,Math.min(c.peerFrameLimit,request.body.length-offset),false);
                    if(count==0)break;
                    c.frame(DATA,offset+count==request.body.length?END_STREAM:0,1,request.body,offset,count);
                    offset+=count;
                }
                Frame frame=c.readFrame();if(frame==null)throw new EOFException("Origin closed HTTP/2 before END_STREAM");
                c.receive(frame);
            }
            if(stream.failure!=null)throw new IOException(stream.failure);
            if(stream.response==null)throw new IOException("HTTP/2 origin omitted final response headers");
            stream.response.body=stream.body.toByteArray();
            checkLength(stream,stream.response,true);
            if(offset<request.body.length)c.reset(1,CANCEL);
            return stream.response;
        } catch(ProtocolError error) {c.goAway(error.code,error.getMessage());throw error;}
        finally {c.close();}
    }

    private void settings() throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        setting(bytes,1,Hpack.MAX_TABLE_BYTES); // header table
        if(!server)setting(bytes,2,0); // never accept server push
        setting(bytes,3,MAX_STREAMS);setting(bytes,4,65535);setting(bytes,5,FRAME_LIMIT);setting(bytes,6,Hpack.MAX_HEADER_BYTES);
        frame(SETTINGS,0,0,bytes.toByteArray());
    }
    private static void setting(ByteArrayOutputStream out,int key,int value) {
        out.write(key>>>8);out.write(key);out.write(value>>>24);out.write(value>>>16);out.write(value>>>8);out.write(value);
    }

    private Frame readFrame() throws IOException {
        int first;
        for(;;) {
            if(server && transport instanceof Socket) {
                synchronized(state) {((Socket)transport).setSoTimeout(streams.isEmpty()?5000:45000);}
            }
            try {first=input.read();break;}
            catch(SocketTimeoutException timeout) {
                if(!server)throw timeout;
                synchronized(state) {if(streams.isEmpty())return null;}
            } catch(IOException failure) {
                synchronized(state) {if(server&&draining&&streams.isEmpty())return null;}
                throw failure;
            }
        }
        if(first<0)return null;
        if(server && transport instanceof Socket)((Socket)transport).setSoTimeout(45000);
        byte[] head=new byte[9];head[0]=(byte)first;readFully(input,head,1,8);
        int length=((head[0]&255)<<16)|((head[1]&255)<<8)|(head[2]&255);
        if(length>FRAME_LIMIT)throw error(FRAME_SIZE_ERROR,"HTTP/2 frame exceeds advertised 16384 bytes");
        int stream=int32(head,5)&0x7fffffff;
        byte[] data=new byte[length];readFully(input,data,0,length);
        return new Frame(head[3]&255,head[4]&255,stream,data);
    }
    private void receive(Frame frame) throws IOException {
        if(continuationStream!=0 && (frame.type!=CONTINUATION || frame.stream!=continuationStream))
            throw error(PROTOCOL_ERROR,"Interleaved HTTP/2 header block");
        if(!receivedSettings) {
            if(frame.type!=SETTINGS || frame.stream!=0 || (frame.flags&ACK)!=0)
                throw error(PROTOCOL_ERROR,"HTTP/2 peer must start with SETTINGS");
            receivedSettings=true;
        }
        if(frame.type!=DATA && ++controlFrames>20000)throw error(ENHANCE_YOUR_CALM,"HTTP/2 control-frame budget exceeded");
        switch(frame.type) {
            case SETTINGS:receiveSettings(frame);break;
            case HEADERS:receiveHeaders(frame);break;
            case CONTINUATION:receiveContinuation(frame);break;
            case DATA:receiveData(frame);break;
            case WINDOW:receiveWindow(frame);break;
            case RST:
                if(frame.stream==0)throw error(PROTOCOL_ERROR,"RST_STREAM requires a stream");
                if(frame.data.length!=4)throw error(FRAME_SIZE_ERROR,"Invalid RST_STREAM length");
                synchronized(state) {
                    Stream s=streams.get(frame.stream);
                    if(s==null && frame.stream>lastStream)throw error(PROTOCOL_ERROR,"RST_STREAM on idle stream");
                    if(s!=null) {
                        s.cancelled=true;s.failure="HTTP/2 peer reset stream: "+(int32(frame.data,0)&0xffffffffL);s.ended=true;
                        if(s.work!=null)s.work.cancel(true);
                        if(server)remove(s);
                        state.notifyAll();
                    }
                }break;
            case PING:
                if(frame.stream!=0)throw error(PROTOCOL_ERROR,"PING must use stream zero");
                if(frame.data.length!=8)throw error(FRAME_SIZE_ERROR,"PING requires eight bytes");
                if((frame.flags&ACK)==0)frame(PING,ACK,0,frame.data);break;
            case GOAWAY:
                if(frame.stream!=0)throw error(PROTOCOL_ERROR,"GOAWAY must use stream zero");
                if(frame.data.length<8)throw error(FRAME_SIZE_ERROR,"Truncated GOAWAY");
                synchronized(state) {
                    draining=true;
                    if(!server && ((int32(frame.data,0)&0x7fffffff)<1 || int32(frame.data,4)!=0))
                        throw error(PROTOCOL_ERROR,"Origin rejected HTTP/2 connection with GOAWAY");
                    state.notifyAll();
                }break;
            case PRIORITY:
                if(frame.stream==0)throw error(PROTOCOL_ERROR,"PRIORITY requires a stream");
                if(frame.data.length!=5)throw error(FRAME_SIZE_ERROR,"Invalid PRIORITY length");
                if((int32(frame.data,0)&0x7fffffff)==frame.stream)throw new StreamError(frame.stream,PROTOCOL_ERROR,"Self-dependent priority");
                break;
            case PUSH:throw error(PROTOCOL_ERROR,"Server push is disabled");
            default:break; // Unknown frame types are explicitly extensible.
        }
    }
    private void receiveSettings(Frame frame) throws IOException {
        if(frame.stream!=0)throw error(PROTOCOL_ERROR,"SETTINGS must use stream zero");
        if((frame.flags&ACK)!=0) {if(frame.data.length!=0)throw error(FRAME_SIZE_ERROR,"SETTINGS ACK has payload");return;}
        if(frame.data.length%6!=0)throw error(FRAME_SIZE_ERROR,"Invalid SETTINGS length");
        synchronized(state) {
            for(int p=0;p<frame.data.length;p+=6) {
                int key=((frame.data[p]&255)<<8)|(frame.data[p+1]&255);long value=int32(frame.data,p+2)&0xffffffffL;
                switch(key) {
                    case 1:hpack.peerTableLimit(value);break;
                    case 2:if(!server || value>1)throw error(PROTOCOL_ERROR,"Invalid ENABLE_PUSH setting");break;
                    case 4:
                        if(value>0x7fffffffL)throw error(FLOW_CONTROL_ERROR,"Invalid initial HTTP/2 window");
                        long delta=value-peerInitialWindow;peerInitialWindow=(int)value;
                        for(Stream stream:streams.values()) {stream.sendWindow+=delta;if(stream.sendWindow>0x7fffffffL)throw error(FLOW_CONTROL_ERROR,"Stream window overflow");}
                        break;
                    case 5:
                        if(value<16384 || value>16777215)throw error(PROTOCOL_ERROR,"Invalid maximum frame setting");
                        peerFrameLimit=(int)Math.min(value,FRAME_LIMIT);break;
                    default:break;
                }
            }
            state.notifyAll();
        }
        frame(SETTINGS,ACK,0,EMPTY);
    }
    private void receiveWindow(Frame frame) throws IOException {
        if(frame.data.length!=4)throw error(FRAME_SIZE_ERROR,"Invalid WINDOW_UPDATE length");
        int increment=int32(frame.data,0)&0x7fffffff;
        if(increment==0) {if(frame.stream==0)throw error(PROTOCOL_ERROR,"Zero connection window increment");throw new StreamError(frame.stream,PROTOCOL_ERROR,"Zero stream window increment");}
        synchronized(state) {
            if(frame.stream==0) {sendWindow+=increment;if(sendWindow>0x7fffffffL)throw error(FLOW_CONTROL_ERROR,"Connection window overflow");}
            else {
                Stream stream=streams.get(frame.stream);
                if(stream==null) {if(frame.stream>lastStream)throw error(PROTOCOL_ERROR,"Window update on idle stream");return;}
                stream.sendWindow+=increment;
                if(stream.sendWindow>0x7fffffffL)throw new StreamError(frame.stream,FLOW_CONTROL_ERROR,"Stream window overflow");
            }
            state.notifyAll();
        }
    }
    private void receiveHeaders(Frame frame) throws IOException {
        if(frame.stream==0)throw error(PROTOCOL_ERROR,"HEADERS requires a stream");
        continuationError=0;
        int start=0,end=frame.data.length;
        if((frame.flags&PADDED)!=0) {if(end==0)throw error(PROTOCOL_ERROR,"Missing header padding");end-=frame.data[start++]&255;}
        if((frame.flags&PRIORITIZED)!=0) {
            if(end-start<5)throw error(FRAME_SIZE_ERROR,"Truncated header priority");
            // HPACK is connection-scoped: decode even a rejected stream's header block.
            if((int32(frame.data,start)&0x7fffffff)==frame.stream)continuationError=PROTOCOL_ERROR;
            start+=5;
        }
        if(end<start)throw error(PROTOCOL_ERROR,"Invalid header padding");
        continuationStream=frame.stream;continuationEndStream=(frame.flags&END_STREAM)!=0;continuation=new ByteArrayOutputStream();
        continuation.write(frame.data,start,end-start);
        if((frame.flags&END_HEADERS)!=0)completeHeaders();
    }
    private void receiveContinuation(Frame frame) throws IOException {
        if(continuationStream==0)throw error(PROTOCOL_ERROR,"Unexpected CONTINUATION");
        if(continuation.size()+frame.data.length>Hpack.MAX_HEADER_BYTES)throw error(ENHANCE_YOUR_CALM,"Compressed headers exceed 64 KiB");
        continuation.write(frame.data);
        if((frame.flags&END_HEADERS)!=0)completeHeaders();
    }
    private void completeHeaders() throws IOException {
        int id=continuationStream,rejected=continuationError;boolean end=continuationEndStream;byte[] bytes=continuation.toByteArray();
        continuationStream=0;continuation=null;
        List<String[]> headers;
        try {headers=hpack.decode(id,bytes);}catch(IOException invalid){throw error(COMPRESSION_ERROR,invalid.getMessage());}
        Stream stream;
        synchronized(state) {
            stream=streams.get(id);
            if(server && stream==null) {
                if((id&1)==0 || id<=lastStream)throw error(PROTOCOL_ERROR,"Invalid client stream identifier");
                lastStream=id;
                if(draining || streams.size()>=MAX_STREAMS) {reset(id,REFUSED_STREAM);return;}
                stream=new Stream(id,peerInitialWindow);streams.put(id,stream);
            } else if(stream==null)throw error(PROTOCOL_ERROR,"Headers on an unknown stream");
        }
        if(rejected!=0)throw new StreamError(id,rejected,"Self-dependent header priority");
        if(stream.receivedEnd || stream.cancelled)throw new StreamError(id,STREAM_CLOSED,"Headers after END_STREAM");
        if(server) {
            if(stream.request==null) {
                stream.request=request(headers,id);stream.authority=pseudo(headers,":authority");stream.scheme=pseudo(headers,":scheme");stream.method=stream.request.method();
            } else {validateTrailers(headers,id);if(!end)throw new StreamError(id,PROTOCOL_ERROR,"Trailers must end the stream");}
        } else {
            validateHeaders(headers,id,false);
            String status=pseudo(headers,":status");
            if(stream.response==null) {
                if(status==null || !status.matches("[1-5][0-9]{2}"))throw new StreamError(id,PROTOCOL_ERROR,"Missing or invalid response status");
                int value=Integer.parseInt(status);
                if(value<200) {if(value==101 || end || ++stream.informational>16)throw new StreamError(id,PROTOCOL_ERROR,"Invalid informational response");return;}
                stream.response=response(headers,id);
            } else {validateTrailers(headers,id);if(!end)throw new StreamError(id,PROTOCOL_ERROR,"Trailers must end the stream");}
        }
        if(end)endIncoming(stream);
    }
    private void receiveData(Frame frame) throws IOException {
        if(frame.stream==0)throw error(PROTOCOL_ERROR,"DATA requires a stream");
        Stream stream;
        synchronized(state) {stream=streams.get(frame.stream);receiveWindow-=frame.data.length;}
        if(receiveWindow<0)throw error(FLOW_CONTROL_ERROR,"Connection receive window exceeded");
        int start=0,end=frame.data.length;
        if((frame.flags&PADDED)!=0) {if(end==0)throw error(PROTOCOL_ERROR,"Missing DATA padding");end-=frame.data[start++]&255;}
        if(end<start)throw error(PROTOCOL_ERROR,"Invalid DATA padding");
        credit(0,frame.data.length);
        if(stream==null) {
            if(frame.stream>lastStream)throw error(PROTOCOL_ERROR,"DATA on idle stream");
            reset(frame.stream,STREAM_CLOSED);return;
        }
        if(stream.receivedEnd || stream.cancelled)throw new StreamError(frame.stream,STREAM_CLOSED,"DATA after END_STREAM");
        if(server?stream.request==null:stream.response==null)throw new StreamError(frame.stream,PROTOCOL_ERROR,"DATA before final headers");
        if(!server && end>start && (stream.method.equalsIgnoreCase("HEAD") || stream.response.status()==204 || stream.response.status()==304))
            throw new StreamError(frame.stream,PROTOCOL_ERROR,"HTTP/2 DATA on a bodyless response");
        stream.receiveWindow-=frame.data.length;
        if(stream.receiveWindow<0)throw new StreamError(frame.stream,FLOW_CONTROL_ERROR,"Stream receive window exceeded");
        int count=end-start;
        synchronized(state) {
            if(stream.body.size()+count>HttpCodec.MAX_BODY || (server&&buffered+count>MAX_BUFFERED))
                throw new StreamError(frame.stream,ENHANCE_YOUR_CALM,"HTTP/2 body memory budget exceeded");
            stream.body.write(frame.data,start,count);buffered+=count;stream.accounted+=count;
        }
        credit(frame.stream,frame.data.length);stream.receiveWindow+=frame.data.length;
        if((frame.flags&END_STREAM)!=0)endIncoming(stream);
    }
    private void credit(int id,int count) throws IOException {
        if(count==0)return;
        if(id==0)receiveWindow+=count;
        byte[] bytes=new byte[4];put32(bytes,0,count);frame(WINDOW,0,id,bytes);
    }
    private void endIncoming(Stream stream) throws IOException {
        stream.receivedEnd=true;
        HttpCodec.Message message=server?stream.request:stream.response;
        if(message==null)throw new StreamError(stream.id,PROTOCOL_ERROR,"Missing HTTP/2 message headers");
        checkLength(stream,message,!server);
        if(!server) {stream.ended=true;return;}
        message.body=stream.body.toByteArray();stream.body=null;
        HttpCodec.normalizeLength(message,false);
        Stream ready=stream;
        FutureTask<Void> task=new FutureTask<>(()->{respond(ready);return null;});stream.work=task;
        try {executor.execute(task);}
        catch(RejectedExecutionException busy) {reset(stream.id,REFUSED_STREAM);}
    }
    private static void checkLength(Stream stream,HttpCodec.Message message,boolean response) throws IOException {
        int length=stream.body==null?message.body.length:stream.body.size();
        String declared=message.header("content-length");
        if(declared==null)return;
        if(response && (stream.method.equalsIgnoreCase("HEAD") || message.status()==304))return;
        try {if(!declared.matches("[0-9]{1,12}") || Long.parseLong(declared)!=length)throw new NumberFormatException();}
        catch(NumberFormatException mismatch){throw new StreamError(stream.id,PROTOCOL_ERROR,"HTTP/2 Content-Length mismatch");}
    }
    private void respond(Stream stream) {
        try {
            if(stream.cancelled)return;
            HttpCodec.Message response;
            try {response=handler.handle(stream.request,stream.authority,stream.scheme);}
            catch(IOException failure) {response=errorResponse(502,failure.getMessage());}
            if(stream.cancelled)return;
            boolean noBody=stream.method.equalsIgnoreCase("HEAD") || response.status()==204 || response.status()==304;
            byte[] body=noBody?EMPTY:response.body;
            writeHeaders(stream.id,responseHeaders(response),body.length==0);
            int offset=0;
            while(offset<body.length) {
                int count=reserve(stream,Math.min(peerFrameLimit,body.length-offset),true);
                frame(DATA,offset+count==body.length?END_STREAM:0,stream.id,body,offset,count);offset+=count;
            }
        } catch(IOException failure) {try {reset(stream.id,INTERNAL_ERROR);}catch(IOException ignored){closeTransport();}}
        finally {
            synchronized(state) {remove(stream);state.notifyAll();if(draining&&streams.isEmpty())closeTransport();}
        }
    }
    private int reserve(Stream stream,int requested,boolean wait) throws IOException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
        synchronized(state) {
            for(;;) {
                if(closed || stream.cancelled)throw new IOException("HTTP/2 stream cancelled");
                int allowed=(int)Math.min(requested,Math.min(sendWindow,stream.sendWindow));
                if(allowed>0) {sendWindow-=allowed;stream.sendWindow-=allowed;return allowed;}
                if(!wait)return 0;
                long remaining=deadline-System.nanoTime();if(remaining<=0)throw new IOException("HTTP/2 send window timed out");
                try {TimeUnit.NANOSECONDS.timedWait(state,remaining);}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("HTTP/2 send interrupted",interrupted);}
            }
        }
    }
    private void writeHeaders(int id,List<String[]> headers,boolean end) throws IOException {
        synchronized(writeLock) {
            byte[] bytes=hpack.encode(id,headers);int offset=0;
            do {
                int count=Math.min(peerFrameLimit,bytes.length-offset);
                int flags=offset+count==bytes.length?END_HEADERS:0;if(offset==0&&end)flags|=END_STREAM;
                frame(offset==0?HEADERS:CONTINUATION,flags,id,bytes,offset,count);offset+=count;
            } while(offset<bytes.length);
        }
    }
    private void frame(int type,int flags,int stream,byte[] payload) throws IOException {frame(type,flags,stream,payload,0,payload.length);}
    private void frame(int type,int flags,int stream,byte[] payload,int offset,int length) throws IOException {
        synchronized(writeLock) {
            if(closed)throw new IOException("HTTP/2 connection closed");
            byte[] head=new byte[9];head[0]=(byte)(length>>>16);head[1]=(byte)(length>>>8);head[2]=(byte)length;
            head[3]=(byte)type;head[4]=(byte)flags;put32(head,5,stream);
            output.write(head);output.write(payload,offset,length);output.flush();
        }
    }
    private void reset(int id,int code) throws IOException {
        synchronized(state) {Stream stream=streams.get(id);if(stream!=null) {stream.cancelled=true;if(stream.work!=null)stream.work.cancel(true);if(server)remove(stream);else {stream.ended=true;stream.failure="HTTP/2 stream reset: "+code;}}state.notifyAll();}
        byte[] data=new byte[4];put32(data,0,code);frame(RST,0,id,data);
    }
    private void remove(Stream stream) {if(streams.remove(stream.id)==stream) {buffered-=stream.accounted;stream.accounted=0;}}
    private void goAway(int code,String reason) {
        try {
            byte[] text=(reason==null?"":reason).getBytes(StandardCharsets.UTF_8);int count=Math.min(256,text.length);
            byte[] bytes=new byte[8+count];put32(bytes,0,lastStream);put32(bytes,4,code);System.arraycopy(text,0,bytes,8,count);frame(GOAWAY,0,0,bytes);
        } catch(IOException ignored) { }
    }
    @Override public void close() {
        synchronized(state) {
            closed=true;
            for(Stream stream:streams.values()) {stream.cancelled=true;if(stream.work!=null)stream.work.cancel(true);}
            streams.clear();buffered=0;state.notifyAll();
        }
        closeTransport();
    }
    private void closeTransport() {if(transport!=null)try {transport.close();}catch(IOException ignored){}}
    private ProtocolError error(int code,String message) {return new ProtocolError(code,message);}

    private static HttpCodec.Message request(List<String[]> headers,int id) throws IOException {
        validateHeaders(headers,id,true);
        String method=pseudo(headers,":method"),path=pseudo(headers,":path"),authority=pseudo(headers,":authority"),scheme=pseudo(headers,":scheme");
        if(method==null || !method.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || method.equals("CONNECT"))throw new StreamError(id,PROTOCOL_ERROR,"Unsupported HTTP/2 method");
        if(authority==null || authority.isEmpty() || path==null || !(path.startsWith("/") || path.equals("*")) || !("https".equals(scheme)||"http".equals(scheme)))
            throw new StreamError(id,PROTOCOL_ERROR,"Missing HTTP/2 pseudo-headers");
        for(int i=0;i<path.length();i++)if(path.charAt(i)<=32 || path.charAt(i)==127)throw new StreamError(id,PROTOCOL_ERROR,"Invalid HTTP/2 request path");
        HttpCodec.Message message=new HttpCodec.Message();message.startLine=method+" "+path+" HTTP/1.1";
        StringBuilder cookies=new StringBuilder();
        for(String[] h:headers)if(!h[0].startsWith(":")) {
            if(h[0].equals("cookie")) {if(cookies.length()>0)cookies.append("; ");cookies.append(h[1]);}
            else if(!h[0].equals("host"))message.headers.add(h);
        }
        if(cookies.length()>0)message.set("Cookie",cookies.toString());message.set("Host",authority);return message;
    }
    private static HttpCodec.Message response(List<String[]> headers,int id) throws IOException {
        validateHeaders(headers,id,false);HttpCodec.Message response=new HttpCodec.Message();response.startLine="HTTP/2 "+pseudo(headers,":status");
        for(String[] h:headers)if(!h[0].startsWith(":"))response.headers.add(h);return response;
    }
    private static void validateHeaders(List<String[]> headers,int id,boolean request) throws IOException {
        boolean regular=false;java.util.HashSet<String> pseudo=new java.util.HashSet<>();boolean length=false;
        for(String[] h:headers) {
            String name=h[0],value=h[1];
            for(int i=0;i<value.length();i++)if((value.charAt(i)<32&&value.charAt(i)!='\t') || value.charAt(i)==127)throw new StreamError(id,PROTOCOL_ERROR,"Invalid header value");
            if(name.startsWith(":")) {
                if(regular || !pseudo.add(name) || !(request?(name.equals(":method")||name.equals(":scheme")||name.equals(":authority")||name.equals(":path")):name.equals(":status")))
                    throw new StreamError(id,PROTOCOL_ERROR,"Invalid HTTP/2 pseudo-header order");
            } else {
                regular=true;
                if(hop(name) || (name.equals("te")&&!value.equals("trailers")))throw new StreamError(id,PROTOCOL_ERROR,"Connection-specific HTTP/2 header");
                if(name.equals("content-length")) {if(length)throw new StreamError(id,PROTOCOL_ERROR,"Duplicate Content-Length");length=true;}
            }
        }
    }
    private static void validateTrailers(List<String[]> headers,int id) throws IOException {
        for(String[] h:headers)if(h[0].startsWith(":")||hop(h[0])||h[0].equals("content-length"))throw new StreamError(id,PROTOCOL_ERROR,"Invalid HTTP/2 trailer");
    }
    private static String pseudo(List<String[]> headers,String name) {for(String[] h:headers)if(h[0].equals(name))return h[1];return null;}
    private static List<String[]> requestHeaders(HttpCodec.Message message,String authority,String scheme) {
        List<String[]> headers=new ArrayList<>();headers.add(new String[]{":method",message.method()});headers.add(new String[]{":scheme",scheme});
        headers.add(new String[]{":authority",authority});headers.add(new String[]{":path",message.startLine.split(" ",3)[1]});regularHeaders(message,headers);return headers;
    }
    private static List<String[]> responseHeaders(HttpCodec.Message message) throws IOException {
        List<String[]> headers=new ArrayList<>();headers.add(new String[]{":status",Integer.toString(message.status())});regularHeaders(message,headers);return headers;
    }
    private static void regularHeaders(HttpCodec.Message message,List<String[]> output) {
        for(String[] h:message.headers) {String name=h[0].toLowerCase(Locale.ROOT);if(!hop(name)&&!name.equals("host")&&(!name.equals("te")||h[1].equals("trailers")))output.add(new String[]{name,h[1]});}
    }
    private static boolean hop(String name) {return name.equals("connection")||name.equals("proxy-connection")||name.equals("keep-alive")||name.equals("transfer-encoding")||name.equals("upgrade");}
    private static HttpCodec.Message errorResponse(int status,String error) {
        HttpCodec.Message message=new HttpCodec.Message();message.startLine="HTTP/2 "+status;
        message.body=("Sprig: "+(error==null?"Origin request failed":error)+"\n").getBytes(StandardCharsets.UTF_8);
        message.set("content-type","text/plain; charset=utf-8");message.set("content-length",Integer.toString(message.body.length));return message;
    }
    private static void readFully(InputStream input,byte[] bytes,int offset,int length) throws IOException {
        while(length>0) {int count=input.read(bytes,offset,length);if(count<0)throw new EOFException("Truncated HTTP/2 frame");offset+=count;length-=count;}
    }
    private static int int32(byte[] bytes,int p) {return ((bytes[p]&255)<<24)|((bytes[p+1]&255)<<16)|((bytes[p+2]&255)<<8)|(bytes[p+3]&255);}
    private static void put32(byte[] bytes,int p,int value) {bytes[p]=(byte)(value>>>24);bytes[p+1]=(byte)(value>>>16);bytes[p+2]=(byte)(value>>>8);bytes[p+3]=(byte)value;}
    private static final class Frame {
        final int type,flags,stream;final byte[] data;
        Frame(int type,int flags,int stream,byte[] data){this.type=type;this.flags=flags;this.stream=stream;this.data=data;}
    }
    private static final class Stream {
        final int id;ByteArrayOutputStream body=new ByteArrayOutputStream();HttpCodec.Message request,response;
        String authority,scheme,method="GET",failure;int informational,accounted;long sendWindow,receiveWindow=65535;
        volatile boolean receivedEnd,ended,cancelled;FutureTask<?> work;
        Stream(int id,int window){this.id=id;sendWindow=window;}
    }
    private static final class ProtocolError extends IOException {
        final int code;ProtocolError(int code,String message){super(message);this.code=code;}
    }
    private static final class StreamError extends IOException {
        final int stream,code;StreamError(int stream,int code,String message){super(message);this.stream=stream;this.code=code;}
    }
}
