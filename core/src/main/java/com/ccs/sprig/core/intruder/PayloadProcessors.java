package com.ccs.sprig.core.intruder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/** Byte-preserving processors. Case conversion is intentionally ASCII-only. */
public final class PayloadProcessors {
    public static final int MAX_BYTES=64*1024;
    private static final byte[] HEX="0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);
    private PayloadProcessors(){}
    public static PayloadProcessor urlEncode(){return input->{long length=0;for(byte b:input)length+=unreserved(b&255)?1:3;bound(length);byte[] out=new byte[(int)length];int p=0;for(byte b:input){int n=b&255;if(unreserved(n))out[p++]=b;else{out[p++]='%';out[p++]=HEX[n>>>4];out[p++]=HEX[n&15];}}return out;};}
    public static PayloadProcessor urlDecode(){return input->{ByteArrayOutputStream out=new ByteArrayOutputStream(input.length);for(int i=0;i<input.length;i++){int c=input[i]&255;if(c=='%'){if(i+2>=input.length)throw new IOException("Incomplete URL escape");int a=Character.digit((char)input[++i],16),b=Character.digit((char)input[++i],16);if(a<0||b<0)throw new IOException("Invalid URL escape");out.write((a<<4)|b);}else out.write(c);}return out.toByteArray();};}
    public static PayloadProcessor base64Encode(){return input->{bound(4L*((input.length+2)/3));return Base64.getEncoder().encode(input);};}
    public static PayloadProcessor base64Decode(){return input->{try{return bounded(Base64.getDecoder().decode(input));}catch(IllegalArgumentException e){throw new IOException("Invalid Base64 payload",e);}};}
    public static PayloadProcessor hexEncode(){return input->{bound(2L*input.length);byte[] out=new byte[input.length*2];for(int i=0;i<input.length;i++){out[i*2]=HEX[(input[i]&255)>>>4];out[i*2+1]=HEX[input[i]&15];}return out;};}
    public static PayloadProcessor hexDecode(){return input->{try{return parseHex(new String(input,StandardCharsets.US_ASCII));}catch(IllegalArgumentException e){throw new IOException(e.getMessage(),e);}};}
    public static PayloadProcessor upperCase(){return input->{byte[] out=input.clone();for(int i=0;i<out.length;i++)if(out[i]>='a'&&out[i]<='z')out[i]-=32;return out;};}
    public static PayloadProcessor lowerCase(){return input->{byte[] out=input.clone();for(int i=0;i<out.length;i++)if(out[i]>='A'&&out[i]<='Z')out[i]+=32;return out;};}
    public static PayloadProcessor prefix(byte[] prefix){if(prefix==null||prefix.length>MAX_BYTES)throw new IllegalArgumentException("Prefix exceeds 64 KiB");byte[] value=prefix.clone();return withMemory(160L+value.length,input->join(value,input));}
    public static PayloadProcessor suffix(byte[] suffix){if(suffix==null||suffix.length>MAX_BYTES)throw new IllegalArgumentException("Suffix exceeds 64 KiB");byte[] value=suffix.clone();return withMemory(160L+value.length,input->join(input,value));}
    public static PayloadProcessor replace(byte[] find,byte[] replacement) {
        if(find==null||find.length==0||find.length>MAX_BYTES||replacement==null||replacement.length>MAX_BYTES)throw new IllegalArgumentException("Replacement buffers must be non-null, search nonempty, and each at most 64 KiB");
        byte[] needle=find.clone(),value=replacement.clone();
        return withMemory(192L+needle.length+value.length,input->{int matches=0;for(int p=0;p<=input.length-needle.length;)if(matches(input,p,needle)){matches++;p+=needle.length;}else p++;
            long length=input.length+(long)matches*(value.length-needle.length);bound(length);ByteArrayOutputStream out=new ByteArrayOutputStream((int)length);
            for(int p=0;p<input.length;)if(p<=input.length-needle.length&&matches(input,p,needle)){out.write(value,0,value.length);p+=needle.length;}else out.write(input[p++]);return out.toByteArray();});
    }
    private static PayloadProcessor withMemory(long bytes,PayloadProcessor operation){return new PayloadProcessor(){public byte[] apply(byte[] input)throws IOException{return operation.apply(input);}public long estimatedBytes(){return bytes;}};}
    public static byte[] parseHex(String text) {
        if(text==null)throw new IllegalArgumentException("Hexadecimal text is required");return parseHex(text,0,text.length());
    }
    static byte[] parseHex(String text,int start,int end) {
        byte[] out=new byte[hexLength(text,start,end)];int high=-1,p=0;
        for(int i=start;i<end;i++){char c=text.charAt(i);if(hexWhitespace(c))continue;int digit=Character.digit(c,16);if(high<0)high=digit;else{out[p++]=(byte)((high<<4)|digit);high=-1;}}return out;
    }
    static int hexLength(String text,int start,int end) {
        int digits=0;for(int i=start;i<end;i++){char c=text.charAt(i);if(hexWhitespace(c))continue;if(Character.digit(c,16)<0)throw new IllegalArgumentException("Invalid hexadecimal payload");if(++digits>MAX_BYTES*2)throw new IllegalArgumentException("Use complete hex byte pairs, at most 64 KiB");}
        if((digits&1)!=0)throw new IllegalArgumentException("Use complete hex byte pairs, at most 64 KiB");return digits/2;
    }
    private static boolean hexWhitespace(char c){return c==' '||c>='\t'&&c<='\r';}
    static byte[] bounded(byte[] bytes)throws IOException{bound(bytes.length);return bytes;}
    private static byte[] join(byte[] a,byte[] b)throws IOException{bound((long)a.length+b.length);byte[] out=Arrays.copyOf(a,a.length+b.length);System.arraycopy(b,0,out,a.length,b.length);return out;}
    private static void bound(long length)throws IOException{if(length<0||length>MAX_BYTES)throw new IOException("Processed payload exceeds 64 KiB");}
    private static boolean unreserved(int c){return c>='A'&&c<='Z'||c>='a'&&c<='z'||c>='0'&&c<='9'||c=='-'||c=='_'||c=='.'||c=='~';}
    private static boolean matches(byte[] bytes,int offset,byte[] needle){for(int i=0;i<needle.length;i++)if(bytes[offset+i]!=needle[i])return false;return true;}
}
