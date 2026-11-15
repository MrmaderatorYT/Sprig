package com.ccs.sprig.core.scanner;

import com.ccs.sprig.core.proxy.HttpCodec;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Immutable request bytes and explicit insertion points; URL target and credentials stay in scope. */
final class ScanRequest {
    final URI uri;
    final String method;
    final List<String[]> headers;
    final byte[] body;
    ScanRequest(URI uri,String method,List<String[]> headers,byte[] body){this.uri=uri;this.method=method;this.headers=headers;this.body=body;}
    static ScanRequest captured(URI seed,byte[] raw,boolean stateful)throws IOException{
        if(raw==null){List<String[]> headers=new ArrayList<>();headers.add(new String[]{"User-Agent","Sprig-Scanner/0.3"});headers.add(new String[]{"Accept","text/html,application/xhtml+xml,*/*;q=0.5"});return new ScanRequest(seed,"GET",headers,new byte[0]);}
        if(raw.length>64*1024)throw new IOException("Captured scanner request exceeds 64 KiB");
        ByteArrayInputStream input=new ByteArrayInputStream(raw);HttpCodec.Message message=HttpCodec.readRequest(input,null);
        if(message==null||input.available()!=0)throw new IOException("Captured request framing is incomplete or contains multiple requests");
        String method=message.method().toUpperCase(Locale.ROOT);
        if(!method.equals("GET")&&!(method.equals("POST")&&stateful))throw new IOException("GET-only by default; captured POST requires explicit stateful testing");
        if(method.equals("GET")&&message.body.length!=0)throw new IOException("Scanner GET requests must have no body");
        String host=message.header("Host");
        if(host==null||!ScanSupport.sameOrigin(seed,ScanSupport.canonical(seed.getScheme()+"://"+host+"/")))throw new IOException("Captured request credentials cannot be used for a different origin");
        String target=message.startLine.split(" ",3)[1];URI capturedTarget=ScanSupport.canonical(seed.resolve(target).toString());
        if(!ScanSupport.sameOrigin(seed,capturedTarget))throw new IOException("Captured request target does not match the scan origin");
        if(method.equals("POST")&&!capturedTarget.equals(seed))throw new IOException("Stateful testing must preserve the original captured POST target");
        HttpCodec.stripHopHeaders(message);
        Set<String> excluded=new HashSet<>();Collections.addAll(excluded,"host","content-length","accept-encoding","expect");
        List<String[]> headers=new ArrayList<>();for(String[] h:message.headers)if(!excluded.contains(h[0].toLowerCase(Locale.ROOT)))headers.add(new String[]{h[0],h[1]});
        return new ScanRequest(seed,method,headers,message.body.clone());
    }
    ScanRequest page(URI next){List<String[]> copied=new ArrayList<>();for(String[] header:headers)if(!header[0].equalsIgnoreCase("Content-Type")&&!header[0].equalsIgnoreCase("Content-Encoding"))copied.add(header);return new ScanRequest(next,"GET",copied,new byte[0]);}
    ScanRequest header(String name,String value){
        if(!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}")||value.indexOf('\r')>=0||value.indexOf('\n')>=0)throw new IllegalArgumentException("Invalid probe header");
        List<String[]> result=new ArrayList<>();for(String[] h:headers)if(!h[0].equalsIgnoreCase(name))result.add(h);result.add(new String[]{name,value});return new ScanRequest(uri,method,result,body);
    }
    ScanRequest method(String method,boolean anonymous){
        List<String[]> result=new ArrayList<>();if(anonymous)result.add(new String[]{"User-Agent","Sprig-Scanner/0.3"});else for(String[] h:headers)result.add(h);
        return new ScanRequest(uri,method,result,new byte[0]);
    }
    ScanRequest cacheBuster(String token)throws IOException {String query=uri.getRawQuery();URI changed=ScanSupport.canonical(uri.getScheme()+"://"+uri.getRawAuthority()+uri.getRawPath()+'?'+(query==null||query.isEmpty()?"":query+'&')+"__sprig_probe="+token);return new ScanRequest(changed,method,headers,body);}
    String header(String name){for(String[] h:headers)if(h[0].equalsIgnoreCase(name))return h[1];return "";}
    byte[] raw()throws IOException{
        HttpCodec.Message message=new HttpCodec.Message();String target=uri.getRawPath();if(target==null||target.isEmpty())target="/";if(uri.getRawQuery()!=null)target+='?'+uri.getRawQuery();
        message.startLine=method+' '+target+" HTTP/1.1";message.headers.add(new String[]{"Host",uri.getRawAuthority()});
        for(String[] h:headers)if(!h[0].equalsIgnoreCase("Host")&&!h[0].equalsIgnoreCase("Content-Length")&&!h[0].equalsIgnoreCase("Accept-Encoding")&&!h[0].equalsIgnoreCase("Connection"))message.headers.add(h);
        message.headers.add(new String[]{"Accept-Encoding","identity"});message.headers.add(new String[]{"Connection","close"});message.body=body;HttpCodec.normalizeLength(message,false);
        byte[] raw=message.raw();if(raw.length>128*1024)throw new IOException("Mutated scanner request exceeds 128 KiB");return raw;
    }
    List<Point> points(Set<ScanConfig.InsertionType> types,Set<String> headerNames,int max)throws IOException{
        List<Point> result=new ArrayList<>();
        if(types.contains(ScanConfig.InsertionType.QUERY))pairs(result,uri.getRawQuery(),ScanConfig.InsertionType.QUERY,max);
        if(types.contains(ScanConfig.InsertionType.HEADER))for(String name:headerNames){if(result.size()>=max)break;if(!forbiddenHeader(name))result.add(new Point(this,ScanConfig.InsertionType.HEADER,name,header(name),-1,null));}
        String contentType=header("Content-Type").toLowerCase(Locale.ROOT);
        if(method.equals("POST")&&types.contains(ScanConfig.InsertionType.FORM)&&contentType.startsWith("application/x-www-form-urlencoded"))pairs(result,new String(body,StandardCharsets.UTF_8),ScanConfig.InsertionType.FORM,max);
        if(method.equals("POST")&&types.contains(ScanConfig.InsertionType.JSON)&&contentType.startsWith("application/json")){
            try{JsonElement json=JsonParser.parseString(new String(body,StandardCharsets.UTF_8));jsonPoints(json,new ArrayList<>(),result,max,0,new int[]{0});}
            catch(RuntimeException|StackOverflowError error){throw new IOException("Captured JSON is invalid or too deeply nested",error);}
        }
        return result;
    }
    private void pairs(List<Point> result,String raw,ScanConfig.InsertionType type,int max){
        if(raw==null||raw.isEmpty())return;Set<String> names=new HashSet<>();String[] pairs=raw.split("&",-1);
        for(int i=0;i<pairs.length&&result.size()<max;i++){int eq=pairs[i].indexOf('=');String name=decode(eq<0?pairs[i]:pairs[i].substring(0,eq)),value=decode(eq<0?"":pairs[i].substring(eq+1));if(!name.isEmpty()&&name.length()<=128&&value.length()<=4096&&!ScanSupport.secretParameter(name)&&names.add(name))result.add(new Point(this,type,name,value,i,null));}
    }
    private void jsonPoints(JsonElement json,List<Object> path,List<Point> result,int max,int depth,int[] nodes)throws IOException{
        if(depth>12||++nodes[0]>5000)throw new IOException("JSON insertion discovery exceeds depth/node limits");
        if(result.size()>=max)return;
        if(json.isJsonObject())for(java.util.Map.Entry<String,JsonElement> entry:json.getAsJsonObject().entrySet()){
            if(ScanSupport.secretParameter(entry.getKey()))continue;List<Object> next=new ArrayList<>(path);next.add(entry.getKey());jsonPoints(entry.getValue(),next,result,max,depth+1,nodes);
        }else if(json.isJsonArray()){JsonArray array=json.getAsJsonArray();for(int i=0;i<array.size()&&result.size()<max;i++){List<Object> next=new ArrayList<>(path);next.add(i);jsonPoints(array.get(i),next,result,max,depth+1,nodes);}}
        else if(json.isJsonPrimitive()&&json.getAsJsonPrimitive().isString()&&json.getAsString().length()<=4096){StringBuilder name=new StringBuilder("$");for(Object part:path)name.append(part instanceof Integer?"["+part+"]":"."+part);result.add(new Point(this,ScanConfig.InsertionType.JSON,name.toString(),json.getAsString(),-1,path));}
    }
    static boolean forbiddenHeader(String name){return name==null||!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}")||name.matches("(?i)(?:host|cookie|authorization|proxy-authorization|content-length|transfer-encoding|connection|upgrade|te|trailer|content-type|accept-encoding)")||name.matches("(?i).*(?:token|secret|api[-_]?key|signature).*" );}
    private static String decode(String value){try{return URLDecoder.decode(value,"UTF-8");}catch(Exception ignored){return value;}}
    private static String encoded(String value)throws IOException{return URLEncoder.encode(value,"UTF-8");}
    static final class Point {
        final ScanRequest base;final ScanConfig.InsertionType type;final String name,value;final int index;final List<Object> path;
        Point(ScanRequest base,ScanConfig.InsertionType type,String name,String value,int index,List<Object> path){this.base=base;this.type=type;this.name=name;this.value=value;this.index=index;this.path=path==null?null:new ArrayList<>(path);}
        String label(){return type.name().toLowerCase(Locale.ROOT)+':'+name;}
        ScanRequest mutate(String value)throws IOException{
            if(type==ScanConfig.InsertionType.HEADER)return base.header(name,value);
            if(type==ScanConfig.InsertionType.JSON){
                JsonElement root=JsonParser.parseString(new String(base.body,StandardCharsets.UTF_8));JsonElement cursor=root;
                for(int i=0;i<path.size()-1;i++){Object part=path.get(i);cursor=part instanceof Integer?cursor.getAsJsonArray().get((Integer)part):cursor.getAsJsonObject().get((String)part);}
                JsonPrimitive replacement=new JsonPrimitive(value);
                if(path.isEmpty())root=replacement;else{Object last=path.get(path.size()-1);if(last instanceof Integer)cursor.getAsJsonArray().set((Integer)last,replacement);else cursor.getAsJsonObject().add((String)last,replacement);}
                return new ScanRequest(base.uri,base.method,base.headers,root.toString().getBytes(StandardCharsets.UTF_8));
            }
            String raw=type==ScanConfig.InsertionType.QUERY?base.uri.getRawQuery():new String(base.body,StandardCharsets.UTF_8);
            String[] pairs=raw.split("&",-1);int eq=pairs[index].indexOf('=');pairs[index]=(eq<0?pairs[index]:pairs[index].substring(0,eq))+'='+encoded(value);
            String updated=String.join("&",pairs);
            if(type==ScanConfig.InsertionType.FORM)return new ScanRequest(base.uri,base.method,base.headers,updated.getBytes(StandardCharsets.UTF_8));
            URI uri=ScanSupport.canonical(base.uri.getScheme()+"://"+base.uri.getRawAuthority()+base.uri.getRawPath()+'?'+updated);
            return new ScanRequest(uri,base.method,base.headers,base.body);
        }
    }
}
