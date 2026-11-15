package com.ccs.sprig.collaborator;

import com.ccs.sprig.oast.CollaboratorClient;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.net.ssl.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class CollaboratorServerTest {
    private static final String KEY="fixture-admin-secret-0123456789-abcdefgh";
    private CollaboratorServer server()throws Exception{
        CollaboratorServer.Config config=new CollaboratorServer.Config();config.domain="oast.example.test";config.adminKey=KEY;
        config.apiPort=0;config.httpPort=0;config.dnsPort=0;config.smtpPort=0;
        return new CollaboratorServer(config);
    }
    @Test public void authenticatesAndIsolatesHttpCallbacks()throws Exception{
        try(CollaboratorServer server=server()){
            CollaboratorClient client=new CollaboratorClient(server.apiUrl());
            try{client.create("wrong");fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("401"));}
            CollaboratorClient.Session one=client.create(KEY),two=client.create(KEY);
            HttpURLConnection callback=(HttpURLConnection)new URL("http://127.0.0.1:"+server.httpPort()+"/"+one.id+"/probe?x=1").openConnection();
            assertEquals(200,callback.getResponseCode());callback.getInputStream().close();callback.disconnect();
            CollaboratorClient.Poll poll=client.poll(one,0);assertEquals(1,poll.events.size());assertEquals("HTTP",poll.events.get(0).protocol);
            assertEquals(0,client.poll(two,0).events.size());assertEquals(0,client.poll(one,poll.cursor).events.size());
            CollaboratorClient.Session forged=new CollaboratorClient.Session(one.id,two.secret,one.payloadHost,one.expiresAt,one.protocols);
            try{client.poll(forged,0);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("401"));}
            client.revoke(one);try{client.poll(one,0);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("401"));}
        }
    }
    @Test public void dnsUdpAndTcpRecordTokenAndNeverRecurse()throws Exception{
        try(CollaboratorServer server=server()){
            CollaboratorClient client=new CollaboratorClient(server.apiUrl());CollaboratorClient.Session session=client.create(KEY);
            byte[] query=query(session.payloadHost);
            try(DatagramSocket socket=new DatagramSocket()){
                socket.setSoTimeout(2000);socket.send(new DatagramPacket(query,query.length,InetAddress.getLoopbackAddress(),server.dnsPort()));
                DatagramPacket reply=new DatagramPacket(new byte[512],512);socket.receive(reply);assertEquals(1,reply.getData()[7]);assertTrue((reply.getData()[2]&4)!=0);
                byte[] unrelated=query("unrelated.example.org");socket.send(new DatagramPacket(unrelated,unrelated.length,InetAddress.getLoopbackAddress(),server.dnsPort()));socket.receive(reply);assertEquals(5,reply.getData()[3]&15);
            }
            try(Socket socket=new Socket("127.0.0.1",server.dnsPort())){socket.setSoTimeout(2000);DataOutputStream out=new DataOutputStream(socket.getOutputStream());out.writeShort(query.length);out.write(query);out.flush();DataInputStream in=new DataInputStream(socket.getInputStream());assertTrue(in.readUnsignedShort()>query.length);}
            assertEquals(2,client.poll(session,0).events.size());
        }
    }
    @Test public void smtpSinkRecordsRecipientAndDataWithoutRelaying()throws Exception{
        try(CollaboratorServer server=server()){
            CollaboratorClient client=new CollaboratorClient(server.apiUrl());CollaboratorClient.Session session=client.create(KEY);
            try(Socket socket=new Socket("127.0.0.1",server.smtpPort())){
                socket.setSoTimeout(2000);BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
                Writer out=new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.US_ASCII);assertTrue(in.readLine().startsWith("220"));
                send(out,"EHLO test");assertTrue(in.readLine().startsWith("250"));send(out,"MAIL FROM:<probe@example.test>");assertTrue(in.readLine().startsWith("250"));
                send(out,"RCPT TO:<sink@"+session.payloadHost+">");assertTrue(in.readLine().startsWith("250"));send(out,"DATA");assertTrue(in.readLine().startsWith("354"));
                send(out,"Subject: local fixture\r\n\r\nprobe\r\n.");assertTrue(in.readLine().startsWith("250"));send(out,"QUIT");assertTrue(in.readLine().startsWith("221"));
            }
            CollaboratorClient.Poll poll=client.poll(session,0);assertEquals(2,poll.events.size());assertTrue(poll.events.get(1).details.contains("local fixture"));
        }
    }
    @Test public void boundedHistorySignalsLostEvents()throws Exception{
        InteractionStore store=new InteractionStore("oast.example.test");InteractionStore.Session s=store.create();
        for(int i=0;i<300;i++)store.record(s,"HTTP","127.0.0.1","test","event"+i);
        assertEquals(Boolean.TRUE,store.poll(s,0).get("truncated"));assertTrue(((java.util.List<?>)store.poll(s,0).get("events")).size()<=256);
        assertNull(store.forHost(s.id+".oast.example.test.attacker.test"));
    }
    @Test public void authoritativeZoneHasSoaMxAndRejectsCompressionLoops()throws Exception{
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(DnsServer dns=new DnsServer("127.0.0.1",0,new byte[]{127,0,0,1},new InteractionStore("oast.example.test"),worker)){
            byte[] soa=query("oast.example.test");soa[soa.length-3]=6;byte[] answer=dns.answer(soa,"127.0.0.1");assertEquals(1,answer[7]);
            byte[] mx=query("oast.example.test");mx[mx.length-3]=15;assertEquals(1,dns.answer(mx,"127.0.0.1")[7]);
            byte[] missing=query("missing.oast.example.test");byte[] negative=dns.answer(missing,"127.0.0.1");assertEquals(3,negative[3]&15);assertEquals(1,negative[9]);
            byte[] loop=new byte[18];loop[5]=1;loop[12]=(byte)0xc0;loop[13]=12;loop[15]=1;loop[17]=1;
            try{dns.answer(loop,"127.0.0.1");fail("Compression pointer recursion must be rejected");}catch(IOException expected){}
        }finally{worker.shutdownNow();}
    }
    @Test public void httpsAndSmtpsUseConfiguredTlsCredentials()throws Exception{
        KeyStore credentials=KeyStore.getInstance("PKCS12");try(InputStream input=getClass().getResourceAsStream("/fixture-test-only.p12")){assertNotNull(input);credentials.load(input,"fixture-password".toCharArray());}
        KeyManagerFactory keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keys.init(credentials,"fixture-password".toCharArray());
        SSLContext serverTls=SSLContext.getInstance("TLS");serverTls.init(keys.getKeyManagers(),null,null);
        KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null);trust.setCertificateEntry("fixture",credentials.getCertificate("fixture"));
        TrustManagerFactory managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());managers.init(trust);
        SSLContext clientTls=SSLContext.getInstance("TLS");clientTls.init(null,managers.getTrustManagers(),null);
        CollaboratorServer.Config config=new CollaboratorServer.Config();config.adminKey=KEY;config.apiPort=0;config.httpPort=0;config.dnsPort=0;config.smtpPort=0;config.httpsPort=0;config.smtpsPort=0;config.tls=serverTls;
        try(CollaboratorServer server=new CollaboratorServer(config)){
            CollaboratorClient client=new CollaboratorClient(server.apiUrl());CollaboratorClient.Session session=client.create(KEY);assertTrue(session.protocols.contains("HTTPS"));assertTrue(session.protocols.contains("SMTPS"));
            HttpsURLConnection callback=(HttpsURLConnection)new URL("https://127.0.0.1:"+server.httpsPort()+"/"+session.id).openConnection();callback.setSSLSocketFactory(clientTls.getSocketFactory());assertEquals(200,callback.getResponseCode());callback.getInputStream().close();callback.disconnect();
            try(SSLSocket socket=(SSLSocket)clientTls.getSocketFactory().createSocket("127.0.0.1",server.smtpsPort())){
                SSLParameters parameters=socket.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");socket.setSSLParameters(parameters);socket.setSoTimeout(2000);socket.startHandshake();
                BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));Writer output=new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.US_ASCII);
                assertTrue(input.readLine().startsWith("220"));send(output,"MAIL FROM:<fixture@example.test>");assertTrue(input.readLine().startsWith("250"));send(output,"RCPT TO:<sink@"+session.payloadHost+">");assertTrue(input.readLine().startsWith("250"));send(output,"QUIT");assertTrue(input.readLine().startsWith("221"));
            }
            CollaboratorClient.Poll result=client.poll(session,0);assertEquals(2,result.events.size());assertEquals("HTTPS",result.events.get(0).protocol);assertEquals("SMTPS",result.events.get(1).protocol);
        }
    }
    private static void send(Writer out,String line)throws IOException{out.write(line+"\r\n");out.flush();}
    private static byte[] query(String host)throws IOException{ByteArrayOutputStream data=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(data);out.writeShort(0x1234);out.writeShort(0x0100);out.writeShort(1);out.writeShort(0);out.writeShort(0);out.writeShort(0);out.write(DnsServer.encodedName(host));out.writeShort(1);out.writeShort(1);return data.toByteArray();}
}
