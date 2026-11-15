package com.ccs.sprig.core.intruder;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Random-access, immutable payload generation. Numeric ranges are never materialized. */
public interface PayloadSource {
    long size();
    byte[] valueAt(long index);
    /** Conservative retained-memory estimate, including list/array overhead. */
    default long estimatedBytes(){return 0;}
    static PayloadSource ofBytes(List<byte[]> values) { return new ListSource(values); }
    static PayloadSource ofLines(String text) { return new ListSource(text,false); }
    static PayloadSource ofHexLines(String text) { return new ListSource(text,true); }
    static PayloadSource numeric(long start,long end,long step,int zeroPad) { return new NumericSource(start,end,step,zeroPad); }

    final class ListSource implements PayloadSource {
        static final int MAX_VALUES=100000;
        static final long MAX_MEMORY=8L*1024*1024,BASE_MEMORY=256,VALUE_OVERHEAD=64;
        private final byte[][] values;
        private final long memory;
        ListSource(List<byte[]> source) {
            if(source==null||source.isEmpty()||source.size()>MAX_VALUES)throw new IllegalArgumentException("Use 1–100000 list payloads");
            values=new byte[source.size()][];long bytes=BASE_MEMORY;
            for(int i=0;i<values.length;i++) {
                byte[] value=source.get(i);if(value==null||value.length>PayloadProcessors.MAX_BYTES)throw new IllegalArgumentException("Payload exceeds 64 KiB");
                bytes=addMemory(bytes,value.length);values[i]=value;
            }
            // Validate the whole list before duplicating any payload storage.
            for(int i=0;i<values.length;i++)values[i]=values[i].clone();
            memory=bytes;
        }
        ListSource(String text,boolean hex) {
            if(text==null)throw new IllegalArgumentException("Payload text is required");
            int count=0,start=0;long bytes=BASE_MEMORY;
            // Preflight allocates no split strings or payload arrays, even for newline-only imports.
            for(;;) {
                if(++count>MAX_VALUES)throw new IllegalArgumentException("Use 1–100000 list payloads");
                int newline=text.indexOf('\n',start),end=newline<0?text.length():newline;
                if(newline>=0&&end>start&&text.charAt(end-1)=='\r')end--;
                int length=hex?PayloadProcessors.hexLength(text,start,end):utf8Length(text,start,end);
                bytes=addMemory(bytes,length);
                if(newline<0)break;start=newline+1;
            }
            values=new byte[count][];start=0;
            for(int i=0;i<count;i++) {
                int newline=text.indexOf('\n',start),end=newline<0?text.length():newline;
                if(newline>=0&&end>start&&text.charAt(end-1)=='\r')end--;
                values[i]=hex?PayloadProcessors.parseHex(text,start,end):text.substring(start,end).getBytes(StandardCharsets.UTF_8);
                start=newline+1;
            }
            memory=bytes;
        }
        private static long addMemory(long current,int length) {
            long next=current+VALUE_OVERHEAD+length;
            if(next>MAX_MEMORY)throw new IllegalArgumentException("Payload lists exceed 8 MiB including array overhead");return next;
        }
        private static int utf8Length(String text,int start,int end) {
            int length=0;
            for(int i=start;i<end;i++) {
                char c=text.charAt(i);
                if(c<0x80)length++;
                else if(c<0x800)length+=2;
                else if(Character.isHighSurrogate(c)&&i+1<end&&Character.isLowSurrogate(text.charAt(i+1))){length+=4;i++;}
                else if(Character.isSurrogate(c))length++; // UTF-8 encoder replaces an unpaired surrogate with '?'.
                else length+=3;
                if(length>PayloadProcessors.MAX_BYTES)throw new IllegalArgumentException("Payload exceeds 64 KiB");
            }
            return length;
        }
        @Override public long size(){return values.length;}
        @Override public long estimatedBytes(){return memory;}
        @Override public byte[] valueAt(long index){if(index<0||index>=values.length)throw new IndexOutOfBoundsException();return values[(int)index].clone();}
    }
    final class NumericSource implements PayloadSource {
        private final BigInteger start,step;private final long count;private final int width;
        NumericSource(long from,long to,long increment,int zeroPad) {
            if(increment==0||increment>0&&from>to||increment<0&&from<to)throw new IllegalArgumentException("Numeric step must approach the inclusive end");
            if(zeroPad<0||zeroPad>32)throw new IllegalArgumentException("Numeric padding must be 0–32 digits");
            start=BigInteger.valueOf(from);step=BigInteger.valueOf(increment);width=zeroPad;
            BigInteger size=BigInteger.valueOf(to).subtract(start).abs().divide(step.abs()).add(BigInteger.ONE);
            if(size.compareTo(BigInteger.valueOf(Long.MAX_VALUE))>0)throw new IllegalArgumentException("Numeric payload count overflows 64 bits");count=size.longValueExact();
        }
        @Override public long size(){return count;}
        @Override public long estimatedBytes(){return 512;}
        @Override public byte[] valueAt(long index) {
            if(index<0||index>=count)throw new IndexOutOfBoundsException();
            String text=start.add(step.multiply(BigInteger.valueOf(index))).toString();
            boolean negative=text.startsWith("-");String digits=negative?text.substring(1):text;
            StringBuilder padded=new StringBuilder();if(negative)padded.append('-');for(int i=digits.length();i<width;i++)padded.append('0');padded.append(digits);
            return padded.toString().getBytes(StandardCharsets.US_ASCII);
        }
    }
}
