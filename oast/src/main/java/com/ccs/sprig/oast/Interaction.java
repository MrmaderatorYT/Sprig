package com.ccs.sprig.oast;

import java.util.Map;

public final class Interaction {
    public final long sequence,timeMillis;
    public final String protocol,remoteAddress,summary,details;
    public Interaction(long sequence,long timeMillis,String protocol,String remoteAddress,String summary,String details) {
        this.sequence=sequence;this.timeMillis=timeMillis;this.protocol=protocol;this.remoteAddress=remoteAddress;this.summary=summary;this.details=details;
    }
    public Map<String,Object> json() {return Json.map("sequence",sequence,"timeMillis",timeMillis,"protocol",protocol,"remoteAddress",remoteAddress,"summary",summary,"details",details);}
    public static Interaction from(Map<String,Object> value) {
        return new Interaction(Json.number(value,"sequence"),Json.number(value,"timeMillis"),Json.string(value,"protocol"),Json.string(value,"remoteAddress"),Json.string(value,"summary"),Json.string(value,"details"));
    }
}
