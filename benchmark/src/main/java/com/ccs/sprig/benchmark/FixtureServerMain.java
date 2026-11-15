package com.ccs.sprig.benchmark;

import com.ccs.sprig.core.net.HostResolver;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Explicit local-LAN fixture for a separately authorized Android test device. */
public final class FixtureServerMain {
    private FixtureServerMain(){}
    public static void main(String[] args)throws Exception {
        String bind="127.0.0.1",token=null;int port=0,seconds=600;Path ready=null;Set<String> seen=new HashSet<>();
        for(int i=0;i<args.length;i+=2){if(i+1>=args.length||!seen.add(args[i]))throw new IllegalArgumentException("Options need one value and may occur once");String value=args[i+1];switch(args[i]){case"--bind":bind=value;break;case"--port":port=Integer.parseInt(value);break;case"--seconds":seconds=Integer.parseInt(value);break;case"--token":token=value;break;case"--ready-file":ready=Path.of(value);break;default:throw new IllegalArgumentException("Unknown fixture option: "+args[i]);}}
        if(!bind.matches("[0-9.]+")||port<0||port>65535||seconds<1||seconds>3600)throw new IllegalArgumentException("Use a numeric local IPv4, port0–65535 and duration1–3600s");
        InetAddress address=HostResolver.resolve(bind,true);if(!(address.isLoopbackAddress()||address.isSiteLocalAddress())||NetworkInterface.getByInetAddress(address)==null)throw new IllegalArgumentException("Bind must be an assigned loopback or private LAN interface");
        if(token==null){byte[] bytes=new byte[16];new SecureRandom().nextBytes(bytes);token=java.util.HexFormat.of().formatHex(bytes);}if(!token.matches("[A-Za-z0-9_-]{12,64}"))throw new IllegalArgumentException("Fixture token needs 12–64 ASCII URL-safe characters");
        try(LoopbackFixture fixture=new LoopbackFixture(address,port,token)){
            Map<String,Object> metadata=new LinkedHashMap<>();metadata.put("fixture",LoopbackFixture.REVISION);metadata.put("bind",address.getHostAddress());metadata.put("port",fixture.port());metadata.put("token",token);metadata.put("httpUrl","http://"+address.getHostAddress()+":"+fixture.port()+"/sprig-fixture?token="+token);metadata.put("webSocketUrl","ws://"+address.getHostAddress()+":"+fixture.port()+"/sprig-fixture?token="+token);metadata.put("getBody","sprig-fixture-v1:"+token);metadata.put("postBody","exact bytes echoed");metadata.put("expiresAfterSeconds",seconds);
            java.io.StringWriter value=new java.io.StringWriter();Json.write(value,metadata);System.out.println(value);System.out.flush();if(ready!=null){Path parent=ready.toAbsolutePath().getParent();Files.createDirectories(parent);Files.writeString(ready,value+"\n");}
            Runtime.getRuntime().addShutdownHook(new Thread(fixture::close,"fixture-shutdown"));Thread.sleep(seconds*1000L);
        }
    }
}
