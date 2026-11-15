package com.ccs.sprig.core.websocket;

import com.ccs.sprig.core.proxy.HttpCodec;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** RFC 6455 handshake validation and explicit compression negotiation removal. */
public final class WebSocketHandshake {
    private static final String GUID="258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private WebSocketHandshake(){}
    public static boolean isWebSocket(HttpCodec.Message request){return "websocket".equalsIgnoreCase(request.header("Upgrade"));}
    public static void validateRequest(HttpCodec.Message request)throws IOException {
        if(!request.startLine.startsWith("GET ")||!request.startLine.endsWith(" HTTP/1.1")||request.body.length!=0)throw new IOException("WebSocket needs a bodyless HTTP/1.1 GET");
        if(!isWebSocket(request)||!token(request,"Connection","upgrade"))throw new IOException("Missing WebSocket Upgrade/Connection headers");
        String version=single(request,"Sec-WebSocket-Version"),key=single(request,"Sec-WebSocket-Key");
        if(!"13".equals(version))throw new IOException("Only WebSocket version 13 is supported");
        try{byte[] decoded=Base64.getDecoder().decode(key);if(decoded.length!=16||!Base64.getEncoder().encodeToString(decoded).equals(key))throw new IllegalArgumentException();}
        catch(IllegalArgumentException error){throw new IOException("Sec-WebSocket-Key must encode exactly 16 bytes");}
        single(request,"Upgrade");
        if(request.header("Host")==null)throw new IOException("WebSocket request needs Host");
        protocols(offeredProtocols(request));
    }
    public static void prepareRequest(HttpCodec.Message request)throws IOException {
        validateRequest(request);
        String connection=request.header("Connection");
        if(connection!=null)for(String name:connection.split(","))if(!name.trim().equalsIgnoreCase("Upgrade"))request.remove(name.trim());
        request.remove("Proxy-Authorization");request.remove("Proxy-Connection");request.remove("Keep-Alive");request.remove("TE");request.remove("Trailer");request.remove("Transfer-Encoding");request.remove("Content-Length");
        request.remove("Sec-WebSocket-Extensions");request.set("Connection","Upgrade");request.set("Upgrade","websocket");
        String protocols=offeredProtocols(request);if(protocols!=null)request.set("Sec-WebSocket-Protocol",protocols);
        validateRequest(request);
    }
    public static void validateResponse(HttpCodec.Message request,HttpCodec.Message response)throws IOException {
        if(response.status()!=101||!response.startLine.startsWith("HTTP/1.1 "))throw new IOException("Origin refused WebSocket upgrade");
        if(!"websocket".equalsIgnoreCase(single(response,"Upgrade"))||!token(response,"Connection","upgrade"))throw new IOException("Invalid origin WebSocket Upgrade/Connection");
        if(!accept(single(request,"Sec-WebSocket-Key")).equals(single(response,"Sec-WebSocket-Accept")))throw new IOException("Invalid Sec-WebSocket-Accept");
        if(response.header("Content-Length")!=null||response.header("Transfer-Encoding")!=null)throw new IOException("WebSocket 101 must not include body framing");
        if(response.header("Sec-WebSocket-Extensions")!=null)throw new IOException("Origin negotiated an unoffered WebSocket extension");
        String selected=optionalSingle(response,"Sec-WebSocket-Protocol");
        if(selected!=null&&(!protocols(offeredProtocols(request)).contains(selected)||selected.contains(",")))throw new IOException("Origin selected an unoffered WebSocket subprotocol");
    }
    public static String accept(String key)throws IOException {
        try{return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key+GUID).getBytes(StandardCharsets.US_ASCII)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IOException(impossible);}
    }
    public static HttpCodec.Message clientRequest(URI uri,String extraHeaders,SecureRandom random)throws IOException {
        if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null||!("ws".equalsIgnoreCase(uri.getScheme())||"wss".equalsIgnoreCase(uri.getScheme())))throw new IOException("Use a ws:// or wss:// URL without credentials or fragment");
        if(extraHeaders==null)extraHeaders="";if(extraHeaders.length()>16384)throw new IOException("Extra WebSocket headers exceed 16 KiB");
        String extras=extraHeaders.replace("\r\n","\n").replace('\r','\n');
        for(String line:extras.split("\n")){int colon=line.indexOf(':');if(colon<=0&& !line.trim().isEmpty())throw new IOException("Enter one HTTP header per line");if(colon>0){String key=line.substring(0,colon).trim().toLowerCase(Locale.ROOT);if(key.equals("host")||key.equals("connection")||key.equals("upgrade")||key.equals("content-length")||key.equals("transfer-encoding")||key.startsWith("sec-websocket-")&&!key.equals("sec-websocket-protocol"))throw new IOException("Reserved handshake header: "+key);}}
        String path=uri.getRawPath();if(path==null||path.isEmpty())path="/";if(uri.getRawQuery()!=null)path+="?"+uri.getRawQuery();
        String host=uri.getHost();if(host.startsWith("[")&&host.endsWith("]"))host=host.substring(1,host.length()-1);if(host.contains(":"))host="["+host+"]";if(uri.getPort()!=-1)host+=":"+uri.getPort();
        byte[] nonce=new byte[16];random.nextBytes(nonce);
        extras=extras.replace("\n","\r\n").replaceAll("[\\r\\n]+$","");
        String raw="GET "+path+" HTTP/1.1\r\nHost: "+host+"\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: "+Base64.getEncoder().encodeToString(nonce)+"\r\n"+(extras.isEmpty()?"":extras+"\r\n")+"\r\n";
        HttpCodec.Message request=HttpCodec.parseEditedRequest(raw.getBytes(StandardCharsets.ISO_8859_1));prepareRequest(request);return request;
    }
    private static String single(HttpCodec.Message message,String key)throws IOException{String value=optionalSingle(message,key);if(value==null)throw new IOException("Missing "+key);return value;}
    private static String optionalSingle(HttpCodec.Message message,String key)throws IOException{String value=null;for(String[] h:message.headers)if(h[0].equalsIgnoreCase(key)){if(value!=null)throw new IOException("Duplicate "+key);value=h[1];}return value;}
    private static boolean token(HttpCodec.Message message,String header,String token){for(String[] h:message.headers)if(h[0].equalsIgnoreCase(header))for(String v:h[1].split(","))if(v.trim().equalsIgnoreCase(token))return true;return false;}
    private static Set<String> protocols(String value)throws IOException{Set<String> values=new HashSet<>();if(value!=null)for(String s:value.split(",",-1)){s=s.trim();if(!s.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")||!values.add(s))throw new IOException("Invalid WebSocket subprotocol list");}return values;}
    private static String offeredProtocols(HttpCodec.Message request){StringBuilder all=new StringBuilder();for(String[] h:request.headers)if(h[0].equalsIgnoreCase("Sec-WebSocket-Protocol")){if(all.length()>0)all.append(", ");all.append(h[1]);}return all.length()==0?null:all.toString();}
}
