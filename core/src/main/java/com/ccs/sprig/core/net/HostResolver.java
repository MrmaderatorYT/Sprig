package com.ccs.sprig.core.net;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded, deadline-aware platform DNS. Does not replace the platform cache or TLS peer name. */
public final class HostResolver implements AutoCloseable {
    private static final HostResolver DEFAULT=new HostResolver(InetAddress::getAllByName,12000);
    @FunctionalInterface interface Lookup {InetAddress[] addresses(String host)throws IOException;}
    private final Lookup lookup;
    private final long timeoutMillis;
    private final ThreadPoolExecutor workers;

    HostResolver(Lookup lookup,long timeoutMillis){
        if(lookup==null||timeoutMillis<1||timeoutMillis>120000)throw new IllegalArgumentException("Lookup and deadline of 1–120000 ms required");
        this.lookup=lookup;this.timeoutMillis=timeoutMillis;
        workers=new ThreadPoolExecutor(2,2,10,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),task->{Thread thread=new Thread(task,"sprig-dns");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
    }
    /** Uses the first platform address, preferring an IPv4 record when requested and available. */
    public static InetAddress resolve(String host,boolean preferIpv4)throws IOException{return DEFAULT.resolveHost(host,preferIpv4);}

    InetAddress resolveHost(String host,boolean preferIpv4)throws IOException{
        CallCancellation.check();
        String normalized=normalize(host);InetAddress literal=literal(normalized);if(literal!=null)return literal;
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        FutureTask<InetAddress[]> pending=new FutureTask<>(()->lookup.addresses(normalized));
        try{
            // Cancelling the Future wakes its waiting caller even if the OS resolver ignores interrupts.
            CallCancellation.track(()->pending.cancel(true));CallCancellation.check();
            workers.execute(pending);CallCancellation.check();
            InetAddress[] addresses=pending.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
            CallCancellation.check();InetAddress first=null;
            if(addresses!=null)for(InetAddress address:addresses){if(address==null)continue;if(first==null)first=address;if(preferIpv4&&address instanceof Inet4Address)return address;}
            if(first==null)throw new UnknownHostException(normalized+": no addresses returned");return first;
        }catch(RejectedExecutionException busy){throw new IOException("Host resolver is busy (2 lookups, 8 queued)",busy);}
        catch(CancellationException cancelled){InterruptedIOException error=new InterruptedIOException("Host lookup cancelled");error.initCause(cancelled);throw error;}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();InterruptedIOException error=new InterruptedIOException("Host lookup interrupted");error.initCause(interrupted);throw error;}
        catch(TimeoutException timeout){SocketTimeoutException error=new SocketTimeoutException("Host lookup timed out after "+timeoutMillis+" ms");error.initCause(timeout);throw error;}
        catch(ExecutionException failure){Throwable cause=failure.getCause();if(cause instanceof IOException)throw(IOException)cause;throw new IOException("Host lookup failed",cause);}
        finally{pending.cancel(true);workers.remove(pending);}
    }
    /** Package-local diagnostics keep resource-bound tests deterministic. */
    int queuedRequests(){return workers.getQueue().size();}
    int activeLookups(){return workers.getActiveCount();}
    @Override public void close(){workers.shutdownNow();}

    private static String normalize(String host)throws UnknownHostException{
        if(host==null||host.isEmpty()||!host.equals(host.trim())||host.length()>254)throw invalid(host);
        boolean bracketed=host.startsWith("[");
        if(bracketed){if(!host.endsWith("]")||host.indexOf(':')<0)throw invalid(host);host=host.substring(1,host.length()-1);}
        if(host.indexOf('[')>=0||host.indexOf(']')>=0)throw invalid(host);
        if(host.indexOf(':')>=0){if(host.indexOf('%')>=0||!host.matches("[0-9a-fA-F:.]+"))throw invalid(host);return host;}
        if(bracketed)throw invalid(host);
        // Ambiguous libc forms (integer, abbreviated, octal/hex) must never become a DNS fallback.
        if(host.matches("[0-9.]+")||host.matches("(?i)(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+))*\\.?"))return host;
        try{String ascii=IDN.toASCII(host,IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);if(ascii.length()>254||(ascii.length()>253&&!ascii.endsWith("."))||ascii.contains("..")||ascii.startsWith(".")||ascii.isEmpty())throw invalid(host);return ascii;}
        catch(IllegalArgumentException error){throw invalid(host);}
    }
    private static InetAddress literal(String host)throws UnknownHostException{
        if(host.indexOf(':')>=0)return Inet6Address.getByAddress(null,ipv6(host),-1);
        if(host.matches("[0-9.]+")||host.matches("(?i)(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+))*\\.?"))return InetAddress.getByAddress(ipv4(host));
        return null;
    }
    private static byte[] ipv4(String host)throws UnknownHostException{
        String[] parts=host.split("\\.",-1);if(parts.length!=4)throw invalid(host);byte[] bytes=new byte[4];
        for(int i=0;i<parts.length;i++){String part=parts[i];if(!part.matches("[0-9]{1,3}")||(part.length()>1&&part.charAt(0)=='0'))throw invalid(host);int value=Integer.parseInt(part);if(value>255)throw invalid(host);bytes[i]=(byte)value;}return bytes;
    }
    private static byte[] ipv6(String host)throws UnknownHostException{
        int compression=host.indexOf("::");List<Integer> left,right;int zeros;
        if(compression<0){left=groups(host,true);right=new ArrayList<>();if(left.size()!=8)throw invalid(host);zeros=0;}
        else{
            if(host.indexOf("::",compression+2)>=0)throw invalid(host);
            left=groups(host.substring(0,compression),false);right=groups(host.substring(compression+2),true);zeros=8-left.size()-right.size();if(zeros<1)throw invalid(host);
        }
        byte[] bytes=new byte[16];int p=0;for(int value:left){bytes[p++]=(byte)(value>>>8);bytes[p++]=(byte)value;}p+=zeros*2;for(int value:right){bytes[p++]=(byte)(value>>>8);bytes[p++]=(byte)value;}if(p!=16)throw invalid(host);return bytes;
    }
    private static List<Integer> groups(String text,boolean allowIpv4)throws UnknownHostException{
        List<Integer> values=new ArrayList<>();if(text.isEmpty())return values;String[] parts=text.split(":",-1);
        for(int i=0;i<parts.length;i++){
            String part=parts[i];if(part.indexOf('.')>=0){if(!allowIpv4||i!=parts.length-1)throw invalid(text);byte[] bytes=ipv4(part);values.add(((bytes[0]&255)<<8)|(bytes[1]&255));values.add(((bytes[2]&255)<<8)|(bytes[3]&255));}
            else{if(!part.matches("[0-9a-fA-F]{1,4}"))throw invalid(text);values.add(Integer.parseInt(part,16));}
            if(values.size()>8)throw invalid(text);
        }
        return values;
    }
    private static UnknownHostException invalid(String host){return new UnknownHostException("Invalid host or numeric IP literal: "+(host==null?"null":host.length()>100?host.substring(0,100)+"…":host));}
}
