package com.ccs.sprig.oast;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class CollaboratorPrivacyTest {
    @Test public void managementRequestsUseStaticUserAgentAndOnlyExplicitSessionCredentials() throws Exception {
        String previous=System.getProperty("http.agent");
        System.setProperty("http.agent","PRIVATE-DEVICE-MODEL-BUILD-CANARY");
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),4);
        ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(4));
        server.setExecutor(worker);List<String> agents=Collections.synchronizedList(new ArrayList<>());
        List<String> bodies=Collections.synchronizedList(new ArrayList<>());
        String id="a".repeat(32),secret="fixture-secret-"+"b".repeat(32),admin="fixture-admin-"+"c".repeat(32);
        server.createContext("/",exchange->{
            try {
                agents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
                byte[] input=exchange.getRequestBody().readNBytes(1025);assertTrue(input.length<=1024);bodies.add(new String(input,StandardCharsets.UTF_8));
                String auth=exchange.getRequestHeaders().getFirst("Authorization");
                assertEquals("Bearer "+(exchange.getRequestMethod().equals("POST")?admin:secret),auth);
                Map<String,Object> response=exchange.getRequestMethod().equals("POST")
                        ?Json.map("id",id,"secret",secret,"payloadHost",id+".fixture.test","expiresAt",1,"protocols",List.of("HTTP"))
                        :exchange.getRequestMethod().equals("GET")?Json.map("events",List.of(),"cursor",0,"truncated",false):Json.map();
                byte[] data=Json.encode(response).getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);
            } finally {exchange.close();}
        });
        server.start();
        try {
            CollaboratorClient client=new CollaboratorClient("http://127.0.0.1:"+server.getAddress().getPort());
            CollaboratorClient.Session session=client.create(admin);client.poll(session,0);client.revoke(session);
            assertEquals(3,agents.size());for(String ua:agents){assertEquals("Sprig-Collaborator/1",ua);assertFalse(ua.contains("PRIVATE-DEVICE"));}
            assertEquals(List.of("","",""),bodies);
        } finally {
            server.stop(0);worker.shutdownNow();assertTrue(worker.awaitTermination(3,TimeUnit.SECONDS));
            if(previous==null)System.clearProperty("http.agent");else System.setProperty("http.agent",previous);
        }
    }
}
