package com.ccs.sprig.core.http3;

import com.ccs.sprig.core.proxy.HttpCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tech.kwik.qpack.Decoder;
import tech.kwik.qpack.Encoder;

/** RFC 9114 message framing with RFC 9204 QPACK; dynamic tables are advertised as zero capacity. */
public final class Http3Codec {
    public static final long H3_NO_ERROR=0x100,H3_GENERAL_PROTOCOL_ERROR=0x101,H3_INTERNAL_ERROR=0x102,
            H3_STREAM_CREATION_ERROR=0x103,H3_CLOSED_CRITICAL_STREAM=0x104,H3_FRAME_UNEXPECTED=0x105,
            H3_EXCESSIVE_LOAD=0x107,H3_SETTINGS_ERROR=0x109,H3_MISSING_SETTINGS=0x10a,
            H3_REQUEST_REJECTED=0x10b,H3_MESSAGE_ERROR=0x10e;
    static final int MAX_HEADERS=64*1024, MAX_BODY=HttpCodec.MAX_BODY;
    private Http3Codec() { }

    public static final class Request {
        public final HttpCodec.Message message;
        public final String authority;
        Request(HttpCodec.Message message,String authority) {this.message=message;this.authority=authority;}
    }
    public static Request readRequest(InputStream input) throws IOException {
        MessageParts parts=readMessage(input,false,false);
        String method=parts.pseudo.get(":method"),path=parts.pseudo.get(":path"),scheme=parts.pseudo.get(":scheme"),authority=parts.pseudo.get(":authority");
        if(method==null||!method.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")||"CONNECT".equals(method)
                ||path==null||!path.startsWith("/")||!"https".equals(scheme)||authority==null||authority.isEmpty()
                ||parts.pseudo.size()!=4) throw new IOException("Malformed or unsupported HTTP/3 request pseudo-headers");
        for(int i=0;i<path.length();i++) if(path.charAt(i)<=32||path.charAt(i)==127) throw new IOException("Invalid HTTP/3 request path");
        HttpCodec.Message message=parts.message;
        message.startLine=method+" "+path+" HTTP/1.1";
        message.set("Host",authority); message.set("Content-Length",Integer.toString(message.body.length));
        return new Request(message,authority);
    }
    public static HttpCodec.Message readResponse(InputStream input,String method) throws IOException {
        MessageParts parts=readMessage(input,true,"HEAD".equalsIgnoreCase(method));
        String status=parts.pseudo.get(":status");
        if(status==null||!status.matches("[1-5][0-9]{2}")||parts.pseudo.size()!=1) throw new IOException("Invalid HTTP/3 status pseudo-header");
        parts.message.startLine="HTTP/3 "+status;
        if(!"HEAD".equalsIgnoreCase(method)&&!status.equals("204")&&!status.equals("304"))
            parts.message.set("Content-Length",Integer.toString(parts.message.body.length));
        return parts.message;
    }
    private static MessageParts readMessage(InputStream input,boolean response,boolean head) throws IOException {
        HttpCodec.Message message=new HttpCodec.Message();
        Map<String,String> pseudo=null; boolean trailers=false;
        ByteArrayOutputStream body=new ByteArrayOutputStream();
        int frames=0;
        for(;;) {
            long type=readVarInt(input,true); if(type<0) break;
            if(++frames>8192) throw new IOException("Too many HTTP/3 frames");
            long length=readVarInt(input,false);
            if(length>MAX_BODY) throw new IOException("HTTP/3 frame exceeds inspection limit");
            if(type==1) {
                if(length>MAX_HEADERS) throw new IOException("HTTP/3 headers exceed 64 KiB");
                List<Map.Entry<String,String>> fields=decodeHeaders(readBytes(input,(int)length));
                Map<String,String> section=new HashMap<>(); boolean ordinary=false; int size=0;
                for(Map.Entry<String,String> field:fields) {
                    String name=field.getKey(),value=field.getValue();
                    size+=name.length()+value.length()+32;
                    if(size>MAX_HEADERS) throw new IOException("Decoded HTTP/3 headers exceed 64 KiB");
                    validateHeader(name,value);
                    if(name.startsWith(":")) {
                        if(ordinary||pseudo!=null||section.put(name,value)!=null) throw new IOException("Misordered or duplicate HTTP/3 pseudo-header");
                    } else {
                        ordinary=true;
                        if(pseudo==null) message.headers.add(new String[]{name,value});
                        else if(name.equals("content-length")||name.equals("host")) throw new IOException("Invalid HTTP/3 trailer");
                    }
                }
                if(pseudo==null) {
                    if(response&&section.containsKey(":status")&&section.get(":status").startsWith("1")) {
                        message.headers.clear(); continue;
                    }
                    pseudo=section;
                } else {if(trailers) throw new IOException("Multiple HTTP/3 trailer blocks");trailers=true;}
            } else if(type==0) {
                if(pseudo==null||trailers) throw new IOException("HTTP/3 DATA outside message body");
                if(head&&length>0) throw new IOException("HTTP/3 HEAD response contains DATA");
                if(length>MAX_BODY-body.size()) throw new IOException("HTTP/3 body exceeds inspection limit");
                copyExactly(input,body,(int)length);
            } else if(type==4||type==7||type==3||type==5||type==13||type==2||type==6||type==8||type==9) {
                throw new IOException("Unexpected HTTP/3 frame on request stream");
            } else skipExactly(input,length);
        }
        if(pseudo==null) throw new IOException("HTTP/3 stream closed before headers");
        message.body=body.toByteArray();
        String contentLength=null;
        for(String[] header:message.headers) if(header[0].equals("content-length")) {
            if(contentLength!=null) throw new IOException("Duplicate HTTP/3 Content-Length"); contentLength=header[1];
        }
        if(contentLength!=null) {
            if(!contentLength.matches("[0-9]{1,12}")) throw new IOException("Invalid HTTP/3 Content-Length");
            boolean metadataOnly=head||response&&("204".equals(pseudo.get(":status"))||"304".equals(pseudo.get(":status")));
            if(!metadataOnly&&Long.parseLong(contentLength)!=message.body.length) throw new IOException("HTTP/3 Content-Length mismatch");
        }
        return new MessageParts(message,pseudo);
    }
    public static void writeRequest(OutputStream out,HttpCodec.Message request,String authority) throws IOException {
        String[] line=request.startLine.split(" ",3);
        if(line.length!=3) throw new IOException("Invalid request line");
        List<Map.Entry<String,String>> headers=new ArrayList<>();
        field(headers,":method",line[0]);field(headers,":scheme","https");field(headers,":authority",authority);field(headers,":path",line[1]);
        appendHeaders(headers,request,true);
        writeMessage(out,headers,request.body);
    }
    public static void writeResponse(OutputStream out,HttpCodec.Message response,boolean head) throws IOException {
        List<Map.Entry<String,String>> headers=new ArrayList<>();field(headers,":status",Integer.toString(response.status()));
        appendHeaders(headers,response,false);
        int status=response.status();
        writeMessage(out,headers,head||status==204||status==304?new byte[0]:response.body);
    }
    private static void appendHeaders(List<Map.Entry<String,String>> fields,HttpCodec.Message message,boolean request) throws IOException {
        for(String[] header:message.headers) {
            String name=header[0].toLowerCase(Locale.ROOT);
            if(name.equals("host")||name.equals("connection")||name.equals("proxy-connection")||name.equals("keep-alive")
                    ||name.equals("transfer-encoding")||name.equals("upgrade")||name.equals("te")||name.equals("trailer")) continue;
            validateHeader(name,header[1]);field(fields,name,header[1]);
        }
    }
    private static void writeMessage(OutputStream out,List<Map.Entry<String,String>> headers,byte[] body) throws IOException {
        if(body.length>MAX_BODY) throw new IOException("HTTP/3 body exceeds inspection limit");
        byte[] block=encodeHeaders(headers);writeFrame(out,1,block);
        for(int offset=0;offset<body.length;offset+=16384) {
            int count=Math.min(16384,body.length-offset);writeVarInt(out,0);writeVarInt(out,count);out.write(body,offset,count);
        }
        out.close();
    }
    static byte[] encodeHeaders(List<Map.Entry<String,String>> headers) throws IOException {
        long size=0;for(Map.Entry<String,String> h:headers) size+=h.getKey().length()+h.getValue().length()+32;
        if(size>MAX_HEADERS) throw new IOException("HTTP/3 headers exceed peer limit");
        ByteBuffer encoded=Encoder.newBuilder().build().compressHeaders(headers);encoded.flip();
        if(encoded.remaining()>MAX_HEADERS)throw new IOException("Encoded HTTP/3 headers exceed 64 KiB");
        byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);return bytes;
    }
    static List<Map.Entry<String,String>> decodeHeaders(byte[] bytes) throws IOException {
        if(bytes.length<2||bytes[0]!=0||bytes[1]!=0) throw new IOException("Dynamic QPACK table reference with zero advertised capacity");
        try{return Decoder.newBuilder().build().decodeStream(new ByteArrayInputStream(bytes));}
        catch(RuntimeException e){throw new IOException("Invalid QPACK header block",e);}
    }
    static void writeSettings(OutputStream output) throws IOException {
        writeVarInt(output,0);
        ByteArrayOutputStream settings=new ByteArrayOutputStream();
        writeVarInt(settings,1);writeVarInt(settings,0); // QPACK_MAX_TABLE_CAPACITY
        writeVarInt(settings,7);writeVarInt(settings,0); // QPACK_BLOCKED_STREAMS
        writeVarInt(settings,6);writeVarInt(settings,MAX_HEADERS);
        writeFrame(output,4,settings.toByteArray()); output.flush();
    }
    static void readControl(InputStream input) throws IOException {
        boolean settings=false;int count=0;
        for(;;) {
            long type=readVarInt(input,true);if(type<0) throw new IOException("HTTP/3 critical control stream closed");
            long length=readVarInt(input,false);
            if(++count>1024||length>MAX_HEADERS) throw new IOException("HTTP/3 control limit exceeded");
            if(!settings&&type!=4) throw new IOException("Missing HTTP/3 SETTINGS");
            if(type==4) {
                if(settings) throw new IOException("Duplicate HTTP/3 SETTINGS");settings=true;
                ByteArrayInputStream values=new ByteArrayInputStream(readBytes(input,(int)length));Map<Long,Long> seen=new HashMap<>();
                while(values.available()>0) {
                    long key=readVarInt(values,false),value=readVarInt(values,false);
                    if(seen.put(key,value)!=null||key>=2&&key<=5) throw new IOException("Invalid HTTP/3 setting");
                }
            } else if(type==0||type==1||type==5) throw new IOException("Invalid frame on HTTP/3 control stream");
            else skipExactly(input,length);
        }
    }
    static void writeFrame(OutputStream output,long type,byte[] payload) throws IOException {writeVarInt(output,type);writeVarInt(output,payload.length);output.write(payload);}
    public static long readVarInt(InputStream input,boolean allowEof) throws IOException {
        int first=input.read();if(first<0){if(allowEof)return -1;throw new EOFException("Truncated QUIC variable integer");}
        int bytes=1<<(first>>>6);long value=first&63;
        for(int i=1;i<bytes;i++){int b=input.read();if(b<0)throw new EOFException("Truncated QUIC variable integer");value=(value<<8)|b;}
        return value;
    }
    public static void writeVarInt(OutputStream output,long value) throws IOException {
        if(value<0||value>0x3fffffffffffffffL) throw new IOException("Invalid QUIC integer");
        int bytes=value<64?1:value<16384?2:value<(1L<<30)?4:8;
        long encoded=value|(long)(bytes==1?0:bytes==2?1:bytes==4?2:3)<<(bytes*8-2);
        for(int i=bytes-1;i>=0;i--) output.write((int)(encoded>>>(8*i))&255);
    }
    static byte[] readBytes(InputStream input,int length) throws IOException {byte[] out=new byte[length];int p=0;while(p<length){int n=input.read(out,p,length-p);if(n<0)throw new EOFException("Truncated HTTP/3 frame");p+=n;}return out;}
    private static void copyExactly(InputStream in,OutputStream out,int length) throws IOException {byte[] buf=new byte[Math.min(16384,Math.max(1,length))];while(length>0){int n=in.read(buf,0,Math.min(buf.length,length));if(n<0)throw new EOFException("Truncated HTTP/3 DATA");out.write(buf,0,n);length-=n;}}
    private static void skipExactly(InputStream input,long length) throws IOException {byte[] buf=new byte[4096];while(length>0){int n=input.read(buf,0,(int)Math.min(length,buf.length));if(n<0)throw new EOFException("Truncated HTTP/3 frame");length-=n;}}
    private static void field(List<Map.Entry<String,String>> list,String name,String value) {list.add(new AbstractMap.SimpleImmutableEntry<>(name,value));}
    private static void validateHeader(String name,String value) throws IOException {
        if(name.isEmpty()||!name.equals(name.toLowerCase(Locale.ROOT))||!name.matches(":?[!#$%&'*+.^_`|~0-9a-z-]+")) throw new IOException("Invalid HTTP/3 header name");
        if(name.equals("connection")||name.equals("proxy-connection")||name.equals("keep-alive")||name.equals("transfer-encoding")||name.equals("upgrade")||name.equals("te")&&!value.equals("trailers")) throw new IOException("Connection-specific HTTP/3 header");
        for(int i=0;i<value.length();i++)if(value.charAt(i)<32&&value.charAt(i)!='\t'||value.charAt(i)==127)throw new IOException("Invalid HTTP/3 header value");
    }
    private static final class MessageParts {
        final HttpCodec.Message message;final Map<String,String> pseudo;
        MessageParts(HttpCodec.Message message,Map<String,String> pseudo){this.message=message;this.pseudo=pseudo;}
    }
}
