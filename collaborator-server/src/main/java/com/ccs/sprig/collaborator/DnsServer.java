package com.ccs.sprig.collaborator;

import com.ccs.sprig.oast.Json;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/** Authoritative-only UDP and TCP DNS. Never resolves or forwards arbitrary DNS requests. */
public final class DnsServer implements Closeable {
    private final InteractionStore store;
    private final byte[] publicAddress;
    private final DatagramSocket udp;
    private final ServerSocket tcp;
    private final ExecutorService workers;
    private volatile boolean running=true;
    private final Thread udpThread,tcpThread;
    private final java.util.Set<Socket> clients=java.util.concurrent.ConcurrentHashMap.newKeySet();
    public DnsServer(String bind,int port,byte[] publicAddress,InteractionStore store,ExecutorService workers) throws IOException {
        if(publicAddress.length!=4) throw new IOException("DNS public address must be IPv4");
        this.store=store;this.publicAddress=publicAddress.clone();this.workers=workers;
        udp=new DatagramSocket(new InetSocketAddress(bind,port));
        try {tcp=new ServerSocket();tcp.bind(new InetSocketAddress(bind,udp.getLocalPort()),32);}
        catch(IOException e){udp.close();throw e;}
        udpThread=new Thread(this::udpLoop,"sprig-oast-dns-udp");tcpThread=new Thread(this::tcpLoop,"sprig-oast-dns-tcp");
        udpThread.setDaemon(true);tcpThread.setDaemon(true);udpThread.start();tcpThread.start();
    }
    public int port(){return udp.getLocalPort();}
    private void udpLoop(){
        byte[] buffer=new byte[4096];
        while(running) try {
            DatagramPacket request=new DatagramPacket(buffer,buffer.length);udp.receive(request);
            byte[] answer=answer(java.util.Arrays.copyOf(request.getData(),request.getLength()),request.getAddress().getHostAddress());
            if(answer!=null)udp.send(new DatagramPacket(answer,answer.length,request.getSocketAddress()));
        }catch(IOException|RuntimeException ignored){if(!running)return;}
    }
    private void tcpLoop(){while(running)try{
        Socket socket=tcp.accept();socket.setSoTimeout(10000);clients.add(socket);java.util.concurrent.ScheduledFuture<?> deadline=SocketDeadline.closeAfter(socket,10000);
        try{workers.execute(()->{try(Socket peer=socket){
            java.io.DataInputStream input=new java.io.DataInputStream(peer.getInputStream());int length=input.readUnsignedShort();
            if(length<12||length>4096)return;byte[] query=new byte[length];input.readFully(query);
            byte[] answer=answer(query,peer.getInetAddress().getHostAddress());
            if(answer!=null){DataOutputStream output=new DataOutputStream(peer.getOutputStream());output.writeShort(answer.length);output.write(answer);output.flush();}
        }catch(IOException|RuntimeException ignored){}finally{deadline.cancel(false);clients.remove(socket);}});}catch(java.util.concurrent.RejectedExecutionException e){deadline.cancel(false);clients.remove(socket);socket.close();}
    }catch(IOException ignored){if(!running)return;}}
    byte[] answer(byte[] query,String remote) throws IOException {
        if(query.length<12||(query[2]&0x80)!=0||u16(query,4)!=1)return null;
        Name name=readName(query,12,0);int end=name.end+4;if(end>query.length)return null;
        int type=u16(query,name.end),clazz=u16(query,name.end+2);
        String host=name.value.toLowerCase(Locale.ROOT),domain=store.domain();
        boolean scoped=host.equals(domain)||host.endsWith("."+domain);
        InteractionStore.Session session=scoped?store.forHost(host):null;
        if(session!=null)store.record(session,"DNS",remote,host+" type="+type,Json.encode(Json.map("query",host,"type",type,"transport","DNS")));
        boolean exists=session!=null||host.equals(domain)||host.equals("ns."+domain)||host.equals("mail."+domain);
        int rcode=!scoped?5:!exists?3:0;
        byte[] value=null;
        if(rcode==0&&clazz==1&&type==1)value=publicAddress;
        else if(rcode==0&&clazz==1&&type==2)value=encodedName("ns."+domain);
        else if(rcode==0&&clazz==1&&type==6)value=soa(domain);
        else if(rcode==0&&clazz==1&&type==15){ByteArrayOutputStream mx=new ByteArrayOutputStream();DataOutputStream fields=new DataOutputStream(mx);fields.writeShort(10);fields.write(encodedName("mail."+domain));value=mx.toByteArray();}
        else if(rcode==0&&clazz==1&&type==16){byte[] text="Sprig OAST".getBytes(StandardCharsets.US_ASCII);value=new byte[text.length+1];value[0]=(byte)text.length;System.arraycopy(text,0,value,1,text.length);}
        boolean negativeAuthority=scoped&&clazz==1&&value==null;
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeShort(u16(query,0));out.writeShort(0x8000|0x0400|(u16(query,2)&0x0100)|rcode);
        out.writeShort(1);out.writeShort(value==null?0:1);out.writeShort(negativeAuthority?1:0);out.writeShort(0);
        out.write(encodedName(host));out.writeShort(type);out.writeShort(clazz);
        if(value!=null){out.writeShort(0xc00c);out.writeShort(type);out.writeShort(1);out.writeInt(30);out.writeShort(value.length);out.write(value);}
        if(negativeAuthority){byte[] soa=soa(domain);out.write(encodedName(domain));out.writeShort(6);out.writeShort(1);out.writeInt(30);out.writeShort(soa.length);out.write(soa);}
        return bytes.toByteArray();
    }
    private static byte[] soa(String domain)throws IOException{ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream fields=new DataOutputStream(bytes);fields.write(encodedName("ns."+domain));fields.write(encodedName("hostmaster."+domain));fields.writeInt(2026111401);fields.writeInt(3600);fields.writeInt(600);fields.writeInt(604800);fields.writeInt(60);return bytes.toByteArray();}
    private static final class Name{final String value;final int end;Name(String value,int end){this.value=value;this.end=end;}}
    private static Name readName(byte[] data,int offset,int depth)throws IOException{
        if(depth>8)throw new IOException("DNS pointer recursion");StringBuilder name=new StringBuilder();int p=offset,end=-1,total=0;
        while(true){if(p>=data.length)throw new IOException("DNS name truncated");int length=data[p++]&255;
            if(length==0){if(end<0)end=p;break;}
            if((length&0xc0)==0xc0){if(p>=data.length)throw new IOException("DNS pointer truncated");int pointer=((length&63)<<8)|(data[p++]&255);if(pointer>=data.length)throw new IOException("DNS pointer out of bounds");Name rest=readName(data,pointer,depth+1);if(name.length()>0)name.append('.');name.append(rest.value);end=p;break;}
            if(length>63||p+length>data.length||(total+=length+1)>254)throw new IOException("Invalid DNS label");
            if(name.length()>0)name.append('.');for(int i=0;i<length;i++){int c=data[p++]&255;if(c<33||c>126||c=='.')throw new IOException("Invalid DNS name");name.append((char)c);}
        }return new Name(name.toString(),end);
    }
    static byte[] encodedName(String name)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();for(String label:name.split("\\.")){byte[] bytes=label.getBytes(StandardCharsets.US_ASCII);if(bytes.length<1||bytes.length>63)throw new IOException("Invalid DNS domain");out.write(bytes.length);out.write(bytes);}out.write(0);return out.toByteArray();}
    private static int u16(byte[] data,int p){return((data[p]&255)<<8)|(data[p+1]&255);}
    @Override public void close(){running=false;udp.close();try{tcp.close();}catch(IOException ignored){}for(Socket socket:clients)try{socket.close();}catch(IOException ignored){}clients.clear();}
}
