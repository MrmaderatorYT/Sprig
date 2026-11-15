package com.ccs.sprig.core.scanner;

import com.ccs.sprig.core.model.Exchange;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

final class ScanSupport {
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final Pattern SECRET=Pattern.compile("(?i)(?:.*(?:csrf|token|password|secret|signature|session|nonce|authorization|jwt).*|api_?key|key|auth)");
    private ScanSupport(){ }
    static boolean secretParameter(String name){return SECRET.matcher(name).matches();}
    static URI canonical(String value)throws IOException{
        try{
            if(value==null||value.length()>8192)throw new IOException("URL exceeds 8192 characters");URI uri=new URI(value).normalize();String scheme=uri.getScheme();
            if(!"http".equalsIgnoreCase(scheme)&&!"https".equalsIgnoreCase(scheme))throw new IOException("Scope URL must use HTTP or HTTPS");
            if(uri.getUserInfo()!=null||uri.getHost()==null)throw new IOException("Scope URL requires a host without embedded credentials");
            String host=uri.getHost().toLowerCase(Locale.ROOT);if(host.startsWith("[")&&host.endsWith("]"))host=host.substring(1,host.length()-1);
            if(host.indexOf(':')<0)host=IDN.toASCII(host,IDN.USE_STD3_ASCII_RULES);if(host.endsWith("."))host=host.substring(0,host.length()-1);
            int port=uri.getPort();if(port==0||port>65535||port< -1)throw new IOException("Invalid scope port");scheme=scheme.toLowerCase(Locale.ROOT);
            String authority=(host.indexOf(':')>=0?"["+host+"]":host)+(port<0||port==("https".equals(scheme)?443:80)?"":":"+port);
            String path=uri.getRawPath();if(path==null||path.isEmpty())path="/";return new URI(scheme+"://"+authority+path+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery()));
        }catch(URISyntaxException|IllegalArgumentException error){throw new IOException("Invalid scope URL",error);}
    }
    static boolean sameOrigin(URI a,URI b){return a.getScheme().equals(b.getScheme())&&a.getHost().equalsIgnoreCase(b.getHost())&&port(a)==port(b);}
    private static int port(URI uri){return uri.getPort()<0?("https".equals(uri.getScheme())?443:80):uri.getPort();}
    static boolean unsafe(URI uri){
        String path=decode(uri.getRawPath()).toLowerCase(Locale.ROOT);for(String part:path.split("/"))if(part.matches("(?:logout|logoff|signout|delete|remove|destroy|reset|unsubscribe|purchase|checkout|pay|transfer)(?:[._-].*)?"))return true;
        if(uri.getRawQuery()!=null)for(String pair:uri.getRawQuery().split("&")){String[] kv=pair.split("=",2);if(decode(kv[0]).matches("(?i)(?:action|do|op|operation|cmd|command)")&&kv.length>1&&decode(kv[1]).matches("(?i)(?:delete|remove|destroy|reset|logout|unsubscribe|purchase|checkout|pay|transfer)"))return true;}return false;
    }
    static String decode(String value){try{return URLDecoder.decode(value,"UTF-8");}catch(Exception ignored){return value;}}
    static String header(Exchange e,String name){if(e.responseHeaders!=null)for(String line:e.responseHeaders.split("\r?\n")){int colon=line.indexOf(':');if(colon>0&&line.substring(0,colon).equalsIgnoreCase(name))return line.substring(colon+1).trim();}return "";}
    static boolean html(Exchange e){String type=header(e,"content-type").toLowerCase(Locale.ROOT);return type.contains("text/html")||type.contains("application/xhtml+xml");}
    static String text(Exchange e){
        byte[] body=e.responseBody==null?new byte[0]:e.responseBody;if(body.length>512*1024)return "";
        String encoding=header(e,"content-encoding").toLowerCase(Locale.ROOT);if(encoding.isEmpty()||encoding.equals("identity"))return new String(body,StandardCharsets.UTF_8);
        try(InputStream input=encoding.equals("gzip")?new GZIPInputStream(new ByteArrayInputStream(body)):encoding.equals("deflate")?new InflaterInputStream(new ByteArrayInputStream(body)):null){
            if(input==null)return "";ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
            while((count=input.read(buffer))!=-1){if(output.size()+count>512*1024)return "";output.write(buffer,0,count);}return new String(output.toByteArray(),StandardCharsets.UTF_8);
        }catch(IOException ignored){return "";}
    }
    static String token(){byte[] bytes=new byte[8];RANDOM.nextBytes(bytes);char[] digits="0123456789abcdef".toCharArray(),result=new char[16];for(int i=0;i<bytes.length;i++){result[i*2]=digits[(bytes[i]>>>4)&15];result[i*2+1]=digits[bytes[i]&15];}return new String(result);}
    static int integer(int min,int range){return min+RANDOM.nextInt(range);}
    static String trim(String value,int max){return value==null?"":value.length()<=max?value:value.substring(0,max)+"…";}
    static String redact(String text){
        return text.replaceAll("(?is)-----BEGIN (?:[A-Z ]*PRIVATE KEY)-----.*?-----END (?:[A-Z ]*PRIVATE KEY)-----","[REDACTED PRIVATE KEY BLOCK]")
                .replaceAll("(?i)(authorization\\s*[:=]\\s*)(?:Bearer|Basic)\\s+[A-Za-z0-9+/_.=-]+","$1[REDACTED]")
                .replaceAll("(?i)((?:password|secret|access_token|api_key|authorization|sessionid|csrf)[\\w-]*[\\s\"']*[:=][\\s\"']*)[^\\s\"'<>;&]{4,}","$1[REDACTED]");
    }
    static String snippet(String body,int position){
        int start=Math.max(0,position-70),end=Math.min(body.length(),position+180);
        java.util.regex.Matcher privateKeys=Pattern.compile("(?is)-----BEGIN (?:[A-Z ]*PRIVATE KEY)-----.*?-----END (?:[A-Z ]*PRIVATE KEY)-----").matcher(body);
        while(privateKeys.find())if(privateKeys.start()<end&&privateKeys.end()>start)return "[REDACTED PRIVATE KEY CONTEXT]";
        return trim(redact(body.substring(start,end).replace('\r',' ').replace('\n',' ')),250);
    }
    static Finding finding(String check,String severity,String confidence,String title,String detail,ScanRequest request,String point,String evidence,Exchange...exchanges){
        long[] ids=new long[Math.max(0,exchanges.length-2)];for(int i=2;i<exchanges.length;i++)ids[i-2]=exchanges[i]==null?0:exchanges[i].id;
        return new Finding(0,check,severity,confidence,title,detail,request.uri.toString(),trim(point,160),trim(redact(evidence),2000),exchanges.length==0||exchanges[0]==null?0:exchanges[0].id,exchanges.length<2||exchanges[1]==null?0:exchanges[1].id,ids);
    }
}
