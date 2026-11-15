package com.ccs.sprig.collaborator;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/** Callback sink, never an SMTP relay. Bounded commands, DATA and connection lifetime. */
public final class SmtpServer implements Closeable {
    private final InteractionStore store;
    private final ServerSocket server;
    private final ExecutorService workers;
    private final SSLContext tls;
    private final boolean implicitTls;
    private final Set<Socket> clients=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean running=true;
    public SmtpServer(String bind,int port,InteractionStore store,ExecutorService workers,SSLContext tls,boolean implicitTls)throws IOException{
        this.store=store;this.workers=workers;this.tls=tls;this.implicitTls=implicitTls;
        server=implicitTls?tls.getServerSocketFactory().createServerSocket():new ServerSocket();server.bind(new InetSocketAddress(bind,port),32);
        Thread thread=new Thread(this::accept,"sprig-oast-smtp");thread.setDaemon(true);thread.start();
    }
    public int port(){return server.getLocalPort();}
    private void accept(){while(running)try{Socket socket=server.accept();socket.setSoTimeout(10000);clients.add(socket);java.util.concurrent.ScheduledFuture<?> deadline=SocketDeadline.closeAfter(socket,30000);try{workers.execute(()->{try{handle(socket);}finally{deadline.cancel(false);clients.remove(socket);}});}catch(java.util.concurrent.RejectedExecutionException e){deadline.cancel(false);clients.remove(socket);socket.close();}}catch(IOException ignored){if(!running)return;}}
    private void handle(Socket original){Socket socket=original;try{
        if(socket instanceof SSLSocket)((SSLSocket)socket).startHandshake();
        BufferedReader input=reader(socket);BufferedWriter output=writer(socket);write(output,"220 "+store.domain()+" Sprig callback sink");
        Set<InteractionStore.Session> recipients=new LinkedHashSet<>();boolean encrypted=implicitTls,mail=false;
        long deadline=System.nanoTime()+30_000_000_000L;
        for(int command=0;command<100&&System.nanoTime()<deadline;command++){
            String line=line(input,2048);if(line==null)return;String upper=line.toUpperCase(Locale.ROOT);
            if(upper.startsWith("EHLO")||upper.startsWith("HELO")){
                if(tls!=null&&!encrypted)write(output,"250-"+store.domain()+"\r\n250-STARTTLS\r\n250 SIZE 8192");else write(output,"250 "+store.domain());
            }else if(upper.equals("STARTTLS")&&tls!=null&&!encrypted){
                write(output,"220 Start TLS");SSLSocket secure=(SSLSocket)tls.getSocketFactory().createSocket(socket,socket.getInetAddress().getHostAddress(),socket.getPort(),true);
                secure.setUseClientMode(false);secure.setSoTimeout(10000);secure.startHandshake();socket=secure;encrypted=true;input=reader(socket);output=writer(socket);recipients.clear();mail=false;
            }else if(upper.startsWith("MAIL FROM:")){mail=true;recipients.clear();write(output,"250 Sender accepted");
            }else if(upper.startsWith("RCPT TO:")&&mail){
                String address=line.substring(8).trim();int at=address.lastIndexOf('@');String host=at>=0?address.substring(at+1).replace(">","").trim():"";
                InteractionStore.Session session=store.forHost(host);
                if(session!=null){recipients.add(session);store.record(session,encrypted?"SMTPS":"SMTP",socket.getInetAddress().getHostAddress(),"RCPT "+host,line);write(output,"250 Recipient accepted");}else write(output,"550 Unknown callback recipient");
            }else if(upper.equals("DATA")&&!recipients.isEmpty()){
                write(output,"354 End with <CRLF>.<CRLF>");StringBuilder data=new StringBuilder();boolean ended=false;
                for(int n=0;n<1024;n++){String part=line(input,2048);if(part==null)return;if(part.equals(".")){ended=true;break;}if(data.length()+part.length()+2>8192){write(output,"552 Callback message exceeds 8192 bytes");return;}data.append(part.startsWith("..")?part.substring(1):part).append("\r\n");}
                if(!ended)return;for(InteractionStore.Session session:recipients)store.record(session,encrypted?"SMTPS":"SMTP",socket.getInetAddress().getHostAddress(),"SMTP DATA",data.toString());
                write(output,"250 Callback recorded; message is not relayed");recipients.clear();mail=false;
            }else if(upper.equals("QUIT")){write(output,"221 Bye");return;
            }else if(upper.equals("RSET")){recipients.clear();mail=false;write(output,"250 Reset");
            }else if(upper.equals("NOOP"))write(output,"250 OK");else write(output,"502 Command not supported");
        }
    }catch(IOException|RuntimeException ignored){}finally{try{socket.close();}catch(IOException ignored){}if(socket!=original)try{original.close();}catch(IOException ignored){}}}
    private static BufferedReader reader(Socket s)throws IOException{return new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.US_ASCII),2048);}
    private static BufferedWriter writer(Socket s)throws IOException{return new BufferedWriter(new OutputStreamWriter(s.getOutputStream(),StandardCharsets.US_ASCII));}
    private static void write(BufferedWriter output,String line)throws IOException{output.write(line);output.write("\r\n");output.flush();}
    private static String line(BufferedReader input,int limit)throws IOException{StringBuilder result=new StringBuilder();for(int n=0;n<=limit;n++){int c=input.read();if(c<0)return result.length()==0?null:result.toString();if(c=='\n')return result.toString();if(c!='\r')result.append((char)c);}throw new IOException("SMTP line too long");}
    @Override public void close(){running=false;try{server.close();}catch(IOException ignored){}for(Socket socket:clients)try{socket.close();}catch(IOException ignored){}clients.clear();}
}
