package com.ccs.sprig.collaborator;

import com.ccs.sprig.oast.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;

/** Self-hosted OAST services and isolated authenticated polling API. */
public final class CollaboratorServer implements Closeable {
    public static final class Config {
        public String domain="oast.example.test",publicAddress="127.0.0.1",bind="127.0.0.1",apiBind="127.0.0.1",adminKey;
        public int apiPort=18081,httpPort=18080,dnsPort=15353,smtpPort=12525,httpsPort=18443,smtpsPort=12465;
        public SSLContext tls;
        public boolean apiTls;
    }
    private final InteractionStore store;
    private final Config config;
    private final ThreadPoolExecutor httpWorkers=pool("sprig-oast-http",8,32),apiWorkers=pool("sprig-oast-api",4,16),socketWorkers=pool("sprig-oast-socket",16,32);
    private final List<Closeable> services=new ArrayList<>();
    private final List<String> protocols=new ArrayList<>();
    private HttpServer api,http,https;
    private DnsServer dns;
    private SmtpServer smtp,smtps;
    public CollaboratorServer(Config config)throws IOException{
        if(config.adminKey==null||config.adminKey.length()<32)throw new IOException("Set an admin key containing at least 32 characters");
        if(config.apiTls&&config.tls==null)throw new IOException("API TLS requires a configured key store");
        this.config=config;store=new InteractionStore(config.domain);
        try{
            api=create(config.apiBind,config.apiPort,config.apiTls?config.tls:null);api.setExecutor(apiWorkers);api.createContext("/api/v1",this::api);
            http=create(config.bind,config.httpPort,null);http.createContext("/",this::callback);
            dns=new DnsServer(config.bind,config.dnsPort,InetAddress.getByName(config.publicAddress).getAddress(),store,socketWorkers);services.add(dns);
            smtp=new SmtpServer(config.bind,config.smtpPort,store,socketWorkers,config.tls,false);services.add(smtp);
            protocols.add("DNS");protocols.add("HTTP");protocols.add("SMTP");
            if(config.tls!=null){https=create(config.bind,config.httpsPort,config.tls);https.createContext("/",this::callback);smtps=new SmtpServer(config.bind,config.smtpsPort,store,socketWorkers,config.tls,true);services.add(smtps);protocols.add("HTTPS");protocols.add("SMTPS");}
            api.start();http.start();if(https!=null)https.start();
        }catch(IOException|RuntimeException error){close();throw error;}
    }
    private HttpServer create(String bind,int port,SSLContext tls)throws IOException{
        HttpServer server;if(tls!=null){HttpsServer secure=HttpsServer.create(new InetSocketAddress(bind,port),32);secure.setHttpsConfigurator(new HttpsConfigurator(tls));server=secure;}else server=HttpServer.create(new InetSocketAddress(bind,port),32);
        server.setExecutor(httpWorkers);services.add(()->server.stop(0));return server;
    }
    public int apiPort(){return api.getAddress().getPort();}public int httpPort(){return http.getAddress().getPort();}public int dnsPort(){return dns.port();}public int smtpPort(){return smtp.port();}
    public int httpsPort(){return https==null?-1:https.getAddress().getPort();}public int smtpsPort(){return smtps==null?-1:smtps.port();}
    public String apiUrl(){return(config.apiTls?"https":"http")+"://127.0.0.1:"+apiPort()+"/api/v1";}
    private void api(HttpExchange exchange)throws IOException{
        try{
            String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod(),auth=bearer(exchange);
            if(path.equals("/api/v1/sessions")&&method.equals("POST")){
                if(!InteractionStore.constantTime(config.adminKey,auth)){json(exchange,401,Json.map("error","Unauthorized"));return;}
                try{json(exchange,201,store.create().json(protocols));}catch(IllegalStateException e){json(exchange,429,Json.map("error",e.getMessage()));}return;
            }
            String[] parts=path.split("/",-1);
            if(parts.length<5||!parts[4].matches("[a-z2-7]{32}")){json(exchange,404,Json.map("error","Not found"));return;}
            InteractionStore.Session session=store.authenticate(parts[4],auth);
            if(session==null){json(exchange,401,Json.map("error","Unauthorized or expired"));return;}
            if(parts.length==5&&method.equals("DELETE")){store.revoke(session.id,auth);json(exchange,200,Json.map("revoked",true));return;}
            if(parts.length==6&&parts[5].equals("events")&&method.equals("GET")){
                long after=0;String query=exchange.getRequestURI().getRawQuery();if(query!=null&&query.matches("after=[0-9]{1,19}"))try{after=Long.parseLong(query.substring(6));}catch(NumberFormatException ignored){}
                json(exchange,200,store.poll(session,after));return;
            }
            json(exchange,405,Json.map("error","Unsupported operation"));
        }finally{exchange.close();}
    }
    private void callback(HttpExchange exchange)throws IOException{
        try{
            String host=exchange.getRequestHeaders().getFirst("Host");if(host==null)host="";int colon=host.indexOf(':');if(colon>=0)host=host.substring(0,colon);
            InteractionStore.Session session=store.forHost(host);
            if(session==null){String[] parts=exchange.getRequestURI().getPath().split("/");if(parts.length>1&&parts[1].matches("[a-z2-7]{32}"))session=store.forHost(parts[1]+"."+store.domain());}
            if(session!=null){
                ByteArrayOutputStream body=new ByteArrayOutputStream();try(InputStream input=exchange.getRequestBody()){byte[] buffer=new byte[1024];int n;while(body.size()<8192&&(n=input.read(buffer,0,Math.min(buffer.length,8192-body.size())))!=-1)body.write(buffer,0,n);}
                StringBuilder details=new StringBuilder(exchange.getRequestMethod()).append(' ').append(exchange.getRequestURI()).append('\n');
                for(Map.Entry<String,List<String>> header:exchange.getRequestHeaders().entrySet())if(!header.getKey().equalsIgnoreCase("Authorization")&&!header.getKey().equalsIgnoreCase("Cookie"))details.append(header.getKey()).append(": ").append(String.join(", ",header.getValue())).append('\n');
                details.append('\n').append(new String(body.toByteArray(),StandardCharsets.UTF_8));
                store.record(session,exchange instanceof com.sun.net.httpserver.HttpsExchange?"HTTPS":"HTTP",exchange.getRemoteAddress().getAddress().getHostAddress(),exchange.getRequestMethod()+" "+exchange.getRequestURI(),details.toString());
            }
            byte[] response="Sprig callback recorded.\n".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","text/plain; charset=utf-8");exchange.getResponseHeaders().set("Cache-Control","no-store");
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
        }finally{exchange.close();}
    }
    private static String bearer(HttpExchange exchange){String value=exchange.getRequestHeaders().getFirst("Authorization");return value!=null&&value.startsWith("Bearer ")?value.substring(7):"";}
    private static void json(HttpExchange exchange,int status,Object value)throws IOException{byte[] bytes=Json.encode(value).getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);}
    private static ThreadPoolExecutor pool(String name,int max,int queue){return new ThreadPoolExecutor(2,max,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(queue),r->{Thread t=new Thread(r,name);t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());}
    @Override public void close(){for(Closeable service:services)try{service.close();}catch(IOException ignored){}services.clear();httpWorkers.shutdownNow();apiWorkers.shutdownNow();socketWorkers.shutdownNow();}
}
