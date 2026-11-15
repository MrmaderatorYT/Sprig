package com.ccs.sprig.oast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Client for Sprig's private OAST server. TLS is always validated by the platform. */
public final class CollaboratorClient {
    public static final class Session {
        public final String id,secret,payloadHost;
        public final long expiresAt;
        public final List<String> protocols;
        public Session(String id,String secret,String host,long expiresAt,List<String> protocols) {
            this.id=id;this.secret=secret;this.payloadHost=host;this.expiresAt=expiresAt;this.protocols=Collections.unmodifiableList(new ArrayList<>(protocols));
        }
    }
    public static final class Poll {
        public final List<Interaction> events;
        public final long cursor;
        public final boolean truncated;
        Poll(List<Interaction> events,long cursor,boolean truncated) {this.events=Collections.unmodifiableList(events);this.cursor=cursor;this.truncated=truncated;}
    }
    private final String base;
    public CollaboratorClient(String apiBase) throws IOException {
        try {
            URI uri=new URI(apiBase.trim());
            String host=uri.getHost();
            if(host==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null) throw new IOException("Invalid Collaborator API URL");
            boolean local=host.equals("localhost")||host.equals("127.0.0.1")||host.equals("[::1]")||host.equals("::1");
            if(!"https".equalsIgnoreCase(uri.getScheme()) && !("http".equalsIgnoreCase(uri.getScheme())&&local)) throw new IOException("Use HTTPS for remote Collaborator servers");
            String normalized=uri.toASCIIString();while(normalized.endsWith("/")) normalized=normalized.substring(0,normalized.length()-1);
            base=normalized;
        } catch(java.net.URISyntaxException e) {throw new IOException("Invalid Collaborator API URL",e);}
    }
    public Session create(String adminKey) throws IOException {
        Map<String,Object> response=request("POST","/sessions",adminKey);
        String id=Json.string(response,"id"), secret=Json.string(response,"secret"), host=Json.string(response,"payloadHost");
        if(!id.matches("[a-z0-9]{32}")||secret.length()<32||host.isEmpty()) throw new IOException("Invalid session response");
        List<String> protocols=new ArrayList<>();Object values=response.get("protocols");
        if(values instanceof List) for(Object p:(List<?>)values) protocols.add(String.valueOf(p));
        return new Session(id,secret,host,Json.number(response,"expiresAt"),protocols);
    }
    public Poll poll(Session session,long after) throws IOException {
        Map<String,Object> response=request("GET","/sessions/"+session.id+"/events?after="+Math.max(0,after),session.secret);
        List<Interaction> events=new ArrayList<>();Object values=response.get("events");
        if(!(values instanceof List)) throw new IOException("Invalid interaction list");
        for(Object value:(List<?>)values) if(value instanceof Map) {
            @SuppressWarnings("unchecked") Map<String,Object> map=(Map<String,Object>)value;events.add(Interaction.from(map));
        }
        return new Poll(events,Json.number(response,"cursor"),Boolean.TRUE.equals(response.get("truncated")));
    }
    public void revoke(Session session) throws IOException { request("DELETE","/sessions/"+session.id,session.secret); }
    private Map<String,Object> request(String method,String path,String secret) throws IOException {
        if(secret==null||secret.indexOf('\r')>=0||secret.indexOf('\n')>=0) throw new IOException("Invalid API credential");
        HttpURLConnection connection=(HttpURLConnection)new URL(base+path).openConnection();
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10000);connection.setReadTimeout(15000);
        connection.setRequestMethod(method);connection.setRequestProperty("Authorization","Bearer "+secret);connection.setRequestProperty("Accept","application/json");
        if(method.equals("POST")) {connection.setDoOutput(true);connection.setFixedLengthStreamingMode(0);connection.getOutputStream().close();}
        try {
            int status=connection.getResponseCode();InputStream input=status>=400?connection.getErrorStream():connection.getInputStream();
            ByteArrayOutputStream body=new ByteArrayOutputStream();
            if(input!=null) try(InputStream stream=input) {byte[] buffer=new byte[8192];int n;while((n=stream.read(buffer))!=-1) {if(body.size()+n>512*1024) throw new IOException("Collaborator response exceeds 512 KiB");body.write(buffer,0,n);}}
            if(status<200||status>=300) throw new IOException("Collaborator API returned HTTP "+status);
            return Json.object(new String(body.toByteArray(),StandardCharsets.UTF_8));
        } finally {connection.disconnect();}
    }
}
