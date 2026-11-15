package com.ccs.sprig.core.scanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Evidence-backed result; confidence distinguishes an observation from a suspected vulnerability. */
public final class Finding {
    public final long id, baselineExchangeId, probeExchangeId;
    public final String checkId, severity, confidence, title, detail, url, parameter, evidence;
    public final List<Long> evidenceExchangeIds;
    Finding(long id, String checkId, String severity, String confidence, String title, String detail,
            String url, String parameter, String evidence, long baselineExchangeId, long probeExchangeId) {
        this(id,checkId,severity,confidence,title,detail,url,parameter,evidence,baselineExchangeId,probeExchangeId,new long[0]);
    }
    Finding(long id,String checkId,String severity,String confidence,String title,String detail,String url,String parameter,
            String evidence,long baselineExchangeId,long probeExchangeId,long...additionalIds){
        this.id=id;this.checkId=checkId;this.severity=severity;this.confidence=confidence;
        this.title=title;this.detail=detail;this.url=url;this.parameter=parameter;
        this.evidence=evidence;this.baselineExchangeId=baselineExchangeId;this.probeExchangeId=probeExchangeId;
        Set<Long> ids=new LinkedHashSet<>();if(baselineExchangeId>0)ids.add(baselineExchangeId);if(probeExchangeId>0)ids.add(probeExchangeId);
        for(long value:additionalIds)if(value>0&&ids.size()<16)ids.add(value);
        evidenceExchangeIds=Collections.unmodifiableList(new ArrayList<>(ids));
    }
    Finding withId(long value) {long[] ids=new long[evidenceExchangeIds.size()];for(int i=0;i<ids.length;i++)ids[i]=evidenceExchangeIds.get(i);return new Finding(value,checkId,severity,confidence,title,detail,url,parameter,evidence,baselineExchangeId,probeExchangeId,ids);}
}
