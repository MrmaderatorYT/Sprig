package com.ccs.sprig.collaborator;

import java.io.FileInputStream;
import java.security.KeyStore;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

public final class Main {
    private Main(){}
    public static void main(String[] args)throws Exception{
        if(args.length!=1){System.err.println("Usage: sprig-collaborator <server.properties>; set SPRIG_COLLABORATOR_KEY and optional SPRIG_TLS_PASSWORD");System.exit(2);return;}
        Properties p=new Properties();try(FileInputStream input=new FileInputStream(args[0])){p.load(input);}
        CollaboratorServer.Config c=new CollaboratorServer.Config();c.domain=p.getProperty("domain",c.domain);c.publicAddress=p.getProperty("publicAddress",c.publicAddress);c.bind=p.getProperty("bind","0.0.0.0");c.apiBind=p.getProperty("apiBind","127.0.0.1");c.adminKey=System.getenv("SPRIG_COLLABORATOR_KEY");
        c.apiPort=port(p,"apiPort",18081);c.httpPort=port(p,"httpPort",18080);c.dnsPort=port(p,"dnsPort",15353);c.smtpPort=port(p,"smtpPort",12525);c.httpsPort=port(p,"httpsPort",18443);c.smtpsPort=port(p,"smtpsPort",12465);c.apiTls=Boolean.parseBoolean(p.getProperty("apiTls","false"));
        String keyStore=p.getProperty("tlsKeyStore","");if(!keyStore.isEmpty()){
            String password=System.getenv("SPRIG_TLS_PASSWORD");if(password==null)throw new IllegalArgumentException("Set SPRIG_TLS_PASSWORD");char[] secret=password.toCharArray();
            KeyStore keys=KeyStore.getInstance("PKCS12");try(FileInputStream input=new FileInputStream(keyStore)){keys.load(input,secret);}
            KeyManagerFactory managers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());managers.init(keys,secret);java.util.Arrays.fill(secret,'\0');c.tls=SSLContext.getInstance("TLS");c.tls.init(managers.getKeyManagers(),null,null);
        }
        System.setProperty("sun.net.httpserver.maxReqTime","15");System.setProperty("sun.net.httpserver.maxRspTime","15");
        CollaboratorServer server=new CollaboratorServer(c);Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        System.out.println("Sprig Collaborator running for "+c.domain+"; API port "+server.apiPort()+", HTTP "+server.httpPort()+", DNS UDP/TCP "+server.dnsPort()+", SMTP "+server.smtpPort());
        new CountDownLatch(1).await();
    }
    private static int port(Properties p,String key,int fallback){int port=Integer.parseInt(p.getProperty(key,Integer.toString(fallback)));if(port<1||port>65535)throw new IllegalArgumentException("Invalid "+key);return port;}
}
