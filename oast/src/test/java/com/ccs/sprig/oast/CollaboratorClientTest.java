package com.ccs.sprig.oast;
import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;
public class CollaboratorClientTest {
    @Test public void requiresTlsForRemoteCredentialTransport()throws Exception{
        new CollaboratorClient("https://oast.example.test/api/v1");new CollaboratorClient("http://127.0.0.1:8081/api/v1");
        for(String url:new String[]{"http://oast.example.test/api/v1","https://user:secret@host/api","https://host/api?key=secret"})try{new CollaboratorClient(url);fail(url);}catch(IOException expected){}
    }
    @Test public void jsonRoundTripPreservesEscapingAndUnicode()throws Exception{
        String value="Sprig \"probe\"\n\u0001 українською";assertEquals(value,Json.object(Json.encode(Json.map("value",value))).get("value"));
    }
}
