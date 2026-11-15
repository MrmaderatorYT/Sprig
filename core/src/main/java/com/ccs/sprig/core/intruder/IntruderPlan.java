package com.ccs.sprig.core.intruder;

import com.ccs.sprig.core.proxy.HttpCodec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Lazy mixed-radix generation: no Cartesian product or request list is allocated. */
public final class IntruderPlan {
    private final IntruderConfig config;
    private final List<byte[]> segments=new ArrayList<>(),originals=new ArrayList<>();
    private final long combinations;
    public IntruderPlan(IntruderConfig config) {
        this.config=config;byte[] template=config.markedTemplate;int open=-1,last=0;
        for(int i=0;i<template.length;i++)if((template[i]&255)==0xa7) {
            if(open<0){segments.add(Arrays.copyOfRange(template,last,i));open=i;}
            else{originals.add(Arrays.copyOfRange(template,open+1,i));open=-1;last=i+1;}
        }
        if(open>=0||originals.isEmpty()||originals.size()>32)throw new IllegalArgumentException("Add 1–32 balanced §position§ pairs");
        segments.add(Arrays.copyOfRange(template,last,template.length));
        int expected=config.type==AttackType.SNIPER||config.type==AttackType.BATTERING_RAM?1:positions();
        if(config.payloadSets.size()!=expected)throw new IllegalArgumentException("This mode requires "+expected+" payload set(s)");
        for(PayloadSource source:config.payloadSets)if(source.size()<1)throw new IllegalArgumentException("Payload sets cannot be empty");
        try {
            if(config.type==AttackType.SNIPER)combinations=Math.multiplyExact(config.payloadSets.get(0).size(),positions());
            else if(config.type==AttackType.BATTERING_RAM)combinations=config.payloadSets.get(0).size();
            else if(config.type==AttackType.PITCHFORK){long count=Long.MAX_VALUE;for(PayloadSource source:config.payloadSets)count=Math.min(count,source.size());combinations=count;}
            else{long count=1;for(PayloadSource source:config.payloadSets)count=Math.multiplyExact(count,source.size());combinations=count;}
        }catch(ArithmeticException overflow){throw new IllegalArgumentException("Attack combination count overflows 64 bits",overflow);}
        byte[] baseline=render(originals);
        String first=new String(baseline,0,Math.min(baseline.length,64),java.nio.charset.StandardCharsets.ISO_8859_1);
        if(!first.matches("(?s)[!#$%&'*+.^_`|~0-9A-Za-z-]+ .*"))throw new IllegalArgumentException("Template must begin with an HTTP request line");
    }
    public int positions(){return originals.size();}public long combinationCount(){return combinations;}public long requestCount(){return Math.min(combinations,config.requestBudget);}
    public Case caseAt(long index)throws IOException {
        if(index<0||index>=requestCount())throw new IndexOutOfBoundsException();
        List<byte[]> replacements=new ArrayList<>(originals);List<byte[]> selected=new ArrayList<>();int position=-1;
        if(config.type==AttackType.SNIPER){long size=config.payloadSets.get(0).size();position=(int)(index/size);byte[] payload=processed(0,index%size);replacements.set(position,payload);selected.add(payload);}
        else if(config.type==AttackType.BATTERING_RAM){byte[] payload=processed(0,index);for(int i=0;i<positions();i++)replacements.set(i,payload);selected.add(payload);}
        else if(config.type==AttackType.PITCHFORK){for(int i=0;i<positions();i++){byte[] payload=processed(i,index);replacements.set(i,payload);selected.add(payload);}}
        else {long remaining=index;long[] indices=new long[positions()];for(int i=positions()-1;i>=0;i--){long size=config.payloadSets.get(i).size();indices[i]=remaining%size;remaining/=size;}for(int i=0;i<positions();i++){byte[] payload=processed(i,indices[i]);replacements.set(i,payload);selected.add(payload);}}
        return new Case(index,position,render(replacements),selected);
    }
    private byte[] processed(int set,long index)throws IOException {byte[] value=PayloadProcessors.bounded(config.payloadSets.get(set).valueAt(index));for(PayloadProcessor processor:config.setProcessors.getOrDefault(set,Collections.emptyList()))value=PayloadProcessors.bounded(processor.apply(value));for(PayloadProcessor processor:config.processors)value=PayloadProcessors.bounded(processor.apply(value));return value;}
    private byte[] render(List<byte[]> replacements) {
        long length=0;for(byte[] segment:segments)length+=segment.length;for(byte[] payload:replacements)length+=payload.length;
        if(length>HttpCodec.MAX_BODY+HttpCodec.MAX_HEADERS)throw new IllegalArgumentException("Generated request exceeds inspection limit");
        byte[] result=new byte[(int)length];int offset=0;for(int i=0;i<segments.size();i++){byte[] segment=segments.get(i);System.arraycopy(segment,0,result,offset,segment.length);offset+=segment.length;if(i<replacements.size()){byte[] payload=replacements.get(i);System.arraycopy(payload,0,result,offset,payload.length);offset+=payload.length;}}return result;
    }
    public static final class Case {
        public final long index;public final int position;public final byte[] rawRequest;public final List<byte[]> payloads;
        Case(long index,int position,byte[] request,List<byte[]> payloads){this.index=index;this.position=position;rawRequest=request;this.payloads=Collections.unmodifiableList(payloads);}
    }
}
