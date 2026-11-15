package com.ccs.sprig.core.scanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Stable check identifiers form the configuration/report boundary. Extended probes are opt-in. */
public final class CheckRegistry {
    public static final class Definition {
        public final String id,title,description;
        public final boolean active,defaultEnabled;
        private Definition(String id,String title,String description,boolean active,boolean enabled){this.id=id;this.title=title;this.description=description;this.active=active;defaultEnabled=enabled;}
    }
    private static final List<Definition> CHECKS;
    static {
        List<Definition> checks=new ArrayList<>();
        checks.add(new Definition("headers","Security headers and cookies","Passive response hardening observations.",false,true));
        checks.add(new Definition("reflection","Contextual reflection","Harmless marker reflection; reports HTML/attribute/JavaScript context, never execution.",true,true));
        checks.add(new Definition("sql-error","SQL error differential","Apostrophe and benign control; no extraction or time-delay SQL.",true,true));
        checks.add(new Definition("redirect","External redirect","Unique reserved-domain target; external Location is not followed.",true,true));
        checks.add(new Definition("cors","Credentialed CORS","Two distinct origins must both be reflected with credentials enabled.",true,true));
        checks.add(new Definition("template","Template arithmetic","Two harmless arithmetic expressions and a literal control; no function calls or commands.",true,false));
        checks.add(new Definition("crlf","Response header injection","Encoded line-break marker compared with a harmless control.",true,false));
        checks.add(new Definition("host-header","Forwarded-host reflection","Unique X-Forwarded-Host markers with no-cache headers; no shared-cache retrieval or poisoning.",true,false));
        checks.add(new Definition("methods","OPTIONS and TRACE","Bounded method discovery and credential-free TRACE marker reflection.",true,false));
        checks.add(new Definition("sql-boolean","Boolean SQL differential","Numeric points only; repeated baseline/true/false pairs. Tentative lead, no delays or extraction.",true,false));
        checks.add(new Definition("cache","Cache policy observations","Cache headers on authenticated or sensitive responses; no cache poisoning.",false,false));
        checks.add(new Definition("disclosure","Sensitive data and debug disclosures","Private-key blocks are redacted; stack traces and debug pages are heuristic observations.",false,false));
        checks.add(new Definition("directory","Directory listing","Recognizable index title and parent-directory indicators.",false,false));
        checks.add(new Definition("csrf","CSRF form audit","State-changing forms and apparent token absence; no form submission.",false,false));
        checks.add(new Definition("dom","DOM source observations","Source and dangerous-sink patterns; does not execute JavaScript or prove dataflow.",false,false));
        checks.add(new Definition("oast","User-provided callback probe","Submits only your configured callback address; an HTTP response is not proof of a callback.",true,false));
        CHECKS=Collections.unmodifiableList(checks);
    }
    private CheckRegistry(){ }
    public static List<Definition> all(){return CHECKS;}
    public static Set<String> defaultIds(){Set<String> ids=new LinkedHashSet<>();for(Definition d:CHECKS)if(d.defaultEnabled)ids.add(d.id);return ids;}
    public static boolean known(String id){for(Definition d:CHECKS)if(d.id.equals(id))return true;return false;}
}
